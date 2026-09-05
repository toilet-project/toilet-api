# 공개 상세 API의 검증된 지역정보

2026-09-05 · Next.js 상세 SEO 연동용 최소 확장 · `feature/seo-read-projections`

`GET /api/v1/toilets/{id}` 응답에 nullable `region`을 추가한다. 기존 필드와 지도 영역 목록 응답은 바꾸지 않는다.

```json
{
  "region": {
    "sidoName": "충청남도",
    "sidoCode": "44",
    "sigunguName": "천안시 서북구",
    "sigunguCode": "44133",
    "cityName": "천안시",
    "districtName": "서북구"
  }
}
```

- 기존 `current_toilet_region` view를 toilet_id로 조회한다. VERIFIED이며 원본/평가 좌표와 현재 좌표, 두 주소가 일치하는 행만 노출된다.
- 지역 결과 없음, 미검증, 현재 좌표/주소와 불일치하면 `region: null`. 기존 주소·좌표는 그대로 응답하고 주소 문자열로 지역을 추정하지 않는다.
- 시/구 추가 계층은 선택 필드이며 세종 등에서 null을 허용한다. 코드 자료형은 문자열이다.
- 상세 조회의 기존 read-only transaction에서 엔티티와 지역 projection을 읽는다. 상세 1건당 지역 조회 1회 추가, 지도 목록 쿼리는 변경 없음.
- DDL·인덱스·정규화 작업·데이터 업데이트 없음. V8의 기존 view와 PK를 사용한다.
- 서비스/컨트롤러 테스트에 더해 `ToiletRegionMySqlTest`가 실제 V8 migration과 Spring Data native projection을 사용한다. 시→구/세종, nullable 주소, 미검증 4상태, 좌표·주소 변경 8경로에서 stale 지역정보 비노출을 검증한다. 전체 SEO 선택 테스트 23건(사이트맵 포함)이 Linux CI에서 통과했다.
- Web `feature/nextjs-toilet-seo`, WBS [toilet-web #186](https://github.com/toilet-project/toilet-web/issues/186)과 연동한다. 이 브랜치 push는 main 배포가 아니다.

## 운영 읽기 전용 사전 점검

2026-09-05 API 컨테이너의 기존 DB 계정으로 조회 권한을 확인했다. 전체 화장실 53,582건, current view 51,985건. `13448`은 대전광역시/유성구, `28654`는 충청남도/천안시 서북구, `14766`은 경기도/수원시 영통구, `45938`은 세종(하위 계층 null), `78`은 NO_COORDINATE로 current view 미포함이다.

상세 지역조회 EXPLAIN은 toilet/region 양쪽 PRIMARY의 const 접근(각 1행)을 사용한다. DB 쓰기·DDL·지오코딩 호출은 없었다. 운영 배포/preview 응답 결과는 WBS #186 및 Web `docs/nextjs-workers-preview-setup.md`에 기록한다. 배포 후 위 표본의 실제 API와 서버 HTML을 다시 확인한다.
