package com.example.toiletapi.report.dto;
import java.math.BigDecimal;
/** roadAddress는 레거시 표시용 요청값이며, 저장할 주소는 서버가 제보 좌표로 조회합니다. */
public record CreateToiletReportRequest(Long toiletId, String reportType, BigDecimal latitude, BigDecimal longitude, String roadAddress, String openTime, String reason) { }
