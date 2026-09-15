DROP TABLE IF EXISTS admin_analytics_snapshot;

CREATE TABLE service_analytics_event (
    event_id BIGINT NOT NULL AUTO_INCREMENT,
    occurred_at DATETIME(6) NOT NULL,
    occurred_date DATE NOT NULL,
    event_name VARCHAR(40) NOT NULL,
    page_key VARCHAR(120) NOT NULL,
    channel_key VARCHAR(40) NOT NULL,
    source_key VARCHAR(80) NOT NULL,
    device_type VARCHAR(20) NOT NULL,
    os_family VARCHAR(30) NOT NULL,
    browser_family VARCHAR(30) NOT NULL,
    country_code CHAR(2) NOT NULL,
    city_name VARCHAR(80) NOT NULL,
    visitor_hash BINARY(32) NOT NULL,
    session_hash BINARY(32) NOT NULL,
    engagement_seconds SMALLINT UNSIGNED NOT NULL DEFAULT 0,
    result_count_bucket VARCHAR(16) NOT NULL DEFAULT '',
    event_detail VARCHAR(40) NOT NULL DEFAULT '',
    success_status BOOLEAN NULL,
    new_visitor BOOLEAN NOT NULL DEFAULT FALSE,
    key_event BOOLEAN NOT NULL DEFAULT FALSE,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (event_id),
    KEY idx_service_analytics_event_time (occurred_at),
    KEY idx_service_analytics_event_date_name (occurred_date, event_name),
    KEY idx_service_analytics_event_date_visitor (occurred_date, visitor_hash),
    KEY idx_service_analytics_event_date_session (occurred_date, session_hash),
    KEY idx_service_analytics_event_date_page (occurred_date, page_key)
);

CREATE TABLE service_analytics_daily_summary (
    analytics_date DATE NOT NULL,
    active_users BIGINT NOT NULL DEFAULT 0,
    new_users BIGINT NOT NULL DEFAULT 0,
    sessions BIGINT NOT NULL DEFAULT 0,
    views BIGINT NOT NULL DEFAULT 0,
    engaged_sessions BIGINT NOT NULL DEFAULT 0,
    key_events BIGINT NOT NULL DEFAULT 0,
    event_count BIGINT NOT NULL DEFAULT 0,
    total_engagement_seconds BIGINT NOT NULL DEFAULT 0,
    calculated_at DATETIME(6) NOT NULL,
    finalized BOOLEAN NOT NULL DEFAULT FALSE,
    PRIMARY KEY (analytics_date),
    KEY idx_service_analytics_daily_calculated (calculated_at)
);

CREATE TABLE service_analytics_daily_dimension (
    analytics_date DATE NOT NULL,
    dimension_type VARCHAR(24) NOT NULL,
    dimension_key VARCHAR(120) NOT NULL,
    dimension_label VARCHAR(160) NOT NULL,
    active_users BIGINT NOT NULL DEFAULT 0,
    views BIGINT NOT NULL DEFAULT 0,
    sessions BIGINT NOT NULL DEFAULT 0,
    event_count BIGINT NOT NULL DEFAULT 0,
    key_events BIGINT NOT NULL DEFAULT 0,
    engagement_seconds BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (analytics_date, dimension_type, dimension_key),
    KEY idx_service_analytics_dimension_lookup (dimension_type, analytics_date)
);

CREATE TABLE service_analytics_batch_run (
    run_id BIGINT NOT NULL AUTO_INCREMENT,
    started_at DATETIME(6) NOT NULL,
    finished_at DATETIME(6) NULL,
    range_start DATE NOT NULL,
    range_end DATE NOT NULL,
    status VARCHAR(16) NOT NULL,
    processed_dates INT NOT NULL DEFAULT 0,
    error_code VARCHAR(80) NULL,
    PRIMARY KEY (run_id),
    KEY idx_service_analytics_batch_started (started_at),
    KEY idx_service_analytics_batch_status (status, started_at)
);
