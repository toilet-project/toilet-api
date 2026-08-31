package com.example.toiletapi.report.dto;

import java.math.BigDecimal;

/**
 * 관리자 검토 결과입니다. 위치 제보는 지도에서 보정한 좌표와 도로명 주소를 함께 전달할 수 있습니다.
 * 제보 원문은 수정하지 않고, 실제 반영 좌표는 좌표 변경 이력에 별도로 남깁니다.
 */
public record ReviewToiletReportRequest(
        String note,
        BigDecimal confirmedLatitude,
        BigDecimal confirmedLongitude,
        String confirmedRoadAddress
) { }
