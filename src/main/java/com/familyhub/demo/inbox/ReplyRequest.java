package com.familyhub.demo.inbox;

import java.time.LocalDate;
import java.util.List;

/**
 * A parent's reply to the inbox's confirmation, to read against the events that message
 * listed: {@code text} is the parent's own words (the quoted earlier email left out), and
 * {@code events} are those still on the calendar or still waiting, each under the number
 * the emails showed it with.
 */
public record ReplyRequest(String text, List<Listed> events, LocalDate today, String timeZone,
                           List<String> memberNames) {

    /**
     * One listed event: {@code onCalendar} when the inbox (or a parent) already added it,
     * as it is on the calendar now; otherwise it waits for a parent, as the reader proposed it.
     */
    public record Listed(int number, boolean onCalendar, MailReading.ReadEvent event) {
    }
}
