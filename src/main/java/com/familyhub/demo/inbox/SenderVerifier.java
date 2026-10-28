package com.familyhub.demo.inbox;

import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Decides whether a message really comes from its From address, using the verdict the
 * receiving mail server (Gmail) wrote on top of it: DMARC passed for the From domain,
 * or a DKIM signature from that domain (or a subdomain of it) passed. Anyone can put an
 * Authentication-Results header into a message they send, so only the topmost one, from
 * a trusted server, counts.
 */
public class SenderVerifier {
    private static final Pattern COMMENT = Pattern.compile("\\([^)]*\\)");
    private static final Pattern HEADER_FROM = Pattern.compile("header\\.from=([^\\s;]+)");
    private static final Pattern DKIM_DOMAIN = Pattern.compile("header\\.(?:i=[^@\\s;]*@|d=)([^\\s;]+)");

    private final List<String> trustedAuthservIds;

    public SenderVerifier(List<String> trustedAuthservIds) {
        this.trustedAuthservIds = trustedAuthservIds.stream().map(id -> id.toLowerCase(Locale.ROOT)).toList();
    }

    /** Whether the message verifiably comes from {@code from}'s domain. */
    public boolean verified(String from, List<String> authenticationResults) {
        if (from == null || !from.contains("@") || authenticationResults.isEmpty()) {
            return false;
        }
        String domain = from.substring(from.lastIndexOf('@') + 1).toLowerCase(Locale.ROOT);
        String top = COMMENT.matcher(authenticationResults.getFirst()).replaceAll(" ")
                .replaceAll("\\s+", " ").trim().toLowerCase(Locale.ROOT);
        String[] parts = top.split(";");
        String authservId = parts[0].trim().split(" ")[0];
        if (!trustedAuthservIds.contains(authservId)) {
            return false;
        }
        for (int i = 1; i < parts.length; i++) {
            String result = parts[i].trim();
            if (result.startsWith("dmarc=pass") && domain.equals(value(HEADER_FROM, result))) {
                return true;
            }
            if (result.startsWith("dkim=pass") && aligned(value(DKIM_DOMAIN, result), domain)) {
                return true;
            }
        }
        return false;
    }

    private static String value(Pattern pattern, String result) {
        Matcher matcher = pattern.matcher(result);
        return matcher.find() ? matcher.group(1) : null;
    }

    /**
     * The same domain, or one inside the other (DKIM's relaxed alignment). A signer
     * without a dot (a bare top-level domain) never vouches for anyone.
     */
    private static boolean aligned(String signer, String domain) {
        return signer != null && signer.contains(".") && (signer.equals(domain)
                || signer.endsWith("." + domain) || domain.endsWith("." + signer));
    }
}
