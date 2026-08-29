package com.example.toiletapi.sync.service;

import com.example.toiletapi.sync.dto.DataSyncStatusResponse;
import com.example.toiletapi.sync.model.BatchSyncStatus;
import com.example.toiletapi.sync.repository.BatchSyncHistoryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class DataSyncStatusService {

    private final BatchSyncHistoryRepository historyRepository;

    public DataSyncStatusResponse getLatestStatus() {
        return historyRepository.findFirstByStatusOrderByCompletedAtDesc(BatchSyncStatus.SUCCESS)
                .map(DataSyncStatusResponse::from)
                .orElseGet(DataSyncStatusResponse::unavailable);
    }
}
