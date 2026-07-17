# 6. Redis holds a read model, and the two services declare its shape separately

Date: 2026-07-16
Status: Accepted

## Context

The worker maintains an inventory summary that the API serves. That value crosses a process
boundary between two independently deployed services, which makes its shape a contract.

## Decision

Redis stores it as JSON under a single typed serialiser. The record is declared twice, once
in each service, rather than shared through the `platform` module.

## Consequences

### JSON, not JDK serialisation

JDK serialisation would couple the two services to identical class bytecode: a worker deploy
would start throwing `InvalidClassException` inside the API for a change that compiled
cleanly. JSON also means the value can be read with `redis-cli GET` during an incident,
which matters more than it sounds like it does at three in the morning.

### A serialiser bound to one concrete type, not polymorphic typing

Jackson's default typing embeds a class name in the stored document and instantiates it on
read. Over a cache that more than one service can write, that is a deserialisation gadget:
whatever can write to Redis chooses what class the API constructs. Binding the serialiser to
a single concrete type removes the capability entirely, and costs one extra bean the day a
second cached type appears.

### Duplicated, not shared

Putting the record in `platform` would make it trivial to change both sides in one commit
and deploy them separately — shipping a worker that writes a shape the running API cannot
read. Two declarations force the compatibility question to be asked out loud. Adding a field
is safe; removing or renaming one is an API change and needs the same two-step treatment as
a schema migration.

This is the same reasoning that keeps the worker out of the API's database. Shared code
across a deployment boundary is shared coupling, and a shared library is the most
comfortable way to build a distributed monolith.

### Staleness is accepted, drift is not

The projection is eventually consistent by construction, and the endpoint documents that.
Eventual consistency is fine; unbounded drift is not. An incremental projection built from
deltas never self-corrects — a lost event or an out-of-window redelivery leaves the counters
wrong forever, and nothing in the event path will notice.

The API therefore reconciles from Postgres on a timer and exports a counter for how often it
found a disagreement. A reconciler that silently fixes things hides a bug in the event path;
one that reports what it had to fix turns that bug into an alert.
