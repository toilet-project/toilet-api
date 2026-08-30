package com.example.toiletapi.toilet.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.toiletapi.global.config.CorsConfig;
import com.example.toiletapi.auth.config.SecurityConfig;
import com.example.toiletapi.global.exception.ToiletNotFoundException;
import com.example.toiletapi.toilet.dto.ToiletDetailResponse;
import com.example.toiletapi.toilet.dto.ToiletMapResponse;
import com.example.toiletapi.toilet.dto.ToiletMapSearchResponse;
import com.example.toiletapi.toilet.service.ToiletService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(ToiletController.class)
@Import({CorsConfig.class, SecurityConfig.class})
class ToiletControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ToiletService toiletService;

    @Test
    void shouldReturnToiletsWithinMapBounds() throws Exception {
        when(toiletService.getToiletsInBounds(any(), any(), any(), any(), any(), anyBoolean()))
                .thenReturn(ToiletMapSearchResponse.markers(
                        3,
                        List.of(new ToiletMapResponse(101L, "강남역 공중화장실", "공중화장실", 37.4979, 127.0276))
                ));

        mockMvc.perform(get("/api/v1/toilets")
                        .param("southLat", "37.4900")
                        .param("northLat", "37.5100")
                        .param("westLng", "127.0100")
                        .param("eastLng", "127.0300")
                        .param("zoom", "3"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.meta.map_level").value(3))
                .andExpect(jsonPath("$.meta.display_type").value("MARKER"))
                .andExpect(jsonPath("$.meta.total_count").value(1))
                .andExpect(jsonPath("$.meta.result_count").value(1))
                .andExpect(jsonPath("$.toilets[0].id").value(101))
                .andExpect(jsonPath("$.toilets[0].name").value("강남역 공중화장실"))
                .andExpect(jsonPath("$.toilets[0].toiletType").value("공중화장실"))
                .andExpect(jsonPath("$.toilets[0].latitude").value(37.4979))
                .andExpect(jsonPath("$.toilets[0].longitude").value(127.0276))
                .andExpect(jsonPath("$.clusters").doesNotExist());

        verify(toiletService).getToiletsInBounds(any(), any(), any(), any(), any(), anyBoolean());
    }

    @Test
    void shouldReturnStandardErrorResponseForInvalidRequest() throws Exception {
        when(toiletService.getToiletsInBounds(any(), any(), any(), any(), any(), anyBoolean()))
                .thenThrow(new IllegalArgumentException("카카오맵 레벨은 1부터 14 사이여야 합니다."));

        mockMvc.perform(get("/api/v1/toilets")
                        .param("southLat", "37.4900")
                        .param("northLat", "37.5100")
                        .param("westLng", "127.0100")
                        .param("eastLng", "127.0300")
                        .param("zoom", "15"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.error.message").value("카카오맵 레벨은 1부터 14 사이여야 합니다."));
    }

    @Test
    void shouldAllowConfiguredWebOrigin() throws Exception {
        mockMvc.perform(options("/api/v1/toilets")
                        .header("Origin", "https://geupddong.com")
                        .header("Access-Control-Request-Method", "GET"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "https://geupddong.com"));
    }

    @Test
    void shouldReturnToiletDetail() throws Exception {
        when(toiletService.getToiletDetail(101L)).thenReturn(detailResponse());

        mockMvc.perform(get("/api/v1/toilets/101"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(101))
                .andExpect(jsonPath("$.name").value("강남역 공중화장실"))
                .andExpect(jsonPath("$.maleToiletCount").value(3))
                .andExpect(jsonPath("$.femaleToiletCount").value(6))
                .andExpect(jsonPath("$.hasEmergencyBell").value("Y"));

        verify(toiletService).getToiletDetail(101L);
    }

    @Test
    void shouldReturnNotFoundForMissingToilet() throws Exception {
        when(toiletService.getToiletDetail(999L)).thenThrow(new ToiletNotFoundException(999L));

        mockMvc.perform(get("/api/v1/toilets/999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("TOILET_NOT_FOUND"));
    }

    @Test
    void shouldRejectUnauthenticatedAdminRequest() throws Exception {
        mockMvc.perform(get("/api/admin/monitoring"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("AUTHENTICATION_REQUIRED"));
    }

    private ToiletDetailResponse detailResponse() {
        return new ToiletDetailResponse(
                101L, "강남역 공중화장실", "공중화장실", "서울특별시 강남구 강남대로 396", "서울특별시 강남구 역삼동 858",
                3, 4, 1, 1, 0, 1, 6, 1, 1,
                "강남구청", "02-3423-5900", "24시간", "연중무휴", "2018-05",
                "Y", "화장실 내부", "Y", "Y", "여자화장실 입구", "2024-01-01", "PUBLIC_DATA"
        );
    }
}
