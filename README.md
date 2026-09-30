# PayGrid

Distributed payment gateway. Merchants onboard, take payments, vault cards, and get settled — all through one gateway API.

`com.saswat.paygrid` · Java 25 · Spring Boot 4.1 · Kubernetes namespace `paygrid-core`

## How a payment flows

1. Merchant signs up and logs in → gets a JWT.
2. Merchant creates an API key (`keyId` + secret).
3. Merchant calls the payments API with `Basic base64(keyId:secret)` and an idempotency key.
4. Gateway verifies the key, rate-limits, and forwards trusted identity headers downstream.
5. Payment service runs init → capture via state machine + saga/outbox over Kafka.
6. Operations settles and delivers webhooks signed with `X-PayGrid-Signature`.

```bash
# 1. Login (public route, no auth needed)
curl -X POST http://localhost:8080/v1/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"email":"shop@example.com","password":"secret"}'

# 2. Initiate a payment (API-key auth + idempotency)
curl -X POST http://localhost:8080/v1/payments \
  -u '<keyId>:<secret>' \
  -H 'Content-Type: application/json' \
  -H 'X-Idempotency-Key: order-1001' \
  -d '{"amount":1999,"currency":"INR","customerId":"<uuid>"}'
```

## Services

| Service | Port | Responsibility |
|---|---|---|
| `api-gateway-service` | 8080 | Entry point. JWT + API-key auth, rate limiting, identity propagation, docs proxy |
| `merchant-service` | 9010 | Signup/login, API keys, customers, webhook config |
| `payment-service` | 9020 | Payments, state machine, saga, outbox, idempotency |
| `operations-service` | 9030 | Settlements, webhook delivery |
| `vault-service` | 9040 | AES card tokenization |
| `config-service` | 8888 | Central config from git |
| `discovery-service` | 8761 | Eureka discovery |
| `common-lib` | — | Shared auth, cache, rate limit, OpenAPI setup |

Backing infra: Postgres, Redis, Kafka, Zipkin, Prometheus/Grafana.

## Quick start

```bash
docker compose up -d   # postgres, redis, kafka

export PSQL_USER=paygrid PSQL_PASSWORD=secret \
  REDIS_PASSWORD=secret JWT_SECRET=secret

./common-lib/mvnw -q install -o
./config-service/mvnw spring-boot:run &
./discovery-service/mvnw spring-boot:run &
./merchant-service/mvnw spring-boot:run &
./payment-service/mvnw spring-boot:run &
./vault-service/mvnw spring-boot:run &
./operations-service/mvnw spring-boot:run &
./api-gateway-service/mvnw spring-boot:run &  # http://localhost:8080
```

Service config comes from the Config Server git repo (`GITHUB_URI`); local `application.yaml` files only point at the config server. Full secret list lives in `k8s/k8s-secrets.env`.

## Auth reference

| Credential | Header | Verified by | Downstream headers |
|---|---|---|---|
| API key | `Basic base64(keyId:secret)` | `ApiKeyAuthHandler` (cache → lookup, BCrypt, 60/min Redis limit) | `X-Merchant-Id`, `X-Environment`, `X-Key-Id` |
| JWT | `Bearer <token>` | `JwtAuthHandler` / `JwtVerifier` | `X-Merchant-Id`, `X-User-Role` |

Failures return `401` (`429` + `Retry-After` when rate-limited) as `{"errorCode","errorDescription"}`. Swagger and `/v3/api-docs` bypass auth.

## API docs

* Per service: `http://localhost:<port>/swagger-ui/index.html`
* Aggregated through the gateway: `http://localhost:8080/<service>/v3/api-docs`

## Deploy to Kubernetes

```bash
kind create cluster --config k8s/kind-config.yaml
kubectl -k k8s/

# Release one service (images: docker.io/saspal02/paygrid-<service>:latest)
./payment-service/mvnw package jib:build
kubectl -n paygrid-core rollout restart deploy/payment-service
kubectl -n paygrid-core rollout status deploy/payment-service
```

Health: `/actuator/health` (also the K8s readiness probe). Gateway is reachable at `http://localhost:8080` via NodePort 30080.

## Layout

```
api-gateway-service/  merchant-service/  payment-service/
vault-service/  operations-service/  config-service/
discovery-service/  common-lib/
k8s/              # kind config, kustomization, infra/, services/, stateful/
docker-compose.yml  diagrams/  observability/
AGENTS.md         # code style and testing rules — read before contributing
```

No CI yet; deploys are manual per the steps above.
