package com.hytechster.ewallet.common;

public class AccountFrozenException extends BusinessException {

    public AccountFrozenException(String message) {
        super(message);
    }
}
