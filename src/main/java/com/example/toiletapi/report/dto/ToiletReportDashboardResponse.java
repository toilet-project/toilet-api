package com.example.toiletapi.report.dto;

import java.util.List;

/** 운영 홈에 필요한 제보 검토 건수와 오래된 제보 일곱 건만 반환한다. */
public record ToiletReportDashboardResponse(
        long pendingCount,
        long overdueCount,
        List<ToiletReportListItem> recentReports
) { }
