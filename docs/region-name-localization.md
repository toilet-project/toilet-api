# 행정구역 다국어 이름

행정구역의 식별자는 한국어 이름이 아니라 `sigungu_code` 5자리다. `region_sigungu_reference`는 공식 코드와 한국어 이름을 저장하고, `region_sigungu_translation`은 코드·언어별 표시 이름을 저장한다. 시설별 `toilet_region_assignment`와 `toilet_region_decision`에는 코드만 둔다. `current_toilet_region`의 이름은 기준표와의 조인 결과다.

V31은 번역 테이블, V32는 287개 기준 지역의 영어·일본어·중국어 간체·중국어 번체(대만/홍콩) 1,435행을 넣는다. 기존 `toilet_region`의 이름·코드는 읽기 경로의 기준이 아닌 과거 이중 기록이며, [W7 레거시 제거](https://github.com/toilet-project/toilet-api/issues/128)의 배치·불일치 관찰 및 복구 조건을 마친 뒤 제거한다. 이 변경은 기존 테이블을 삭제하거나 운영 데이터에 직접 적용하지 않는다.

`scripts/data/regions/`의 두 JSON은 Google Cloud Translation Basic으로 2026-09-22에 생성한 감사 자료다. 첫 파일은 지명 단독, 둘째 파일은 시·도 이름을 붙여 동명이 지역을 구분한 결과다. 호출 대상은 중복 제거한 기준표 이름만이며 두 번째 실행은 13,545자 상한으로 제한했다. `build_region_name_assets.py`가 두 결과를 코드로 대조하고 잘못 번역된 소수의 지명을 정리해 SQL과 웹용 JSON을 함께 생성한다. `reviewed=false`는 공식 외국어 표기 전체에 대한 사람 검수가 끝났다는 뜻으로 해석하지 않는다.

웹의 `data/regions/names.json`은 DB 기준표와 동일한 코드의 배포용 스냅샷이다. 현재 지도 경계에 실제로 있는 256개만 포함한다. 한국어는 경계 자료의 기존 이름을 사용하고 다른 언어는 코드로 이 스냅샷을 찾는다. 새 코드가 들어왔으나 번역이 아직 없다면 기존 한국어로 안전하게 표시한다. 향후 번역 정정은 이미 적용된 마이그레이션을 수정하지 않고 새 마이그레이션과 웹 스냅샷에 함께 반영한다.

운영 적용 시에는 `Region translation production read-only check`를 `before`로 실행해 V17/V18과 번역 대상 코드 287개가 실제 DB에 있는지 확인한다. V31/V32는 기존 테이블을 건드리지 않는 새 테이블과 행만 추가하므로 기존 API 이미지가 해당 테이블을 사용하지 않는 한 이미지 롤백과 호환된다. 계정 기능이 active인 운영 환경에서는 paused 전용 `deploy.yml`을 재시도하거나 계정 플래그를 바꾸지 않는다. 승인된 커밋의 `Build approved account service image`로 이미지를 만든 뒤, digest와 현재 API/배치 커밋을 고정하여 `Preserve account state during image rollout`의 `apply-image`로 교체한다. 이 경로는 기존 Compose 이미지 한 항목만 바꾸고 계정 설정을 유지한다. 교체 후 같은 읽기 전용 검사를 `after`로 실행해 V31/V32 성공과 287개 코드 × 5개 언어 = 1,435행을 확인한다. 실패 시 자동 재시도하지 않고 운영 상태와 Flyway 이력을 읽기 전용으로 확인한다.

생성 명령 예시:

```sh
python3 scripts/build_region_name_assets.py \
  scripts/data/regions/context-20260922.json \
  scripts/data/regions/isolated-20260922.json \
  ../toilet-web/data/regions/sgg.json \
  src/main/resources/db/migration/V32__seed_region_sigungu_translation.sql \
  ../toilet-web/data/regions/names.json
```
