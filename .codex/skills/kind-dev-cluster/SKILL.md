---
name: kind-dev-cluster
description: Create, validate, inspect, or remove DeployDock's pinned six-node Kind development cluster. Use for local Kubernetes environment setup and checks in this repository; do not use for production or external clusters.
---

# DeployDock Kind Development Cluster

Use the repository entrypoint `./dev/kind/cluster.sh`. It pins Kind, kubectl, and the Kind node image by version and SHA-256, and stores its kubeconfig under the ignored `dev/.state` directory instead of changing the user's default kubeconfig.

## Execution permissions

- `up`, `status`, `validate`, `kubeconfig`, and `down` access the user's Docker Unix socket, which is outside the normal workspace sandbox. Request sandbox escalation on the first invocation of one of these commands; do not first probe Docker or retry after an expected sandbox denial.
- When `exec_command` supports scoped escalation, invoke the exact repository command with `sandbox_permissions: require_escalated`, explain that it needs the local Docker socket, and request the reusable prefix `./dev/kind/cluster.sh`.
- Keep the approval scoped to this entrypoint. Do not request unrestricted Docker access, disable the sandbox, or use a full-access mode.
- `./dev/kind/cluster.sh versions` does not access Docker or the network and can run inside the normal sandbox.

## Workflow

1. Run from the repository root.
2. Use `./dev/kind/cluster.sh up` to create the cluster. The command downloads verified tool binaries when absent, creates `deploydock-dev`, waits for all nodes, and validates the exact topology and Kubernetes version.
3. If the cluster already exists, `up` validates it without changing it. If validation reports a topology, version, or image mismatch, stop and report it; never delete or recreate the cluster implicitly.
4. Use `./dev/kind/cluster.sh status` for a node overview and `./dev/kind/cluster.sh validate` for the full invariant check.
5. Report the kubeconfig path from `./dev/kind/cluster.sh kubeconfig` when another command must access this cluster. Pass it explicitly with `--kubeconfig`; do not merge it into the user's default kubeconfig.
6. Run `./dev/kind/cluster.sh down` only when the user explicitly requests deletion or recreation of this development cluster.

## Fixed environment

- Cluster: `deploydock-dev`
- Kind: `v0.33.0`
- Kubernetes: `v1.37.0`
- Topology: three control-plane nodes and three worker nodes
- Canonical configuration and checksums: `dev/kind/cluster.yaml` and `dev/kind/versions.env`

Do not substitute a system-installed Kind or kubectl, float image tags, omit digest verification, change the node count, or target a differently named cluster.
