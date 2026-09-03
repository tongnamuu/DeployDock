#!/usr/bin/env bash

set -euo pipefail

KIND_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "${KIND_DIR}/../.." && pwd)"
CONFIG_FILE="${KIND_DIR}/cluster.yaml"
VERSIONS_FILE="${KIND_DIR}/versions.env"
TOOLS_DIR="${PROJECT_DIR}/dev/.tools"
STATE_DIR="${PROJECT_DIR}/dev/.state"
KUBECONFIG_FILE="${STATE_DIR}/kubeconfig"

# shellcheck disable=SC1090
source "${VERSIONS_FILE}"

KIND_BIN="${TOOLS_DIR}/kind/${KIND_VERSION}/kind"
KUBECTL_BIN="${TOOLS_DIR}/kubectl/${KUBERNETES_VERSION}/kubectl"

fail() {
  printf 'ERROR: %s\n' "$*" >&2
  exit 1
}

require_command() {
  command -v "$1" >/dev/null 2>&1 || fail "Required command is not installed: $1"
}

sha256_of() {
  local path="$1"

  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "${path}" | awk '{print $1}'
  elif command -v shasum >/dev/null 2>&1; then
    shasum -a 256 "${path}" | awk '{print $1}'
  else
    fail "sha256sum or shasum is required"
  fi
}

detect_platform() {
  case "$(uname -s)" in
    Darwin) PLATFORM_OS=darwin ;;
    Linux) PLATFORM_OS=linux ;;
    *) fail "Unsupported operating system: $(uname -s)" ;;
  esac

  case "$(uname -m)" in
    x86_64|amd64) PLATFORM_ARCH=amd64 ;;
    arm64|aarch64) PLATFORM_ARCH=arm64 ;;
    *) fail "Unsupported architecture: $(uname -m)" ;;
  esac

  case "${PLATFORM_OS}-${PLATFORM_ARCH}" in
    darwin-amd64)
      KIND_BINARY_SHA256="${KIND_SHA256_DARWIN_AMD64}"
      KUBECTL_BINARY_SHA256="${KUBECTL_SHA256_DARWIN_AMD64}"
      ;;
    darwin-arm64)
      KIND_BINARY_SHA256="${KIND_SHA256_DARWIN_ARM64}"
      KUBECTL_BINARY_SHA256="${KUBECTL_SHA256_DARWIN_ARM64}"
      ;;
    linux-amd64)
      KIND_BINARY_SHA256="${KIND_SHA256_LINUX_AMD64}"
      KUBECTL_BINARY_SHA256="${KUBECTL_SHA256_LINUX_AMD64}"
      ;;
    linux-arm64)
      KIND_BINARY_SHA256="${KIND_SHA256_LINUX_ARM64}"
      KUBECTL_BINARY_SHA256="${KUBECTL_SHA256_LINUX_ARM64}"
      ;;
  esac
}

download_verified_binary() {
  local name="$1"
  local url="$2"
  local expected_sha256="$3"
  local destination="$4"
  local actual_sha256
  local temporary_file

  if [[ -x "${destination}" ]]; then
    actual_sha256="$(sha256_of "${destination}")"
    if [[ "${actual_sha256}" == "${expected_sha256}" ]]; then
      return
    fi
    printf 'Cached %s checksum differs; downloading the pinned binary again.\n' "${name}" >&2
  fi

  require_command curl
  mkdir -p "$(dirname "${destination}")"
  temporary_file="$(mktemp "${TMPDIR:-/tmp}/deploydock-${name}.XXXXXX")"

  if ! curl --proto '=https' --tlsv1.2 --fail --location --silent --show-error \
    "${url}" --output "${temporary_file}"; then
    rm -f "${temporary_file}"
    fail "Failed to download ${name}"
  fi

  actual_sha256="$(sha256_of "${temporary_file}")"
  if [[ "${actual_sha256}" != "${expected_sha256}" ]]; then
    rm -f "${temporary_file}"
    fail "${name} checksum mismatch: expected ${expected_sha256}, got ${actual_sha256}"
  fi

  chmod 0755 "${temporary_file}"
  mv -f "${temporary_file}" "${destination}"
}

ensure_tools() {
  detect_platform
  download_verified_binary \
    kind \
    "https://github.com/kubernetes-sigs/kind/releases/download/${KIND_VERSION}/kind-${PLATFORM_OS}-${PLATFORM_ARCH}" \
    "${KIND_BINARY_SHA256}" \
    "${KIND_BIN}"
  download_verified_binary \
    kubectl \
    "https://dl.k8s.io/release/${KUBERNETES_VERSION}/bin/${PLATFORM_OS}/${PLATFORM_ARCH}/kubectl" \
    "${KUBECTL_BINARY_SHA256}" \
    "${KUBECTL_BIN}"
}

require_docker() {
  require_command docker
  docker info >/dev/null 2>&1 || fail "Docker is not running or is not accessible"
}

cluster_exists() {
  "${KIND_BIN}" get clusters 2>/dev/null | grep -Fxq "${KIND_CLUSTER_NAME}"
}

export_kubeconfig() {
  mkdir -p "${STATE_DIR}"
  "${KIND_BIN}" export kubeconfig \
    --name "${KIND_CLUSTER_NAME}" \
    --kubeconfig "${KUBECONFIG_FILE}" >/dev/null
  chmod 0600 "${KUBECONFIG_FILE}"
}

validate_cluster() {
  local control_plane_count
  local node_count
  local node_name
  local node_image
  local unexpected_versions
  local worker_count

  cluster_exists || fail "Kind cluster does not exist: ${KIND_CLUSTER_NAME}"
  export_kubeconfig

  "${KUBECTL_BIN}" --kubeconfig "${KUBECONFIG_FILE}" \
    wait --for=condition=Ready nodes --all --timeout=300s >/dev/null

  node_count="$("${KUBECTL_BIN}" --kubeconfig "${KUBECONFIG_FILE}" get nodes -o name | wc -l | tr -d ' ')"
  control_plane_count="$("${KUBECTL_BIN}" --kubeconfig "${KUBECONFIG_FILE}" get nodes \
    -l node-role.kubernetes.io/control-plane -o name | wc -l | tr -d ' ')"
  worker_count="$("${KUBECTL_BIN}" --kubeconfig "${KUBECONFIG_FILE}" get nodes \
    -l '!node-role.kubernetes.io/control-plane' -o name | wc -l | tr -d ' ')"

  [[ "${node_count}" == "6" ]] || fail "Expected 6 nodes, found ${node_count}"
  [[ "${control_plane_count}" == "3" ]] || fail "Expected 3 control-plane nodes, found ${control_plane_count}"
  [[ "${worker_count}" == "3" ]] || fail "Expected 3 worker nodes, found ${worker_count}"

  unexpected_versions="$("${KUBECTL_BIN}" --kubeconfig "${KUBECONFIG_FILE}" get nodes \
    -o jsonpath='{range .items[*]}{.status.nodeInfo.kubeletVersion}{"\n"}{end}' \
    | awk -v expected="${KUBERNETES_VERSION}" '$0 != expected {print}')"
  [[ -z "${unexpected_versions}" ]] || fail "Unexpected kubelet versions: ${unexpected_versions}"

  while IFS= read -r node_name; do
    [[ -n "${node_name}" ]] || continue
    case "${node_name}" in
      *-external-load-balancer) continue ;;
    esac
    node_image="$(docker inspect --format '{{.Config.Image}}' "${node_name}")"
    [[ "${node_image}" == "${KIND_NODE_IMAGE}" ]] || \
      fail "Node ${node_name} uses ${node_image}; expected ${KIND_NODE_IMAGE}"
  done < <("${KIND_BIN}" get nodes --name "${KIND_CLUSTER_NAME}")

  printf 'Validated %s: Kubernetes %s, 3 control-plane nodes, 3 worker nodes.\n' \
    "${KIND_CLUSTER_NAME}" "${KUBERNETES_VERSION}"
}

create_cluster() {
  ensure_tools
  require_docker

  if cluster_exists; then
    printf 'Cluster %s already exists; validating without modifying it.\n' "${KIND_CLUSTER_NAME}"
    validate_cluster
    return
  fi

  mkdir -p "${STATE_DIR}"
  "${KIND_BIN}" create cluster \
    --name "${KIND_CLUSTER_NAME}" \
    --config "${CONFIG_FILE}" \
    --image "${KIND_NODE_IMAGE}" \
    --kubeconfig "${KUBECONFIG_FILE}" \
    --wait 5m
  chmod 0600 "${KUBECONFIG_FILE}"
  validate_cluster
}

show_status() {
  ensure_tools
  require_docker
  cluster_exists || fail "Kind cluster does not exist: ${KIND_CLUSTER_NAME}"
  export_kubeconfig
  "${KUBECTL_BIN}" --kubeconfig "${KUBECONFIG_FILE}" get nodes -o wide
}

delete_cluster() {
  ensure_tools
  require_docker

  if ! cluster_exists; then
    printf 'Cluster %s is already absent.\n' "${KIND_CLUSTER_NAME}"
    return
  fi

  "${KIND_BIN}" delete cluster --name "${KIND_CLUSTER_NAME}"
  rm -f "${KUBECONFIG_FILE}"
}

show_versions() {
  printf 'cluster=%s\n' "${KIND_CLUSTER_NAME}"
  printf 'kind=%s\n' "${KIND_VERSION}"
  printf 'kubernetes=%s\n' "${KUBERNETES_VERSION}"
  printf 'node_image=%s\n' "${KIND_NODE_IMAGE}"
  printf 'kubeconfig=%s\n' "${KUBECONFIG_FILE}"
}

usage() {
  printf 'Usage: %s <up|status|validate|down|kubeconfig|versions>\n' "$0"
}

case "${1:-}" in
  up)
    create_cluster
    ;;
  status)
    show_status
    ;;
  validate)
    ensure_tools
    require_docker
    validate_cluster
    ;;
  down)
    delete_cluster
    ;;
  kubeconfig)
    ensure_tools
    require_docker
    cluster_exists || fail "Kind cluster does not exist: ${KIND_CLUSTER_NAME}"
    export_kubeconfig
    printf '%s\n' "${KUBECONFIG_FILE}"
    ;;
  versions)
    show_versions
    ;;
  *)
    usage >&2
    exit 2
    ;;
esac
