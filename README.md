# Production Infrastructure Blueprint

A reference repository that takes a small distributed system from `git push` to a
monitored, alerting, zero-downtime production deployment — and shows what it costs to run.

The point is not that it uses Kubernetes. The point is that every layer is provisioned,
built, deployed, observed and operated from code in this repository, that the resulting
monthly bill is measured rather than assumed, and that the failure modes which only appear
once services talk to each other are handled rather than hoped away.

## Architecture

```
  internet ──▶ Traefik ──▶ api ──┬──▶ Postgres        (system of record)
                                 ├──▶ Redis           (cache + read model)
                                 └──▶ outbox table
                                          │
                                   outbox relay ──▶ Kafka ──▶ worker ──▶ Redis
                                                                 │
                                                          retry → item-events.DLT
```

A write commits the item and its event in one Postgres transaction. A relay publishes
committed events to Kafka. The worker consumes them idempotently and maintains an inventory
read model in Redis, which the API serves back. Nothing writes to two systems at once, so
there is no state the two services can disagree about permanently.

Four components, held at four. Each exists to demonstrate a specific operational problem:

| Component | The problem it demonstrates |
| --- | --- |
| Traefik | Service discovery that works the same way locally and in Kubernetes |
| api | Schema ownership, cache-aside reads, transactional outbox writes |
| worker | At-least-once delivery, idempotency, bounded retry, dead-letter handling |
| Postgres / Redis / Kafka | Three failure domains with three different health semantics |

## Quickstart

```bash
make bootstrap     # build, start everything, and prove it works
make smoke         # re-run the end-to-end assertions
make down          # stop and delete volumes
```

`make bootstrap` does not return successfully until a request has travelled the whole
system: through the gateway, into Postgres and the outbox, out to Kafka, through the worker
into Redis, and back out of the API's read endpoint.

| | |
| --- | --- |
| API (via gateway) | http://localhost:8080/api/v1/items |
| Inventory summary | http://localhost:8080/api/v1/inventory/summary |
| OpenAPI UI | http://localhost:8080/swagger-ui.html |
| Gateway dashboard | http://localhost:8081/dashboard/ |
| API health / metrics | http://localhost:9090/actuator/health |
| Worker health / metrics | http://localhost:9091/actuator/health |

Run `make help` for every available target, and `make doctor` to see which container
runtime and compose implementation it found. Docker and rootless podman are both supported.

## Stack

| Layer | Technology |
| --- | --- |
| Services | Java 21, Spring Boot 3.5 on virtual threads, Gradle multi-project |
| Gateway | Traefik v3 |
| Persistence | PostgreSQL 16, Spring Data JPA, Flyway |
| Cache / read model | Redis 7 |
| Messaging | Apache Kafka 4.x (KRaft, single broker) |
| Container | Multi-stage: layered JAR, `jlink` runtime, distroless non-root |
| Local stack | Docker Compose / podman-compose |
| Orchestration | k3s, Kustomize (base + overlays) |
| Provisioning | Terraform (Hetzner Cloud), Ansible |
| Delivery | GitHub Actions (build, scan, sign) → Flux v2 (reconcile) |
| Metrics / logs / traces | Prometheus + Alertmanager, Loki + Alloy, Tempo |
| Dashboards | Grafana, provisioned as JSON |

## What to look at first

- **`docs/cost-analysis.md`** — every performance and footprint number in this repository,
  with the method used to measure it. Image size went from 523 MB to 170 MB; a code-only
  deploy ships a 33 KB layer.
- **`docs/adr/`** — the decisions that were close calls, including the ones that cost
  something. ADR 0004 explains why Kafka was chosen despite being the most expensive line
  in the budget.
- **`services/api/src/main/java/dev/tahir/blueprint/outbox/`** — the transactional outbox,
  the reason a broker outage cannot corrupt state or take the API down.
- **`scripts/smoke-test.sh`** — what "working" is defined as, in executable form.

## Documentation

| | |
| --- | --- |
| `docs/architecture.md` | How the pieces fit and why |
| `docs/cost-analysis.md` | Measured footprint and the hosting comparison |
| `docs/security.md` | Hardening measures and the threat model they address |
| `docs/operations.md` | Running it: sizing, tuning, capacity |
| `docs/adr/` | Architecture decision records |
| `docs/runbooks/` | Incident procedures, written for whoever is on call |

## License

MIT
