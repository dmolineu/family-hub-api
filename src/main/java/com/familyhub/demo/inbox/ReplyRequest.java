package com.familyhub.demo.inbox;

import java.time.LocalDate;
import java.util.List;

/**
 * A parent's reply to the inbox's confirmation, to read against the events still
 * waiting from that message: {@code text} is the parent's own words (the quoted earlier
 * email left out), and {@code waiting} is numbered from 1 in the order the confirmation
 * listed it.
 */
public record ReplyRequest(String text, List<MailReading.ReadEvent> waiting, LocalDate today, String timeZone,
                           List<String> memberNames) {
}
