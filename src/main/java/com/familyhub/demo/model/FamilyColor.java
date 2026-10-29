package com.familyhub.demo.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * A member's colour: one of the seven named colours, or any custom {@code #rrggbb}
 * the family picks (some characters' pictures look wrong on every named colour).
 * JSON carries it as {@code "coral"} or {@code "#4a90d9"}.
 *
 * <p>This was an enum until custom colours; it keeps the enum's constants and
 * {@link #fromValue}/{@link #toValue}, so callers did not change. The database
 * keeps the named colours as the enum stored them ({@code CORAL}); see
 * {@link FamilyColorConverter}.
 */
public final class FamilyColor {
    public static final FamilyColor CORAL = new FamilyColor("coral");
    public static final FamilyColor TEAL = new FamilyColor("teal");
    public static final FamilyColor GREEN = new FamilyColor("green");
    public static final FamilyColor PURPLE = new FamilyColor("purple");
    public static final FamilyColor YELLOW = new FamilyColor("yellow");
    public static final FamilyColor PINK = new FamilyColor("pink");
    public static final FamilyColor ORANGE = new FamilyColor("orange");

    private static final List<FamilyColor> NAMED = List.of(CORAL, TEAL, GREEN, PURPLE, YELLOW, PINK, ORANGE);
    private static final Pattern CUSTOM = Pattern.compile("^#[0-9a-f]{6}$");

    private final String value;

    private FamilyColor(String value) {
        this.value = value;
    }

    /**
     * Reads a named colour in any case, or a custom {@code #rrggbb} (kept lowercase).
     *
     * @throws IllegalArgumentException for anything else, which JSON turns into a 400
     */
    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public static FamilyColor fromValue(String value) {
        String normalised = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        for (FamilyColor named : NAMED) {
            if (named.value.equals(normalised)) {
                return named;
            }
        }
        if (CUSTOM.matcher(normalised).matches()) {
            return new FamilyColor(normalised);
        }
        throw new IllegalArgumentException(
                "A colour is coral, teal, green, purple, yellow, pink, orange or #rrggbb, not \"" + value + "\"");
    }

    /** The JSON form: the lowercase name, or {@code #rrggbb}. */
    @JsonValue
    public String toValue() {
        return value;
    }

    /** True for a {@code #rrggbb} the family picked, false for a named colour. */
    public boolean isCustom() {
        return value.startsWith("#");
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof FamilyColor color && color.value.equals(value);
    }

    @Override
    public int hashCode() {
        return Objects.hash(value);
    }

    @Override
    public String toString() {
        return value;
    }
}
