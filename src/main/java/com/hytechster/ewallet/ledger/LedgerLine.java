package com.hytechster.ewallet.ledger;

import org.hibernate.annotations.Immutable;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/**
 * One side of a journal entry. {@code amount} is signed: positive increases the account's
 * balance, negative decreases it. {@code balanceAfter} is the account balance right after this line.
 */
@Entity
@Immutable
@Table(name = "ledger_lines")
public class LedgerLine {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "entry_id", nullable = false)
    private JournalEntry entry;

    @Column(name = "account_id", nullable = false)
    private Long accountId;

    @Column(nullable = false)
    private long amount;

    @Column(name = "balance_after", nullable = false)
    private long balanceAfter;

    protected LedgerLine() {
    }

    LedgerLine(JournalEntry entry, long accountId, long amount, long balanceAfter) {
        this.entry = entry;
        this.accountId = accountId;
        this.amount = amount;
        this.balanceAfter = balanceAfter;
    }

    public Long getId() {
        return id;
    }

    public JournalEntry getEntry() {
        return entry;
    }

    public Long getAccountId() {
        return accountId;
    }

    public long getAmount() {
        return amount;
    }

    public long getBalanceAfter() {
        return balanceAfter;
    }
}
