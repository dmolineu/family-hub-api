package com.familyhub.demo.service;

import com.familyhub.demo.dto.ChoreAssigneeGroupResponse;
import com.familyhub.demo.dto.ChoreBoardItemResponse;
import com.familyhub.demo.dto.ChoreBoardResponse;
import com.familyhub.demo.dto.ChoreCurrentPeriodStateResponse;
import com.familyhub.demo.dto.ChoreScopeBoardResponse;
import com.familyhub.demo.dto.ChoreTemplateResponse;
import com.familyhub.demo.dto.CreateChoreTemplateRequest;
import com.familyhub.demo.dto.FamilyMemberResponse;
import com.familyhub.demo.dto.UpdateChoreTemplateRequest;
import com.familyhub.demo.dto.UpdateCurrentPeriodCompletionRequest;
import com.familyhub.demo.exception.BadRequestException;
import com.familyhub.demo.exception.ResourceNotFoundException;
import com.familyhub.demo.model.ChoreCadence;
import com.familyhub.demo.model.ChorePeriodCompletion;
import com.familyhub.demo.model.ChoreScope;
import com.familyhub.demo.model.ChoreTemplate;
import com.familyhub.demo.model.Family;
import com.familyhub.demo.model.FamilyMember;
import com.familyhub.demo.repository.ChorePeriodCompletionRepository;
import com.familyhub.demo.repository.ChoreTemplateRepository;
import com.familyhub.demo.repository.FamilyMemberRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Objects;
import java.util.ArrayList;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ChoreService {
    private static final String INACTIVE_TEMPLATE_MESSAGE = "Chore template is not active for the current period.";
    private static final String STALE_PERIOD_MESSAGE = "Chore period is stale. Refresh and try again.";
    private static final String WHO_COMPLETED_REQUIRED_MESSAGE = "Choose who completed this chore.";

    private final ChoreTemplateRepository choreTemplateRepository;
    private final ChorePeriodCompletionRepository chorePeriodCompletionRepository;
    private final ChorePeriodCompletionWriter chorePeriodCompletionWriter;
    private final FamilyMemberRepository familyMemberRepository;
    private final Clock clock;

    public ChoreBoardResponse getBoard(Family family) {
        LocalDate today = familyLocalToday(family);
        List<ChoreTemplate> activeTemplates = choreTemplateRepository.findActiveByFamily(family);

        ChoreScopeBoardResponse todayBoard = buildScopeBoard(
                activeTemplates,
                ChoreScope.TODAY,
                today,
                today,
                today
        );

        LocalDate weekStart = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.SUNDAY));
        ChoreScopeBoardResponse weekBoard = buildScopeBoard(
                activeTemplates,
                ChoreScope.THIS_WEEK,
                today,
                weekStart,
                weekStart.plusDays(6)
        );

        LocalDate monthStart = today.withDayOfMonth(1);
        ChoreScopeBoardResponse monthBoard = buildScopeBoard(
                activeTemplates,
                ChoreScope.THIS_MONTH,
                today,
                monthStart,
                today.withDayOfMonth(today.lengthOfMonth())
        );

        return new ChoreBoardResponse(resolveTimezone(family), todayBoard, weekBoard, monthBoard);
    }

    /**
     * Creates one template per listed member, or a single unassigned ("anyone") template when
     * no members are listed. Every member is checked before anything is saved, so a bad id
     * creates nothing. A member listed twice gets one chore.
     */
    @Transactional
    public List<ChoreTemplateResponse> createTemplates(CreateChoreTemplateRequest request, Family family) {
        List<UUID> memberIds = request.assignedToMemberIds() == null
                ? List.of()
                : request.assignedToMemberIds().stream().filter(Objects::nonNull).distinct().toList();

        List<ChoreTemplate> templates = new ArrayList<>();
        if (memberIds.isEmpty()) {
            templates.add(newTemplate(request, family, null));
        } else {
            for (UUID memberId : memberIds) {
                templates.add(newTemplate(request, family, requireAssignedMember(family, memberId)));
            }
        }

        return choreTemplateRepository.saveAll(templates).stream()
                .map(this::toTemplateResponse)
                .toList();
    }

    private ChoreTemplate newTemplate(CreateChoreTemplateRequest request, Family family, FamilyMember assignee) {
        ChoreTemplate template = new ChoreTemplate();
        template.setFamily(family);
        template.setAssignedToMember(assignee);
        template.setTitle(request.title().trim());
        template.setCadence(request.cadence());
        template.setActiveFrom(request.activeFrom());
        return template;
    }

    @Transactional
    public ChoreTemplateResponse updateTemplate(UUID templateId, UpdateChoreTemplateRequest request, Family family) {
        ChoreTemplate template = choreTemplateRepository.findByFamilyAndId(family, templateId)
                .orElseThrow(() -> new ResourceNotFoundException("Chore Template", templateId));

        if (request.title() != null) {
            if (request.title().isBlank()) {
                throw new BadRequestException("Chore title is required");
            }
            template.setTitle(request.title().trim());
        }
        if (request.assignedToMemberId() != null) {
            template.setAssignedToMember(requireAssignedMember(family, request.assignedToMemberId()));
        }
        if (request.cadence() != null) {
            template.setCadence(request.cadence());
        }
        if (request.activeFrom() != null) {
            template.setActiveFrom(request.activeFrom());
        }
        if (request.archived() != null) {
            template.setArchivedAt(Boolean.TRUE.equals(request.archived()) ? LocalDateTime.now(clock) : null);
        }

        return toTemplateResponse(choreTemplateRepository.save(template));
    }

    @Transactional
    public ChoreCurrentPeriodStateResponse completeCurrentPeriod(
            UUID templateId,
            UpdateCurrentPeriodCompletionRequest request,
            Family family
    ) {
        LocalDate today = familyLocalToday(family);
        ChoreTemplate template = requireActiveTemplate(family, templateId, today);
        ResolvedPeriod period = resolveCurrentPeriod(template, today);
        assertFreshPeriod(request, period);
        UUID completedByMemberId = resolveCompletedBy(template, request.completedByMemberId(), family);

        ChorePeriodCompletion existingCompletion = chorePeriodCompletionRepository
                .findByChoreTemplateAndPeriodStartDateAndPeriodEndDate(
                        template,
                        period.periodStartDate(),
                        period.periodEndDate()
                )
                .orElse(null);

        if (existingCompletion != null) {
            return toCurrentPeriodStateResponse(template, period, existingCompletion);
        }

        try {
            ChorePeriodCompletion saved = chorePeriodCompletionWriter.createCompletion(
                    template.getId(),
                    period.periodStartDate(),
                    period.periodEndDate(),
                    LocalDateTime.now(clock),
                    completedByMemberId
            );
            return toCurrentPeriodStateResponse(template, period, saved);
        } catch (DataIntegrityViolationException ex) {
            ChorePeriodCompletion concurrentCompletion = chorePeriodCompletionRepository
                    .findByChoreTemplateAndPeriodStartDateAndPeriodEndDate(
                            template,
                            period.periodStartDate(),
                            period.periodEndDate()
                    )
                    .orElseThrow(() -> ex);
            return toCurrentPeriodStateResponse(template, period, concurrentCompletion);
        }
    }

    @Transactional
    public ChoreCurrentPeriodStateResponse uncompleteCurrentPeriod(
            UUID templateId,
            UpdateCurrentPeriodCompletionRequest request,
            Family family
    ) {
        LocalDate today = familyLocalToday(family);
        ChoreTemplate template = requireActiveTemplate(family, templateId, today);
        ResolvedPeriod period = resolveCurrentPeriod(template, today);
        assertFreshPeriod(request, period);

        chorePeriodCompletionRepository
                .findByChoreTemplateAndPeriodStartDateAndPeriodEndDate(
                        template,
                        period.periodStartDate(),
                        period.periodEndDate()
                )
                .ifPresent(chorePeriodCompletionRepository::delete);

        return new ChoreCurrentPeriodStateResponse(
                period.scope(),
                period.periodStartDate(),
                period.periodEndDate(),
                toBoardItem(template, null)
        );
    }

    private ChoreScopeBoardResponse buildScopeBoard(
            List<ChoreTemplate> activeTemplates,
            ChoreScope scope,
            LocalDate today,
            LocalDate periodStart,
            LocalDate periodEnd
    ) {
        List<ChoreTemplate> scopeTemplates = activeTemplates.stream()
                .filter(template -> scopeFor(template.getCadence()) == scope)
                .filter(template -> !template.getActiveFrom().isAfter(today))
                .toList();

        Map<UUID, ChorePeriodCompletion> completionsByTemplateId = completionsByTemplateId(
                scopeTemplates,
                periodStart,
                periodEnd
        );

        List<FamilyMember> assignees = scopeTemplates.stream()
                .map(ChoreTemplate::getAssignedToMember)
                .filter(Objects::nonNull)
                .collect(Collectors.toMap(
                        FamilyMember::getId,
                        Function.identity(),
                        (first, ignored) -> first
                ))
                .values()
                .stream()
                .sorted(memberComparator())
                .toList();

        // Unassigned chores come first: they are everyone's to pick up.
        List<ChoreAssigneeGroupResponse> groups = new ArrayList<>();
        buildAnyoneGroup(scopeTemplates, completionsByTemplateId).ifPresent(groups::add);
        assignees.stream()
                .map(member -> buildAssigneeGroup(member, scopeTemplates, completionsByTemplateId))
                .forEach(groups::add);

        int total = groups.stream().mapToInt(group -> group.summary().total()).sum();
        int completed = groups.stream().mapToInt(group -> group.summary().completed()).sum();

        return new ChoreScopeBoardResponse(
                scope,
                periodStart,
                periodEnd,
                new ChoreScopeBoardResponse.Summary(total, completed, total - completed),
                groups
        );
    }

    private Map<UUID, ChorePeriodCompletion> completionsByTemplateId(
            List<ChoreTemplate> templates,
            LocalDate periodStart,
            LocalDate periodEnd
    ) {
        if (templates.isEmpty()) {
            return Map.of();
        }

        List<UUID> templateIds = templates.stream()
                .map(ChoreTemplate::getId)
                .toList();

        return chorePeriodCompletionRepository.findByTemplateIdsAndPeriod(templateIds, periodStart, periodEnd)
                .stream()
                .collect(Collectors.toMap(
                        completion -> completion.getChoreTemplate().getId(),
                        Function.identity()
                ));
    }

    private ChoreAssigneeGroupResponse buildAssigneeGroup(
            FamilyMember member,
            List<ChoreTemplate> templates,
            Map<UUID, ChorePeriodCompletion> completionsByTemplateId
    ) {
        List<ChoreTemplate> own = templates.stream()
                .filter(template -> template.getAssignedToMember() != null
                        && template.getAssignedToMember().getId().equals(member.getId()))
                .toList();
        return buildGroup(FamilyMemberResponse.toDto(member), own, completionsByTemplateId);
    }

    private Optional<ChoreAssigneeGroupResponse> buildAnyoneGroup(
            List<ChoreTemplate> templates,
            Map<UUID, ChorePeriodCompletion> completionsByTemplateId
    ) {
        List<ChoreTemplate> unassigned = templates.stream()
                .filter(template -> template.getAssignedToMember() == null)
                .toList();
        if (unassigned.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(buildGroup(null, unassigned, completionsByTemplateId));
    }

    private ChoreAssigneeGroupResponse buildGroup(
            FamilyMemberResponse member,
            List<ChoreTemplate> templates,
            Map<UUID, ChorePeriodCompletion> completionsByTemplateId
    ) {
        List<ChoreBoardItemResponse> chores = templates.stream()
                .sorted(templateComparator(completionsByTemplateId))
                .map(template -> toBoardItem(template, completionsByTemplateId.get(template.getId())))
                .toList();

        int total = chores.size();
        int completed = (int) chores.stream().filter(ChoreBoardItemResponse::completed).count();

        return new ChoreAssigneeGroupResponse(
                member,
                new ChoreAssigneeGroupResponse.Summary(total, completed, total - completed),
                chores
        );
    }

    private Comparator<ChoreTemplate> templateComparator(Map<UUID, ChorePeriodCompletion> completionsByTemplateId) {
        return Comparator
                .comparing((ChoreTemplate template) -> completionsByTemplateId.containsKey(template.getId()))
                .thenComparing(
                        ChoreTemplate::getCreatedAt,
                        Comparator.nullsLast(Comparator.naturalOrder())
                )
                .thenComparing(template -> template.getTitle().toLowerCase(Locale.ROOT))
                .thenComparing(template -> template.getId().toString());
    }

    private Comparator<FamilyMember> memberComparator() {
        return Comparator
                .comparing((FamilyMember member) -> member.getName().toLowerCase(Locale.ROOT))
                .thenComparing(member -> member.getId().toString());
    }

    private ChoreScope scopeFor(ChoreCadence cadence) {
        return switch (cadence) {
            case DAILY -> ChoreScope.TODAY;
            case WEEKLY -> ChoreScope.THIS_WEEK;
            case MONTHLY -> ChoreScope.THIS_MONTH;
        };
    }

    private ChoreTemplate requireActiveTemplate(Family family, UUID templateId, LocalDate today) {
        ChoreTemplate template = choreTemplateRepository.findByFamilyAndId(family, templateId)
                .orElseThrow(() -> new ResourceNotFoundException("Chore Template", templateId));

        if (template.getArchivedAt() != null || template.getActiveFrom().isAfter(today)) {
            throw new BadRequestException(INACTIVE_TEMPLATE_MESSAGE);
        }

        return template;
    }

    /**
     * The member credited with a completion: whoever the caller names (checked against the
     * family), else the assignee. An unassigned chore must name someone.
     */
    private UUID resolveCompletedBy(ChoreTemplate template, UUID requestedMemberId, Family family) {
        if (requestedMemberId != null) {
            return requireAssignedMember(family, requestedMemberId).getId();
        }
        if (template.getAssignedToMember() == null) {
            throw new BadRequestException(WHO_COMPLETED_REQUIRED_MESSAGE);
        }
        return template.getAssignedToMember().getId();
    }

    private FamilyMember requireAssignedMember(Family family, UUID memberId) {
        FamilyMember member = familyMemberRepository.findById(memberId)
                .orElseThrow(() -> new ResourceNotFoundException("Family Member", memberId));

        if (!member.getFamily().getId().equals(family.getId())) {
            throw new AccessDeniedException("Unauthorized");
        }

        return member;
    }

    private ResolvedPeriod resolveCurrentPeriod(ChoreTemplate template, LocalDate today) {
        return switch (template.getCadence()) {
            case DAILY -> new ResolvedPeriod(ChoreScope.TODAY, today, today);
            case WEEKLY -> {
                LocalDate weekStart = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.SUNDAY));
                yield new ResolvedPeriod(ChoreScope.THIS_WEEK, weekStart, weekStart.plusDays(6));
            }
            case MONTHLY -> {
                LocalDate monthStart = today.withDayOfMonth(1);
                yield new ResolvedPeriod(ChoreScope.THIS_MONTH, monthStart, today.withDayOfMonth(today.lengthOfMonth()));
            }
        };
    }

    private void assertFreshPeriod(UpdateCurrentPeriodCompletionRequest request, ResolvedPeriod period) {
        if (request.scope() != period.scope() || !request.periodStartDate().equals(period.periodStartDate())) {
            throw new BadRequestException(STALE_PERIOD_MESSAGE);
        }
    }

    private LocalDate familyLocalToday(Family family) {
        return LocalDate.now(clock.withZone(ZoneId.of(resolveTimezone(family))));
    }

    private String resolveTimezone(Family family) {
        return FamilyTimezoneResolver.resolveStoredTimezoneOrDefault(family.getTimezone());
    }

    private ChoreCurrentPeriodStateResponse toCurrentPeriodStateResponse(
            ChoreTemplate template,
            ResolvedPeriod period,
            ChorePeriodCompletion completion
    ) {
        return new ChoreCurrentPeriodStateResponse(
                period.scope(),
                period.periodStartDate(),
                period.periodEndDate(),
                toBoardItem(template, completion)
        );
    }

    private ChoreBoardItemResponse toBoardItem(ChoreTemplate template, ChorePeriodCompletion completion) {
        return new ChoreBoardItemResponse(
                template.getId(),
                template.getTitle(),
                template.getCadence(),
                assigneeId(template),
                completion != null,
                completion != null ? completion.getCompletedAt() : null,
                completion != null && completion.getCompletedByMember() != null
                        ? completion.getCompletedByMember().getId()
                        : null
        );
    }

    private static UUID assigneeId(ChoreTemplate template) {
        return template.getAssignedToMember() == null ? null : template.getAssignedToMember().getId();
    }

    private ChoreTemplateResponse toTemplateResponse(ChoreTemplate template) {
        return new ChoreTemplateResponse(
                template.getId(),
                template.getTitle(),
                assigneeId(template),
                template.getCadence(),
                template.getActiveFrom(),
                template.getArchivedAt() != null,
                template.getCreatedAt(),
                template.getUpdatedAt()
        );
    }

    private record ResolvedPeriod(
            ChoreScope scope,
            LocalDate periodStartDate,
            LocalDate periodEndDate
    ) {
    }
}
