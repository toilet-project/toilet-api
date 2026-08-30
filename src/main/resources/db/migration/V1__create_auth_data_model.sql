CREATE TABLE app_user (
    user_id BIGINT NOT NULL AUTO_INCREMENT,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    display_name VARCHAR(100) NULL,
    email VARCHAR(255) NULL,
    email_verified BOOLEAN NOT NULL DEFAULT FALSE,
    last_login_at DATETIME NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (user_id),
    KEY idx_app_user_status (status)
);

CREATE TABLE user_social_account (
    social_account_id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    provider VARCHAR(20) NOT NULL,
    provider_subject_hash CHAR(64) NOT NULL,
    provider_email VARCHAR(255) NULL,
    linked_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_login_at DATETIME NULL,
    PRIMARY KEY (social_account_id),
    UNIQUE KEY uk_social_provider_subject_hash (provider, provider_subject_hash),
    UNIQUE KEY uk_social_user_provider (user_id, provider),
    CONSTRAINT fk_social_user FOREIGN KEY (user_id) REFERENCES app_user (user_id)
);

CREATE TABLE user_role (
    user_id BIGINT NOT NULL,
    role VARCHAR(30) NOT NULL,
    granted_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    granted_by_user_id BIGINT NULL,
    PRIMARY KEY (user_id, role),
    CONSTRAINT fk_role_user FOREIGN KEY (user_id) REFERENCES app_user (user_id)
);

CREATE TABLE audit_log (
    audit_log_id BIGINT NOT NULL AUTO_INCREMENT,
    actor_user_id BIGINT NULL,
    action VARCHAR(100) NOT NULL,
    target_type VARCHAR(50) NOT NULL,
    target_id BIGINT NULL,
    detail_json JSON NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (audit_log_id),
    KEY idx_audit_actor_created (actor_user_id, created_at),
    KEY idx_audit_target (target_type, target_id)
);
