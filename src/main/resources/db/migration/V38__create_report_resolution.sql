CREATE TABLE toilet_report_resolution (
    resolution_id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    report_id BIGINT NOT NULL,
    toilet_id BIGINT NOT NULL,
    actor_user_id BIGINT NOT NULL,
    action VARCHAR(32) NOT NULL,
    reason VARCHAR(500) NOT NULL,
    request_id CHAR(36) NOT NULL,
    request_hash CHAR(64) NOT NULL,
    before_json JSON NOT NULL,
    after_json JSON NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_report_resolution_request (report_id, request_id),
    KEY idx_report_resolution_facility (toilet_id, resolution_id),
    CONSTRAINT fk_resolution_report FOREIGN KEY (report_id) REFERENCES toilet_report(report_id),
    CONSTRAINT fk_resolution_toilet FOREIGN KEY (toilet_id) REFERENCES toilet(toilet_id),
    CONSTRAINT fk_resolution_actor FOREIGN KEY (actor_user_id) REFERENCES app_user(user_id)
);
