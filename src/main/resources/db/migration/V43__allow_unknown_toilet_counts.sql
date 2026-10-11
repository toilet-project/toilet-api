-- Explicit NULL means unknown. Keep legacy defaults for imports that omit a count column.
-- Fail rather than silently fall back to a DDL algorithm that blocks table writes.
ALTER TABLE toilet
    MODIFY COLUMN male_toilet_count INT NULL DEFAULT 0 COMMENT '남성용-대변기수',
    MODIFY COLUMN male_urinal_count INT NULL DEFAULT 0 COMMENT '남성용-소변기수',
    MODIFY COLUMN male_disabled_toilet_count INT NULL DEFAULT 0 COMMENT '남성용-장애인용대변기수',
    MODIFY COLUMN male_disabled_urinal_count INT NULL DEFAULT 0 COMMENT '남성용-장애인용소변기수',
    MODIFY COLUMN male_child_toilet_count INT NULL DEFAULT 0 COMMENT '남성용-어린이용대변기수',
    MODIFY COLUMN male_child_urinal_count INT NULL DEFAULT 0 COMMENT '남성용-어린이용소변기수',
    MODIFY COLUMN female_toilet_count INT NULL DEFAULT 0 COMMENT '여성용-대변기수',
    MODIFY COLUMN female_disabled_toilet_count INT NULL DEFAULT 0 COMMENT '여성용-장애인용대변기수',
    MODIFY COLUMN female_child_toilet_count INT NULL DEFAULT 0 COMMENT '여성용-어린이용대변기수',
    ALGORITHM=INPLACE, LOCK=NONE;
