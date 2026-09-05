# 캐시 갱신 대기열 수동 설치 안내

2026-09-05 · 운영자 직접 실행용 · WBS toilet-web #186

## 현재 상태

- 격리 MySQL 및 Spring 스케줄러 검증 CI 통과: [33966325006](https://github.com/toilet-project/toilet-api/actions/runs/33966325006).
- 운영 사전 점검: 대기열 테이블 없음, 원본 두 테이블에 기존 트리거 없음, 각각 53,582행.
- 암호화 DB 백업과 체크섬 파일, 여유 공간 확인. 이번 점검에서 복원 테스트를 새로 수행한 것은 아니다.
- 전용 서명 키를 GitHub API 저장소와 preview Workers에 등록하고 서명 요청 200 / 미서명 요청 401을 확인했다. 값은 문서에 기록하지 않는다.
- 전송기는 비활성화 상태. API main 병합·재배포는 별도 검토/승인 대상이다.
- 운영자가 관리자 계정으로 직접 설치한 뒤 읽기 전용 확인을 완료했다. 테이블 1개/트리거 6개/인덱스 및 본문 일치, 대기열 0건, API 계정 SELECT·EXPLAIN UPDATE/DELETE 확인. **이미 설치되었으므로 설치 SQL을 다시 실행하지 않는다.** 아래 절차는 설치 기록/향후 복구 참고용이다.
- 정정: 초기 검사에서 이스케이프된 DB 이름을 제대로 판별하지 못해 API 권한 부족으로 안내했다. 현재 API `luha@%`에는 해당 DB 전체 권한이 있다. MySQL binlog의 추가 CREATE TRIGGER 권한 조건은 별개다. 이 작업에서 계정 권한이나 전역 서버 설정을 변경하지 않았다.

## 실행 순서

1. MySQL 클라이언트에서 **운영 미니 PC의 `toilet_db`**에 관리자 계정으로 접속한다. 비밀번호를 채팅/문서에 붙여넣지 않는다.
2. 아래 읽기 전용 쿼리로 대상과 기존 객체를 다시 확인한다. 다른 DB이거나 같은 객체가 이미 있다면 설치하지 말고 현재 상태부터 확인한다.

```sql
USE toilet_db;
SELECT DATABASE(), CURRENT_USER(), @@default_storage_engine;
SELECT TABLE_NAME, ENGINE
FROM information_schema.TABLES
WHERE TABLE_SCHEMA = DATABASE()
  AND TABLE_NAME IN ('toilet', 'toilet_region', 'web_cache_invalidation');
SELECT TRIGGER_NAME, EVENT_OBJECT_TABLE
FROM information_schema.TRIGGERS
WHERE TRIGGER_SCHEMA = DATABASE()
  AND EVENT_OBJECT_TABLE IN ('toilet', 'toilet_region');
```

기대값: `toilet_db`, 기본 엔진 및 원본 테이블은 InnoDB, 대기열 테이블 없음, 트리거 0건. 설치 계정은 지속 유지되는 관리자 계정이어야 한다. 생성 트리거의 DEFINER가 해당 계정이므로 이후 계정 삭제·권한 회수는 원본 쓰기에 영향을 줄 수 있다.

3. [설치 SQL](../src/main/resources/db/cache-revalidation/V1__web_cache_invalidation.sql)을 같은 연결에서 **한 번만** 실행한다. 테이블 1개와 트리거 6개만 생성하며 기존 화장실 주소·좌표·행을 수정하지 않는다. 단일 SQL 문으로 된 트리거이므로 별도의 DELIMITER 변경은 필요 없다.
4. [설치 확인 SQL](../src/main/resources/db/cache-revalidation/verify_installation.sql)을 실행한다. InnoDB 테이블 3개와 트리거 6개가 보여야 한다. 실제 업무 변경이 있으면 대기열에 ID가 쌓일 수 있으며 현재 전송기가 꺼져 있으므로 즉시 삭제되지 않는 것이 정상이다.
5. 성공 여부를 알려주면 API 실행 계정의 대기열 읽기/갱신/삭제 권한과 실제 설치 상태를 별도 확인한다. 검토된 API PR을 보여드린 뒤 병합·배포 승인과 후속 활성화를 진행한다.

## 중간 실패와 복구

MySQL DDL 여러 문장은 하나의 트랜잭션으로 함께 롤백되지 않는다. **오류가 나면 반복 실행하거나 다음 문장을 계속 실행하지 말고 중단**한 뒤 오류 번호와 내용만 공유한다. 비밀번호는 보내지 않는다.

현재 서버는 binlog가 켜져 있고 `log_bin_trust_function_creators=0`이다. 1419/1227 등 권한 오류를 해결하려고 전역 설정이나 권한을 임의로 완화하지 않는다.

새 트리거에 문제가 생기면 원본 변경도 실패할 수 있다. 전송기 비활성화만으로 트리거는 제거되지 않는다. [해제 SQL](../src/main/resources/db/cache-revalidation/rollback_triggers.sql)은 이번 트리거 6개만 제거하며 **대기열 테이블과 행은 보존**한다. 운영 반영 전부터 이 파일을 준비해 둔다. 원본 화장실에 테스트용 UPDATE/DELETE를 실행하지 않는다.
