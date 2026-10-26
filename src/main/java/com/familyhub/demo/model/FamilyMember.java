package com.familyhub.demo.model;

import jakarta.persistence.*;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

import java.util.UUID;

@Entity
@Getter
@Setter
public class FamilyMember {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @NotNull
    @ManyToOne
    @JoinColumn(name = "family_id", nullable = false)
    private Family family;

    @NotBlank
    @Column(nullable = false)
    private String name;

    @Enumerated(EnumType.STRING)
    @NotNull
    @Column(nullable = false)
    private FamilyColor color;

    private String avatarUrl;

    @Email
    private String email;

    @Column(name = "birth_month")
    private Integer birthMonth;

    @Column(name = "birth_day")
    private Integer birthDay;

    @Column(name = "birth_year")
    private Integer birthYear;

    /** The birthday as one value, or null when none is set. */
    public com.familyhub.demo.dto.MemberBirthday getBirthday() {
        if (birthMonth == null || birthDay == null) {
            return null;
        }
        return new com.familyhub.demo.dto.MemberBirthday(birthMonth, birthDay, birthYear);
    }

    /** Sets or clears (null) the birthday; callers validate first. */
    public void setBirthday(com.familyhub.demo.dto.MemberBirthday birthday) {
        this.birthMonth = birthday == null ? null : birthday.month();
        this.birthDay = birthday == null ? null : birthday.day();
        this.birthYear = birthday == null ? null : birthday.year();
    }
}
