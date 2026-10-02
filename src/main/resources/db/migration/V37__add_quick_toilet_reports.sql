-- Additive only; legacy reports and member history are preserved.
ALTER TABLE toilet_report
    MODIFY COLUMN toilet_id BIGINT NULL,
    ADD COLUMN reporter_kind VARCHAR(10) NOT NULL DEFAULT 'MEMBER',
    ADD COLUMN proposed_name VARCHAR(100) NULL,
    ADD COLUMN observed_at DATETIME NULL,
    ADD COLUMN submission_key CHAR(64) NULL,
    ADD COLUMN submission_fingerprint CHAR(64) NULL,
    ADD UNIQUE KEY uk_report_submission_key (submission_key);
