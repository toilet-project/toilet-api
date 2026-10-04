package com.example.toiletapi.report.dto;

import java.util.List;
import java.util.Map;

/** Owner-only history. Counts cover the date range before the status filter. */
public record MyToiletReportPageResponse(List<ToiletReportResponse> items, int page, int size,
        long totalElements, boolean hasNext, Map<String, Long> statusCounts) { }
