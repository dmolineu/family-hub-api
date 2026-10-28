package com.familyhub.demo.service;

import com.familyhub.demo.dto.BackupDocument.FollowedTeamBackup;
import com.familyhub.demo.dto.FollowTeamRequest;
import com.familyhub.demo.dto.FollowedTeamResponse;
import com.familyhub.demo.dto.FollowedTeamUpdateRequest;
import com.familyhub.demo.dto.SportsLeagueResponse;
import com.familyhub.demo.exception.BadRequestException;
import com.familyhub.demo.exception.ConflictException;
import com.familyhub.demo.exception.ResourceNotFoundException;
import com.familyhub.demo.model.Family;
import com.familyhub.demo.model.FamilyMember;
import com.familyhub.demo.model.FollowedTeam;
import com.familyhub.demo.model.SportsGame;
import com.familyhub.demo.repository.FamilyMemberRepository;
import com.familyhub.demo.repository.FollowedTeamRepository;
import com.familyhub.demo.repository.SportsGameRepository;
import com.familyhub.demo.sports.ProviderTeam;
import com.familyhub.demo.sports.SportsDataProvider;
import com.familyhub.demo.sports.SportsLeague;
import com.familyhub.demo.sports.SportsProviders;
import com.familyhub.demo.sports.SportsTeamCatalog;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * The teams a family follows, chosen in Family Settings: which league, which team,
 * whose lanes its games go in, its colour and whether its scores are hidden.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SportsTeamService {
    private final FollowedTeamRepository followedTeamRepository;
    private final SportsGameRepository sportsGameRepository;
    private final FamilyMemberRepository familyMemberRepository;
    private final SportsProviders providers;
    private final SportsTeamCatalog catalog;

    /** Every league, whether a data source covers it yet, and whether that source has live scores. */
    public List<SportsLeagueResponse> leagues() {
        return Arrays.stream(SportsLeague.values())
                .map(l -> {
                    Optional<SportsDataProvider> provider = providers.forLeague(l);
                    return new SportsLeagueResponse(l, l.displayName(), provider.isPresent(),
                            provider.map(p -> p.liveScores(l)).orElse(false));
                })
                .toList();
    }

    /**
     * Searches a league's teams.
     *
     * @throws BadRequestException when no data source covers the league yet (400)
     */
    public List<ProviderTeam> searchTeams(SportsLeague league, String query) {
        return catalog.search(provider(league), league, query);
    }

    /** The family's followed teams, in the order they were followed. */
    public List<FollowedTeamResponse> list(Family family) {
        return followedTeamRepository.findByFamilyOrderByCreatedAtAsc(family).stream()
                .map(FollowedTeamResponse::toDto)
                .toList();
    }

    /**
     * Follows a team; its schedule is read separately (see {@code SportsSyncService}).
     *
     * @throws BadRequestException       when no data source covers the league (400)
     * @throws ResourceNotFoundException when the league has no such team (404)
     * @throws ConflictException         when the family already follows it (409)
     */
    @Transactional
    public FollowedTeamResponse follow(Family family, FollowTeamRequest request) {
        SportsDataProvider provider = provider(request.league());
        ProviderTeam team = catalog.find(provider, request.league(), request.providerTeamId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "No " + request.league().displayName() + " team has the id " + request.providerTeamId()));
        if (followedTeamRepository.existsByFamilyAndLeagueAndProviderTeamId(family, request.league(), team.id())) {
            throw new ConflictException("The family already follows the " + team.name() + ".");
        }
        FollowedTeam followed = new FollowedTeam();
        followed.setFamily(family);
        followed.setLeague(request.league());
        followed.setProviderTeamId(team.id());
        followed.setName(team.name());
        followed.setShortName(team.shortName());
        followed.setAbbreviation(team.abbreviation());
        followed.setLogoUrl(team.logoUrl());
        FollowedTeam saved = followedTeamRepository.save(followed);
        log.info("Team followed, familyId={}, league={}, team={}", family.getId(), request.league(), team.id());
        return FollowedTeamResponse.toDto(saved);
    }

    /**
     * Changes a followed team's fans, colour or switches.
     *
     * @throws ResourceNotFoundException for a team or member not in this family (404)
     */
    @Transactional
    public FollowedTeamResponse update(Family family, UUID teamId, FollowedTeamUpdateRequest request) {
        FollowedTeam team = find(family, teamId);
        if (request.memberIds() != null) {
            team.setFans(members(family, request.memberIds()));
        }
        if (request.color() != null) {
            team.setColor(request.color().isEmpty() ? null : request.color().toLowerCase());
        }
        if (request.showOnCalendar() != null) {
            team.setShowOnCalendar(request.showOnCalendar());
        }
        if (request.hideScores() != null) {
            team.setHideScores(request.hideScores());
        }
        return FollowedTeamResponse.toDto(followedTeamRepository.save(team));
    }

    /** Unfollows a team and drops the games no other followed team plays in. */
    @Transactional
    public void unfollow(Family family, UUID teamId) {
        FollowedTeam team = find(family, teamId);
        followedTeamRepository.delete(team);
        followedTeamRepository.flush();
        Set<String> stillFollowed = followedTeamRepository.findByFamilyOrderByCreatedAtAsc(family).stream()
                .filter(t -> t.getLeague() == team.getLeague())
                .map(FollowedTeam::getProviderTeamId)
                .collect(Collectors.toSet());
        List<SportsGame> orphaned = sportsGameRepository.findByFamilyAndLeague(family, team.getLeague()).stream()
                .filter(g -> !stillFollowed.contains(g.getHomeTeamId()) && !stillFollowed.contains(g.getAwayTeamId()))
                .toList();
        sportsGameRepository.deleteAll(orphaned);
    }

    // ------------------------------------------------------------------ backup

    /** The family's followed teams, fans as member refs, for the backup file. */
    public List<FollowedTeamBackup> exportAll(Family family, Map<UUID, String> memberRefs) {
        return followedTeamRepository.findByFamilyOrderByCreatedAtAsc(family).stream()
                .map(t -> new FollowedTeamBackup(t.getLeague(), t.getProviderTeamId(), t.getName(), t.getShortName(),
                        t.getAbbreviation(), t.getLogoUrl(), t.getColor(), t.isShowOnCalendar(), t.isHideScores(),
                        t.getFans().stream().map(m -> memberRefs.get(m.getId())).filter(r -> r != null).sorted().toList()))
                .toList();
    }

    /**
     * Replaces the family's teams with a backup's. Their games are dropped and come back
     * with the next sync, which picks up teams never synced.
     */
    @Transactional
    public int replaceAll(List<FollowedTeamBackup> backups, Family family, Map<String, FamilyMember> membersByRef) {
        sportsGameRepository.deleteByFamily(family);
        followedTeamRepository.deleteByFamily(family);
        if (backups == null || backups.isEmpty()) {
            return 0;
        }
        Set<String> seen = new HashSet<>();
        for (FollowedTeamBackup backup : backups) {
            if (backup.league() == null || backup.providerTeamId() == null || backup.name() == null
                    || !seen.add(backup.league() + ":" + backup.providerTeamId())) {
                throw new BadRequestException("The backup holds a followed team the app cannot read");
            }
            FollowedTeam team = new FollowedTeam();
            team.setFamily(family);
            team.setLeague(backup.league());
            team.setProviderTeamId(backup.providerTeamId());
            team.setName(backup.name());
            team.setShortName(backup.shortName());
            team.setAbbreviation(backup.abbreviation());
            team.setLogoUrl(backup.logoUrl());
            team.setColor(backup.color() != null && backup.color().matches("^#[0-9a-fA-F]{6}$")
                    ? backup.color().toLowerCase() : null);
            team.setShowOnCalendar(backup.showOnCalendar());
            team.setHideScores(backup.hideScores());
            Set<FamilyMember> fans = new HashSet<>();
            for (String ref : backup.fans() == null ? List.<String>of() : backup.fans()) {
                FamilyMember member = membersByRef.get(ref);
                if (member != null) {
                    fans.add(member);
                }
            }
            team.setFans(fans);
            followedTeamRepository.save(team);
        }
        return backups.size();
    }

    // ----------------------------------------------------------------- helpers

    private SportsDataProvider provider(SportsLeague league) {
        return providers.forLeague(league).orElseThrow(() -> new BadRequestException(
                "There is no data source for " + league.displayName() + " yet."));
    }

    private FollowedTeam find(Family family, UUID teamId) {
        return followedTeamRepository.findByIdAndFamily(teamId, family)
                .orElseThrow(() -> new ResourceNotFoundException("Followed team", teamId));
    }

    private Set<FamilyMember> members(Family family, List<UUID> memberIds) {
        Set<FamilyMember> members = new HashSet<>();
        for (UUID id : new HashSet<>(memberIds)) {
            FamilyMember member = familyMemberRepository.findById(id)
                    .filter(m -> m.getFamily().getId().equals(family.getId()))
                    .orElseThrow(() -> new ResourceNotFoundException("Family Member", id));
            members.add(member);
        }
        return members;
    }
}
