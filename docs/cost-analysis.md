# Cost Analysis

Every number in this document was measured on this repository, not estimated. The method
is given for each so it can be reproduced or disputed.

Runtime hosting figures are added once the cluster exists; this first section covers the
container itself, because image size and memory footprint are what actually determine how
many replicas fit on a node, and therefore what the node has to cost.

---

## 1. Container image size

**Method.** Three images built from the same commit and the same 69 MB Spring Boot fat
JAR, measured with `podman images`. Reproduce with `make image && make image-size`.

> Figures below are for the API image. The worker image built from the same Dockerfile is
> **164 MB** — smaller because it carries no JPA, servlet or OpenAPI dependencies. Both come
> from one `Dockerfile` selected by `--build-arg SERVICE`, so the hardening applies to both
> by construction rather than by remembering to copy it.

| # | Approach | Base image | Image size | vs. naive |
| --- | --- | --- | ---: | ---: |
| 0 | Fat JAR on a JDK base | `eclipse-temurin:21-jdk-noble` | **523 MB** | — |
| 1 | Fat JAR on a JRE base | `eclipse-temurin:21-jre-alpine` | **279 MB** | −47% |
| 2 | Layered JAR + `jlink` runtime | `distroless/java-base-debian12:nonroot` | **170 MB** | **−67%** |

The shipped image is #2. Where the 170 MB goes:

| Layer | Size | Changes when |
| --- | ---: | --- |
| distroless base | ~36 MB | base image is bumped |
| `jlink` runtime | 65 MB | JDK version or module list changes |
| `/app/lib` (dependencies) | 69 MB | a dependency changes |
| `/app/app.jar` (application) | **33 KB** | every code change |

The layer split is the operationally interesting part. A typical deploy — application code
changed, dependencies unchanged — pushes and pulls **33 KB**, not 170 MB. Over a working
week of ten deploys across three replicas that is the difference between roughly 5 GB and
1 MB of registry egress and node pull traffic.

The `jlink` runtime is 63 MB against 345 MB for the full JDK it was cut from. The module
list is curated by hand: `./gradlew printJdepsModules` reports only `java.base,java.sql`,
because jdeps cannot see reflective loading, JDBC driver discovery or `ServiceLoader`
lookups. A runtime built from that output fails on the first request rather than at build
time, which is why `scripts/smoke-test.sh` — not the image build — is what certifies the
module list.

## 2. Startup time and the CDS trade-off

**Method.** Spring context refresh to exit, `-Dspring.context.exit=onRefresh`, three runs
per configuration on the same host, no database attached.

| Configuration | Run 1 | Run 2 | Run 3 | Mean |
| --- | ---: | ---: | ---: | ---: |
| Without CDS | 4.78 s | 4.93 s | 4.99 s | **4.90 s** |
| With CDS archive | 3.55 s | 3.69 s | 3.73 s | **3.66 s** |

**−25% startup, +88 MB image.**

This is why CDS is a separate build target (`make image-cds`) rather than the default.
For a long-lived pod, 1.24 seconds once per deploy does not justify a 52% larger image and
the registry traffic that comes with it. It becomes worth it when startup latency is on
the critical path — aggressive horizontal autoscaling, or scale-to-zero on non-production
environments, where the pod starts far more often than it is deployed.

Stating a default and the condition that flips it is the point. "We enabled CDS because it
is faster" is not an engineering decision; it is a benchmark quoted out of context.

## 3. Memory footprint and right-sizing

**Method.** Container run under a 512 MiB limit with `MaxRAMPercentage=70`, measured with
`podman stats` after the smoke-test workload.

| Metric | Value |
| --- | ---: |
| Container limit | 512 MiB |
| Steady-state RSS after smoke tests | **361 MB** |
| Utilisation against the limit | 67% |

The Kubernetes `resources.limits.memory` is set from this measurement, and the manifest
cites it. Two things follow from that:

- No `-Xmx` is hardcoded anywhere. The heap is derived from the cgroup limit via
  `MaxRAMPercentage`, so the limit is the single place this is tuned. A hardcoded heap and
  a container limit drift apart, and the pod is then OOM-killed by the kernel with no JVM
  diagnostics to explain why.
- The remaining 33% is headroom for metaspace, thread stacks, code cache and direct
  buffers — the non-heap consumption that a heap-only sizing exercise misses and that
  causes JVM containers to be killed while their heap graph looks healthy.

## 4. Whole-stack footprint

**Method.** All six containers running, measured with `podman stats` immediately after
`scripts/smoke-test.sh` completed, so every code path in the suite has been exercised.

| Container | Measured RSS | Limit | Utilisation |
| --- | ---: | ---: | ---: |
| api | 433 MB | 640 MiB | 68% |
| worker | 311 MB | 448 MiB | 66% |
| kafka | 326 MB | 1400 MiB | 22% |
| postgres | 29 MB | 512 MiB | 5% |
| gateway (Traefik) | 29 MB | 128 MiB | 22% |
| redis | 7 MB | 128 MiB | 5% |
| **Total measured** | **~1.14 GB** | | |

Two things this measurement changed:

- **The API limit went from 512 MiB to 640 MiB.** Adding the Redis and Kafka clients cost
  roughly 72 MB of resident memory over the single-service version, which put the old limit
  at 81% utilisation — no headroom for a GC pause or a traffic burst. The limit was raised
  because the number said so, not because 640 is a rounder guess than 512.
- **Kafka's limit is generous relative to its measured use** (22%), and deliberately.
  Kafka's real memory consumer is the page cache holding recent segments, which does not
  appear in the process RSS. Sizing it from RSS alone would produce a broker that looks
  fine in `stats` and reads every fetch from disk.

Postgres at 29 MB reflects an effectively empty database. It is the one figure here that
will not survive contact with real data, and the node budget below allows for that rather
than quoting it as a steady state.

### What that means for the node

The application tier needs roughly 1.2 GB measured, and the observability stack adds
Prometheus, Loki, Grafana, Alloy and Tempo on top, plus k3s itself. That does not fit the
smallest ARM instance, and Kafka is the reason: at ~1.4 GB provisioned it is a third of the
entire budget, which is exactly the trade-off ADR 0004 accepts and records.

The honest framing is not "Kafka is cheap". It is that a self-hosted single-broker Kafka
costs one instance size, while the managed equivalent has a three-figure monthly floor.

## 5. Still to be measured

- Monthly hosting: k3s on a Hetzner ARM node against the managed equivalent (EKS control
  plane, RDS, managed load balancer).
- Self-hosted Prometheus + Loki + Grafana against a SaaS observability vendor at the same
  ingest volume.
- Log volume per request, and what the retention tiers cost at that rate.
- Registry storage and egress under the real deploy cadence.
- Postgres memory under a realistic dataset rather than an empty schema.
- Kafka page-cache working set under sustained produce load.
