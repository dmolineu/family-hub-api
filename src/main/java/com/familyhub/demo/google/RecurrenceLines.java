package com.familyhub.demo.google;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Repairs recurrence lines before they are read or sent (Phase 4). The app's "repeat
 * until" writes a date ({@code UNTIL=20261123}); on a timed series RFC 5545 needs a UTC
 * date-time, and ical4j cannot compare a date with a zoned start. Such an UNTIL becomes
 * the end of that day in the series' zone, in UTC. All-day series keep dates.
 */
public final class RecurrenceLines {

    private static final Pattern DATE_ONLY_UNTIL = Pattern.compile("(?i)(UNTIL=)(\\d{8})(?=;|$)");
    private static final DateTimeFormatter COMPACT_DATE = DateTimeFormatter.BASIC_ISO_DATE;
    private static final DateTimeFormatter COMPACT_UTC =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);

    private RecurrenceLines() {
    }

    /**
     * The lines with any date-only UNTIL of a timed series made a UTC date-time.
     *
     * @param lines the RRULE/EXDATE/RDATE lines, or null
     * @param timed whether the series is timed (not all-day)
     * @param zone  the series' zone
     * @return the repaired lines; null for null
     */
    public static List<String> normalize(List<String> lines, boolean timed, ZoneId zone) {
        if (lines == null || !timed) {
            return lines;
        }
        List<String> repaired = new ArrayList<>(lines.size());
        for (String line : lines) {
            repaired.add(line.toUpperCase(Locale.ROOT).startsWith("RRULE:") ? untilInUtc(line, zone) : line);
        }
        return repaired;
    }

    /**
     * One RRULE value (without "RRULE:") with a date-only UNTIL made a UTC date-time.
     *
     * @param rule the rule
     * @param zone the series' zone
     * @return the repaired rule
     */
    public static String untilInUtc(String rule, ZoneId zone) {
        Matcher until = DATE_ONLY_UNTIL.matcher(rule);
        if (!until.find()) {
            return rule;
        }
        try {
            LocalDate last = LocalDate.parse(until.group(2), COMPACT_DATE);
            String end = COMPACT_UTC.format(last.plusDays(1).atStartOfDay(zone).minusSeconds(1).toInstant());
            return rule.substring(0, until.start(2)) + end + rule.substring(until.end(2));
        } catch (DateTimeParseException e) {
            return rule;
        }
    }
}
