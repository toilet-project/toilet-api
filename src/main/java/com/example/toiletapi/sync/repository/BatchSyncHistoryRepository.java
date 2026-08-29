package com.example.toiletapi.sync.repository;

import com.example.toiletapi.sync.model.BatchSyncHistory;
import com.example.toiletapi.sync.model.BatchSyncStatus;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BatchSyncHistoryRepository extends JpaRepository<BatchSyncHistory, Long> {

    Optional<BatchSyncHistory> findFirstByStatusOrderByCompletedAtDesc(BatchSyncStatus status);
}
