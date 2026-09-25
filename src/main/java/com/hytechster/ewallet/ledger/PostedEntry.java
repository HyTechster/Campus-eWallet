package com.hytechster.ewallet.ledger;

/**
 * The result of a money operation. {@code replayed} is true when the idempotency key was
 * already used and the original entry is returned instead of posting again.
 */
public record PostedEntry(long entryId, EntryType type, long amount, boolean replayed) {

    static PostedEntry of(JournalEntry entry, boolean replayed) {
        return new PostedEntry(entry.getId(), entry.getType(), entry.amount(), replayed);
    }
}
