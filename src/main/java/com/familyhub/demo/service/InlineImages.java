package com.familyhub.demo.service;

import java.util.Base64;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Raster pictures carried inline as {@code data:} URLs (member avatars and the
 * characters of avatar packs). They are shown in {@code <img>} tags on every device, so
 * only PNG, JPEG and WebP are accepted, and the bytes must really be the declared type:
 * a mislabelled file would show as a broken picture on the wall.
 */
final class InlineImages {

    private static final Pattern DATA_URL =
            Pattern.compile("^data:image/(png|jpeg|webp);base64,([A-Za-z0-9+/]+={0,2})$");

    private InlineImages() {}

    /** A decoded picture: its declared type and how many bytes it holds. */
    record Picture(String type, int bytes) {}

    /**
     * Empty when the value is not a PNG, JPEG or WebP data URL whose bytes start with
     * that format's signature.
     */
    static Optional<Picture> parse(String value) {
        if (value == null) {
            return Optional.empty();
        }
        Matcher matcher = DATA_URL.matcher(value);
        if (!matcher.matches()) {
            return Optional.empty();
        }
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(matcher.group(2));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
        String type = matcher.group(1);
        boolean signatureMatches = switch (type) {
            case "png" -> startsWith(bytes, 0, 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A);
            case "jpeg" -> startsWith(bytes, 0, 0xFF, 0xD8, 0xFF);
            case "webp" -> startsWith(bytes, 0, 'R', 'I', 'F', 'F') && startsWith(bytes, 8, 'W', 'E', 'B', 'P');
            default -> false;
        };
        return signatureMatches ? Optional.of(new Picture(type, bytes.length)) : Optional.empty();
    }

    private static boolean startsWith(byte[] bytes, int offset, int... signature) {
        if (bytes.length < offset + signature.length) {
            return false;
        }
        for (int i = 0; i < signature.length; i++) {
            if ((bytes[offset + i] & 0xFF) != signature[i]) {
                return false;
            }
        }
        return true;
    }
}
