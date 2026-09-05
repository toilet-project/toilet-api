# 운영 웹 캐시 전환 사전 검사

2026-09-06 · 개발 브랜치 · 운영 API 배포 및 전송 대상 변경 미실행

## 문제와 변경

기존 배포는 `WEB_CACHE_ORIGIN`을 바꿔도 서명 키를 항상 `WEB_CACHE_REVALIDATION_SECRET`에서 가져왔다. 운영 Worker에는 preview와 별도 키를 준비했으므로 주소만 전환하면 인증이 실패한다.

배포 전 검사가 아래 고정 조합을 확인하며, 실제 환경변수 주입도 같은 선택식을 사용한다.

| 전송 주소 | GitHub Secret | 컨테이너 변수 |
| --- | --- | --- |
| `https://preview.geupddong.com` | `WEB_CACHE_REVALIDATION_SECRET` | `WEB_CACHE_REVALIDATION_SECRET` |
| `https://geupddong.com` | `WEB_CACHE_PRODUCTION_REVALIDATION_SECRET` | `WEB_CACHE_REVALIDATION_SECRET` |

키 **이름**을 먼저 선택한 다음 해당 secret을 조회한다. 운영 키 값이 비어 있을 때 preview 키 값으로 돌아가는 fallback은 없다. 전송 활성 상태에서 키/주소가 빠졌거나 조합이 틀리면 Docker 이미지 업로드·SSH·컨테이너 재시작 전에 실패한다. 주소의 하위 경로, 다른 프로토콜, 사용자정보, 유사 도메인, 공백도 거부한다. 로그에는 키 대신 유효 여부와 preview/production만 출력한다.

현재처럼 전송을 비활성화하고 주소/키를 비워 두는 설정은 지원한다. 제공된 값에 줄바꿈이나 셸 해석 문자가 있으면 비활성 상태라도 허용하지 않는다. 이 검사는 캐시 설정만 대상으로 하며 기존 모든 배포 secret의 안전성을 검증한다고 주장하지 않는다.

## 검증

`node --test scripts/web-cache-deployment-policy.test.mjs`: 정상 두 대상, 비활성, 주소 누락, 운영 키 누락, 키 이름 불일치, 유사 주소, 잘못된 플래그/키 문자, workflow 선택식 일치 등 10개 테스트.

실제 키가 들어가는 preflight는 승인된 main 배포에서 실행한다. 단위 테스트에서는 테스트 문자열만 사용하며 실키·Cloudflare·운영 DB에 연결하지 않는다.

## 승인 후 실제 전환 — 아직 실행하지 않음

1. 비공개 운영 Worker, 전용 R2/D1, 서명 키 및 해당 빌드 검증 상태를 확인한다.
2. 본 도메인 전환과 API 재배포를 승인받는다. 기존 Pages 복구 배포와 도메인 설정을 보존한다.
3. 기존 캐시 outbox의 미전송/실패 이벤트를 점검한다. 큐를 삭제하거나 임의 완료 처리하지 않는다.
4. 승인된 시간대에 웹 도메인 연결과 `WEB_CACHE_ORIGIN`을 운영 주소로 바꾸고 이 사전 검사를 통과한 API를 배포한다. 운영 주소와 키를 함께 전환한다.
5. 원본 화장실을 변경하지 않는 승인된 확인 이벤트로 Spring → 운영 Worker → D1 저장 → ACK를 검증하고 해당 상세의 캐시 재생성을 확인한다.
6. Discord 점검 대상도 승인된 운영 주소로 전환한다. preview만 보고 운영 감시 완료로 간주하지 않는다.
7. 실제 로그인/쿠키/제보 복귀, robots·canonical·사이트맵을 확인한 뒤 완료한다.

롤백 시 전송 origin을 preview로 되돌리면 기존 preview 키가 선택된다. 기존 키/큐를 보존해야 한다. API/Worker 상태가 불확실하면 전송을 비활성화해 큐를 유지하고 진단한다. 웹 도메인 롤백은 별도의 Pages 복구 절차이며 이 검사만으로 자동 복구되지 않는다.

현재 비공개 운영 Worker로 향하는 실제 HTTP 서명 검증은 아직 남아 있다. 단위 테스트 성공을 운영 연결 성공으로 기록하지 않는다.

참고: [GitHub contexts의 index 접근](https://docs.github.com/en/actions/reference/workflows-and-actions/contexts).
