# 리뷰 로컬 HTTP·MySQL 통합 검증 — 개발 4주차(2026-09-07~09-13)

검증일: 2026-09-12. API 후보 `d55052b`, 웹 후보 `1f4ede5`의 실제 제품 코드를 사용했다. 이번 추가 파일은 시험 실행기·브라우저 검사·검증 문서이며 제품 로직을 변경하지 않았다.

## 실제로 연결한 범위

- 기존 웹의 리뷰 화면/클라이언트 → loopback Spring HTTP → 실제 리뷰 컨트롤러/서비스/저장소 → 새 MySQL 8.0.25 시험 DB.
- JWT 서명·계정 버전 검사, 쿠키 추출, Spring Security, 리뷰 출처 필터, 공통 오류 처리기, 실제 JPA 약관 동의 서비스, JDBC/JPA 트랜잭션을 사용했다. 리뷰 응답/저장소는 mock으로 바꾸지 않았다.
- 관련 기존 마이그레이션과 V12 원문을 빈 가상 스키마에 적용했다. 관련 없는 운영 테이블 전체/Flyway 운영 이력을 복제한 시험은 아니다.
- `auth/me` 응답용 시험 컨트롤러, 신규 시험 JWT 발급, 지도 메타데이터와 GPS 입력만 가상 구성이다. Google/Kakao OAuth와 refresh 저장소는 시험하지 않았다.
- 브라우저는 로컬 웹에서 실행했다. API 요청을 시험 서버로만 전달하고 테스트 전송 계층에서 Origin/CORS를 정규화했다. 제품 CORS/출처 허용 목록은 변경하지 않았다. 실제 운영 도메인의 쿠키/SameSite/Cloudflare 경로 인수를 대체하지 않는다.
- 외부 HTTP 요청은 차단하며 HMR WebSocket만 정확한 loopback 주소로 연결한다. 초기 가상 HTTPS 웹 주소 시험 중 HMR 접속 시도가 있었으나 업무 API/회원/리뷰 쓰기는 없었고, 최종 실행은 외부 WebSocket도 차단했다.

## 결과

- [x] 비로그인 401, 비허용 출처 403, 실제 필수 동의 누락 403.
- [x] 150m 밖/5분 초과/정확도 50m 초과 요청 거부.
- [x] HTTP 작성 201 → MySQL 저장 → 같은 요청키 재전송 시 동일 ID.
- [x] 같은 계정·시설의 동시 요청 2개 중 1개만 생성, 나머지 409.
- [x] 타 계정 본인 상세 조회/수정 거부, 실제 CORS preflight 응답 확인.
- [x] Chrome 390/320/1280px: DB 커밋 후 응답 유실 → 사용자 재시도 → 기존 리뷰 상세 이동. 실제 POST 1회, 추가 GPS 요청 없음.
- [x] 새로고침 후 DB 재조회, 수정 저장, 이전 버전의 변경 요청 409.
- [x] 본인 작성자 연결 해제 → 내 리뷰 제외 → 공개 API에서 본문/평가 보존 및 `익명` 표시.
- [x] 실제 날짜 필터와 최신순 10개 커서 추가, 전체 24개 과거 시험 리뷰 조회.
- [x] 7일 지난 리뷰는 수정 UI 없음/작성자 해제 API 403, 익명 처리 후 24시간 재작성 제한 유지.
- [x] 최종 DB 직접 확인: 과거 가상 행 72개 + 신규 5개 = 총 77개. 익명 연결 해제 3개, 수정 본문 3개 보존, 익명 리뷰의 요청 연결 행 0개.
- [x] 기존 HTTP/규칙 회귀 13건과 bootJar 빌드 통과.
- [x] 웹 타입 검사 및 단위 검사 157건 통과. 운영 JAR 내부의 `ReviewHttpFixture` 클래스 0개 확인.
- [x] 시험 API/웹 listen 포트 해제, 새 MySQL 정상 종료 로그 확인 후 이번 fixture 디렉터리(약 181.5 MiB) 삭제. 임시 JWT metadata도 함께 삭제. 기존 다른 DB/시험 디렉터리는 건드리지 않았다.

390px의 최종 업무 검사는 통과했으나 최초 실행기의 브라우저 종료 중 진행 중인 알림 조회를 닫는 오류가 발생했다. 실행기에 요청 완료/정리 절차를 추가했고, 320/1280px의 나머지 최종 검사는 정상 종료했다. 이를 제품 저장 오류나 추가 통과 건수로 계산하지 않는다. 시험 전용 토큰은 로그에 반복 기록하지 않고 임시 DB와 함께 폐기한다.

## 재실행 방법과 정리 원칙

1. 운영 데이터 없는 새 MySQL을 127.0.0.1의 임시 포트에서 시작한다. 기본 3306은 사용하지 않는다. datadir 이름은 `account-retention-mysql-<10자리 hex marker>/data/`여야 한다.
2. 새 서버의 datadir를 확인한 뒤 `account_retention_fixture_guard.fixture_guard`에 해당 marker 한 개를 준비한다. 기존/운영 서버에 marker를 추가해서 검증을 우회하지 않는다.
3. `ACCOUNT_RETENTION_MYSQL_MARKER`, `ACCOUNT_RETENTION_MYSQL_PORT`, `REVIEW_HTTP_METADATA`를 지정한다. metadata 파일은 같은 fixture 디렉터리 내부의 아직 없는 파일이어야 한다.
4. API에서 `gradlew -I scripts/review-http-fixture.init.gradle reviewHttpFixture` 실행. 원격 JDBC 설정은 받지 않으며 검증된 loopback DB에만 새 스키마를 만든다. HTTP도 loopback 임의 포트이고 20분 후 자동 종료한다.
5. 웹은 기존 개발 명령으로 시작한다. `SITE_INDEXABLE=false`, `REVIEW_API_ENABLED=true`, `TOILET_API_ORIGIN=http://127.0.0.1:<metadata의 port>`, `NEXT_PUBLIC_API_BASE_URL=https://api.geupddong.com`, `NEXT_PUBLIC_KAKAO_JAVASCRIPT_KEY=synthetic-unused`를 사용한다. **이 설정은 요청을 가로채는 자동 검증 브라우저 전용**이며 일반 브라우저로 API 쓰기를 하지 않는다.
6. 웹에서 동일 `REVIEW_HTTP_METADATA`와 필요 시 `PLAYWRIGHT_PACKAGE`, `REVIEW_SCREENSHOT_DIR`를 지정하고 `node tests/review-real-api-browser.cjs` 실행. 쓰기 검사는 새 스키마 기준으로 한 번 실행한다. 중간 재개 전용 `--browser-only --widths=320,1280`은 전체 재검증 결과로 표기하지 않는다.
7. API/웹 프로세스를 종료하고 새 MySQL을 정상 shutdown한다. 포트/프로세스 해제를 확인한 뒤 이번 marker의 정확한 fixture 경로만 삭제한다. 가상 JWT metadata도 함께 삭제하며, 작은 검증 결과/화면과 재사용 검사 코드는 남긴다.

## 완료로 간주하지 않는 것

- 실제 휴대폰 GPS 정확도, OAuth/refresh, 운영 쿠키·프록시·Cloudflare 동작.
- 단건 익명 처리 후 과거 백업으로 작성자 연결이 되살아나지 않게 하는 복원 보호.
- 만료 제한 메타데이터 정리, 자유글 보존/위치 목적 고지 후속 검토.
- 운영 DB 적용, main 병합, 공개 프리뷰/운영 배포, 리뷰 활성화, WBS 공개 변경.
