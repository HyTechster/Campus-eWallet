package com.hytechster.ewallet.common;

public class LimitExceededException extends BusinessException {

    public LimitExceededException(String message) {
        super(message);
    }
}
