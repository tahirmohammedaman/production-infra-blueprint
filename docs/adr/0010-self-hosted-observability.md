# 10. Observability is self-hosted, budgeted, and measured at the gateway

Date: 2026-07-29, recorded 2026-08-08
Status: Accepted

## Context

A system that cannot explain itself cannot be operated, so metrics, logs, traces, alerting and
dashboards were never optional. The question was where they run and what they cost.

A hosted vendor is the fastest start. It is also the line in a small system's bill that grows
fastest without anyone deciding it should: it is priced per host, per million spans and per
gigabyte of logs, and every new label, log line and service moves it. At Datadog's list prices a
single host with infrastructure monitoring and APM is $46 a month before a byte of logs, and the
node it would be monitoring costs about €11.

## Decision

Prometheus and Alertmanager for metrics and alerting, Loki with Grafana Alloy for logs, Tempo for
traces, and Grafana over all three, provisioned as code. One set of configuration files serves
the Compose stack and the cluster.

Three rules come with it:

- **Objectives, not thresholds, page people.** Availability and latency objectives with
  multi-window burn-rate alerts; everything else that pages covers a hole the objectives cannot
  see. Every alert has a unit test and a runbook section.
- **The API is measured at the gateway.** The application cannot count the 503 the gateway
  returns when no replica is ready; an SLI computed inside it reports 100% through a total outage.
- **Cardinality has a budget, enforced in configuration.** A sample limit per scrape target, four
  labels per log stream, histogram buckets only at the objective thresholds, and scheduled-task
  spans dropped before export. Exceeding a budget fails loudly — a rejected scrape, a rejected
  push — rather than growing a bill or a disk.

## Consequences

**It costs a measured 590 MB of memory** on a node that was already paid for, plus two small
volumes and object storage for thirty days of log chunks (`docs/cost-analysis.md`). The monitoring
stack's own series outnumber the application's at this scale; that is the honest shape of a small
system, and the budgets are what stop it from being true at every scale.

**We operate it.** Upgrades, retention, and noticing when the monitoring itself is broken. The
last is handled by a Watchdog alert that is always firing and is forwarded to an external dead
man's switch: when the node dies, so does Prometheus, and only something outside the node can
say so.

**Retention is a decision, in two places that must agree.** Thirty days in Prometheus, because
the error budget is computed over thirty days; thirty days in Loki and in the bucket's lifecycle
rule, because a bucket that keeps what Loki has forgotten bills for data nothing can read.

**It found real defects.** An alert on a documented metric name that Micrometer publishes under a
different one, a histogram that published 600 series for eight routes, a reconciler that double
counted events in flight, and an outbox relay that stranded events after a two-minute broker
outage — the last two only visible because the dashboards and alerts disagreed with what the code
was assumed to do.

**When to revisit:** when more than one person is on call and the time spent operating the stack
costs more than a vendor would, or when the system spans enough nodes that a hosted backend's
durability is worth paying for. Grafana Cloud would take the same Alloy pipeline and dashboards
with a change of endpoint.
