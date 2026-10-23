-- Historical awards remain collected, but are not announced as newly earned.
-- New awards default to unseen. Acknowledgement never changes XP or eligibility.
ALTER TABLE growth_award ADD COLUMN notice_seen_at DATETIME(6) NULL;
UPDATE growth_award SET notice_seen_at = CURRENT_TIMESTAMP(6);
