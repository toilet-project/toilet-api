package com.example.toiletapi.quality.dto;

import java.util.List;

public record DuplicateCoordinateGroupDetailResponse(
        DuplicateCoordinateGroupResponse group,
        List<DuplicateCoordinateToiletResponse> toilets,
        List<CoordinateQualityReportResponse> pendingReports,
        List<CoordinateQualityRevisionResponse> revisions
) {
}
