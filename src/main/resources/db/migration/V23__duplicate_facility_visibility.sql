ALTER TABLE toilet
    ADD COLUMN visibility_status VARCHAR(24) NOT NULL DEFAULT 'VISIBLE',
    ADD COLUMN representative_toilet_id BIGINT NULL,
    ADD COLUMN visibility_version BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN hidden_event_id BIGINT NULL,
    ADD KEY idx_toilet_visibility_name (visibility_status, name),
    ADD KEY idx_toilet_representative (representative_toilet_id),
    ADD CONSTRAINT fk_toilet_representative FOREIGN KEY (representative_toilet_id) REFERENCES toilet(toilet_id);

CREATE TABLE toilet_visibility_event (
    event_id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    toilet_id BIGINT NOT NULL,
    representative_toilet_id BIGINT NULL,
    action VARCHAR(24) NOT NULL,
    reason VARCHAR(500) NOT NULL,
    actor_user_id BIGINT NOT NULL,
    occurred_at DATETIME NOT NULL,
    previous_version BIGINT NOT NULL,
    snapshot_name VARCHAR(100) NULL,
    snapshot_road_address VARCHAR(255) NULL,
    snapshot_jibun_address VARCHAR(255) NULL,
    snapshot_latitude DECIMAL(10,7) NULL,
    snapshot_longitude DECIMAL(10,7) NULL,
    KEY idx_visibility_event_toilet (toilet_id, event_id),
    CONSTRAINT fk_visibility_event_toilet FOREIGN KEY (toilet_id) REFERENCES toilet(toilet_id),
    CONSTRAINT fk_visibility_event_representative FOREIGN KEY (representative_toilet_id) REFERENCES toilet(toilet_id),
    CONSTRAINT fk_visibility_event_actor FOREIGN KEY (actor_user_id) REFERENCES app_user(user_id)
);

ALTER TABLE public_data_change_review
    ADD COLUMN baseline_name VARCHAR(100) NULL,
    ADD COLUMN proposal_name VARCHAR(100) NULL,
    ADD COLUMN hidden_event_id BIGINT NULL,
    ADD CONSTRAINT fk_change_hidden_event FOREIGN KEY (hidden_event_id) REFERENCES toilet_visibility_event(event_id);
