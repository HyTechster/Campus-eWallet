package com.hytechster.ewallet.ledger;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** A whole journal entry with every line, for the admin entry detail page. */
public record EntryView(
        long id,
        EntryType type,
        Instant createdAt,
        long amount,
        String description,
        Long createdBy,
        String createdByName,
        UUID idempotencyKey,
        Long reversesEntryId,
        Long reversedByEntryId,
        List<Line> lines) {

    /** Always 0 for a valid entry. Shown so an admin can see it balance. */
    public long sum() {
        return lines.stream().mapToLong(Line::amount).sum();
    }

    public boolean reversible() {
        return type != EntryType.REVERSAL && reversedByEntryId == null;
    }

    public record Line(long accountId, AccountType accountType, Long ownerUserId, String name, long amount,
                       long balanceAfter) {
    }
}
