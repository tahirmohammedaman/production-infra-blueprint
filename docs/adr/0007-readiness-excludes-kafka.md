# 7. Postgres and Redis gate API readiness; Kafka does not

Date: 2026-07-16
Status: Accepted

## Context

The API depends on three external systems. Readiness controls whether Kubernetes and the
gateway send it traffic, so the question is which of those dependencies should be able to
remove a healthy process from rotation.

## Decision

The readiness group includes Postgres and Redis. It deliberately excludes Kafka.

## Consequences

### Why Kafka is excluded

Writes do not touch Kafka. They commit to Postgres together with an outbox row, and a
separate relay publishes afterwards. A broker outage therefore degrades the asynchronous
path — the projection goes stale, the outbox backlog grows — while every synchronous request
continues to succeed.

Gating readiness on Kafka would convert that partial degradation into a total outage:
healthy replicas serving correct responses would be pulled from the load balancer because a
system they do not need in the request path is down. The outbox exists precisely so this
failure is survivable, and gating on Kafka would throw that away.

The backlog is not ignored — it is alerted on. `blueprint_outbox_pending_events` and
`blueprint_outbox_stuck_events` are the signals, and paging when the relay has stopped
publishing (`OutboxRelayStalled`) is the correct response to a broker outage. Removing the
service from rotation is not.

### Why Redis is included

Redis is a cache, and a cache being down is normally not a readiness concern. It is included
here because of what happens at startup rather than mid-flight: a replica that starts with
Redis unreachable serves every read from Postgres, silently multiplying database load at
exactly the moment the system is already unhealthy. The HTTP dashboards look fine while the
database saturates.

Mid-flight failures behave differently and deliberately so: `InventorySummaryService` catches
the connection failure and falls back to the database, so an established replica degrades
rather than flaps. Readiness gates admission; it does not police steady state.

### Liveness stays independent of everything

No dependency appears in the liveness group. Restarting an application because its database
is down adds a cold start to an incident and fixes nothing. Liveness answers "is this
process wedged", readiness answers "should it receive traffic", and conflating them turns a
dependency outage into a crashloop.

There is a test asserting each of these properties, because a readiness group is one line of
YAML away from being wrong and nothing else would catch it.
