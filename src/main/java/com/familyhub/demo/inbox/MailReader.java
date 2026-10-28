package com.familyhub.demo.inbox;

import java.util.Optional;

/** Reads a message for events or a recipe. */
public interface MailReader {
    /** Whether a reader is set up; without one, messages with words are filed unread. */
    boolean available();

    /**
     * Why the reader cannot read just now (not signed in, busy, unreachable), for the
     * family to see; empty while it reads, or before it has tried.
     */
    default Optional<String> problem() {
        return Optional.empty();
    }

    /**
     * @throws MailReaderException when it cannot be read; {@link MailReaderException#temporary()}
     *                             says whether to try again at the next reading
     */
    MailReading read(ReadingRequest request) throws MailReaderException;
}
