package com.familyhub.demo.dto;

import com.familyhub.demo.model.FamilyColor;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;


public record FamilyMemberRequest(
    @NotEmpty
    @Size(max = 30, message = "Name must be 30 characters or less")
    String name,

    @NotNull
    FamilyColor color,

    @Email
    @Size(max = 254, message = "Email is too long")
    String email,

    /** Checked by {@code MemberAvatars}: none, a built-in key, an https link or a picture of up to 64 KB. */
    String avatarUrl,

    /** Optional; omitted or null clears it on update. */
    MemberBirthday birthday
) {
    /** The shape before birthdays existed. */
    public FamilyMemberRequest(String name, FamilyColor color, String email, String avatarUrl) {
        this(name, color, email, avatarUrl, null);
    }
}
