package com.familyhub.demo.service;

import com.familyhub.demo.exception.BadRequestException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ThemePackDocumentValidatorTest {

    private final ThemePackDocumentValidator validator = new ThemePackDocumentValidator();

    private static final String PNG_PIXEL = "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg==";

    private static String doc(String name, String dark, String tokens, String images) {
        return """
                {"version": 1, "name": %s, "dark": %s, "tokens": %s, "images": %s,
                 "slots": {"headerLogo": "logo", "mascots": ["logo"]}}
                """.formatted(name, dark, tokens, images);
    }

    @Test
    void acceptsAWellFormedPackAndKeepsTheDocumentVerbatim() {
        String json = doc("\"Padres\"", "true",
                "{\"--background\": \"#2f241d\", \"--primary\": \"oklch(0.85 0.17 90)\", \"--theme-image-background\": \"none\"}",
                "{\"logo\": \"" + PNG_PIXEL + "\"}");

        ValidatedThemePack pack = validator.validate(json);

        assertThat(pack.name()).isEqualTo("Padres");
        assertThat(pack.dark()).isTrue();
        assertThat(pack.document()).isEqualTo(json);
        assertThat(pack.sizeBytes()).isEqualTo(json.getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
    }

    @Test
    void trimsTheNameAndAllowsEmptyImages() {
        ValidatedThemePack pack = validator.validate(doc("\"  Midnight Blue  \"", "false", "{}", "{}"));
        assertThat(pack.name()).isEqualTo("Midnight Blue");
    }

    @Test
    void rejectsMalformedJsonAndNonObjects() {
        assertThatThrownBy(() -> validator.validate("not json"))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("not valid JSON");
        assertThatThrownBy(() -> validator.validate("[1, 2]"))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("object");
    }

    @Test
    void rejectsAnUnsupportedVersion() {
        assertThatThrownBy(() -> validator.validate(doc("\"X\"", "false", "{}", "{}").replace("\"version\": 1", "\"version\": 2")))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("version");
    }

    @Test
    void rejectsAMissingBlankOrOverlongName() {
        assertThatThrownBy(() -> validator.validate(doc("\"   \"", "false", "{}", "{}")))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("name");
        assertThatThrownBy(() -> validator.validate(doc("\"" + "x".repeat(81) + "\"", "false", "{}", "{}")))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("80");
        assertThatThrownBy(() -> validator.validate("{\"version\": 1, \"dark\": false, \"tokens\": {}}"))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("name");
    }

    @Test
    void requiresTheDarkFlagToBeABoolean() {
        assertThatThrownBy(() -> validator.validate(doc("\"X\"", "\"yes\"", "{}", "{}")))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("dark");
    }

    @Test
    void rejectsTokenNamesThatAreNotCustomProperties() {
        assertThatThrownBy(() -> validator.validate(doc("\"X\"", "false", "{\"background\": \"#fff\"}", "{}")))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("background");
    }

    @Test
    void rejectsTokenValuesThatCouldEscapeADeclaration() {
        for (String value : new String[]{"red; } body { display:none", "url(http://x)", "expression(1)", "javascript:alert(1)", "<b>"}) {
            assertThatThrownBy(() -> validator.validate(doc("\"X\"", "false", "{\"--primary\": \"" + value.replace("\"", "\\\"") + "\"}", "{}")))
                    .as(value)
                    .isInstanceOf(BadRequestException.class)
                    .hasMessageContaining("--primary");
        }
    }

    @Test
    void rejectsImagesThatAreNotImageDataUrls() {
        assertThatThrownBy(() -> validator.validate(doc("\"X\"", "false", "{}", "{\"logo\": \"https://example.com/a.png\"}")))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("logo");
        assertThatThrownBy(() -> validator.validate(doc("\"X\"", "false", "{}", "{\"logo\": \"data:text/html;base64,PGI+\"}")))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("logo");
        assertThatThrownBy(() -> validator.validate(doc("\"X\"", "false", "{}", "{\"Bad Key\": \"" + PNG_PIXEL + "\"}")))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("Bad Key");
    }

    @Test
    void capsTheNumberOfImagesAndTokens() {
        StringBuilder images = new StringBuilder("{");
        for (int i = 0; i < 13; i++) {
            if (i > 0) images.append(",");
            images.append("\"img").append(i).append("\": \"").append(PNG_PIXEL).append("\"");
        }
        images.append("}");
        assertThatThrownBy(() -> validator.validate(doc("\"X\"", "false", "{}", images.toString())))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("12");

        StringBuilder tokens = new StringBuilder("{");
        for (int i = 0; i < 201; i++) {
            if (i > 0) tokens.append(",");
            tokens.append("\"--t").append(i).append("\": \"red\"");
        }
        tokens.append("}");
        assertThatThrownBy(() -> validator.validate(doc("\"X\"", "false", tokens.toString(), "{}")))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("200");
    }

    @Test
    void rejectsADocumentOverFourMegabytes() {
        String big = "data:image/png;base64," + "A".repeat(4 * 1024 * 1024);
        assertThatThrownBy(() -> validator.validate(doc("\"X\"", "false", "{}", "{\"logo\": \"" + big + "\"}")))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("4 MB");
    }
}
