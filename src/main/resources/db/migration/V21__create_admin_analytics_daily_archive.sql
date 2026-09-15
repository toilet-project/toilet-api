CREATE TABLE admin_analytics_daily_summary (
    property_id_hash CHAR(64) NOT NULL,
    report_date DATE NOT NULL,
    report_timezone VARCHAR(64) NOT NULL,
    active_users BIGINT UNSIGNED NOT NULL DEFAULT 0,
    total_users BIGINT UNSIGNED NOT NULL DEFAULT 0,
    new_users BIGINT UNSIGNED NOT NULL DEFAULT 0,
    sessions BIGINT UNSIGNED NOT NULL DEFAULT 0,
    engaged_sessions BIGINT UNSIGNED NOT NULL DEFAULT 0,
    views BIGINT UNSIGNED NOT NULL DEFAULT 0,
    event_count BIGINT UNSIGNED NOT NULL DEFAULT 0,
    key_events DECIMAL(20,6) NOT NULL DEFAULT 0,
    engagement_seconds DECIMAL(20,3) NOT NULL DEFAULT 0,
    data_status VARCHAR(16) NOT NULL DEFAULT 'PROVISIONAL',
    collected_at DATETIME(6) NOT NULL,
    finalized_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (property_id_hash, report_date),
    KEY idx_admin_analytics_daily_summary_date (report_date),
    KEY idx_admin_analytics_daily_summary_status (data_status, report_date)
);

CREATE TABLE admin_analytics_daily_breakdown (
    property_id_hash CHAR(64) NOT NULL,
    report_date DATE NOT NULL,
    breakdown_type VARCHAR(32) NOT NULL,
    dimension_hash CHAR(64) NOT NULL,
    dimension_value TEXT NOT NULL,
    dimension_label VARCHAR(512) NOT NULL,
    dimension_detail VARCHAR(512) NULL,
    active_users BIGINT UNSIGNED NOT NULL DEFAULT 0,
    total_users BIGINT UNSIGNED NOT NULL DEFAULT 0,
    new_users BIGINT UNSIGNED NOT NULL DEFAULT 0,
    sessions BIGINT UNSIGNED NOT NULL DEFAULT 0,
    engaged_sessions BIGINT UNSIGNED NOT NULL DEFAULT 0,
    views BIGINT UNSIGNED NOT NULL DEFAULT 0,
    event_count BIGINT UNSIGNED NOT NULL DEFAULT 0,
    key_events DECIMAL(20,6) NOT NULL DEFAULT 0,
    engagement_seconds DECIMAL(20,3) NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (property_id_hash, report_date, breakdown_type, dimension_hash),
    KEY idx_admin_analytics_daily_breakdown_lookup (report_date, breakdown_type),
    CONSTRAINT fk_admin_analytics_daily_breakdown_summary
        FOREIGN KEY (property_id_hash, report_date)
        REFERENCES admin_analytics_daily_summary (property_id_hash, report_date)
        ON DELETE CASCADE
);

CREATE TABLE admin_analytics_collection_run (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    property_id_hash CHAR(64) NOT NULL,
    target_date DATE NOT NULL,
    range_start DATE NOT NULL,
    range_end DATE NOT NULL,
    run_type VARCHAR(20) NOT NULL,
    status VARCHAR(20) NOT NULL,
    started_at DATETIME(6) NOT NULL,
    finished_at DATETIME(6) NULL,
    api_request_count INT UNSIGNED NOT NULL DEFAULT 0,
    stored_row_count INT UNSIGNED NOT NULL DEFAULT 0,
    retry_count INT UNSIGNED NOT NULL DEFAULT 0,
    error_code VARCHAR(64) NULL,
    error_message VARCHAR(500) NULL,
    quota_json JSON NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    KEY idx_admin_analytics_collection_run_target (property_id_hash, target_date, status),
    KEY idx_admin_analytics_collection_run_started (started_at)
);
