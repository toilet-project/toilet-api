# OAuth 임시 세션 쿠키 정책

## 적용 범위

소셜 로그인 시작부터 콜백까지 Spring Security가 인가 요청(state)과 허용된 복귀 화면을 HttpSession에 보관한다. 이때 Tomcat이 만드는 `JSESSIONID`는 로그인 완료 후 발급하는 Access/Refresh 쿠키 및 Cloudflare Access의 인증 쿠키와 별개다.

| 쿠키 | 용도 | 정책 |
| --- | --- | --- |
| JSESSIONID | OAuth 인가 요청·관리자/프리뷰 복귀 주소 | Secure, HttpOnly, SameSite=Lax, 기존 host-only/Path=/ 유지 |
| geupddong_access | 서비스 JWT 인증 | 기존 Secure/HttpOnly/Lax 및 수명 유지 |
| geupddong_refresh | Redis에 연결된 갱신 토큰 | 기존 Secure/HttpOnly/Lax, /api/v1/auth 및 수명 유지 |

## 변경 이유

임시 세션 쿠키에도 `server.servlet.session.cookie.secure`, `http-only`, `same-site`를 명시한다. 프록시와 앱 사이가 HTTP인 경우에도 외부 HTTPS 쿠키 정책을 추론에 맡기지 않는다. Nginx에서 쿠키를 일괄 재작성하거나 인증 공급자·callback·CORS·Domain 범위를 바꾸지 않는다.

현재 Google/Kakao 인가 코드 흐름의 최상위 GET 복귀와 호환되도록 Lax를 사용한다. Strict는 외부 공급자에서 돌아오는 요청의 세션 전달을 막을 수 있고, None은 현재 흐름에서 불필요하다. 향후 form_post/iframe 흐름을 도입한다면 별도 검토한다. SameSite는 OAuth state 검증이나 API 인가/CSRF 대책을 대체하지 않는다.

정상 로그인 시 기존 성공 처리기의 JWT 발급 및 HttpSession 무효화를 유지한다. 보관/탈퇴 복구 흐름과 Redis 정책, 세션 timeout, 쿠키 이름·경로·domain·수명은 바꾸지 않는다. 로컬 HTTP 개발 편의를 위해 운영 Secure 기본값을 낮추지 않는다. 실제 로그인 개발은 기존 운영/프리뷰 HTTPS API 또는 별도로 구성한 로컬 HTTPS를 사용한다.

## 자동 검증

`OAuthSessionCookieIntegrationTest`는 실제 내장 Tomcat과 실제 SecurityConfig/로그인 시작 컨트롤러를 사용한다. 애플리케이션 전체 스캔·DB·Redis 연결은 하지 않고, 공급자는 fixture.invalid, 성공 처리기는 mock이다. HTTP 클라이언트는 리다이렉트를 따르지 않는다.

- HTTP upstream에서도 Google 세션 쿠키의 보안 속성 강제
- HTTPS 전달 헤더 조건에서 Kakao 세션 쿠키 보안 속성
- 관리자/프리뷰 로그인 시작의 세션 쿠키 및 취소 콜백의 복귀 주소 유지
- 잘못된 state와 세션 없는 콜백에서 성공 처리기 미호출
- 기존 PreviewOAuthTest/AuthControllerTest 등으로 정상 복귀·로그인 JWT 쿠키 회귀 검사

MockMvc만으로는 내장 컨테이너의 Set-Cookie 생성까지 검증할 수 없어 실제 Tomcat 테스트를 추가했다. 테스트가 Cookie 헤더를 명시적으로 보내는 것은 세션 연결 검사이며, 실제 브라우저 SameSite/Secure 동작의 종단 간 검증은 아니다.

### 실행 결과 · 2026-09-07

설정 적용 전 실제 Tomcat 테스트 6건 중 쿠키 속성을 검사하는 4건이 실패했다. 설정 적용 후 아래 7개 스위트의 37건이 모두 통과했다(실패·오류·건너뜀 0건). 전체 저장소 테스트 실행 결과가 아니라 이번 변경의 회귀 범위다. 운영 DB·Redis·외부 OAuth 공급자에는 접속하지 않았다.

| 스위트 | 통과 |
| --- | ---: |
| OAuthSessionCookieIntegrationTest | 6 |
| PreviewOAuthTest | 7 |
| AccountRecoveryControllerTest | 4 |
| AuthControllerTest | 7 |
| AdminRegionReviewControllerTest | 4 |
| DataSyncStatusControllerTest | 1 |
| ToiletControllerTest | 8 |

```sh
./gradlew test --tests '*OAuthSessionCookieIntegrationTest' --tests '*PreviewOAuthTest' --tests '*AccountRecoveryControllerTest' --tests '*AuthControllerTest' --tests '*AdminRegionReviewControllerTest' --tests '*DataSyncStatusControllerTest' --tests '*ToiletControllerTest' --no-daemon --console=plain
```

Java 21·Gradle 9.5.1에서 검증했다. 새 라이브러리나 버전 변경은 없다. Chrome Safe Browsing 판정 원인 또는 해제 여부를 이 쿠키 테스트로 판단하지 않는다.

## 배포·인수 경계

이 변경은 DDL·데이터 변경·새 Secret이 없고 API의 세션 쿠키 설정만 보완한다. 별도 API 배포 승인 후 기존 외부 경로에서 시작 쿠키의 속성과 Google/Kakao 실제 복귀를 확인한다. 관리자/사용자/프리뷰를 구분한다. API 재시작 중 진행 중이던 OAuth 세션은 유실되어 로그인을 다시 시작해야 할 수 있다. 기존 쿠키의 속성을 소급 변경하는 것은 아니며 서버가 새로 쿠키를 발급할 때 적용된다.

운영 DNS 전환은 이 보완의 배포·인수와 별도다. 문제 발생 시 운영 보안 설정을 즉석에서 약화하지 않고 로그·callback/state/쿠키 전달을 구분해 조사한다. 실제 provider 로그인 없이 자동 테스트 성공만으로 운영 OAuth를 완료 처리하지 않는다.

## 근거

- [Spring Boot 세션 SameSite 설정](https://docs.spring.io/spring-boot/reference/web/servlet.html#web.servlet.embedded-container.cookies.samesite)
- [Spring Security 인가 요청의 HttpSession 보관](https://docs.spring.io/spring-security/reference/servlet/oauth2/client/authorization-grants.html)
- [쿠키 Secure·HttpOnly·SameSite 동작](https://developer.mozilla.org/en-US/docs/Web/HTTP/Reference/Headers/Set-Cookie)
