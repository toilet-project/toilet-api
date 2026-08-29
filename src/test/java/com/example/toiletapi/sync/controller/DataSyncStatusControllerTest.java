package com.example.toiletapi.sync.controller;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.toiletapi.sync.dto.DataSyncStatusResponse;
import com.example.toiletapi.sync.service.DataSyncStatusService;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(DataSyncStatusController.class)
class DataSyncStatusControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private DataSyncStatusService dataSyncStatusService;

    @Test
    void returnsOnlyTheLatestPublicSyncStatus() throws Exception {
        when(dataSyncStatusService.getLatestStatus()).thenReturn(new DataSyncStatusResponse(
                true,
                LocalDateTime.of(2026, 8, 29, 2, 1),
                LocalDateTime.of(2026, 8, 29, 0, 0),
                12_345L
        ));

        mockMvc.perform(get("/api/v1/data-status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.available").value(true))
                .andExpect(jsonPath("$.totalToiletCount").value(12345))
                .andExpect(jsonPath("$.lastSyncedAt").value("2026-08-29T02:01:00"));

        verify(dataSyncStatusService).getLatestStatus();
    }
}
