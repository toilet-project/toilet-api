-- 기존 주소는 재분류하거나 일괄 변경하지 않는다. 신규 제보/확정부터 주소 유형을 분리한다.
ALTER TABLE toilet_report
    ADD COLUMN proposed_jibun_address VARCHAR(255) NULL COMMENT '제보 좌표의 역지오코딩 지번주소' AFTER proposed_road_address;

ALTER TABLE coordinate_revision
    ADD COLUMN previous_jibun_address VARCHAR(255) NULL COMMENT '변경 전 지번주소' AFTER previous_road_address,
    ADD COLUMN applied_jibun_address VARCHAR(255) NULL COMMENT '확정 좌표의 지번주소' AFTER applied_road_address,
    MODIFY COLUMN applied_road_address VARCHAR(255) NULL COMMENT '확정 좌표의 도로명주소 (미반환 시 NULL)';
