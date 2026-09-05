# 공개 상세 API의 검증된 지역정보

2026-09-05 · Next.js 상세 SEO 연동용 최소 확장 · 운영 미반영

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
- 서비스/컨트롤러 선택 테스트 14건 통과. 기존 주소 보존, 지역 존재/없음, 시→구 필드, 공개 JSON 계약 포함. 운영 MySQL view의 실제 쿼리와 실행 계획 검증은 배포 전 별도 수행해야 한다.
- Web `feature/nextjs-toilet-seo`, WBS [toilet-web #186](https://github.com/toilet-project/toilet-web/issues/186)과 연동한다. 이 브랜치 push는 main 배포가 아니다.
