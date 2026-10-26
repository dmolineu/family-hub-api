package com.familyhub.demo.dto;

import com.familyhub.demo.model.FamilyColor;
import com.familyhub.demo.model.FamilyMember;
import com.familyhub.demo.model.MemberRole;

import java.util.UUID;

public record FamilyMemberResponse(
        UUID id,
        String name,
        FamilyColor color,
        String email,
        String avatarUrl,
        MemberBirthday birthday,
        MemberRole role,
        boolean hasPin
) {

    /** The shape before roles and PINs existed: a parent without a PIN. */
    public FamilyMemberResponse(UUID id, String name, FamilyColor color, String email, String avatarUrl,
                                MemberBirthday birthday) {
        this(id, name, color, email, avatarUrl, birthday, MemberRole.PARENT, false);
    }

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
                familyMember.getBirthday(),
                familyMember.getRole(),
                familyMember.hasPin()
        );
    }
}
