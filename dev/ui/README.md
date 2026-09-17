# 배포 UI 검증

실제 콘솔은 웹 전용 `/deployments.html`과 배치 전용 `/batch.html`로 분리되어 있다.
기존 로그인 토큰을 사용하며 앱 등록, 설정 저장, 배포, 승인, 중단, 롤백을 실제 배포 API로 요청한다.
배치는 배포 이력과 실행 이력이 별도다. 배포는 CronJob 템플릿만 변경하며 실행 이력 탭에서
현재 배포된 CronJob으로 Job을 수동 실행한다. 스케줄 Job은 수집하지 않는다.
서버 capability 조회는 인증이 필요하고, HTML 자체에는 인증 정보나 Kubernetes 스냅샷을 포함하지 않는다.

## 클러스터 없는 UI 미리보기

```sh
node dev/ui/preview.mjs
```

`http://127.0.0.1:4173/deployments.html`에서 확인한다. 기본 포트가 사용 중이면
`PORT=4174 node dev/ui/preview.mjs`처럼 별도 포트를 지정한다.
배치 화면은 같은 서버의 `/batch.html`이다. 샘플은 배포된 v3와 저장된 v4를 구분하며
배포 전 수동 실행은 v3, v4 배포 후 수동 실행은 v4를 이력에 남긴다.

이 서버는 **Kubernetes에 접근하지 않는 개발용 모의 API**다. 상단에 미리보기 표시가 나온다.
샘플 앱·설정·실행은 메모리에만 저장되며 재시작하면 초기화된다. 실제 Pod, Ingress, Service,
Temporal Workflow를 생성하거나 요청 비율을 변경하지 않는다. preview 접속 명령도 예시이며
해당 Service가 실제 클러스터에 생성된 것은 아니다. 이 서버는 loopback 주소에서만 실행한다.
제품 JAR에는 `dev/ui`가 포함되지 않고 자동으로 데모 데이터로 전환하는 기능도 없다.
미리보기 상태를 별도로 내보낸 경우 `UI_PREVIEW_STATE`에 JSON 파일 경로를 지정해 재시작할 수 있다.
파일은 `apps` 배열과 앱 ID별 `configurations`·`runs` 객체, 선택적인 `executions`·`templates` 객체를 포함한다. 저장 설정은 최신 하나만
복원하고 과거 실행 설정은 실행에 보존한다. 실제 Kubernetes 상태 파일이 아니다.

## 브라우저 테스트

초기 샘플 상태의 별도 미리보기 서버를 실행하고 Node에서 `playwright`를 불러올 수 있는 환경에서 실행한다.
테스트가 설정·이력을 변경하므로 사용 중인 미리보기와 다른 포트를 쓰고, 재검증 전 테스트 서버를 재시작한다.

```sh
node dev/ui/check.mjs
```

별도 설치된 Playwright를 사용할 때는 해당 `node_modules`를 `NODE_PATH`에 지정한다.
서버 포트를 바꿨다면 `UI_URL=http://127.0.0.1:4174`를 지정한다.

데스크톱 1440px·모바일 390px에서 화면을 촬영하고 페이지 가로 넘침을 검사한다.
블루그린 승격·롤백, 앱 등록, 설정 저장, 기본/가중치 카나리, 웹/배치 목록 분리, 그룹 배포와
수동 Job 이력의 독립성, 실제 배포 이미지 선택, 최신 설정 한 개 유지, 실행 권한 오류와 배포의 분리,
응답 유실 후 동일 requestId 재시도, JSON 입력 오류, 403·401 처리를 검증한다.
결과 이미지는 Git에서 제외된 `build/ui/`에 저장한다. 실제 Kubernetes 제어는 이 테스트의 범위가 아니다.

제품 서버의 인증·capability 응답·정적 화면 제공은 `./gradlew test`에서 별도로 검증한다.
