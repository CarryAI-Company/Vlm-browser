package co.carryai.chromeclone;

import java.util.Locale;

/**
 * URL normalization helpers for the browser address bar.
 * Pure-Java so it can be unit tested on the JVM.
 */
public final class UrlUtils {

    private UrlUtils() {}

    /**
     * Normalizes user input into a navigable URL.
     * <ul>
     *   <li>"file:///..." or any input with an explicit scheme is returned as-is.</li>
     *   <li>"about:blank" style schemes are returned as-is.</li>
     *   <li>Bare hosts like "example.com" get "https://" prepended.</li>
     *   <li>Inputs with spaces (or no dot) are treated as search queries.</li>
     * </ul>
     */
    public static String normalizeUrl(String input) {
        if (input == null) {
            return "about:blank";
        }
        String trimmed = input.trim();
        if (trimmed.isEmpty()) {
            return "about:blank";
        }
        // Explicit scheme (http://, https://, file://, about:, data:, javascript:, content:)
        String lower = trimmed.toLowerCase(Locale.ROOT);
        if (lower.matches("^[a-z][a-z0-9+\\-.]*:.*")) {
            return trimmed;
        }
        // Looks like a search query when it contains whitespace or no dot at all
        if (trimmed.contains(" ") || !trimmed.contains(".")) {
            return "https://www.google.com/search?q=" + urlEncode(trimmed);
        }
        return "https://" + trimmed;
    }

    /** True when the input already carries an explicit URI scheme. */
    public static boolean hasScheme(String input) {
        if (input == null) return false;
        return input.trim().toLowerCase(Locale.ROOT)
                .matches("^[a-z][a-z0-9+\\-.]*:.*");
    }

    /** Minimal percent-encoding for query strings (space and URL-reserved chars). */
    static String urlEncode(String s) {
        StringBuilder out = new StringBuilder(s.length() * 2);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9') || c == '-' || c == '_' || c == '.' || c == '~') {
                out.append(c);
            } else if (c == ' ') {
                out.append('+');
            } else {
                out.append('%');
                out.append(Character.toUpperCase(Character.forDigit((c >> 4) & 0xF, 16)));
                out.append(Character.toUpperCase(Character.forDigit(c & 0xF, 16)));
            }
        }
        return out.toString();
    }
}
