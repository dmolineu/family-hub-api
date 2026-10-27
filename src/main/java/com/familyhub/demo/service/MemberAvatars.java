package com.familyhub.demo.service;

import com.familyhub.demo.exception.BadRequestException;

import java.util.Optional;
import java.util.regex.Pattern;

/**
 * The forms a member's avatar may take, checked wherever one is saved (adding or editing
 * a member, registration and restoring a backup):
 * <ul>
 *   <li>none, so the member shows their initial on their colour;</li>
 *   <li>{@code builtin:<key>}, one of the pictures drawn into the app (the app falls back
 *       to the initial for a key it does not know);</li>
 *   <li>a PNG, JPEG or WebP {@code data:} URL of at most 64 KB, which is how an uploaded
 *       photo and a chosen character are stored: a copy on the member, so it still shows
 *       after its pack is deleted and travels in backups;</li>
 *   <li>a short {@code https} link, the only form the app accepted before pictures.</li>
 * </ul>
 */
public final class MemberAvatars {

    public static final int MAX_IMAGE_BYTES = 64 * 1024;
    public static final int MAX_LINK_LENGTH = 254;
    public static final String BUILT_IN_PREFIX = "builtin:";

    private static final Pattern BUILT_IN_KEY = Pattern.compile("^[a-z0-9-]{1,40}$");

    private MemberAvatars() {}

    /**
     * Returns the avatar to store, or empty for none.
     *
     * @throws BadRequestException when the value is not one of the accepted forms
     */
    public static Optional<String> normalize(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        String value = raw.trim();
        if (value.startsWith(BUILT_IN_PREFIX)) {
            if (!BUILT_IN_KEY.matcher(value.substring(BUILT_IN_PREFIX.length())).matches()) {
                throw new BadRequestException("That built-in picture does not exist");
            }
            return Optional.of(value);
        }
        if (value.startsWith("data:")) {
            InlineImages.Picture picture = InlineImages.parse(value)
                    .orElseThrow(() -> new BadRequestException("An avatar picture must be a PNG, JPEG or WebP image"));
            if (picture.bytes() > MAX_IMAGE_BYTES) {
                throw new BadRequestException("An avatar picture must be 64 KB or smaller");
            }
            return Optional.of(value);
        }
        if (value.startsWith("https://") && value.length() <= MAX_LINK_LENGTH && !value.matches(".*\\s.*")) {
            return Optional.of(value);
        }
        throw new BadRequestException("An avatar is a built-in picture, an uploaded photo or a character");
    }
}
