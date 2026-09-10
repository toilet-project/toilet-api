# 무효한 access cookie가 새 로그인을 막는 문제

## 현상과 원인

다른 기기에서 탈퇴하거나 세션이 만료되면 이전 브라우저의 access cookie는 남아 있을 수 있다.
로그인 시작 주소가 `permitAll`이어도 같은 보안 필터 체인의 JWT 인증이 먼저 실패하면
OAuth 리다이렉트 컨트롤러에 도달하기 전에 401을 반환한다.
복구 인증값이나 refresh cookie가 유효해도 같은 문제가 발생한다.

## 변경 범위

`CookieBearerTokenResolver`에서 다음 **정확한 경로와 메서드**는 access token을 읽지 않는다.
Authorization 헤더도 이 경로의 인증 수단으로 사용하지 않는다.

| 경로 | 메서드 | 실제 검증 수단 |
| --- | --- | --- |
| `/api/v1/auth/login/{google,kakao}` | GET | 허용 provider·복귀 목적지 검사 후 OAuth 시작 |
| `/oauth2/authorization/{google,kakao}` | GET | OAuth 인가 요청·임시 세션 생성 |
| `/login/oauth2/code/{google,kakao}` | GET | 기존 OAuth state·인가 코드 검증 |
| `/api/v1/auth/recovery` | GET·POST·DELETE | 별도 복구 쿠키, 만료·탈퇴 회차 검증; 변경 요청의 Origin 검사 |
| `/api/v1/auth/refresh` | POST | refresh 저장소 조회·현재 회원 상태 검사 |
| `/api/v1/auth/logout` | POST | 해당 refresh 토큰 폐기·로그인 쿠키 만료 |

복구 DELETE는 복구 인증값을 취소할 뿐 회원 복구·파기를 실행하지 않는다.
복구 POST에는 기존 활성화 보호 설정과 Origin·proof 검사를 그대로 적용한다.
access token을 무시하는 것은 인증 성공이나 회원 복구를 의미하지 않는다.
회원정보·프로필 변경·탈퇴·제보·관리자 API에는 기존 JWT 검증을 유지한다.
다른 경로, 하위 경로, 다른 메서드에는 넓은 예외를 적용하지 않는다.

## 검증

- 수정 전 실제 필터 테스트에서 로그인·복구·갱신의 401 재현.
- 경로/메서드 경계 및 헤더 우선순위 단위 테스트.
- 실제 Spring Security 체인과 컨트롤러로 Google/Kakao 로그인 시작, 복구 proof 분리,
  proof 누락/만료 거부, 출처 거부, 복구 취소, refresh 성공/실패, 탈퇴 계정 refresh 거부, logout 검증.
- 가상 키로 서명한 만료·탈퇴 토큰 및 malformed token으로 로그인 시작은 허용되고 보호 API는 거부됨을 검증.
- 로컬 Tomcat에서 쿠키가 남은 두 provider의 OAuth 진행과 잘못된 state 거부 검증.
- 외부 OAuth 제공자·운영 DB·실회원 파기를 테스트에 사용하지 않는다.

## 배포와 인수

DDL·데이터 보정·프론트엔드 변경·쿠키 일괄 삭제는 필요 없다.
기존 계정 보호 설정을 보존하는 API 배포 경로로 별도 승인 후 배포한다.
배포 후 무효한 가상 쿠키의 로그인 시작이 302인지 확인하고, 사용자에게 기존 오류 브라우저로
다시 로그인하여 복구 안내까지 도달하는지 확인받는다. 실제 복구·파기는 별도 인수 범위다.
운영 적용 전까지 사용자의 오류가 해결되었다고 표시하지 않는다.

이번 수정은 auth 진입점의 무효 쿠키 문제만 해결한다. 일반 공개 데이터 API에 대한 JWT 적용 정책은 변경하지 않는다.
