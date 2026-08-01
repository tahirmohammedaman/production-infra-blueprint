# Kubernetes manifests

Kustomize, not Helm. There is no templating language between a reviewer and the YAML that
gets applied — `kustomize build overlays/prod` prints exactly what the cluster will see.

```
base/                     every workload, sized from measured RSS
  namespace/              its own directory so other kustomizations can reference it
  migration/              the Flyway Job, applied ahead of the rollout
  api/ worker/            the two services
  postgres/ redis/ kafka/ the three backing stores
  network/                default-deny plus an explicit allowlist, ingress *and* egress
config/prod/              Namespace, the SOPS-encrypted Secrets, the NetworkPolicies
data/prod/                the three stores, so they are healthy before the migration runs
migrations/prod/          just the Job, so Flux can block on it
overlays/prod/            production: real hostname, real image names, image-policy setters
overlays/dev/             a second namespace on the same cluster, one replica, no HPA
infrastructure/
  controllers/            Traefik and cert-manager as HelmReleases
  configs/                the ACME ClusterIssuers, which need cert-manager's CRDs first
  observability/          Prometheus, Alertmanager, Loki, Tempo, Alloy and Grafana as
                          HelmReleases, configured from ../../../observability
```

## Why five kustomizations for production rather than one

Because they are applied by Flux Kustomizations that depend on each other, and two Flux
Kustomizations cannot own the same object — they fight over it. The split is what makes the
ordering expressible:

```
infrastructure-controllers → infrastructure-configs → apps-config → apps-data → apps-migrations → apps
```

`apps-config` holds the Namespace, the Secrets and the NetworkPolicies, separately from the
application, so that a failed deploy cannot prune the database credentials out from under the
database, and so that no pod ever runs before the policy that restricts it exists.
`apps-data` holds Postgres, Redis and Kafka, and is the one stage that does not prune.

The stores used to be part of `apps`. That worked on a cluster where Postgres already existed
and deadlocked on a fresh one: the migration Job waited for a database that only the stage
after it would create. It was found by bringing these manifests up in a real cluster for the
first time, along with the other things schema validation could not see — see
[Found by running it](#found-by-running-it).
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

## Monitoring

The monitoring stack is its own Flux Kustomization, `observability`, and nothing depends on
it. An application deploy never waits for Grafana to become healthy, and a broken monitoring
upgrade can never block a fix from reaching production.

Its HelmReleases install the software and nothing else. What the software runs — scrape
configuration, alert rules, routing, dashboards, the log pipeline — is the same set of files
the Compose stack mounts, turned into ConfigMaps by `observability/kustomization.yaml`. A rule
changed in git reaches the running Prometheus through Flux and the chart's config reloader,
without a restart. See `observability/README.md`.

Every chart pins the same application version the Compose stack runs, so what is tested on a
laptop is what runs here.

Grafana has no ingress. Reach it with the cluster credentials you already have:

```bash
kubectl -n monitoring port-forward svc/grafana 3000:80
```

## Secrets created by hand

Everything this repository can generate is committed, SOPS-encrypted. Four Secrets hold
credentials issued by someone else, so they are created once per cluster, from files rather
than `--from-literal` so the values never land in shell history:

```bash
# The age key Flux decrypts every *.enc.yaml with. Kept in a password manager, never in git.
kubectl -n flux-system create secret generic sops-age \
  --from-file=age.agekey=./age.key

# Lets image automation list tags on GHCR: a token with read:packages only.
kubectl -n flux-system create secret docker-registry ghcr-credentials \
  --docker-server=ghcr.io --docker-username=<github-user> --docker-password="$(cat ./ghcr-token)"

# Alertmanager's receivers, read through *_file settings in observability/alertmanager.
kubectl -n monitoring create secret generic alertmanager-receivers \
  --from-file=slack-webhook-url=./slack-webhook-url \
  --from-file=pagerduty-routing-key=./pagerduty-routing-key \
  --from-file=deadmans-switch-url=./deadmans-switch-url

# Object storage for Loki's chunks, in AWS shared-credentials format:
#   [default]
#   aws_access_key_id = ...
#   aws_secret_access_key = ...
kubectl -n monitoring create secret generic loki-object-storage \
  --from-file=credentials=./loki-credentials
```

Alertmanager and Loki do not start until theirs exist. That is deliberate: an Alertmanager that
cannot notify anyone should be visibly broken, not quietly running.

## Found by running it

Every manifest here rendered, passed kubeconform against the real 1.33 schemas, and passed
checkov and trivy — and the first time they ran in a cluster, nothing came up. None of these
is visible to a schema; all of them are visible to a pod.

| What broke | Why validation could not see it | Fix |
| --- | --- | --- |
| A fresh cluster deadlocked: the migration Job waited for a Postgres created only by the stage after it | Ordering between Flux Kustomizations is not in any one manifest | `apps-data` stage before migrations |
| The migration and topics Jobs timed out connecting | Default-deny blocked their egress; only the long-running pods had rules | egress policies for both Jobs, and policies applied with the namespace |
| Kafka shut down in a loop in the dev namespace | Its DNS names hard-coded `blueprint`; valid YAML, wrong everywhere else | namespace-independent short names |
| The worker crashed at startup whenever the broker was not yet ready | A headless Service has no DNS record until its pod is Ready | ClusterIP for bootstrap, headless only for identity |
| Kafka was restarted four times during a load test while it was only slow | Its liveness probe started a JVM per check and timed out on a busy node | liveness is a TCP check; readiness keeps the real one |

## Local checks

```bash
make k8s-build     # render every kustomization
make k8s-validate  # render, then check against the real Kubernetes schemas
```

Both run in CI on any change under `deploy/k8s/` or `clusters/`.
