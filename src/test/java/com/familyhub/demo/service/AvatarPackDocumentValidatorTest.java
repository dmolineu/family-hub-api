package com.familyhub.demo.service;

import com.familyhub.demo.exception.BadRequestException;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static com.familyhub.demo.service.MemberAvatarsTest.JPEG_SIGNATURE;
import static com.familyhub.demo.service.MemberAvatarsTest.PNG_SIGNATURE;
import static com.familyhub.demo.service.MemberAvatarsTest.dataUrl;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AvatarPackDocumentValidatorTest {

    private final AvatarPackDocumentValidator validator = new AvatarPackDocumentValidator();

    private static final String PNG = dataUrl("image/png", PNG_SIGNATURE, 200);

    static String character(String key, String name, String group, String image) {
        return """
                {"key": "%s", "name": "%s"%s, "image": "%s"}"""
                .formatted(key, name, group == null ? "" : ", \"group\": \"" + group + "\"", image);
    }

    static String pack(String name, String... characters) {
        return """
                {"version": 1, "name": "%s", "characters": [%s]}"""
                .formatted(name, String.join(",", characters));
    }

    @Test
    void acceptsAWellFormedPackAndListsItsGroupsInOrder() {
        String json = pack("Characters",
                character("pikachu", "Pikachu", "Pokémon", PNG),
                character("yoda", "Yoda", "Star Wars", dataUrl("image/jpeg", JPEG_SIGNATURE, 300)),
                character("eevee", "Eevee", "Pokémon", PNG));

        ValidatedAvatarPack pack = validator.validate(json);

        assertThat(pack.name()).isEqualTo("Characters");
        assertThat(pack.characterCount()).isEqualTo(3);
        assertThat(pack.groups()).containsExactly("Pokémon", "Star Wars");
        assertThat(pack.document()).isEqualTo(json);
        assertThat(pack.sizeBytes()).isEqualTo(json.getBytes(StandardCharsets.UTF_8).length);
    }

    @Test
    void aCharacterWithoutAGroupBelongsToThePacksName() {
        ValidatedAvatarPack pack = validator.validate(pack("  Leagues  ", character("nfl", "NFL", null, PNG)));
        assertThat(pack.name()).isEqualTo("Leagues");
        assertThat(pack.groups()).containsExactly("Leagues");
    }

    @Test
    void rejectsDocumentsThatAreNotPacks() {
        assertThatThrownBy(() -> validator.validate("not json")).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> validator.validate("[]")).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> validator.validate("{\"version\": 2, \"name\": \"X\", \"characters\": []}"))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("version");
        assertThatThrownBy(() -> validator.validate("{\"version\": 1, \"characters\": []}"))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("name");
        assertThatThrownBy(() -> validator.validate(pack("Empty")))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("at least one character");
    }

    @Test
    void capsThePackAt120Characters() {
        String many = IntStream.range(0, AvatarPackDocumentValidator.MAX_CHARACTERS + 1)
                .mapToObj(i -> character("c" + i, "C" + i, null, PNG))
                .collect(Collectors.joining(","));
        assertThatThrownBy(() -> validator.validate(pack("Many", many)))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("120");
    }

    @Test
    void rejectsBadOrRepeatedKeysAndMissingNames() {
        assertThatThrownBy(() -> validator.validate(pack("P", character("Pika Chu", "Pikachu", null, PNG))))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("Pika Chu");
        assertThatThrownBy(() -> validator.validate(pack("P",
                character("pikachu", "Pikachu", null, PNG), character("pikachu", "Pikachu 2", null, PNG))))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("pikachu");
        assertThatThrownBy(() -> validator.validate(pack("P", character("pikachu", " ", null, PNG))))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("pikachu");
    }

    @Test
    void imagesMustBeSmallPngJpegOrWebpCheckedByTheirBytes() {
        String svg = "data:image/svg+xml;base64,PHN2Zy8+";
        String lying = dataUrl("image/png", JPEG_SIGNATURE, 200);
        String big = dataUrl("image/png", PNG_SIGNATURE, AvatarPackDocumentValidator.MAX_IMAGE_BYTES + 1);
        for (String image : List.of(svg, lying, big, "https://example.com/a.png")) {
            assertThatThrownBy(() -> validator.validate(pack("P", character("pikachu", "Pikachu", null, image))))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessageContaining("pikachu");
        }
    }

    @Test
    void capsTheDocumentAt4Mb() {
        String huge = pack("Huge", character("a", "A", "x".repeat(AvatarPackDocumentValidator.MAX_BYTES), PNG));
        assertThatThrownBy(() -> validator.validate(huge))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("4 MB");
    }
}
