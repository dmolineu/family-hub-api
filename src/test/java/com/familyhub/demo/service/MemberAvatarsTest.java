package com.familyhub.demo.service;

import com.familyhub.demo.exception.BadRequestException;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MemberAvatarsTest {

    static final byte[] PNG_SIGNATURE = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};
    static final byte[] JPEG_SIGNATURE = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF};
    static final byte[] WEBP_SIGNATURE = {'R', 'I', 'F', 'F', 0, 0, 0, 0, 'W', 'E', 'B', 'P'};

    /** A data URL whose bytes start with the given signature and are padded to {@code size}. */
    static String dataUrl(String type, byte[] signature, int size) {
        byte[] bytes = Arrays.copyOf(signature, size);
        return "data:" + type + ";base64," + Base64.getEncoder().encodeToString(bytes);
    }

    @Test
    void noAvatarMeansInitials() {
        assertThat(MemberAvatars.normalize(null)).isEmpty();
        assertThat(MemberAvatars.normalize("   ")).isEmpty();
    }

    @Test
    void acceptsBuiltInPictures() {
        assertThat(MemberAvatars.normalize("builtin:fox")).contains("builtin:fox");
        assertThat(MemberAvatars.normalize("builtin:paper-plane")).contains("builtin:paper-plane");
    }

    @Test
    void rejectsBuiltInKeysOutsideTheSafeAlphabet() {
        assertThatThrownBy(() -> MemberAvatars.normalize("builtin:Fox!"))
                .isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> MemberAvatars.normalize("builtin:"))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void acceptsPngJpegAndWebpImagesUpTo64KbCheckedByTheirBytes() {
        String png = dataUrl("image/png", PNG_SIGNATURE, MemberAvatars.MAX_IMAGE_BYTES);
        String jpeg = dataUrl("image/jpeg", JPEG_SIGNATURE, 20_000);
        String webp = dataUrl("image/webp", WEBP_SIGNATURE, 20_000);

        assertThat(MemberAvatars.normalize(png)).contains(png);
        assertThat(MemberAvatars.normalize(jpeg)).contains(jpeg);
        assertThat(MemberAvatars.normalize(webp)).contains(webp);
    }

    @Test
    void rejectsImagesOver64Kb() {
        String big = dataUrl("image/png", PNG_SIGNATURE, MemberAvatars.MAX_IMAGE_BYTES + 1);
        assertThatThrownBy(() -> MemberAvatars.normalize(big))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("64 KB");
    }

    @Test
    void rejectsImagesWhoseBytesAreNotTheDeclaredType() {
        String lying = dataUrl("image/jpeg", PNG_SIGNATURE, 100);
        assertThatThrownBy(() -> MemberAvatars.normalize(lying))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void rejectsSvgAndOtherTypes() {
        String svg = "data:image/svg+xml;base64," + Base64.getEncoder().encodeToString("<svg/>".getBytes());
        assertThatThrownBy(() -> MemberAvatars.normalize(svg)).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> MemberAvatars.normalize("data:text/html;base64,PGI+")).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> MemberAvatars.normalize("data:image/png;base64,not base64!")).isInstanceOf(BadRequestException.class);
    }

    @Test
    void keepsShortHttpsLinksForAvatarsSetBeforePicturesExisted() {
        assertThat(MemberAvatars.normalize("https://example.com/a.png")).contains("https://example.com/a.png");
        assertThatThrownBy(() -> MemberAvatars.normalize("http://example.com/a.png"))
                .isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> MemberAvatars.normalize("https://example.com/" + "a".repeat(250)))
                .isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> MemberAvatars.normalize("javascript:alert(1)"))
                .isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> MemberAvatars.normalize("just text"))
                .isInstanceOf(BadRequestException.class);
    }
}
