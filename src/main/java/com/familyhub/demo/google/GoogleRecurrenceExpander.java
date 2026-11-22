package com.familyhub.demo.google;

import com.familyhub.demo.model.GoogleEvent;
import com.familyhub.demo.model.GoogleEventStatus;
import lombok.extern.slf4j.Slf4j;
import net.fortuna.ical4j.model.Recur;
import org.springframework.stereotype.Component;

import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;

/**
 * Expands a cached Google series into the occurrences a window shows (Phase 4). Rules
 * come from ical4j; this class adds what Google layers on top: EXDATE and RDATE lines in
 * every form Google writes, and the instances that replace, cancel or move single
 * occurrences. A timed series is expanded in its own zone and keeps its local length, so
 * a 09:00-10:00 weekly event stays 09:00-10:00 across DST. At most
 * {@value #MAX_PER_SERIES} occurrences per series are returned.
 */
@Slf4j
@Component
public class GoogleRecurrenceExpander {

    /** The most occurrences one series contributes to one window. */
    public static final int MAX_PER_SERIES = 1000;

    private static final DateTimeFormatter COMPACT_UTC =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter COMPACT_LOCAL = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss");
    private static final DateTimeFormatter COMPACT_DATE = DateTimeFormatter.BASIC_ISO_DATE;
    // A timed occurrence that started up to this long before its overlap with the window
    // started still counts, so the rule is asked for dates from a little earlier.
    private static final Duration DST_SLACK = Duration.ofHours(2);

    /**
     * A half-open window: timed occurrences overlap [from, to), all-day ones
     * [fromDate, toDate) with Google's exclusive end dates.
     *
     * @param from     start of the window
     * @param to       end of the window, exclusive
     * @param fromDate the first day of the window
     * @param toDate   the day after the last day of the window
     */
    public record Window(Instant from, Instant to, LocalDate fromDate, LocalDate toDate) {

        /**
         * The window of whole days from {@code first} to {@code lastInclusive} in a zone.
         *
         * @param first         the first day shown
         * @param lastInclusive the last day shown
         * @param zone          the family's zone
         * @return the window
         */
        public static Window of(LocalDate first, LocalDate lastInclusive, ZoneId zone) {
            LocalDate after = lastInclusive.plusDays(1);
            return new Window(first.atStartOfDay(zone).toInstant(), after.atStartOfDay(zone).toInstant(), first, after);
        }

        /**
         * Whether a timed span overlaps the window.
         *
         * @param start the span's start
         * @param end   the span's end
         * @return true if they overlap
         */
        public boolean overlaps(Instant start, Instant end) {
            return start.isBefore(to) && end.isAfter(from);
        }

        /**
         * Whether an all-day span overlaps the window.
         *
         * @param start        the first day
         * @param endExclusive the day after the last day
         * @return true if they overlap
         */
        public boolean overlaps(LocalDate start, LocalDate endExclusive) {
            return start.isBefore(toDate) && endExclusive.isAfter(fromDate);
        }
    }

    /**
     * One occurrence of a series.
     *
     * @param source        the master, or the instance that replaced this occurrence
     * @param modified      whether an instance replaced it
     * @param start         a timed occurrence's start; null for all-day
     * @param end           a timed occurrence's end; null for all-day
     * @param startDate     an all-day occurrence's first day; null for timed
     * @param endDate       an all-day occurrence's day after its last; null for timed
     * @param originalStart the occurrence's original start in Google's compact form
     *                      ({@code 20260308T170000Z} or {@code 20260308}), which names it
     */
    public record Occurrence(GoogleEvent source, boolean modified, Instant start, Instant end, LocalDate startDate,
                             LocalDate endDate, String originalStart) {
    }

    /** A series' rule lines, sorted by kind. */
    private record Lines(List<String> rules, List<String> exdates, List<String> rdates) {
        static Lines of(String[] recurrence) {
            List<String> rules = new ArrayList<>();
            List<String> exdates = new ArrayList<>();
            List<String> rdates = new ArrayList<>();
            for (String line : recurrence == null ? new String[0] : recurrence) {
                String upper = line.toUpperCase(Locale.ROOT);
                if (upper.startsWith("RRULE:")) {
                    rules.add(line.substring("RRULE:".length()));
                } else if (upper.startsWith("EXDATE")) {
                    exdates.add(line);
                } else if (upper.startsWith("RDATE")) {
                    rdates.add(line);
                }
            }
            return new Lines(rules, exdates, rdates);
        }
    }

    /**
     * The occurrences of a series that overlap a window.
     *
     * @param master       the series' master row
     * @param instances    its changed and cancelled instances (any dates)
     * @param fallbackZone the zone to use when the series names none
     * @param window       what to show
     * @return the occurrences, by start
     */
    public List<Occurrence> expand(GoogleEvent master, List<GoogleEvent> instances, ZoneId fallbackZone,
                                   Window window) {
        ZoneId zone = zoneOf(master, fallbackZone);
        Lines lines = Lines.of(master.getRecurrence());
        List<Occurrence> found = master.isAllDay()
                ? allDay(master, instances, lines, zone, window)
                : timed(master, instances, lines, zone, window);
        return found.size() > MAX_PER_SERIES ? List.copyOf(found.subList(0, MAX_PER_SERIES)) : found;
    }

    private List<Occurrence> timed(GoogleEvent master, List<GoogleEvent> instances, Lines lines, ZoneId zone,
                                   Window window) {
        ZonedDateTime seed = master.getStartAt().atZone(zone);
        Duration length = Duration.between(seed.toLocalDateTime(), master.getEndAt().atZone(zone).toLocalDateTime());
        ZonedDateTime periodStart = window.from().minus(length).minus(DST_SLACK).atZone(zone);
        ZonedDateTime periodEnd = window.to().atZone(zone);

        TreeSet<Instant> starts = new TreeSet<>();
        for (String rule : lines.rules()) {
            try {
                Recur<ZonedDateTime> recur = new Recur<>(rule);
                for (ZonedDateTime date : recur.getDates(seed, periodStart, periodEnd, MAX_PER_SERIES + 1)) {
                    starts.add(date.toInstant());
                }
            } catch (RuntimeException e) {
                log.warn("Skipping a recurrence rule it cannot read, eventId={}, rule={}: {}",
                        master.getGoogleEventId(), rule, e.toString());
            }
        }
        if (!seed.isBefore(periodStart) && seed.isBefore(periodEnd)) {
            // The first occurrence is the event's own start, whatever the rule says.
            starts.add(seed.toInstant());
        }
        for (String line : lines.rdates()) {
            for (Object value : values(line, zone, master)) {
                starts.add(value instanceof LocalDate date
                        ? date.atTime(seed.toLocalTime()).atZone(zone).toInstant()
                        : (Instant) value);
            }
        }
        for (String line : lines.exdates()) {
            for (Object value : values(line, zone, master)) {
                if (value instanceof LocalDate date) {
                    starts.removeIf(start -> start.atZone(zone).toLocalDate().equals(date));
                } else {
                    starts.remove((Instant) value);
                }
            }
        }

        Map<Instant, GoogleEvent> byOriginal = new HashMap<>();
        for (GoogleEvent instance : instances) {
            if (instance.getOriginalStartAt() != null) {
                byOriginal.put(instance.getOriginalStartAt(), instance);
            }
        }
        List<Occurrence> found = new ArrayList<>();
        for (Instant start : starts) {
            if (found.size() > MAX_PER_SERIES) {
                break;
            }
            String original = COMPACT_UTC.format(start);
            GoogleEvent instance = byOriginal.remove(start);
            if (instance != null) {
                if (shows(instance) && window.overlaps(instance.getStartAt(), instance.getEndAt())) {
                    found.add(new Occurrence(instance, true, instance.getStartAt(), instance.getEndAt(), null, null,
                            original));
                }
                continue;
            }
            Instant end = start.atZone(zone).toLocalDateTime().plus(length).atZone(zone).toInstant();
            if (window.overlaps(start, end)) {
                found.add(new Occurrence(master, false, start, end, null, null, original));
            }
        }
        // Instances moved into the window from an occurrence outside it.
        for (GoogleEvent instance : byOriginal.values()) {
            if (shows(instance) && instance.getStartAt() != null
                    && window.overlaps(instance.getStartAt(), instance.getEndAt())) {
                found.add(new Occurrence(instance, true, instance.getStartAt(), instance.getEndAt(), null, null,
                        COMPACT_UTC.format(instance.getOriginalStartAt())));
            }
        }
        found.sort(Comparator.comparing(Occurrence::start));
        return found;
    }

    private List<Occurrence> allDay(GoogleEvent master, List<GoogleEvent> instances, Lines lines, ZoneId zone,
                                    Window window) {
        LocalDate seed = master.getStartDate();
        long days = Math.max(1, ChronoUnit.DAYS.between(seed, master.getEndDate()));
        LocalDate periodStart = window.fromDate().minusDays(days);
        LocalDate periodEnd = window.toDate();

        TreeSet<LocalDate> starts = new TreeSet<>();
        for (String rule : lines.rules()) {
            try {
                Recur<LocalDate> recur = new Recur<>(rule);
                starts.addAll(recur.getDates(seed, periodStart, periodEnd, MAX_PER_SERIES + 1));
            } catch (RuntimeException e) {
                log.warn("Skipping a recurrence rule it cannot read, eventId={}, rule={}: {}",
                        master.getGoogleEventId(), rule, e.toString());
            }
        }
        if (!seed.isBefore(periodStart) && seed.isBefore(periodEnd)) {
            starts.add(seed);
        }
        for (String line : lines.rdates()) {
            for (Object value : values(line, zone, master)) {
                starts.add(value instanceof LocalDate date ? date : ((Instant) value).atZone(zone).toLocalDate());
            }
        }
        for (String line : lines.exdates()) {
            for (Object value : values(line, zone, master)) {
                starts.remove(value instanceof LocalDate date ? date : ((Instant) value).atZone(zone).toLocalDate());
            }
        }

        Map<LocalDate, GoogleEvent> byOriginal = new HashMap<>();
        for (GoogleEvent instance : instances) {
            if (instance.getOriginalStartDate() != null) {
                byOriginal.put(instance.getOriginalStartDate(), instance);
            }
        }
        List<Occurrence> found = new ArrayList<>();
        for (LocalDate start : starts) {
            if (found.size() > MAX_PER_SERIES) {
                break;
            }
            String original = COMPACT_DATE.format(start);
            GoogleEvent instance = byOriginal.remove(start);
            if (instance != null) {
                if (shows(instance) && instance.getStartDate() != null
                        && window.overlaps(instance.getStartDate(), instance.getEndDate())) {
                    found.add(new Occurrence(instance, true, null, null, instance.getStartDate(),
                            instance.getEndDate(), original));
                }
                continue;
            }
            LocalDate end = start.plusDays(days);
            if (window.overlaps(start, end)) {
                found.add(new Occurrence(master, false, null, null, start, end, original));
            }
        }
        for (GoogleEvent instance : byOriginal.values()) {
            if (shows(instance) && instance.getStartDate() != null
                    && window.overlaps(instance.getStartDate(), instance.getEndDate())) {
                found.add(new Occurrence(instance, true, null, null, instance.getStartDate(), instance.getEndDate(),
                        COMPACT_DATE.format(instance.getOriginalStartDate())));
            }
        }
        found.sort(Comparator.comparing(Occurrence::startDate));
        return found;
    }

    private static boolean shows(GoogleEvent instance) {
        return instance.getStatus() != GoogleEventStatus.CANCELLED;
    }

    private static ZoneId zoneOf(GoogleEvent master, ZoneId fallback) {
        if (master.getTimeZone() == null) {
            return fallback;
        }
        try {
            return ZoneId.of(master.getTimeZone());
        } catch (DateTimeException e) {
            return fallback;
        }
    }

    /**
     * The dates or instants of one EXDATE/RDATE line: {@code ;VALUE=DATE:20250617},
     * {@code :20250617T130000Z}, {@code ;TZID=America/New_York:20250617T090000}, floating
     * times in the series' zone, and comma lists of any of those. PERIOD values are skipped.
     */
    private static List<Object> values(String line, ZoneId seriesZone, GoogleEvent master) {
        int colon = line.indexOf(':');
        if (colon < 0) {
            return List.of();
        }
        ZoneId zone = seriesZone;
        for (String parameter : line.substring(0, colon).split(";")) {
            String upper = parameter.toUpperCase(Locale.ROOT);
            if (upper.equals("VALUE=PERIOD")) {
                log.info("Skipping a PERIOD date list it does not support, eventId={}: {}",
                        master.getGoogleEventId(), line);
                return List.of();
            }
            if (upper.startsWith("TZID=")) {
                try {
                    zone = ZoneId.of(parameter.substring("TZID=".length()));
                } catch (DateTimeException e) {
                    zone = seriesZone;
                }
            }
        }
        List<Object> values = new ArrayList<>();
        for (String raw : line.substring(colon + 1).split(",")) {
            String value = raw.trim();
            try {
                if (value.length() == 8) {
                    values.add(LocalDate.parse(value, COMPACT_DATE));
                } else if (value.endsWith("Z") || value.endsWith("z")) {
                    values.add(Instant.from(COMPACT_UTC.parse(value.toUpperCase(Locale.ROOT))));
                } else {
                    values.add(LocalDateTime.parse(value, COMPACT_LOCAL).atZone(zone).toInstant());
                }
            } catch (DateTimeException e) {
                log.warn("Skipping a date it cannot read, eventId={}: {}", master.getGoogleEventId(), value);
            }
        }
        return values;
    }
}
