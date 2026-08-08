# 2. Java 21 and Spring Boot, with the footprint measured and paid down in public

Date: 2026-07-04, recorded 2026-08-08
Status: Accepted

## Context

The services are small: a REST API over one table, and a Kafka consumer maintaining a read model.
Any mainstream language would do. The choice is about what the repository needs to demonstrate
and what it can afford to run on one 8 GB node.

Go would produce 20 MB images that start in milliseconds and idle in tens of megabytes. Java is
the heavier option on every one of those axes, and it is also what most of the systems this
repository is meant to be read against are written in: Spring's transaction management, its
Kafka integration and its actuator are the idioms a reviewer will recognise.

## Decision

Java 21 on Spring Boot, Spring MVC on virtual threads, as a Gradle multi-project with a shared
`platform` module for observability, error handling and event contracts.

Because Java is the heavy option, its footprint is treated as a first-class deliverable:
measured at every stage, reduced where the reduction pays, and published in
`docs/cost-analysis.md` with the method used. Kubernetes requests and limits cite the
measurement they came from.

## Consequences

**Measured, not assumed.** Image size went from 523 MB (a fat JAR on a JDK base) to 170 MB with a
layered JAR and a `jlink` runtime on distroless — and 200 MB once the Redis, Kafka and JPA
dependencies arrived with the multi-service split. A code-only deploy ships a 33 KB layer. The
API runs at 433 MB resident under a 640 MiB limit; the worker at 311 MB under 448 MiB. A Go
service would be a fraction of each, and the node is sized with that difference in it.

**Startup is seconds, and the CDS trade-off is written down.** The context refreshes in 4.9 s,
3.7 s with a class-data-sharing archive that adds 88 MB to the image. CDS is a separate build
target rather than the default, because for pods that live for days a second per deploy does not
pay for the extra image. The zero-downtime drill found the cost that does matter: new pods warming
up during a rollout take CPU from the pods still serving.

**Virtual threads make the connection pool the concurrency limit.** A blocked JDBC call no longer
holds a platform thread, so the pool size, not a thread count, bounds concurrent database work,
and memory is sized from heap rather than from thread stacks. Pool exhaustion is therefore the
overload signal, and it is answered with 503 and Retry-After rather than a 500.

**The JVM is tuned from the container.** Heap is `MaxRAMPercentage` of the cgroup limit, never an
`-Xmx`, so the limit is the one place memory is set. There are no CPU limits, because CFS
throttling a JVM during start-up and garbage collection produces latency spikes that look like
application bugs.

**Not done:** a GraalVM native image. It would bring the footprint close to Go's at the cost of
reflection configuration, slower builds and a second way of running every test. The measurements
above did not make the case for it; if the node ever has to shrink, they will be the first place to
look.
