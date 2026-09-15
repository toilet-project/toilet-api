CREATE TABLE admin_analytics_snapshot (
    report_key VARCHAR(64) NOT NULL,
    property_id_hash CHAR(64) NOT NULL,
    range_start DATE NULL,
    range_end DATE NULL,
    payload_json JSON NOT NULL,
    fetched_at DATETIME(6) NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    last_success_at DATETIME(6) NOT NULL,
    last_attempt_at DATETIME(6) NOT NULL,
    last_error_code VARCHAR(40) NULL,
    failure_count INT UNSIGNED NOT NULL DEFAULT 0,
    quota_json JSON NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (report_key),
    KEY idx_admin_analytics_snapshot_expiry (expires_at),
    KEY idx_admin_analytics_snapshot_success (last_success_at)
);
