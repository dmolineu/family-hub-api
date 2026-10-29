package com.familyhub.demo.service;

import java.util.Optional;
import java.util.regex.Pattern;

/**
 * The shape of a member's theme. Packs are named rather than numbered so a member
 * keeps their look when the family deletes and re-imports a newer version of a pack.
 */
public final class MemberThemes {

    public static final Pattern BUILT_IN = Pattern.compile("^builtin:[a-z0-9-]{1,40}$");
    public static final String PACK_PREFIX = "pack:";

    private MemberThemes() {}

    /** The pack name a theme names, if it names a pack. */
    public static Optional<String> packName(String theme) {
        if (theme == null || !theme.startsWith(PACK_PREFIX)) return Optional.empty();
        String name = theme.substring(PACK_PREFIX.length()).trim();
        return name.isEmpty() ? Optional.empty() : Optional.of(name);
    }

    /** A built-in look, or a pack name of at most 80 characters (the pack name limit). */
    public static boolean isWellFormed(String theme) {
        if (theme == null) return false;
        if (BUILT_IN.matcher(theme).matches()) return true;
        return packName(theme).filter(name -> name.length() <= 80).isPresent();
    }
}
