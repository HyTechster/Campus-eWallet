package com.hytechster.ewallet.ledger;

/** Who an account belongs to, for "Sent to Siti" style labels. */
public record AccountLabel(Long accountId, AccountType type, Long ownerUserId, String name) {
}
