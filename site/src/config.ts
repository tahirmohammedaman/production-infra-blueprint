// Site identity and navigation. Pages rendered from repository markdown name their `source`;
// everything else under src/pages is written for the site and reads its numbers from the
// same documents it links to.

export const site = {
  title: 'Production Infrastructure Blueprint',
  description:
    'A small distributed system taken from git push to a monitored, alerting, zero-downtime ' +
    'production deployment — provisioned, built, observed and operated from one repository, ' +
    'with every footprint and cost figure measured.',
  repo: 'https://github.com/tahirmohammedaman/production-infra-blueprint',
  branch: 'main',
  // The date the documented system last changed. The title block on every sheet carries it.
  revision: '2026-08-09',
} as const;

export interface NavItem {
  title: string;
  route: string;
  /** Repository path of the markdown this page is rendered from. */
  source?: string;
  /** A directory of markdown documents listed under this page. */
  collection?: 'adr' | 'runbooks';
}

export interface NavGroup {
  id: string;
  title: string;
  items: NavItem[];
}

export const nav: NavGroup[] = [
  {
    id: 'overview',
    title: 'Overview',
    items: [
      { title: 'Introduction', route: '/' },
      { title: 'Quickstart', route: '/quickstart/' },
    ],
  },
  {
    id: 'architecture',
    title: 'Architecture',
    items: [
      { title: 'System architecture', route: '/architecture/', source: 'docs/architecture.md' },
      { title: 'Decision records', route: '/decisions/', collection: 'adr' },
    ],
  },
  {
    id: 'delivery',
    title: 'Delivery & security',
    items: [
      { title: 'Delivery pipeline', route: '/delivery/' },
      { title: 'Security', route: '/security/', source: 'docs/security.md' },
    ],
  },
  {
    id: 'platform',
    title: 'Platform',
    items: [
      { title: 'Terraform', route: '/platform/terraform/', source: 'infra/terraform/README.md' },
      { title: 'Ansible', route: '/platform/ansible/', source: 'infra/ansible/README.md' },
      { title: 'Kubernetes manifests', route: '/platform/kubernetes/', source: 'deploy/k8s/README.md' },
      { title: 'Observability stack', route: '/platform/observability/', source: 'observability/README.md' },
    ],
  },
  {
    id: 'reliability',
    title: 'Reliability',
    items: [
      { title: 'Service level objectives', route: '/slo/', source: 'docs/slo.md' },
      { title: 'Alert catalog', route: '/alerts/' },
      { title: 'Runbooks', route: '/runbooks/', collection: 'runbooks' },
    ],
  },
  {
    id: 'operations',
    title: 'Operations & cost',
    items: [
      { title: 'Operations', route: '/operations/', source: 'docs/operations.md' },
      { title: 'Cost analysis', route: '/cost/', source: 'docs/cost-analysis.md' },
      { title: 'Measurements', route: '/measurements/' },
    ],
  },
  {
    id: 'reference',
    title: 'Reference',
    items: [
      { title: 'Make targets', route: '/reference/make/' },
      { title: 'Enforced invariants', route: '/reference/invariants/' },
      { title: 'Repository map', route: '/reference/repository/' },
    ],
  },
];
