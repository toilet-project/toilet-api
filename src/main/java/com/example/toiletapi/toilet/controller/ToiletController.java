package com.example.toiletapi.toilet.controller;

import com.example.toiletapi.global.response.ApiResponse;
import com.example.toiletapi.toilet.dto.ToiletMapSearchResponse;
import com.example.toiletapi.toilet.service.ToiletService;
import java.math.BigDecimal;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 화장실 REST API를 제공합니다.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/toilets")
public class ToiletController {

    private final ToiletService toiletService;

    /**
     * 현재 지도 화면 영역 안의 화장실 기본 정보를 반환합니다.
     *
     * @param southLat 최남단 위도
     * @param northLat 최북단 위도
     * @param westLng 최서단 경도
     * @param eastLng 최동단 경도
     * @param zoom 카카오맵 줌 레벨
     * @return 지도 마커용 화장실 목록
     */
    @GetMapping
    public ApiResponse<ToiletMapSearchResponse> getToiletsInBounds(
            @RequestParam BigDecimal southLat,
            @RequestParam BigDecimal northLat,
            @RequestParam BigDecimal westLng,
            @RequestParam BigDecimal eastLng,
            @RequestParam(required = false) Integer zoom
    ) {
        return ApiResponse.of(toiletService.getToiletsInBounds(southLat, northLat, westLng, eastLng, zoom));
    }
}
