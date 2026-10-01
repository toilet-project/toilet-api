-- Independent from public toilet data and its revisions/cache invalidation.
CREATE TABLE toilet_view_stats (
    toilet_id BIGINT NOT NULL PRIMARY KEY,
    total_views BIGINT NOT NULL DEFAULT 0,
    first_view_at DATETIME(6) NOT NULL,
    last_view_at DATETIME(6) NOT NULL,
    CONSTRAINT fk_view_stats_toilet FOREIGN KEY (toilet_id) REFERENCES toilet(toilet_id) ON DELETE CASCADE,
    CONSTRAINT ck_view_total CHECK (total_views >= 0)
);
CREATE TABLE toilet_view_daily (
    view_date DATE NOT NULL,
    toilet_id BIGINT NOT NULL,
    views BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (view_date, toilet_id),
    KEY idx_view_daily_toilet (toilet_id, view_date),
    CONSTRAINT fk_view_daily_toilet FOREIGN KEY (toilet_id) REFERENCES toilet(toilet_id) ON DELETE CASCADE,
    CONSTRAINT ck_view_daily CHECK (views >= 0)
);
-- HMACs only: no raw session, account ID, IP, user agent, location or URL.
CREATE TABLE toilet_view_guard (
    session_hash CHAR(64) NOT NULL,
    toilet_id BIGINT NOT NULL,
    last_counted_at DATETIME(6) NULL,
    expires_at DATETIME(6) NOT NULL,
    PRIMARY KEY (session_hash, toilet_id),
    KEY idx_view_guard_expiry (expires_at),
    CONSTRAINT fk_view_guard_toilet FOREIGN KEY (toilet_id) REFERENCES toilet(toilet_id) ON DELETE CASCADE
);
CREATE TABLE toilet_view_receipt (
    event_hash CHAR(64) NOT NULL PRIMARY KEY,
    toilet_id BIGINT NOT NULL,
    processed BOOLEAN NOT NULL DEFAULT FALSE,
    expires_at DATETIME(6) NOT NULL,
    KEY idx_view_receipt_expiry (expires_at),
    CONSTRAINT fk_view_receipt_toilet FOREIGN KEY (toilet_id) REFERENCES toilet(toilet_id) ON DELETE CASCADE
);
CREATE TABLE toilet_like (
    user_id BIGINT NOT NULL,
    toilet_id BIGINT NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (user_id, toilet_id),
    KEY idx_toilet_like_facility (toilet_id),
    -- Final erasure and verified backup replay delete app_user through the same path.
    CONSTRAINT fk_like_user FOREIGN KEY (user_id) REFERENCES app_user(user_id) ON DELETE CASCADE,
    CONSTRAINT fk_like_toilet FOREIGN KEY (toilet_id) REFERENCES toilet(toilet_id) ON DELETE CASCADE
);
