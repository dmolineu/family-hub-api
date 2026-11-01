package com.familyhub.demo.inbox;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import java.util.List;

/**
 * What the reader made of a parent's reply: a decision for each waiting event, and a
 * note on anything it could not apply. This is the structured output's schema, so
 * every field is always present.
 */
public record ReplyReading(
        @JsonPropertyDescription("One decision per waiting event, by its number")
        List<Decision> decisions,
        @JsonPropertyDescription("One short sentence for the parent on anything in the reply that could not be"
                + " applied, or a question it asked; otherwise \"\"")
        String note) {

    public enum Action { ADD, TURN_DOWN, WAIT }

    public record Decision(
            @JsonPropertyDescription("The waiting event's number, from 1")
            int number,
            @JsonPropertyDescription("ADD when the reply approves it, as listed or with changes; TURN_DOWN when the"
                    + " reply says not to add it; WAIT when the reply does not decide it")
            Action action,
            @JsonPropertyDescription("The event with the reply's changes applied; exactly as listed when it"
                    + " changes nothing")
            MailReading.ReadEvent event) {
    }
}
