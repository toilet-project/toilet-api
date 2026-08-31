-- V1~V5 운영 기간에 toilet-api JVM이 UTC로 동작하면서 LocalDateTime 값을
-- DATETIME 칼럼에 UTC 그대로 기록했다. MySQL과 운영 UI의 기준인 KST로 한 번만 보정한다.
-- Flyway 이력으로 단 한 번 실행되므로 이후 KST 신규 데이터는 중복 보정되지 않는다.

UPDATE app_user
SET last_login_at = CASE WHEN last_login_at IS NULL THEN NULL ELSE DATE_ADD(last_login_at, INTERVAL 9 HOUR) END,
    created_at = DATE_ADD(created_at, INTERVAL 9 HOUR),
    updated_at = DATE_ADD(updated_at, INTERVAL 9 HOUR);

UPDATE user_social_account
SET linked_at = DATE_ADD(linked_at, INTERVAL 9 HOUR),
    last_login_at = CASE WHEN last_login_at IS NULL THEN NULL ELSE DATE_ADD(last_login_at, INTERVAL 9 HOUR) END;

UPDATE user_role
SET granted_at = DATE_ADD(granted_at, INTERVAL 9 HOUR);

UPDATE audit_log
SET created_at = DATE_ADD(created_at, INTERVAL 9 HOUR);

UPDATE toilet_report
SET reviewed_at = CASE WHEN reviewed_at IS NULL THEN NULL ELSE DATE_ADD(reviewed_at, INTERVAL 9 HOUR) END,
    created_at = DATE_ADD(created_at, INTERVAL 9 HOUR),
    updated_at = DATE_ADD(updated_at, INTERVAL 9 HOUR);

UPDATE coordinate_revision
SET applied_at = DATE_ADD(applied_at, INTERVAL 9 HOUR);

UPDATE user_notification
SET read_at = CASE WHEN read_at IS NULL THEN NULL ELSE DATE_ADD(read_at, INTERVAL 9 HOUR) END,
    created_at = DATE_ADD(created_at, INTERVAL 9 HOUR);

-- created_at/updated_at은 MySQL(KST)이 생성했으므로 API가 기록한 reviewed_at만 보정한다.
UPDATE coordinate_quality_review
SET reviewed_at = DATE_ADD(reviewed_at, INTERVAL 9 HOUR)
WHERE reviewed_at IS NOT NULL;
