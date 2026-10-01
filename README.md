# PayGrid — Distributed Payment Gateway (like Razorpay)

PayGrid is a Razorpay-style payment gateway built as Spring Cloud microservices. Merchants sign up, get a JWT, create scoped API keys, and accept payments through one gateway API with idempotency, rate-limiting, and PCI-safe card tokenization built in. It follows PCI-DSS compliance.

 A **correct, idempotent, distributed payment flow** — order → payment → async bank resolution → settlement → webhook delivery — with the exact consistency patterns (outbox, distributed locking, idempotency) that production fintech systems depend on.

 A **real Kubernetes deployment**: 6 services, 3 stateful data stores, all as actual
`Deployment`/`StatefulSet`/`Service`/`ConfigMap`/`Secret` manifests, applied, restarted, scaled, and debugged against a live cluster — the same `kubectl` workflow used against any cloud cluster (EKS/GKE/AKS). Moving this to a hosted cloud changes _which node the API server runs on_ and _which managed services back the stateful pieces_ — not whether this is “deployed.”

 A **fully wired observability stack** — Prometheus scraping every service, a custom Grafana
 dashboard built from scratch, Zipkin distributed tracing — wired in and ready to use for
 spotting bottlenecks live, not just installed and left unused.


## Architecture

A distributed, Kubernetes-native payment platform — order creation, payment authorization,
bank callback simulation, settlement, webhooks — built across 7 microservices with the same
architectural patterns real payment companies (Stripe, Razorpay, Adyen) use in production.

| Service | Responsibility |
| ----- | ----- |
| `api-gateway`  | Auth (API key + BCrypt), rate limiting, routing |
| `payment-service`  | Orders, payments, state machine, bank callback simulation |
| `merchant-service`  | Merchant accounts, API keys, customers, webhooks |
| `operations-service`  | Settlements, webhook delivery, outbox relay |
| `vault-service`  | Card tokenization, encryption |
| `config-service`  | Centralized config (Spring Cloud Config, git-backed) |
| `discovery-service`  | Service registry |

Backed by Postgres (per-service databases), Redis (rate limiting, idempotency, caching, distributed locks), Kafka (event bus), and a full observability stack (Prometheus, Grafana, Zipkin), all running as a real Kubernetes deployment (Deployments, StatefulSets, Services, ConfigMaps, Secrets).

## Tech-Stack

You don't need any of this installed to test — prebuilt images are pulled automatically; stack listed for reference.

- **Java 25**
- **Spring Boot 4.1**
- **Spring Cloud:** Gateway, Config Server, Eureka
- **Database:** PostgreSQL + Hibernate (`ddl-auto`)
- **Caching:** Redis
- **Messaging:** Apache Kafka (KRaft)
- **Security:** JJWT + BCrypt
- **Mapping:** MapStruct
- **Distributed Scheduling:** ShedLock
- **Resilience:** Resilience4j
- **Observability:** Prometheus + Grafana + Zipkin
- **Local Development:** Kind + Spring Cloud Config Server
- **Kubernetes:** Kind + Kustomize
- **Containerization:** Docker + Jib

## Run Locally

The whole platform runs in a single local Kubernetes cluster (Kind): all 6 application
services plus Postgres, Redis, Kafka, Zipkin, Prometheus, Grafana and Kafka UI.


**Prerequisites:** Docker, kind, `kubectl`, and `openssl`.
Give Docker at least 10 GB of memory and make sure host port `8080` is free.

### 1. Clone the project

```bash
git clone https://github.com/saspal02/distributed-payment-gateway.git
```

### 2. Go to the project directory

```bash
cd distributed-payment-gateway
```

### 3. Run the project

```bash
./scripts/run-local.sh
```

The script creates your secrets file if missing, creates the Kind cluster, deploys
everything and waits until all pods are ready. First start takes 5–10 minutes, most of it
pulling images and waiting for the services to settle.

### 4. Open the API

```
http://localhost:8080/swagger-ui.html
```

### 5. Stop the project

```bash
./scripts/run-local.sh down
```

### Script commands

| Command | Description |
| ----- | ----- |
| `./scripts/run-local.sh` | Create the cluster and deploy (same as `up`) |
| `./scripts/run-local.sh up` | Create the cluster and deploy |
| `./scripts/run-local.sh down` | Delete the cluster |
| `./scripts/run-local.sh fresh` | Delete the cluster and redeploy from scratch |
| `./scripts/run-local.sh status` | Show all pods and services |
| `./scripts/run-local.sh logs [pod]` | Follow the logs of a pod (all pods if omitted) |
| `./scripts/run-local.sh pf` | Port-forward Grafana, Prometheus, Zipkin and Kafka UI |
| `./scripts/run-local.sh secrets` | Regenerate `k8s/k8s-secrets.env` |
| `./scripts/run-local.sh up --timeout N` | Wait up to N seconds for the pods (default 600) |

### What the script does

```bash
cp k8s/k8s-secrets.env.example k8s/k8s-secrets.env
kind create cluster --config k8s/kind-config.yaml
kubectl apply -k k8s/
kubectl -n paygrid-core wait --for=condition=Ready pod --all --timeout=600s
```

To stop:

```bash
kind delete cluster
```

### Accessing the other services

Only the API gateway is exposed to the host. Everything else is reachable through
port-forwarding:

```bash
kubectl -n paygrid-core port-forward svc/grafana        3000:3000   # Grafana (admin / the GRAFANA_ADMIN_PASSWORD you set)
kubectl -n paygrid-core port-forward svc/prometheus     9090:9090   # Prometheus
kubectl -n paygrid-core port-forward svc/zipkin         9411:9411   # Zipkin
kubectl -n paygrid-core port-forward svc/kafka-ui       8090:8090   # Kafka UI
kubectl -n paygrid-core port-forward svc/postgres       5432:5432   # Postgres
kubectl -n paygrid-core port-forward svc/redis          6379:6379   # Redis
kubectl -n paygrid-core port-forward svc/kafka          9092:9092   # Kafka
kubectl -n paygrid-core port-forward svc/config-service 8888:8888   # Config server
```

Or run all four observability UIs at once with `./scripts/run-local.sh pf`.

