package com.familyhub.demo.service;

import com.familyhub.demo.dto.BackupDocument;
import com.familyhub.demo.dto.BackupDocument.CategoryBackup;
import com.familyhub.demo.dto.BackupDocument.ActivityBackup;
import com.familyhub.demo.dto.BackupDocument.ChoreBackup;
import com.familyhub.demo.dto.BackupDocument.CompletionBackup;
import com.familyhub.demo.dto.BackupDocument.EventBackup;
import com.familyhub.demo.dto.BackupDocument.FamilyBackup;
import com.familyhub.demo.dto.BackupDocument.ItemBackup;
import com.familyhub.demo.dto.BackupDocument.ListBackup;
import com.familyhub.demo.dto.BackupDocument.ListsBackup;
import com.familyhub.demo.dto.BackupDocument.MealEntryBackup;
import com.familyhub.demo.dto.BackupDocument.MealSlotBackup;
import com.familyhub.demo.dto.BackupDocument.MemberBackup;
import com.familyhub.demo.dto.BackupDocument.RecipeBackup;
import com.familyhub.demo.exception.ResourceNotFoundException;
import com.familyhub.demo.model.CalendarEvent;
import com.familyhub.demo.model.ChorePeriodCompletion;
import com.familyhub.demo.model.ChoreTemplate;
import com.familyhub.demo.model.EventSource;
import com.familyhub.demo.model.Family;
import com.familyhub.demo.model.FamilyMember;
import com.familyhub.demo.model.MealSlot;
import com.familyhub.demo.model.MealSlotEntry;
import com.familyhub.demo.model.Recipe;
import com.familyhub.demo.model.RecipeIngredient;
import com.familyhub.demo.model.RecipeInstruction;
import com.familyhub.demo.model.RecipeTag;
import com.familyhub.demo.model.SharedList;
import com.familyhub.demo.model.SharedListItem;
import com.familyhub.demo.repository.CalendarEventRepository;
import com.familyhub.demo.repository.ChoreActivityRepository;
import com.familyhub.demo.repository.ChorePeriodCompletionRepository;
import com.familyhub.demo.repository.ChoreTemplateRepository;
import com.familyhub.demo.repository.FamilyMemberRepository;
import com.familyhub.demo.repository.FamilyRepository;
import com.familyhub.demo.repository.ListCategoryRepository;
import com.familyhub.demo.repository.ListPreferencesRepository;
import com.familyhub.demo.repository.MealSlotRepository;
import com.familyhub.demo.repository.RecipeRepository;
import com.familyhub.demo.repository.SharedListRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/** Builds the backup document for a family. Ids become refs that only link rows inside the file. */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class BackupExportService {
    private final FamilyRepository familyRepository;
    private final FamilyMemberRepository familyMemberRepository;
    private final ChoreTemplateRepository choreTemplateRepository;
    private final ChorePeriodCompletionRepository chorePeriodCompletionRepository;
    private final ChoreActivityRepository choreActivityRepository;
    private final ListPreferencesRepository listPreferencesRepository;
    private final ListCategoryRepository listCategoryRepository;
    private final SharedListRepository sharedListRepository;
    private final RecipeRepository recipeRepository;
    private final MealSlotRepository mealSlotRepository;
    private final CalendarEventRepository calendarEventRepository;
    private final ThemePackService themePackService;
    private final AvatarPackService avatarPackService;
    private final RewardService rewardService;
    private final Clock clock;

    public BackupDocument export(Family principal) {
        // The principal is detached (loaded by the auth filter); work on a managed copy.
        Family family = familyRepository.findById(principal.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Family", principal.getId()));

        return new BackupDocument(
                BackupDocument.CURRENT_VERSION,
                clock.instant(),
                new FamilyBackup(
                        family.getName(),
                        family.getTimezone(),
                        family.getWeatherLocation(),
                        family.getLatitude(),
                        family.getLongitude()
                ),
                members(family),
                chores(family),
                lists(family),
                recipes(family),
                meals(family),
                events(family),
                themePackService.exportAll(family),
                activity(family),
                avatarPackService.exportAll(family),
                rewardService.exportAll(family)
        );
    }

    private List<MemberBackup> members(Family family) {
        return familyMemberRepository.findByFamily(family).stream()
                // Never the PIN hash: backups carry no credentials.
                .map(m -> new MemberBackup(ref(m.getId()), m.getName(), m.getColor(), m.getAvatarUrl(), m.getEmail(),
                        m.getBirthday(), m.getRole()))
                .toList();
    }

    private List<ChoreBackup> chores(Family family) {
        Map<UUID, List<ChorePeriodCompletion>> completions = chorePeriodCompletionRepository.findByFamily(family).stream()
                .collect(Collectors.groupingBy(c -> c.getChoreTemplate().getId()));
        return choreTemplateRepository.findByFamilyOrderByCreatedAtAsc(family).stream()
                .map(t -> new ChoreBackup(
                        ref(t.getId()),
                        t.getTitle(),
                        t.getCadence(),
                        t.getDaysOfWeek().stream().sorted().toList(),
                        t.getAssignedToMember() == null ? null : ref(t.getAssignedToMember().getId()),
                        t.getActiveFrom(),
                        t.getArchivedAt(),
                        completions.getOrDefault(t.getId(), List.of()).stream()
                                .sorted(Comparator.comparing(ChorePeriodCompletion::getPeriodStartDate))
                                .map(c -> new CompletionBackup(
                                        c.getPeriodStartDate(),
                                        c.getPeriodEndDate(),
                                        c.getCompletedAt(),
                                        c.getCompletedByMember() == null ? null : ref(c.getCompletedByMember().getId()),
                                        c.getStatus(),
                                        c.getPointsAwarded()
                                ))
                                .toList(),
                        t.getPoints()
                ))
                .toList();
    }

    /** The whole chore log, oldest first so a restore replays it in order. */
    private List<ActivityBackup> activity(Family family) {
        List<ActivityBackup> lines = new java.util.ArrayList<>(
                choreActivityRepository.findByFamilyOrderByOccurredAtDescIdDesc(family).stream()
                        .map(a -> new ActivityBackup(
                                a.getChoreTemplate() == null ? null : ref(a.getChoreTemplate().getId()),
                                a.getChoreTitle(),
                                a.getMember() == null ? null : ref(a.getMember().getId()),
                                a.getMemberName(),
                                a.getKind(),
                                a.getPoints(),
                                a.getActor() == null ? null : ref(a.getActor().getId()),
                                a.getActorName(),
                                a.getReason(),
                                a.getPeriodStartDate(),
                                a.getOccurredAt()
                        ))
                        .toList());
        java.util.Collections.reverse(lines);
        return lines;
    }

    private ListsBackup lists(Family family) {
        Boolean showCompleted = listPreferencesRepository.findByFamily(family)
                .map(p -> p.isShowCompletedByDefault())
                .orElse(null);
        List<CategoryBackup> categories = listCategoryRepository.findByFamilyOrderByKindAscSortOrderAsc(family).stream()
                .map(c -> new CategoryBackup(ref(c.getId()), c.getKind(), c.getName(), c.getSortOrder()))
                .toList();
        List<ListBackup> lists = sharedListRepository.findByFamilyWithItems(family).stream()
                .sorted(Comparator.comparing(SharedList::getCreatedAt, Comparator.nullsLast(Comparator.naturalOrder())))
                .map(l -> new ListBackup(
                        l.getName(),
                        l.getKind(),
                        l.getCategoryDisplayMode(),
                        l.getShowCompletedOverride(),
                        l.getItems().stream()
                                .map(i -> new ItemBackup(
                                        i.getText(),
                                        i.isCompleted(),
                                        i.getCompletedAt(),
                                        i.getCategory() == null ? null : ref(i.getCategory().getId())
                                ))
                                .toList()
                ))
                .toList();
        return new ListsBackup(showCompleted, categories, lists);
    }

    private List<RecipeBackup> recipes(Family family) {
        return recipeRepository.findByFamilyOrderByCreatedAtAsc(family).stream()
                .map(r -> new RecipeBackup(
                        ref(r.getId()),
                        r.getTitle(),
                        r.getImageUrl(),
                        r.getNote(),
                        r.getSourceUrl(),
                        r.isFavorite(),
                        r.getIngredients().stream().map(RecipeIngredient::getText).toList(),
                        r.getInstructions().stream().map(RecipeInstruction::getText).toList(),
                        r.getTags().stream().map(RecipeTag::getName).toList()
                ))
                .toList();
    }

    private List<MealSlotBackup> meals(Family family) {
        return mealSlotRepository.findByFamilyOrderByWeekStartDateAscDayIndexAscMealTypeAsc(family).stream()
                .map(s -> new MealSlotBackup(
                        s.getWeekStartDate(),
                        s.getDayIndex(),
                        s.getMealType(),
                        s.getNote(),
                        s.getEntries().stream()
                                .sorted(Comparator.comparingInt(MealSlotEntry::getSortOrder))
                                .map(e -> new MealEntryBackup(
                                        e.getRole(),
                                        e.getSourceType(),
                                        e.getRecipe() == null ? null : ref(e.getRecipe().getId()),
                                        e.getTitleSnapshot(),
                                        e.getImageUrlSnapshot(),
                                        e.getNoteSnapshot()
                                ))
                                .toList()
                ))
                .toList();
    }

    private List<EventBackup> events(Family family) {
        // Parents and one-offs first so a restore can resolve exception rows in order.
        return calendarEventRepository.findByFamily(family).stream()
                .filter(e -> e.getSource() == EventSource.NATIVE)
                .sorted(Comparator
                        .comparing((CalendarEvent e) -> e.getRecurringEvent() != null)
                        .thenComparing(CalendarEvent::getDate)
                        .thenComparing(e -> e.getId().toString()))
                .map(e -> new EventBackup(
                        ref(e.getId()),
                        e.getTitle(),
                        e.getDate(),
                        e.getEndDate(),
                        e.getStartTime(),
                        e.getEndTime(),
                        e.isAllDay(),
                        ref(e.getMember().getId()),
                        e.getLocation(),
                        e.getDescription(),
                        e.getRecurrenceRule(),
                        e.getRecurringEvent() == null ? null : ref(e.getRecurringEvent().getId()),
                        e.getOriginalDate(),
                        e.isCancelled(),
                        e.getExdates()
                ))
                .toList();
    }

    private static String ref(UUID id) {
        return id.toString();
    }
}
