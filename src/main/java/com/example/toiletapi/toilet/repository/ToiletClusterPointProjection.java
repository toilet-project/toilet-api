package com.example.toiletapi.toilet.repository;

import java.math.BigDecimal;

/** Coordinates only: the edge Worker builds viewport clusters without loading facility details. */
public interface ToiletClusterPointProjection {
    BigDecimal getLatitude();
    BigDecimal getLongitude();
}
