package com.example.toiletapi.analytics;

import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class AnalyticsEventService {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final Set<String> ORIGINS = Set.of(
            "https://geupddong.com", "https://www.geupddong.com", "https://preview.geupddong.com",
            "http://localhost:5173");
    private static final Set<String> PRODUCTION_ORIGINS = Set.of(
            "https://geupddong.com", "https://www.geupddong.com");
    private static final Set<String> EVENTS = Set.of(
            "page_view", "session_start", "engagement", "screen_view", "scroll_depth", "toilet_search",
            "nearby_search", "search_result_select", "toilet_marker_select", "toilet_detail_open",
            "directions_click", "report_start", "report_submit", "login_result", "review_submit");
    private static final Set<String> KEY_EVENTS = Set.of(
            "nearby_search", "search_result_select", "report_start", "report_submit", "login_result", "review_submit");
    private static final Set<String> RESULT_BUCKETS = Set.of("", "0", "1", "2-5", "6-10", "11-25", "26+");
    private static final Set<String> SCREEN_KEYS = Set.of(
            "notifications", "account_home", "my_reports", "my_reviews", "account_settings",
            "review_list", "review_write", "not_found");

    private final AnalyticsEventWriter writer;
    private final Clock clock;
    private final boolean enabled;
    private final byte[] secret;
    private final Map<String, AtomicInteger> minuteCounts = new ConcurrentHashMap<>();

    @Autowired
    public AnalyticsEventService(AnalyticsEventWriter writer,
                                 @Value("${service-analytics.enabled:false}") boolean enabled,
                                 @Value("${service-analytics.visitor-secret:}") String secret) {
        this(writer, Clock.systemUTC(), enabled, secret);
    }

    AnalyticsEventService(AnalyticsRepository repository, Clock clock, boolean enabled, String secret) {
        this(new AnalyticsEventWriter(repository), clock, enabled, secret);
    }

    AnalyticsEventService(AnalyticsEventWriter writer, Clock clock, boolean enabled, String secret) {
        this.writer = writer;
        this.clock = clock;
        this.enabled = enabled && secret != null && secret.length() >= 32;
        this.secret = deriveKey(secret);
    }

    public void collect(AnalyticsEventRequest request, HttpServletRequest http) {
        String origin = clean(http.getHeader("Origin"));
        if (!ORIGINS.contains(origin)) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "허용되지 않은 출처입니다.");
        if (!enabled) return;

        String event = clean(request.event()).toLowerCase(Locale.ROOT);
        if (!EVENTS.contains(event)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "허용되지 않은 분석 이벤트입니다.");
        if (!PRODUCTION_ORIGINS.contains(origin)) return;
        String userAgent = clean(http.getHeader("User-Agent"));
        if (isBot(userAgent)) return;

        Instant now = clock.instant();
        byte[] visitorHash = visitorHash(clientNetwork(http), userAgent, now);
        byte[] sessionHash = sessionHash(request.sessionId(), visitorHash, now);
        enforceRate(visitorHash, now);
        Client client = classify(userAgent);
        Referral referral = referral(request.referrerHost(), request.utmSource(), request.utmMedium());
        int engagement = "engagement".equals(event) ? Math.min(number(request.engagementSeconds()), 3600) : 0;
        String resultBucket = RESULT_BUCKETS.contains(clean(request.resultCountBucket()))
                ? clean(request.resultCountBucket()) : "";
        String detail = eventDetail(event, request);
        try {
            writer.write(new AnalyticsRepository.EventRow(
                now, now.atZone(SEOUL).toLocalDate(), event, pageKey(request.path()), referral.channel(), referral.source(),
                client.device(), client.os(), client.browser(), country(http.getHeader("CF-IPCountry")),
                city(http.getHeader("CF-IPCity")), visitorHash, sessionHash, engagement, resultBucket, detail,
                request.success(), Boolean.TRUE.equals(request.newVisitor()), KEY_EVENTS.contains(event)));
        } catch (RuntimeException ignored) {
            // 분석 큐가 가득 차도 사용자 기능과 응답은 계속 동작한다.
        }
    }

    private void enforceRate(byte[] hash, Instant now) {
        long minute = now.getEpochSecond() / 60;
        String key = minute + ":" + HexFormat.of().formatHex(hash, 0, 8);
        if (minuteCounts.size() > 20_000) minuteCounts.keySet().removeIf(value -> !value.startsWith(minute + ":"));
        if (minuteCounts.computeIfAbsent(key, ignored -> new AtomicInteger()).incrementAndGet() > 120) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "분석 이벤트 요청이 너무 많습니다.");
        }
    }

    private byte[] visitorHash(String network, String userAgent, Instant now) {
        String period = YearMonth.from(now.atZone(SEOUL)).toString();
        return hmac(period + "\n" + network + "\n" + userAgent);
    }

    private byte[] sessionHash(String sessionId, byte[] visitorHash, Instant now) {
        String value = clean(sessionId);
        if (!value.matches("[a-zA-Z0-9._-]{8,64}")) {
            value = HexFormat.of().formatHex(visitorHash, 0, 8) + ":" + now.getEpochSecond() / 1800;
        }
        return hmac("session\n" + value);
    }

    private byte[] hmac(String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Analytics HMAC is unavailable", exception);
        }
    }

    private static byte[] deriveKey(String secret) {
        if (secret == null || secret.isBlank()) return new byte[0];
        try {
            return MessageDigest.getInstance("SHA-256")
                    .digest(("service-analytics-v1\n" + secret).getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Analytics key derivation is unavailable", exception);
        }
    }

    private static String eventDetail(String event, AnalyticsEventRequest request) {
        if ("scroll_depth".equals(event) && request.scrollPercent() != null
                && Set.of(50, 90).contains(request.scrollPercent())) {
            return request.scrollPercent().toString();
        }
        String detail = clean(request.detail()).toLowerCase(Locale.ROOT);
        if ("screen_view".equals(event)) return SCREEN_KEYS.contains(detail) ? detail : "";
        return detail.matches("[a-z0-9_+-]{1,40}") ? detail : "";
    }

    private static String clientNetwork(HttpServletRequest request) {
        String value = clean(request.getHeader("CF-Connecting-IP"));
        if (value.isBlank()) value = clean(request.getRemoteAddr());
        if (value.contains(".")) {
            String[] parts = value.split("\\.");
            return parts.length == 4 ? parts[0] + "." + parts[1] + "." + parts[2] + ".0/24" : "unknown";
        }
        String[] parts = value.split(":");
        return parts.length >= 4 ? String.join(":", parts[0], parts[1], parts[2], parts[3]) + "::/64" : "unknown";
    }

    private static String pageKey(String raw) {
        String path = clean(raw).split("[?#]", 2)[0];
        if (path.length() > 1) path = path.replaceFirst("/+$", "");
        if (path.isBlank()) return "/";
        if (path.matches("/toilet/\\d+") || "/toilet/:id".equals(path)) return "/toilet/:id";
        if (path.matches("/policies/(terms|privacy|location|all)")) return path;
        if ("/".equals(path)) return path;
        return "/other";
    }

    private static Referral referral(String rawHost, String rawUtmSource, String rawUtmMedium) {
        String campaignSource = campaignValue(rawUtmSource, 40);
        String campaignMedium = campaignValue(rawUtmMedium, 24).replace('-', '_');
        if (!campaignSource.isBlank()) return new Referral(campaignChannel(campaignMedium), campaignSource);

        String host = clean(rawHost).toLowerCase(Locale.ROOT).replaceFirst("\\.$", "");
        if (host.isBlank()) return new Referral("Direct", "none");
        if (!host.matches("[a-z0-9](?:[a-z0-9.-]{0,118}[a-z0-9])?")) return new Referral("Unassigned", "unknown");
        if (domain(host, "geupddong.com")) return new Referral("Internal", "geupddong");
        if (host.startsWith("google.") || host.contains(".google.")) return new Referral("Organic Search", "google");
        if (domain(host, "naver.com")) return new Referral("Organic Search", "naver");
        if (domain(host, "daum.net")) return new Referral("Organic Search", "daum");
        if (domain(host, "bing.com")) return new Referral("Organic Search", "bing");
        if (domain(host, "kakao.com") || domain(host, "kakao.co.kr")) return new Referral("Organic Social", "kakao");
        if (domain(host, "instagram.com")) return new Referral("Organic Social", "instagram");
        if (domain(host, "facebook.com")) return new Referral("Organic Social", "facebook");
        if (domain(host, "threads.net")) return new Referral("Organic Social", "threads");
        if (domain(host, "x.com") || domain(host, "twitter.com")) return new Referral("Organic Social", "x");
        return new Referral("Referral", host.length() > 80 ? host.substring(0, 80) : host);
    }

    private static String campaignValue(String raw, int maximum) {
        String value = clean(raw).toLowerCase(Locale.ROOT);
        return value.length() <= maximum && value.matches("[a-z0-9._+-]+") ? value : "";
    }

    private static String campaignChannel(String medium) {
        return switch (medium) {
            case "organic", "organic_search", "seo" -> "Organic Search";
            case "social", "organic_social", "social_media" -> "Organic Social";
            case "paid_social" -> "Paid Social";
            case "cpc", "ppc", "paid_search", "sem" -> "Paid Search";
            case "email", "newsletter" -> "Email";
            case "referral", "affiliate" -> "Referral";
            case "qr", "offline" -> "Offline";
            default -> "Campaign";
        };
    }

    private static boolean domain(String host, String domain) {
        return host.equals(domain) || host.endsWith("." + domain);
    }

    private static Client classify(String ua) {
        String value = ua.toLowerCase(Locale.ROOT);
        String device = value.contains("ipad") || value.contains("tablet") ? "tablet"
                : value.contains("mobile") || value.contains("android") ? "mobile" : "desktop";
        String os = value.contains("android") ? "Android" : value.contains("iphone") || value.contains("ipad") ? "iOS"
                : value.contains("windows") ? "Windows" : value.contains("mac os") || value.contains("macintosh") ? "macOS"
                : value.contains("linux") ? "Linux" : "Other";
        String browser = value.contains("edg/") ? "Edge" : value.contains("samsungbrowser") ? "Samsung Internet"
                : value.contains("chrome/") ? "Chrome" : value.contains("safari/") ? "Safari"
                : value.contains("firefox/") ? "Firefox" : "Other";
        return new Client(device, os, browser);
    }

    private static boolean isBot(String ua) {
        String value = ua.toLowerCase(Locale.ROOT);
        return value.isBlank() || value.contains("bot") || value.contains("crawler") || value.contains("spider")
                || value.contains("headless") || value.contains("preview");
    }

    private static String country(String raw) {
        String value = clean(raw).toUpperCase(Locale.ROOT);
        return value.matches("[A-Z]{2}") ? value : "ZZ";
    }

    private static String city(String raw) {
        String value = clean(raw);
        return value.isBlank() ? "Unknown" : value.substring(0, Math.min(80, value.length()));
    }

    private static int number(Integer value) { return value == null ? 0 : Math.max(0, value); }
    private static String clean(String value) { return value == null ? "" : value.trim(); }
    private record Client(String device, String os, String browser) { }
    private record Referral(String channel, String source) { }
}
