# Preview 로그인 연결

WBS toilet-web #186. 운영 main `52fb277`에서 분리한 `feature/preview-oauth-origin` 변경이다.
미배포 region projection/sitemap/cache-outbox 기능과 DDL은 이번 배포에서 제외한다.

- 정확한 CORS origin `https://preview.geupddong.com`만 추가. wildcard/HTTP/workers.dev 미허용.
- `/api/v1/auth/login/{google|kakao}?returnTo=preview`는 서버가 지정한 preview 주소만 세션에 저장.
- `home` 시작은 이전 admin/preview 복귀 세션을 제거. 모르는 returnTo는 400.
- 기존 회원은 preview `/?login=success`, 신규 동의 대상은 같은 preview `/?login=success&consent=required`로 복귀.
- admin 동의 대상은 기존 운영 동의 화면과 returnTo=admin 유지.
- 실패/취소 시 검증된 대상의 `/?login=failed`로 복귀, 오류 상세/토큰 미노출.
- 세션에 변조된 URL이 있어도 성공/실패 모두 운영 홈으로 fallback.
- OAuth provider callback URI와 쿠키 HttpOnly/Secure/SameSite=Lax/host-only는 그대로 유지.

검증: PreviewOAuthTest, AuthControllerTest(정확한 CORS 허용/위조 origin 차단), 기존 공개/관리자 controller 테스트.
실제 Google/Kakao 로그인은 배포 후 사용자가 브라우저에서 진행한다. 테스트 목적으로 제보/회원탈퇴/권한 변경은 수행하지 않는다.

개발 화면은 운영 API를 호출한다. 로그인 및 쓰기 동작은 운영 데이터에 영향을 줄 수 있으므로 사용자는 이를 알고 검증해야 한다.
롤백은 본 변경의 역커밋을 검토 후 CI/CD로 배포. 앱 코드를 이전 버전으로 돌리면 preview origin/복귀 지원이 제거된다. DB migration/스키마 rollback은 없다. Worker custom domain과 Kakao 허용 도메인은 별도 설정이며 자동 삭제하지 않는다.
