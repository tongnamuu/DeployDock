# DeployDock

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
