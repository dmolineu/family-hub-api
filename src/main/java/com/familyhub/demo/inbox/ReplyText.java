package com.familyhub.demo.inbox;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/** The words a person wrote in a reply, without the earlier email their mail app quoted below them. */
public final class ReplyText {
    /** Lines phones and mail apps add to every message, which are not the sender's words. */
    private static final Pattern SIGNATURE = Pattern.compile(
            "(?i)^\\s*(sent from my .*|sent from (yahoo )?mail.*|get outlook for .*|sent via .*)\\s*$");
    /** Gmail's and Apple Mail's "On <date>, <someone> wrote:". */
    private static final Pattern ATTRIBUTION = Pattern.compile("(?i)^on .*wrote:\\s*$");
    /** Gmail wraps a long attribution: "On Sun, Nov 1, 2026 at 9:24 AM Family Hub <" then "hub@…> wrote:". */
    private static final Pattern WRAPPED_ATTRIBUTION_START = Pattern.compile("(?i)^on .*\\d.*$");
    /** Outlook's "-----Original Message-----", and the rule its newer versions draw instead. */
    private static final Pattern SEPARATOR = Pattern.compile("(?i)^(-{2,}\\s*original message\\s*-{2,}|_{10,})$");

    private ReplyText() {
    }

    /** The reply's own words, trimmed; "" for none. */
    public static String ownWords(String text) {
        if (text == null) {
            return "";
        }
        List<String> lines = text.lines().toList();
        List<String> own = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i).strip();
            if (line.startsWith(">") || SEPARATOR.matcher(line).matches() || ATTRIBUTION.matcher(line).matches()
                    || (WRAPPED_ATTRIBUTION_START.matcher(line).matches() && i + 1 < lines.size()
                    && ATTRIBUTION.matcher("on " + lines.get(i + 1).strip()).matches())) {
                break;
            }
            if (!SIGNATURE.matcher(line).matches()) {
                own.add(lines.get(i));
            }
        }
        return String.join("\n", own).strip();
    }
}
