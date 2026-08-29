package com.example.toiletapi.sync.dto;

import com.example.toiletapi.sync.model.BatchSyncHistory;
import java.time.LocalDateTime;

/** 사용자에게 공개할 수 있는 최소한의 최신 데이터 상태입니다. */
public record DataSyncStatusResponse(
        boolean available,
        LocalDateTime lastSyncedAt,
        LocalDateTime dataThrough,
        Long totalToiletCount
) {
    public static DataSyncStatusResponse from(BatchSyncHistory history) {
        return new DataSyncStatusResponse(true, history.getCompletedAt(), history.getRangeTo(), history.getTotalToiletCount());
    }

    public static DataSyncStatusResponse unavailable() {
        return new DataSyncStatusResponse(false, null, null, null);
    }
}
