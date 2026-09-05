-- Assessment evidence is append-only, independent of the disposable current region projection.
-- Deliberately no cascading FK: deleting a toilet must not silently erase investigation evidence.
CREATE TABLE toilet_region_assessment_history (
    assessment_id BIGINT NOT NULL AUTO_INCREMENT,
    toilet_id BIGINT NOT NULL,
    source_hash CHAR(64) NOT NULL,
    algorithm_version VARCHAR(40) NOT NULL,
    status VARCHAR(30) NOT NULL,
    reason VARCHAR(100) NOT NULL,
    result_json JSON NOT NULL,
    checked_epoch_millis BIGINT NOT NULL,
    checked_at DATETIME(3) NOT NULL,
    PRIMARY KEY (assessment_id),
    UNIQUE KEY uk_region_assessment_replay (toilet_id, source_hash, algorithm_version, checked_epoch_millis),
    KEY idx_region_assessment_toilet (toilet_id, checked_at, assessment_id)
);

-- Future admin queue: WHERE status=? ORDER BY checked_at,toilet_id LIMIT ?.
CREATE INDEX idx_toilet_region_review ON toilet_region (status, checked_at, toilet_id);
