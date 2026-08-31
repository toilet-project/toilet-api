ALTER TABLE coordinate_revision
    MODIFY COLUMN report_id BIGINT NULL;

CREATE TABLE coordinate_quality_review (
    group_key CHAR(64) NOT NULL,
    latitude DECIMAL(10,7) NOT NULL,
    longitude DECIMAL(10,7) NOT NULL,
    status VARCHAR(30) NOT NULL DEFAULT 'PENDING',
    review_note VARCHAR(500) NULL,
    reviewed_by_user_id BIGINT NULL,
    reviewed_at DATETIME NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (group_key),
    KEY idx_coordinate_quality_status_updated (status, updated_at),
    CONSTRAINT fk_coordinate_quality_reviewer FOREIGN KEY (reviewed_by_user_id) REFERENCES app_user (user_id)
);
