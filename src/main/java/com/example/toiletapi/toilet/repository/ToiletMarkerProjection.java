package com.example.toiletapi.toilet.repository;

import java.math.BigDecimal;

/** Only columns required to draw a public map marker. */
public interface ToiletMarkerProjection {
    Long getId();
    String getName();
    String getToiletType();
    BigDecimal getLatitude();
    BigDecimal getLongitude();
}
