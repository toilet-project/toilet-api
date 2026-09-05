# 공개 사이트맵 ID projection

2026-09-05 · `feature/seo-read-projections` · 읽기 전용 API, 별도 cache outbox 변경 제외.

- `GET /api/v1/toilets/sitemap/shards`: 실제 존재하는 1만 ID 범위 번호의 오름차순 배열.
- `GET /api/v1/toilets/sitemap/ids?shard=0`: `0 < toilet_id <= 10000`의 실제 ID 배열. 다음 구간은 `10000 < toilet_id <= 20000`이다.
- ID만 반환하고 toilet 엔티티·region·제보·사용자를 JOIN하지 않는다. 상세 API와 같은 공개 테이블의 양의 safe integer ID를 사용한다. 좌표 누락만으로 상세 URL을 제외하지 않는다.
- `ToiletSitemapService`의 read-only 트랜잭션, JdbcTemplate parameter binding, PK range, LIMIT 10000. OFFSET과 신규 인덱스/DDL 없음.
- max ID 9007199254740991, 마지막 구간 900719925474. 범위 밖 요청은 400. 비숫자/필수 파라미터 누락은 MVC 400.
- index 집계는 PK scan/group이며 결과를 최대 50000구간으로 제한하고 50000 이상이면 실패한다. 스캔하는 원본 행 수의 상한은 아니므로 데이터 증가 시 실행계획/시간을 다시 점검한다. Web 정적 sitemap 1개를 고려해 실제 허용은 49999구간이다. 이 규모에 도달하면 별도 index 분할 필요.
- API cache header 5분, Web data cache 1시간. 신규/삭제 즉시 sitemap 갱신은 아니며 요청 기반 재조회. 상세 cache webhook과 독립적이다.
- 실제 수정시각이 보장되지 않으므로 lastmod 없음. database_date 같은 원본 기준일을 대체 사용하지 않는다.
- SecurityConfig의 기존 공개 toilet GET 범위를 재사용한다. noindex 응답 헤더. 인증/개인정보를 추가하지 않는다.
- 운영 적용은 기존 API 배포만 필요하고 신규 DB migration은 없다. 이전 단계의 캐시 대기열 DDL 적용과 혼동하지 않는다.

검증: MockMvc의 익명 요청·ID-only JSON·입력 오류, CI disposable MySQL 8.0에서 sparse ID/경계/삭제/빈 데이터/1만 건 상한과 EXPLAIN PK range 검사 통과.

운영 사전 점검(2026-09-05, API DB 계정, read-only transaction): 전체 53,582개 ID가 0~5 구간으로 나뉜다. 각 구간 10,000/10,000/10,000/10,000/10,000/3,582건. ID 조회는 PRIMARY range/covering index를 사용한다. 구간 집계는 PRIMARY range + temporary/filesort이며 신규 인덱스를 추가하지 않았다. 집계 실측 113ms는 Docker client 실행 왕복을 포함하므로 순수 DB/Worker CPU 시간으로 해석하지 않는다. 배포 후 API ID 배열과 preview XML의 전체 개수·중복·범위를 재검증한다. 배포 결과는 WBS #186 및 Web 검증 문서에 기록한다.

Web 설계: `toilet-web/docs/nextjs-seo-discovery.md` (feature/nextjs-toilet-seo).
