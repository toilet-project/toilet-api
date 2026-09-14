package com.example.toiletapi.toilet.model;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ToiletCoordinateAddressTest {
    @Test void regionRevisionChangesOnlyWhenRegionInputsChange() {
        Toilet toilet = new Toilet();
        assertEquals(1L, toilet.getRegionRevision());
        toilet.applyAdminConfirmedCoordinates(new BigDecimal("37.5"), new BigDecimal("127.0"), "도로명", null);
        assertEquals(2L, toilet.getRegionRevision());
        toilet.applyAdminConfirmedCoordinates(new BigDecimal("37.5000000"), new BigDecimal("127.0000000"), "도로명", null);
        assertEquals(2L, toilet.getRegionRevision());
        toilet.applyAdminConfirmedCoordinates(new BigDecimal("37.5"), new BigDecimal("127.0"), "도로명 변경", null);
        assertEquals(3L, toilet.getRegionRevision());
    }

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
