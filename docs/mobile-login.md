# Native app login

The mobile app uses the existing Google/Kakao OAuth registrations and user database. Native login is disabled unless `AUTH_MOBILE_ENABLED=true` (`auth.mobile.enabled`). Existing cookie login remains available.

The app creates a 32-byte random verifier and independent state; sends a base64url SHA-256 challenge to `/api/v1/auth/mobile/login/{provider}` using the system authentication browser. The servlet session binds this attempt to Spring OAuth state. Success invalidates that temporary session and returns only a 90-second single-use code to the fixed `geupddong://auth/callback` address. Redis atomically checks the verifier challenge and consumes the code. The app posts the code/verifier to `/exchange`, then stores the returned session in Keychain/Keystore through Expo SecureStore.

`/refresh` accepts the refresh token in a JSON body and consumes it atomically before issuing its replacement. `/logout` revokes the supplied refresh token. Both endpoints, and `/exchange`, send `Cache-Control: no-store`; clients must not log request bodies or tokens. Existing JWT status/version checks and account consent rules still apply. No wildcard CORS change is needed for native fetch.

Enable after deploying the companion app and checking Google/Kakao browser-to-app return on real iOS/Android devices. Pending-consent users use the existing policy endpoints. Withdrawn users receive `recovery_required`; recovery continues on the existing website. Do not add access/refresh tokens to callback URLs, or accept caller-controlled redirect targets.
