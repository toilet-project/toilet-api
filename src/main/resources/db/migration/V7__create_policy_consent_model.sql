ALTER TABLE app_user
    MODIFY status VARCHAR(20) NOT NULL DEFAULT 'PENDING_CONSENT';

CREATE TABLE policy_document (
    policy_document_id BIGINT NOT NULL AUTO_INCREMENT,
    policy_key VARCHAR(40) NOT NULL,
    version VARCHAR(20) NOT NULL,
    title VARCHAR(120) NOT NULL,
    required BOOLEAN NOT NULL DEFAULT FALSE,
    effective_at DATE NOT NULL,
    content_path VARCHAR(255) NOT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (policy_document_id),
    UNIQUE KEY uk_policy_document_key_version (policy_key, version),
    KEY idx_policy_document_active_required (active, required)
);

CREATE TABLE user_policy_consent (
    user_policy_consent_id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    policy_document_id BIGINT NOT NULL,
    consent_source VARCHAR(30) NOT NULL,
    agreed_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    withdrawn_at DATETIME NULL,
    PRIMARY KEY (user_policy_consent_id),
    UNIQUE KEY uk_user_policy_document (user_id, policy_document_id),
    KEY idx_user_policy_consent_user_active (user_id, withdrawn_at),
    CONSTRAINT fk_policy_consent_user FOREIGN KEY (user_id) REFERENCES app_user (user_id),
    CONSTRAINT fk_policy_consent_document FOREIGN KEY (policy_document_id) REFERENCES policy_document (policy_document_id)
);

INSERT INTO policy_document (policy_key, version, title, required, effective_at, content_path)
VALUES
    ('SERVICE_TERMS', '1.0', '급똥 서비스 이용약관', TRUE, '2026-09-01', '/policies/terms'),
    ('PRIVACY_COLLECTION', '1.0', '개인정보 수집·이용 동의', TRUE, '2026-09-01', '/policies/privacy#collection'),
    ('AGE_14_PLUS', '1.0', '만 14세 이상 확인', TRUE, '2026-09-01', '/policies/terms#age'),
    ('PRIVACY_POLICY', '1.0', '개인정보 처리방침', FALSE, '2026-09-01', '/policies/privacy'),
    ('LOCATION_NOTICE', '1.0', '위치정보 이용 안내', FALSE, '2026-09-01', '/policies/location');
