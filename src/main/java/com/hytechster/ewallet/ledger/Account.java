package com.hytechster.ewallet.ledger;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * A wallet or system account. {@code balance} is a cached running total of its ledger lines,
 * kept so reads are fast and so there is a row to lock. Only {@link LedgerService} changes it.
 */
@Entity
@Table(name = "accounts")
public class Account {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "owner_user_id")
    private Long ownerUserId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AccountType type;

    @Column(nullable = false)
    private long balance;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected Account() {
    }

    Account(Long ownerUserId, AccountType type) {
        this.ownerUserId = ownerUserId;
        this.type = type;
        this.balance = 0;
        this.createdAt = Instant.now();
    }

    /** Package-private on purpose: the ledger is the only writer of balances. */
    void apply(long amount) {
        this.balance = Math.addExact(this.balance, amount);
    }

    public Long getId() {
        return id;
    }

    public Long getOwnerUserId() {
        return ownerUserId;
    }

    public AccountType getType() {
        return type;
    }

    public long getBalance() {
        return balance;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
