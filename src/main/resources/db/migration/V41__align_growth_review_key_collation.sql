-- V12 review keys use utf8mb4_unicode_ci, while V40 growth keys inherited the
-- database default. Align the new keys so review/evidence joins work on MySQL 8.
ALTER TABLE growth_review_evidence
    MODIFY COLUMN review_key CHAR(36) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL;

ALTER TABLE growth_review_exclusion
    MODIFY COLUMN review_key CHAR(36) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL;
