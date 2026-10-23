package com.familyhub.demo.dto;

/**
 * Enough to draw a pack's card in the Appearance picker without downloading the
 * pack: its five card colours (any may be null when the pack does not set that
 * token) and a small JPEG thumbnail of its backdrop as a data URL, or null when
 * the pack has no decodable picture.
 */
public record ThemePackPreview(
        String background,
        String card,
        String primary,
        String foreground,
        String accent,
        String thumbnail
) {
    public static ThemePackPreview empty() {
        return new ThemePackPreview(null, null, null, null, null, null);
    }
}
