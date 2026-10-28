package com.familyhub.demo.inbox;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import java.util.List;

/**
 * What the reader found in a message: events to put on the calendar, a recipe, or
 * nothing. This is the structured output's schema, so every field is always present
 * ("" and [] for those that do not apply).
 */
public record MailReading(
        @JsonPropertyDescription("EVENTS for dated happenings, RECIPE for a recipe, NOTHING otherwise")
        Kind kind,
        @JsonPropertyDescription("The events, soonest first; [] unless kind is EVENTS")
        List<ReadEvent> events,
        @JsonPropertyDescription("The recipe; empty fields unless kind is RECIPE")
        ReadRecipe recipe,
        @JsonPropertyDescription("One short sentence describing the email, for the family's records")
        String summary) {

    public enum Kind { EVENTS, RECIPE, NOTHING }

    public enum Confidence { HIGH, MEDIUM, LOW }

    public record ReadEvent(
            @JsonPropertyDescription("Short wall-calendar title, at most 80 characters")
            String title,
            @JsonPropertyDescription("yyyy-MM-dd")
            String date,
            @JsonPropertyDescription("yyyy-MM-dd, the last day of an all-day event over several days; otherwise \"\"")
            String endDate,
            @JsonPropertyDescription("true when no time of day is given")
            boolean allDay,
            @JsonPropertyDescription("Like \"9:00 AM\"; \"\" for an all-day event")
            String startTime,
            @JsonPropertyDescription("Like \"10:30 AM\"; \"\" for an all-day event")
            String endTime,
            @JsonPropertyDescription("As written, or \"\"")
            String location,
            @JsonPropertyDescription("Names of the family members it is for, exactly as listed; [] for everyone or unclear")
            List<String> people,
            @JsonPropertyDescription("HIGH only when the date and any time are written plainly and certain")
            Confidence confidence,
            @JsonPropertyDescription("For MEDIUM or LOW, one short sentence saying what is unclear; otherwise \"\"")
            String uncertainty) {
    }

    public record ReadRecipe(
            String title,
            @JsonPropertyDescription("As written, or \"\"")
            String servings,
            @JsonPropertyDescription("One ingredient per item, with its quantity")
            List<String> ingredients,
            @JsonPropertyDescription("The steps in order")
            List<String> steps,
            @JsonPropertyDescription("A few short tags, e.g. dinner, vegetarian")
            List<String> tags,
            @JsonPropertyDescription("The recipe page's address when the email links to one; otherwise \"\"")
            String sourceUrl) {
    }
}
