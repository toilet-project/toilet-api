package com.example.toiletapi.report.dto;

import java.util.List;
import org.springframework.data.domain.Page;

/** 검색·페이지네이션 정보를 함께 반환하는 관리자 제보 목록 응답이다. */
public record ToiletReportPageResponse(List<ToiletReportListItem> items, int page, int size, long totalElements, int totalPages) {
    public static ToiletReportPageResponse from(Page<ToiletReportListItem> page) {
        return new ToiletReportPageResponse(page.getContent(), page.getNumber(), page.getSize(), page.getTotalElements(), page.getTotalPages());
    }
}
