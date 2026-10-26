package com.familyhub.demo.dto;

import com.familyhub.demo.model.FamilyColor;
import com.familyhub.demo.model.FamilyMember;

import java.util.UUID;

public record FamilyMemberResponse(
        UUID id,
        String name,
        FamilyColor color,
        String email,
        String avatarUrl,
        MemberBirthday birthday
) {

    /** The shape before birthdays existed. */
    public FamilyMemberResponse(UUID id, String name, FamilyColor color, String email, String avatarUrl) {
        this(id, name, color, email, avatarUrl, null);
    }

    public static FamilyMemberResponse toDto(FamilyMember familyMember) {
        return new FamilyMemberResponse(
                familyMember.getId(),
                familyMember.getName(),
                familyMember.getColor(),
                familyMember.getEmail(),
                familyMember.getAvatarUrl(),
                familyMember.getBirthday()
        );
    }
}
