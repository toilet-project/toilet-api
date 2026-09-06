-- Additive preparation. No existing withdrawn account is enrolled without consent.
ALTER TABLE app_user ADD COLUMN auth_version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE audit_log ADD COLUMN actor_erased BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE toilet_report MODIFY COLUMN reporter_user_id BIGINT NULL;
ALTER TABLE coordinate_revision MODIFY COLUMN applied_by_user_id BIGINT NULL;

CREATE TABLE account_withdrawal (
    user_id BIGINT NOT NULL PRIMARY KEY,
    withdrawal_key CHAR(36) NOT NULL,
    withdrawn_at DATETIME(6) NOT NULL,
    purge_after DATETIME(6) NOT NULL,
    recovery_allowed BOOLEAN NOT NULL,
    consent_version VARCHAR(30) NULL,
    recovery_display_name VARCHAR(100) NULL,
    attempts INT NOT NULL DEFAULT 0,
    next_attempt_at DATETIME(6) NOT NULL,
    last_failure_code VARCHAR(50) NULL,
    CONSTRAINT fk_withdrawal_user FOREIGN KEY (user_id) REFERENCES app_user(user_id),
    KEY idx_withdrawal_due (next_attempt_at, user_id)
);
