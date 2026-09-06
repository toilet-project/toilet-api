# 회원 탈퇴·선택적 복구·자동 파기

로컬 feature 브랜치 `feature/account-withdrawal-retention` 구현. 운영 미적용.

- 스키마: `src/main/resources/db/migration/V11__account_withdrawal_retention.sql`
- 설계 원본: docs 저장소 `database/account-withdrawal-retention-v1.11.md`
- 운영 체크: docs 저장소 `operations/account-erasure-runbook.md`
- `ACCOUNT_RETENTION_ENABLED=false`가 기본값이다. 실제 MySQL·백업 복구 시 파기 재적용·고지 검증 전 활성화하지 않는다.
- Spring 스케줄러가 1분/50명 처리, DB 체크포인트로 재개, 최대 60분 지수 재시도. Redis·DB 오류는 전체 DB 파기 롤백.
- 동일 소셜 인증은 10분짜리 별도 확인 쿠키만 발급하며 명시 확인 전 일반 로그인 토큰을 발급하지 않는다.
- 과거 탈퇴자 자동 편입, 운영 회원 삭제, 운영 배포는 수행하지 않았다.

검증: `./gradlew test --tests '*auth.*' --tests '*policy.*' --tests '*ToiletReportServiceTest' --tests '*UserNotificationServiceTest'`.
H2의 SQL 검증과 실제 MySQL 검증은 구분한다. Docker 미설치 환경의 Testcontainers skip을 통과로 집계하지 않는다.
