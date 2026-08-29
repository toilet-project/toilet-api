package com.example.toiletapi.sync.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "batch_sync_history")
public class BatchSyncHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "status", length = 20, nullable = false)
    @Enumerated(EnumType.STRING)
    private BatchSyncStatus status;

    @Column(name = "range_to", nullable = false)
    private LocalDateTime rangeTo;

    @Column(name = "total_toilet_count")
    private Long totalToiletCount;

    @Column(name = "completed_at", nullable = false)
    private LocalDateTime completedAt;
}
