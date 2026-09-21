CREATE TABLE toilet_display_group_translation (
    group_id BIGINT NOT NULL,
    locale VARCHAR(10) NOT NULL,
    display_name VARCHAR(100) NOT NULL,
    manual_override BOOLEAN NOT NULL DEFAULT TRUE,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (group_id, locale),
    CONSTRAINT fk_toilet_display_group_translation_group
        FOREIGN KEY (group_id) REFERENCES toilet_display_group (group_id) ON DELETE CASCADE
);
