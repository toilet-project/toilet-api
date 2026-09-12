# Account lifecycle deployment preparation

This feature is not authorized for production activation. The deployment preflight requires
`ACCOUNT_LIFECYCLE_DEPLOYMENT_APPROVED=true` and an exact `ACCOUNT_LIFECYCLE_DEPLOYMENT_APPROVED_SHA` match are required before building/pushing an image or connecting to the host.
Do not set it just to make a failed build green: the API rollout runs Flyway V11 and temporarily disables existing self-service withdrawal.

- Current workflow forces `ACCOUNT_LIFECYCLE_MAINTENANCE=true`, retention/erasure/ledger/catalogue flags false. Activation is rejected by preflight and requires a separate reviewed release.
- Maintenance gates withdrawal, recovery status/decisions, withdrawn-account OAuth and direct erasure services. Ordinary login/nickname routes remain outside this gate. Cancelling recovery can still invalidate its cookie/proof.
- This is startup configuration, not hot reload or a distributed mutex. Drain/restart all API/batch writers and verify flags when stopping work; already-running operations are not retroactively cancelled.
- Outside maintenance: retention enables recovery, erasure enables direct SQL erasure. New withdrawal requires BOTH to avoid intentionally accepting deletion requests with no erasure capability.
- R2 endpoint/realm/bucket/active-key ID use repository variables. Access key, secret key and encryption key JSON use the existing GitHub Secrets.
- A masked base64 step output transports single-quoted Compose dotenv values to `.account-lifecycle.env` (0600). It is an encoding, not encryption. Only the API receives that file; the Redis container does not receive the new R2 credentials.
- The API still needs its existing `REDIS_PASSWORD`. Keep the batch on that SAME Redis/DB/password; do not reset passwords or flush keys.
- Execute V11 through the existing Flyway path only. Do not run the docs copy manually first or fake Flyway history.

Before activation: production Redis/R2 connectivity, ledger checkpoint/database epoch, backups/key recovery, policy and web transition, and real approved test-account flows remain required. Preparation checks are not proof of live credential validity or erasure readiness.
