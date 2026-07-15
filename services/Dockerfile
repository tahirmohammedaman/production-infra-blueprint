# syntax=docker/dockerfile:1.9

# One Dockerfile, both services.
#
# Build stages use a full JDK; the runtime image gets a purpose-built jlink runtime on a
# distroless base with no shell and no package manager. Bases are pinned by digest so a
# rebuild of an old commit produces the same image, and so a moved tag cannot silently
# change what ships.
#
# The service is selected with --build-arg SERVICE=api|worker. Two near-identical
# Dockerfiles would drift: the day one gets a security fix and the other does not is the
# day the hardening stops being real.
#
# Build context is services/, not services/<name>, because a Gradle multi-project build
# needs the settings file, the version catalog and the shared platform module.
#
#   podman build --build-arg SERVICE=api    --target final .
#   podman build --build-arg SERVICE=worker --target final .
#
# Targets:
#   --target final  (default)  application only
#   --target cds               adds a class-data-sharing archive: ~25% faster startup,
#                              ~88 MB larger. See docs/cost-analysis.md.

ARG JDK_IMAGE=eclipse-temurin:21-jdk-noble@sha256:75ce56643243c3db632be2ef259625fb42ee3be1334389659f7a1a61acb78783
ARG RUNTIME_IMAGE=gcr.io/distroless/java-base-debian12:nonroot@sha256:a9930cad62d02853d7f3dede7281c4b916cbf74493c2d8d38564121aad92bf6c

# --------------------------------------------------------------------------- build
FROM ${JDK_IMAGE} AS build
ARG SERVICE
WORKDIR /workspace

# Dependency resolution is its own layer: it is invalidated by build files only, not by
# source edits, so the common inner-loop rebuild does not re-download the world.
COPY gradle/ gradle/
COPY gradlew settings.gradle.kts build.gradle.kts gradle.properties ./
COPY platform/build.gradle.kts platform/
COPY api/build.gradle.kts api/
COPY worker/build.gradle.kts worker/
RUN --mount=type=cache,target=/root/.gradle,sharing=locked \
    ./gradlew --no-daemon --console=plain :${SERVICE}:dependencies --configuration runtimeClasspath >/dev/null

# platform is copied unconditionally because both services depend on it. Only the target
# service's sources are copied, so a change to the worker does not invalidate the API's
# build cache.
COPY platform/src/ platform/src/
COPY ${SERVICE}/src/ ${SERVICE}/src/

ARG APP_VERSION=0.0.0-dev
ENV APP_VERSION=${APP_VERSION}
RUN --mount=type=cache,target=/root/.gradle,sharing=locked \
    ./gradlew --no-daemon --console=plain :${SERVICE}:bootJar -x test

# Split the fat JAR into an application JAR plus a lib/ directory. Copied as two image
# layers below, a code-only change then ships a ~33 KB layer instead of ~67 MB.
RUN java -Djarmode=tools -jar ${SERVICE}/build/libs/app.jar extract --destination /app

# ------------------------------------------------------------------- CDS training run
# There is no database at build time, so Hikari is told not to fail fast and Hibernate is
# handed the dialect it would otherwise read from a live connection. Without both flags
# the context never reaches refresh and no archive is produced.
FROM build AS cds-archive
WORKDIR /app
RUN java -XX:ArchiveClassesAtExit=/app/app.jsa \
      -Dspring.context.exit=onRefresh \
      -Dspring.profiles.active=prod \
      -Dspring.datasource.hikari.initialization-fail-timeout=-1 \
      -Dspring.jpa.database-platform=org.hibernate.dialect.PostgreSQLDialect \
      -jar /app/app.jar > /dev/null 2>&1

# ------------------------------------------------------------------- jlink runtime
FROM ${JDK_IMAGE} AS runtime-image

# Curated, not generated. `./gradlew printJdepsModules` reports only java.base,java.sql
# for this application, because jdeps cannot see reflective loading, JDBC driver discovery
# or ServiceLoader lookups; a runtime built from that output fails at startup. This list is
# verified by scripts/smoke-test.sh. Treat jdeps output as a review aid when bumping
# dependencies, not as the source of truth.
ARG JAVA_MODULES="java.base,java.compiler,java.desktop,java.instrument,java.logging,java.management,java.naming,java.net.http,java.prefs,java.rmi,java.scripting,java.security.jgss,java.security.sasl,java.sql,java.sql.rowset,java.transaction.xa,java.xml,java.xml.crypto,jdk.crypto.cryptoki,jdk.crypto.ec,jdk.jfr,jdk.management,jdk.unsupported"

# --strip-java-debug-attributes rather than --strip-debug: the latter shells out to
# objcopy, which is not present in this base image and fails the build.
RUN jlink \
      --add-modules "${JAVA_MODULES}" \
      --strip-java-debug-attributes \
      --no-header-files \
      --no-man-pages \
      --compress=zip-6 \
      --output /javaruntime

# -------------------------------------------------------------------- common runtime
FROM ${RUNTIME_IMAGE} AS base

ARG SERVICE
ARG APP_VERSION=0.0.0-dev
ARG VCS_REF=unknown
ARG BUILD_DATE=unknown

LABEL org.opencontainers.image.title="blueprint-${SERVICE}" \
      org.opencontainers.image.description="Blueprint ${SERVICE} service" \
      org.opencontainers.image.version="${APP_VERSION}" \
      org.opencontainers.image.revision="${VCS_REF}" \
      org.opencontainers.image.created="${BUILD_DATE}" \
      org.opencontainers.image.licenses="MIT" \
      org.opencontainers.image.base.name="gcr.io/distroless/java-base-debian12:nonroot"

ENV JAVA_HOME=/opt/java/runtime \
    PATH="/opt/java/runtime/bin:${PATH}" \
    SPRING_PROFILES_ACTIVE=prod \
    APP_VERSION=${APP_VERSION}

COPY --from=runtime-image /javaruntime /opt/java/runtime

WORKDIR /app
# Ordered least- to most-frequently-changed so the expensive layer stays cached.
COPY --from=build /app/lib/ /app/lib/
COPY --from=build /app/app.jar /app/app.jar

# Distroless "nonroot" is uid/gid 65532. Stated explicitly so the Kubernetes manifest's
# runAsUser can assert the same number instead of trusting the base image tag.
USER 65532:65532

# 8080 is the API's public port; the worker never binds it. 9090 is the actuator port on
# both services: probes and Prometheus scrapes only, never routed by the public ingress
# (see the NetworkPolicy in deploy/k8s/base).
EXPOSE 8080 9090

# ------------------------------------------------------------------- opt-in CDS target
FROM base AS cds
COPY --from=cds-archive /app/app.jsa /app/app.jsa
ENTRYPOINT ["java", \
  "-XX:SharedArchiveFile=/app/app.jsa", \
  "-XX:MaxRAMPercentage=70.0", \
  "-XX:InitialRAMPercentage=50.0", \
  "-XX:MaxMetaspaceSize=192m", \
  "-XX:+ExitOnOutOfMemoryError", \
  "-Djava.security.egd=file:/dev/urandom", \
  "-Dfile.encoding=UTF-8", \
  "-jar", "/app/app.jar"]

# ----------------------------------------------------------------- default final image
FROM base AS final
# Heap is sized from the cgroup limit, so the Kubernetes memory limit is the single place
# this is tuned. No -Xmx here on purpose: a hardcoded heap and a container limit drift
# apart and the pod gets OOM-killed by the kernel with no JVM diagnostics.
#
# GC is left to JVM ergonomics rather than pinned: under the small limits this service
# runs with, ergonomics already selects SerialGC, and a hardcoded collector would be the
# wrong one the day the pod is given more headroom.
#
# ExitOnOutOfMemoryError makes the orchestrator responsible for recovery instead of
# leaving a wedged JVM in the load-balancer rotation.
ENTRYPOINT ["java", \
  "-XX:MaxRAMPercentage=70.0", \
  "-XX:InitialRAMPercentage=50.0", \
  "-XX:MaxMetaspaceSize=192m", \
  "-XX:+ExitOnOutOfMemoryError", \
  "-Djava.security.egd=file:/dev/urandom", \
  "-Dfile.encoding=UTF-8", \
  "-jar", "/app/app.jar"]
