package com.familyhub.demo.dto;

import com.familyhub.demo.model.FamilyMember;
import com.familyhub.demo.model.FollowedTeam;
import com.familyhub.demo.sports.SportsLeague;

import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/** A followed team as Family Settings shows it. */
public record FollowedTeamResponse(
        UUID id,
        SportsLeague league,
        String providerTeamId,
        String name,
        String shortName,
        String abbreviation,
        String logoUrl,
        String color,
        boolean showOnCalendar,
        boolean hideScores,
        List<UUID> memberIds
) {

    /** Maps a followed team; fans in a stable order. */
    public static FollowedTeamResponse toDto(FollowedTeam team) {
        return new FollowedTeamResponse(
                team.getId(), team.getLeague(), team.getProviderTeamId(), team.getName(), team.getShortName(),
                team.getAbbreviation(), team.getLogoUrl(), team.getColor(), team.isShowOnCalendar(),
                team.isHideScores(),
                team.getFans().stream().map(FamilyMember::getId).sorted(Comparator.naturalOrder()).toList());
    }
}
