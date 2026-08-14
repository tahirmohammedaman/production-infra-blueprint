// Section outlines for the pages written for the site. A page renders its headings from here,
// and the contents rail and the search index read the same entries, so the three never disagree.

import type { Heading } from './markdown';

export interface OutlineSection extends Heading {
  /** A sentence for search results. */
  summary: string;
}

type Raw = [id: string, text: string, summary: string, depth?: 2 | 3];

function build(sections: Raw[]): Record<string, OutlineSection> & { list: OutlineSection[] } {
  let n = 0;
  const list = sections.map(([id, text, summary, depth = 2]) => ({
    id,
    text,
    summary,
    depth,
    index: depth === 2 ? String(++n).padStart(2, '0') : undefined,
  }));
  return Object.assign(Object.fromEntries(list.map((s) => [s.id, s])), { list });
}

export const outlines = {
  '/': build([
    ['what-it-is', 'What this is', 'Four components, held at four, each exposing an operational problem the others do not have.'],
    ['the-system', 'The system', 'A write commits the item and its event in one transaction; a relay publishes afterwards.'],
    ['claims', 'Claims, and what tests each one', 'Every claim is held up by a drill or a test that causes the failure and checks the outcome.'],
    ['the-bill', 'The bill', '€17.12 a month, against $167.62 for the same architecture on AWS and $650.74 managed.'],
    ['where-to-start', 'Where to start', 'Reading paths for reviewing the design, operability, cost and security.'],
    ['stack', 'Stack', 'Every layer and the decision record behind it.'],
  ]),
  '/quickstart/': build([
    ['requirements', 'Requirements', 'Docker or rootless podman with compose, about 2 GB of memory, and a handful of local ports.'],
    ['bring-it-up', 'Bring it up', 'make bootstrap builds both images, starts everything in health order and runs the smoke test.'],
    ['what-bootstrap-proves', 'What bootstrap proves', 'Bootstrap does not succeed until a request has travelled the whole system and back.'],
    ['endpoints', 'Endpoints', 'The API, OpenAPI UI, gateway dashboard, actuators, Grafana, Prometheus and Alertmanager.'],
    ['secrets-locally', 'Secrets, locally', 'Generated files read through configtree:, the same mechanism production uses.'],
    ['exercise-the-claims', 'Exercise the claims', 'Load, the zero-downtime drill, the restore drill and the observability checks.'],
    ['stores', 'Look inside the stores', 'psql, redis-cli, consumer lag, topics and the dead-letter topic.'],
    ['tear-down', 'Tear down', 'make down stops everything and deletes the volumes.'],
  ]),
  '/findings/': build([
    ['how-to-read', 'How to read this', 'Defects found by running the system rather than reading it, each fixed and tested.'],
    ['async', 'Correctness across services', 'Outbox outage semantics, reconciliation races, readiness and pool exhaustion.'],
    ['delivery', 'Rollouts and a fresh cluster', 'Keep-alive drain, a fresh-cluster deadlock, job egress and Kafka DNS.'],
    ['supply-chain', 'Supply chain', 'An admission policy that did not exist and a signature format it could not read.'],
    ['observability', 'Observability', 'SLIs that read 100% through an outage, metric names and histogram cardinality.'],
    ['data', 'Data and backups', 'A recovery target Postgres refuses at startup, and namespaced config generators.'],
    ['runtime', 'Containers and local runtime', 'jlink, capabilities, SELinux, podman and Testcontainers.'],
    ['tooling', 'Scanners and tooling', 'Scanners that scanned nothing, crashed, or passed by luck.'],
  ]),
  '/build-record/': build([
    ['calendar', 'Commits by day', 'Thirty-nine commits between 4 July and 9 August 2026, in seven phases.'],
    ['phases', 'Phases', 'Foundation, containers, the multi-service split, CI, infrastructure, observability, operations.'],
    ['not-yet', 'Not yet done', 'The first live deploy and the follow-ups each phase left behind.'],
  ]),
  '/decisions/': build([
    ['records', 'Records', 'Ten architecture decision records, from the node to the observability stack.'],
    ['how-they-are-written', 'How they are written', 'Context, decision and consequences, including what each decision costs.'],
  ]),
  '/delivery/': build([
    ['from-commit-to-cluster', 'From commit to cluster', 'CI publishes a signed image and stops; Flux pulls the change from git.'],
    ['workflows', 'Four workflows', 'ci, security, build and drills, each behind a single required status check.'],
    ['reconciliation-order', 'Reconciliation order', 'Six Flux Kustomizations, each waiting for the previous one to be healthy.'],
    ['properties', 'Properties worth knowing', 'Path-filtered builds, signature as the gate, drills on a schedule.'],
    ['verify', 'Verifying it yourself', 'Pins, signatures, SBOMs and provenance, checked against this repository.'],
    ['what-ci-never-holds', 'What CI never holds', 'No kubeconfig, cloud credential or age key exists in any workflow.'],
  ]),
  '/alerts/': build([
    ['severities', 'Two severities', 'page for user-visible impact or a monitoring hole; ticket for everything else.'],
    ['catalog', 'The catalog', 'Every alert, read from the rule files, linked to its runbook section.'],
    ['adding-an-alert', 'Adding an alert', 'A rule, a promtool test that shows when it fires and when it must not, and a runbook section.'],
  ]),
  '/runbooks/': build([
    ['runbooks', 'Runbooks', 'Incident triage, alerts, rollback, database restore, certificates and schema changes.'],
    ['conventions', 'How they are written', 'Symptom first, commands for the cluster, the local equivalent underneath.'],
  ]),
  '/measurements/': build([
    ['method', 'Method', 'Footprints measured on this repository, each with the method used.'],
    ['image', 'Container image', '523 MB naive to 170 MB shipped; a code-only deploy ships 33 KB.'],
    ['startup', 'Startup and CDS', 'Context refresh 4.90 s, 3.66 s with a CDS archive that costs 88 MB.'],
    ['memory', 'Memory against limits', 'Resident memory of every container against the limit it was sized from.'],
    ['node', 'What fits on the node', '3.7 Gi requested of 8 GB.'],
    ['observability-cost', 'What observing it costs', '590 MB of memory, series budgets and 1,101 log bytes per request.'],
    ['drills', 'Load, rollouts and restores', 'Failed requests under load and during rollouts, and restore timings.'],
    ['still-to-measure', 'Still to be measured', 'An invoice, Hetzner object storage, Kafka page cache, real data.'],
  ]),
  '/reference/make/': build([['targets', 'Targets', 'Every make target, read from the Makefile.']]),
  '/reference/invariants/': build([['rules', 'Rules', 'Semgrep rules specific to this repository, read from .semgrep/blueprint.yml.']]),
  '/reference/repository/': build([
    ['tree', 'Layout', 'Where every layer lives in the repository.'],
    ['look-first', 'What to look at first', 'The files that carry the most argument per line.'],
  ]),
};

export type OutlineRoute = keyof typeof outlines;

export function headingsFor(route: OutlineRoute): Heading[] {
  return outlines[route].list;
}
