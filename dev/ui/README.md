# 배포 UI 검증

실제 콘솔은 Spring Boot 앱의 `/deployments.html`이다. 기존 로그인 토큰을 사용하며
앱 등록, 설정 저장, 실행, 승인, 중단, 롤백을 실제 `/api/v2/deployment-applications` API로 요청한다.
서버 capability 조회는 인증이 필요하고, HTML 자체에는 인증 정보나 Kubernetes 스냅샷을 포함하지 않는다.

## 클러스터 없는 UI 미리보기

```sh
node dev/ui/preview.mjs
```

`http://127.0.0.1:4173/deployments.html`에서 확인한다. 기본 포트가 사용 중이면
`PORT=4174 node dev/ui/preview.mjs`처럼 별도 포트를 지정한다.

이 서버는 **Kubernetes에 접근하지 않는 개발용 모의 API**다. 상단에 미리보기 표시가 나온다.
샘플 앱·설정·실행은 메모리에만 저장되며 재시작하면 초기화된다. 실제 Pod, Ingress, Service,
Temporal Workflow를 생성하거나 요청 비율을 변경하지 않는다. preview 접속 명령도 예시이며
해당 Service가 실제 클러스터에 생성된 것은 아니다. 이 서버는 loopback 주소에서만 실행한다.
제품 JAR에는 `dev/ui`가 포함되지 않고 자동으로 데모 데이터로 전환하는 기능도 없다.

## 브라우저 테스트

미리보기 서버가 실행 중이고 Node에서 `playwright`를 불러올 수 있는 환경에서 실행한다.

```sh
node dev/ui/check.mjs
```

별도 설치된 Playwright를 사용할 때는 해당 `node_modules`를 `NODE_PATH`에 지정한다.
서버 포트를 바꿨다면 `UI_URL=http://127.0.0.1:4174`를 지정한다.

데스크톱 1440px·모바일 390px에서 화면을 촬영하고 페이지 가로 넘침을 검사한다.
블루그린 승격·롤백, 앱 등록, 설정 저장, 기본/가중치 카나리, 그룹 배치, 응답 유실 후
동일 requestId 재시도, JSON 입력 오류, 403·401 처리를 검증한다.
결과 이미지는 Git에서 제외된 `build/ui/`에 저장한다. 실제 Kubernetes 제어는 이 테스트의 범위가 아니다.

제품 서버의 인증·capability 응답·정적 화면 제공은 `./gradlew test`에서 별도로 검증한다.
