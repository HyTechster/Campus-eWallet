-- Dev seed. Only runs with the dev profile (see application.yml). Every password is "campus123".
-- History is posted through a helper that keeps entries balanced and balance_after correct,
-- so the admin reconciliation report is green from the first start.

INSERT INTO users (email, password_hash, full_name, student_id, role) VALUES
    ('admin@campus.test',     '$2a$10$l7YpSXhgFB9Sm/Irpv.6tuocARqo9b0hX1M5Zr9S3Ry3awVlZezxy', 'Farah Iskandar',  NULL,        'ADMIN'),
    ('kafe@campus.test',      '$2a$10$l7YpSXhgFB9Sm/Irpv.6tuocARqo9b0hX1M5Zr9S3Ry3awVlZezxy', 'Kafe Siswa',      NULL,        'MERCHANT'),
    ('fotostat@campus.test',  '$2a$10$l7YpSXhgFB9Sm/Irpv.6tuocARqo9b0hX1M5Zr9S3Ry3awVlZezxy', 'Kedai Fotostat',  NULL,        'MERCHANT'),
    ('aisyah@campus.test',    '$2a$10$l7YpSXhgFB9Sm/Irpv.6tuocARqo9b0hX1M5Zr9S3Ry3awVlZezxy', 'Aisyah Rahman',   'A21CS0001', 'USER'),
    ('weiming@campus.test',   '$2a$10$l7YpSXhgFB9Sm/Irpv.6tuocARqo9b0hX1M5Zr9S3Ry3awVlZezxy', 'Tan Wei Ming',    'A21CS0002', 'USER'),
    ('priya@campus.test',     '$2a$10$l7YpSXhgFB9Sm/Irpv.6tuocARqo9b0hX1M5Zr9S3Ry3awVlZezxy', 'Priya Ramasamy',  'A21EE0003', 'USER'),
    ('haziq@campus.test',     '$2a$10$l7YpSXhgFB9Sm/Irpv.6tuocARqo9b0hX1M5Zr9S3Ry3awVlZezxy', 'Haziq Kamarul',   'A22ME0004', 'USER'),
    ('meiling@campus.test',   '$2a$10$l7YpSXhgFB9Sm/Irpv.6tuocARqo9b0hX1M5Zr9S3Ry3awVlZezxy', 'Lim Mei Ling',    'A22CS0005', 'USER');

INSERT INTO merchants (user_id, code, name)
SELECT id, 'KAFESISWA', 'Kafe Siswa' FROM users WHERE email = 'kafe@campus.test';
INSERT INTO merchants (user_id, code, name)
SELECT id, 'FOTOSTAT', 'Kedai Fotostat' FROM users WHERE email = 'fotostat@campus.test';

INSERT INTO accounts (owner_user_id, type)
SELECT id, CASE role WHEN 'USER' THEN 'USER_WALLET' ELSE 'MERCHANT_WALLET' END
FROM users WHERE role <> 'ADMIN' ORDER BY id;

-- Helpers live in pg_temp, so they vanish when the migration's session ends.
CREATE FUNCTION pg_temp.acct(p_email TEXT) RETURNS BIGINT AS $$
    SELECT a.id FROM accounts a JOIN users u ON u.id = a.owner_user_id WHERE u.email = p_email;
$$ LANGUAGE sql;

CREATE FUNCTION pg_temp.uid(p_email TEXT) RETURNS BIGINT AS $$
    SELECT id FROM users WHERE email = p_email;
$$ LANGUAGE sql;

-- Moves p_amount sen from one account to another as one balanced two-line entry.
CREATE FUNCTION pg_temp.post(p_type TEXT, p_from BIGINT, p_to BIGINT, p_amount BIGINT, p_desc TEXT,
                             p_by BIGINT, p_at TIMESTAMPTZ) RETURNS BIGINT AS $$
DECLARE
    v_entry BIGINT;
    v_from_after BIGINT;
    v_to_after BIGINT;
BEGIN
    INSERT INTO journal_entries (type, idempotency_key, description, created_by, created_at)
    VALUES (p_type, gen_random_uuid(), p_desc, p_by, p_at) RETURNING id INTO v_entry;
    UPDATE accounts SET balance = balance - p_amount WHERE id = p_from RETURNING balance INTO v_from_after;
    UPDATE accounts SET balance = balance + p_amount WHERE id = p_to RETURNING balance INTO v_to_after;
    INSERT INTO ledger_lines (entry_id, account_id, amount, balance_after)
    VALUES (v_entry, p_from, -p_amount, v_from_after), (v_entry, p_to, p_amount, v_to_after);
    RETURN v_entry;
END;
$$ LANGUAGE plpgsql;

-- Posts the mirror of an entry, linked by reverses_entry_id.
CREATE FUNCTION pg_temp.reverse(p_entry BIGINT, p_reason TEXT, p_by BIGINT, p_at TIMESTAMPTZ) RETURNS BIGINT AS $$
DECLARE
    v_entry BIGINT;
    v_line RECORD;
    v_after BIGINT;
BEGIN
    INSERT INTO journal_entries (type, idempotency_key, description, created_by, created_at, reverses_entry_id)
    VALUES ('REVERSAL', gen_random_uuid(), 'Reversal of #' || p_entry || ': ' || p_reason, p_by, p_at, p_entry)
    RETURNING id INTO v_entry;
    FOR v_line IN SELECT account_id, amount FROM ledger_lines WHERE entry_id = p_entry ORDER BY id LOOP
        UPDATE accounts SET balance = balance - v_line.amount WHERE id = v_line.account_id RETURNING balance INTO v_after;
        INSERT INTO ledger_lines (entry_id, account_id, amount, balance_after)
        VALUES (v_entry, v_line.account_id, -v_line.amount, v_after);
    END LOOP;
    RETURN v_entry;
END;
$$ LANGUAGE plpgsql;

DO $$
DECLARE
    sys      BIGINT := (SELECT id FROM accounts WHERE type = 'SYSTEM_TOPUP');
    kafe     BIGINT := pg_temp.acct('kafe@campus.test');
    fotostat BIGINT := pg_temp.acct('fotostat@campus.test');
    aisyah   BIGINT := pg_temp.acct('aisyah@campus.test');
    weiming  BIGINT := pg_temp.acct('weiming@campus.test');
    priya    BIGINT := pg_temp.acct('priya@campus.test');
    haziq    BIGINT := pg_temp.acct('haziq@campus.test');
    meiling  BIGINT := pg_temp.acct('meiling@campus.test');
    admin    BIGINT := pg_temp.uid('admin@campus.test');
    u_aisyah BIGINT := pg_temp.uid('aisyah@campus.test');
    u_wm     BIGINT := pg_temp.uid('weiming@campus.test');
    u_priya  BIGINT := pg_temp.uid('priya@campus.test');
    u_haziq  BIGINT := pg_temp.uid('haziq@campus.test');
    u_ml     BIGINT := pg_temp.uid('meiling@campus.test');
    double_charge BIGINT;
BEGIN
    -- 8 days ago: everyone tops up
    PERFORM pg_temp.post('TOPUP', sys, aisyah,  10000, 'Counter top-up (cash)', admin,    now() - interval '8 days 6 hours');
    PERFORM pg_temp.post('TOPUP', sys, weiming,  5000, 'Demo card top-up',      u_wm,     now() - interval '8 days 5 hours');
    PERFORM pg_temp.post('TOPUP', sys, priya,    8000, 'Demo card top-up',      u_priya,  now() - interval '8 days 4 hours');
    PERFORM pg_temp.post('TOPUP', sys, haziq,    3000, 'Counter top-up (cash)', admin,    now() - interval '8 days 3 hours');
    PERFORM pg_temp.post('TOPUP', sys, meiling, 15000, 'Demo card top-up',      u_ml,     now() - interval '8 days 2 hours');

    -- 7 days ago
    PERFORM pg_temp.post('PAYMENT',  aisyah,  kafe,      650, 'Nasi lemak ayam',     u_aisyah, now() - interval '7 days 5 hours');
    PERFORM pg_temp.post('PAYMENT',  weiming, fotostat,  240, 'Lab report printing', u_wm,     now() - interval '7 days 4 hours');
    PERFORM pg_temp.post('TRANSFER', priya,   meiling,  1500, 'Movie ticket',        u_priya,  now() - interval '7 days 2 hours');

    -- 6 days ago
    PERFORM pg_temp.post('PAYMENT',  haziq,   kafe,      800, NULL,                  u_haziq,  now() - interval '6 days 6 hours');
    PERFORM pg_temp.post('PAYMENT',  meiling, kafe,     1230, 'Lunch for two',       u_ml,     now() - interval '6 days 5 hours');
    PERFORM pg_temp.post('TRANSFER', aisyah,  haziq,    1000, 'Nasi lemak, thanks!', u_aisyah, now() - interval '6 days 1 hour');

    -- 5 days ago
    PERFORM pg_temp.post('PAYMENT',  priya,   fotostat,  560, 'Assignment printing', u_priya,  now() - interval '5 days 4 hours');
    PERFORM pg_temp.post('TOPUP',    sys,     weiming,  3000, 'Demo card top-up',    u_wm,     now() - interval '5 days 2 hours');

    -- 4 days ago
    PERFORM pg_temp.post('TRANSFER', meiling, aisyah,   2000, 'Group project snacks', u_ml,    now() - interval '4 days 5 hours');
    PERFORM pg_temp.post('PAYMENT',  haziq,   kafe,      450, 'Teh tarik',           u_haziq,  now() - interval '4 days 3 hours');

    -- 3 days ago, including a double charge that the office reversed
    PERFORM pg_temp.post('PAYMENT',  aisyah,  fotostat,  320, 'Binding',             u_aisyah, now() - interval '3 days 6 hours');
    PERFORM pg_temp.post('PAYMENT',  priya,   kafe,      700, NULL,                  u_priya,  now() - interval '3 days 5 hours');
    PERFORM pg_temp.post('PAYMENT',  weiming, kafe,     2500, 'Club dinner',         u_wm,     now() - interval '3 days 4 hours');
    double_charge := pg_temp.post('PAYMENT', weiming, kafe, 2500, 'Club dinner',     u_wm,     now() - interval '3 days 4 hours' + interval '40 seconds');
    PERFORM pg_temp.reverse(double_charge, 'Charged twice at the counter', admin,    now() - interval '3 days 2 hours');

    -- 2 days ago
    PERFORM pg_temp.post('TOPUP',    sys,     haziq,    5000, 'Demo card top-up',    u_haziq,  now() - interval '2 days 5 hours');
    PERFORM pg_temp.post('PAYMENT',  meiling, fotostat,  180, NULL,                  u_ml,     now() - interval '2 days 3 hours');

    -- Yesterday
    PERFORM pg_temp.post('PAYMENT',  aisyah,  kafe,      990, 'Mee goreng + milo',   u_aisyah, now() - interval '1 day 4 hours');
    PERFORM pg_temp.post('TRANSFER', weiming, priya,    1200, 'Badminton court',     u_wm,     now() - interval '1 day 2 hours');

    -- Today
    PERFORM pg_temp.post('PAYMENT',  priya,   kafe,      550, 'Roti canai set',      u_priya,  now() - interval '3 hours');
    PERFORM pg_temp.post('PAYMENT',  meiling, kafe,      600, NULL,                  u_ml,     now() - interval '2 hours');
    PERFORM pg_temp.post('TOPUP',    sys,     aisyah,   2000, 'Demo card top-up',    u_aisyah, now() - interval '40 minutes');
END;
$$;
