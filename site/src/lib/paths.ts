// Where things are: the repository on disk, its files on GitHub, and the route each rendered
// document lives at. Every link the markdown pipeline writes goes through here, so a path that
// resolves to nothing fails the build instead of shipping a 404.

import fs from 'node:fs';
import path from 'node:path';
import { nav, site } from '../config';

// Astro runs from site/, and bundles pages into a temporary directory before rendering them,
// so import.meta.url does not point anywhere stable. The working directory does.
export const repoRoot = path.resolve(process.cwd(), '..');

export function repoFile(repoPath: string): string {
  return path.join(repoRoot, repoPath);
}

export function repoPathExists(repoPath: string): boolean {
  return repoPath !== '' && !repoPath.startsWith('..') && fs.existsSync(repoFile(repoPath));
}

export function readRepoFile(repoPath: string): string {
  return fs.readFileSync(repoFile(repoPath), 'utf8');
}

export function githubUrl(repoPath: string): string {
  const clean = repoPath.replace(/\/$/, '');
  const kind = fs.existsSync(repoFile(clean)) && fs.statSync(repoFile(clean)).isDirectory() ? 'tree' : 'blob';
  return `${site.repo}/${kind}/${site.branch}/${clean}`;
}

/** Prefix a site route with the configured base path. */
export function withBase(route: string): string {
  const base = import.meta.env.BASE_URL.replace(/\/$/, '');
  return base + route;
}

const collections = {
  adr: { dir: 'docs/adr', order: [] as string[] },
  // The order the README lists them in: where an on-call engineer starts, then what they reach.
  runbooks: {
    dir: 'docs/runbooks',
    order: ['incident-triage', 'alerts', 'rollback', 'db-restore', 'cert-expiry', 'zero-downtime-migration'],
  },
};

export function collectionSources(key: keyof typeof collections): string[] {
  const { dir, order } = collections[key];
  const stems = fs
    .readdirSync(repoFile(dir))
    .filter((f) => f.endsWith('.md'))
    .map((f) => f.slice(0, -3))
    .sort((a, b) => {
      const ia = order.indexOf(a);
      const ib = order.indexOf(b);
      if (ia !== ib) return (ia === -1 ? Infinity : ia) - (ib === -1 ? Infinity : ib);
      return a.localeCompare(b);
    });
  return stems.map((stem) => `${dir}/${stem}.md`);
}

let routes: Map<string, string> | undefined;

/** Repository path → site route, for every file and directory the site renders. */
export function sourceRoutes(): Map<string, string> {
  if (routes) return routes;
  routes = new Map();
  for (const group of nav) {
    for (const item of group.items) {
      if (item.source) routes.set(item.source, item.route);
      if (item.collection) {
        routes.set(collections[item.collection].dir, item.route);
        for (const source of collectionSources(item.collection)) {
          routes.set(source, item.route + path.posix.basename(source, '.md') + '/');
        }
      }
    }
  }
  return routes;
}

export function routeForSource(repoPath: string): string | undefined {
  return sourceRoutes().get(repoPath.replace(/\/$/, ''));
}
