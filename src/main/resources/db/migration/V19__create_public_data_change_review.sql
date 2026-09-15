ALTER TABLE batch_sync_history
    ADD COLUMN execution_key CHAR(36) NULL AFTER id,
    ADD UNIQUE KEY uk_batch_sync_execution_key (execution_key);

CREATE TABLE public_data_change_review (
    review_id BIGINT NOT NULL AUTO_INCREMENT,
    toilet_id BIGINT NOT NULL,
    active_toilet_id BIGINT NULL COMMENT 'PENDING 후보에만 toilet_id를 저장해 시설별 활성 후보를 하나로 제한',
    baseline_latitude DECIMAL(10,7) NULL,
    baseline_longitude DECIMAL(10,7) NULL,
    baseline_road_address VARCHAR(255) NULL,
    baseline_jibun_address VARCHAR(255) NULL,
    proposal_latitude DECIMAL(10,7) NULL,
    proposal_longitude DECIMAL(10,7) NULL,
    proposal_road_address VARCHAR(255) NULL,
    proposal_jibun_address VARCHAR(255) NULL,
    changed_fields VARCHAR(100) NOT NULL,
    baseline_hash CHAR(64) NOT NULL,
    proposal_hash CHAR(64) NOT NULL,
    provider_updated_at DATETIME NULL,
    first_received_at DATETIME NOT NULL,
    last_received_at DATETIME NOT NULL,
    receipt_count INT UNSIGNED NOT NULL DEFAULT 1,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    status_reason VARCHAR(500) NULL,
    version BIGINT UNSIGNED NOT NULL DEFAULT 1,
    superseded_by_review_id BIGINT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (review_id),
    UNIQUE KEY uk_public_data_change_active_toilet (active_toilet_id),
    KEY idx_public_data_change_status_received (status, last_received_at, review_id),
    KEY idx_public_data_change_toilet_compare (toilet_id, baseline_hash, proposal_hash, review_id),
    CONSTRAINT fk_public_data_change_toilet FOREIGN KEY (toilet_id) REFERENCES toilet (toilet_id),
    CONSTRAINT fk_public_data_change_superseded_by FOREIGN KEY (superseded_by_review_id)
        REFERENCES public_data_change_review (review_id)
);

CREATE TABLE public_data_confirmed_receipt (
    receipt_id BIGINT NOT NULL AUTO_INCREMENT,
    execution_key CHAR(36) NOT NULL,
    toilet_id BIGINT NOT NULL,
    review_id BIGINT NULL,
    received_at DATETIME NOT NULL,
    input_hash CHAR(64) NOT NULL,
    protected_before_hash CHAR(64) NOT NULL,
    protected_after_hash CHAR(64) NOT NULL,
    result VARCHAR(30) NOT NULL,
    PRIMARY KEY (receipt_id),
    UNIQUE KEY uk_public_data_receipt_execution_toilet (execution_key, toilet_id),
    KEY idx_public_data_receipt_review_received (review_id, received_at),
    KEY idx_public_data_receipt_toilet_received (toilet_id, received_at),
    CONSTRAINT fk_public_data_receipt_toilet FOREIGN KEY (toilet_id) REFERENCES toilet (toilet_id),
    CONSTRAINT fk_public_data_receipt_review FOREIGN KEY (review_id)
        REFERENCES public_data_change_review (review_id)
);

CREATE TABLE public_data_change_decision (
    decision_id BIGINT NOT NULL AUTO_INCREMENT,
    review_id BIGINT NOT NULL,
    action VARCHAR(20) NOT NULL,
    note VARCHAR(500) NULL,
    decided_by_user_id BIGINT NOT NULL,
    decided_at DATETIME NOT NULL,
    candidate_version BIGINT UNSIGNED NOT NULL,
    PRIMARY KEY (decision_id),
    KEY idx_public_data_decision_review (review_id, decided_at, decision_id),
    CONSTRAINT fk_public_data_decision_review FOREIGN KEY (review_id)
        REFERENCES public_data_change_review (review_id),
    CONSTRAINT fk_public_data_decision_user FOREIGN KEY (decided_by_user_id)
        REFERENCES app_user (user_id)
);
