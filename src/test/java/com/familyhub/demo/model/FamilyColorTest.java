package com.familyhub.demo.model;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** A member's colour is one of the seven named colours, or any #rrggbb the family picks. */
class FamilyColorTest {

    private final ObjectMapper json = JsonMapper.builder().build();

    @Test
    void paletteNamesStillReadInAnyCase() {
        assertThat(FamilyColor.fromValue("Teal")).isEqualTo(FamilyColor.TEAL);
        assertThat(FamilyColor.fromValue("CORAL").toValue()).isEqualTo("coral");
    }

    @Test
    void aCustomColourIsHashRrggbbKeptLowercase() {
        assertThat(FamilyColor.fromValue("#4A90D9").toValue()).isEqualTo("#4a90d9");
        assertThat(FamilyColor.fromValue("#4a90d9")).isEqualTo(FamilyColor.fromValue("#4A90D9"));
        assertThat(FamilyColor.fromValue("#4a90d9")).isNotEqualTo(FamilyColor.fromValue("#4a90d8"));
    }

    @Test
    void anythingElseIsRefused() {
        for (String value : List.of("blue", "#4a90d", "#4a90d9f", "4a90d9", "#4a90dz", "", "#fff")) {
            assertThatThrownBy(() -> FamilyColor.fromValue(value))
                    .as(value)
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void jsonCarriesThePaletteNameOrTheHex() {
        assertThat(json.writeValueAsString(FamilyColor.PINK)).isEqualTo("\"pink\"");
        assertThat(json.writeValueAsString(FamilyColor.fromValue("#4a90d9"))).isEqualTo("\"#4a90d9\"");
        assertThat(json.readValue("\"#ABCDEF\"", FamilyColor.class).toValue()).isEqualTo("#abcdef");
        assertThat(json.readValue("\"purple\"", FamilyColor.class)).isEqualTo(FamilyColor.PURPLE);
    }
}
