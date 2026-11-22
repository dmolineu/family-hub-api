package com.familyhub.demo.google;

import net.fortuna.ical4j.model.Recur;

import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Where a series the hub writes should start (Phase 4). Google, like RFC 5545, counts a
 * series' start as its first occurrence even on a day its rule skips, so "every Saturday"
 * made on a Sunday would show on that Sunday too. The hub's old calendar never did, and
 * Google's own screens move the start to the rule's first day; the hub does the same.
 */
public final class RecurrenceStart {

    // A rule may skip ahead to a matching day this far (a yearly Feb 29 rule needs 4 years).
    private static final int SEARCH_YEARS = 8;
    private static final Pattern UTC_UNTIL = Pattern.compile("(?i)(UNTIL=\\d{8})T\\d{6}Z");

    private RecurrenceStart() {
    }

    /**
     * The first day a series' rule gives, when that is not its start day.
     *
     * @param start the series' start day, in its own zone
     * @param lines its RRULE/EXDATE/RDATE lines, or null
     * @return the day to start on instead; empty when the start already matches, or when the
     *         lines have no single rule it can read, add dates of their own (RDATE), or the
     *         rule gives no day at all
     */
    public static Optional<LocalDate> firstMatchingDay(LocalDate start, List<String> lines) {
        if (start == null || lines == null) {
            return Optional.empty();
        }
        List<String> rules = lines.stream()
                .filter(line -> line.toUpperCase(Locale.ROOT).startsWith("RRULE:")).toList();
        boolean extraDates = lines.stream().anyMatch(line -> line.toUpperCase(Locale.ROOT).startsWith("RDATE"));
        if (rules.size() != 1 || extraDates) {
            return Optional.empty();
        }
        // Days are compared, so a UTC UNTIL is read as its date: at most a day generous.
        String rule = UTC_UNTIL.matcher(rules.getFirst().substring("RRULE:".length())).replaceAll("$1");
        try {
            List<LocalDate> first = new Recur<LocalDate>(rule)
                    .getDates(start, start, start.plusYears(SEARCH_YEARS), 1);
            return first.isEmpty() || first.getFirst().equals(start)
                    ? Optional.empty() : Optional.of(first.getFirst());
        } catch (RuntimeException e) {
            // A rule it cannot read is left for Google to judge.
            return Optional.empty();
        }
    }
}
