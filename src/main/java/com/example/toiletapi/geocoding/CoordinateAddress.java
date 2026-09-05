package com.example.toiletapi.geocoding;

import java.math.BigDecimal;

/** The exact persisted coordinates and separately identified provider addresses. */
public record CoordinateAddress(BigDecimal latitude, BigDecimal longitude,
                                String roadAddress, String jibunAddress) {
}
