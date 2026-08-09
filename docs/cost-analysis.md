# Cost Analysis

Two kinds of number are in this document, and they are kept apart. **Footprints** — image size,
memory, start-up time, series, log bytes, backup sizes — were measured on this repository, and
the method is given for each so it can be reproduced or disputed. **Prices** are published list
prices, each with its source, as they stood in August 2026. No price here comes from an invoice;
the first month's bill will replace them.

The short version: the whole system — application, database, broker, cache, metrics, logs, traces,
backups — runs for about **€17 a month**. The same architecture on AWS is about **$168**, and the
managed services plus a hosted observability vendor that a team would more usually reach for come
to about **$650**. Sections 6 to 9 show the arithmetic and what it leaves out.

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

Postgres at 29 MB reflects an effectively empty database. In the kind cluster, just after a
restore with 30,000 rows, it was 88 MB; the 512 MiB limit is for the page cache and
`shared_buffers` a real dataset grows into.

The cluster adds what Compose does not run: a Postgres metrics exporter (6 MB), kube-state-metrics
restricted to one CronJob (10 MB) and the image signature webhook (28 MB), all measured in kind.
`docs/operations.md` adds every request and limit up against the node: **3.7 Gi requested of
8 GB**.

### What that means for the node

The application tier needs roughly 1.2 GB measured, and the observability stack adds
Prometheus, Loki, Grafana, Alloy and Tempo on top, plus k3s itself. That does not fit the
smallest ARM instance, and Kafka is the reason: at ~1.4 GB provisioned it is a third of the
entire budget, which is exactly the trade-off ADR 0004 accepts and records.

The honest framing is not "Kafka is cheap". It is that a self-hosted single-broker Kafka
costs one instance size, while the managed equivalent has a three-figure monthly floor —
section 7 puts a number on it.

## 5. What observing it costs

**Method.** The Compose stack with its observability overlay, measured with `podman stats` and
Prometheus' own `scrape_samples_post_metric_relabeling` after the smoke test and a broker-outage
drill, so every panel and alert had data to evaluate.

### Memory

| Container | Measured RSS | Limit |
| --- | ---: | ---: |
| Grafana | 178 MB | 256 MiB |
| Alloy | 115 MB | 192 MiB |
| Tempo | 110 MB | 256 MiB |
| Loki | 86 MB | 256 MiB |
| Prometheus | 77 MB | 384 MiB |
| Alertmanager | 21 MB | 64 MiB |
| **Total** | **~590 MB** | |

Metrics, logs, traces, alerting and dashboards for the whole system cost roughly half a
gigabyte — about one API replica. Prometheus is the one to watch as retention fills: its
figure here is a few hours of data, and the in-cluster limit (512 MiB) is set for thirty days of
this series count, not for this measurement.

### Series

| Target | Samples per scrape | Budget (`sample_limit`) |
| --- | ---: | ---: |
| api | 412 | 1,500 |
| worker | 543 | 1,200 |
| gateway | 344 | 1,000 |
| postgres (exporter, in the cluster) | 224 | 400 |
| grafana | 8 (of ~3,700 exported) | — |

The API's figure was **1,025** before this measurement. Micrometer's percentile histogram was
on for the request timer and published 75 buckets for every route, method and status
combination — 600 series for the eight combinations one smoke test produces, none of them a
threshold anything alerted on. Publishing only the six SLO boundaries took the API's scrape
down by 60%, and the worker's end-to-end timer from 69 buckets to 7. The saving grows with
every route added: 75 series each, before, and 7 after.

The Postgres exporter's default collectors publish 651 samples, most of them `pg_settings`
re-exporting a configuration file that is already in git. Five collectors publish 224.

Grafana exports more series about itself than the API and the worker combined. Everything
except whether it is up, how fast it answers and how much memory it holds is dropped at scrape
time.

At this scale the monitoring stack's own metrics are the majority of what Prometheus stores —
Loki, Prometheus, Tempo and Alertmanager together export about 3,700 samples per scrape against
the application's 1,300. That is the honest shape of a small system: the fixed cost of
observing it dominates until the application grows into it.

### Traces

Before `OperationalSpanFilter`, 176 of the 198 traces Tempo stored in ten minutes were the
outbox relay polling every second with nothing to publish, other scheduled tasks, and
Prometheus and health probes calling the actuator endpoints. After it, over the same kind of
window, every stored trace was a request or a consumed event. The metrics those spans came
from are unaffected; only the spans are dropped, at export.

### Logs

**Method.** Loki's own ingestion counters read before and after 9,051 requests of the steady k6
profile through the gateway (50 a second for three minutes, reads and writes mixed; none failed,
p99 38 ms), then a `bytes_over_time` query by service over the same window.

| | Per request | Share of bytes |
| --- | ---: | ---: |
| All services | **1,101 bytes, 1.48 lines** | |
| gateway (the access log: one line per request) | 809 bytes | 76% |
| api | 157 bytes | 15% |
| worker | 94 bytes | 9% |

Loki's flushed chunks compressed 4.4 to 1 on average. Those were small, idle-period chunks, so
dense production chunks will do at least as well; 4.4 is the figure used below.

The gateway's access log is three quarters of all log volume, and the obvious lever. The
availability and latency objectives already come from Traefik's router metrics, so a
successful request's log line records nothing the metrics do not — except its individual latency,
which is what one opens the access log for during a latency incident. Traefik can keep only
error and slow requests (`accesslog.filters`); that would cut log volume by about three quarters and
is not done yet, because at this traffic the whole log costs almost nothing to keep (section 8).

## 6. The monthly bill

**Everything in `infra/terraform`, at Hetzner's list prices** (EUR, excluding VAT, Germany, after
Hetzner's price adjustment of 15 June 2026). `terraform output estimated_monthly_eur` computes the
same total from the configuration.

| Item | €/month |
| --- | ---: |
| CAX21 — 4 Ampere vCPU, 8 GB, 80 GB disk, 20 TB of traffic | 10.49 |
| Primary IPv4 (IPv6 is free) | 0.50 |
| Volume, 20 GB — Postgres data and Kafka logs | 1.14 |
| Object storage — 1 TB stored and 1 TB egress included | 4.99 |
| Private network, cloud firewall, Cloudflare DNS | 0.00 |
| **Total** | **17.12** |

The CAX21 was €7.99 before June; the adjustment raised it by 31%, and this total by about
€2.50. The first version of the Terraform estimate still carried the old price. A cheap provider
is a price, not a promise (ADR 0001).

## 7. The same system elsewhere

**Two AWS builds**, both in us-east-1 — AWS's cheapest region, so the comparison leans in AWS's
favour; a European deployment would use Frankfurt and pay more — at on-demand list prices,
730 hours a month.

- **A. The same architecture on AWS.** Everything here, self-hosted inside EKS on one node the
  size of the CAX21's memory. The only managed pieces are the ones Hetzner does not charge for:
  the control plane and the load balancer.
- **B. The managed equivalent.** What a team would more usually build: RDS, ElastiCache and MSK for
  the three stores, a smaller node for the two services and the gateway, and Datadog instead of the
  self-hosted observability stack.

| | A: self-hosted on EKS | B: managed + Datadog |
| --- | ---: | ---: |
| EKS control plane ($0.10/h) | 73.00 | 73.00 |
| Node: m7g.large, 2 vCPU / 8 GiB ($0.0816/h) | 59.57 | |
| Node: t4g.medium, 2 vCPU / 4 GiB ($0.0336/h) | | 24.53 |
| Node disk, 20 GB gp3 ($0.08/GB) | 1.60 | 1.60 |
| Application Load Balancer ($0.0225/h + one LCU at $0.008/h) | 22.27 | 22.27 |
| Public IPv4 — two on the load balancer, one on the node ($0.005/h each) | 10.95 | 10.95 |
| Postgres: RDS db.t4g.small, single-AZ ($0.032/h) + 20 GB ($0.115/GB) | in-cluster | 25.66 |
| Redis: ElastiCache cache.t4g.small ($0.032/h) | in-cluster | 23.36 |
| Kafka: MSK, two kafka.m7g.large brokers — the minimum, one per zone ($0.204/h) + 40 GB | in-cluster | 301.84 |
| Object storage for backups and log chunks, ~10 GB of S3 | 0.23 | |
| Observability: Datadog, below | in-cluster | 167.53 |
| **Total, USD/month** | **167.62** | **650.74** |

**Datadog, sized from this repository's measurements** at annual list prices, for one host and a
modest one million requests a day:

| | USD/month |
| --- | ---: |
| Infrastructure Monitoring Pro, one host | 15.00 |
| APM, one host | 31.00 |
| Log ingestion: 1,101 bytes × 30 M requests = 33 GB at $0.10/GB | 3.30 |
| Log indexing, 15-day retention: 1.48 lines × 30 M = 44.4 M events at $1.70 per million | 75.48 |
| Custom metrics: the API's and worker's 955 series, 100 included, $5 per 100 beyond | 42.75 |
| **Total** | **167.53** |

The gateway's 344 series are left out as covered by Datadog's Traefik integration; so is every
series the monitoring stack exports about itself, which a vendor would not charge for because it
would not be running.

Against **€17.12**: build A costs roughly ten times as much, build B nearly forty times — 7 to 12
times and 29 to 47 times respectively for any exchange rate between 0.8 and 1.3 dollars to the
euro, so the conclusion does not depend on it.

**Where B's money goes** says more than its total. Kafka is 46% of it: MSK will not run fewer than
two brokers, and a broker is the smallest thing it sells. The observability vendor is another
quarter, and of that, log indexing is the largest line — at 1.48 events per request, it scales
linearly with traffic in a way the self-hosted stack's disk does not. The EKS control plane alone
costs four times this entire system.

## 8. What the data costs to keep

All of it fits in the 1 TB the object storage base price already includes.

| Data | Measured | 30 days at 1 M requests a day |
| --- | --- | ---: |
| Logs | 1,101 bytes a request raw, 4.4 to 1 compressed | 7.5 GB |
| Traces | scheduled tasks and probes dropped at export | a few GB |
| WAL archive | 16 KB for a segment `archive_timeout` closes on a quiet database; 80 MB compressed for 300,000 inserted rows | at most 4.6 MB a day idle, then proportional to writes |
| Base backups | 15 MB for a 205 MB database, 14 kept | 210 MB |

The `wal_recycle = off` setting is what makes the five-minute archive timeout cheap: without it, a
timed-out segment still holds the old WAL its file last carried and compresses like data rather
than like the zeros it now ends in.

The backup figures come from `make restore-drill`, whose rows repeat the same 200-character
description; real data compresses less, so treat 15 MB as a floor. The shape of the argument does
not change: at this system's size the backups are a rounding error inside a storage price that is
flat to 1 TB.

## 9. What this comparison does not say

- **It does not buy the same availability.** Build B's MSK spans two availability zones, which this
  system's single broker does not; its RDS is single-AZ, like the Postgres here, and multi-AZ would
  double that line. This system promises 99.5% because one node cannot promise more
  (`docs/slo.md`). Matching B's broker availability here means three nodes and a replicated
  Kafka, which roughly triples the node line and is still a fraction of MSK's floor.
- **It ignores commitments.** One-year reserved pricing takes a t4g.large from $0.067 to $0.042 an
  hour, and savings plans do similar things to EC2, RDS and ElastiCache. None of it applies to the
  EKS fee, MSK's minimum broker count, or log indexing, which are most of the difference.
- **It ignores labour.** Running Postgres, Kafka and an observability stack yourself takes time.
  This repository's runbooks, alerts, drills and the defects they found are an honest measure of
  how much — and a managed service does not remove all of it: someone still sizes, alerts on and
  restores an RDS instance.
- **It ignores egress.** The CAX21 includes 20 TB of traffic a month; AWS charges for data out to the
  internet beyond a small free allowance. At this system's traffic that is small; for a
  download-heavy service it can be the largest line of all.
- **Neither total includes VAT or tax.**

## 10. Still to be measured

- **An invoice.** Every price above is a list price. The first month's bill replaces section 6.
- Loki and the WAL archive against Hetzner's own object storage endpoint rather than an S3
  stand-in, including request latency from the node.
- Kafka's page-cache working set under sustained produce load, which decides whether its 1.4 GB
  is generous or merely adequate.
- Registry storage and egress under the real deploy cadence, where the 33 KB application layer
  should show up.
- Backup size and restore time on a dataset that compresses like real data.

## Sources

Prices as published in August 2026, excluding VAT and tax.

- Hetzner Cloud server prices after 15 June 2026: [Hetzner price adjustment](https://docs.hetzner.com/general/infrastructure-and-availability/price-adjustment/);
  primary IPv4, volume and object storage: [Hetzner Cloud pricing calculator](https://costgoat.com/pricing/hetzner),
  [Hetzner Object Storage](https://www.hetzner.com/storage/object-storage/); included traffic:
  [EU cloud cost comparison](https://www.eucloudcost.com/providers/hetzner/).
- AWS: [EKS](https://aws.amazon.com/eks/pricing/), [VPC and public IPv4](https://aws.amazon.com/vpc/pricing/),
  [Elastic Load Balancing](https://aws.amazon.com/elasticloadbalancing/pricing/),
  [EBS](https://aws.amazon.com/ebs/pricing/), [MSK](https://aws.amazon.com/msk/pricing/);
  instance prices via [Vantage](https://instances.vantage.sh/): m7g.large, t4g.large and
  cache.t4g.small; RDS db.t4g.small and gp3 storage via [Bytebase's RDS price list](https://www.bytebase.com/dbcost/rds-pricing/).
- Datadog: [price list](https://www.datadoghq.com/pricing/list/).
