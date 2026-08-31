package com.example.toiletapi.report.dto;

import com.example.toiletapi.toilet.model.Toilet;
import java.math.BigDecimal;

/** 관리자 검토에 필요한 제보 원문과 현재 등록 화장실 정보를 한 번에 반환한다. */
public record ToiletReportDetailResponse(ToiletReportResponse report, ToiletSnapshot toilet) {
    public record ToiletSnapshot(Long id, String name, BigDecimal latitude, BigDecimal longitude, String roadAddress,
                                 String jibunAddress, String openTime) {
        static ToiletSnapshot from(Toilet toilet) {
            return new ToiletSnapshot(toilet.getId(), toilet.getName(), toilet.getLatitude(), toilet.getLongitude(),
                    toilet.getRoadAddress(), toilet.getJibunAddress(), toilet.getOpenTime());
        }
    }

    public static ToiletReportDetailResponse from(ToiletReportResponse report, Toilet toilet) {
        return new ToiletReportDetailResponse(report, ToiletSnapshot.from(toilet));
    }
}
