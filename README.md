# DeployDock

DeployDock exposes a reactive API for account authentication and Kubernetes
namespace discovery. Accounts are stored as `DeployDockUser` custom resources;
BCrypt password hashes are kept separately in Kubernetes Secrets.

The local web console is available at `http://localhost:8080`. It is served as
static HTML, CSS, and JavaScript; every account and namespace operation uses the
JSON API, and the server has no template-rendering controller.

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

The service account can list namespaces and create `SubjectAccessReview` objects,
but the API response only includes namespaces allowed for the authenticated user.

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
