CREATE TABLE toilet_display_group (
    group_id BIGINT NOT NULL AUTO_INCREMENT,
    display_name VARCHAR(100) NOT NULL,
    latitude DECIMAL(10,7) NOT NULL,
    longitude DECIMAL(10,7) NOT NULL,
    created_by_user_id BIGINT NULL,
    updated_by_user_id BIGINT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (group_id),
    KEY idx_toilet_display_group_coordinates (latitude, longitude),
    CONSTRAINT fk_toilet_display_group_creator FOREIGN KEY (created_by_user_id) REFERENCES app_user (user_id),
    CONSTRAINT fk_toilet_display_group_updater FOREIGN KEY (updated_by_user_id) REFERENCES app_user (user_id)
);

CREATE TABLE toilet_display_group_member (
    group_id BIGINT NOT NULL,
    toilet_id BIGINT NOT NULL,
    sort_order INT NOT NULL DEFAULT 0,
    PRIMARY KEY (group_id, toilet_id),
    UNIQUE KEY uk_toilet_display_group_member_toilet (toilet_id),
    CONSTRAINT fk_toilet_display_group_member_group FOREIGN KEY (group_id) REFERENCES toilet_display_group (group_id) ON DELETE CASCADE,
    CONSTRAINT fk_toilet_display_group_member_toilet FOREIGN KEY (toilet_id) REFERENCES toilet (toilet_id) ON DELETE CASCADE
);
