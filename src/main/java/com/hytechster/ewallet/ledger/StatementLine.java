package com.hytechster.ewallet.ledger;

import java.time.Instant;

/**
 * One transaction as seen from one account. {@code amount} is signed from that account's side:
 * positive is money in, negative is money out.
 */
public record StatementLine(
        long entryId,
        EntryType type,
        Instant createdAt,
        long amount,
        long balanceAfter,
        String title,
        String counterparty,
        String note,
        Long reversesEntryId) {

    public boolean moneyIn() {
        return amount > 0;
    }

    public String directionLabel() {
        return amount > 0 ? "Money in" : "Money out";
    }

    /** Icon name from templates/fragments/icons.html. */
    public String icon() {
        return switch (type) {
            case TOPUP -> "plus";
            case PAYMENT -> "store";
            case REVERSAL -> "undo";
            case TRANSFER -> amount > 0 ? "receive" : "send";
        };
    }
}
