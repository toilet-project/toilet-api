# 관리자 경험치 랭킹

회원 관리에서 저장된 XP와 휴지 등급을 조회한다. 프리뷰 검수 대상이며 운영 병합·배포·DB 변경은 별도 승인 전이다. 새 마이그레이션이나 XP 재계산은 없다.

## 조회 기준

- 대상은 `ACTIVE`, `SUSPENDED` 회원이다. 동의 대기 `PENDING_CONSENT`와 탈퇴 `WITHDRAWN`은 제외한다.
- XP는 기존 `GrowthLedger.totalXp`와 같은 `growth_account.total_xp` 잔액이다. 원장이 없는 회원은 0 XP로 포함한다. 조회는 계정·원장·정책·배지를 생성하거나 보정하지 않는다.
- `position`은 전체 대상의 XP 내림차순 공동순위다. 동점은 `1, 1, 3`이며 검색과 오름차순 정렬에도 전체 순위가 유지된다. 같은 XP의 표시 순서는 회원 ID 오름차순이다.
- 회원 요약·공개 리뷰 작성자 등급·관리자 랭킹은 공통 순수 `GrowthLevelPolicy`를 사용한다. 기존 등급 경계는 그대로다.
- 검색은 이름 부분 일치 또는 전체 이메일 정확 일치다. 이름의 `%`, `_`, `!`는 와일드카드가 아닌 문자다. 암호화 이메일은 기존 HMAC 검색 키를 재사용하며 복호화하지 않는다. 응답 이메일은 기존 회원 목록처럼 마스킹한다.
- 분포는 검색 결과가 아닌 전체 대상이다. 목록과 분포는 각각 최신 조회이므로 그 사이 실제 XP가 변경되면 잠시 차이가 날 수 있다. 페이지 이동 중 XP가 변경되면 새 정렬 순서가 반영되며 과거 시점의 고정 스냅샷은 제공하지 않는다.

## API

`POST /api/admin/v1/growth/ranking/search`

```json
{"keyword":"","sort":"xp_desc","page":0,"size":10}
```

응답: `{items, page, size, totalElements, totalPages, scope, excludedStatuses}`.

각 항목: `{id, displayName, email, status, totalXp, level, rank, rankName, position}`. ID는 문자열이다. `sort`는 `xp_desc` 또는 `xp_asc`, 페이지는 0부터 시작한다. 기본 크기는 10, 허용 크기는 1~50이고 페이지 상한은 100,000이다. 범위를 벗어난 유효 페이지에는 전체 검색 수와 빈 목록을 반환한다.

`GET /api/admin/v1/growth/ranking/distribution`

응답: `{totalMembers, buckets, scope, excludedStatuses}`. 각 분포는 `{rank, rankName, minXp, maxXp, count}`이며 마지막 등급의 `maxXp`는 `null`이다. 7개 등급을 0명도 빠짐없이 반환한다. `scope`는 `ACTIVE_AND_SUSPENDED`, `excludedStatuses`는 `PENDING_CONSENT`, `WITHDRAWN`이다.

| 등급 | XP |
|---|---:|
| white / 흰색 휴지 | 0~79 |
| green / 초록 휴지 | 80~239 |
| yellow / 노랑 휴지 | 240~989 |
| blue / 파랑 휴지 | 990~2,239 |
| red / 빨강 휴지 | 2,240~6,239 |
| pink / 핑크 휴지 | 6,240~15,989 |
| black / 검정 휴지 | 15,990 이상 |

## 개인정보와 접근 경계

검색은 HTTP 메서드만 POST이고 DB 작업은 SELECT뿐이다. 이름·이메일이 URL 및 URL 접근 로그에 남지 않게 검색어는 JSON 본문만 받으며 쿼리 문자열을 거절한다. 본문은 실제 입력 스트림에서 4KiB로 제한한다. 검색어는 최대 320자이며 제어 문자를 받지 않는다. 잘못된 타입·중복 키·알 수 없는 필드·소수 페이지·문자열 숫자·JSON 뒤 추가 토큰도 거절한다.

매 요청에 현재 DB의 ACTIVE 관리자 계정·ADMIN 역할·인증 버전을 재확인한다. 브라우저 POST는 정확한 관리자 출처를 요구하고 native 요청은 검증된 명시 bearer만 허용한다. 성공·실패 모두 `private, no-store`다. 파싱·JDBC 예외는 고정 메시지로 처리하며 검색어·SQL 인자·원문 이메일·암호문을 오류나 작업 기록에 남기지 않는다. 검색 본문 또는 JDBC 인자를 기록하는 별도 프록시/디버그 로깅을 켜서는 안 된다.

## 실데이터 프리뷰

`scripts/experience-ranking-live-preview.init.gradle`의 `experienceRankingLivePreviewJar`로 별도 아티팩트를 만든다. 운영 `bootJar`에 테스트 진입점은 포함되지 않는다. 임시 서버는 운영 API를 재시작하거나 Spring·Flyway·Redis·스케줄러를 시작하지 않는다.

명시적 opt-in, 게이트웨이 비밀키, 최대 2시간 만료 및 localhost 기본 바인딩이 필요하다. 게이트웨이는 매 요청 실제 관리자 세션을 확인한다. 호스트는 `GET /__ready`, `GET /api/admin/v1/growth/ranking/distribution`, `POST /api/admin/v1/growth/ranking/search`만 제공한다. 기존 리뷰 경로나 회원 변경 기능은 노출하지 않는다.

기존 DB 연결값과 `AUTH_EMAIL_SEARCH_KEY`만 서버 내 메모리에서 사용한다. 이메일 복호화 키는 가져오지 않는다. 각 MySQL 연결에 네이티브 `TRANSACTION READ ONLY`를 설정·확인하며 실제 저장소의 SELECT 4종만 JDBC 경계에서 허용한다. 최대 4개 연결, 쿼리 3초·접속 3초·소켓 5초 제한을 적용한다. 실데이터 파일·스냅샷·검색 결과 로그는 만들지 않는다.

## 검증

합성 H2와 CI의 격리 MySQL에서 코호트·0 XP·공동순위·정렬·페이지·이름 리터럴 검색·암호화 이메일 HMAC 검색·모든 등급 경계·현재 권한 회수·조회 전후 DB 동일성을 확인한다. native MySQL 쓰기 거부 검증은 폐기 가능한 테스트 DB에서만 수행한다. 운영 DB에는 쓰기 검증을 실행하지 않는다.
