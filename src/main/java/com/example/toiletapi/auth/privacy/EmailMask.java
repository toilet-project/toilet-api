package com.example.toiletapi.auth.privacy;

/** Display-only hint. Never return an invalid address unchanged. */
public final class EmailMask {
    private EmailMask() { }

    public static String mask(String value) {
        if (value == null || value.isBlank()) return null;
        String email = value.trim();
        int at = email.lastIndexOf('@');
        if (at < 1 || at != email.indexOf('@') || at == email.length() - 1
                || email.codePoints().anyMatch(c -> Character.isWhitespace(c) || Character.isISOControl(c))) return "***";
        String local = email.substring(0, at);
        if (local.contains("*")) local = local.substring(0, local.indexOf('*'));
        int points = local.codePointCount(0, local.length());
        int visible = Math.min(2, Math.max(0, points - (email.substring(0, at).contains("*") ? 0 : 1)));
        return local.substring(0, local.offsetByCodePoints(0, visible)) + "***" + email.substring(at);
    }
}
