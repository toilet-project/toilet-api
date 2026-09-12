# 리뷰 운영 활성화 실행안 — 개발 4주차(2026-09-07~09-13)

2026-09-12 배포 직전 후보. 이 문서는 main 병합·운영 DDL·서버 설정 변경·기능 활성화 승인이 아니다.

## 변경 경계

- Flyway V12는 리뷰·24시간 제한·요청키 테이블과 리뷰 UUID를 추가한다. 기존 회원·화장실·제보·감사·로그 행을 삭제하거나 보관 기한을 바꾸지 않는다.
- `REVIEWS_ENABLED=false`가 API 기본값이다. V12가 적용돼도 리뷰 읽기·쓰기는 활성화되지 않는다.
- 일반 `main` 배포는 저장소 변수 `ACCOUNT_LIFECYCLE_DEPLOYMENT_APPROVED_SHA`가 정확히 해당 커밋과 일치할 때만 실행된다. 리뷰 릴리스에서는 계정 상태를 안전 준비 모드로 덮어쓰는 일반 배포를 사용하지 않는다.
- 작성자 정보 지우기는 해당 리뷰의 작성자 연결과 요청 연결만 제거한다. 리뷰·자유글·평가·화장실·회원·제보·감사·운영 로그를 삭제하지 않는다.
- 24시간 제한 만료 행 정리는 리뷰 트랜잭션을 소유하는 단일 API에서 실행한다. 회원정보 3개월 파기 배치와 분리한다.

## 사전 조건

- [x] API PR #106, 웹 PR #206, 배치 PR #52가 Ready·병합 가능 상태이며 최신 커밋의 전체 CI가 2026-09-12 모두 성공했다.
- [ ] 변경 전 암호화 DB 백업과 해시가 생성됐고 기존 읽기 전용 백업 점검이 성공이다.
- [x] 회원 파기 대장과 분리된 리뷰 로컬 디렉터리·marker·lock·store ID가 준비됐다.
- [x] 독립 저장소의 `review-anonymization-v1` orphan 브랜치에 realm `review-anonymization`, 현재 DB epoch, 0건 inventory genesis가 준비됐다.
- [x] 별도 후보 도구로 로컬 두 목록과 독립 checkpoint를 읽는 0건 사전 검사가 성공했고, 실행 중인 컨테이너 설정과 대장 파일 바이트가 변하지 않았다.
- [ ] 최종 API 이미지가 전용 경로를 마운트한 뒤 같은 snapshot 검사를 다시 통과한다. 이 항목은 실제 배포 단계에서만 확인한다.
- [x] GitHub Actions의 일회용 MySQL 8에서 V12 형식 가상 백업의 회원 파기와 리뷰 연결 해제 결합 복원 시험이 성공했다. 운영 미니 PC에서는 복원 컨테이너를 실행하지 않는다.
- [ ] 자유글 보존·위치 사용 목적·본문 개인정보 요청 절차의 공개 문구와 시행 시점을 별도 승인한다.

## 필요한 설정 이름

비밀값은 문서·명령 이력·Actions 로그에 출력하지 않는다. 기존 회원 파기 대장의 키 체계와 DB epoch를 재사용하지만 리뷰 저장 디렉터리와 store ID는 반드시 분리한다.

```text
REVIEWS_ENABLED=true
REVIEW_UNLINK_ENABLED=true
REVIEW_UNLINK_LOCAL_VERIFIED=true
REVIEW_UNLINK_DIRECTORY=<회원 파기 대장과 분리된 절대 경로>
REVIEW_UNLINK_STORE_ID=<별도 UUID>
REVIEW_GUARD_CLEANUP_ENABLED=true
```

API에는 기존 `ERASURE_LEDGER_PROVIDER=LOCAL`, 키 JSON·active key ID, 회원 대장 로컬 디렉터리, 독립 GitHub 토큰·DB epoch도 필요하다. 하나라도 없거나 대장 snapshot이 불완전하면 작성자 연결 해제 기능은 성공으로 처리되지 않는다.

2026-09-12 준비 검사에서는 전용 orphan 기준선 0건, 전용 ext4 디렉터리 권한 0700, marker/lock 권한 0600, 알 수 없는 파일 0건을 확인했다. `ReviewUnlinkLedgerPreflightCli --read-only`의 결과는 `records=0`, `checkpointMatched=true`, `localStoreVerified=true`, `activationAllowed=false`였다. 운영 DB·Redis에 연결하지 않았고 컨테이너를 재시작하지 않았다. 가상 결합 복원은 운영 미니 PC가 아닌 [배치 GitHub Actions](https://github.com/toilet-project/toilet-batch/actions/runs/34673844484/job/103500043339)에서 통과했다.

웹 운영 리뷰 후보는 다음 네 값이 빌드 시 모두 정확히 일치할 때만 API 모드를 포함한다. 기본 운영 후보는 계속 리뷰 OFF다.

```text
SITE_INDEXABLE=true
NEXT_PUBLIC_API_BASE_URL=https://api.geupddong.com
REVIEW_API_ENABLED=true
REVIEW_PRODUCTION_APPROVED=true
```

## 순차 적용

1. 수동 이미지 빌드로 새 API 이미지를 태그·digest 고정해 준비한다. 일반 `main` 배포는 사용하지 않는다.
2. 기존 `account-preserving-rollout`의 이미지 전용 전환으로 `.env`, `.account-lifecycle.env`, Compose의 기존 마운트와 회원 기능 상태를 그대로 보존하며 API만 교체한다. 이때 리뷰 전용 설정은 아직 없으므로 기본 OFF다.
3. API 교체 후 Flyway V12 성공, 기존 로그인·지도·제보·관리자 상태를 확인한다. 실패하면 여기서 중단한다.
4. `review-preserving-transition`의 `mount-disabled`로 리뷰 대장 bind mount와 전용 0600 `.review.env`만 추가한다. 전체 Compose 렌더에서 이 마운트와 6개 리뷰 변수 외 변경이 있으면 중단한다.
5. 같은 워크플로의 `activate`가 독립 checkpoint와 로컬 대장 snapshot을 읽기 전용으로 재검증한 뒤 API의 리뷰·작성자 연결 보호·제한 행 정리 플래그를 함께 켠다. 기존 회원 플래그·기본 환경 파일·배치 컨테이너가 바뀌면 실패한다.
6. CI에서 생성한 리뷰 ON 웹 운영 후보의 커밋·config 해시·build ID·빈 route·배포 미승인 manifest를 재검증한다. 승인 시에만 운영 route를 별도 주입해 웹을 교체한다.
7. 본인 테스트 계정으로 위치 밖 차단, 위치 안 작성, 24시간 기존 리뷰 이동, 수정, 내 리뷰 조회까지만 순서대로 확인한다.
8. 별도 시험 리뷰에서 작성자 정보 지우기 안내를 확인한 뒤 실행하고, 내 목록 제외·공개 내용 보존·작성자 `익명`을 확인한다.
9. 기존 로그인·지도·제보·알림·관리자 대시보드와 외부 감시를 다시 확인한다.

## 중단·되돌림

- 장애 시 먼저 웹 리뷰 진입을 OFF 후보로 되돌린다. 기존 지도·로그인·제보는 유지한다.
- 다음으로 API `REVIEWS_ENABLED=false`로 리뷰 읽기·쓰기를 닫는다. 대장 파일·checkpoint·DB 리뷰 행은 삭제하지 않는다.
- 리뷰 중단은 `review-preserving-transition`의 `deactivate`만 사용한다. 리뷰 전용 4개 동작 플래그만 false로 바꾸고 기존 회원 기능·대장 마운트·이미지·DB는 유지한다.
- V12 테이블을 DROP하거나 리뷰·제보·감사·로그를 일괄 삭제하지 않는다. 이전 코드가 V12를 무시하도록 코드/플래그만 되돌린다.
- 작성자 연결 해제 대장 기록 후 SQL 응답이 불명확하면 빈 기록으로 되돌리지 않는다. 동일 리뷰 UUID 요청 또는 검증된 복원 재처리로 완료한다.
- 복원 검사 실패 시 복원 DB를 서비스에 연결하지 않는다. 전체 서비스 자동복구 WBS의 승격 조건과 별도로, 개인정보 재등장 방지 단계가 성공할 때까지 격리한다.

## 완료 판정

운영 활성화 완료는 V12 적용만으로 판정하지 않는다. API·웹 플래그, 실제 위치/세션 인수, 익명 처리 후 보존 확인, 결합 복원 시험, 정책 공개 시각, 기존 기능 회귀, 감시 확인이 모두 성공해야 한다. 실패한 단계가 있으면 리뷰만 OFF로 유지하며 다른 서비스 데이터를 삭제하지 않는다.
