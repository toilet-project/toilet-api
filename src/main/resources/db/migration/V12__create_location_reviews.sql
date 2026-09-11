-- Additive only. API review endpoints remain OFF until a separate rollout approval.
-- No device coordinates, measured-at, nickname snapshot, email, IP or user agent.
CREATE TABLE toilet_review (
    review_id BIGINT NOT NULL AUTO_INCREMENT,
    toilet_id BIGINT NOT NULL,
    author_user_id BIGINT NULL,
    author_detached BOOLEAN NOT NULL DEFAULT FALSE,
    satisfaction TINYINT NOT NULL,
    cleanliness TINYINT NOT NULL,
    paper_available BOOLEAN NOT NULL,
    wait_minutes TINYINT NOT NULL DEFAULT 0,
    comment VARCHAR(200) NOT NULL DEFAULT '',
    version BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (review_id),
    KEY idx_review_author_created (author_user_id, created_at, review_id),
    KEY idx_review_toilet_created (toilet_id, created_at, review_id),
    CONSTRAINT fk_review_toilet FOREIGN KEY (toilet_id) REFERENCES toilet (toilet_id),
    -- Final account erasure and verified old-backup replay use the same app_user deletion.
    -- SET NULL preserves review content without needing a new conditional erasure SQL path.
    CONSTRAINT fk_review_author FOREIGN KEY (author_user_id) REFERENCES app_user (user_id) ON DELETE SET NULL,
    CONSTRAINT ck_review_satisfaction CHECK (satisfaction BETWEEN 1 AND 5),
    CONSTRAINT ck_review_cleanliness CHECK (cleanliness BETWEEN 1 AND 5),
    CONSTRAINT ck_review_paper CHECK (paper_available BETWEEN 0 AND 1),
    CONSTRAINT ck_review_wait CHECK (wait_minutes BETWEEN 0 AND 60 AND MOD(wait_minutes, 10) = 0),
    CONSTRAINT ck_review_version CHECK (version >= 0),
    -- MySQL disallows a CHECK on an ON DELETE SET NULL column. Atomic service SQL clears both fields.
    CONSTRAINT ck_review_detached CHECK (author_detached BETWEEN 0 AND 1)
) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

-- No review ID / facility / location here: unlink cannot reset per-account abuse limits.
CREATE TABLE toilet_review_write_guard (
    user_id BIGINT NOT NULL,
    last_created_at DATETIME(6) NOT NULL,
    daily_date DATE NOT NULL,
    daily_count INT NOT NULL,
    PRIMARY KEY (user_id),
    CONSTRAINT fk_review_guard_user FOREIGN KEY (user_id) REFERENCES app_user (user_id) ON DELETE CASCADE,
    CONSTRAINT ck_review_guard_count CHECK (daily_count > 0)
);

-- Explicit unlink removes this per-review author/request association in the SAME transaction.
-- A separate per-toilet throttle retains no review ID or content. Unlink must not reset it.
CREATE TABLE toilet_review_toilet_guard (
    user_id BIGINT NOT NULL,
    toilet_id BIGINT NOT NULL,
    next_allowed_at DATETIME(6) NOT NULL,
    PRIMARY KEY (user_id, toilet_id),
    CONSTRAINT fk_review_toilet_guard_user FOREIGN KEY (user_id) REFERENCES app_user (user_id) ON DELETE CASCADE,
    CONSTRAINT fk_review_toilet_guard_toilet FOREIGN KEY (toilet_id) REFERENCES toilet (toilet_id) ON DELETE CASCADE
);

CREATE TABLE toilet_review_submission (
    user_id BIGINT NOT NULL,
    request_key CHAR(36) NOT NULL,
    content_hash CHAR(64) NOT NULL,
    review_id BIGINT NOT NULL,
    PRIMARY KEY (user_id, request_key),
    KEY idx_review_submission_review (review_id),
    CONSTRAINT fk_review_submission_user FOREIGN KEY (user_id) REFERENCES app_user (user_id) ON DELETE CASCADE,
    CONSTRAINT fk_review_submission_review FOREIGN KEY (review_id) REFERENCES toilet_review (review_id) ON DELETE CASCADE
);
