package com.example.toiletapi.report.dto;

import java.util.List;

/** 대시보드에 필요한 대기 건수와 최근 제보 다섯 건만 반환한다. */
public record ToiletReportDashboardResponse(long pendingCount, List<ToiletReportListItem> recentReports) { }
