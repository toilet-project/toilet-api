# 관리자 행정구역 검토 API

## 범위와 배포 순서

- API feature와 관리자 feature를 순서대로 배포합니다. 사용자 React/Next.js 코드는 변경하지 않습니다.
- V8 `toilet_region`, V10 `toilet_region_assessment_history`가 선행되어야 합니다. 이번 변경의 추가 DDL·인덱스·시크릿은 없습니다.
- 기존 데이터의 일괄 수정, 지역 코드 직접 입력, 강제 VERIFIED 처리는 제공하지 않습니다.

## API 계약

모든 경로는 `/api/admin/v1/regions` 아래이며 ADMIN 권한이 필요합니다. 비로그인 401, 일반 사용자 403입니다.

| 메서드·경로 | 내용 |
|---|---|
| GET `/` | 상태·검색어 기반 목록. `page=0&size=20`, 최대 100건 |
| GET `/{id}` | 현재 주소·좌표, 최근 판정 당시 주소·지역 코드·원본 JSON 근거 |
| GET `/{id}/history` | 개별 판정 이력. `page=0&size=10`, 최신 판정부터 |
| POST `/{id}/coordinates` | 좌표 확정. 사유와 조회 당시 `expectedLocation` 필수 |

`status`는 REVIEW(기본), ALL, VERIFIED, MISMATCH, ADDRESS_UNVERIFIED, REVERSE_FAILED,
NO_COORDINATE, STALE, UNASSESSED를 지원합니다. REVIEW는 VERIFIED를 제외합니다.
`keyword`는 화장실명·관리번호·두 주소에서 찾으며 최대 100자, SQL 와일드카드는 일반 문자로 검색합니다.
목록은 최근 판정 시각 오름차순, 동률이면 화장실 ID 순입니다. 목록에 대용량 근거 JSON이나 전체 이력은 포함하지 않습니다.

## 안전한 좌표 확정

```json
{
  "latitude": 36.35,
  "longitude": 127.38,
  "note": "현장 위치 확인",
  "expectedLocation": {
    "latitude": null,
    "longitude": null,
    "roadAddress": null,
    "jibunAddress": "조회 시 표시된 지번주소"
  }
}
```

1. 화장실 행 잠금 후 조회 당시 주소·좌표와 현재 값을 비교합니다. 다르면 409이며 외부 조회·수정을 하지 않습니다.
2. 기존 `CoordinateQualityService.correctToilet`을 재사용하여 서버가 좌표로 도로명·지번을 각각 확인합니다.
3. 주소 조회 실패 시 트랜잭션을 롤백합니다. 성공 시 기존 ADMIN_CONFIRMED 보호, 좌표 변경 이력, 관리자 감사 로그를 남깁니다.
4. 기존 정규화 워커가 새 소스를 재판정합니다. 대기량·일일 API 예산으로 지연될 수 있으며 화면에서 새로고침으로 확인합니다.

주소나 좌표가 달라지면 기존 VERIFIED 결과도 STALE로 표시합니다. 문자열은 바이트 기준으로 비교하고,
VERIFIED의 실제 평가 좌표도 확인합니다. 현재 좌표가 누락되면 NO_COORDINATE, 판정 행이 없으면 UNASSESSED입니다.
상세의 이전 근거는 조사용 자료로만 표시합니다. 변경 없이 공급자 코드 충돌을 해결할 수 없는 항목은 미해결로 유지합니다.

## 조회·성능

기존 지역 상태/판정시각 인덱스와 이력의 `(toilet_id, checked_at, assessment_id)` 인덱스를 유지합니다.
현재 소스와의 비교로 계산하는 상태 필터는 단순 status 인덱스만으로 처리되지 않을 수 있습니다.
운영 크기의 SELECT 검증에서 집계/목록 왕복은 각각 약 314/270ms였습니다(단일 측정, SLA 아님).
대규모 증가 시 실행계획과 p95 지연을 다시 측정하고 필요할 때만 인덱스·큐 구조를 변경합니다.

## 검증과 남은 확인

- API 단위/보안 테스트: 401·403, ADMIN 목록, 잘못된 상태, snapshot 필수, 동시 좌표/주소 변경 거절, nullable 좌표, 숫자 scale.
- 운영 MySQL SELECT 전용: 원본 서비스의 CASE SQL을 그대로 추출하여 합성 10건, 실제 목록·집계·이력 조회 검증. DB 쓰기 0.
- 관리자 모의 브라우저: 비로그인/일반 사용자 차단, 데스크탑/모바일, 주소 후보 수동 선택, 필수 사유, 재조회 응답 역전, 초기화, 페이지네이션, 409 처리.
- 실제 지도 SDK와 운영 제보 승인 → 워커 재판정 E2E는 배포 후 별도 확인이 필요합니다. 임의의 운영 화장실을 테스트로 수정하지 않습니다.
