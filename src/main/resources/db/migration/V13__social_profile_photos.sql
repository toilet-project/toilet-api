-- Only derived images are referenced here. No provider URL or original image is persisted.
CREATE TABLE profile_photo_object (
    object_key VARCHAR(100) NOT NULL PRIMARY KEY,
    created_at DATETIME(6) NOT NULL,
    KEY idx_photo_object_created (created_at)
);
CREATE TABLE profile_photo (
    user_id BIGINT NOT NULL PRIMARY KEY,
    use_social BOOLEAN NOT NULL DEFAULT FALSE,
    is_public BOOLEAN NOT NULL DEFAULT FALSE,
    generation BIGINT NOT NULL DEFAULT 0,
    object_key VARCHAR(100) NULL,
    content_hash CHAR(64) NULL,
    source_hash CHAR(64) NULL,
    checked_at DATETIME(6) NULL,
    updated_at DATETIME(6) NOT NULL,
    CONSTRAINT fk_photo_user FOREIGN KEY (user_id) REFERENCES app_user(user_id) ON DELETE CASCADE,
    CONSTRAINT fk_photo_object FOREIGN KEY (object_key) REFERENCES profile_photo_object(object_key),
    KEY idx_photo_object (object_key)
);
-- Object records deliberately survive account deletion: they allow retryable orphan cleanup
-- without retaining an owner ID. Existing API/batch/restore account deletion cascades above.
