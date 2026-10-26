package com.familyhub.demo.service;

import com.familyhub.demo.dto.ChoreBoardItemResponse;
import com.familyhub.demo.dto.ChoreBoardResponse;
import com.familyhub.demo.dto.ChoreCurrentPeriodStateResponse;
import com.familyhub.demo.dto.ChoreTemplateResponse;
import com.familyhub.demo.dto.CreateChoreTemplateRequest;
import com.familyhub.demo.dto.UpdateChoreTemplateRequest;
import com.familyhub.demo.dto.UpdateCurrentPeriodCompletionRequest;
import com.familyhub.demo.exception.BadRequestException;
import com.familyhub.demo.model.ChoreCadence;
import com.familyhub.demo.model.ChoreCompletionStatus;
import com.familyhub.demo.model.ChorePeriodCompletion;
import com.familyhub.demo.model.ChoreScope;
import com.familyhub.demo.model.ChoreTemplate;
import com.familyhub.demo.model.Family;
import com.familyhub.demo.model.FamilyMember;
import com.familyhub.demo.repository.ChorePeriodCompletionRepository;
import com.familyhub.demo.repository.ChoreTemplateRepository;
import com.familyhub.demo.repository.FamilyMemberRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.EnumSet;
import java.util.Optional;
import java.util.UUID;

import static com.familyhub.demo.TestDataFactory.CHORE_TEMPLATE_ID;
import static com.familyhub.demo.TestDataFactory.MEMBER_ID;
import static com.familyhub.demo.TestDataFactory.createChoreTemplate;
import static com.familyhub.demo.TestDataFactory.createFamily;
import static com.familyhub.demo.TestDataFactory.createFamilyMember;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ChoreServiceTest {

    @Mock
    private ChoreTemplateRepository choreTemplateRepository;

    @Mock
    private ChorePeriodCompletionRepository chorePeriodCompletionRepository;

    @Mock
    private ChorePeriodCompletionWriter chorePeriodCompletionWriter;

    @Mock
    private FamilyMemberRepository familyMemberRepository;

    @Mock
    private ChoreActivityLog choreActivityLog;

    @Mock
    private ParentPinService parentPinService;

    private ChoreService choreService;
    private Family family;
    private FamilyMember member;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(Instant.parse("2026-05-17T16:00:00Z"), ZoneOffset.UTC);
        choreService = new ChoreService(
                choreTemplateRepository,
                chorePeriodCompletionRepository,
                chorePeriodCompletionWriter,
                familyMemberRepository,
                choreActivityLog,
                parentPinService,
                clock
        );
        family = createFamily();
        family.setTimezone("America/Los_Angeles");
        member = createFamilyMember(family);
        member.setId(MEMBER_ID);
    }

    private static final UUID SECOND_MEMBER_ID = UUID.fromString("00000000-0000-0000-0000-000000000009");

    @Test
    void getBoard_groupsTemplatesIntoTodayWeekAndMonth() {
        ChoreTemplate daily = createChoreTemplate(
                family,
                member,
                "Brush teeth",
                ChoreCadence.DAILY,
                LocalDate.of(2026, 5, 17)
        );
        ChoreTemplate weekly = createChoreTemplate(
                family,
                member,
                "Take out trash",
                ChoreCadence.WEEKLY,
                LocalDate.of(2026, 5, 17)
        );
        ChoreTemplate monthly = createChoreTemplate(
                family,
                member,
                "Deep clean fridge",
                ChoreCadence.MONTHLY,
                LocalDate.of(2026, 5, 17)
        );

        when(choreTemplateRepository.findActiveByFamily(family)).thenReturn(List.of(daily, weekly, monthly));
        when(chorePeriodCompletionRepository.findByTemplateIdsAndPeriod(any(), any(), any())).thenReturn(List.of());

        ChoreBoardResponse board = choreService.getBoard(family);

        assertThat(board.timezone()).isEqualTo("America/Los_Angeles");
        assertThat(board.today().periodStartDate()).isEqualTo(LocalDate.of(2026, 5, 17));
        assertThat(board.thisWeek().periodStartDate()).isEqualTo(LocalDate.of(2026, 5, 17));
        assertThat(board.thisWeek().periodEndDate()).isEqualTo(LocalDate.of(2026, 5, 23));
        assertThat(board.thisMonth().periodStartDate()).isEqualTo(LocalDate.of(2026, 5, 1));
        assertThat(board.thisMonth().periodEndDate()).isEqualTo(LocalDate.of(2026, 5, 31));
        assertThat(board.today().summary().total()).isEqualTo(1);
        assertThat(board.thisWeek().summary().total()).isEqualTo(1);
        assertThat(board.thisMonth().summary().total()).isEqualTo(1);
    }

    @Test
    void getBoard_excludesTemplateBeforeActiveFromDate() {
        ChoreTemplate futureMonthly = createChoreTemplate(
                family,
                member,
                "Deep clean fridge",
                ChoreCadence.MONTHLY,
                LocalDate.of(2026, 6, 1)
        );

        when(choreTemplateRepository.findActiveByFamily(family)).thenReturn(List.of(futureMonthly));

        ChoreBoardResponse board = choreService.getBoard(family);

        assertThat(board.thisMonth().summary().total()).isZero();
    }

    @Test
    void getBoard_withInvalidStoredTimezone_fallsBackToPacificDefault() {
        family.setTimezone("Mars/Olympus");
        when(choreTemplateRepository.findActiveByFamily(family)).thenReturn(List.of());

        ChoreBoardResponse board = choreService.getBoard(family);

        assertThat(board.timezone()).isEqualTo("America/Los_Angeles");
    }

    @Test
    void getBoard_excludesWeeklyAndMonthlyTemplatesUntilTheirActiveFromDateWithinCurrentPeriod() {
        ChoreTemplate weeklyLaterThisWeek = createChoreTemplate(
                family,
                member,
                "Take out trash",
                ChoreCadence.WEEKLY,
                LocalDate.of(2026, 5, 20)
        );
        ChoreTemplate monthlyLaterThisMonth = createChoreTemplate(
                family,
                member,
                "Deep clean fridge",
                ChoreCadence.MONTHLY,
                LocalDate.of(2026, 5, 25)
        );

        when(choreTemplateRepository.findActiveByFamily(family))
                .thenReturn(List.of(weeklyLaterThisWeek, monthlyLaterThisMonth));

        ChoreBoardResponse board = choreService.getBoard(family);

        assertThat(board.thisWeek().summary().total()).isZero();
        assertThat(board.thisMonth().summary().total()).isZero();
        verifyNoInteractions(chorePeriodCompletionRepository);
    }

    @Test
    void getBoard_ordersIncompleteBeforeCompleted_thenByCreatedAtAndTitle() {
        ChoreTemplate bravo = createChoreTemplate(
                family,
                member,
                "Bravo",
                ChoreCadence.DAILY,
                LocalDate.of(2026, 5, 17)
        );
        bravo.setCreatedAt(LocalDateTime.of(2026, 5, 17, 9, 0));

        ChoreTemplate alpha = createChoreTemplate(
                family,
                member,
                "Alpha",
                ChoreCadence.DAILY,
                LocalDate.of(2026, 5, 17)
        );
        alpha.setCreatedAt(LocalDateTime.of(2026, 5, 17, 8, 0));

        ChoreTemplate completed = createChoreTemplate(
                family,
                member,
                "Completed",
                ChoreCadence.DAILY,
                LocalDate.of(2026, 5, 17)
        );
        completed.setCreatedAt(LocalDateTime.of(2026, 5, 17, 7, 0));

        ChorePeriodCompletion completion = new ChorePeriodCompletion();
        completion.setChoreTemplate(completed);
        completion.setPeriodStartDate(LocalDate.of(2026, 5, 17));
        completion.setPeriodEndDate(LocalDate.of(2026, 5, 17));
        completion.setCompletedAt(LocalDateTime.of(2026, 5, 17, 10, 0));

        when(choreTemplateRepository.findActiveByFamily(family)).thenReturn(List.of(bravo, completed, alpha));
        when(chorePeriodCompletionRepository.findByTemplateIdsAndPeriod(any(), any(), any()))
                .thenReturn(List.of(completion));

        ChoreBoardResponse board = choreService.getBoard(family);

        assertThat(board.today().assignees().getFirst().chores())
                .extracting(ChoreBoardItemResponse::title)
                .containsExactly("Alpha", "Bravo", "Completed");
        assertThat(board.today().assignees().getFirst().chores().get(2).completed()).isTrue();
    }

    @Test
    void createTemplates_withSeveralMembers_createsOneTemplatePerMember() {
        FamilyMember second = createFamilyMember(family);
        second.setId(SECOND_MEMBER_ID);
        when(familyMemberRepository.findById(MEMBER_ID)).thenReturn(Optional.of(member));
        when(familyMemberRepository.findById(SECOND_MEMBER_ID)).thenReturn(Optional.of(second));
        when(choreTemplateRepository.saveAll(any())).thenAnswer(invocation -> {
            List<ChoreTemplate> saved = invocation.getArgument(0);
            saved.forEach(template -> {
                template.setId(UUID.randomUUID());
                template.setCreatedAt(LocalDateTime.of(2026, 5, 17, 8, 0));
                template.setUpdatedAt(LocalDateTime.of(2026, 5, 17, 8, 0));
            });
            return saved;
        });

        List<ChoreTemplateResponse> responses = choreService.createTemplates(
                new CreateChoreTemplateRequest(
                        " Brush teeth ",
                        List.of(MEMBER_ID, SECOND_MEMBER_ID, MEMBER_ID),
                        ChoreCadence.DAILY,
                        null,
                        LocalDate.of(2026, 5, 17)
                ),
                family
        );

        // A member listed twice still gets one chore.
        assertThat(responses)
                .extracting(ChoreTemplateResponse::assignedToMemberId)
                .containsExactly(MEMBER_ID, SECOND_MEMBER_ID);
        assertThat(responses).allSatisfy(response -> {
            assertThat(response.title()).isEqualTo("Brush teeth");
            assertThat(response.cadence()).isEqualTo(ChoreCadence.DAILY);
            assertThat(response.activeFrom()).isEqualTo(LocalDate.of(2026, 5, 17));
            assertThat(response.archived()).isFalse();
        });
    }

    @Test
    void createTemplates_withNoMembers_createsOneUnassignedTemplate() {
        when(choreTemplateRepository.saveAll(any())).thenAnswer(invocation -> {
            List<ChoreTemplate> saved = invocation.getArgument(0);
            saved.forEach(template -> template.setId(CHORE_TEMPLATE_ID));
            return saved;
        });

        List<ChoreTemplateResponse> responses = choreService.createTemplates(
                new CreateChoreTemplateRequest("Feed the dog", List.of(), ChoreCadence.DAILY, null, LocalDate.of(2026, 5, 17)),
                family
        );

        assertThat(responses).hasSize(1);
        assertThat(responses.get(0).assignedToMemberId()).isNull();
        assertThat(responses.get(0).title()).isEqualTo("Feed the dog");
        verifyNoInteractions(familyMemberRepository);
    }

    @Test
    void updateTemplate_updatesEditableFieldsAndArchives() {
        FamilyMember newAssignee = createFamilyMember(family);
        newAssignee.setId(MEMBER_ID);
        ChoreTemplate template = createChoreTemplate(
                family,
                member,
                "Brush teeth",
                ChoreCadence.DAILY,
                LocalDate.of(2026, 5, 17)
        );
        when(choreTemplateRepository.findByFamilyAndId(family, template.getId())).thenReturn(Optional.of(template));
        when(familyMemberRepository.findById(MEMBER_ID)).thenReturn(Optional.of(newAssignee));
        when(choreTemplateRepository.save(template)).thenReturn(template);

        ChoreTemplateResponse response = choreService.updateTemplate(
                template.getId(),
                new UpdateChoreTemplateRequest(
                        "Take out trash",
                        MEMBER_ID,
                        ChoreCadence.WEEKLY,
                        null,
                        LocalDate.of(2026, 5, 18),
                        true
                ),
                family
        );

        assertThat(response.title()).isEqualTo("Take out trash");
        assertThat(response.cadence()).isEqualTo(ChoreCadence.WEEKLY);
        assertThat(response.activeFrom()).isEqualTo(LocalDate.of(2026, 5, 18));
        assertThat(response.archived()).isTrue();
        assertThat(template.getArchivedAt()).isEqualTo(LocalDateTime.of(2026, 5, 17, 16, 0));
    }

    @Test
    void completeCurrentPeriod_createsCompletionForCurrentPeriod() {
        ChoreTemplate daily = createChoreTemplate(
                family,
                member,
                "Brush teeth",
                ChoreCadence.DAILY,
                LocalDate.of(2026, 5, 17)
        );
        when(choreTemplateRepository.findByFamilyAndId(family, daily.getId())).thenReturn(Optional.of(daily));
        when(chorePeriodCompletionRepository.findByChoreTemplateAndPeriodStartDateAndPeriodEndDate(
                daily,
                LocalDate.of(2026, 5, 17),
                LocalDate.of(2026, 5, 17)
        )).thenReturn(Optional.empty());
        ChorePeriodCompletion savedCompletion = new ChorePeriodCompletion();
        savedCompletion.setChoreTemplate(daily);
        savedCompletion.setPeriodStartDate(LocalDate.of(2026, 5, 17));
        savedCompletion.setPeriodEndDate(LocalDate.of(2026, 5, 17));
        savedCompletion.setCompletedAt(LocalDateTime.of(2026, 5, 17, 16, 0));
        savedCompletion.setCompletedByMember(member);
        when(chorePeriodCompletionWriter.createCompletion(
                daily.getId(),
                LocalDate.of(2026, 5, 17),
                LocalDate.of(2026, 5, 17),
                LocalDateTime.of(2026, 5, 17, 16, 0),
                MEMBER_ID, ChoreCompletionStatus.APPROVED, 0
        )).thenReturn(savedCompletion);

        ChoreCurrentPeriodStateResponse response = choreService.completeCurrentPeriod(
                daily.getId(),
                new UpdateCurrentPeriodCompletionRequest(ChoreScope.TODAY, LocalDate.of(2026, 5, 17), null),
                family
        );

        assertThat(response.item().completed()).isTrue();
        assertThat(response.item().completedAt()).isEqualTo(LocalDateTime.of(2026, 5, 17, 16, 0));
        // An assigned chore credits its assignee without the caller naming anyone.
        assertThat(response.item().completedByMemberId()).isEqualTo(MEMBER_ID);
    }

    @Test
    void completeCurrentPeriod_unassignedTemplate_requiresWhoCompletedIt() {
        ChoreTemplate anyone = createChoreTemplate(family, null, "Feed the dog", ChoreCadence.DAILY, LocalDate.of(2026, 5, 17));
        when(choreTemplateRepository.findByFamilyAndId(family, anyone.getId())).thenReturn(Optional.of(anyone));

        assertThatThrownBy(() -> choreService.completeCurrentPeriod(
                anyone.getId(),
                new UpdateCurrentPeriodCompletionRequest(ChoreScope.TODAY, LocalDate.of(2026, 5, 17), null),
                family
        ))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("Choose who completed this chore.");
        verifyNoInteractions(chorePeriodCompletionWriter);
    }

    @Test
    void completeCurrentPeriod_unassignedTemplate_creditsWhoeverCompletedIt() {
        ChoreTemplate anyone = createChoreTemplate(family, null, "Feed the dog", ChoreCadence.DAILY, LocalDate.of(2026, 5, 17));
        when(choreTemplateRepository.findByFamilyAndId(family, anyone.getId())).thenReturn(Optional.of(anyone));
        when(familyMemberRepository.findById(MEMBER_ID)).thenReturn(Optional.of(member));
        when(chorePeriodCompletionRepository.findByChoreTemplateAndPeriodStartDateAndPeriodEndDate(
                anyone, LocalDate.of(2026, 5, 17), LocalDate.of(2026, 5, 17)
        )).thenReturn(Optional.empty());
        ChorePeriodCompletion saved = new ChorePeriodCompletion();
        saved.setChoreTemplate(anyone);
        saved.setPeriodStartDate(LocalDate.of(2026, 5, 17));
        saved.setPeriodEndDate(LocalDate.of(2026, 5, 17));
        saved.setCompletedAt(LocalDateTime.of(2026, 5, 17, 16, 0));
        saved.setCompletedByMember(member);
        when(chorePeriodCompletionWriter.createCompletion(
                anyone.getId(), LocalDate.of(2026, 5, 17), LocalDate.of(2026, 5, 17), LocalDateTime.of(2026, 5, 17, 16, 0), MEMBER_ID, ChoreCompletionStatus.APPROVED, 0
        )).thenReturn(saved);

        ChoreCurrentPeriodStateResponse response = choreService.completeCurrentPeriod(
                anyone.getId(),
                new UpdateCurrentPeriodCompletionRequest(ChoreScope.TODAY, LocalDate.of(2026, 5, 17), MEMBER_ID),
                family
        );

        assertThat(response.item().assignedToMemberId()).isNull();
        assertThat(response.item().completedByMemberId()).isEqualTo(MEMBER_ID);
    }

    @Test
    void createTemplates_weeklyOnSpecificDays_storesTheDays() {
        when(familyMemberRepository.findById(MEMBER_ID)).thenReturn(Optional.of(member));
        when(choreTemplateRepository.saveAll(any())).thenAnswer(invocation -> {
            List<ChoreTemplate> saved = invocation.getArgument(0);
            saved.forEach(template -> template.setId(CHORE_TEMPLATE_ID));
            return saved;
        });

        List<ChoreTemplateResponse> responses = choreService.createTemplates(
                new CreateChoreTemplateRequest(
                        "Take out bins",
                        List.of(MEMBER_ID),
                        ChoreCadence.WEEKLY,
                        Set.of(DayOfWeek.THURSDAY, DayOfWeek.MONDAY),
                        LocalDate.of(2026, 5, 17)
                ),
                family
        );

        assertThat(responses.get(0).daysOfWeek()).containsExactly(DayOfWeek.MONDAY, DayOfWeek.THURSDAY);
    }

    @Test
    void createTemplates_daysOnANonWeeklyChore_isRejected() {
        assertThatThrownBy(() -> choreService.createTemplates(
                new CreateChoreTemplateRequest(
                        "Brush teeth",
                        List.of(),
                        ChoreCadence.DAILY,
                        Set.of(DayOfWeek.MONDAY),
                        LocalDate.of(2026, 5, 17)
                ),
                family
        ))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("Days of the week apply to weekly chores only.");
        verifyNoInteractions(choreTemplateRepository);
    }

    @Test
    void getBoard_weeklyOnSpecificDays_showsUnderTodayOnlyOnThoseDays() {
        // The fixed clock is Sunday 2026-05-17 in Los Angeles.
        ChoreTemplate sundays = createChoreTemplate(family, member, "Water plants", ChoreCadence.WEEKLY, LocalDate.of(2026, 5, 10));
        sundays.setDaysOfWeek(EnumSet.of(DayOfWeek.SUNDAY, DayOfWeek.WEDNESDAY));
        ChoreTemplate mondays = createChoreTemplate(family, member, "Take out bins", ChoreCadence.WEEKLY, LocalDate.of(2026, 5, 10));
        mondays.setDaysOfWeek(EnumSet.of(DayOfWeek.MONDAY));
        ChoreTemplate anyDay = createChoreTemplate(family, member, "Vacuum", ChoreCadence.WEEKLY, LocalDate.of(2026, 5, 10));
        when(choreTemplateRepository.findActiveByFamily(family)).thenReturn(List.of(sundays, mondays, anyDay));
        when(chorePeriodCompletionRepository.findByTemplateIdsAndPeriod(any(), any(), any())).thenReturn(List.of());

        ChoreBoardResponse board = choreService.getBoard(family);

        assertThat(board.today().assignees().get(0).chores())
                .extracting(ChoreBoardItemResponse::title)
                .containsExactly("Water plants");
        assertThat(board.today().assignees().get(0).chores().get(0).daysOfWeek())
                .containsExactly(DayOfWeek.WEDNESDAY, DayOfWeek.SUNDAY);
        // Day-pinned weekly chores never double up in the week column.
        assertThat(board.thisWeek().assignees().get(0).chores())
                .extracting(ChoreBoardItemResponse::title)
                .containsExactly("Vacuum");
        assertThat(board.today().summary().total()).isEqualTo(1);
    }

    @Test
    void completeCurrentPeriod_weeklyOnSpecificDays_usesTodayAsThePeriod() {
        ChoreTemplate sundays = createChoreTemplate(family, member, "Water plants", ChoreCadence.WEEKLY, LocalDate.of(2026, 5, 10));
        sundays.setDaysOfWeek(EnumSet.of(DayOfWeek.SUNDAY));
        when(choreTemplateRepository.findByFamilyAndId(family, sundays.getId())).thenReturn(Optional.of(sundays));
        when(chorePeriodCompletionRepository.findByChoreTemplateAndPeriodStartDateAndPeriodEndDate(
                sundays, LocalDate.of(2026, 5, 17), LocalDate.of(2026, 5, 17)
        )).thenReturn(Optional.empty());
        ChorePeriodCompletion saved = new ChorePeriodCompletion();
        saved.setChoreTemplate(sundays);
        saved.setPeriodStartDate(LocalDate.of(2026, 5, 17));
        saved.setPeriodEndDate(LocalDate.of(2026, 5, 17));
        saved.setCompletedAt(LocalDateTime.of(2026, 5, 17, 16, 0));
        saved.setCompletedByMember(member);
        when(chorePeriodCompletionWriter.createCompletion(
                sundays.getId(), LocalDate.of(2026, 5, 17), LocalDate.of(2026, 5, 17), LocalDateTime.of(2026, 5, 17, 16, 0), MEMBER_ID, ChoreCompletionStatus.APPROVED, 0
        )).thenReturn(saved);

        ChoreCurrentPeriodStateResponse response = choreService.completeCurrentPeriod(
                sundays.getId(),
                new UpdateCurrentPeriodCompletionRequest(ChoreScope.TODAY, LocalDate.of(2026, 5, 17), null),
                family
        );

        assertThat(response.scope()).isEqualTo(ChoreScope.TODAY);
        assertThat(response.periodEndDate()).isEqualTo(LocalDate.of(2026, 5, 17));
        assertThat(response.item().completed()).isTrue();
    }

    @Test
    void completeCurrentPeriod_weeklyOnSpecificDays_notDueToday_isRejected() {
        ChoreTemplate mondays = createChoreTemplate(family, member, "Take out bins", ChoreCadence.WEEKLY, LocalDate.of(2026, 5, 10));
        mondays.setDaysOfWeek(EnumSet.of(DayOfWeek.MONDAY));
        when(choreTemplateRepository.findByFamilyAndId(family, mondays.getId())).thenReturn(Optional.of(mondays));

        assertThatThrownBy(() -> choreService.completeCurrentPeriod(
                mondays.getId(),
                new UpdateCurrentPeriodCompletionRequest(ChoreScope.TODAY, LocalDate.of(2026, 5, 17), null),
                family
        ))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("Chore template is not active for the current period.");
        verifyNoInteractions(chorePeriodCompletionWriter);
    }

    @Test
    void getBoard_listsUnassignedChoresUnderAnyoneFirst() {
        ChoreTemplate anyone = createChoreTemplate(family, null, "Feed the dog", ChoreCadence.DAILY, LocalDate.of(2026, 5, 17));
        ChoreTemplate mine = createChoreTemplate(family, member, "Brush teeth", ChoreCadence.DAILY, LocalDate.of(2026, 5, 17));
        when(choreTemplateRepository.findActiveByFamily(family)).thenReturn(List.of(mine, anyone));
        when(chorePeriodCompletionRepository.findByTemplateIdsAndPeriod(any(), any(), any())).thenReturn(List.of());

        ChoreBoardResponse board = choreService.getBoard(family);

        assertThat(board.today().assignees()).hasSize(2);
        assertThat(board.today().assignees().get(0).member()).isNull();
        assertThat(board.today().assignees().get(0).chores())
                .extracting(ChoreBoardItemResponse::title)
                .containsExactly("Feed the dog");
        assertThat(board.today().assignees().get(1).member().id()).isEqualTo(MEMBER_ID);
        assertThat(board.today().summary().total()).isEqualTo(2);
    }

    @Test
    void completeCurrentPeriod_whenTemplateArchived_throwsBadRequest() {
        ChoreTemplate archived = createChoreTemplate(
                family,
                member,
                "Brush teeth",
                ChoreCadence.DAILY,
                LocalDate.of(2026, 5, 17)
        );
        archived.setArchivedAt(LocalDateTime.of(2026, 5, 17, 15, 0));
        when(choreTemplateRepository.findByFamilyAndId(family, archived.getId())).thenReturn(Optional.of(archived));

        assertThatThrownBy(() -> choreService.completeCurrentPeriod(
                archived.getId(),
                new UpdateCurrentPeriodCompletionRequest(ChoreScope.TODAY, LocalDate.of(2026, 5, 17), null),
                family
        ))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("Chore template is not active for the current period.");
    }

    @Test
    void completeCurrentPeriod_whenTemplateNotYetActive_throwsBadRequest() {
        ChoreTemplate futureWeekly = createChoreTemplate(
                family,
                member,
                "Take out trash",
                ChoreCadence.WEEKLY,
                LocalDate.of(2026, 5, 20)
        );
        when(choreTemplateRepository.findByFamilyAndId(family, futureWeekly.getId()))
                .thenReturn(Optional.of(futureWeekly));

        assertThatThrownBy(() -> choreService.completeCurrentPeriod(
                futureWeekly.getId(),
                new UpdateCurrentPeriodCompletionRequest(ChoreScope.THIS_WEEK, LocalDate.of(2026, 5, 17), null),
                family
        ))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("Chore template is not active for the current period.");
    }

    @Test
    void completeCurrentPeriod_duplicateInsertRace_returnsExistingCompletion() {
        ChoreTemplate daily = createChoreTemplate(
                family,
                member,
                "Brush teeth",
                ChoreCadence.DAILY,
                LocalDate.of(2026, 5, 17)
        );
        ChorePeriodCompletion existingCompletion = new ChorePeriodCompletion();
        existingCompletion.setChoreTemplate(daily);
        existingCompletion.setPeriodStartDate(LocalDate.of(2026, 5, 17));
        existingCompletion.setPeriodEndDate(LocalDate.of(2026, 5, 17));
        existingCompletion.setCompletedAt(LocalDateTime.of(2026, 5, 17, 15, 59));

        when(choreTemplateRepository.findByFamilyAndId(family, daily.getId())).thenReturn(Optional.of(daily));
        when(chorePeriodCompletionRepository.findByChoreTemplateAndPeriodStartDateAndPeriodEndDate(
                daily,
                LocalDate.of(2026, 5, 17),
                LocalDate.of(2026, 5, 17)
        )).thenReturn(Optional.empty(), Optional.of(existingCompletion));
        when(chorePeriodCompletionWriter.createCompletion(
                daily.getId(),
                LocalDate.of(2026, 5, 17),
                LocalDate.of(2026, 5, 17),
                LocalDateTime.of(2026, 5, 17, 16, 0),
                MEMBER_ID, ChoreCompletionStatus.APPROVED, 0
        ))
                .thenThrow(new DataIntegrityViolationException("duplicate key"));

        ChoreCurrentPeriodStateResponse response = choreService.completeCurrentPeriod(
                daily.getId(),
                new UpdateCurrentPeriodCompletionRequest(ChoreScope.TODAY, LocalDate.of(2026, 5, 17), null),
                family
        );

        assertThat(response.item().completed()).isTrue();
        assertThat(response.item().completedAt()).isEqualTo(LocalDateTime.of(2026, 5, 17, 15, 59));
    }

    @Test
    void uncompleteCurrentPeriod_deletesCurrentCompletion() {
        ChoreTemplate daily = createChoreTemplate(
                family,
                member,
                "Brush teeth",
                ChoreCadence.DAILY,
                LocalDate.of(2026, 5, 17)
        );
        ChorePeriodCompletion completion = new ChorePeriodCompletion();
        completion.setChoreTemplate(daily);
        completion.setPeriodStartDate(LocalDate.of(2026, 5, 17));
        completion.setPeriodEndDate(LocalDate.of(2026, 5, 17));
        completion.setCompletedAt(LocalDateTime.of(2026, 5, 17, 10, 0));
        when(choreTemplateRepository.findByFamilyAndId(family, daily.getId())).thenReturn(Optional.of(daily));
        when(chorePeriodCompletionRepository.findByChoreTemplateAndPeriodStartDateAndPeriodEndDate(
                daily,
                LocalDate.of(2026, 5, 17),
                LocalDate.of(2026, 5, 17)
        )).thenReturn(Optional.of(completion));

        ChoreCurrentPeriodStateResponse response = choreService.uncompleteCurrentPeriod(
                daily.getId(),
                new UpdateCurrentPeriodCompletionRequest(ChoreScope.TODAY, LocalDate.of(2026, 5, 17), null),
                family
        );

        assertThat(response.item().completed()).isFalse();
    }

    @Test
    void uncompleteCurrentPeriod_whenTemplateArchived_throwsBadRequest() {
        ChoreTemplate archived = createChoreTemplate(
                family,
                member,
                "Brush teeth",
                ChoreCadence.DAILY,
                LocalDate.of(2026, 5, 17)
        );
        archived.setArchivedAt(LocalDateTime.of(2026, 5, 17, 15, 0));
        when(choreTemplateRepository.findByFamilyAndId(family, archived.getId())).thenReturn(Optional.of(archived));

        assertThatThrownBy(() -> choreService.uncompleteCurrentPeriod(
                archived.getId(),
                new UpdateCurrentPeriodCompletionRequest(ChoreScope.TODAY, LocalDate.of(2026, 5, 17), null),
                family
        ))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("Chore template is not active for the current period.");
    }

    @Test
    void uncompleteCurrentPeriod_staleRequest_throwsBadRequest() {
        ChoreTemplate daily = createChoreTemplate(
                family,
                member,
                "Brush teeth",
                ChoreCadence.DAILY,
                LocalDate.of(2026, 5, 17)
        );
        when(choreTemplateRepository.findByFamilyAndId(family, daily.getId())).thenReturn(Optional.of(daily));

        assertThatThrownBy(() -> choreService.uncompleteCurrentPeriod(
                daily.getId(),
                new UpdateCurrentPeriodCompletionRequest(ChoreScope.TODAY, LocalDate.of(2026, 5, 16), null),
                family
        ))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("Chore period is stale. Refresh and try again.");
    }
}
