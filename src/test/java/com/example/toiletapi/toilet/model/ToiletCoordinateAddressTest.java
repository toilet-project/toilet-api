package com.example.toiletapi.toilet.model;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ToiletCoordinateAddressTest {
    @Test void missingCounterpartClearsPreviousLocationAddress() {
        Toilet toilet = new Toilet();
        toilet.applyAdminConfirmedCoordinates(BigDecimal.ONE, BigDecimal.TEN, "이전 도로명", "이전 지번");
        toilet.applyAdminConfirmedCoordinates(BigDecimal.TEN, BigDecimal.ONE, null, "새 지번");
        assertNull(toilet.getRoadAddress());
        assertEquals("새 지번", toilet.getJibunAddress());
        toilet.applyAdminConfirmedCoordinates(BigDecimal.ONE, BigDecimal.TEN, "새 도로명", null);
        assertEquals("새 도로명", toilet.getRoadAddress());
        assertNull(toilet.getJibunAddress());
        assertEquals("ADMIN_CONFIRMED", toilet.getCoordinateSource());
    }
}
