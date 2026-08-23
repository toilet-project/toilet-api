package com.example.toiletapi.toilet.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.toiletapi.toilet.dto.ToiletMapResponse;
import com.example.toiletapi.toilet.dto.ToiletMapSearchResponse;
import com.example.toiletapi.toilet.service.ToiletService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(ToiletController.class)
class ToiletControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ToiletService toiletService;

    @Test
    void shouldReturnToiletsWithinMapBounds() throws Exception {
        when(toiletService.getToiletsInBounds(any(), any(), any(), any(), any()))
                .thenReturn(ToiletMapSearchResponse.markers(
                        3,
                        List.of(new ToiletMapResponse(101L, "강남역 공중화장실", 37.4979, 127.0276))
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
                .andExpect(jsonPath("$.toilets[0].latitude").value(37.4979))
                .andExpect(jsonPath("$.toilets[0].longitude").value(127.0276))
                .andExpect(jsonPath("$.clusters").doesNotExist());

        verify(toiletService).getToiletsInBounds(any(), any(), any(), any(), any());
    }

    @Test
    void shouldReturnStandardErrorResponseForInvalidRequest() throws Exception {
        when(toiletService.getToiletsInBounds(any(), any(), any(), any(), any()))
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
}
