package com.hytechster.ewallet.ledger;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import org.hibernate.annotations.Immutable;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;

/**
 * One money movement. Append-only: no setters, {@code @Immutable}, and a database trigger
 * rejects UPDATE and DELETE. Mistakes are fixed with a new REVERSAL entry.
 */
@Entity
@Immutable
@Table(name = "journal_entries")
public class JournalEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private EntryType type;

    @Column(name = "idempotency_key", nullable = false, unique = true)
    private UUID idempotencyKey;

    private String description;

    @Column(name = "created_by")
    private Long createdBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "reverses_entry_id")
    private Long reversesEntryId;

    @OneToMany(mappedBy = "entry", cascade = CascadeType.PERSIST)
    @OrderBy("id")
    private List<LedgerLine> lines = new ArrayList<>();

    protected JournalEntry() {
    }

    JournalEntry(EntryType type, UUID idempotencyKey, String description, Long createdBy, Long reversesEntryId) {
        this.type = type;
        this.idempotencyKey = idempotencyKey;
        this.description = description;
        this.createdBy = createdBy;
        this.reversesEntryId = reversesEntryId;
        this.createdAt = Instant.now();
    }

    void addLine(long accountId, long amount, long balanceAfter) {
        lines.add(new LedgerLine(this, accountId, amount, balanceAfter));
    }

    /** The size of the movement: the sum of the positive lines. */
    public long amount() {
        return lines.stream().mapToLong(LedgerLine::getAmount).filter(a -> a > 0).sum();
    }

    public Long getId() {
        return id;
    }

    public EntryType getType() {
        return type;
    }

    public UUID getIdempotencyKey() {
        return idempotencyKey;
    }

    public String getDescription() {
        return description;
    }

    public Long getCreatedBy() {
        return createdBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Long getReversesEntryId() {
        return reversesEntryId;
    }

    public List<LedgerLine> getLines() {
        return Collections.unmodifiableList(lines);
    }
}
