CREATE TABLE toilet (
    toilet_id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(100), mng_no VARCHAR(50),
    visibility_status VARCHAR(24) NOT NULL DEFAULT 'VISIBLE',
    latitude DECIMAL(10,7), longitude DECIMAL(10,7), coordinate_source VARCHAR(30),
    road_address VARCHAR(255), jibun_address VARCHAR(255), toilet_type VARCHAR(20),
    open_time VARCHAR(50), open_time_detail VARCHAR(255),
    male_toilet_count INT, male_urinal_count INT, male_disabled_toilet_count INT,
    male_disabled_urinal_count INT, male_child_toilet_count INT, male_child_urinal_count INT,
    female_toilet_count INT, female_disabled_toilet_count INT, female_child_toilet_count INT,
    agency_name VARCHAR(100), phone_number VARCHAR(20), installation_date VARCHAR(20),
    has_emergency_bell VARCHAR(10), emergency_bell_location VARCHAR(100), has_cctv VARCHAR(10),
    has_diaper_table VARCHAR(10), diaper_table_location VARCHAR(100),
    data_base_date VARCHAR(20), data_source VARCHAR(20), region_revision BIGINT NOT NULL DEFAULT 1,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP
);
