# 프로필 사진 저장·접근 설계

[WEB #203](https://github.com/toilet-project/toilet-web/issues/203)의 API 구현이다. 코드는 기본 OFF이며 이 문서만으로 운영 활성화나 고지 완료를 뜻하지 않는다.

## 사용자 동작

- 신규 카카오 회원이 카카오 가입 화면에서 선택 항목인 프로필 사진 제공에 동의한 경우에만 가입 처리 중 한 번 가져온다. 기존 회원 로그인, 계정 복구, 이후 재로그인에서는 카카오 사진을 확인하거나 갱신하지 않는다.
- 사진 제공을 거부하거나 가입 시 가져오기에 실패해도 가입과 서비스 이용은 정상 완료된다. 실패한 카카오 사진을 다음 로그인에서 다시 가져오지 않는다.
- 신규·기존 회원 모두 마이페이지의 프로필 수정 화면에서 JPEG·PNG·정지 WebP 사진을 직접 등록, 교체, 삭제할 수 있다.
- 사진은 기본 비공개다. 본인은 비공개 사진을 볼 수 있고, 공개를 선택하면 공개 리뷰의 작성자 사진에도 표시된다. 비공개 전환은 저장 파일을 유지하면서 공개 접근을 즉시 차단한다.
- 사진 삭제와 회원 탈퇴는 DB 연결을 즉시 끊고 파일을 비동기로 삭제한다. 계정 복구용 3개월 선택 보관에는 사진을 포함하지 않는다.

## 가입 시 1회 가져오기

OAuth 성공 처리기가 이번 콜백에서 새로 만든 카카오 계정인지 구분한다. 새 계정이며 `profile_image_needs_agreement=false`인 경우에만 허용된 카카오 이미지 주소를 최대 10분 동안 프로세스 메모리에 보관한다. 주소를 DB, Redis, 로그에 기록하지 않으며 최대 256명을 넘겨 대기시키지 않는다.

급똥의 필수 약관 동의가 이미 끝난 회원은 OAuth 성공 처리에서, 약관 동의가 필요한 회원은 동의 완료 직후 한 번 처리한다. 단일 사진 작업 스레드와 최대 32개 대기열을 사용한다. 서버 재시작, 만료, 다운로드·변환·저장 실패 시 사진만 생략하고 사용자는 마이페이지에서 직접 등록할 수 있다.

## 이미지 변환과 저장소

- 가입 사진은 허용한 Kakao HTTPS 호스트만 요청한다. DNS 결과가 사설·루프백·링크 로컬·멀티캐스트 주소면 거부하고 리다이렉트를 따르지 않는다.
- 외부 다운로드와 직접 업로드 원본은 2MiB 이하만 받는다. JPEG·PNG·정지 WebP, 최대 400만 픽셀만 허용한다.
- 원본은 JVM과 변환 프로세스 메모리에서만 사용하고 처리 후 바이트 배열을 지운다. 변환기는 stdin/stdout을 사용해 임시 파일을 만들지 않는다.
- EXIF 방향을 적용하고 중앙 정사각형으로 자른 뒤 최대 256×256, 품질 80 WebP로 만든다. 작은 이미지는 확대하지 않으며 결과가 100KB를 넘으면 실패한다. EXIF·ICC·원본 URL은 저장하지 않는다.
- 변환 프로세스는 주소 공간 256MiB, CPU 4초, 실행 8초 제한을 사용한다. 동시에 한 건만 변환하고 추가 동시 요청은 제한 응답으로 종료한다.
- 변환본만 미국 관할(`US jurisdiction`) 비공개 R2 버킷 `geupddong-profile-photos-us`에 무작위 UUID 경로로 저장한다. 공개 도메인과 `r2.dev`는 사용하지 않는다.

## API 계약

| 경로 | 동작 |
| --- | --- |
| `GET /api/v1/auth/me/photo` | 사진 보유 여부, 공개 여부, 현재 이미지 버전 반환. 기능 OFF이면 `available:false` |
| `PUT /api/v1/auth/me/photo` | JPEG·PNG·WebP 원본을 직접 등록하거나 교체. `Content-Length` 필수, 최대 2MiB |
| `PATCH /api/v1/auth/me/photo` | JSON `publicPhoto`로 공개 범위 변경. 저장된 사진이 있어야 공개 가능 |
| `DELETE /api/v1/auth/me/photo` | 사진 연결 제거 및 비공개 파일 삭제 예약 |
| `GET /api/v1/auth/me/photo/image?version=…` | 본인의 현재 버전 사진 반환 |
| `GET /api/v1/toilets/{toilet}/reviews/{review}/photo` | ACTIVE 작성자가 사진 공개를 선택한 공개 리뷰에만 사진 반환 |

쓰기 요청은 인증과 허용 Origin을 요구한다. 사진은 R2 주소나 서명 URL을 브라우저에 노출하지 않고 API가 전달한다. 공개 리뷰 URL에도 회원 ID를 넣지 않는다. 설정과 사진 응답은 `no-store`이며 Next.js 이미지 최적화 캐시를 사용하지 않는다. R2를 읽은 뒤에도 현재 DB 권한과 버전을 다시 확인하므로 삭제·비공개 전환 뒤 시작한 요청은 이전 URL로 사진을 받을 수 없다. 이미 완료된 다운로드나 다른 기기에 남은 사본은 회수할 수 없다.

이 구조에서는 미니 PC API가 작은 WebP를 전달한다. 추후 Worker 전송으로 바꾸더라도 현재 공개 상태 확인을 생략하거나 장기 서명 URL을 발급해서는 안 된다.

## 삭제와 경합 처리

사진 행의 세대 번호와 회원 `auth_version`을 사용한다. 직접 업로드, 공개 범위 변경, 삭제, 탈퇴 전에 시작한 비동기 저장은 최신 결정을 덮어쓸 수 없다. 가입 사진은 회원이 아직 사진 행을 만들지 않았고 `auth_version=0`일 때만 저장 티켓을 얻는다. 사용자가 먼저 직접 올리거나 삭제 결정을 하면 늦은 가입 작업은 연결되지 않는다.

R2 쓰기 전에 객체 키를 사진 객체 대장에 기록한다. 대장에는 회원 ID나 원본 주소가 없다. R2 쓰기 실패, DB 연결 실패, 프로세스 종료로 참조되지 않은 파일도 추후 찾을 수 있다. 생성 후 5분이 지나고 어떤 사진 행에서도 참조하지 않는 객체를 1분마다 최대 5개씩 삭제한다. 접근 차단과 실제 파일 삭제 완료는 구분하며, 삭제 실패는 대장에 남겨 재시도한다.

되돌릴 때는 웹 기능을 먼저 끈다. 저장된 사진이 있으면 API의 R2 설정과 삭제 작업을 유지한 채 파기한다. 정리 전에 자격증명을 제거하지 않는다. 회원 DB를 복원하는 경우에도 계정 파기 대장을 재적용하고 사진 연결과 R2 잔여 객체를 검사한다.

## 개인정보 안내 상태

카카오 앱에는 프로필 사진을 선택 제공 항목으로 두었다. Cloudflare 국외이전 정보는 미국, `Cloudflare, Inc.`, `legal@cloudflare.com`으로 등록했고 카카오 동의 미리보기에서 닉네임 필수, 국외이전 필수, 프로필 사진·이메일 선택으로 표시되는 것을 확인했다.

웹 개인정보 처리방침 초안에는 다음을 명시한다.

- 이전 항목: 최대 256×256 WebP 변환본
- 국가·방법: 미국, 가입 시 선택 제공 또는 직접 등록 때 암호화 통신으로 전송
- 이전받는 자: Cloudflare, Inc. 및 연락처
- 목적·기간: 본인 프로필과 공개 선택 시 리뷰 작성자 사진, 삭제·탈퇴 때까지
- 거부 방법·영향: 카카오 사진 제공과 직접 등록을 하지 않아도 기본 아바타로 회원 기능 이용 가능

이 항목은 운영 전에 공지·시행 시각을 확정해야 하는 검토안이다. 기능 플래그를 켜기 전에 실제 공개 정책과 화면을 배포하고 가입·직접 업로드의 수집 시점에 유효한 안내가 보장되는지 확인한다.

## 배포 전 확인

- 기본값 `PROFILE_PHOTO_ENABLED=false`, 웹 `NEXT_PUBLIC_PROFILE_PHOTO_ENABLED=false`, Kakao 기본 scope `profile_nickname, account_email`을 유지한다.
- 미국 관할 전용 버킷에만 읽기·쓰기·삭제할 수 있는 전용 자격증명을 발급한다. 미니 PC의 별도 권한 0600 환경 파일에 저장하고 Redis, 공통 `.env`, PR, 로그에 넣지 않는다.
- 환경 변수는 `PROFILE_PHOTO_R2_ENDPOINT`, `PROFILE_PHOTO_R2_BUCKET`, `PROFILE_PHOTO_R2_ACCESS_KEY_ID`, `PROFILE_PHOTO_R2_SECRET_ACCESS_KEY`다. 미국 관할 endpoint는 `<account-id>.us.r2.cloudflarestorage.com`, 버킷은 `geupddong-profile-photos-us`만 허용한다.
- 전용 점검 스크립트로 합성 WebP 쓰기·동일 바이트 읽기·익명 접근 거부·다른 버킷 거부·삭제를 확인한다. 회원 사진은 점검에 사용하지 않는다.
- V13을 격리 MySQL과 배포 후보에서 검증한다. Pillow/WebP 변환 검사는 `PROFILE_PHOTO_CONVERTER_TEST=true`인 전용 CI에서 실행한다.
- API 이미지와 개인정보 처리방침을 먼저 배포한 뒤 서버에서 `PROFILE_PHOTO_ENABLED=true`와 Kakao `KAKAO_LOGIN_SCOPES=profile_nickname,account_email,profile_image`를 함께 적용한다.
- 실제 신규 카카오 계정의 선택 동의·비동의, 직접 등록·교체·삭제, 공개 리뷰 표시·비공개 차단, 탈퇴·R2 삭제를 검증한다. 마지막에 웹 사진 기능을 켜고 모바일 인수를 마친다.

`docker compose config` 전체에는 비밀값이 포함될 수 있으므로 로그나 PR에 붙이지 않는다. 이 문서 기록은 배포 승인이나 운영 플래그 변경으로 해석하지 않는다.

참고: [Kakao 사용자 정보](https://developers.kakao.com/docs/ko/kakaologin/rest-api), [Kakao 개인정보 국외이전](https://developers.kakao.com/docs/ko/kakaologin/prerequisite#transfer-of-personal-data), [R2 데이터 위치](https://developers.cloudflare.com/r2/reference/data-location/), [Cloudflare DPA](https://www.cloudflare.com/cloudflare-customer-dpa/), [R2 요금](https://developers.cloudflare.com/r2/pricing/).
