# 5. Transactional outbox with a polling relay, not change data capture

Date: 2026-07-16
Status: Accepted

## Context

The API must persist an item and publish an event describing it. Doing both directly is a
dual write: there is no transaction spanning Postgres and Kafka, so any failure between
them leaves the two permanently inconsistent — an item nobody was told about, or an event
describing a row that was rolled back.

The transactional outbox removes the dual write by making the event part of the same
database transaction as the entity. What remains is the question of how rows get from the
table to the broker.

## Decision

A polling relay inside the API service, claiming rows with `FOR UPDATE SKIP LOCKED`.

## Consequences

`SKIP LOCKED` is what makes the relay safe to run in every replica: each instance claims
rows nobody else holds instead of blocking behind them. Without it, replicas serialise and
the relay gets slower as the service scales out — the opposite of the intent.

Publishing is synchronous per record and the row is marked published only after the broker
acknowledges. Fire-and-forget would mark rows published that the broker never accepted,
converting at-least-once into at-most-once silently.

The consequence accepted here is at-least-once delivery: a relay that dies between the
broker acknowledgement and the database commit republishes on the next poll. This is why
every event carries an idempotency key and the worker deduplicates on it. Trading
exactly-once for at-least-once plus a genuinely idempotent consumer is the deliberate
choice; exactly-once across two systems is not available at this cost.

### Rejected: Debezium change data capture

Debezium reads the Postgres write-ahead log and would give lower latency with no polling
load — technically the better mechanism. It was rejected on footprint: it requires a Kafka
Connect cluster and a replication slot to operate and monitor, roughly a gigabyte of
additional memory, to serve a service publishing a handful of events per second.

An unmonitored replication slot is also an outage waiting to happen — it retains WAL
segments until consumed, and a stalled connector fills the database's disk. That is a real
operational burden to take on for latency nobody has asked for.

The polling interval is one second. If that ever became the bottleneck, CDC is the next
step, and the outbox table is the same either way — Debezium would read the same rows this
relay polls, so the migration does not touch application code.
