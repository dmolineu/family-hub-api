package com.familyhub.demo.exception;

/** An outside service this request depends on (e.g. the weather provider) did not answer. */
public class ServiceUnavailableException extends RuntimeException {
    public ServiceUnavailableException(String message) {
        super(message);
    }

    public ServiceUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
