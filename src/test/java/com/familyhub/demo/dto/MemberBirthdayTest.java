package com.familyhub.demo.dto;

import com.familyhub.demo.exception.BadRequestException;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MemberBirthdayTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 25);

    @Test
    void acceptsAMonthAndDayWithOrWithoutAYear() {
        assertThatCode(() -> new MemberBirthday(3, 14, null).validate(TODAY)).doesNotThrowAnyException();
        assertThatCode(() -> new MemberBirthday(3, 14, 2017).validate(TODAY)).doesNotThrowAnyException();
        assertThatCode(() -> new MemberBirthday(12, 31, 1980).validate(TODAY)).doesNotThrowAnyException();
    }

    @Test
    void allows29FebruaryWithoutAYearOrInALeapYear() {
        assertThatCode(() -> new MemberBirthday(2, 29, null).validate(TODAY)).doesNotThrowAnyException();
        assertThatCode(() -> new MemberBirthday(2, 29, 2016).validate(TODAY)).doesNotThrowAnyException();
        assertThatThrownBy(() -> new MemberBirthday(2, 29, 2023).validate(TODAY))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("2023");
    }

    @Test
    void rejectsDaysThatDoNotExist() {
        assertThatThrownBy(() -> new MemberBirthday(2, 30, null).validate(TODAY)).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> new MemberBirthday(4, 31, null).validate(TODAY)).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> new MemberBirthday(13, 1, null).validate(TODAY)).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> new MemberBirthday(1, 0, null).validate(TODAY)).isInstanceOf(BadRequestException.class);
    }

    @Test
    void needsBothMonthAndDay() {
        assertThatThrownBy(() -> new MemberBirthday(5, null, null).validate(TODAY))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("month and day");
        assertThatThrownBy(() -> new MemberBirthday(null, 5, 2010).validate(TODAY))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void rejectsAYearInTheFutureOrBefore1900() {
        assertThatThrownBy(() -> new MemberBirthday(1, 1, 2027).validate(TODAY))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("future");
        assertThatThrownBy(() -> new MemberBirthday(12, 1, 2026).validate(TODAY))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("future");
        assertThatThrownBy(() -> new MemberBirthday(1, 1, 1899).validate(TODAY))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("1900");
    }
}
