#!/usr/bin/env bash
#
# Runs PayGrid locally in a single Kind cluster.
#
#   ./scripts/run-local.sh            same as "up"
#   ./scripts/run-local.sh up         create the cluster and deploy
#   ./scripts/run-local.sh down       delete the cluster
#   ./scripts/run-local.sh fresh      delete the cluster and redeploy
#   ./scripts/run-local.sh status     show all pods
#   ./scripts/run-local.sh logs [pod] follow logs for one pod, or all of them
#   ./scripts/run-local.sh pf         port-forward Grafana, Prometheus, Zipkin, Kafka UI
#   ./scripts/run-local.sh secrets    regenerate k8s/k8s-secrets.env
#
# Options for "up" and "fresh":
#   --build        build images from source and load them into the cluster
#   --timeout N    seconds to wait for all pods (default 600)

set -euo pipefail

readonly ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
readonly K8S_DIR="${ROOT_DIR}/k8s"
readonly KIND_CONFIG="${K8S_DIR}/kind-config.yaml"
readonly KUSTOMIZATION="${K8S_DIR}/kustomization.yaml"
readonly SECRETS_FILE="${K8S_DIR}/k8s-secrets.env"
readonly SECRETS_EXAMPLE="${K8S_DIR}/k8s-secrets.env.example"
readonly NAMESPACE="paygrid-core"
readonly GATEWAY_PORT="8080"
readonly READY_TIMEOUT_DEFAULT="600"
readonly MIN_DOCKER_MEMORY_BYTES=$((8 * 1024 * 1024 * 1024))
readonly IMAGE_PREFIX="docker.io/saspal02/paygrid-"
readonly MODULES=(config-service api-gateway-service merchant-service payment-service operations-service vault-service)

CLUSTER_NAME="paygrid"
READY_TIMEOUT="${READY_TIMEOUT_DEFAULT}"
DO_BUILD="false"

info() { printf '\033[0;36m==>\033[0m %s\n' "$*"; }
warn() { printf '\033[0;33mwarning:\033[0m %s\n' "$*" >&2; }
fail() { printf '\033[0;31merror:\033[0m %s\n' "$*" >&2; exit 1; }

require_command() {
    command -v "$1" >/dev/null 2>&1 || fail "'$1' is required but was not found on PATH."
}

cluster_exists() {
    kind get clusters 2>/dev/null | grep -qx "${CLUSTER_NAME}"
}

# Every kubectl call targets the Kind cluster explicitly, so a read command can never
# query whichever context happened to be active.
use_cluster_context() {
    require_command kind
    require_command kubectl
    if ! cluster_exists; then
        fail "Cluster '${CLUSTER_NAME}' was not found. Create it with './scripts/run-local.sh up'."
    fi
    kind export kubeconfig --name "${CLUSTER_NAME}" >/dev/null
    kubectl config use-context "kind-${CLUSTER_NAME}" >/dev/null
}

# A raw string of at least 32 characters. Keys.hmacShaKeyFor() uses the string
# bytes directly, so this is not base64-decoded.
generate_raw_secret() {
    openssl rand -base64 48 | tr -d '\n'
}

# Base64 of exactly 32 bytes. AesEncryptionConfig base64-decodes this into an
# AES/GCM key, which must be 16, 24 or 32 bytes long.
generate_aes_key() {
    openssl rand -base64 32
}

replace_secret() {
    local key="$1"
    local value="$2"
    local tmp
    tmp="$(mktemp)"
    sed "s|^${key}=.*|${key}=${value}|" "${SECRETS_FILE}" >"${tmp}"
    mv "${tmp}" "${SECRETS_FILE}"
    chmod 600 "${SECRETS_FILE}"
}

create_secrets() {
    [ -f "${SECRETS_EXAMPLE}" ] || fail "${SECRETS_EXAMPLE} is missing."
    info "Creating ${SECRETS_FILE#"${ROOT_DIR}/"}"
    cp "${SECRETS_EXAMPLE}" "${SECRETS_FILE}"
    replace_secret "JWT_SECRET" "$(generate_raw_secret)"
    replace_secret "VAULT_MASTER_KEY" "$(generate_aes_key)"
    replace_secret "WEBHOOK_SECRET" "$(generate_aes_key)"
    chmod 600 "${SECRETS_FILE}"
}

regenerate_secrets() {
    [ -f "${SECRETS_FILE}" ] || { create_secrets; return; }
    info "Regenerating cryptographic keys and passwords"
    replace_secret "JWT_SECRET" "$(generate_raw_secret)"
    replace_secret "VAULT_MASTER_KEY" "$(generate_aes_key)"
    replace_secret "WEBHOOK_SECRET" "$(generate_aes_key)"
    replace_secret "PSQL_PASSWORD" "$(generate_raw_secret)"
    replace_secret "REDIS_PASSWORD" "$(generate_raw_secret)"
    replace_secret "MERCHANT_DB_PASSWORD" "$(generate_raw_secret)"
    replace_secret "PAYMENT_DB_PASSWORD" "$(generate_raw_secret)"
    replace_secret "OPERATIONS_DB_PASSWORD" "$(generate_raw_secret)"
    replace_secret "VAULT_DB_PASSWORD" "$(generate_raw_secret)"
    warn "Run './scripts/run-local.sh fresh' for the new database passwords to take effect."
    warn "The Postgres init script only runs when the volume is first initialised."
}

port_in_use() {
    local port="$1"
    if command -v ss >/dev/null 2>&1; then
        ss -ltn 2>/dev/null | awk '{print $4}' | grep -qE "[:.]${port}\$"
        return
    fi
    if command -v lsof >/dev/null 2>&1; then
        lsof -iTCP:"${port}" -sTCP:LISTEN >/dev/null 2>&1
        return
    fi
    return 1
}

preflight() {
    require_command docker
    require_command kind
    require_command kubectl
    require_command openssl

    docker info >/dev/null 2>&1 || fail "Docker is not running."

    local memory
    memory="$(docker info --format '{{.MemTotal}}' 2>/dev/null || echo 0)"
    if [ "${memory}" -gt 0 ] && [ "${memory}" -lt "${MIN_DOCKER_MEMORY_BYTES}" ]; then
        warn "Docker has less than 8 GB of memory. Pods may stay Pending."
        warn "Increase the memory limit in your Docker Desktop settings."
    fi

    if port_in_use "${GATEWAY_PORT}"; then
        fail "Host port ${GATEWAY_PORT} is already in use. Stop whatever is using it, or edit the hostPort in k8s/kind-config.yaml."
    fi

    if [ ! -f "${SECRETS_FILE}" ]; then
        create_secrets
    else
        info "Using existing ${SECRETS_FILE#"${ROOT_DIR}/"}"
    fi
}

build_images() {
    require_command java
    info "Installing common-lib"
    (cd "${ROOT_DIR}/common-lib" && ./mvnw -B install -DskipTests -Djib.skip=true)
    local module
    for module in "${MODULES[@]}"; do
        info "Building ${module}"
        (cd "${ROOT_DIR}/${module}" && ./mvnw -B package jib:dockerBuild -DskipTests)
    done
    info "Loading images into the ${CLUSTER_NAME} cluster"
    for module in "${MODULES[@]}"; do
        kind load docker-image "docker.io/saspal02/paygrid-${module}:latest" --name "${CLUSTER_NAME}"
    done
}

# Every manifest pins imagePullPolicy: Always, which makes Kubernetes pull from the
# registry and ignore the images just loaded into the cluster. Point it at the local ones.
# Only the PayGrid images are touched, so third-party deployments such as kafka-ui keep
# their own pull policy.
use_local_images() {
    info "Switching deployments to the locally built images"
    local deployment container image
    while read -r deployment container image; do
        [ -n "${deployment}" ] || continue
        case "${image}" in
            "${IMAGE_PREFIX}"*) ;;
            *) continue ;;
        esac
        kubectl --namespace "${NAMESPACE}" patch deployment "${deployment}" --type=strategic \
            --patch "{\"spec\":{\"template\":{\"spec\":{\"containers\":[{\"name\":\"${container}\",\"imagePullPolicy\":\"IfNotPresent\"}]}}}}" \
            >/dev/null
        printf '  %s\n' "${deployment}"
    done < <(kubectl --namespace "${NAMESPACE}" get deployment \
        -o jsonpath='{range .items[*]}{.metadata.name}{" "}{range .spec.template.spec.containers[*]}{.name}{" "}{.image}{" "}{end}{"\n"}{end}')
}

ensure_cluster() {
    if cluster_exists; then
        info "Reusing existing cluster '${CLUSTER_NAME}'"
    else
        info "Creating cluster '${CLUSTER_NAME}'"
        kind create cluster --name "${CLUSTER_NAME}" --config "${KIND_CONFIG}" --wait 120s
    fi
    use_cluster_context
}

apply_manifests() {
    info "Deploying manifests"
    kubectl apply -k "${KUSTOMIZATION}"
}

wait_for_rollout() {
    info "Waiting up to ${READY_TIMEOUT}s for all pods in ${NAMESPACE} to be ready"
    if ! kubectl wait --namespace "${NAMESPACE}" --for=condition=Ready pod --all \
        --timeout="${READY_TIMEOUT}s"; then
        printf '\n'
        kubectl get pods --namespace "${NAMESPACE}" || true
        printf '\n'
        warn "Pods did not become ready. Inspect them with:"
        warn "  kubectl -n ${NAMESPACE} describe pod <name>"
        warn "  kubectl -n ${NAMESPACE} logs <name> --tail=100"
        exit 1
    fi
}

print_summary() {
    printf '\n'
    info "PayGrid is running"
    printf '\n'
    printf '  API gateway  http://localhost:%s\n' "${GATEWAY_PORT}"
    printf '  Swagger UI   http://localhost:%s/swagger-ui.html\n' "${GATEWAY_PORT}"
    printf '  Health       http://localhost:%s/actuator/health\n' "${GATEWAY_PORT}"
    printf '\n'
    printf '  Grafana, Prometheus, Zipkin and Kafka UI are not exposed to the host.\n'
    printf "  Run './scripts/run-local.sh pf' to forward them.\n"
    printf '\n'
    printf '  Stop everything with ./scripts/run-local.sh down\n'
    printf '\n'
}

cmd_up() {
    preflight
    ensure_cluster
    if [ "${DO_BUILD}" = "true" ]; then
        build_images
    fi
    apply_manifests
    if [ "${DO_BUILD}" = "true" ]; then
        use_local_images
    fi
    wait_for_rollout
    print_summary
}

cmd_down() {
    require_command kind
    if cluster_exists; then
        info "Deleting cluster '${CLUSTER_NAME}'"
        kind delete cluster --name "${CLUSTER_NAME}"
    else
        info "No cluster named '${CLUSTER_NAME}' found"
    fi
}

cmd_fresh() {
    cmd_down
    cmd_up
}

cmd_status() {
    use_cluster_context
    kubectl get pods,svc --namespace "${NAMESPACE}"
}

cmd_logs() {
    use_cluster_context
    if [ "$#" -ge 1 ] && [ -n "$1" ]; then
        kubectl logs --namespace "${NAMESPACE}" --follow --tail=200 "$1"
        return
    fi
    kubectl logs --namespace "${NAMESPACE}" --all-containers --prefix --tail=100 \
        --follow --max-log-requests=20 --selector app
}

cmd_pf() {
    use_cluster_context
    local pids=()
    cleanup() {
        local pid
        for pid in ${pids[@]+"${pids[@]}"}; do
            kill "${pid}" 2>/dev/null || true
        done
    }
    trap cleanup EXIT INT TERM

    info "Forwarding observability UIs. Press Ctrl-C to stop."
    local mapping service rest port
    for mapping in grafana:3000:3000 prometheus:9090:9090 zipkin:9411:9411 kafka-ui:8090:8090; do
        service="${mapping%%:*}"
        rest="${mapping#*:}"
        port="${rest%%:*}"
        if port_in_use "${port}"; then
            warn "Host port ${port} is already in use; ${service} will not be forwarded."
            continue
        fi
        printf '  %-12s http://localhost:%s\n' "${service}" "${port}"
        kubectl port-forward --namespace "${NAMESPACE}" "svc/${service}" "${rest}" &
        pids+=("$!")
    done
    printf '\n'
    wait
}

cmd_secrets() {
    regenerate_secrets
    info "Done. Review ${SECRETS_FILE#"${ROOT_DIR}/"}"
}

parse_run_args() {
    local command="$1"
    shift
    while [ "$#" -gt 0 ]; do
        case "$1" in
            --build)
                DO_BUILD="true"
                ;;
            --timeout)
                shift
                [ "$#" -gt 0 ] || fail "--timeout needs a value in seconds."
                READY_TIMEOUT="$1"
                ;;
            *)
                fail "Unknown option for '${command}': $1"
                ;;
        esac
        shift
    done
}

main() {
    local command="${1:-up}"
    [ "$#" -gt 0 ] && shift || true

    case "${command}" in
        up)
            parse_run_args "${command}" "$@"
            cmd_up
            ;;
        down|delete)
            cmd_down
            ;;
        fresh|reset)
            parse_run_args "${command}" "$@"
            cmd_fresh
            ;;
        status)
            cmd_status
            ;;
        logs)
            cmd_logs "$@"
            ;;
        pf|port-forward)
            cmd_pf
            ;;
        secrets)
            cmd_secrets
            ;;
        -h|--help|help)
            awk 'NR > 1 && /^#/ { sub(/^# ?/, ""); print; next } NR > 1 { exit }' "${BASH_SOURCE[0]}"
            ;;
        *)
            fail "Unknown command '${command}'. Try './scripts/run-local.sh --help'."
            ;;
    esac
}

main "$@"
