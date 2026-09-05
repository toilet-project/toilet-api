package com.example.toiletapi.report.dto;

import java.math.BigDecimal;

/**
 * 관리자 검토 결과입니다. 위치 제보는 지도에서 보정한 좌표를 전달할 수 있습니다.
 * confirmedRoadAddress는 이전 클라이언트 호환 필드이며 저장에는 사용하지 않습니다.
 * 서버가 최종 좌표를 역지오코딩하여 도로명/지번주소를 각각 확정합니다.
 * 제보 원문은 수정하지 않고, 실제 반영 좌표는 좌표 변경 이력에 별도로 남깁니다.
 */
public record ReviewToiletReportRequest(
        String note,
        BigDecimal confirmedLatitude,
        BigDecimal confirmedLongitude,
        String confirmedRoadAddress
) { }
