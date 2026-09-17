-- Per-administrator work-queue preference only. Never changes toilet visibility or public queries.
CREATE TABLE duplicate_name_work_visibility (
    admin_user_id BIGINT NOT NULL,
    group_name VARCHAR(100) NOT NULL,
    work_hidden BOOLEAN NOT NULL DEFAULT FALSE,
    version BIGINT NOT NULL DEFAULT 0,
    updated_at DATETIME NOT NULL,
    PRIMARY KEY (admin_user_id, group_name),
    CONSTRAINT fk_duplicate_work_admin FOREIGN KEY (admin_user_id) REFERENCES app_user(user_id)
);
