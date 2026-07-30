# Runbook: alerts

One section per alert, in the order you are likely to meet them. Every alert's `runbook_url`
annotation links to its section here.

Each section answers four questions: what it means, what users are seeing, where to look first,
and what makes it stop. Commands are given for the cluster; the local stack equivalent is
underneath where it differs.

| Severity | Meaning | Channel |
| --- | --- | --- |
| `page` | User-visible impact now, or a hole in monitoring that would hide it | PagerDuty |
| `ticket` | Something is wrong that nobody outside will notice yet | Slack, `#blueprint-alerts` |

Before anything else, open **Blueprint / Overview** in Grafana. Objectives, gateway, API,
worker and logs are laid out in the order a request meets them, and restarts are marked on
every graph.

---

## ErrorBudgetBurnFast

**Page.** An objective is failing fast enough to spend its whole 30-day budget within days: 2%
of it in the last hour (14.4x), or 5% in the last six (6x). The `slo` label says which one.

**Users are seeing** failed requests (`api-availability`), slow ones (`api-latency`), or an
inventory summary that lags writes by more than ten seconds (`worker-freshness`).

**Look first:** **Blueprint / SLOs**, objective set to the alert's `slo`. Then, by objective:

`api-availability` — Overview → *Requests by status*. The status code says where to go:

| Code | Who answered | Usually |
| --- | --- | --- |
| 503 | the gateway: no ready replica | readiness failing because Postgres or Redis is down, or every pod restarting |
| 502, 504 | the gateway: a replica died or hung mid-request | OOM kills, a rollout without drain, a stuck thread pool |
| 500 | the API | a bug. *Server errors by route* says which endpoint; the logs panel has the trace |

```bash
kubectl -n blueprint get pods -o wide
kubectl -n blueprint describe pod -l app.kubernetes.io/name=api | grep -A3 'Last State'
kubectl -n flux-system get kustomizations        # did a deploy just land?
```

If it started with a deploy — a restart marker on the graphs, a fresh `chore(deploy)` commit
from image automation — do not debug it live: **roll back** ([rollback.md](rollback.md)),
then investigate.

`api-latency` — Overview → *Database connection pool* and *p95 latency by route*, then
**Blueprint / Runtime** → GC time. Pool `pending` above zero means the database is the
bottleneck: see [DatabasePoolExhausted](#databasepoolexhausted). A cache outage also shows up
here, because summary reads fall back to an aggregate query: see
[CacheUnavailable](#cacheunavailable).

`worker-freshness` — **Blueprint / Async pipeline**. Outbox pending climbing means the relay is
behind ([OutboxRelayStalled](#outboxrelaystalled)); lag climbing means the worker is
([ConsumerLagHigh](#consumerlaghigh)); listener failures climbing means events are being
retried with backoff, which blocks their partition for up to a minute each.

**It stops** within minutes of the cause being fixed: the short window of each pair clears
long before the long one does. The budget spent stays spent; check what is left on the SLO
dashboard, and the error budget policy in [docs/slo.md](../slo.md) for what it changes.

## ErrorBudgetBurnSlow

**Ticket.** The same objectives, burning at 3x over a day or 1x over three days: nothing is
on fire, but the budget will be gone before the 30-day window ends.

**Look first:** the same triage as [ErrorBudgetBurnFast](#errorbudgetburnfast), without the
urgency. Slow burns are usually one endpoint that got slower, or a small, steady error rate
that nobody noticed because each hour looks fine. *Server errors by route* and *p95 latency by
route* over seven days usually show it.

**It stops** when the burn rate drops below the threshold for the short window. If the budget
is already low, the error budget policy applies whether or not the alert is still firing.

## GatewayDown

**Page.** Prometheus has not scraped the gateway for two minutes. Either Traefik is down —
every request is failing — or it is up and unscrapeable, and every burn-rate alert is blind,
because the SLOs are measured at the gateway.

**Look first:**

```bash
kubectl -n traefik get pods
kubectl -n traefik logs deploy/traefik --tail=100
flux get helmreleases -A                        # a failed upgrade rolls back on its own; check it did
kubectl get nodes                               # one node: if it is gone, so is everything
curl -sI https://blueprint.tahir.dev/api/v1/items
```

Locally: `make ps` and `make logs-gateway`.

**It stops** when Traefik is scraped again. If the HelmRelease is failing, `flux suspend
helmrelease traefik -n traefik`, fix the values in git, then resume — never edit the
Deployment by hand, Flux will revert it.

## WorkerDown

**Page.** The only consumer of `item-events` has not been scraped for five minutes.

**Users are seeing** correct responses to every write, and an inventory summary that stopped
changing when the worker did. Nothing is lost: events wait in Kafka at the committed offset and
are applied when a worker returns. Consumer lag is not reported while nothing is consuming,
which is why this alert exists instead of relying on lag.

**Look first:**

```bash
kubectl -n blueprint get pods -l app.kubernetes.io/name=worker
kubectl -n blueprint describe pod -l app.kubernetes.io/name=worker   # OOMKilled? Pending?
kubectl -n blueprint logs deploy/worker --previous --tail=100
```

The worker's readiness includes its consumers and Redis. If Redis is down, see
[CacheUnavailable](#cacheunavailable); if Kafka is down, [OutboxRelayStalled](#outboxrelaystalled)
will be firing too and is the one to fix.

**It stops** when a worker is scraped again. It then works through the backlog; expect
`worker-freshness` to burn while it catches up, and lag to fall on **Async pipeline**.

## OutboxRelayStalled

**Page.** Events have been waiting in the outbox for fifteen minutes and nothing has been
published: the relay cannot reach Kafka.

**Users are seeing** every request succeed — writes commit to Postgres regardless, which is the
point of the outbox (ADR 0007) — and an inventory summary that stopped reflecting them.

**Look first:**

```bash
kubectl -n blueprint get pod kafka-0
kubectl -n blueprint exec kafka-0 -- /opt/kafka/bin/kafka-broker-api-versions.sh \
  --bootstrap-server localhost:9092 >/dev/null && echo broker answering
kubectl -n blueprint logs deploy/api --tail=200 | grep 'outbox publish'
```

Locally: `make topics`, and `make logs-api`. The relay logs `outbox publish deferred, broker
unreachable` with the producer's reason on every poll while this lasts.

A broker out of disk stops accepting writes long before it stops answering health checks:
`kubectl -n blueprint exec kafka-0 -- df -h /var/lib/kafka/data`.

**Do not** restart the API to "unstick" it. The events are rows in Postgres, not in the
process; a restart changes nothing except adding a cold start.

**It stops** when the broker answers. An outage costs events no attempts, however long it lasts,
so every waiting event is published on the next poll — up to 100 per second per replica. Expect
`worker-freshness` to burn while the backlog drains.

## TargetDown

**Ticket.** Prometheus has failed every scrape of one target for five minutes. For a single
replica of a multi-replica service that is lost capacity, not an outage; the SLO pages if it
becomes one.

**Look first:** the target's last error, on Prometheus' *Targets* page (locally
http://localhost:9095/targets). Two causes cover almost every case:

- **Connection refused / timeout** — the process is down. `kubectl describe` the pod.
- **`sample limit exceeded`** — the target now exports more series than its `sample_limit`,
  and the whole scrape is rejected. Someone added a label. Find it:

  ```promql
  topk(10, count by (__name__) ({job="api"}))
  ```

  Fix the label, not the limit. The limit is the series budget (see
  `observability/prometheus/prometheus.yml`); raising it is a decision about cost, made in a
  commit, not a way to make an alert go away.

## OutboxEventsStuck

**Ticket.** Events the broker refused, repeatedly, until they ran out of attempts (ten). They
will not be published without someone deciding they should be. An unreachable broker does not
cause this — only a broker that answered and said no.

**Look first:** why it refused them. The reason is on the row:

```bash
kubectl -n blueprint exec -it postgres-0 -- psql -U blueprint -d blueprint -c "
  SELECT id, event_type, attempts, last_error, created_at
  FROM outbox_events
  WHERE published_at IS NULL AND attempts >= 10
  ORDER BY created_at;"
```

Locally: `make psql`. Typical reasons: the record exceeds the broker's size limit, the topic
was deleted or recreated with a different configuration, or an authorisation change.

**It stops** once the cause is fixed and the events are re-driven. Resetting the attempts makes
them claimable again; redelivery is safe because the worker is idempotent on event id:

```sql
UPDATE outbox_events SET attempts = 0, last_error = NULL
WHERE published_at IS NULL AND attempts >= 10;
```

In the meantime the read model's totals stay correct: the reconciler does not wait for stuck
events.

## ConsumerLagHigh

**Ticket.** The worker is alive and consuming, but more than 1,000 records behind for ten
minutes.

**Look first:** **Async pipeline** → *Lag by partition* and *Listener failures by exception*.

- **One partition climbing** — one hot key, or one record being retried. Each retry backs off
  up to ten seconds and the whole sequence can take a minute, during which that partition does
  not move.
- **Every partition climbing** — the worker is slower than the write rate. Check its CPU and GC
  on **Runtime**.

```bash
kubectl -n blueprint exec kafka-0 -- /opt/kafka/bin/kafka-consumer-groups.sh \
  --bootstrap-server localhost:9092 --describe --group blueprint-worker
```

Locally: `make lag`.

Scaling the worker out does not help past three consumers: the topic has three partitions and
the worker already runs three consumer threads. More throughput means more partitions, which
is a topic change with an ordering consequence — plan it, do not do it mid-incident.

## DeadLetterTopicReceiving

**Ticket.** Records exhausted their retries or could not be parsed, and were routed to
`item-events.DLT`. Each one is an update the read model never applied; the reconciler repairs
the totals, but the event itself is gone from the normal path.

**Look first:** what is in the topic. The failure reason is in the record's headers:

```bash
kubectl -n blueprint exec kafka-0 -- /opt/kafka/bin/kafka-console-consumer.sh \
  --bootstrap-server localhost:9092 --topic item-events.DLT --from-beginning \
  --timeout-ms 5000 --property print.headers=true --property print.key=true
```

Locally: `make dlq`. The worker also logs `event exhausted retries, routing to dead-letter
topic` with the partition, offset and exception.

**It stops** when records stop arriving. For the ones already there: fix the cause, then
re-publish them to `item-events` with their original key. The worker's idempotency guard makes
a replay of an event that was in fact applied a no-op.

## ProjectionDriftCorrected

**Ticket.** The reconciler found the inventory projection disagreeing with Postgres and rewrote
it. Users now see correct totals; the question is why they were wrong.

**Look first:** whether Redis restarted. Redis holds the projection without persistence, by
design, so a restart empties it and the next reconciliation rebuilds it — that is this alert
reporting the reconciler doing its job. **Runtime** marks restarts; so does:

```bash
kubectl -n blueprint get pod redis-0 -o jsonpath='{.status.containerStatuses[0].restartCount}'
```

If Redis did not restart, an event was lost or applied twice. The log line has the size of the
correction — `projection drift corrected distinct=a->b quantity=c->d` — and Loki has it:

```logql
{service_name="api"} |= "projection drift corrected"
```

Then check [DeadLetterTopicReceiving](#deadlettertopicreceiving) and
[OutboxEventsStuck](#outboxeventsstuck) for the same window; those are the two known ways an
event misses the projection.

The reconciler never corrects while events are in flight — it waits until nothing has been
published for 30 seconds — so a correction is not a race with normal traffic.

## ReconciliationStarved

**Ticket.** For two hours, every reconciliation run on one instance found events in flight and
stood down. The reconciler waits until nothing has been published for 30 seconds, so that it
never counts an event twice; writes arriving more often than that, without a pause, starve it.

**Users are seeing** nothing, unless the projection has also drifted — in which case the
inventory summary stays wrong until a run gets through.

**Look first:** whether the write rate really is continuous (**Async pipeline** → *Relay
throughput*), or whether events are being published in a loop that should not exist — a stuck
retry republishing the same event, a client hammering the API.

**It stops** at the first lull, when a run proceeds. If the traffic is genuinely continuous,
reconcile deliberately at a quiet moment by lowering `blueprint.reconcile.quiet-period` for one
deploy; a correction then may overcount events in flight by exactly those events, and the next
run repairs that.

## DatabasePoolExhausted

**Ticket.** Requests have been waiting for a pooled database connection for five minutes.
Latency climbs before errors do; if it climbs far enough, the latency SLO pages.

**Look first:** what is holding the connections.

```bash
kubectl -n blueprint exec -it postgres-0 -- psql -U blueprint -d blueprint -c "
  SELECT pid, now() - xact_start AS age, state, left(query, 80) AS query
  FROM pg_stat_activity
  WHERE datname = 'blueprint' AND state <> 'idle'
  ORDER BY age DESC NULLS LAST;"
```

Slow statements are logged by Postgres itself (`log_min_duration_statement=250ms`):

```logql
{service_name="postgres"} |= "duration:"
```

Long transactions are almost always the cause, not too small a pool. The pool is sized against
Postgres' `max_connections` shared by every replica; raising it without raising that moves the
queue from the application into the database.

## CacheUnavailable

**Ticket.** The API cannot reach Redis and has been serving summary reads from Postgres for ten
minutes. Responses are correct; the database is doing work the cache normally absorbs, and the
worker cannot update the projection either, so [WorkerDown](#workerdown) may follow.

**Look first:**

```bash
kubectl -n blueprint get pod redis-0
kubectl -n blueprint exec redis-0 -- redis-cli INFO memory | grep -E 'used_memory_human|maxmemory_human'
```

Locally: `make redis-cli`, then `PING` and `INFO memory`.

Redis runs with `noeviction`: when it is full it refuses writes rather than silently dropping
the projection. Errors mentioning `OOM command not allowed` mean that — the limit is the thing
to revisit, and `maxmemory` has to stay under the container limit.

## JvmHeapPressure

**Ticket.** One instance has used more than 90% of its heap for fifteen minutes: sustained, not
the peak before a collection.

**Look first:** **Runtime** → *Heap in use against the maximum* over a day.

- A floor that rises after every collection is a leak. Restarting buys time; the fix is in the
  code.
- A sawtooth that simply runs high is an instance sized too small for its load.

The heap is derived from the container limit (`MaxRAMPercentage=70`), and the limit cites the
RSS it was sized from in `docs/cost-analysis.md`. Change it in the manifest, with a new
measurement, in a commit.

## CertificateExpiringSoon

**Ticket.** A certificate expires in under 14 days. cert-manager renews at 30 days, so this one
has already failed to renew at least once.

**Look first:**

```bash
kubectl get certificates -A
kubectl -n blueprint describe certificate
kubectl get challenges -A
kubectl -n cert-manager logs deploy/cert-manager --tail=100
```

The issuer uses HTTP-01, which needs port 80 reachable from the internet and DNS pointing at
the node. A firewall change (`infra/terraform/modules/firewall`) or a DNS change is the usual
cause.

## Watchdog

**Always firing, by design.** Alertmanager forwards it to an external dead man's switch every
minute. Nobody acts on this alert. You act when the switch says it has **stopped** hearing it,
which means the alerting path itself is broken:

```bash
kubectl -n monitoring get pods
kubectl -n monitoring get secret alertmanager-receivers -o jsonpath='{.data}' | jq 'keys'
```

Prometheus down, Alertmanager down, or the `deadmans-switch-url` in that Secret wrong — in that
order of likelihood.

## LogPipelineDropping

**Ticket.** Alloy is discarding log lines Loki refused.

**Look first:** why Loki refused them.

```bash
kubectl -n monitoring logs ds/alloy --tail=100 | grep -i 'error'
```

Locally: `make logs-alloy`. The common case is HTTP 400 `entry has too many labels` — someone
added a stream label and crossed the label budget (`max_label_names_per_series` in
`observability/loki/loki.yml`). High-cardinality fields belong in structured metadata, as
`trace_id` and `request_id` are in `observability/alloy/pipeline.alloy`. HTTP 429 means the
ingestion rate limit; look for a service logging in a loop before raising it.

## AlertmanagerNotificationsFailing

**Ticket.** Alerts are firing and Alertmanager cannot deliver them to at least one integration.

**Look first:** which integration is failing:

```promql
sum by (integration) (rate(alertmanager_notifications_failed_total[5m]))
```

Then the receiver's credential, which is a file projected from the `alertmanager-receivers`
Secret: `slack-webhook-url`, `pagerduty-routing-key`, `deadmans-switch-url`. A rotated webhook
that was not updated in the Secret is the usual cause. Rotate it with `sops` like any other
secret; Flux applies it.

## PrometheusRuleFailures

**Ticket.** A rule group is failing to evaluate. Every alert in it is silently disabled until
this is fixed, which is why this is worth a ticket even though nothing else is wrong.

**Look first:** the rule's last error on Prometheus' *Rules* page. The usual cause is a
many-to-many match after a label was added to a metric on one side of a binary operator.

Reproduce it as a test in `observability/prometheus/tests/`, fix the rule, and run
`make obs-validate`. A rule that failed in production and has no test will fail again.
