package com.familyhub.demo.service;

import com.familyhub.demo.dto.CalendarEventRequest;
import com.familyhub.demo.inbox.MailReading;
import com.familyhub.demo.model.FamilyMember;
import com.familyhub.demo.model.MemberRole;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Turns an event the inbox read (or a parent completed) into a calendar request: a
 * plain date, "9:00 AM"-style times (an hour long when only the start is known), and
 * the member it is for. Anything it cannot read makes no request, so the event waits
 * for a parent instead of going on wrong.
 */
final class InboxEventMapper {
    private static final Pattern TIME = Pattern.compile("^(1[0-2]|[1-9]):[0-5][0-9] (AM|PM)$");
    private static final DateTimeFormatter TWELVE_HOUR = DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH);
    /** How the calendar stores an all-day event's times. */
    private static final String ALL_DAY_TIME = "12:00 AM";

    private InboxEventMapper() {
    }

    /**
     * The calendar request for an event, if its title, date and times can be read.
     *
     * @param from    the sender's address, whose member an event for no one in particular goes to
     * @param subject the message's subject, noted in the event's description
     */
    static Optional<CalendarEventRequest> toRequest(MailReading.ReadEvent event, List<FamilyMember> familyMembers,
                                                    String from, String subject) {
        try {
            String title = event.title() == null ? "" : event.title().strip();
            if (title.isEmpty() || event.date() == null) {
                return Optional.empty();
            }
            LocalDate date = LocalDate.parse(event.date());
            LocalDate endDate = event.allDay() && event.endDate() != null && !event.endDate().isBlank()
                    ? LocalDate.parse(event.endDate()) : null;
            String start = ALL_DAY_TIME;
            String end = ALL_DAY_TIME;
            if (!event.allDay()) {
                if (event.startTime() == null || !TIME.matcher(event.startTime()).matches()) {
                    return Optional.empty();
                }
                start = event.startTime();
                end = event.endTime() != null && TIME.matcher(event.endTime()).matches() ? event.endTime()
                        : LocalTime.parse(start, TWELVE_HOUR).plusHours(1).format(TWELVE_HOUR);
                if (!LocalTime.parse(start, TWELVE_HOUR).isBefore(LocalTime.parse(end, TWELVE_HOUR))) {
                    return Optional.empty();
                }
            }
            FamilyMember member = memberFor(event, familyMembers, from);
            if (member == null) {
                return Optional.empty();
            }
            String location = event.location() == null || event.location().isBlank() ? null
                    : truncate(event.location().strip(), 255);
            return Optional.of(new CalendarEventRequest(truncate(title, 100), start, end, date, member.getId(),
                    event.allDay(), location, endDate, null, truncate("From an email: " + subject, 2000)));
        } catch (DateTimeParseException e) {
            return Optional.empty();
        }
    }

    /** The first person named, else the sender, else a parent, else anyone in the family. */
    static FamilyMember memberFor(MailReading.ReadEvent event, List<FamilyMember> familyMembers, String from) {
        if (event.people() != null) {
            for (String name : event.people()) {
                for (FamilyMember member : familyMembers) {
                    if (name != null && member.getName() != null && member.getName().equalsIgnoreCase(name.strip())) {
                        return member;
                    }
                }
            }
        }
        return familyMembers.stream().filter(member -> member.getEmail() != null && from != null
                        && member.getEmail().trim().equalsIgnoreCase(from)).findFirst()
                .or(() -> familyMembers.stream().filter(member -> member.getRole() == MemberRole.PARENT).findFirst())
                .or(() -> familyMembers.stream().findFirst())
                .orElse(null);
    }

    private static String truncate(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max - 1) + "…";
    }
}
