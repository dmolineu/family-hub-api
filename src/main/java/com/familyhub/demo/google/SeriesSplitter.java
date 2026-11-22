package com.familyhub.demo.google;

import lombok.extern.slf4j.Slf4j;
import net.fortuna.ical4j.model.Recur;
import org.springframework.stereotype.Component;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;

/**
 * Splits a series at one occurrence for "this and following" (Phase 4), as Google's own
 * app does: the old series gets an UNTIL just before the split (one second for a timed
 * series, one day for an all-day one) and the new series starts at the split with the
 * rest of the rule. A COUNT is shared out between the two; an UNTIL stays with the new
 * series; EXDATE and RDATE values go to the half they fall in.
 */
@Slf4j
@Component
public class SeriesSplitter {

    private static final DateTimeFormatter COMPACT_UTC =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter COMPACT_LOCAL = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss");
    private static final DateTimeFormatter COMPACT_DATE = DateTimeFormatter.BASIC_ISO_DATE;
    // A COUNT is shared out by generating at most this many dates before the split.
    private static final int MAX_COUNTED = 5000;

    /**
     * The two halves of a split series.
     *
     * @param before        the old series' lines, ending before the split; empty when
     *                      {@code startsAtFirst}
     * @param after         the new series' lines, from the split on
     * @param startsAtFirst whether the split is at the series' first date, so the whole
     *                      series is changed rather than split
     */
    public record Split(String[] before, String[] after, boolean startsAtFirst) {
    }

    /**
     * Splits a timed series.
     *
     * @param lines       the series' RRULE/EXDATE/RDATE lines
     * @param seriesStart the series' first start
     * @param splitAt     the original start of the occurrence the new series begins with
     * @param zone        the series' zone, for floating and TZID times
     * @return the two halves
     */
    public Split splitTimed(String[] lines, Instant seriesStart, Instant splitAt, ZoneId zone) {
        if (!splitAt.isAfter(seriesStart)) {
            return new Split(new String[0], lines.clone(), true);
        }
        String until = COMPACT_UTC.format(splitAt.minusSeconds(1));
        ZonedDateTime seed = seriesStart.atZone(zone);
        return split(lines, until,
                rule -> countBefore(rule, seed, splitAt.atZone(zone)),
                value -> instant(value.value(), value.zone(zone)).isBefore(splitAt));
    }

    /**
     * Splits an all-day series.
     *
     * @param lines       the series' RRULE/EXDATE/RDATE lines
     * @param seriesStart the series' first day
     * @param splitAt     the original day of the occurrence the new series begins with
     * @return the two halves
     */
    public Split splitAllDay(String[] lines, LocalDate seriesStart, LocalDate splitAt) {
        if (!splitAt.isAfter(seriesStart)) {
            return new Split(new String[0], lines.clone(), true);
        }
        String until = COMPACT_DATE.format(splitAt.minusDays(1));
        return split(lines, until,
                rule -> countBefore(rule, seriesStart, splitAt),
                value -> date(value.value()).isBefore(splitAt));
    }

    /** One value of an EXDATE/RDATE line, with the TZID its line names, if any. */
    private record Value(String value, String tzid) {
        ZoneId zone(ZoneId fallback) {
            if (tzid == null) {
                return fallback;
            }
            try {
                return ZoneId.of(tzid);
            } catch (DateTimeException e) {
                return fallback;
            }
        }
    }

    @FunctionalInterface
    private interface Counter {
        int occurrencesBefore(String rule);
    }

    private Split split(String[] lines, String until, Counter counter, Predicate<Value> isBefore) {
        List<String> before = new ArrayList<>();
        List<String> after = new ArrayList<>();
        for (String line : lines) {
            String upper = line.toUpperCase(Locale.ROOT);
            if (upper.startsWith("RRULE:")) {
                String rule = line.substring("RRULE:".length());
                before.add("RRULE:" + endAt(rule, until));
                Integer count = part(rule, "COUNT");
                if (count == null) {
                    after.add(line);
                } else {
                    int left = count - counter.occurrencesBefore(rule);
                    if (left > 0) {
                        after.add("RRULE:" + withPart(rule, "COUNT", String.valueOf(left)));
                    }
                }
            } else if (upper.startsWith("EXDATE") || upper.startsWith("RDATE")) {
                partition(line, isBefore, before, after);
            } else {
                // Anything else Google may add (EXRULE is obsolete) stays with both halves.
                before.add(line);
                after.add(line);
            }
        }
        return new Split(before.toArray(String[]::new), after.toArray(String[]::new), false);
    }

    private static void partition(String line, Predicate<Value> isBefore, List<String> before, List<String> after) {
        int colon = line.indexOf(':');
        if (colon < 0) {
            return;
        }
        String head = line.substring(0, colon);
        String tzid = null;
        for (String parameter : head.split(";")) {
            if (parameter.toUpperCase(Locale.ROOT).startsWith("TZID=")) {
                tzid = parameter.substring("TZID=".length());
            }
        }
        List<String> early = new ArrayList<>();
        List<String> late = new ArrayList<>();
        for (String raw : line.substring(colon + 1).split(",")) {
            String value = raw.trim();
            try {
                (isBefore.test(new Value(value, tzid)) ? early : late).add(value);
            } catch (DateTimeException e) {
                log.warn("Keeping a date it cannot read with both halves of a split: {}", value);
                early.add(value);
                late.add(value);
            }
        }
        if (!early.isEmpty()) {
            before.add(head + ":" + String.join(",", early));
        }
        if (!late.isEmpty()) {
            after.add(head + ":" + String.join(",", late));
        }
    }

    // The rule without COUNT or UNTIL, ending at the given UNTIL.
    private static String endAt(String rule, String until) {
        List<String> parts = new ArrayList<>();
        for (String part : rule.split(";")) {
            String name = part.split("=", 2)[0].toUpperCase(Locale.ROOT);
            if (!name.equals("COUNT") && !name.equals("UNTIL")) {
                parts.add(part);
            }
        }
        parts.add("UNTIL=" + until);
        return String.join(";", parts);
    }

    private static String withPart(String rule, String name, String value) {
        List<String> parts = new ArrayList<>();
        for (String part : rule.split(";")) {
            parts.add(part.split("=", 2)[0].equalsIgnoreCase(name) ? name + "=" + value : part);
        }
        return String.join(";", parts);
    }

    private static Integer part(String rule, String name) {
        for (String part : rule.split(";")) {
            String[] pair = part.split("=", 2);
            if (pair.length == 2 && pair[0].equalsIgnoreCase(name)) {
                try {
                    return Integer.parseInt(pair[1].trim());
                } catch (NumberFormatException e) {
                    return null;
                }
            }
        }
        return null;
    }

    private static int countBefore(String rule, ZonedDateTime seed, ZonedDateTime splitAt) {
        Recur<ZonedDateTime> recur = new Recur<>(rule);
        return (int) recur.getDates(seed, seed, splitAt, MAX_COUNTED).stream()
                .filter(date -> date.isBefore(splitAt))
                .count();
    }

    private static int countBefore(String rule, LocalDate seed, LocalDate splitAt) {
        Recur<LocalDate> recur = new Recur<>(rule);
        return (int) recur.getDates(seed, seed, splitAt, MAX_COUNTED).stream()
                .filter(date -> date.isBefore(splitAt))
                .count();
    }

    private static Instant instant(String value, ZoneId zone) {
        if (value.length() == 8) {
            return LocalDate.parse(value, COMPACT_DATE).atStartOfDay(zone).toInstant();
        }
        if (value.endsWith("Z") || value.endsWith("z")) {
            return Instant.from(COMPACT_UTC.parse(value.toUpperCase(Locale.ROOT)));
        }
        return LocalDateTime.parse(value, COMPACT_LOCAL).atZone(zone).toInstant();
    }

    private static LocalDate date(String value) {
        return LocalDate.parse(value.substring(0, 8), COMPACT_DATE);
    }
}
