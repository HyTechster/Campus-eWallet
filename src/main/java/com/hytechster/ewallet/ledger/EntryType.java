package com.hytechster.ewallet.ledger;

public enum EntryType {
    TOPUP("Top up"), TRANSFER("Transfer"), PAYMENT("Payment"), REVERSAL("Reversal");

    private final String label;

    EntryType(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
