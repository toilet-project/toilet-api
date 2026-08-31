package com.example.toiletapi.report.dto;

import com.example.toiletapi.report.model.ToiletReport;
import java.time.LocalDateTime;

/** 관리자 목록과 대시보드에서 쓰는 최소 제보 정보다. */
public record ToiletReportListItem(Long id, Long toiletId, String toiletName, String reportType, String status, LocalDateTime createdAt) {
    public static ToiletReportListItem from(ToiletReport report, String toiletName) {
        return new ToiletReportListItem(report.getId(), report.getToiletId(), toiletName, report.getReportType(), report.getStatus().name(), report.getCreatedAt());
    }
}
