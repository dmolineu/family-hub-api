package com.familyhub.demo.inbox;

/** A message could not be read; a temporary failure (a busy or unreachable API) is worth retrying. */
public class MailReaderException extends Exception {
    private final boolean temporary;

    public MailReaderException(String message, boolean temporary, Throwable cause) {
        super(message, cause);
        this.temporary = temporary;
    }

    public boolean temporary() {
        return temporary;
    }
}
