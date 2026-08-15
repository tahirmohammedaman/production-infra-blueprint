// Defects found by running the system, grouped by where they lived. Every one was invisible to
// the linters and validators that had already passed; each is fixed, and most have a test, a
// rule or a drill that would catch it again. Backticks become code in the page.

export interface Finding {
  title: string;
  what: string;
  found: string;
  fix: string;
  /** [label, site route or repository URL] */
  refs?: [string, string][];
}

export interface FindingGroup {
  id: string;
  findings: Finding[];
}

export const findingGroups: FindingGroup[] = [
  {
    id: 'async',
    findings: [
      {
        title: 'A two-minute broker outage stranded every event written during it',
        what: 'The relay counted every failed poll against the event. A broker away for about two minutes used up the attempts of every event written in that time — contradicting the central claim of ADR 0007 — and one poll could hold row locks for around thirteen minutes.',
        found: 'Stopping Kafka under load, and watching the async dashboard disagree with the design.',
        fix: 'An unreachable broker costs events nothing and ends the batch; only a broker that answers and refuses counts. A 150-second outage: 15 of 15 writes succeeded, no attempts spent, longest transaction 10 s, backlog drained 12 s after the broker returned.',
        refs: [['ADR 0007', '/decisions/0007-readiness-excludes-kafka/'], ['OutboxRelayStalled', '/runbooks/alerts/#outboxrelaystalled']],
      },
      {
        title: 'The reconciler counted events in flight twice',
        what: 'The job that rewrites the read model from Postgres overwrote the counters while events were still on their way to the worker, which then applied them on top of the corrected totals.',
        found: 'The drift alert firing under ordinary traffic.',
        fix: 'Reconciliation stands down unless nothing has been published for 30 seconds, and a second alert catches the case where continuous writes starve it.',
        refs: [['ADR 0006', '/decisions/0006-cache-as-a-read-model/'], ['ReconciliationStarved', '/runbooks/alerts/#reconciliationstarved']],
      },
      {
        title: 'An unmapped path returned 500',
        what: 'A catch-all `@ExceptionHandler(Exception.class)` swallowed Spring MVC’s own exceptions, so a typo in a URL was a server error — and would have burned error budget on client mistakes.',
        found: 'The smoke test, asking for a path that does not exist.',
        fix: 'The handler extends `ResponseEntityExceptionHandler`. A semgrep rule now fails the build on the original shape, with an exception for exactly the fixed one.',
        refs: [['Enforced invariants', '/reference/invariants/']],
      },
      {
        title: 'Pool exhaustion was a 500',
        what: 'On a saturated node — 100 requests a second while new pods warmed their JVMs on the same CPUs — Hikari held 8 active connections with 32 requests waiting, and the API answered 500 to the ones that timed out.',
        found: 'The zero-downtime drill at a rate the node could not carry.',
        fix: 'A request that waits more than three seconds for a connection is answered 503 with `Retry-After`, with the pool’s occupancy in the log. An integration test holds the only connection to prove it.',
        refs: [['DatabasePoolExhausted', '/runbooks/alerts/#databasepoolexhausted']],
      },
    ],
  },
  {
    id: 'delivery',
    findings: [
      {
        title: 'The zero-downtime claim was false as shipped',
        what: 'A `preStop` sleep stops new connections reaching a terminating pod, but clients and the gateway keep reusing keep-alive connections until the server closes them; a close that crosses a request fails it. GETs were retried silently, POSTs were not: 4 failed of 11,972 during rollouts.',
        found: '`make drill`, A/B at 40 requests a second, half writes, three rollouts of both pods.',
        fix: 'The preStop hook drains: `Connection: close` for five seconds, then SIGTERM. 0 failed of 11,970 under identical load, and 0 of 6,001 in the weekly run.',
        refs: [['Kubernetes manifests', '/platform/kubernetes/#found-by-running-it']],
      },
      {
        title: 'A fresh cluster would never have come up',
        what: 'Postgres lived in the `apps` stage, which waits for the migration Job, which needs Postgres. It worked on a cluster where the database already existed and deadlocked on an empty one.',
        found: 'Bringing the manifests up in a real cluster for the first time.',
        fix: 'A separate `apps-data` stage for the stores, between the configuration and the migration.',
        refs: [['Reconciliation order', '/delivery/#reconciliation-order']],
      },
      {
        title: 'Both Jobs timed out under default-deny',
        what: 'The migration and Kafka topics Jobs had no egress rules; only long-running pods did. k3s enforces NetworkPolicy by default, so the first production deploy would have failed.',
        found: 'The same first bring-up.',
        fix: 'Egress policies for both Jobs, applied with the namespace so nothing runs before the policy that restricts it.',
      },
      {
        title: 'Kafka was unreachable, then killed while merely slow',
        what: 'DNS names hard-coded the production namespace. A headless Service has no DNS record until its pod is Ready, so the worker crashed with “No resolvable bootstrap urls”. And a liveness probe that started a JVM per check timed out on a busy node — the kubelet killed a healthy broker four times in one drill.',
        found: 'The dev overlay in kind, and the zero-downtime drill.',
        fix: 'Namespace-independent short names, a ClusterIP Service for bootstrap and a headless one for identity, and a TCP liveness check. The topic also went from one partition to three, to match the worker’s three consumer threads.',
      },
      {
        title: 'Image automation would never have found an image',
        what: 'The manifests, Flux’s image policy and `verify-image.sh` named a registry owner different from the one CI pushes to. Every genuine image would have been ignored, and every verification would have failed.',
        found: 'Tracing an image from the build workflow to the cluster by name.',
        fix: 'One owner everywhere. The deployments also gained the OTLP endpoint they lacked, which would otherwise have exported traces to localhost in production.',
      },
    ],
  },
  {
    id: 'supply-chain',
    findings: [
      {
        title: 'The admission policy the supply chain relied on did not exist',
        what: 'ADR 0008, the build workflow, the checkov configuration and `verify-image.sh` all said an unsigned image is inert because admission requires a signature. Nothing in the cluster required one.',
        found: 'Looking for the policy while documenting it.',
        fix: 'Sigstore’s policy-controller, with a ClusterImagePolicy that admits an image of ours only if `build.yml` in this repository signed it. It fails closed.',
        refs: [['ADR 0008', '/decisions/0008-supply-chain-pinned-signed-and-attested/'], ['Security', '/security/#the-supply-chain']],
      },
      {
        title: 'Once it existed, it would have refused every image CI publishes',
        what: 'cosign 3 signs in the Sigstore bundle format by default, and the policy-controller release available verifies bundles only for attestations: “bundle support for image signatures is not yet implemented”.',
        found: 'Testing admission in kind with the production chart values, rather than reading about it.',
        fix: 'CI signs image signatures in the legacy format; attestations keep the bundle format. A signed image was admitted; an unsigned one and one validly signed by another workflow were refused.',
      },
    ],
  },
  {
    id: 'observability',
    findings: [
      {
        title: 'An availability SLI read 100% through a total outage',
        what: 'When no replica is ready the gateway answers 503 itself, and 502 or 504 when one dies mid-request. None of those reach the application’s metrics, so an SLI computed there never moves.',
        found: 'Stopping the API and watching which series changed. Only the gateway’s router metrics counted the failures.',
        fix: 'Availability and latency are measured at the gateway.',
        refs: [['Service level objectives', '/slo/#what-the-slis-deliberately-do-and-do-not-count']],
      },
      {
        title: 'An outage was served as a 404',
        what: 'Traefik on Kubernetes drops a route whose Service has no endpoints, so with every API pod down, callers got 404 — a client error, which pages nobody.',
        found: 'The same outage, in the cluster.',
        fix: '`allowEmptyServices: true`, so an empty Service is a 503 counted against the objective.',
      },
      {
        title: 'Alerts written against names Micrometer does not publish',
        what: 'Micrometer strips the reserved `_total` suffix from gauges and appends base units: `blueprint_outbox_pending_events`, not the `blueprint_outbox_pending` the design named. An alert on the documented name matches nothing and never fires.',
        found: 'Writing the rules against a running Prometheus rather than the ADR.',
        fix: 'Rules use the published names, and every alert has a promtool test proving it fires.',
        refs: [['Alert catalog', '/alerts/']],
      },
      {
        title: '600 series for eight routes',
        what: 'The request timer’s percentile histogram published 75 buckets for every route, method and status — none of them a threshold anything alerted on.',
        found: 'Reading `scrape_samples_post_metric_relabeling` per target.',
        fix: 'Only the objective thresholds are published. The API’s scrape went from 1,025 samples to 412, and the worker’s end-to-end timer from 69 buckets to 7.',
        refs: [['Measurements', '/measurements/#observability-cost']],
      },
      {
        title: '176 of 198 stored traces were noise',
        what: 'The relay’s one-second poll, other scheduled tasks, and probes calling the actuator.',
        found: 'Tempo, ten minutes after the tracing pipeline first ran.',
        fix: 'A span filter in the platform module drops them at export. The metrics those spans came from are untouched.',
      },
    ],
  },
  {
    id: 'data',
    findings: [
      {
        title: 'The restore would have failed at the worst moment',
        what: 'Postgres refuses to start with `recovery_target_time` written with `Z` for UTC: the setting is checked before timezone abbreviations are loaded.',
        found: 'The first run of the restore drill — before anyone needed a restore.',
        fix: 'The restore script writes the target as `+00`. The drill runs weekly in CI with 100,000 rows.',
        refs: [['Restore the database', '/runbooks/db-restore/']],
      },
      {
        title: 'Postgres waited forever for its configuration',
        what: 'Generated ConfigMaps without a namespace landed in `default` when included from a kustomization without one, and the pod sat in PodInitializing on “configmap not found”.',
        found: 'The restore runbook, run command by command in kind.',
        fix: 'Namespaced generators. The metrics exporter also moved out of the database pod before it shipped: a crashlooping sidecar makes the database unready and removes it from DNS.',
      },
    ],
  },
  {
    id: 'runtime',
    findings: [
      {
        title: 'Hardening that looks right in review crashlooped the stores',
        what: '`cap_drop: ALL` on the stock Postgres and Redis images: their entrypoints start as root and need SETUID and SETGID to drop privileges.',
        found: 'The first `make up`.',
        fix: 'Capabilities are dropped on the images this repository builds. In the cluster, Postgres runs as a fixed unprivileged uid from the start, which the restricted Pod Security Standard requires anyway.',
      },
      {
        title: 'A jlink runtime built from jdeps fails on the first request',
        what: 'jdeps reports only `java.base,java.sql` for this application; it cannot see reflection, JDBC driver discovery or ServiceLoader. Separately, `--strip-debug` shells out to `objcopy`, which the build image does not have.',
        found: 'The smoke test, not the image build.',
        fix: 'A curated module list, certified by the smoke test; `--strip-java-debug-attributes` instead.',
      },
      {
        title: 'Kafka in Testcontainers never started under podman',
        what: 'The advertised listener was derived from Docker’s legacy `NetworkSettings.IPAddress`, which podman leaves empty, so the broker always advertised 0.0.0.0 and refused to start.',
        found: 'Running the suite on rootless podman.',
        fix: 'An explicit test fixture that pins a free host port; it behaves identically on both runtimes.',
      },
      {
        title: 'Rootless podman on SELinux: three kinds of permission denied',
        what: 'Bind-mounted secrets need relabelling that compose `secrets:` cannot express; Traefik cannot read the podman socket; and distroless containers run as a uid that cannot read a 0600 file the Postgres container reads fine.',
        found: 'Running the stack on an enforcing Fedora host.',
        fix: 'An overlay applied automatically on such hosts, and different modes for the two secret directories, for a reason written next to them.',
      },
      {
        title: '`podman-compose up --build` never deployed new code',
        what: 'It rebuilt the image and kept the running container on the old one — and could not replace the API alone while the gateway and worker depended on it.',
        found: 'A fix that verified “live” while the old image was still serving.',
        fix: '`make up` recreates the whole project under podman-compose.',
      },
    ],
  },
  {
    id: 'tooling',
    findings: [
      {
        title: 'The dependency scanner scanned nothing, and passed',
        what: '`trivy fs` cannot resolve a Gradle build: pointed at this repository it reports “number of language-specific files: 0” and exits zero. The green check is indistinguishable from a pass.',
        found: 'Reading the scanner’s output rather than its exit code.',
        fix: 'Dependency CVEs are scanned inside the built image, where the jars are real files, before it is signed.',
        refs: [['ADR 0008', '/decisions/0008-supply-chain-pinned-signed-and-attested/']],
      },
      {
        title: 'The configuration scanner passed by luck',
        what: 'checkov’s kustomize renderer writes every overlay to temporary files named after it, and four overlays here are named `prod`. Some runs crashed with a ComposerError, others hung; green results had been luck for weeks.',
        found: 'Reproducing an intermittent failure on an older tree.',
        fix: 'checkov scans exactly what kustomize renders, from one script shared by the Makefile and both workflows.',
      },
      {
        title: 'SOPS silently dropped every Secret after the first',
        what: 'Given a multi-document YAML file, SOPS encrypts the first document and discards the rest — a commit that looks right until a missing Secret is referenced at apply time.',
        found: 'Diffing the decrypted output against the plaintext.',
        fix: 'One Kubernetes object per encrypted file.',
      },
      {
        title: 'A flaky smoke test that was really a pipe',
        what: '`printf | grep -q` under `pipefail`: grep exits on the first match, printf gets EPIPE, and the pipeline reports failure — only for bodies larger than the pipe buffer, which is exactly what `/actuator/prometheus` returns.',
        found: 'A smoke test that failed one run in several.',
        fix: 'Match on a captured body instead of a pipe.',
      },
      {
        title: 'Terraform against a provider that speaks S3 but is not AWS',
        what: 'Five AWS S3 policies fire against Hetzner Object Storage, which implements none of them — `PutPublicAccessBlock` returns 501, so satisfying the scanner would break every apply. Child modules without a full provider source address resolved a provider that does not exist, with the error naming the wrong file.',
        found: '`terraform validate` against the real provider schemas, and checkov.',
        fix: 'Documented suppressions with verified reasons, and `required_providers` in every module.',
      },
      {
        title: 'Pre-commit and CI disagreed about the same scripts',
        what: 'shellcheck reports SC1091 on every script when files are passed one at a time, as pre-commit does, and nothing when the directory is passed at once, as CI did.',
        found: 'A commit that passed one gate and failed the other.',
        fix: 'Both run with `--external-sources`. A local hook that disagrees with CI is not a control.',
      },
    ],
  },
];
