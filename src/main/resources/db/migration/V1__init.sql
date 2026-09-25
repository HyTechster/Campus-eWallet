-- Campus e-wallet schema. Amounts are BIGINT sen (RM 12.50 = 1250).

CREATE TABLE users (
    id            BIGSERIAL    PRIMARY KEY,
    email         VARCHAR(255) NOT NULL UNIQUE,
    password_hash VARCHAR(100) NOT NULL,
    full_name     VARCHAR(120) NOT NULL,
    student_id    VARCHAR(30)  UNIQUE,
    role          VARCHAR(16)  NOT NULL CHECK (role IN ('USER', 'MERCHANT', 'ADMIN')),
    status        VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'FROZEN')),
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT users_email_lowercase CHECK (email = lower(email))
);

CREATE TABLE merchants (
    id         BIGSERIAL   PRIMARY KEY,
    user_id    BIGINT      NOT NULL UNIQUE REFERENCES users (id),
    code       VARCHAR(20) NOT NULL UNIQUE CHECK (code = upper(code)),
    name       VARCHAR(80) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE accounts (
    id            BIGSERIAL   PRIMARY KEY,
    owner_user_id BIGINT      REFERENCES users (id),
    type          VARCHAR(20) NOT NULL CHECK (type IN ('USER_WALLET', 'MERCHANT_WALLET', 'SYSTEM_TOPUP')),
    balance       BIGINT      NOT NULL DEFAULT 0,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- User and merchant wallets can never go below zero. SYSTEM_TOPUP may.
    CONSTRAINT accounts_balance_non_negative CHECK (type = 'SYSTEM_TOPUP' OR balance >= 0),
    -- System accounts have no owner; wallets always have one.
    CONSTRAINT accounts_owner_matches_type CHECK ((type = 'SYSTEM_TOPUP') = (owner_user_id IS NULL))
);

-- One wallet per user, one SYSTEM_TOPUP account.
CREATE UNIQUE INDEX accounts_one_per_owner ON accounts (owner_user_id) WHERE owner_user_id IS NOT NULL;
CREATE UNIQUE INDEX accounts_one_system_topup ON accounts (type) WHERE type = 'SYSTEM_TOPUP';

CREATE TABLE journal_entries (
    id                BIGSERIAL    PRIMARY KEY,
    type              VARCHAR(16)  NOT NULL CHECK (type IN ('TOPUP', 'TRANSFER', 'PAYMENT', 'REVERSAL')),
    idempotency_key   UUID         NOT NULL UNIQUE,
    description       VARCHAR(200),
    created_by        BIGINT       REFERENCES users (id),
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    -- An entry can be reversed at most once.
    reverses_entry_id BIGINT       UNIQUE REFERENCES journal_entries (id),
    CONSTRAINT journal_entries_reversal_link CHECK ((type = 'REVERSAL') = (reverses_entry_id IS NOT NULL))
);

CREATE INDEX journal_entries_created_at ON journal_entries (created_at);

CREATE TABLE ledger_lines (
    id            BIGSERIAL PRIMARY KEY,
    entry_id      BIGINT    NOT NULL REFERENCES journal_entries (id),
    account_id    BIGINT    NOT NULL REFERENCES accounts (id),
    amount        BIGINT    NOT NULL CHECK (amount <> 0),
    balance_after BIGINT    NOT NULL
);

CREATE INDEX ledger_lines_entry ON ledger_lines (entry_id);
CREATE INDEX ledger_lines_account ON ledger_lines (account_id, id);

-- The ledger is append-only. Mistakes are fixed with a reversing entry.
CREATE FUNCTION reject_ledger_change() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'ledger is append-only: % on % is not allowed', TG_OP, TG_TABLE_NAME;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER journal_entries_append_only
    BEFORE UPDATE OR DELETE ON journal_entries
    FOR EACH ROW EXECUTE FUNCTION reject_ledger_change();

CREATE TRIGGER ledger_lines_append_only
    BEFORE UPDATE OR DELETE ON ledger_lines
    FOR EACH ROW EXECUTE FUNCTION reject_ledger_change();

-- Backstop for LedgerService: at commit, every entry that got lines must sum to exactly 0.
CREATE FUNCTION check_entry_balanced() RETURNS trigger AS $$
DECLARE
    total BIGINT;
BEGIN
    SELECT COALESCE(SUM(amount), 0) INTO total FROM ledger_lines WHERE entry_id = NEW.entry_id;
    IF total <> 0 THEN
        RAISE EXCEPTION 'journal entry % does not balance (sum = %)', NEW.entry_id, total;
    END IF;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE CONSTRAINT TRIGGER ledger_lines_entry_balanced
    AFTER INSERT ON ledger_lines
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION check_entry_balanced();

-- The single source of every top-up.
INSERT INTO accounts (owner_user_id, type, balance) VALUES (NULL, 'SYSTEM_TOPUP', 0);
