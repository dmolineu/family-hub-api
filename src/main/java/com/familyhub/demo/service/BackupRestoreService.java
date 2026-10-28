package com.familyhub.demo.service;

import com.familyhub.demo.dto.BackupDocument;
import com.familyhub.demo.dto.BackupDocument.CategoryBackup;
import com.familyhub.demo.dto.BackupDocument.ChoreBackup;
import com.familyhub.demo.dto.BackupDocument.ActivityBackup;
import com.familyhub.demo.dto.BackupDocument.CompletionBackup;
import com.familyhub.demo.dto.BackupDocument.EventBackup;
import com.familyhub.demo.dto.BackupDocument.ItemBackup;
import com.familyhub.demo.dto.BackupDocument.ListBackup;
import com.familyhub.demo.dto.BackupDocument.MealEntryBackup;
import com.familyhub.demo.dto.BackupDocument.MealSlotBackup;
import com.familyhub.demo.dto.BackupDocument.MemberBackup;
import com.familyhub.demo.dto.BackupDocument.RecipeBackup;
import com.familyhub.demo.dto.RestoreSummary;
import com.familyhub.demo.exception.BadRequestException;
import com.familyhub.demo.exception.ResourceNotFoundException;
import com.familyhub.demo.model.CalendarEvent;
import com.familyhub.demo.model.ChoreActivity;
import com.familyhub.demo.model.ChoreCompletionStatus;
import com.familyhub.demo.model.ChorePeriodCompletion;
import com.familyhub.demo.model.MemberRole;
import com.familyhub.demo.model.ChoreTemplate;
import com.familyhub.demo.model.EventSource;
import com.familyhub.demo.model.Family;
import com.familyhub.demo.model.FamilyMember;
import com.familyhub.demo.model.ListCategory;
import com.familyhub.demo.model.ListCategoryCatalogScope;
import com.familyhub.demo.model.ListKind;
import com.familyhub.demo.model.ListPreferences;
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
import com.familyhub.demo.repository.ListCategoryCatalogScopeRepository;
import com.familyhub.demo.repository.ListCategoryRepository;
import com.familyhub.demo.repository.ListPreferencesRepository;
import com.familyhub.demo.repository.MealSlotRepository;
import com.familyhub.demo.repository.RecipeRepository;
import com.familyhub.demo.repository.SharedListItemRepository;
import com.familyhub.demo.repository.SharedListRepository;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Replaces a family's data from a backup document in one transaction: the file is checked
 * first so a bad one changes nothing, members are matched by name so their ids (and
 * Google connections) survive, then every module is wiped and rebuilt in dependency order.
 */
@Service
@RequiredArgsConstructor
public class BackupRestoreService {
    private final FamilyRepository familyRepository;
    private final FamilyMemberRepository familyMemberRepository;
    private final ChoreTemplateRepository choreTemplateRepository;
    private final ChorePeriodCompletionRepository chorePeriodCompletionRepository;
    private final ChoreActivityRepository choreActivityRepository;
    private final ListPreferencesRepository listPreferencesRepository;
    private final ListCategoryRepository listCategoryRepository;
    private final ListCategoryCatalogScopeRepository listCategoryCatalogScopeRepository;
    private final SharedListRepository sharedListRepository;
    private final SharedListItemRepository sharedListItemRepository;
    private final RecipeRepository recipeRepository;
    private final MealSlotRepository mealSlotRepository;
    private final CalendarEventRepository calendarEventRepository;
    private final ThemePackService themePackService;
    private final AvatarPackService avatarPackService;
    private final RewardService rewardService;
    private final EntityManager entityManager;

    @Transactional
    public RestoreSummary restore(BackupDocument document, Family principal) {
        validate(document);
        Family family = managedFamily(principal.getId());

        family.setName(document.family().name().trim());
        family.setTimezone(FamilyTimezoneResolver.resolveStoredTimezoneOrDefault(document.family().timezone()));
        family.setWeatherLocation(document.family().weatherLocation());
        family.setLatitude(document.family().latitude());
        family.setLongitude(document.family().longitude());
        familyRepository.save(family);

        Map<String, UUID> memberIdsByRef = upsertMembers(document.members(), family);
        wipe(family, new HashSet<>(memberIdsByRef.values()));

        // The bulk deletes cleared the persistence context; continue on fresh entities.
        family = managedFamily(family.getId());
        Map<String, FamilyMember> members = new HashMap<>();
        for (Map.Entry<String, UUID> entry : memberIdsByRef.entrySet()) {
            members.put(entry.getKey(), familyMemberRepository.findById(entry.getValue()).orElseThrow());
        }

        restoreLists(document, family);
        Map<String, Recipe> recipes = restoreRecipes(document.recipes(), family);
        restoreMeals(document.meals(), family, recipes);
        Map<String, ChoreTemplate> chores = restoreChores(document.chores(), family, members);
        restoreActivity(document.activity(), family, members, chores);
        restoreEvents(document.events(), family, members);
        int themePacks = themePackService.replaceAll(document.themePacks(), family);
        int avatarPacks = avatarPackService.replaceAll(document.avatarPacks(), family);
        int rewards = rewardService.replaceAll(document.rewards(), family);

        return new RestoreSummary(
                document.members().size(),
                document.chores().size(),
                document.lists().lists().size(),
                document.recipes().size(),
                document.meals().size(),
                document.events().size(),
                themePacks,
                avatarPacks,
                rewards
        );
    }

    // ---------------------------------------------------------------- validation

    private static void validate(BackupDocument document) {
        if (document.version() == null || document.version() != BackupDocument.CURRENT_VERSION) {
            throw new BadRequestException(
                    "This file is not a Family Hub backup (version " + document.version() + ").");
        }
        if (!document.members().isEmpty()
                && document.members().stream().allMatch(m -> m.role() == MemberRole.KID)) {
            throw new BadRequestException("This backup has no parent. A family needs at least one parent.");
        }
        Set<String> memberRefs = new HashSet<>();
        document.members().forEach(m -> memberRefs.add(m.ref()));

        for (ChoreBackup chore : document.chores()) {
            if (chore.assignedTo() != null && !memberRefs.contains(chore.assignedTo())) {
                throw new BadRequestException("Chore \"" + chore.title() + "\" refers to an unknown member.");
            }
            for (CompletionBackup completion : chore.completions()) {
                if (completion.completedBy() != null && !memberRefs.contains(completion.completedBy())) {
                    throw new BadRequestException(
                            "A completion of \"" + chore.title() + "\" refers to an unknown member.");
                }
            }
        }

        Map<String, ListKind> categoryKinds = new HashMap<>();
        for (CategoryBackup category : document.lists().categories()) {
            categoryKinds.put(category.ref(), category.kind());
        }
        for (ListBackup list : document.lists().lists()) {
            for (ItemBackup item : list.items()) {
                if (item.category() == null) continue;
                ListKind kind = categoryKinds.get(item.category());
                if (kind == null) {
                    throw new BadRequestException("Item \"" + item.text() + "\" refers to an unknown category.");
                }
                if (kind != list.kind()) {
                    throw new BadRequestException(
                            "Item \"" + item.text() + "\" uses a category from a different kind of list.");
                }
            }
        }

        Set<String> recipeRefs = new HashSet<>();
        document.recipes().forEach(r -> recipeRefs.add(r.ref()));
        for (MealSlotBackup slot : document.meals()) {
            for (MealEntryBackup entry : slot.entries()) {
                if (entry.recipe() != null && !recipeRefs.contains(entry.recipe())) {
                    throw new BadRequestException(
                            "Meal entry \"" + entry.titleSnapshot() + "\" refers to an unknown recipe.");
                }
            }
        }

        Set<String> parentRefs = new HashSet<>();
        document.events().stream().filter(e -> e.recurringEvent() == null).forEach(e -> parentRefs.add(e.ref()));
        for (EventBackup event : document.events()) {
            if (!memberRefs.contains(event.member())) {
                throw new BadRequestException("Event \"" + event.title() + "\" refers to an unknown member.");
            }
            if (event.recurringEvent() != null && !parentRefs.contains(event.recurringEvent())) {
                throw new BadRequestException(
                        "Event \"" + event.title() + "\" refers to an unknown recurring event.");
            }
        }
    }

    // ---------------------------------------------------------------- members and wipe

    private Map<String, UUID> upsertMembers(List<MemberBackup> backups, Family family) {
        Map<String, FamilyMember> existingByName = new HashMap<>();
        for (FamilyMember member : familyMemberRepository.findByFamily(family)) {
            existingByName.putIfAbsent(normalize(member.getName()), member);
        }
        Map<String, UUID> idsByRef = new HashMap<>();
        for (MemberBackup backup : backups) {
            FamilyMember member = existingByName.get(normalize(backup.name()));
            if (member == null) {
                member = new FamilyMember();
                member.setFamily(family);
                member.setName(backup.name().trim());
            }
            member.setColor(backup.color());
            member.setAvatarUrl(MemberAvatars.normalize(backup.avatarUrl()).orElse(null));
            member.setEmail(backup.email());
            member.setBirthday(backup.birthday());
            if (backup.role() != null) {
                member.setRole(backup.role());
            }
            if (member.getRole() == MemberRole.KID) {
                // A kid has no PIN. Parents keep theirs: backups never carry PINs.
                member.setPinHash(null);
                member.setPinFailedAttempts(0);
                member.setPinLockedUntil(null);
            }
            member = familyMemberRepository.save(member);
            existingByName.put(normalize(member.getName()), member);
            idsByRef.put(backup.ref(), member.getId());
        }
        return idsByRef;
    }

    /** Everything the family owns except the members being kept. Order respects the one NO ACTION FK. */
    private void wipe(Family family, Set<UUID> membersToKeep) {
        mealSlotRepository.deleteAll(mealSlotRepository.findByFamilyOrderByWeekStartDateAscDayIndexAscMealTypeAsc(family));
        recipeRepository.deleteAll(recipeRepository.findByFamilyOrderByCreatedAtAsc(family));
        choreActivityRepository.deleteByFamily(family);
        chorePeriodCompletionRepository.deleteByFamily(family);
        choreTemplateRepository.deleteByFamily(family);
        calendarEventRepository.deleteNativeByFamily(family);
        sharedListItemRepository.deleteByFamilyId(family.getId());
        sharedListRepository.deleteByFamily(family);
        listCategoryRepository.deleteByFamily(family);
        listCategoryCatalogScopeRepository.deleteByFamily(family);
        listPreferencesRepository.findByFamily(family).ifPresent(listPreferencesRepository::delete);
        for (FamilyMember member : familyMemberRepository.findByFamily(family)) {
            if (!membersToKeep.contains(member.getId())) {
                familyMemberRepository.delete(member);
            }
        }
        entityManager.flush();
        entityManager.clear();
    }

    // ---------------------------------------------------------------- modules

    private void restoreLists(BackupDocument document, Family family) {
        ListPreferences preferences = new ListPreferences();
        preferences.setFamily(family);
        preferences.setShowCompletedByDefault(
                document.lists().showCompletedByDefault() == null || document.lists().showCompletedByDefault());
        listPreferencesRepository.save(preferences);

        for (ListKind kind : ListKind.values()) {
            ListCategoryCatalogScope scope = new ListCategoryCatalogScope();
            scope.setFamily(family);
            scope.setKind(kind);
            listCategoryCatalogScopeRepository.save(scope);
        }

        Map<String, ListCategory> categories = new HashMap<>();
        for (CategoryBackup backup : document.lists().categories()) {
            ListCategory category = new ListCategory();
            category.setFamily(family);
            category.setKind(backup.kind());
            category.setName(backup.name().trim());
            category.setSortOrder(backup.sortOrder());
            categories.put(backup.ref(), listCategoryRepository.save(category));
        }

        for (ListBackup backup : document.lists().lists()) {
            SharedList list = new SharedList();
            list.setFamily(family);
            list.setName(backup.name().trim());
            list.setKind(backup.kind());
            list.setCategoryDisplayMode(backup.categoryDisplayMode());
            list.setShowCompletedOverride(backup.showCompletedOverride());
            for (ItemBackup itemBackup : backup.items()) {
                SharedListItem item = new SharedListItem();
                item.setList(list);
                item.setText(itemBackup.text());
                item.setCompleted(itemBackup.completed());
                item.setCompletedAt(itemBackup.completedAt());
                item.setCategory(itemBackup.category() == null ? null : categories.get(itemBackup.category()));
                list.getItems().add(item);
            }
            sharedListRepository.save(list);
        }
    }

    private Map<String, Recipe> restoreRecipes(List<RecipeBackup> backups, Family family) {
        Map<String, Recipe> recipes = new HashMap<>();
        for (RecipeBackup backup : backups) {
            Recipe recipe = new Recipe();
            recipe.setFamily(family);
            recipe.setTitle(backup.title().trim());
            recipe.setImageUrl(backup.imageUrl());
            recipe.setNote(backup.note());
            recipe.setSourceUrl(backup.sourceUrl());
            recipe.setFavorite(backup.favorite());
            for (int i = 0; i < backup.ingredients().size(); i++) {
                RecipeIngredient ingredient = new RecipeIngredient();
                ingredient.setRecipe(recipe);
                ingredient.setSortOrder(i);
                ingredient.setText(backup.ingredients().get(i));
                recipe.getIngredients().add(ingredient);
            }
            for (int i = 0; i < backup.instructions().size(); i++) {
                RecipeInstruction instruction = new RecipeInstruction();
                instruction.setRecipe(recipe);
                instruction.setSortOrder(i);
                instruction.setText(backup.instructions().get(i));
                recipe.getInstructions().add(instruction);
            }
            Set<String> seenTags = new HashSet<>();
            for (String name : backup.tags()) {
                if (!seenTags.add(name)) continue;
                RecipeTag tag = new RecipeTag();
                tag.setRecipe(recipe);
                tag.setSortOrder(seenTags.size() - 1);
                tag.setName(name);
                recipe.getTags().add(tag);
            }
            recipes.put(backup.ref(), recipeRepository.save(recipe));
        }
        return recipes;
    }

    private void restoreMeals(List<MealSlotBackup> backups, Family family, Map<String, Recipe> recipes) {
        for (MealSlotBackup backup : backups) {
            MealSlot slot = new MealSlot();
            slot.setFamily(family);
            slot.setWeekStartDate(backup.weekStartDate());
            slot.setDayIndex(backup.dayIndex());
            slot.setMealType(backup.mealType());
            slot.setNote(backup.note());
            for (int i = 0; i < backup.entries().size(); i++) {
                MealEntryBackup entryBackup = backup.entries().get(i);
                MealSlotEntry entry = new MealSlotEntry();
                entry.setSlot(slot);
                entry.setRole(entryBackup.role());
                entry.setSortOrder(i);
                entry.setSourceType(entryBackup.sourceType());
                entry.setRecipe(entryBackup.recipe() == null ? null : recipes.get(entryBackup.recipe()));
                entry.setTitleSnapshot(entryBackup.titleSnapshot());
                entry.setImageUrlSnapshot(entryBackup.imageUrlSnapshot());
                entry.setNoteSnapshot(entryBackup.noteSnapshot());
                slot.getEntries().add(entry);
            }
            mealSlotRepository.save(slot);
        }
    }

    private Map<String, ChoreTemplate> restoreChores(List<ChoreBackup> backups, Family family,
                                                     Map<String, FamilyMember> members) {
        Map<String, ChoreTemplate> byRef = new HashMap<>();
        for (ChoreBackup backup : backups) {
            ChoreTemplate template = new ChoreTemplate();
            template.setFamily(family);
            template.setAssignedToMember(backup.assignedTo() == null ? null : members.get(backup.assignedTo()));
            template.setTitle(backup.title().trim());
            template.setCadence(backup.cadence());
            template.setDaysOfWeek(backup.daysOfWeek() == null || backup.daysOfWeek().isEmpty()
                    ? EnumSet.noneOf(DayOfWeek.class)
                    : EnumSet.copyOf(backup.daysOfWeek()));
            template.setActiveFrom(backup.activeFrom());
            template.setArchivedAt(backup.archivedAt());
            template.setPoints(backup.points());
            ChoreTemplate saved = choreTemplateRepository.save(template);
            byRef.put(backup.ref(), saved);
            for (CompletionBackup completionBackup : backup.completions()) {
                ChorePeriodCompletion completion = new ChorePeriodCompletion();
                completion.setChoreTemplate(saved);
                completion.setPeriodStartDate(completionBackup.periodStartDate());
                completion.setPeriodEndDate(completionBackup.periodEndDate());
                completion.setCompletedAt(completionBackup.completedAt());
                completion.setCompletedByMember(
                        completionBackup.completedBy() == null ? null : members.get(completionBackup.completedBy()));
                completion.setStatus(completionBackup.status() == null
                        ? ChoreCompletionStatus.APPROVED : completionBackup.status());
                completion.setPointsAwarded(completionBackup.pointsAwarded());
                chorePeriodCompletionRepository.save(completion);
            }
        }
        return byRef;
    }

    /** Replays the chore log; a ref that matches nothing in the file becomes a name only. */
    private void restoreActivity(List<ActivityBackup> backups, Family family, Map<String, FamilyMember> members,
                                 Map<String, ChoreTemplate> chores) {
        if (backups == null) {
            return;
        }
        for (ActivityBackup backup : backups) {
            ChoreActivity activity = new ChoreActivity();
            activity.setFamily(family);
            activity.setChoreTemplate(backup.chore() == null ? null : chores.get(backup.chore()));
            activity.setChoreTitle(backup.choreTitle());
            activity.setMember(backup.member() == null ? null : members.get(backup.member()));
            activity.setMemberName(backup.memberName());
            activity.setKind(backup.kind());
            activity.setPoints(backup.points());
            activity.setActor(backup.actor() == null ? null : members.get(backup.actor()));
            activity.setActorName(backup.actorName());
            activity.setReason(backup.reason());
            activity.setPeriodStartDate(backup.periodStartDate());
            activity.setOccurredAt(backup.occurredAt());
            choreActivityRepository.save(activity);
        }
    }

    private void restoreEvents(List<EventBackup> backups, Family family, Map<String, FamilyMember> members) {
        Map<String, CalendarEvent> parents = new HashMap<>();
        // Parents and one-offs first; exception rows need their parent's new id.
        for (EventBackup backup : backups) {
            if (backup.recurringEvent() == null) {
                parents.put(backup.ref(), calendarEventRepository.save(toEvent(backup, family, members, null)));
            }
        }
        for (EventBackup backup : backups) {
            if (backup.recurringEvent() != null) {
                calendarEventRepository.save(toEvent(backup, family, members, parents.get(backup.recurringEvent())));
            }
        }
    }

    private static CalendarEvent toEvent(
            EventBackup backup, Family family, Map<String, FamilyMember> members, CalendarEvent parent) {
        CalendarEvent event = new CalendarEvent();
        event.setFamily(family);
        event.setMember(members.get(backup.member()));
        event.setTitle(backup.title());
        event.setDate(backup.date());
        event.setEndDate(backup.endDate());
        event.setStartTime(backup.startTime());
        event.setEndTime(backup.endTime());
        event.setAllDay(backup.allDay());
        event.setLocation(backup.location());
        event.setDescription(backup.description());
        event.setRecurrenceRule(backup.recurrenceRule());
        event.setRecurringEvent(parent);
        event.setOriginalDate(backup.originalDate());
        event.setCancelled(backup.cancelled());
        event.setExdates(backup.exdates());
        event.setSource(EventSource.NATIVE);
        return event;
    }

    private Family managedFamily(UUID familyId) {
        return familyRepository.findById(familyId)
                .orElseThrow(() -> new ResourceNotFoundException("Family", familyId));
    }

    private static String normalize(String name) {
        return name == null ? "" : name.trim().toLowerCase(Locale.ROOT);
    }
}
