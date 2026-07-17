# 4. Kafka as the broker, single node, replication factor 1

Date: 2026-07-16
Status: Accepted

## Context

The architecture needs an asynchronous boundary between the API and the worker. The
candidates were RabbitMQ, Redis Streams, a Postgres-backed queue, and Kafka.

This repository argues that production systems can be run well on cheap infrastructure.
Kafka is the least cheap option on the list by a wide margin, so choosing it needs a
reason better than familiarity.

## Decision

Apache Kafka in KRaft mode, one broker, replication factor 1.

## Consequences

### What it costs

Measured footprint against the alternatives considered:

| Option | Resident memory | Components to operate |
| --- | ---: | --- |
| Postgres queue (`SKIP LOCKED`) | 0 (reuses the database) | none |
| Redis Streams | ~50 MB (shared with the cache) | none extra |
| RabbitMQ | ~150 MB | one |
| **Kafka (KRaft, single node)** | **~1.4 GB** | one |

That difference moves the target node up a size. It is the single most expensive decision
in this repository, and `docs/cost-analysis.md` reports it as such rather than burying it.

### Why it is still the right choice here

The comparison that matters is not Kafka against RabbitMQ, it is self-hosted Kafka against
managed Kafka. A managed Kafka service has a three-figure monthly floor before a single
message is produced. Running the same workload on a node costing roughly the price of two
coffees is the argument this repository exists to make, and it is a much stronger argument
with Kafka than it would have been with RabbitMQ.

Kafka also gives the consumer semantics the worker is built to demonstrate — consumer
groups, partition-ordered delivery, replayable offsets, lag as a first-class metric — which
a queue does not.

### Replication factor 1 is not high availability

One broker means the broker is a single point of failure. If it is lost, unpublished events
stay in the outbox and the API keeps serving; published-but-unconsumed events are lost with
the disk. This is stated plainly because a single-node cluster described as "production
Kafka" is misleading.

The design accommodates it rather than pretending otherwise:

- The outbox is the durable record. Postgres, not Kafka, is the system of record for what
  happened.
- The API does not gate readiness on Kafka (see ADR 0007), so a broker outage degrades the
  async path without taking the synchronous one down.
- The projection is reconciled from Postgres on a timer, so a lost event self-corrects
  within one reconciliation interval.

Moving to three brokers is a replication-factor change and a `min.insync.replicas` change,
both already correct in the producer config (`acks=all` with idempotence enabled). Nothing
in the application code has to change.

### Rejected: Redpanda

Redpanda is Kafka-wire-compatible with roughly a third of the footprint and no JVM, which
would have been a materially better fit for the cost thesis. It was rejected because the
purpose of this repository is to demonstrate operating the mainstream tool, and "we ran
something else that speaks the same protocol" is a weaker claim in the context it is
written for. This is a presentation trade-off, not a technical one, and it is recorded here
so it is visible as such.
