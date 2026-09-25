package com.hytechster.ewallet.ledger;

public enum AccountType {
    USER_WALLET, MERCHANT_WALLET, SYSTEM_TOPUP;

    /** Only the system top-up account may go below zero. */
    public boolean mayGoNegative() {
        return this == SYSTEM_TOPUP;
    }
}
