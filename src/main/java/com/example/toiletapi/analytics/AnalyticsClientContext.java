package com.example.toiletapi.analytics;

import java.util.Locale;
import java.util.regex.Pattern;

/** Coarse, self-declared request environment, never a referral or verified human identity. */
final class AnalyticsClientContext {
    private AnalyticsClientContext() { }
    private static final Pattern AUTOMATION = Pattern.compile("bot|crawler|spider|headless|preview|(?:^|[\\s;(])(?:yeti|ads-naver|blueno|claude-user|chatgpt-user|facebookexternalhit)(?=[/\\s;)]|$)");
    static String context(String raw) {
        String ua = raw == null ? "" : raw.toLowerCase(Locale.ROOT);
        if (AUTOMATION.matcher(ua).find()) return "AUTOMATION";
        if (ua.contains("kakaotalk")) return "KAKAOTALK";
        if (ua.contains("naver(" ) || ua.contains("naver/") || ua.contains("naver(inapp")) return "NAVER_APP";
        if (ua.contains("line/")) return "LINE";
        if (ua.contains("instagram")) return "INSTAGRAM";
        if (ua.contains("fban/") || ua.contains("fbav/")) return "FACEBOOK";
        if (ua.contains("gsa/")) return "GOOGLE_APP";
        if (ua.contains("; wv)") || ua.contains("; wv;")) return "ANDROID_WEBVIEW";
        if (!browser(ua).equals("Other")) return "BROWSER";
        if ((ua.contains("iphone") || ua.contains("ipad")) && ua.contains("applewebkit")) return "IOS_WEBVIEW";
        return "UNKNOWN";
    }
    static String browser(String raw) {
        String ua = raw == null ? "" : raw.toLowerCase(Locale.ROOT);
        if (ua.contains("edg/") || ua.contains("edgios/") || ua.contains("edga/")) return "Edge";
        if (ua.contains("samsungbrowser")) return "Samsung Internet";
        if (ua.contains("fxios/") || ua.contains("firefox/")) return "Firefox";
        if (ua.contains("crios/") || ua.contains("chrome/")) return "Chrome";
        if (ua.contains("safari/")) return "Safari";
        return "Other";
    }
}
