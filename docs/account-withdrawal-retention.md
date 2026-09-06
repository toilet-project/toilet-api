# 회원 탈퇴·선택적 복구·자동 파기

로컬 feature 브랜치 `feature/account-withdrawal-retention` 구현. 운영 미적용.

- 스키마: `src/main/resources/db/migration/V11__account_withdrawal_retention.sql`
- 설계 원본: docs 저장소 `database/account-withdrawal-retention-v1.11.md`
- 운영 체크: docs 저장소 `operations/account-erasure-runbook.md`
- `ACCOUNT_RETENTION_ENABLED=false`가 기본값이다. 실제 MySQL·백업 복구 시 파기 재적용·고지 검증 전 활성화하지 않는다.
- API에는 정기 파기 스케줄러가 없다. 즉시 파기 요청과 만료 후 OAuth 재가입에 필요한 파기 서비스만 유지한다.
- 정기 파기는 toilet-batch의 매일 02:00 KST 공공데이터 동기화 종료 후 실행한다. 동기화 실패도 후속 실행하며, 실패·누락 건은 다음 일일 실행에서 재시도한다.
- API와 배치는 동일한 AccountErasureSql v1 소스를 사용한다. 별도 저장소 사본이므로 배포 전 batch의 scripts/verify-account-erasure-contract.ps1로 일치 검증한다. Redis·DB 오류는 전체 DB 파기 롤백.
- 동일 소셜 인증은 10분짜리 별도 확인 쿠키만 발급하며 명시 확인 전 일반 로그인 토큰을 발급하지 않는다.
- 과거 탈퇴자 자동 편입, 운영 회원 삭제, 운영 배포는 수행하지 않았다.

검증: `./gradlew test --tests '*auth.*' --tests '*policy.*' --tests '*ToiletReportServiceTest' --tests '*UserNotificationServiceTest'`.
H2의 SQL 검증과 실제 MySQL 검증은 구분한다. Docker 미설치 환경의 Testcontainers skip을 통과로 집계하지 않는다.
