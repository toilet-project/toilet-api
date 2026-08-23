package com.example.toiletapi.toilet.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.toiletapi.toilet.dto.ToiletMapResponse;
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
        when(toiletService.getToiletsInBounds(any(), any(), any(), any()))
                .thenReturn(List.of(new ToiletMapResponse(101L, "강남역 공중화장실", 37.4979, 127.0276)));

        mockMvc.perform(get("/api/v1/toilets")
                        .param("southLat", "37.4900")
                        .param("northLat", "37.5100")
                        .param("westLng", "127.0100")
                        .param("eastLng", "127.0300")
                        .param("zoom", "3"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(101))
                .andExpect(jsonPath("$[0].name").value("강남역 공중화장실"))
                .andExpect(jsonPath("$[0].latitude").value(37.4979))
                .andExpect(jsonPath("$[0].longitude").value(127.0276));

        verify(toiletService).getToiletsInBounds(any(), any(), any(), any());
    }
}
