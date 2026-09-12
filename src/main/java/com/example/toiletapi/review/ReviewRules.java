package com.example.toiletapi.review;

import java.time.Duration;
import java.time.Instant;

/** Review-specific rules. Browser location is a claim, not attested proof of presence. */
public final class ReviewRules {
    public static final int MAX_COMMENT_CODE_POINTS = 200;
    public static final Duration EDIT_WINDOW = Duration.ofDays(7);
    public static final Duration MAX_LOCATION_AGE = Duration.ofMinutes(5);
    private ReviewRules() { }

    public record Content(Integer satisfaction, Boolean paperAvailable, Integer cleanliness,
                          Integer waitMinutes, String comment) { }
    public record Position(Double latitude, Double longitude, Double accuracyMeters, Instant measuredAt) { }
    public record LocationPolicy(double radiusMeters, double maxAccuracyMeters) {
        public LocationPolicy {
            if (!Double.isFinite(radiusMeters) || radiusMeters <= 0
                    || !Double.isFinite(maxAccuracyMeters) || maxAccuracyMeters <= 0)
                throw new IllegalArgumentException("리뷰 위치 기준이 올바르지 않습니다.");
        }
    }

    public static Content validate(Content value) {
        if (value == null) throw new IllegalArgumentException("리뷰 내용을 입력해 주세요.");
        stars(value.satisfaction(), "만족도");
        stars(value.cleanliness(), "청결도");
        if (value.paperAvailable() == null) throw new IllegalArgumentException("화장지 유무를 선택해 주세요.");
        int wait = value.waitMinutes() == null ? 0 : value.waitMinutes();
        if (wait < 0 || wait > 60 || wait % 10 != 0) {
            throw new IllegalArgumentException("대기시간은 0~60분에서 10분 단위로 선택해 주세요.");
        }
        String comment = value.comment() == null ? "" : value.comment().strip();
        if (comment.codePointCount(0, comment.length()) > MAX_COMMENT_CODE_POINTS)
            throw new IllegalArgumentException("내용은 200자 이내로 입력해 주세요.");
        if (comment.codePoints().anyMatch(c -> (Character.isISOControl(c) && c != '\n' && c != '\t') || (c >= 0xD800 && c <= 0xDFFF)))
            throw new IllegalArgumentException("내용에 사용할 수 없는 문자가 있어요.");
        return new Content(value.satisfaction(), value.paperAvailable(), value.cleanliness(), wait, comment);
    }

    private static void stars(Integer value, String label) {
        if (value == null || value < 1 || value > 5)
            throw new IllegalArgumentException(label + "는 1~5점에서 선택해 주세요.");
    }

    /** Call on the server with database toilet coordinates; never trust a client distance. */
    public static double requireNearby(Position claimed, Double toiletLatitude, Double toiletLongitude,
                                       Instant now, LocationPolicy policy) {
        if (claimed == null || claimed.measuredAt() == null)
            throw new IllegalArgumentException("현재 위치 권한을 허용하고 위치를 다시 확인해 주세요.");
        if (toiletLatitude == null || toiletLongitude == null
                || !coordinates(toiletLatitude, toiletLongitude))
            throw new IllegalArgumentException("화장실의 위치 정보가 없어 리뷰를 작성할 수 없어요.");
        if (claimed.latitude() == null || claimed.longitude() == null || claimed.accuracyMeters() == null
                || !coordinates(claimed.latitude(), claimed.longitude()) || !Double.isFinite(claimed.accuracyMeters())
                || claimed.accuracyMeters() < 0 || claimed.accuracyMeters() > policy.maxAccuracyMeters())
            throw new IllegalArgumentException("위치 오차가 커요. 정확한 위치를 켜고 다시 확인해 주세요.");
        if (claimed.measuredAt().isAfter(now.plusSeconds(5))
                || claimed.measuredAt().isBefore(now.minus(MAX_LOCATION_AGE)))
            throw new IllegalArgumentException("위치 확인 시간이 지났어요. 현재 위치를 다시 확인해 주세요.");
        double distance = distanceMeters(claimed.latitude(), claimed.longitude(), toiletLatitude, toiletLongitude);
        if (distance > policy.radiusMeters())
            throw new IllegalArgumentException("화장실에서 " + Math.round(policy.radiusMeters()) + "m 안에 있어야 리뷰 작성이 가능합니다.");
        return distance;
    }

    public static boolean canManage(Long ownerId, long requesterId, Instant createdAt, Instant now) {
        return ownerId != null && ownerId == requesterId && createdAt != null
                && !now.isBefore(createdAt) && now.isBefore(createdAt.plus(EDIT_WINDOW));
    }

    private static boolean coordinates(double latitude, double longitude) {
        return Double.isFinite(latitude) && Double.isFinite(longitude)
                && latitude >= -90 && latitude <= 90 && longitude >= -180 && longitude <= 180;
    }

    static double distanceMeters(double lat1, double lon1, double lat2, double lon2) {
        double dLat = Math.toRadians(lat2 - lat1), dLon = Math.toRadians(lon2 - lon1);
        double a = Math.pow(Math.sin(dLat / 2), 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) * Math.pow(Math.sin(dLon / 2), 2);
        return 6_371_000 * 2 * Math.asin(Math.sqrt(Math.min(1, Math.max(0, a))));
    }
}
