CREATE TABLE profile_photo_cdn_purge (
    photo_version CHAR(36) NOT NULL PRIMARY KEY,
    first_queued_at DATETIME(6) NOT NULL,
    next_attempt_at DATETIME(6) NOT NULL,
    attempts INT NOT NULL DEFAULT 0,
    last_error_code VARCHAR(40) NULL,
    KEY idx_profile_photo_cdn_purge_due (next_attempt_at, photo_version)
);

-- Only opaque image versions are retained. Member IDs, object keys and URLs are deliberately excluded.
