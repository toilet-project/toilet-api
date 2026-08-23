package com.example.toiletapi.toilet.repository;

import java.math.BigDecimal;

/**
 * 지도 격자별 화장실 집계 결과입니다.
 */
public interface ToiletClusterProjection {

    BigDecimal getLatitude();

    BigDecimal getLongitude();

    long getToiletCount();
}
