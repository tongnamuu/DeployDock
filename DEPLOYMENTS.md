# Deployment 실행과 신규 Pod 테스트

DeployDock은 기존 `apps/v1 Deployment`와 `Service`를 직접 사용한다. Argo Rollouts나
DeployDock 상위 CRD 설치는 필요하지 않다. 실행 상태와 복구용 스냅샷은
`deploydock-system`의 ConfigMap에 저장한다.

파일별 역할과 내부 호출·상태 전이는 [배포 코드 읽기](DEPLOYMENT_CODE.md)를 참고한다.
Spring 서버 없이 사용하는 방법은 [라이브러리 가이드](deployment-library/README.md)에 있다.
아래 JWT·사용자 권한·주기 실행 설정은 루트 서버 앱 기준이다. 핵심 라이브러리는
호출자가 제공한 KubernetesClient와 재조정 스케줄을 사용한다.

## 준비

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

핵심 실행기는 `CanaryTrafficAdapter`에 트래픽 제어를 위임한다. 기본 환경에서는 어댑터가
없으므로 가중치 카나리 설정을 저장할 때 명시적으로 거부한다. 롤링·블루그린·배치에는 필요 없다.
기본 제공하는 선택형 어댑터는 Gateway API `gateway.networking.k8s.io/v1 HTTPRoute`다.
서버에서 사용하려면 `deploydock.deployment.gateway-api.enabled=true`를 지정한다.
이를 지원하는 Gateway controller가 이미 설치되어 있고, HTTPRoute가 운영 Service를 가리켜야 한다.
라이브러리나 서버는 controller를 설치하지 않는다. 환경에 맞는 다른 어댑터를 직접 등록할 수 있다.
일반 Service의 replica 비율을 정확한 요청 비율로 간주하지 않는다.
Ingress NGINX와 서비스 메시용 어댑터는 아직 없다.

설정 예:

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
- 마지막 성공 실행에 `ROLLBACK`: 신규 버전을 임시로 다시 가동해 트래픽을 받은 상태에서 원본을 이전 이미지로 복원한다. 완료 상태는 `ROLLED_BACK`이다.
- 준비 시간 초과나 실행 오류: 스냅샷을 이용해 자동 복구한다. 복원이 끝난 뒤에만 `FAILED`로 종료한다.
- 복구 작업 자체가 막히면 `ABORTING`과 `recoveryError`를 유지하고 재시도한다. 외부 변경이나 권한 문제를 해결해야 한다.
- 승인 대기에는 자동 승격이나 제한 시간이 없다. 준비 상태를 계속 확인하지만 오류율·지연 시간 분석은 운영자가 수행한다.
- `progressDeadlineSeconds`는 준비·전환 단계별 제한이다. Service selector나 대상 UID가 외부에서 변경되면 덮어쓰지 않는다.

## 배치

`GROUPED`는 2~10개의 서로 다른 기존 CronJob 템플릿을 갱신한다.
`INDIVIDUAL`은 지정한 CronJob 하나를 갱신하며, 대상 생략 시 앱 이름을 사용한다.
이미 실행 중인 Job은 수정하지 않고 이후 스케줄에 새 이미지를 적용한다.
그룹 변경은 Kubernetes 다중 리소스 트랜잭션이 아니며 순차 적용한다. 중간 실패 시
이미 바뀐 대상들을 복원한다. 그 사이 스케줄된 Job까지 취소·복원하지는 않는다.

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

- 설정과 실행 이력은 앱별 ConfigMap에 저장하며 800KB 제한을 둔다. 무제한 이력·자동 보관 기능은 없다.
- 복구를 위해 성공한 실행의 preview Deployment와 Service를 남기고 Pod만 0개로 줄인다. 자동 삭제 정책은 없다.
- HPA/GitOps와의 공동 소유·충돌 조정은 제공하지 않는다. 같은 Deployment 이미지와 Service selector를 동시에 변경하지 않아야 한다.
- preview 격리를 위해 원본 selector의 label 값 하나가 달라진다. 해당 label에 의존하는 NetworkPolicy와 PDB는 신규 Pod에도 맞는 별도 정책이 필요할 수 있다.
- 현재 변경 대상은 지정 컨테이너 이미지와 선택한 replica 수다. 신규 Secret, 이미지 빌드, DB 마이그레이션은 수행하지 않는다.
- `./gradlew test`는 Fabric8 HTTP mock server에서 Kubernetes 읽기·변경·복구를 검증하고 Temporal 테스트 서버에서 Workflow를 실행한다.
- 실제 클러스터의 Gateway 트래픽 분포·EndpointSlice 전파·HTTP 응답은 별도 검증이 필요하다. 2026-09-17 로컬 Kind 점검은 API 서버 TLS timeout/EOF로 실패해 실 Pod 검증을 완료하지 못했다.

실제 클러스터 테스트는 별도 네임스페이스를 만들고 nginx 1.27/1.28의 HTTP 응답 헤더로
운영·preview 격리, 승인 후 전환, 원본 UID 유지, 롤백을 확인한 다음 테스트 네임스페이스를 삭제한다.
기본 테스트 실행에서는 건너뛰며 저장소 Kind context만 허용한다.

```sh
DEPLOYDOCK_E2E_KUBECONFIG="$PWD/dev/.state/kubeconfig" \
  ./gradlew test --tests '*DeploymentClusterTests' --rerun
```
