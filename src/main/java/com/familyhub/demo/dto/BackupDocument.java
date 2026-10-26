package com.familyhub.demo.dto;

import com.familyhub.demo.model.ChoreCadence;
import com.familyhub.demo.model.FamilyColor;
import com.familyhub.demo.model.ListCategoryDisplayMode;
import com.familyhub.demo.model.ListKind;
import com.familyhub.demo.model.MealEntrySourceType;
import com.familyhub.demo.model.MealSlotRole;
import com.familyhub.demo.model.MealType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

/**
 * Everything a family owns, as one file. Credentials and Google tokens are never part
 * of it; Google-synced calendar events are left out because they re-sync. The {@code ref}
 * fields are the ids at export time and only serve to link rows inside the document; a
 * restore assigns new ids.
 */
public record BackupDocument(
        @NotNull Integer version,
        Instant exportedAt,
        @NotNull @Valid FamilyBackup family,
        @NotNull @Valid List<MemberBackup> members,
        @NotNull @Valid List<ChoreBackup> chores,
        @NotNull @Valid ListsBackup lists,
        @NotNull @Valid List<RecipeBackup> recipes,
        @NotNull @Valid List<MealSlotBackup> meals,
        @NotNull @Valid List<EventBackup> events,
        /** Absent in backups taken before theme packs existed; treated as empty. */
        @Valid List<ThemePackBackup> themePacks
) {
    public static final int CURRENT_VERSION = 1;

    /** A look the family imported, as the exact document; validated again on restore. */
    public record ThemePackBackup(
            @NotBlank String name,
            boolean dark,
            @NotBlank String document
    ) {}

    public record FamilyBackup(
            @NotBlank String name,
            String timezone,
            String weatherLocation,
            Double latitude,
            Double longitude
    ) {}

    public record MemberBackup(
            @NotBlank String ref,
            @NotBlank String name,
            @NotNull FamilyColor color,
            String avatarUrl,
            String email,
            /** Absent in backups taken before birthdays existed. */
            MemberBirthday birthday
    ) {}

    public record ChoreBackup(
            @NotBlank String ref,
            @NotBlank String title,
            @NotNull ChoreCadence cadence,
            List<DayOfWeek> daysOfWeek,
            /** Member ref, or null for a chore anyone can do. */
            String assignedTo,
            @NotNull LocalDate activeFrom,
            LocalDateTime archivedAt,
            @NotNull @Valid List<CompletionBackup> completions
    ) {}

    public record CompletionBackup(
            @NotNull LocalDate periodStartDate,
            @NotNull LocalDate periodEndDate,
            @NotNull LocalDateTime completedAt,
            String completedBy
    ) {}

    public record ListsBackup(
            Boolean showCompletedByDefault,
            @NotNull @Valid List<CategoryBackup> categories,
            @NotNull @Valid List<ListBackup> lists
    ) {}

    public record CategoryBackup(
            @NotBlank String ref,
            @NotNull ListKind kind,
            @NotBlank String name,
            int sortOrder
    ) {}

    public record ListBackup(
            @NotBlank String name,
            @NotNull ListKind kind,
            @NotNull ListCategoryDisplayMode categoryDisplayMode,
            Boolean showCompletedOverride,
            @NotNull @Valid List<ItemBackup> items
    ) {}

    public record ItemBackup(
            @NotBlank String text,
            boolean completed,
            LocalDateTime completedAt,
            /** Category ref, or null. */
            String category
    ) {}

    public record RecipeBackup(
            @NotBlank String ref,
            @NotBlank String title,
            String imageUrl,
            String note,
            String sourceUrl,
            boolean favorite,
            @NotNull List<String> ingredients,
            @NotNull List<String> instructions,
            @NotNull List<String> tags
    ) {}

    public record MealSlotBackup(
            @NotNull LocalDate weekStartDate,
            int dayIndex,
            @NotNull MealType mealType,
            String note,
            @NotNull @Valid List<MealEntryBackup> entries
    ) {}

    public record MealEntryBackup(
            @NotNull MealSlotRole role,
            @NotNull MealEntrySourceType sourceType,
            /** Recipe ref, or null. */
            String recipe,
            @NotBlank String titleSnapshot,
            String imageUrlSnapshot,
            String noteSnapshot
    ) {}

    public record EventBackup(
            @NotBlank String ref,
            @NotBlank String title,
            @NotNull LocalDate date,
            LocalDate endDate,
            @NotNull LocalTime startTime,
            @NotNull LocalTime endTime,
            boolean allDay,
            @NotBlank String member,
            String location,
            String description,
            String recurrenceRule,
            /** Ref of the recurring parent when this row is an exception. */
            String recurringEvent,
            LocalDate originalDate,
            boolean cancelled,
            String exdates
    ) {}
}
