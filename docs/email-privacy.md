# Email privacy rollout

The self profile and administrator user responses keep the existing `email` JSON field but return only a masked hint. Existing web/mobile versions can read it without a contract change. User IDs, provider subject hashes, JWTs, sessions, roles, reviews and photos are unchanged.

## Storage

V34 is additive: it adds encrypted email columns, a masked display hint and an indexed HMAC lookup value. It does not transform existing data on startup. Account and linked-provider copies are both protected; removing the duplicate provider copy is deliberately outside this compatibility release.

AES-256-GCM uses a fresh 96-bit nonce and 128-bit authentication tag, a version/key ID envelope, and different authenticated contexts for the account and provider fields. Search uses HMAC-SHA-256 with a separate random key, not an unkeyed email hash. An email can be searched by its complete, case-insensitive address; partial nickname search remains supported. Partial email search is intentionally no longer supported. No profile or administrator response exposes the raw address.

Configuration comes only from the server secret environment:

- `AUTH_EMAIL_ENCRYPTION_ENABLED=false` by default for the initial compatible release.
- `AUTH_EMAIL_ACTIVE_KEY_ID=V1`.
- `AUTH_EMAIL_KEY_V1`: base64-encoded 32 random bytes, generated in a secret manager or on the server.
- `AUTH_EMAIL_SEARCH_KEY`: a separate base64-encoded 32-byte random key. Keep this stable across encryption key rotation.

Never put real keys in source, chat, build output, APKs, browser variables, or database backups. Keep recoverable key backups in a separate restricted secret store. Retain old encryption keys while any row or retained backup depends on them.

## Deployment sequence

1. Record the running API/web versions, account counts, current session behavior and backup/restore status. Keep encrypted, access-controlled database backups and the required keys separately. Do not dump addresses to logs or reports.
2. Deploy this API with encrypted writes disabled. V34 adds nullable columns only. Verify login, refresh, masked `/api/v1/auth/me`, admin lookup and withdrawal. Keep this compatible image/digest as the minimum rollback target.
3. Deploy the web/mobile display changes. They defensively mask old API responses too. Do not log users out or clear their application data.
4. Provision and restore-test the two server secrets. Confirm every API writer runs the compatible release, then enable encrypted writes on all writers. New users and subsequent logins clear the legacy plaintext columns in the same transaction that stores ciphertext, lookup hash and display hint. Missing or invalid keys reject encrypted writes rather than falling back to plaintext.
5. Run `./gradlew emailPrivacyCheck` in the approved maintenance environment using `SPRING_DB_URL`, `SPRING_DB_USERNAME`, `SPRING_DB_PASSWORD` and the key settings. It reads existing values and outputs counts only. Inspect its exit status.
6. Set `AUTH_EMAIL_MIGRATION_ACK=ENCRYPT_EXISTING_EMAILS` only for the explicit migration process and run `./gradlew emailPrivacyMigrate`. It first verifies every existing ciphertext, locks at most 100 parent accounts per transaction, encrypts and verifies both copies, then clears their old plaintext columns. Parent-first locking matches OAuth/withdrawal; a failed batch rolls back and rerunning safely resumes.
7. Run the check again: both legacy counts must be zero. Compare user IDs/counts, provider identity counts, roles and session behavior with the preflight. Verify the two social providers and an existing mobile session before closing the rollout.

The command is not a startup runner or daily batch. Deploying the code alone does **not** mean existing records were encrypted.

## Rollback and recovery

Before the first encrypted write, the previous image still works with the additive schema. After encryption starts, roll back only to a compatible image, with the same keys. **Do not roll back to an image predating V34/read support or remove the keys.** Disabling a write flag does not decrypt records; it is not a rollback procedure. Interrupted backfills leave a valid mixture of legacy/protected rows, and the compatible code can read both.

Withdrawal clears plaintext, ciphertext, lookup hash, display hint and linked-provider ciphertext. Existing erasure jobs delete the account rows and remain compatible without receiving encryption keys.

Historical plaintext may remain in preexisting database backups or transaction logs. This migration does not rewrite those files. Review access, storage encryption, retention/expiry and restore handling separately; do not delete recovery backups ad hoc. A restored legacy database must pass the same email privacy check/backfill before serving traffic. Do not ship SQL bind-value/parameter DEBUG logging.

## Validation

Synthetic tests cover old/new profile masking, short/Unicode/malformed addresses, nonce uniqueness, ciphertext tampering, wrong context/key, previous-key reads, HMAC lookup, existing/new social identity, administrator bootstrap, multi-batch conversion, restart, rollback and withdrawal. Production keys and personal data are not used by tests.
