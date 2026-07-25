# Kubernetes manifests

Kustomize, not Helm. There is no templating language between a reviewer and the YAML that
gets applied — `kustomize build overlays/prod` prints exactly what the cluster will see.

```
base/                     every workload, sized from measured RSS
  namespace/              its own directory so other kustomizations can reference it
  migration/              the Flyway Job, applied ahead of the rollout
  api/ worker/            the two services
  postgres/ redis/ kafka/ the three backing stores
  networkpolicy.yaml      default-deny plus an explicit allowlist, ingress *and* egress
config/prod/              Namespace and the SOPS-encrypted Secrets
migrations/prod/          just the Job, so Flux can block on it
overlays/prod/            production: real hostname, real image names, image-policy setters
overlays/dev/             a second namespace on the same cluster, one replica, no HPA
infrastructure/
  controllers/            Traefik and cert-manager as HelmReleases
  configs/                the ACME ClusterIssuers, which need cert-manager's CRDs first
```

## Why four kustomizations for production rather than one

Because they are applied by four Flux Kustomizations that depend on each other, and two Flux
Kustomizations cannot own the same object — they fight over it. The split is what makes the
ordering expressible:

```
infrastructure-controllers → infrastructure-configs → apps-config → apps-migrations → apps
```

`apps-config` holds the Namespace and the Secrets, separately from the application, so that
a failed deploy cannot prune the database credentials out from under the database.
`apps-migrations` holds only the Job, so `wait: true` can block the rollout until the schema
change has actually finished. That is the entire mechanism behind a safe expand/contract
deploy — without it, Flux applies the Job and the Deployments together and the new pods race
the migration.

## Things in here that are load-bearing

- **`maxUnavailable: 0` with `maxSurge: 1`.** Readiness gating alone does not give zero
  downtime; this is what stops the rollout from reducing capacity even briefly.
- **`terminationGracePeriodSeconds: 40`** against the application's 25-second drain window.
  Set it shorter and a graceful shutdown becomes a SIGKILL mid-request.
- **The worker mounts no secret at all.** It never opens a database connection, so it has no
  reason to be able to read the password. The NetworkPolicy says the same thing at the
  network layer.
- **Kafka's NetworkPolicy allows Kafka to reach itself.** KRaft's controller quorum is a
  connection from the broker to its own controller listener; default-deny blocks it, the
  broker starts, reports healthy for about a minute, and never elects a leader.
- **Postgres runs as uid 999 from the start**, not by dropping privileges itself. The
  `restricted` Pod Security Standard forbids starting as root — the same constraint that
  made `cap_drop: ALL` crashloop this image in the Compose stack.
- **No CPU limits.** CFS throttling on a JVM during startup and GC produces latency spikes
  that look exactly like application bugs. Requests protect the node; this node has one
  tenant.
- **The HPA scales on CPU, never memory.** A JVM's RSS rises to fill its heap and does not
  come back down, so a memory-based HPA scales up once and never scales down again.

## Local checks

```bash
make k8s-build     # render every kustomization
make k8s-validate  # render, then check against the real Kubernetes schemas
```

Both run in CI on any change under `deploy/k8s/` or `clusters/`.
