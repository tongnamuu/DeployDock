# DeployDock

롤링 배포의 첫 실제 앱 검증은 [tongnamuu 롤링 실험](dev/rolling/README.md)을 참고한다.
설정 저장과 적용을 별도로 실행하며, 새 제품 큐·복구 기능의 완성 여부와는 구분한다.

## 프로젝트 구성

목표는 특정 Kubernetes 배포판·클라우드·Ingress에 종속되지 않는 배포 라이브러리다.

- `deployment-library`: 표준 Kubernetes API 기반 실행기와 저장소. Spring·Temporal 없이 사용한다.
- `deployment-gateway-api`: 선택형 가중치 카나리 어댑터. 핵심 라이브러리는 이 모듈에 의존하지 않는다.
- 루트 앱: 라이브러리를 사용하는 Spring Boot API·웹 콘솔·Temporal 실행 호스트다.

[라이브러리 사용 예제와 확장 계약](deployment-library/README.md)을 먼저 참고한다.
라이브러리는 Java 17 바이트코드로 빌드하며 저장소 빌드 도구 체인은 Java 25다.
현재 워크로드는 Deployment와 CronJob이며, 모든 Kubernetes 환경의 실검증 완료를 뜻하지 않는다.
정적 웹 화면은 루트 앱의 `src/main/resources/static`에서 제공한다.

DeployDock exposes a reactive API for account authentication and Kubernetes
namespace discovery. Accounts are stored as `DeployDockUser` custom resources;
BCrypt password hashes are kept separately in Kubernetes Secrets.

The local web console is available at `http://localhost:8080`. It is served as
static HTML, CSS, and JavaScript; every account and namespace operation uses the
JSON API, and the server has no template-rendering controller.

- `/admin/crds.html`: administrator-only CRD catalog management. The page is a
  static shell; its API requests still require administrator authorization.
- `/custom-resources.html`: creates a registered custom resource when the
  signed-in user has `create` RBAC permission for that CRD and namespace.
- `/deployments.html`: registers deployment applications, saves configurations,
  starts deployments, and displays live execution phases with approval, abort,
  rollback, and preview connection controls. Uses the authenticated deployment API.

For a cluster-free, explicitly labelled UI preview and browser checks, see
[deployment UI verification](dev/ui/README.md). Preview data is not a real deployment.

## Cluster resources

Install the CRD and the service account permissions:

```shell
kubectl apply -k k8s/base
```

When DeployDock runs in the cluster, use the `deploydock` service account in the
`deploydock-system` namespace. For local development, the Fabric8 client uses the
current kubeconfig context.

Grant a DeployDock user access with a normal Kubernetes RoleBinding. The JWT
subject and Kubernetes RBAC username for account `alice` is `deploydock:alice`:

```shell
kubectl create namespace team-a
kubectl apply -f k8s/examples/alice-team-a-access.yaml
```

The base resources bind the Kubernetes principal `deploydock:admin` to the
minimal `deploydock-namespace-admin` ClusterRole. Create the `admin` account
through the signup endpoint once, then its token can create namespaces. Other
users receive `403 Forbidden` unless Kubernetes RBAC grants the same permission.

By default, a namespace is visible when the user can `list` core `pods` in that
namespace. Change `deploydock.kubernetes.namespace-access-group`, `resource`, and
`verb` to use a different access signal.

The service account can list namespaces, create `SubjectAccessReview` objects,
and manage DeployDock-owned member Roles and RoleBindings. The accompanying
`ValidatingAdmissionPolicy` rejects any service-account attempt to manage an
unowned RBAC object or grant a resource or verb outside the application catalog.
The API response only includes namespaces allowed for the authenticated user.

## Run

Set a private signing key of at least 32 bytes, then start the application:

```shell
export DEPLOYDOCK_JWT_SECRET='replace-with-a-random-secret-of-at-least-32-bytes'
./gradlew bootRun
```

The project toolchain targets Java 25. `./gradlew bootRun` selects that toolchain
even if the shell's default `java` command points to an older JDK.

The account store namespace defaults to `deploydock-system` and can be overridden
with `DEPLOYDOCK_CONTROL_NAMESPACE`.

## API

Create an account:

```shell
curl -i http://localhost:8080/api/auth/signup \
  -H 'Content-Type: application/json' \
  -d '{"username":"alice","password":"correct-horse"}'
```

Log in:

```shell
curl -s http://localhost:8080/api/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"username":"alice","password":"correct-horse"}'
```

Use the returned access token:

```shell
curl http://localhost:8080/api/namespaces \
  -H "Authorization: Bearer $ACCESS_TOKEN"
```

Create a namespace with an authorized admin token:

```shell
curl -i http://localhost:8080/api/namespaces \
  -H "Authorization: Bearer $ADMIN_ACCESS_TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"name":"team-a"}'
```

List DeployDock users and the permission catalog with an authorized admin token:

```shell
curl http://localhost:8080/api/admin/users \
  -H "Authorization: Bearer $ADMIN_ACCESS_TOKEN"

curl http://localhost:8080/api/admin/permission-catalog \
  -H "Authorization: Bearer $ADMIN_ACCESS_TOKEN"
```

Register an installed namespace-scoped CRD in the permission catalog:

```shell
curl -X PUT \
  http://localhost:8080/api/admin/permission-catalog/custom-resources/widgets.example.com \
  -H "Authorization: Bearer $ADMIN_ACCESS_TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"displayName":"Widgets","allowedVerbs":["get","list","watch","create","update","patch","delete"]}'

curl http://localhost:8080/api/admin/permission-catalog/custom-resources \
  -H "Authorization: Bearer $ADMIN_ACCESS_TOKEN"

curl -X DELETE \
  http://localhost:8080/api/admin/permission-catalog/custom-resources/widgets.example.com \
  -H "Authorization: Bearer $ADMIN_ACCESS_TOKEN"
```

Registration verifies that the CRD exists, is established, and has
`spec.scope: Namespaced`. DeployDock control CRDs cannot be registered. A
registration cannot be removed while a managed Role still grants that custom
resource; revoke or replace those member permissions first.

List the registered custom resources that the current user can create, then
create one from a JSON `spec`:

```shell
curl http://localhost:8080/api/custom-resources/creatable \
  -H "Authorization: Bearer $ACCESS_TOKEN"

curl -X POST \
  http://localhost:8080/api/custom-resources/team-a/widgets.example.com \
  -H "Authorization: Bearer $ACCESS_TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"name":"example-widget","spec":{"message":"hello"}}'
```

The create endpoint performs a Kubernetes `SubjectAccessReview` for the JWT
principal on every request. DeployDock also maintains a namespace-scoped proxy
Role for its own service account containing only the registered CRDs for which
managed members have `create`; it never grants access to Namespace objects or
unregistered resources.

Replace a member's permissions in one namespace, inspect the namespace mapping,
and revoke it:

```shell
curl -X PUT http://localhost:8080/api/admin/namespaces/team-a/members/alice \
  -H "Authorization: Bearer $ADMIN_ACCESS_TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"permissions":[{"apiGroup":"","resource":"pods","verbs":["get","list"]},{"apiGroup":"apps","resource":"deployments","verbs":["get","create","update","patch","delete"]}]}'

curl http://localhost:8080/api/admin/namespaces/team-a/members \
  -H "Authorization: Bearer $ADMIN_ACCESS_TOKEN"

curl -X DELETE http://localhost:8080/api/admin/namespaces/team-a/members/alice \
  -H "Authorization: Bearer $ADMIN_ACCESS_TOKEN"
```

Permission replacement is allowlist-based. Kubernetes system namespaces,
Namespace objects, cluster-scoped resources, and Kubernetes RBAC resources
cannot be granted. Workload creation
and Secret access can still be security-sensitive within the selected namespace,
so grant only the verbs each member needs.

## Deployment API

The `/api/v2/deployment-applications` endpoints register deployment targets,
save immutable configurations, and submit runs. Requests are authenticated with
the same JWT subject and are limited to namespaces visible to that principal.
Runs return `202` with a persisted execution ID. A reconciler changes Kubernetes
resources and checks readiness before reporting success. Configurations, runs,
and rollback snapshots are stored in ConfigMaps in the control namespace.
When `deploydock.temporal.enabled=true`, Temporal workflows drive reconciliation.
Selecting `TEMPORAL` while disabled returns `503`; `LOCAL` is an explicit choice.

Web deployments target existing `apps/v1 Deployment` workloads. Blue-green
creates an isolated preview Service and waits for manual approval. Canary also
supports preview deployment, testing, promotion, and rollback without a traffic
adapter. Existing Ingress objects and their controller choice are left untouched.
This preview-only mode does not split production traffic. Weighted canary
uses an explicitly registered `CanaryTrafficAdapter`; Gateway API is optional.
The bundled Gateway adapter is disabled by default. Enable it with
`deploydock.deployment.gateway-api.enabled=true` only when a suitable controller
and HTTPRoute already exist. Custom adapter beans can use other traffic systems.
Argo Rollouts is not required. Promotion preserves the original Deployment and
Service names. Batch modes update existing CronJob templates, not running Jobs.
See [deployment operations and preview testing](DEPLOYMENTS.md) for prerequisites,
permissions, approval, rollback, recovery, and current limits.
For the implementation walkthrough, file responsibilities, and state transitions,
see [deployment code guide](DEPLOYMENT_CODE.md).

Register a web application and save preview-only canary/blue-green configurations.
Neither Temporal nor a Gateway/Ingress adapter is required:

```shell
curl -X POST http://localhost:8080/api/v2/deployment-applications \
  -H "Authorization: Bearer $ACCESS_TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"name":"shop-web","namespace":"team-a","kind":"WEB"}'

curl -X POST http://localhost:8080/api/v2/deployment-applications/$APP_ID/configurations \
  -H "Authorization: Bearer $ACCESS_TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"image":"registry.example.com/shop:v2","replicas":3,"webStrategy":"CANARY"}'

curl -X POST http://localhost:8080/api/v2/deployment-applications/$APP_ID/configurations \
  -H "Authorization: Bearer $ACCESS_TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"image":"registry.example.com/shop:v3","replicas":3,"webStrategy":"BLUE_GREEN"}'
```

For preview-only canary, test `result.previewService` and send `PROMOTE` when ready.
`result.trafficMode` is `PREVIEW_ONLY`; `ADVANCE` is rejected because there is no
weighted production routing. To opt into weighted routing, explicitly select a
registered adapter as shown in [the operations guide](DEPLOYMENTS.md).

Batch applications support grouped and individual deployment modes:

```shell
curl -X POST http://localhost:8080/api/v2/deployment-applications \
  -H "Authorization: Bearer $ACCESS_TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"name":"billing-batch","namespace":"team-a","kind":"BATCH"}'

curl -X POST http://localhost:8080/api/v2/deployment-applications/$BATCH_APP_ID/configurations \
  -H "Authorization: Bearer $ACCESS_TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"image":"registry.example.com/billing:v1","batchMode":"GROUPED","batchTargets":["settlement","invoice"]}'

curl -X POST http://localhost:8080/api/v2/deployment-applications/$BATCH_APP_ID/configurations \
  -H "Authorization: Bearer $ACCESS_TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"image":"registry.example.com/billing:v2","batchMode":"INDIVIDUAL","batchTargets":["settlement"]}'
```
