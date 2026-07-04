# Production Infrastructure Blueprint

A reference repository that takes a real backend service from `git push` to a monitored,
alerting, zero-downtime production deployment — and shows what it costs to run.

The point is not that it uses Kubernetes. The point is that every layer is provisioned,
built, deployed, observed and operated from code in this repository, and that the resulting
monthly bill is measured rather than assumed.

## Stack

| Layer | Technology |
| --- | --- |
| Service | Java 21, Spring Boot 3.4 (Spring MVC on virtual threads) |
| Persistence | PostgreSQL 16, Spring Data JPA, Flyway |
| Build | Gradle 8 (Kotlin DSL), version catalog |
| Container | Multi-stage: layered JAR, `jlink` runtime, distroless non-root |
| Local stack | Docker Compose |
| Orchestration | k3s, Kustomize (base + overlays) |
| Provisioning | Terraform (Hetzner Cloud), Ansible |
| Delivery | GitHub Actions (build, scan, sign) → Flux v2 (reconcile) |
| Metrics | Prometheus, Alertmanager, Micrometer |
| Logs | Loki, Grafana Alloy |
| Traces | Tempo, OpenTelemetry |
| Dashboards | Grafana, provisioned as JSON |

## Status

Work in progress. See `docs/` for architecture notes and decision records.
