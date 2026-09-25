package com.hytechster.ewallet.common;

public class InsufficientFundsException extends BusinessException {

    public InsufficientFundsException(String message) {
        super(message);
    }
}
