# Deployment 실행과 신규 Pod 테스트

DeployDock은 기존 `apps/v1 Deployment`와 `Service`를 직접 사용한다. Argo Rollouts나
DeployDock 상위 CRD 설치는 필요하지 않다. 실행 상태와 복구용 스냅샷은
`deploydock-system`의 ConfigMap에 저장한다.

파일별 역할과 내부 호출·상태 전이는 [배포 코드 읽기](DEPLOYMENT_CODE.md)를 참고한다.
Spring 서버 없이 사용하는 방법은 [라이브러리 가이드](deployment-library/README.md)에 있다.
아래 JWT·사용자 권한·주기 실행 설정은 루트 서버 앱 기준이다. 핵심 라이브러리는
호출자가 제공한 KubernetesClient와 재조정 스케줄을 사용한다.

## 준비

웹 콘솔 `/deployments.html`에서 로그인한 뒤 앱 등록 → 배포 설정 → 설정 저장 → 배포 실행으로
진행한다. 웹 전용 화면이며 배포 이력은 4초마다 갱신한다. Pod 로그나 지표가 아니라 서버의 배포 상태를 표시한다.
승인 대기 시 preview Service와 port-forward 명령을 확인하고 승격 또는 가중치 단계를 승인한다.
UI가 port-forward 프로세스를 자동 실행하지는 않는다. 명령 실행 후 로컬 preview 링크에 접속한다.
가중치 어댑터와 Temporal은 서버 capability에 등록된 경우에만 선택할 수 있다.
진행 중인 실행이 있거나 상태 갱신에 실패하면 신규 실행·상태 변경 버튼을 비활성화한다.
서버는 UI 버튼과 별개로 매 요청의 권한과 상태를 다시 검사한다.

저장된 배포 설정은 앱마다 최신 버전 한 개만 유지한다. 다시 저장하면 기존 설정을 교체하며,
웹은 별도의 리비전 목록을 보존하며 과거 설정 ID로도 새 배포를 실행할 수 있다. 배치는 최신 설정 ID만 허용한다. 이미 시작한 실행에는 당시 설정을 따로 보관하므로
이후 저장이 진행 중 배포·승인·롤백 기준을 바꾸지 않는다. 기존 다중 설정 데이터는 조회 시 최신만
반환하고, 다음 저장 시 과거 실행의 설정을 실행 이력으로 옮긴 뒤 최신 한 개로 정리한다.

## 웹 리비전 배포

웹 화면의 **리비전** 탭에서 이미지·전략·Pod 수·저장 시각을 확인하고 **이 리비전 실행**을 누른다.
설정 저장마다 불변 리비전을 별도 보관하며, 아직 배포하지 않은 리비전도 선택할 수 있다.
최신 저장 설정은 계속 하나만 표시한다. 과거 리비전 실행이 최신 설정을 덮어쓰거나 리비전 번호를 올리지는 않는다.

`GET /api/v2/deployment-applications/{id}/revisions`는 웹 리비전 목록을 최신 번호순으로 반환한다.
선택한 설정 ID를 기존 `POST /runs`의 `configurationId`로 보내면 **새 run ID와 새 배포 이력**이 생긴다.
그 시점의 클러스터 상태를 다시 스냅샷으로 저장하고, 선택한 리비전의 이미지·Pod 수·전략을 적용한다.
블루그린·카나리는 과거 리비전을 선택해도 새 preview 준비와 승인이 필요하다.
복원하는 것은 저장된 배포 설정이며 과거 Secret·ConfigMap·DB·전체 PodSpec을 되감는 기능이 아니다.
이미지 태그가 변경 가능한 경우 과거와 같은 이미지 내용까지 보장하지 못하므로 digest 고정을 권장한다.

웹에는 **롤백 버튼이 없으며 새 ROLLBACK API 요청도 거부**한다. 실패·중단 시 자동 복구는 그대로다.
기존 배포 이력은 수정하지 않는다. 이미 접수됐던 구버전 롤백의 재개와 과거 ROLLED_BACK 이력 해석만 호환한다.
리비전 재배포에도 현재 권한·어댑터·동시 배포 제한·requestId 중복 방지를 다시 적용한다.
이전 데이터는 최신 설정과 실행 당시 설정에서 리비전을 복구한다. 이전 버전에서 덮어쓴 뒤 한 번도
배포하지 않아 어디에도 남지 않은 설정은 복구할 수 없다.

- 웹 앱 등록 시 `name`은 기존 Deployment 이름이다. Service 이름이 다르면 `serviceName`을 지정한다.
- 컨테이너가 여러 개면 변경할 `containerName`을 지정한다. 다른 컨테이너, 환경변수, 볼륨, probe는 유지한다.
- 원본 Deployment는 준비 완료 상태여야 한다. 블루그린과 카나리는 원본과 신규 Pod를 함께 실행할 자원이 필요하다.
- 운영 Service와 Deployment의 `matchLabels`에는 공통 키가 있어야 한다. 신규 버전에서 이 값을 바꿔 기존 ReplicaSet 및 Service와 격리한다.
- 다른 Service가 신규 Pod를 선택할 수 있으면 실행을 거부한다. `publishNotReadyAddresses=true`도 허용하지 않는다.
- 실행 사용자와 DeployDock ServiceAccount 모두 대상 리소스 수정 권한이 필요하다. 네임스페이스 조회 권한만으로는 배포할 수 없다.
- 기본 RBAC와 함께 `k8s/examples/deployment-access.yaml`을 대상 네임스페이스·사용자에 맞춰 적용한다. preview port-forward에는 별도로 `pods/get`, `pods/list`, `pods/portforward/create` 권한이 필요하다.
- 같은 리소스를 다른 애플리케이션이 동시에 관리하지 않도록 `deploydock.io/application` annotation으로 소유권을 기록한다.

## 블루그린 흐름

1. 기존 Deployment의 Pod 설정을 복제하고 지정 컨테이너 이미지를 변경한다.
2. 실행별 신규 Deployment와 ClusterIP preview Service를 만든다. 운영 Service는 그대로 둔다.
3. Deployment의 관측 generation, 전체 updated/available replica, 준비된 EndpointSlice를 확인한다.
4. `AWAITING_APPROVAL`에서 멈춘다. 이때 preview로 신규 Pod를 테스트한다.
5. `PROMOTE`를 받으면 운영 Service를 신규 Pod로 전환하고 EndpointSlice 반영을 기다린다.
6. 신규 Pod가 트래픽을 처리하는 동안 원본 Deployment를 새 이미지로 갱신한다.
7. 원본이 준비되면 운영 Service를 원래 selector로 돌리고 신규 Deployment를 0개로 줄인다.
8. 이 과정이 모두 끝나야 `SUCCEEDED`다. 기존 Deployment와 Service의 이름·UID를 유지한다.

설정 저장 후 다음처럼 실행한다. 응답은 배포 완료를 기다리지 않는다.

```sh
curl -X POST "http://localhost:8080/api/v2/deployment-applications/$APP_ID/runs" \
  -H "Authorization: Bearer $ACCESS_TOKEN" -H 'Content-Type: application/json' \
  -d '{"configurationId":"CONFIGURATION_ID","requestId":"release-2026-09-17-1"}'

curl "http://localhost:8080/api/v2/deployment-applications/$APP_ID/runs" \
  -H "Authorization: Bearer $ACCESS_TOKEN"
```

`requestId`를 같은 설정으로 재전송하면 기존 실행을 반환한다. 한 앱에는 활성 실행을 하나만 허용한다.
응답의 `result.previewService`와 `result.previewPorts`가 접속 정보다. 예를 들어 포트가 80이면:

```sh
kubectl -n team-a port-forward service/PREVIEW_SERVICE 18080:80 --address=127.0.0.1
curl -f http://127.0.0.1:18080/health
```

`/health`는 실제 앱의 테스트 경로로 바꾼다. 신규 Pod의 응답·화면·업무 동작을 확인한 뒤 승인한다.
preview Service는 클러스터 내부에만 생성하며 외부 도메인이나 Ingress는 자동 생성하지 않는다.
port-forward는 개별 Pod 연결 테스트이며 운영 트래픽 비율이나 무중단을 증명하지 않는다.

```sh
curl -X POST "http://localhost:8080/api/v2/deployment-applications/$APP_ID/runs/$RUN_ID/actions" \
  -H "Authorization: Bearer $ACCESS_TOKEN" -H 'Content-Type: application/json' \
  -d '{"action":"PROMOTE","requestId":"approve-release-1"}'
```

## 카나리 흐름

### 기본: 신규 버전 배포와 테스트

Ingress가 없거나 NGINX·Cilium 등 어떤 종류이든 기본 배포 실행기는 Ingress를 조회·변경하지 않는다.
기존 Ingress가 참조하는 운영 Service 이름을 유지하고, 신규 버전은 별도 preview Service로 테스트한다.

```json
{
  "image": "registry.example.com/shop:v2",
  "webStrategy": "CANARY"
}
```

`trafficAdapter`와 `canaryRoute`를 생략하면 `result.trafficMode=PREVIEW_ONLY`다.
어댑터가 서버에 등록돼 있어도 자동 선택하지 않는다. 준비가 끝나면 `AWAITING_APPROVAL`에서
preview를 테스트하고 바로 `PROMOTE`할 수 있다. `ABORT`·실패 복구를 지원하며 과거 버전은 리비전을 선택해 새로 배포한다.
승인 전에는 운영 Service selector를 유지하고, 승격 후에는 블루그린과 같은 전환 과정을 거친다.
이 모드는 운영 트래픽 일부를 신규 버전에 흘리는 가중치 카나리가 아니다.
`canarySteps`는 사용하지 않으며 `ADVANCE`는 거부한다. 신규 Pod가 준비되기 전 승격도 거부한다.

### 선택: 운영 트래픽 비율 조절

운영 요청을 단계별로 분배하려는 경우에만 `trafficAdapter` 또는 기존 `canaryRoute`를 지정한다.
이때 `result.trafficMode=WEIGHTED`이며 핵심 실행기는 `CanaryTrafficAdapter`에 트래픽 제어를 위임한다.
명시한 어댑터가 없으면 설정을 거부한다. 잘못된 어댑터 설정을 preview-only로 자동 변경하지 않는다.
기본 제공하는 선택형 어댑터는 Gateway API `gateway.networking.k8s.io/v1 HTTPRoute`다.
서버에서 사용하려면 `deploydock.deployment.gateway-api.enabled=true`를 지정한다.
이를 지원하는 Gateway controller가 이미 설치되어 있고, HTTPRoute가 운영 Service를 가리켜야 한다.
라이브러리나 서버는 controller를 설치하지 않는다. 환경에 맞는 다른 어댑터를 직접 등록할 수 있다.
일반 Service의 replica 비율을 정확한 요청 비율로 간주하지 않는다.
Ingress NGINX와 서비스 메시용 어댑터는 아직 없다.

가중치 분배 설정 예:

```json
{
  "image": "registry.example.com/shop:v2",
  "webStrategy": "CANARY",
  "trafficAdapter": "gateway-api",
  "trafficOptions": {"routeName": "shop-web"},
  "canarySteps": [10, 50],
  "progressDeadlineSeconds": 600
}
```

기존 `canaryRoute` 필드도 Gateway 어댑터 선택·경로 이름의 호환 별칭으로 유지한다.
다음 설명은 Gateway 어댑터 기준이다. HTTPRoute는 parent 하나, 규칙 하나,
같은 네임스페이스의 운영 Service backend 하나인 구성을 지원한다.
매칭 조건·호스트·필터를 유지하고 backend 가중치만 변경한다.

처음에는 preview 전용으로 준비하고 운영 노출은 0%다. 신규 Pod 테스트 후 `ADVANCE`를 보내면
첫 단계인 10%로 변경한다. HTTPRoute의 최신 generation이 `Accepted`와 `ResolvedRefs`를
만족해야 다시 승인 대기 상태가 된다. 지표나 응답을 확인한 후 다시 `ADVANCE`하면 50%다.
모든 단계를 거친 뒤 `PROMOTE`로 블루그린과 같은 원본 Deployment 갱신·복귀 과정을 완료한다.
각 action에도 고유한 `requestId`를 넣는다. 같은 요청의 재전송은 단계를 추가로 진행시키지 않는다.
가중치는 Gateway로 들어오는 요청에 적용되며 Service 직접 호출에는 적용되지 않는다.
[Gateway API 트래픽 분배 기준](https://gateway-api.sigs.k8s.io/guides/user-guides/traffic-splitting/)을 따른다.

## 중단과 롤백

- 진행 중 `ABORT`: 기존 이미지·Service selector·HTTPRoute backend를 복원하고 신규 Pod를 0개로 줄인다.
- 웹의 이전 버전 적용은 리비전을 선택한 새 배포로 처리한다. 마지막 실행의 상태를 ROLLED_BACK으로 바꾸지 않는다. 배치의 기존 ROLLBACK 동작은 별도다.
- 준비 시간 초과나 실행 오류: 스냅샷을 이용해 자동 복구한다. 복원이 끝난 뒤에만 `FAILED`로 종료한다.
- 복구 작업 자체가 막히면 `ABORTING`과 `recoveryError`를 유지하고 재시도한다. 외부 변경이나 권한 문제를 해결해야 한다.
- 승인 대기에는 자동 승격이나 제한 시간이 없다. 준비 상태를 계속 확인하지만 오류율·지연 시간 분석은 운영자가 수행한다.
- `progressDeadlineSeconds`는 준비·전환 단계별 제한이다. Service selector나 대상 UID가 외부에서 변경되면 덮어쓰지 않는다.

## 배치

배치 전용 화면은 `/batch.html`이다. 웹 앱은 웹 화면에, 배치 앱은 배치 화면에만 표시한다.
배치는 **배포 설정 / 배포 이력 / 실행 이력**을 따로 관리한다.
설정 저장은 최신 설정 하나를 교체할 뿐이고, **배포**는 CronJob 템플릿만 갱신한다.
**수동 실행**을 별도로 눌러야 DeployDock이 Job을 생성한다. 배포 성공과 작업 성공은 다른 상태다.

`GROUPED`는 2~10개의 서로 다른 기존 CronJob 템플릿을 갱신한다.
`INDIVIDUAL`은 지정한 CronJob 하나를 갱신하며, 대상 생략 시 앱 이름을 사용한다.
이미 실행 중인 Job은 수정하지 않고 이후 스케줄에 새 이미지를 적용한다.
그룹 변경은 Kubernetes 다중 리소스 트랜잭션이 아니며 순차 적용한다. 중간 실패 시
이미 바뀐 대상들을 복원한다. 그 사이 스케줄된 Job까지 취소·복원하지는 않는다.

### UI 수동 실행

실행 이력 탭에서 현재 배포된 CronJob과 이미지를 선택·확인하고 수동 실행한다.
서버가 요청을 접수할 때 실제 CronJob 템플릿을 읽어 고정한다. 저장만 한 설정은 실행에 사용하지 않는다.
예를 들어 배포된 이미지가 v3이고 저장 설정만 v4라면 수동 Job은 v3로 실행한다.
v4를 배포한 뒤 요청하는 Job부터 v4로 실행한다. 이미 접수한 Job의 설정은 바뀌지 않는다.

| API (`/api/v2/deployment-applications/{id}` 기준) | 역할 |
|---|---|
| `GET /execution-targets` | 앱의 현재·과거 배포 대상 중 실제 존재하는 CronJob과 배포된 이미지 |
| `POST /executions` | `{ "cronJobName": "settlement", "requestId": "manual-20260917-1" }`, 수동 실행 접수 후 202 |
| `GET /executions` | UI/API로 수동 접수한 실행 이력; 스케줄 생성 Job은 포함하지 않음 |

템플릿과 요청을 먼저 저장하고 별도의 `BatchExecutionScheduler`가 기본 5초 간격으로 Job 생성·상태 확인을 한다.
배포 엔진의 LOCAL/Temporal 선택과 별개이며, 실제 Pod 실행은 Kubernetes Job controller가 담당한다.
동일 requestId 재요청은 기존 실행을 반환한다. 다른 requestId는 새로운 작업이며 동시 실행할 수 있다.
CronJob의 `concurrencyPolicy`는 이 수동 Job에 적용되지 않으므로 중복 업무 처리 방지는 작업 자체에서도 필요하다.
CronJob의 schedule·suspend를 바꾸지 않으며 기존 스케줄은 그대로다. Job 템플릿 자체가 suspended이거나
수동 selector를 지정한 경우 수동 실행을 거부한다. 이번 범위는 수동 실행과 이력이며 로그·취소 기능은 포함하지 않는다.

상태는 `QUEUED` → `PENDING` → `RUNNING` → `SUCCEEDED` 또는 `FAILED`다. 짧은 작업은 중간 상태를 건너뛸 수 있다.
Pod 한 번의 실패만으로 Job 실패를 확정하지 않고 Job의 Complete/Failed condition을 확인한다.
완료 확인 후 Job이 TTL로 삭제되어도 이력은 남는다. 생성 UID를 확인했던 미완료 Job이 사라지면
`MISSING`으로 기록하고 재생성하지 않는다. API·권한 오류는 오류 메시지와 이전 상태를 남기고 재시도한다.

서버 사용자와 ServiceAccount 모두 대상 namespace의 `cronjobs/get`, `jobs/get,create` 권한이 필요하다.
실행 이력 조회는 `jobs/get`, 대상 조회는 `cronjobs/get`을 검사한다. 배포 권한과 별도이며
실행 조회 실패가 배포 화면을 잠그지는 않는다. 원본 템플릿 스냅샷은 저장소에만 두고 API에 반환하지 않는다.

## Temporal과 재시작

```properties
deploydock.temporal.enabled=true
deploydock.temporal.target=127.0.0.1:7233
deploydock.temporal.namespace=default
deploydock.temporal.task-queue=deploydock-deployments
```

앱 등록 시 `TEMPORAL`을 선택하면 Temporal Workflow가 재조정 Activity를 반복한다.
Kotlin payload 변환기를 명시적으로 등록했다. 비활성 상태에서 `TEMPORAL`을 선택하면 `503`이다.
`LOCAL`도 동일한 Kubernetes 실행기를 사용하며 기본 5초 간격으로 저장된 미완료 실행을 재개한다.
프로세스 종료 시 ConfigMap 잠금은 최대 5분 뒤 만료된다. 리소스 이름과 실행 ID가 고정되어
다시 실행하더라도 같은 preview를 확인해 이어간다.

## 운영 범위와 검증

- 설정·배포 이력·별도의 수동 실행 이력은 앱별 ConfigMap에 저장하며 합계 800KB 제한을 둔다. 무제한 이력·자동 보관 기능은 없다.
- 복구를 위해 성공한 실행의 preview Deployment와 Service를 남기고 Pod만 0개로 줄인다. 자동 삭제 정책은 없다.
- HPA/GitOps와의 공동 소유·충돌 조정은 제공하지 않는다. 같은 Deployment 이미지와 Service selector를 동시에 변경하지 않아야 한다.
- preview 격리를 위해 원본 selector의 label 값 하나가 달라진다. 해당 label에 의존하는 NetworkPolicy와 PDB는 신규 Pod에도 맞는 별도 정책이 필요할 수 있다.
- 현재 변경 대상은 지정 컨테이너 이미지와 선택한 replica 수다. 신규 Secret, 이미지 빌드, DB 마이그레이션은 수행하지 않는다.
- `./gradlew test`는 Fabric8 HTTP mock server에서 Kubernetes 읽기·변경·복구를 검증하고 Temporal 테스트 서버에서 Workflow를 실행한다.
- 실제 클러스터의 Gateway 트래픽 분포·EndpointSlice 전파·HTTP 응답은 별도 검증이 필요하다. 2026-09-17 로컬 Kind 점검은 API 서버 TLS timeout/EOF로 실패해 실 Pod 검증을 완료하지 못했다.

실제 클러스터 테스트는 별도 네임스페이스를 만들고 nginx 1.27/1.28의 HTTP 응답 헤더로
운영·preview 격리, 승인 후 전환, 원본 UID 유지, 이전 리비전 재배포를 확인한 다음 테스트 네임스페이스를 삭제한다.
기본 테스트 실행에서는 건너뛰며 저장소 Kind context만 허용한다.

```sh
DEPLOYDOCK_E2E_KUBECONFIG="$PWD/dev/.state/kubeconfig" \
  ./gradlew test --tests '*DeploymentClusterTests' --rerun
```
