package com.hytechster.ewallet.common;

/**
 * Something does not exist, or the current user is not allowed to know it exists.
 */
public class NotFoundException extends RuntimeException {

    public NotFoundException(String message) {
        super(message);
    }
}
