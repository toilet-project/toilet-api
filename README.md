# 급똥 API

급똥 서비스의 공중화장실 조회 REST API입니다.

- 서비스: <https://geupddong.com>
- 운영 API: <https://api.geupddong.com>
- 상태 확인: <https://api.geupddong.com/api/health>
- API 명세: [docs 저장소](https://github.com/toilet-project/docs/blob/main/api_spec.md)

## 제공 기능

- 지도 Bounding Box·줌 레벨 기반 화장실 마커 및 클러스터 조회
- 화장실 상세 정보 조회
- 표준 성공/오류 응답

## 기술 및 실행 환경

- Java 21 (Eclipse Temurin), Spring Boot, MySQL, Docker
- 운영 환경은 Mini PC의 Docker Compose에서 Nginx 뒤 내부 포트로 실행됩니다.
- 데이터베이스 및 외부 API 설정은 환경 변수로만 주입하며 `.env`는 커밋하지 않습니다.

## 배포

`main` 반영 시 GitHub Actions가 Docker 이미지를 빌드·배포하고 Mini PC에서 컨테이너를 갱신합니다.
배포 구성과 환경 변수 이름은 [운영 문서](https://github.com/toilet-project/docs/blob/main/operations.md)를 참고하세요.
