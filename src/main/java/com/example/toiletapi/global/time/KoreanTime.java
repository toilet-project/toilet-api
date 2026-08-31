package com.example.toiletapi.global.time;

import java.time.LocalDateTime;
import java.time.ZoneId;

/** 서비스와 DB의 업무 시각을 한국 표준시로 생성합니다. */
public final class KoreanTime {

    public static final ZoneId ZONE_ID = ZoneId.of("Asia/Seoul");

    private KoreanTime() {
    }

    public static LocalDateTime now() {
        return LocalDateTime.now(ZONE_ID);
    }
}
