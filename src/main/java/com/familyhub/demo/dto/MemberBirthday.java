package com.familyhub.demo.dto;

import com.familyhub.demo.exception.BadRequestException;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.MonthDay;
import java.time.Year;

/**
 * A family member's birthday: month and day, and optionally the year, so a parent
 * can keep their age private while a child's card can say "turns 9". 29 February is
 * allowed without a year or in a leap year.
 */
public record MemberBirthday(Integer month, Integer day, Integer year) {

    /** Throws {@link BadRequestException} with a readable reason for anything impossible. */
    public void validate(LocalDate today) {
        if (month == null || day == null) {
            throw new BadRequestException("A birthday needs both a month and day");
        }
        MonthDay monthDay;
        try {
            monthDay = MonthDay.of(month, day);
        } catch (DateTimeException ex) {
            throw new BadRequestException("That birthday is not a real date: " + month + "/" + day);
        }
        if (year == null) {
            return;
        }
        if (year < 1900) {
            throw new BadRequestException("A birth year must be 1900 or later");
        }
        if (!monthDay.isValidYear(year)) {
            throw new BadRequestException(month + "/" + day + " does not exist in " + year);
        }
        if (Year.of(year).atMonthDay(monthDay).isAfter(today)) {
            throw new BadRequestException("A birthday cannot be in the future");
        }
    }
}
