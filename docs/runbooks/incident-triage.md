# Runbook: incident triage

**Use when:** something is wrong and you do not yet know what. A page, a user report, a graph
that looks wrong. The other runbooks start from a diagnosis; this one gets you to one.

**The goal of the first fifteen minutes** is not the root cause. It is to stop the damage, then
to know which runbook applies. Root cause is for afterwards, when nobody is waiting.

---

## 1. Is it real, and is it us?

```bash
curl -sS -o /dev/null -w '%{http_code} %{time_total}s\n' https://blueprint.tahir.dev/api/v1/items
make alerts                                   # or Alertmanager: what else is firing
```

Open **Blueprint / Overview** in Grafana. It is laid out in the order a request meets the
system — objectives, gateway, API, worker, logs — and every graph marks pod restarts.

- The objectives are fine and one alert fired: go to that alert's section in
  [alerts.md](alerts.md). This is not an incident yet.
- `curl` fails with a TLS error: the certificate. The error-budget alerts cannot see this — the
  handshake fails before any router metric counts the request — so go straight to
  [cert-expiry.md](cert-expiry.md).
- `curl` hangs or is refused: the gateway or the node. Step 4.

Say in the incident channel that you are looking, even before you know anything.

## 2. What changed?

Most incidents follow a change. Find it before debugging anything.

```bash
flux get kustomizations                                    # anything not Ready, or suspended?
git log --oneline -10 -- deploy/k8s clusters               # image bumps land here as commits
kubectl -n blueprint get events --sort-by=.lastTimestamp | tail -20
kubectl get nodes -o wide                                   # AGE: did the node just reboot?
```

- **A deploy in the last hour** — the image automation commit `chore(deploy)...`, a merge to
  `main`, a restart marker on the graphs — and users are affected: roll back first, debug after.
  [rollback.md](rollback.md).
- **The node rebooted.** Unattended security upgrades run on it (the Ansible `base` role). Pods
  restart, the JVMs warm up, and the objectives dip for a few minutes. If they do not recover,
  continue.
- **A Renovate merge** moved a base image or a chart: treat it like a deploy.

## 3. Where in the request path?

Follow the request. Each hop has one question and one place to look.

| Hop | Question | Look at | Next |
| --- | --- | --- | --- |
| Gateway | Is Traefik routing? | Overview → *Requests by status*; `kubectl -n traefik get pods` | 503 from the gateway: no ready API pod |
| API | Are pods ready? | `kubectl -n blueprint get pods`; *Server errors by route* | a 500 is a bug; a 503 with `Database unavailable` in the body is the pool |
| Postgres | Is it up, and is anything holding locks? | `pg_isready`; `pg_stat_activity` | [DatabasePoolExhausted](alerts.md#databasepoolexhausted) |
| Redis | Is it up? | *Cache requests by result*; `redis-cli PING` | [CacheUnavailable](alerts.md#cacheunavailable) |
| Outbox | Are events leaving? | **Async pipeline** → *Outbox pending* | [OutboxRelayStalled](alerts.md#outboxrelaystalled) |
| Kafka | Is the broker answering? | `kafka-broker-api-versions.sh` | the relay section above |
| Worker | Is it consuming? | *Lag by partition*, *Listener failures* | [ConsumerLagHigh](alerts.md#consumerlaghigh) |

The response code tells you which hop answered:

- **503 from the gateway** — no API pod was ready. Readiness gates on Postgres and Redis, so one
  of them is usually why.
- **502 or 504** — a pod died or hung mid-request: OOM kills, a stuck thread, a rollout without
  the drain.
- **503 from the API**, body `Database unavailable` — the pool timed out waiting for a connection.
  Overload or a slow database, not a bug.
- **500** — a bug. *Server errors by route* says which endpoint; the log line carries the trace
  id, and the trace shows where it failed.

Writes succeeding while the inventory summary is stale is not an outage. It is the outbox doing
its job while Kafka or the worker is down (ADR 0007). Fix it, but it is a ticket, not a page.

## 4. The node

One node runs everything, so a node problem looks like everything failing at once.

```bash
kubectl get nodes
kubectl top nodes && kubectl top pods -A --sort-by=memory | head -15
kubectl get pods -A | grep -vE 'Running|Completed'
ssh <node> 'df -h / /var/lib/rancher; free -m; uptime'
```

- **Disk full** — Kafka logs, Postgres WAL (an archive that stopped shipping; see
  [PostgresWalArchivingFailing](alerts.md#postgreswalarchivingfailing)), container images, the
  journal. Find which before deleting anything.
- **Memory** — `OOMKilled` in `kubectl describe pod`. Every limit in the manifests cites the
  measurement it came from; a pod past its limit is using more than was measured, and that is the
  thing to find.
- **Unreachable** — check the Hetzner console. If the server is gone, it is rebuilt from Terraform
  and Ansible and Flux does the rest; the data volume outlives the server.

## 5. Stabilise before you diagnose

In order of how quickly they buy time:

| Action | When |
| --- | --- |
| Roll back | anything that started with a deploy — [rollback.md](rollback.md) |
| Restart one pod | a single wedged pod, after capturing `kubectl logs --previous` |
| Suspend Flux | before any manual change, or Flux reverts it: `flux suspend kustomization apps` |
| Scale the API | CPU-bound under a traffic spike and the node has room |
| Restore the database | only when data is wrong or gone — [db-restore.md](db-restore.md) |

Restarting the API does not fix a stalled outbox, a full disk or a slow query. It adds a cold
start to whatever was already wrong.

## 6. Closing

- Every Flux Kustomization back to Ready and none suspended: `flux get kustomizations`.
- The objectives recovering on **Blueprint / SLOs**, and how much budget the incident spent.
- An incident record: timeline, what users saw, what fixed it, what would have caught it sooner.
  Anything that spent more than a fifth of a budget gets a written review under the error budget
  policy in [docs/slo.md](../slo.md).
- If an alert should have fired and did not, or fired and was useless, that is a defect in the
  rules. It gets a promtool test like any other.

## Locally

The same questions against the Compose stack: `make ps`, `make logs-<service>`, `make alerts`,
Grafana at http://localhost:3000, and `make psql`, `make redis-cli`, `make lag`, `make dlq` for the
stores.
