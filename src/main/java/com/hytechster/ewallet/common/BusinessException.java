package com.hytechster.ewallet.common;

/**
 * A rule the user broke. The message is safe and friendly enough to show on screen.
 */
public class BusinessException extends RuntimeException {

    public BusinessException(String message) {
        super(message);
    }
}
