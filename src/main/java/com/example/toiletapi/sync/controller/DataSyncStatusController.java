package com.example.toiletapi.sync.controller;

import com.example.toiletapi.sync.dto.DataSyncStatusResponse;
import com.example.toiletapi.sync.service.DataSyncStatusService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/data-status")
public class DataSyncStatusController {

    private final DataSyncStatusService dataSyncStatusService;

    @GetMapping
    public DataSyncStatusResponse getLatestStatus() {
        return dataSyncStatusService.getLatestStatus();
    }
}
