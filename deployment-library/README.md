# Kubernetes 배포 라이브러리

특정 클라우드·Kubernetes 배포판·Ingress·서비스 메시를 필수로 두지 않는다.
호출자가 전달한 Fabric8 `KubernetesClient`로 표준 API를 사용한다. Spring 서버,
Temporal, Argo Rollouts, DeployDock CRD 없이도 호출할 수 있다.

## 모듈과 지원 범위

| 모듈 | Maven artifact | 역할 |
|---|---|---|
| `deployment-library` | `com.deploy.k8s:deploydock-deployment` | 요청 검증, 상태 전이, Kubernetes 변경, ConfigMap 저장 |
| `deployment-gateway-api` | `com.deploy.k8s:deploydock-gateway-api` | 선택형 HTTPRoute 트래픽 어댑터 |
| 루트 앱 | Spring Boot 실행 JAR | 인증 API, 웹 콘솔, 주기 실행, Temporal 연결 |

현재 버전은 `0.0.1-SNAPSHOT`이다. Maven publication과 sources JAR를 생성하도록 구성했으며
공개 Maven 저장소에 게시한 것은 아니다. 저장소 안에서는 `implementation(project(":deployment-library"))`로 사용한다.

```sh
./gradlew :deployment-library:build :deployment-gateway-api:build
```

라이브러리 바이트코드는 Java 17을 대상으로 하며 저장소 빌드에는 Java 25 도구 체인을 사용한다.
실제 Java 17 JVM 실행 및 모든 Kubernetes 배포판에 대한 인증 테스트를 마쳤다는 뜻은 아니다.

| 기능 | 필요한 환경 |
|---|---|
| 롤링 | 기존 `apps/v1 Deployment` |
| 블루그린 및 신규 Pod 테스트 | 기존 Deployment와 selector 기반 Service, `discovery.k8s.io/v1 EndpointSlice`, 추가 Pod 용량 |
| 개별·그룹 배치 배포 | 기존 `batch/v1 CronJob`; 실행 중 Job은 변경하지 않음 |
| 정확한 가중치 설정 기반 카나리 | 해당 환경에서 구현·등록한 `CanaryTrafficAdapter`와 트래픽 분배기 |
| 기본 상태 저장 | 기존 제어 namespace와 ConfigMap 읽기·쓰기 권한 |

기본 Kubernetes Service만으로 요청 비율을 10%/90%로 제어한다고 약속하지 않는다.
어댑터 없는 환경에서는 롤링·블루그린·배치를 사용하고, 가중치 카나리 설정은 명시적으로 거부한다.
`capabilities()`는 등록된 기능 목록이지 클러스터 권한·controller 정상 동작을 검사한 결과가 아니다.
StatefulSet·DaemonSet·임의 CRD 배포는 현재 구현 범위가 아니다.

## 서버 없이 호출하기

다음 예제는 이미 준비된 `team-a/shop-web` Deployment를 롤링 갱신한다.
제어 namespace `deploydock-system`도 미리 존재해야 한다. 클라이언트 구성은 호출자가 결정한다.

```kotlin
import com.deploy.k8s.DeployDock.deployment.*
import io.fabric8.kubernetes.client.KubernetesClientBuilder
import java.time.Clock

fun main() {
    KubernetesClientBuilder().build().use { kubernetes ->
        val store = KubernetesDeploymentStore(kubernetes, "deploydock-system")
        val workloads = KubernetesDeploymentWorkloads(kubernetes)
        val api = DeploymentClient(store, workloads)
        val reconciler = DeploymentReconciler(store, workloads, Clock.systemUTC())
        val app = api.registerApplication("release-job", RegisterApplicationRequest(
            name = "shop-web", namespace = "team-a", kind = ApplicationKind.WEB,
        ))
        val config = api.saveConfiguration("release-job", app.id, SaveDeploymentConfigurationRequest(
            image = "registry.example.com/shop:v2", webStrategy = WebDeploymentStrategy.ROLLING,
        ))
        val run = api.submitRun("release-job", app.id, SubmitDeploymentRunRequest(
            configurationId = config.id, requestId = "shop-release-2",
        ))
        while (!reconciler.reconcile(app.id, run.id)) Thread.sleep(5_000)
        val finished = api.runs(app.id).first { it.id == run.id }
        check(finished.status == DeploymentRunStatus.SUCCEEDED) { finished.toString() }
    }
}
```

`DeploymentClient`는 동기 API다. 요청을 저장할 뿐 background thread를 시작하지 않는다.
`reconcile()`은 한 단계를 진행하고 종료 상태 여부를 반환한다. `true`가 성공만을 의미하지는 않는다.
호출자가 executor, 기존 작업 큐 또는 Temporal Activity에서 반복 호출한다. 재시작 시
`store.list()`에서 종료되지 않은 실행을 찾아 다시 호출하면 저장된 단계부터 이어간다.
독립 클라이언트의 기본 orchestrator는 `LOCAL`이다. 별도 실행기를 연결하지 않고
`TEMPORAL`을 선택하면 거부한다. 루트 앱은 실행 가능 여부 검사를 주입하고 Temporal을 연결한다.

블루그린은 `webStrategy=BLUE_GREEN`으로 저장한다. `AWAITING_APPROVAL`에서
`run.result.previewService`에 연결해 신규 Pod를 테스트한 후
`api.action(principal, app.id, run.id, DeploymentAction.PROMOTE, requestId)`를 호출한다.
승인 대기는 무기한이며 호출자가 승인을 제공해야 한다. 상세한 port-forward 및 복구 절차는
[운영 가이드](../DEPLOYMENTS.md)에 있다.

## 인증과 저장소

독립 라이브러리의 기본 권한은 **전달한 KubernetesClient의 자격 증명**이다.
`principal` 문자열은 이력에 기록할 실행 주체이며 Kubernetes 사용자 impersonation이 아니다.
기본 `ClientCredentialsAuthorization`은 추가 SAR을 요청하지 않고 Kubernetes API 서버가
실제 요청의 RBAC를 판단하도록 한다. 다중 사용자 HTTP 서버의 인증·테넌트 격리는 제공하지 않는다.

다중 사용자 호스트는 요청 인증과 조회 범위 검사를 직접 수행하고, 필요하면
`KubernetesDeploymentWorkloads(kubernetes, authorization = SubjectAccessReviewAuthorization(kubernetes))`를 사용한다.
이 경우 실제 클라이언트 권한과 `principal`의 대상 권한이 모두 필요하며 SAR 생성 권한도 필요하다.
루트 앱은 기존 JWT·namespace 접근 검사와 이 SAR 정책을 유지한다.

`DeploymentStore`를 구현해 다른 저장소를 주입할 수 있다. `update`는 앱 단위로
읽기·변경·저장을 직렬화해야 하며 예외 시 부분 저장하지 않아야 한다. 영속성·잠금·충돌 처리는
구현체 책임이다. 기본 ConfigMap 저장소는 300초 잠금과 resourceVersion 검사를 사용하며
자동 잠금 연장·무제한 이력 보관·HA 보장을 제공하지 않는다.

## 트래픽 어댑터

`CanaryTrafficAdapter`는 라이브러리가 정의한 작은 확장 인터페이스다.
환경에서 이미 사용하는 분배기를 연결하며, 모든 어댑터가 Gateway API를 사용할 필요는 없다.

| 메서드 | 구현 계약 |
|---|---|
| `id`, `permissions` | 고유한 선택 이름과 SAR 검사에 필요한 Kubernetes 권한 |
| `validate` | 외부 상태를 바꾸지 않고 `trafficOptions`를 검사 |
| `capture` | 복구 가능한 원래 상태를 `TrafficSnapshot`으로 반환; 같은 요청 재시도에 안전해야 함 |
| `setWeight` | 신규 Service로 보낼 가중치 설정; `null`이면 실행 스냅샷으로 복원; 재시도에 안전해야 함 |
| `isReady` | 직전 설정이 분배기에 반영됐는지 확인; 반영 대기 중에는 `false` |

신규 Service 이름은 `KubernetesDeploymentWorkloads.previewName(run)`이다.
`TrafficSnapshot`에는 어댑터 ID, 범용 Kubernetes 리소스 목록 또는 문자열 속성을 저장한다.
어댑터는 외부 변경 충돌을 감지하고 자신이 소유한 필드만 변경·복원해야 한다.
가중치 조절·승격·복구 중 어댑터를 제거하거나 ID를 바꾸면 실행을 이어갈 수 없다.

Gateway 환경에서만 별도 모듈을 의존성에 추가하고 다음처럼 등록한다.

```kotlin
val workloads = KubernetesDeploymentWorkloads(kubernetes, listOf(GatewayApiTrafficAdapter(kubernetes)))
val api = DeploymentClient(store, workloads)
val config = api.saveConfiguration("release-job", app.id, SaveDeploymentConfigurationRequest(
    image = "registry.example.com/shop:v2",
    webStrategy = WebDeploymentStrategy.CANARY,
    trafficAdapter = "gateway-api",
    trafficOptions = mapOf("routeName" to "shop-web"),
    canarySteps = listOf(10, 50),
))
```

기존 `canaryRoute` 설정과 이전 HTTPRoute 스냅샷도 호환해서 읽는다.
루트 서버에서는 `deploydock.deployment.gateway-api.enabled=true`로 기본 어댑터를 켠다.
다른 어댑터는 `CanaryTrafficAdapter` Spring Bean으로 제공한다.
NGINX·Istio 등의 실제 어댑터는 아직 포함하지 않았다.

## 검증 범위

독립 모듈 테스트는 Spring·Temporal·Gateway 어댑터 클래스가 classpath에 없음을 확인하고
mock Kubernetes에서 롤링, 블루그린 preview, 미지원 카나리 거부, 사용자 정의 어댑터·저장소를 검증한다.
서버 통합 테스트는 승인·복구·Gateway 상태 확인·Temporal Workflow를 검증한다.
이 테스트는 실제 클러스터의 admission 정책, CNI, HTTP 트래픽 분포를 검증하지 않는다.
환경별 RBAC·NetworkPolicy·quota·이미지 접근·GitOps/HPA 충돌을 별도 검증해야 한다.
