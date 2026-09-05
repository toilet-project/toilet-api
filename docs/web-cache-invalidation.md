# 공개 상세 캐시 갱신 — opt-in 설계·운영 절차

2026-09-05 · `feature/durable-preview-cache` · WBS toilet-web #186

## 적용 상태

운영자가 DDL을 직접 설치했고 읽기 전용 확인을 완료했다. **main 병합·운영 API 재배포·자동 전송 활성화는 아직 하지 않았다.** 전송기는 기본 비활성화다. 아래 DDL은 자동 Flyway 경로 `db/migration` 바깥에 있으므로 API 배포로 다시 설치되지 않는다.

- InnoDB 대기열 1개, 컬럼 7개, PK 및 `idx_web_cache_due`를 설치문과 대조했다.
- `toilet`/`toilet_region` AFTER INSERT·UPDATE·DELETE 트리거 6개의 본문이 설치문과 일치한다. DEFINER는 `root@%`다.
- 대기열은 확인 시점 0건. 원본 두 테이블은 각각 53,582건이며 검증 과정에서 원본/대기열 쓰기는 하지 않았다.
- 실제 API 계정 `luha@%`로 SELECT 및 EXPLAIN UPDATE/DELETE를 확인했다. 대기열 조회 인덱스도 사용된다. 이전 사전 검사에서 DB 이름 `toilet\\_db`의 이스케이프 표기를 누락하여 권한 부족으로 잘못 판별한 안내는 정정한다. 현재 계정은 DB 전체 권한과 전역 CREATE USER 권한이 있으며, 이 작업에서 권한을 변경하지 않았다. 최소 권한 정리는 별도 보안 작업이다.
- GitHub API 저장소 `WEB_CACHE_REVALIDATION_SECRET`과 preview Worker `CACHE_REVALIDATION_SECRET`에 별도 랜덤 키를 등록했다. 실제 수신자에 서명 요청 200/미서명 요청 401을 확인했다. 키 값은 파일·문서에 기록하지 않는다.
- GitHub 변수 `WEB_CACHE_ORIGIN=https://preview.geupddong.com`, `WEB_CACHE_REVALIDATION_ENABLED=false`. 현재 실행 중인 API에는 새 설정이 아직 주입되지 않았다.
- 격리 MySQL 및 실제 Spring 스케줄러/서명 수신자 검증: [CI 33966325006 성공](https://github.com/toilet-project/toilet-api/actions/runs/33966325006). 운영 업무 변경→실제 전송→ACK 전체 연결은 활성화 후 별도 확인해야 한다.

## 변경 포착과 일관성

- 승인 서비스는 API의 JPA, 공공데이터 동기화는 batch의 JdbcTemplate, 행정구역 판정 저장은 batch의 별도 JDBC 경로를 사용한다. 한 서비스에만 webhook을 넣으면 다른 변경이 빠진다.
- `toilet`, `toilet_region`의 INSERT/UPDATE/DELETE 후 트리거 6개가 `web_cache_invalidation`에 ID와 새 UUID 이벤트 토큰만 기록한다. 외부 HTTP는 트리거에서 호출하지 않는다.
- 업무 변경과 같은 트랜잭션이므로 롤백 시 대기열도 롤백된다. Spring 폴러의 별도 연결은 커밋된 행만 읽는다.
- 같은 ID의 변경은 1행으로 합친다. ACK와 재시도 UPDATE는 `(toilet_id,event_id)` 조건을 사용한다. 전송 중 다시 수정되거나 ACK 직후 같은 ID가 다시 등록돼도 이전 ACK가 새 이벤트를 지우지 않는다.
- 삭제된 화장실의 캐시도 제거해야 하므로 대기열에는 FK를 두지 않는다. 원본 삭제의 외래키 cascade가 region 트리거를 실행하지 않아도 toilet 삭제 트리거가 ID를 보존한다.
- 주소/좌표 변경 직후에는 view의 오래된 지역정보가 숨겨진 상태로 갱신되고, 나중에 region 판정이 끝나면 별도로 다시 갱신된다. 개방시간 승인·공공데이터 갱신·관리자 수동 좌표 수정도 모두 동일한 포착 대상이다.
- 직접 SQL UPDATE도 포착하지만 TRUNCATE, 테이블 교체/rename, 트리거를 제외한 복구는 포착하지 못한다. 해당 유지보수 후에는 별도 캐시 재검증/캐시 비우기 절차가 필요하다.

## 대기열 DDL

[설치 SQL](../src/main/resources/db/cache-revalidation/V1__web_cache_invalidation.sql), [트리거 해제 SQL](../src/main/resources/db/cache-revalidation/rollback_triggers.sql)

| 항목 | 용도 |
| --- | --- |
| toilet_id | 화장실 ID, PK. 동일 ID를 합쳐 저장 |
| event_id | 변경마다 새 UUID. 이전 전송 결과와 새 수정의 충돌 방지 |
| attempts | 현재 이벤트의 실패 횟수 |
| next_attempt_at | 다음 전송 가능 시각 |
| first_queued_at / last_queued_at | 최초 대기 / 마지막 변경 시각 |
| last_error_code | HTTP_503 등 제한된 오류 코드. 응답 본문·비밀키·개인정보 저장 안 함 |
| idx_web_cache_due | 다음 실행 시각 + ID 순서로 최대 100건 조회 |

대기열 시각은 모두 `UTC_TIMESTAMP(6)` 기반 UTC다. 기존 업무 시각(KST)을 변경하지 않는다. 운영 조회 시 `CONVERT_TZ(...,'+00:00','+09:00')`로 표시한다. 이 표는 업무 이력 보관이 아닌 전달 대기열이므로 ACK 후 행을 삭제한다.

## 전송과 보안

- 5초 간격, 한 번에 최대 100건, HTTP 연결 제한 3초/요청 제한 8초. redirect는 따라가지 않는다.
- `POST /_internal/cache/revalidate`, JSON은 `{ "toiletIds": [1,2] }`만 허용한다.
- `x-cache-timestamp`: UTC Unix 초. `x-cache-signature`: HMAC-SHA256 소문자 hex.
- 서명 원문은 `v1\nPOST\n/_internal/cache/revalidate\n{timestamp}\n{raw JSON body}` UTF-8이다. 요청 본문이 조금이라도 바뀌면 재서명한다.
- 수신자는 5분 시차, 본문 4KiB, 100개 양의 안전 정수 ID, 고정 메서드/경로, 상수시간 서명 비교를 검증한다. arbitrary tag/path는 허용하지 않는다.
- 같은 유효 서명을 5분 이내 재전송하는 것은 의도적으로 허용한다. 작업이 '삭제/승인'이 아닌 멱등적인 캐시 무효화이기 때문에 전송 재시도와 복수 인스턴스의 중복 배달을 허용한다. 키 유출 시 즉시 교체한다.
- 200만으로는 성공 처리하지 않는다. `ok:true`와 정확한 `acceptedIds` 집합까지 확인해야 ACK한다.
- 실패는 10초부터 지수 증가, 최대 1시간 간격으로 계속 재시도하며 조용히 폐기하지 않는다. 새로운 변경은 토큰을 바꾸고 즉시 실행 대상으로 돌아온다. 전송 실패는 이미 커밋된 업무 승인을 롤백하지 않는다.
- 단, 대기열 트리거 자체의 DB 오류(권한/디스크 부족 등)는 원본 트랜잭션도 실패시킨다. 누락 방지와 원본 쓰기 가용성 사이의 명시적 trade-off다. 문제가 생기면 전송 비활성화만으로는 트리거가 없어지지 않으므로 승인 후 해제 SQL을 적용한다.

## 활성화 순서 — 별도 승인 필요

1. 현재 DB 백업과 복구 경로, 같은 이름의 테이블/트리거 부재를 확인한다. DBA 권한으로 설치 SQL을 검토한다. MySQL 8/InnoDB가 기준이며 binlog 환경의 CREATE TRIGGER 권한도 확인한다. 운영 설정을 자동으로 완화하지 않는다.
2. 테스트 DB에서 설치·업무 롤백·일반 승인/배치 쓰기·해제 검증을 완료한다. 설치는 명시적 SQL로 한 번만 수행한다. MySQL DDL은 원자적 트랜잭션 전체 롤백을 보장하지 않으므로 부분 설치 실패 시 SHOW TRIGGERS로 정확히 확인한다.
3. 별도 승인된 Workers preview에 R2/DO/D1 태그 저장소와 전용 secret을 준비한다. D1에는 화장실 원본이 아니라 캐시 태그/갱신 시각만 저장한다.
4. API `WEB_CACHE_ORIGIN`을 preview origin으로, `WEB_CACHE_REVALIDATION_SECRET`을 Workers `CACHE_REVALIDATION_SECRET`과 동일하게 주입한다. OAuth/JWT 키와 별개의 랜덤 32바이트 이상 키를 사용한다. URL query·문서·브라우저 공개 변수에 넣지 않는다.
5. `WEB_CACHE_REVALIDATION_ENABLED=true`는 DDL 설치 및 receiver 검증 후에만 적용한다. `CACHE_RUNTIME=workers`는 wrangler에 설정돼 있다. preview와 운영에는 같은 키/대기열 수신자를 동시에 쓰지 않는다.
6. 쓰기 동작/롤백/재시작 검증은 격리된 MySQL과 Spring 전송기로 수행한다. 사용자가 승인한 현재 연결은 실제 운영 변경을 preview 캐시에 전달하는 것이다. 테스트를 위해 정상 화장실을 임의 수정하지 않는다. 공개 웹 전환 시 대상 origin/secret을 검토하고 기존 미전송 대기열을 확인한다.
7. 승인 후 원본 DB 반영, 대기열 생성/삭제, Workers 응답, 신규 HTTP 요청의 HTML·지역·metadata 갱신을 끝까지 확인한다. main 배포는 별도 검토/승인 후 진행한다.

## 관측·복구

- Micrometer: `web.cache.invalidation.pending`, `web.cache.invalidation.oldest.seconds`(-1은 조회 장애), `web.cache.invalidation.deliveries`, `web.cache.invalidation.failures`. 메트릭은 기존 보호된 운영 수집 경로에서 확인한다. 오래된 대기/실패에 대한 별도 알림 연결은 이 PR에 포함하지 않는다.
- pending이 오래 유지되면 `first_queued_at`, attempts, last_error_code로 확인한다. 첫 데이터 변경부터 ACK까지의 최대 지연을 운영 preview에서 측정해야 한다.
- 수신지 복구 후 자동 재시도한다. 재시도 앞당김은 대상 ID에 한해 next_attempt_at을 UTC 현재로 변경하는 수동 운영 작업이다. 전체 대기열 무조건 삭제는 금지한다.
- 수신 ACK와 DB 삭제 사이에 서버가 재시작돼도 행이 남아 중복 배달된다. 허용된 멱등 동작이다.
- 긴 장애에는 상세 캐시의 기존 1시간 TTL이 보조 수단이지만, stale-on-error 때문에 모든 오류 상황에서 '1시간 안에 무조건 최신'은 보장하지 않는다.
- 이미 열린 브라우저 카드/Next router의 클라이언트 캐시는 webhook만으로 즉시 밀어 갱신하지 않는다. 이 단계의 보장은 서버의 후속 조회이며, 사용자 열린 화면 실시간 갱신은 별도 UX 정책이다.

## 테스트

Java 단위 테스트: 서명 전송·정확한 ACK·실패 보존·백오프·무효 설정·민감정보 로그 회피.
MySQL Testcontainers: commit/rollback, 다른 연결의 미커밋 이벤트 비노출, 변경 합치기, 오래된 ACK/재시도의 새 이벤트 보호, region insert/update/delete, 원본 삭제, 실패 재시도/재시작 후 유지. 로컬에는 Docker가 없어 이 통합 테스트는 Linux CI에서 실행한다.
실제 Spring 스케줄러 + 격리 MySQL + HMAC 검증 수신자: 커밋 후 자동 전송/ACK, 롤백 미전송, 503 후 대기열 보존 및 Spring 컨텍스트 재시작 후 재전송. 실제 해제 SQL 실행 후 트리거 6개 제거와 대기열 보존도 검증한다. API 운영 계정에 CREATE TRIGGER 권한을 추가하는 테스트가 아니라 별도 fixture DBA가 설치한다.
Web 모의 production: 정상 서명 갱신, 위조 서명 미갱신, 이름/시간/지역 반영, 지역 제거, 삭제 404와 복구 200, 1회 원본 호출 및 HIT, 미존재와 원본 장애 구분.
