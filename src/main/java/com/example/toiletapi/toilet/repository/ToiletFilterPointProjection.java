package com.example.toiletapi.toilet.repository;

import java.math.BigDecimal;

public interface ToiletFilterPointProjection extends ToiletFilterFlagsProjection {
    BigDecimal getLatitude();
    BigDecimal getLongitude();
}
