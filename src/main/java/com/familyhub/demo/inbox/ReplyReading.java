package com.familyhub.demo.inbox;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import java.util.List;

/**
 * What the reader made of a parent's reply: a decision for each event the inbox listed
 * (on the calendar already, or waiting), and a note on anything it could not apply. This
 * is the structured output's schema, so every field is always present.
 */
public record ReplyReading(
        @JsonPropertyDescription("One decision per listed event, by its number")
        List<Decision> decisions,
        @JsonPropertyDescription("One short sentence for the parent on anything in the reply that could not be"
                + " applied, or a question it asked; otherwise \"\"")
        String note) {

    /**
     * ADD: the event belongs on the calendar as the decision's event says (a waiting one
     * approved, or one on the calendar corrected). TURN_DOWN: it does not (a waiting one
     * turned down, or one on the calendar taken off). WAIT: the reply leaves it as it is.
     */
    public enum Action { ADD, TURN_DOWN, WAIT }

    public record Decision(
            @JsonPropertyDescription("The event's number, as the inbox's email listed it")
            int number,
            @JsonPropertyDescription("For a waiting event: ADD when the reply approves it, as listed or with"
                    + " changes; TURN_DOWN when it says not to add it. For an event on the calendar: ADD when the"
                    + " reply corrects it; TURN_DOWN when it says to take it off. WAIT when the reply leaves it as"
                    + " it is")
            Action action,
            @JsonPropertyDescription("The event with the reply's changes applied; exactly as listed when it"
                    + " changes nothing")
            MailReading.ReadEvent event) {
    }
}
