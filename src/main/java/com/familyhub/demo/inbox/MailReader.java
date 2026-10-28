package com.familyhub.demo.inbox;

/** Reads a message for events or a recipe. */
public interface MailReader {
    /** Whether a reader is set up (an API key); without one, messages with words wait. */
    boolean available();

    /**
     * @throws MailReaderException when it cannot be read; {@link MailReaderException#temporary()}
     *                             says whether to try again at the next reading
     */
    MailReading read(ReadingRequest request) throws MailReaderException;
}
