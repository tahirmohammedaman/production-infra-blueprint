// The build as it happened, from `git log`: phase by phase, commit by commit. Fixed rather
// than read at build time, because a shallow clone on a hosting provider has no history to read.

export interface Commit {
  sha: string;
  at: string;
  subject: string;
}

export interface Phase {
  id: string;
  title: string;
  window: string;
  note: string;
  commits: Commit[];
}

export const phases: Phase[] = [
  {
    id: 'P1',
    title: 'Foundation',
    window: '4 – 7 July',
    note: 'The API service, its schema, RFC 7807 errors, structured logs and metrics, and one Makefile entrypoint for everything.',
    commits: [
      { sha: 'f160527', at: '2026-07-04 11:26', subject: 'chore(repo): scaffold repository with license, editorconfig and ignore rules' },
      { sha: 'f00aee0', at: '2026-07-04 15:41', subject: 'build(app): bootstrap spring boot service with gradle version catalog' },
      { sha: '3b59dd3', at: '2026-07-04 22:07', subject: 'feat(db): add item domain model with jpa persistence and flyway baseline' },
      { sha: '980d71f', at: '2026-07-05 13:18', subject: 'feat(api): expose item crud endpoints with rfc 7807 problem responses' },
      { sha: '6f6a5f9', at: '2026-07-05 17:04', subject: 'feat(obs): add correlation ids, structured json logs and prometheus metrics' },
      { sha: '4ea99f3', at: '2026-07-07 21:52', subject: 'chore(make): add makefile entrypoints and environment template' },
    ],
  },
  {
    id: 'P2',
    title: 'Containers and the local stack',
    window: '11 – 13 July',
    note: 'The distroless jlink image and its measurements, the Compose stack, tests against real backends, and the first defects found by running it.',
    commits: [
      { sha: '59f34ae', at: '2026-07-11 10:52', subject: 'feat(docker): add hardened multi-stage image with jlink runtime on distroless' },
      { sha: 'fe35ef8', at: '2026-07-11 15:26', subject: 'feat(compose): add local stack with healthchecked postgres and hardened api' },
      { sha: '8162536', at: '2026-07-11 22:41', subject: 'fix(api): map unmapped paths and constraint violations to real status codes' },
      { sha: 'c1ac349', at: '2026-07-12 11:34', subject: 'fix(obs): rename item gauge so micrometer stops stripping the total suffix' },
      { sha: '8de7a47', at: '2026-07-12 16:58', subject: 'test(app): add unit, web-slice and testcontainers integration suites' },
      { sha: 'cc3661e', at: '2026-07-13 20:11', subject: 'build(app): enforce java formatting with spotless and add pre-commit hooks' },
      { sha: 'e96a87e', at: '2026-07-13 22:47', subject: 'feat(scripts): add bootstrap, teardown and smoke test entrypoints' },
      { sha: '8b58dc4', at: '2026-07-13 23:52', subject: 'docs(cost): record measured image size, startup and memory figures' },
    ],
  },
  {
    id: 'P3',
    title: 'Four components',
    window: '15 – 17 July',
    note: 'The split into api, worker and a shared platform module; Kafka, Redis and Traefik; the outbox, the idempotent consumer and the decisions behind them.',
    commits: [
      { sha: '28837e3', at: '2026-07-15 20:34', subject: 'refactor(services): split into a gradle multi-project with a shared platform module' },
      { sha: 'b53d709', at: '2026-07-15 23:47', subject: 'feat(worker): add kafka consumer with idempotency, bounded retry and dead-letter topic' },
      { sha: 'd7645b5', at: '2026-07-16 21:18', subject: 'feat(compose): add gateway, kafka and redis with health-gated startup ordering' },
      { sha: '55c25d5', at: '2026-07-17 12:05', subject: 'test(services): cover outbox, projection and dead-letter paths against real brokers' },
      { sha: 'c2c0d82', at: '2026-07-17 22:41', subject: 'docs(adr): record the kafka, outbox, cache and readiness decisions' },
    ],
  },
  {
    id: 'P4',
    title: 'CI and the supply chain',
    window: '20 – 22 July',
    note: 'Gates on formatting, tests and coverage; secret, SAST and configuration scanning; signed multi-arch images with SBOM and provenance; every action pinned.',
    commits: [
      { sha: '34bfcb6', at: '2026-07-20 20:47', subject: 'ci: gate merges on formatting, tests and per-service coverage thresholds' },
      { sha: 'c491f78', at: '2026-07-21 21:38', subject: 'ci(sec): add secret, sast and misconfiguration scanning with repo-specific rules' },
      { sha: '5c71387', at: '2026-07-22 20:26', subject: 'ci(cd): publish signed multi-arch images to ghcr with sbom and provenance' },
      { sha: 'e9eb8d8', at: '2026-07-22 23:47', subject: 'chore(ci): enforce action pinning and wire renovate to keep the pins current' },
    ],
  },
  {
    id: 'P5',
    title: 'Infrastructure and delivery',
    window: '24 – 26 July',
    note: 'Terraform for the node, network, firewall and storage; Ansible hardening and k3s; Kustomize with SOPS; Flux with image automation.',
    commits: [
      { sha: '696d512', at: '2026-07-24 21:12', subject: 'feat(tf): provision the hetzner node, network, firewall and object storage' },
      { sha: '3535f46', at: '2026-07-25 12:41', subject: 'feat(ansible): harden the node and bootstrap k3s with audit logging and secrets encryption' },
      { sha: '16b9d3c', at: '2026-07-25 22:58', subject: 'feat(k8s): add the kustomize base and overlays with sops-encrypted secrets' },
      { sha: 'b25f3e6', at: '2026-07-26 16:34', subject: 'feat(cd): reconcile the cluster from git with flux and automated image updates' },
    ],
  },
  {
    id: 'P6',
    title: 'Observability, and the manifests in a real cluster',
    window: '29 July – 1 August',
    note: 'Metrics, logs, traces, burn-rate objectives and dashboards — which promptly found the outbox and reconciler defects — then the manifests in kind and the zero-downtime drill.',
    commits: [
      { sha: '0449ef3', at: '2026-07-29 20:48', subject: 'feat(prom): scrape services and gateway, record red metrics and route alerts' },
      { sha: '3ad9c5d', at: '2026-07-29 23:41', subject: 'feat(loki): ship logs through alloy and traces to tempo within a label budget' },
      { sha: 'c125f78', at: '2026-07-30 20:52', subject: 'fix(api): keep broker outages and in-flight events from corrupting async state' },
      { sha: 'd8c7e79', at: '2026-07-31 00:46', subject: 'feat(grafana): add burn-rate slos and provision four dashboards as code' },
      { sha: 'cb34d68', at: '2026-07-31 21:12', subject: 'feat(cd): reconcile the observability stack in-cluster from the same files' },
      { sha: 'ad160a5', at: '2026-08-01 13:52', subject: 'fix(k8s): make a fresh cluster come up with a data stage and job egress' },
      { sha: 'bda93ef', at: '2026-08-01 17:36', subject: 'test(load): prove zero-downtime rollouts with k6 and drain keep-alive first' },
    ],
  },
  {
    id: 'P7',
    title: 'Recovery, admission and the documents',
    window: '6 – 9 August',
    note: 'Point-in-time recovery and its drill, 503 on pool exhaustion, both drills weekly in CI, signature admission, and the architecture, security, operations and cost documents.',
    commits: [
      { sha: '802f582', at: '2026-08-06 22:48', subject: 'feat(db): archive wal to object storage and prove point-in-time restores' },
      { sha: 'd9bc02f', at: '2026-08-07 21:26', subject: 'fix(api): answer pool exhaustion with 503 and run both drills weekly in ci' },
      { sha: '6626363', at: '2026-08-08 00:41', subject: 'feat(sec): enforce signed images at admission and scan rendered manifests' },
      { sha: '27ed746', at: '2026-08-08 17:22', subject: 'docs: add architecture, security and operations guides, adrs and runbooks' },
      { sha: 'c4981c8', at: '2026-08-09 16:47', subject: 'docs(cost): price the stack against managed equivalents and finish the readme' },
    ],
  },
];
