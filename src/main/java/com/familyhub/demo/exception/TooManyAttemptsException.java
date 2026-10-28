package com.familyhub.demo.exception;

/** Too many wrong sign-in passwords for a username; answered with 429. */
public class TooManyAttemptsException extends RuntimeException {
    public TooManyAttemptsException(String message) {
        super(message);
    }
}
