# Operations

Running the system from day to day: what it is sized for, how it changes, what runs on a
schedule, and what has actually been exercised. Incidents have their own runbooks in
`docs/runbooks/`; start at [incident-triage.md](runbooks/incident-triage.md).

## What fits on the node

One CAX21: four Ampere vCPUs and 8 GB. Every request and limit in `deploy/k8s` cites the
measurement it came from; this table adds them up.

| Workload | Replicas | Memory request | Memory limit | Measured |
| --- | ---: | ---: | ---: | --- |
| api | 2 (HPA to 4) | 512 Mi each | 640 Mi each | 433 MB resident after the smoke workload |
| worker | 1 | 384 Mi | 448 Mi | 311 MB |
| kafka | 1 | 1 Gi | 1400 Mi | 326 MB resident; the page cache is the real consumer |
| postgres | 1 | 256 Mi | 512 Mi | 29 MB empty; 88 MB in kind with 30,000 rows, just after a restore |
| postgres-exporter | 1 | 16 Mi | 48 Mi | 6 MB |
| redis | 1 | 64 Mi | 160 Mi | 7 MB |
| **application** | | **2.7 Gi** | **3.8 Gi** | |
| Prometheus, Alertmanager, kube-state-metrics | | 304 Mi | 624 Mi | 77, 21 and 10 MB |
| Loki, Tempo, Alloy, Grafana | | 448 Mi | 1216 Mi | 86, 110, 115 and 178 MB |
| **observability** | | **0.7 Gi** | **1.8 Gi** | 590 MB measured for the six |
| Traefik, cert-manager, policy-controller | | 272 Mi | 576 Mi | 28 MB for the webhook |
| **everything scheduled** | | **3.7 Gi** | **6.1 Gi** | |

k3s and Flux's controllers are the rest of the node's fixed overhead and are not in the table.

Two things follow:

- **Requests are what the scheduler guarantees, and 3.7 Gi of them fit with room.** At the HPA
  ceiling the API adds two more replicas and requests reach 4.7 Gi.
- **Limits add up to more than the node,** which is deliberate: they are ceilings a container is
  killed at, not reservations, and they never all peak at once. What would make them peak
  together is a load spike, which is what the HPA and the objectives exist to catch.

The dev overlay is not on the node. Nothing in `clusters/prod` reconciles it; the zero-downtime
drill deploys it into kind. Putting it on this node would add 2.1 Gi of requests, half of that a
second Kafka, and scaling it to zero outside working hours would be the first thing to do.

## Connection budget

Postgres allows 50 connections (`deploy/k8s/base/postgres/config/postgresql.conf`), spent as:

| Client | Connections |
| --- | ---: |
| API, 4 replicas at the HPA ceiling, 8 pooled each | 32 |
| one extra API pod during a rollout at that ceiling | 8 |
| the migration Job | up to 8, briefly |
| postgres-exporter | 1 |
| superuser reserve | 3 |

The base backup streams over a replication connection, counted against `max_wal_senders`. Raising
`DB_POOL_MAX` without raising `max_connections` moves the queue from the pool into Postgres, where
it is harder to see. Pool exhaustion is visible instead: `DatabasePoolExhausted` raises a ticket,
and a request that waits longer than three seconds is answered 503 with `Retry-After`.

## Changing things

Everything below is a commit. Flux applies it; nothing is changed with `kubectl edit`.

**Application code.** Merge to `main`. CI builds, scans and signs the image, image automation
commits the new tag within fifteen minutes, and the rollout replaces pods one at a time with the
drain. Schema changes follow [zero-downtime-migration.md](runbooks/zero-downtime-migration.md).

**Postgres configuration.** Edit `deploy/k8s/base/postgres/config/postgresql.conf`. The ConfigMap is
updated in place — it deliberately has no content hash, so an edit does not restart the database —
and the kubelet refreshes the mounted file within about a minute. Then:

```bash
kubectl -n blueprint exec postgres-0 -c postgres -- psql -U blueprint -d blueprint -c 'SELECT pg_reload_conf()'
kubectl -n blueprint exec postgres-0 -c postgres -- psql -U blueprint -d blueprint \
  -c "SELECT name, setting FROM pg_settings WHERE pending_restart"
```

A setting listed as `pending_restart` — `shared_buffers`, `max_connections`, `archive_mode`,
`wal_level` — needs `kubectl -n blueprint rollout restart statefulset/postgres`. That is a short
outage: readiness fails and the gateway returns 503 until the database is back. Do it at a quiet
hour. The archive and backup scripts need nothing: they are read on every run.

**A secret.** `sops deploy/k8s/config/prod/<file>.enc.yaml`, commit. Pods read secrets as files, so
most pick up a rotated value within a minute or two; the database password also has to be changed
in Postgres itself (`ALTER ROLE`) in the same window.

**A chart or an image version.** Renovate opens the pull request. The HelmRelease upgrades with
automatic rollback on failure; check `flux get helmreleases -A` after it merges.

**k3s.** Change `k3s_version` in `infra/ansible/roles/k3s/defaults/main.yml` and run `make provision`.
The node restarts its control plane and every pod, so this is planned downtime of a few minutes,
within the budget the availability objective allows for exactly this (`docs/slo.md`).

**The node itself.** `terraform plan` shows the replacement; the volume and the primary IP survive
it. After `make tf-apply` and `make provision`, Flux reconciles everything from git. The data comes
back from the volume, or from object storage if the volume is lost too
([db-restore.md](runbooks/db-restore.md)).

## Scaling

| Component | How | Limit |
| --- | --- | --- |
| api | HPA on CPU at 70%, 2 to 4 replicas | the connection budget above, then the node |
| worker | not at all past one replica | `item-events` has three partitions and the worker runs three consumer threads; more consumers would sit idle. More throughput is more partitions, a topic change with an ordering consequence |
| Postgres, Redis, Kafka | vertically, by changing requests and limits | the node. Beyond it is more nodes, replicas of the stores, and ADR 0001's "when to revisit" |

## What runs on a schedule

| When | What | If it stops |
| --- | --- | --- |
| continuously, at most every 5 minutes | WAL segments archived to object storage | `PostgresWalArchivingFailing` |
| 02:30 UTC daily | base backup (`postgres-backup` CronJob) | `PostgresBackupMissing` |
| every 5 / 10 minutes | Flux image scan / reconciliation | `flux get all -A` shows it; a failed stage stops the ones after it |
| 30 days before expiry | certificate renewal | `CertificateExpiringSoon` |
| daily, on the node | unattended security upgrades | a reboot shows as restart markers on every dashboard |
| Monday 05:00 UTC | re-scan of the published images for new CVEs | the `security` workflow fails |
| Tuesday 04:00 UTC | restore drill and zero-downtime drill | the `drills` workflow fails |
| continuously | Watchdog forwarded to the dead man's switch | the external service raises the alarm |

Retention: 30 days of metrics (the error-budget window), 30 days of logs in Loki and in the bucket,
14 days of base backups and WAL, 24 hours of Kafka log.

## What has been run, and where

The difference between a configuration that validates and one that works was found, repeatedly,
by running it. So this is stated plainly.

| Exercised | Where | How |
| --- | --- | --- |
| The whole application, observability included | Compose, rootless podman and Docker | `make bootstrap`: 27 assertions including the async path end to end |
| Load against the objectives | Compose | `make load`: 7,251 requests over two minutes, none failed, p99 37 ms |
| A rolling restart under load | kind, the dev overlay | `make drill`: 0 failed requests of 6,001; weekly in CI with half the traffic writes |
| WAL archiving, a backup, and a point-in-time restore | containers, production's config and scripts | `make restore-drill`: to the second, checksums and indexes verified; weekly in CI |
| The same, through the StatefulSet, CronJob and restore Job | kind, production's manifests | the restore runbook, command by command: 12 seconds from stop to promoted |
| Signature admission | kind, the production chart values | signed image admitted; unsigned, and signed by the wrong workflow, refused |
| Every manifest against the Kubernetes 1.33 schemas | CI | `make k8s-validate` |
| Terraform and Ansible | CI | `terraform validate` against the real provider schemas; `ansible-lint` at the production profile |

**Not yet exercised against a live Hetzner project:** `terraform apply`, the Ansible playbook on a
real node, Flux bootstrapping from this repository, certificate issuance from Let's Encrypt, and
Loki and the WAL archive against Hetzner's object storage endpoint rather than an S3 stand-in.
Each of those is a first-deploy risk, and the first deploy should be watched with that list in
hand.
