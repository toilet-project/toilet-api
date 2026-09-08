-- Isolated test fixture only. These tables predate Flyway V1 in the deployed system.
-- Do not add this to production migrations or relax Hibernate validation to create them.
CREATE TABLE toilet (
    toilet_id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    mng_no VARCHAR(50),
    name VARCHAR(100),
    latitude DECIMAL(10,7),
    longitude DECIMAL(10,7),
    coordinate_source VARCHAR(30),
    toilet_type VARCHAR(20),
    road_address VARCHAR(255),
    jibun_address VARCHAR(255),
    male_toilet_count INT,
    male_urinal_count INT,
    male_disabled_toilet_count INT,
    male_disabled_urinal_count INT,
    male_child_toilet_count INT,
    male_child_urinal_count INT,
    female_toilet_count INT,
    female_disabled_toilet_count INT,
    female_child_toilet_count INT,
    agency_name VARCHAR(100),
    phone_number VARCHAR(20),
    open_time VARCHAR(50),
    open_time_detail VARCHAR(255),
    installation_date VARCHAR(20),
    has_emergency_bell VARCHAR(10),
    emergency_bell_location VARCHAR(100),
    has_cctv VARCHAR(10),
    has_diaper_table VARCHAR(10),
    diaper_table_location VARCHAR(100),
    data_base_date VARCHAR(20),
    data_source VARCHAR(20)
);
CREATE TABLE batch_sync_history (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    status VARCHAR(20) NOT NULL,
    range_to DATETIME NOT NULL,
    total_toilet_count BIGINT,
    completed_at DATETIME NOT NULL
);
