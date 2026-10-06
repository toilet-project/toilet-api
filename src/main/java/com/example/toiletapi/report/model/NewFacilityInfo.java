package com.example.toiletapi.report.model;

import java.util.Set;

/** Optional proposal/approval snapshot. Null means unknown, never an inferred absence. */
public record NewFacilityInfo(String name, String toiletType, String openTime, String openTimeDetail,
        String agencyName, String phoneNumber, Boolean emergencyBell, Boolean cctv, Boolean diaperTable,
        Integer maleDisabledToiletCount, Integer femaleDisabledToiletCount) {
    public static NewFacilityInfo legacy(String name, String openTime) {
        return new NewFacilityInfo(name, null, openTime, null, null, null, null, null, null, null, null);
    }

    public NewFacilityInfo validated(String fallbackName) {
        String finalName = text(name == null ? fallbackName : name, 100);
        if (finalName == null) throw new IllegalArgumentException("신규 화장실 이름을 입력해 주세요.");
        String type = text(toiletType, 20);
        if (type != null && !Set.of("공중", "개방", "이동", "간이", "기타").contains(type))
            throw new IllegalArgumentException("화장실 구분을 확인해 주세요.");
        String phone = text(phoneNumber, 20);
        if (phone != null && !phone.matches("[0-9+() .-]+"))
            throw new IllegalArgumentException("시설 연락처를 확인해 주세요.");
        return new NewFacilityInfo(finalName, type, text(openTime, 50), text(openTimeDetail, 255),
                text(agencyName, 100), phone, emergencyBell, cctv, diaperTable,
                count(maleDisabledToiletCount), count(femaleDisabledToiletCount));
    }

    private static Integer count(Integer value) {
        if (value != null && (value < 0 || value > 999))
            throw new IllegalArgumentException("시설 개수는 0~999 사이로 입력해 주세요.");
        return value;
    }

    private static String text(String value, int max) {
        if (value == null || value.isBlank()) return null;
        String cleaned = value.trim();
        if (cleaned.length() > max || cleaned.codePoints().anyMatch(c -> Character.isISOControl(c)
                && c != '\n' && c != '\r' && c != '\t'))
            throw new IllegalArgumentException("기본 정보의 입력 내용을 확인해 주세요.");
        return cleaned;
    }
}
