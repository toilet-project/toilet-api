package com.example.toiletapi.toilet.openinghours;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalTime;
import org.junit.jupiter.api.Test;

class OpeningHoursParserTest {
    private final OpeningHoursParser parser = new OpeningHoursParser();

    @Test
    void parsesExplicitTwentyFourHoursWithoutKoreanOutputValues() {
        var value = parser.parse("정시", "24시간");
        assertEquals("ALWAYS", value.openingPolicy());
        assertEquals(Boolean.TRUE, value.open24h());
        assertEquals("PARSED", value.status());
        assertTrue(value.schedules().isEmpty());
    }

    @Test
    void parsesEveryPureTwentyFourHourVariantFoundByTheProductionAudit() {
        for (String source : new String[]{"24시간 개방", "00:00 ~ 24:00", "00:00~23:59"}) {
            var value = parser.parse("정시", source);
            assertEquals("ALWAYS", value.openingPolicy(), source);
            assertEquals(Boolean.TRUE, value.open24h(), source);
            assertEquals("PARSED", value.status(), source);
            assertTrue(value.schedules().isEmpty(), source);
        }
    }

    @Test
    void pureTwentyFourHourRepairDoesNotIncludeExceptionText() {
        var value = parser.parse("정시", "24시간 개방, 공휴일 제외");

        assertEquals("REVIEW_REQUIRED", value.status());
        assertNull(value.open24h());
    }

    @Test
    void annualNoHolidayWithLimitedHoursIsNotTwentyFourHours() {
        var value = parser.parse("상시", "연중무휴 09:00~18:00");
        assertEquals("SCHEDULED", value.openingPolicy());
        assertEquals(Boolean.FALSE, value.open24h());
        assertEquals("OPEN", value.holidayPolicy());
        assertEquals(7, value.schedules().size());
    }

    @Test
    void annualNoHolidayAloneNeedsReview() {
        var value = parser.parse("상시", "연중무휴");
        assertEquals("ALWAYS", value.openingPolicy());
        assertNull(value.open24h());
        assertEquals("REVIEW_REQUIRED", value.status());
    }

    @Test
    void parsesWeekdayScheduleIntoIsoDayNumbers() {
        var value = parser.parse("정시", "평일 09:00~18:00");
        assertEquals(Boolean.FALSE, value.open24h());
        assertEquals(5, value.schedules().size());
        assertEquals(1, value.schedules().getFirst().dayOfWeek());
        assertEquals(5, value.schedules().getLast().dayOfWeek());
        assertEquals(LocalTime.of(9, 0), value.schedules().getFirst().startTime());
        assertEquals(LocalTime.of(18, 0), value.schedules().getFirst().endTime());
    }

    @Test
    void recordsOvernightSchedule() {
        var value = parser.parse("정시", "매일 22:00~02:00");
        assertEquals(7, value.schedules().size());
        assertTrue(value.schedules().getFirst().crossesMidnight());
    }

    @Test
    void ambiguousEqualTimesNeedReview() {
        var value = parser.parse("정시", "00:00~00:00");
        assertEquals("REVIEW_REQUIRED", value.status());
        assertNull(value.open24h());
    }

    @Test
    void closedWithActiveScheduleNeedsReview() {
        var value = parser.parse("미개방", "월~금 09:00~18:00");
        assertEquals("REVIEW_REQUIRED", value.status());
        assertNull(value.open24h());
    }

    @Test
    void plainClosedValueIsDefinitelyNotTwentyFourHours() {
        var value = parser.parse("미개방", null);
        assertEquals("CLOSED", value.openingPolicy());
        assertEquals(Boolean.FALSE, value.open24h());
        assertFalse(value.schedules().stream().findAny().isPresent());
    }
}
