# PayGrid — Distributed Payment Gateway (like Razorpay)

PayGrid is a Razorpay-style payment gateway built as Spring Cloud microservices. Merchants sign up, get a JWT, create scoped API keys, and accept payments through one gateway API with idempotency, rate-limiting, and PCI-safe card tokenization built in. It follows PCI-DSS compliance.

A **correct, idempotent, distributed payment flow** — order → payment → async bank resolution → settlement → webhook delivery — with the exact consistency patterns (outbox, distributed locking, idempotency) that production fintech systems depend on.

A **real Kubernetes deployment**: 6 services, 3 stateful data stores, all as actual `Deployment` / `StatefulSet` / `Service` / `ConfigMap` / `Secret` manifests, applied, restarted, scaled, and debugged against a live cluster — the same `kubectl` workflow used against any cloud cluster (EKS/GKE/AKS). Moving this to a hosted cloud changes _which node the API server runs on_ and _which managed services back the stateful pieces_ — not whether this is “deployed.”

A **fully wired observability stack** — Prometheus scraping every service, a custom Grafana dashboard built from scratch, Zipkin distributed tracing — wired in and ready to use for spotting bottlenecks live, not just installed and left unused.

## Architecture

A distributed, Kubernetes-native payment platform — order creation, payment authorization, bank callback simulation, settlement, webhooks — built across 7 microservices with the same architectural patterns real payment companies (Stripe, Razorpay, Adyen) use in production.

| Service | Responsibility |
| --- | --- |
| `api-gateway` | Auth (API key + BCrypt), rate limiting, routing |
| `payment-service` | Orders, payments, state machine, bank callback simulation |
| `merchant-service` | Merchant accounts, API keys, customers, webhooks |
| `operations-service` | Settlements, webhook delivery, outbox relay |
| `vault-service` | Card tokenization, encryption |
| `config-service` | Centralized config (Spring Cloud Config, git-backed) |
| `discovery-service` | Service registry |

Backed by Postgres (per-service databases), Redis (rate limiting, idempotency, caching, distributed locks), Kafka (event bus), and a full observability stack (Prometheus, Grafana, Zipkin), all running as a real Kubernetes deployment (Deployments, StatefulSets, Services, ConfigMaps, Secrets).

## Design patterns involved

| Pattern | Where | What breaks without it, at scale |
| --- | --- | --- |
| **Idempotency keys** | `X-Idempotency-Key` header, Redis-backed `IdempotencyFilter` | Retries are constant at scale (timeouts, LB failover, network blips) —<br>without this, retries create duplicate orders/charges |
| **Distributed scheduler locking** | `ShedLock` on `OutboxPoller`, `BankCallbackSimulator` | The moment you run >1 replica of any `@Scheduled` job,<br>every replica double-processes the same work |
| **Transactional outbox** | Outbox table + poller, atomic with the business write | “Write to DB” and “publish to Kafka” can’t both be guaranteed under partial failure without this —<br>a real distributed-systems bug, not an edge case |
| **Stateless services** | Every service — DB/Redis hold all state, not memory | The actual precondition for horizontal scaling. If two consecutive requests needed the same pod,<br>you couldn’t add replicas at all |
| **Rate limiting** | Redis-backed, per-API-key (token bucket / sliding / fixed window all implemented) | Protects the system from one bad client; without it, a single misbehaving integration takes everyone down |
| **Circuit breaker + retry** | Resilience4j around the `merchant-service` Feign call | More scale = more failure surface. This is what stops one slow dependency from cascading into a full outage |
| **Saga (orchestration + choreography)** | `saga/PaymentAuthorizationRecorder`, `PaymentServiceImpl`, `SettlementTransactionExecutor`, `WebhookKafkaConsumer` | A payment spans payment/order DB, bank/gateway, settlement DB, webhooks —<br>no single DB transaction can cover them; without compensating steps a partial failure leaves money in an inconsistent state |
| **Full observability** | Prometheus + Grafana (per-service CPU/memory), Zipkin tracing | You cannot capacity-plan — or debug — a system at scale you can’t see into |

### Transactional Outbox pattern

![diagram](diagrams/outbox_pattern.webp)

The Transactional Outbox pattern is used for reliable communication between the Payment Service and the Operations Service.

The Payment Service needs to do two things when a payment changes:

1. Update the payment in PostgreSQL.
2. Publish a payment event to Kafka for the Operations Service.

Doing these directly as:

```text
Payment DB update → Kafka publish
```

risks inconsistency. For example, the Payment Service may successfully update the payment status to `SUCCESS` in PostgreSQL, but Kafka publishing may fail. The Payment Service then shows `SUCCESS` while the Operations Service never receives the event.

To solve this, the payment update and the event write happen in one transaction:

```text
Payment Service
→ DB transaction
→ Update payment
→ Insert event into outbox table
→ COMMIT
```

Then, separately:

```text
Outbox table → Kafka → Operations Service
```

The payment update and the outbox insert happen inside the same database transaction, so whenever the payment update is committed, the corresponding event is also stored in the outbox table. A separate publisher reads pending outbox events and publishes them to Kafka.

This avoids a distributed transaction between PostgreSQL and Kafka while providing reliable event delivery between the Payment Service and the Operations Service.

### Saga pattern

The end-to-end payment flow is a Saga — one distributed transaction broken into local transactions with compensating actions instead of 2PC / distributed rollback:

Why it is needed: each step owns a different database (payment-service Postgres, operations-service Postgres) plus external systems (bank/PSP gateway, merchant webhook endpoint) and Kafka in between. No single ACID transaction can span them. If the gateway declines after the order was marked `ATTEMPTED`, or the bank transfer fails after the settlement row was created, the Saga compensates instead of rolling back.

How PayGrid implements it — orchestration inside a service, choreography across services:

1. Orchestrated authorization saga in `payment-service`
2. Choreographed settlement + webhook saga via outbox + Kafka

The rule of thumb is:

> One business transaction + multiple services/databases + no 2PC = Saga with local transactions and compensating (FAILED) states.

In PayGrid, there is no distributed rollback — every forward step has a defined compensation (`AUTHORIZE_FAIL`, settlement `FAILED`, webhook retry/DLQ), and the outbox guarantees each Saga event eventually reaches the next participant.

### Strategy design pattern

![diagram](diagrams/strategy_design_pattern.webp)

UPI, NetBanking, and Card payments are a good fit for the Strategy pattern: they perform the same high-level operation — processing a payment — with a different implementation per method.

For example:

- Payment Strategy
  - Card Payment Strategy
  - UPI Payment Strategy
  - NetBanking Payment Strategy

The Payment Service only knows that it needs to process a payment. It does not need to know how Card, UPI, or NetBanking works internally.

- Card may require card validation, authorization, and 3-D Secure.
- UPI may require UPI ID or intent handling, PSP communication, and asynchronous callbacks.
- NetBanking may require bank selection, redirection, authentication, and bank callbacks.

Without Strategy, this becomes a large `if-else` / `switch` block:

```text
if CARD → card logic
else if UPI → UPI logic
else if NET_BANKING → net banking logic
```

As more payment methods are added, this becomes harder to maintain and test.

With Strategy, each payment method has its own class with its specific processing logic. Adding a new method such as Wallet or EMI means adding another strategy instead of expanding the existing payment-processing code.

The rule of thumb is:

> Same business operation + different algorithms / implementations = Strategy pattern.

In PayGrid, this also isolates external integrations: each strategy talks to its own payment processor, PSP, or bank without the core `PaymentService` knowing those details.

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

The whole platform runs in a single local Kubernetes cluster (Kind): all 6 application services plus Postgres, Redis, Kafka, Zipkin, Prometheus, Grafana and Kafka UI.

**Prerequisites:** Docker, kind, `kubectl`, and `openssl`. Give Docker at least 10 GB of memory and make sure host port `8080` is free.

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

The script creates your secrets file if missing, creates the Kind cluster, deploys everything and waits until all pods are ready. First start takes 5–10 minutes, most of it pulling images and waiting for the services to settle.

### 4. Open the API

```text
http://localhost:8080/swagger-ui.html
```

### 5. Stop the project

```bash
./scripts/run-local.sh down
```

### Script commands

| Command | Description |
| --- | --- |
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

Only the API gateway is exposed to the host. Everything else is reachable through port-forwarding:

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

