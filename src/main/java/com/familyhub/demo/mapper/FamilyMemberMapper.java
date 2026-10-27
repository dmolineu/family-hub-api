package com.familyhub.demo.mapper;

import com.familyhub.demo.dto.FamilyMemberRequest;
import com.familyhub.demo.dto.FamilyMemberResponse;
import com.familyhub.demo.model.Family;
import com.familyhub.demo.model.FamilyMember;
import com.familyhub.demo.service.MemberAvatars;

import java.time.LocalDate;

public class FamilyMemberMapper {
    private FamilyMemberMapper() {}

    public static FamilyMember toEntity(FamilyMemberRequest request, Family owner) {
        FamilyMember familyMember = new FamilyMember();

        familyMember.setName(request.name());
        familyMember.setEmail(request.email());
        familyMember.setColor(request.color());
        familyMember.setAvatarUrl(MemberAvatars.normalize(request.avatarUrl()).orElse(null));
        if (request.birthday() != null) {
            request.birthday().validate(LocalDate.now());
        }
        familyMember.setBirthday(request.birthday());
        familyMember.setFamily(owner);

        return familyMember;
    }

    public static FamilyMemberResponse toDto(FamilyMember familyMember) {
        return FamilyMemberResponse.toDto(familyMember);
    }
}
