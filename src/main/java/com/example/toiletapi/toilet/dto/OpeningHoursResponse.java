package com.example.toiletapi.toilet.dto;

import com.example.toiletapi.toilet.openinghours.OpeningHoursModels;
import java.time.format.DateTimeFormatter;
import java.util.List;

public record OpeningHoursResponse(
        String openingPolicy,
        Boolean open24h,
        String status,
        Double confidence,
        String parserVersion,
        String holidayPolicy,
        boolean manualOverride,
        boolean sourceChanged,
        List<Schedule> schedules
) {
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");

    public static OpeningHoursResponse from(OpeningHoursModels.View value) {
        return new OpeningHoursResponse(value.openingPolicy(), value.open24h(), value.status(),
                value.confidence(), value.parserVersion(), value.holidayPolicy(), value.manualOverride(),
                value.sourceChanged(), value.schedules().stream().map(Schedule::from).toList());
    }

    public record Schedule(int dayOfWeek, int slotIndex, String startTime, String endTime,
                           boolean crossesMidnight, boolean closed) {
        static Schedule from(OpeningHoursModels.Slot value) {
            return new Schedule(value.dayOfWeek(), value.slotIndex(),
                    value.startTime() == null ? null : TIME.format(value.startTime()),
                    value.endTime() == null ? null : TIME.format(value.endTime()),
                    value.crossesMidnight(), value.closed());
        }
    }
}
