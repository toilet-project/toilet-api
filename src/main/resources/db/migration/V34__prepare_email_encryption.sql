-- Additive only. Existing binaries and rows remain readable until encrypted writes are enabled.
ALTER TABLE app_user ADD COLUMN email_ciphertext VARCHAR(2048) NULL;
ALTER TABLE app_user ADD COLUMN email_lookup_hash CHAR(64) NULL;
ALTER TABLE app_user ADD COLUMN email_masked VARCHAR(320) NULL;
CREATE INDEX idx_app_user_email_lookup ON app_user(email_lookup_hash);
ALTER TABLE user_social_account ADD COLUMN provider_email_ciphertext VARCHAR(2048) NULL;
