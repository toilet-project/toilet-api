# 공개 상세 캐시 갱신 계약 v2

2026-09-15 · WBS [API #131](https://github.com/toilet-project/toilet-api/issues/131)

이 문서는 배포 독립 화장실 공개 데이터 캐시에 필요한 revision 이벤트 보강을 설명한다. 코드와 수동 SQL은 구현하지만 운영 DB 적용, 운영 API 배포, 계약 v2 활성화는 별도 승인 전까지 수행하지 않는다. 기존 v1 운영 기록은 [web-cache-invalidation.md](web-cache-invalidation.md)에 보존한다.

## 변경 이유

기존 outbox는 같은 화장실의 이벤트를 UUID로 교체하고 ACK 뒤 행을 삭제한다. 상세 데이터가 배포와 독립된 R2 객체가 되면, 원본 조회 도중 더 최신 수정이 발생하거나 오래된 이벤트가 재전송되는 순서를 구분할 수 있어야 한다. v2는 전달 완료 행을 revision 기준으로 남기고 변경마다 revision을 증가시킨다.

## 계약

API는 `WEB_CACHE_CONTRACT_VERSION=1`일 때 기존 `{ "toiletIds": [...] }`를 전송한다. 값이 `2`일 때 다음 본문을 전송한다.

```json
{
  "contractVersion": 2,
  "events": [
    { "toiletId": 53585, "revision": 18, "action": "UPSERT", "catalogChanged": false }
  ]
}
```

| 필드 | 의미 |
| --- | --- |
| `toiletId` | 공개 화장실 ID |
| `revision` | V2 설치 이후 화장실별 단조 증가 번호 |
| `action` | `UPSERT`, `DELETE`, `PRIVATE` |
| `catalogChanged` | 사이트맵·공개 ID 목록도 바뀌는 이벤트 |

정상 ACK는 `acceptedEvents`에 요청한 모든 `toiletId`와 `revision` 쌍을 정확히 포함해야 한다. 200이어도 누락·추가·형식 오류가 있으면 실패로 기록하고 재시도한다. HMAC 서명 원문과 헤더는 v1과 같으며, 본문 원문 전체에 서명한다.

## DB 변경

[V2 수동 SQL](../src/main/resources/db/cache-revalidation/V2__revisioned_cache_events.sql)은 자동 Flyway 디렉터리 밖에 있다.

- `revision`, `action`, `catalog_changed`, `delivered_at`과 전달 조회 인덱스를 추가한다.
- 기존 pending 행의 정확한 원인을 모르므로 최초 한 번 `catalog_changed=true`로 보수 처리한다.
- `toilet`, 기존 `toilet_region`, 정규화된 `toilet_region_assignment`, `toilet_region_decision`의 INSERT/UPDATE/DELETE 12개 트리거를 설치한다.
- ACK 시 행을 삭제하지 않고 `delivered_at`을 기록한다. 다음 변경은 같은 PK 행의 revision을 올리고 다시 pending으로 전환한다.
- 삭제는 `DELETE`와 catalog 변경, 일반 변경은 `UPSERT`로 기록한다.

트리거가 원본 변경 트랜잭션 안에서 outbox를 쓰므로 rollback된 업무는 전송되지 않는다. API/JPA, 배치 JDBC, 관리자 SQL처럼 쓰기 주체가 달라도 대상 테이블의 커밋을 포착한다. `TRUNCATE`, 테이블 교체, 트리거를 우회한 복구는 포착하지 못하므로 별도 정합성 점검이 필요하다.

## 적용 순서

1. 격리 MySQL 8에서 V1→V2 설치, commit/rollback, revision, normalized region, 삭제, 전송 재시작 검사를 통과시킨다.
2. 운영 백업과 트리거 이름 충돌, V18 정규화 테이블 존재를 읽기 전용으로 확인한다.
3. 승인 후 V2 SQL과 [설치 검증 SQL](../src/main/resources/db/cache-revalidation/verify_installation.sql)을 실행한다.
   번역 표시를 공개하기 전에는 V26 적용 여부를 확인한 뒤 V4 번역 트리거까지 같은 수동 절차로 설치한다.
4. V2 컬럼을 읽는 API를 배포하되 `WEB_CACHE_CONTRACT_VERSION=1`을 유지한다.
5. v1/v2 수신 Web과 공유 R2 binding·기능 플래그 준비를 완료한다.
6. 공유 캐시를 표본 검증한 뒤 API 계약만 `2`로 바꾼다.
7. pending 수, 최고 대기 시간, failures, 실제 수정→다음 상세 조회 반영을 확인한다.

운영 계정 기능이 활성 상태이면 준비 상태 전용 `deploy.yml`을 재활성화하지 않는다. 이미지와 계정 설정을 그대로 둔 채 계약 값 하나만 바꾸는 수동 `cache-contract-transition.yml`을 사용한다. 정확한 workflow SHA·현재 API 이미지 SHA·목표 계약 버전을 저장소 변수에 고정하고, 먼저 승인 스위치가 필요 없는 읽기 전용 `check`를 실행한다. 운영 변경 승인 스위치는 `apply`에만 필요하다. `apply`는 배포 중지, V2 스키마 검증, Web V2 수신 및 공유 캐시 표본 검증을 모두 입력으로 확인한 경우에만 `.env`의 `WEB_CACHE_CONTRACT_VERSION` 한 줄을 원자적으로 바꾸고 API만 재생성한다. 실패 시 원래 파일을 복구하고 같은 이미지로 API를 다시 확인하며 자동 재시도하지 않는다.

API 코드를 먼저 배포하면 V2 컬럼 조회가 실패하므로 SQL 선행이 필수다. 반대로 SQL을 먼저 적용해도 기존 v1 전송기는 추가 컬럼을 무시하므로 단계적 전환이 가능하다.

## 복구

- 수신 문제: `WEB_CACHE_CONTRACT_VERSION=1`로 되돌린다. pending은 유지된다.
- 트리거 문제: 승인 후 [rollback SQL](../src/main/resources/db/cache-revalidation/rollback_triggers.sql)로 이 기능의 트리거만 제거한다. outbox와 원본 데이터는 삭제하지 않는다.
- Web 공유 캐시 문제: Web의 공유 캐시 기능 플래그를 끄면 기존 1시간 Next fetch로 돌아간다.
- 전달 완료 ledger는 개인정보가 아니며 즉시 삭제할 필요가 없다. 임의 삭제하면 화장실 revision이 1부터 다시 시작하므로 Web 객체와 함께 명시적인 세대 전환 없이는 비우지 않는다.

## 검증 경계

Java 단위 검사는 v1 호환, v2 직렬화·정확한 ACK, dispatcher 선택과 오류 재시도를 확인한다. Testcontainers는 V1→V2 실제 MySQL DDL, 12개 트리거, commit/rollback, ABA ACK 보호, 전달 완료 뒤 revision 증가, 지역 테이블, 삭제 action과 실제 Spring scheduler→서명 HTTP→ACK를 확인한다. 로컬 Docker가 없을 때 운영 DB로 대체하지 않고 Linux CI 결과를 적용 조건으로 사용한다.
