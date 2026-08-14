// The site's page list: every nav entry, with each collection's documents expanded in place.
// Page order, sheet numbers and previous/next links all come from here.

import { nav } from '../config';
import { collectionSources, sourceRoutes } from './paths';
import { renderMarkdown, type RenderedDoc } from './markdown';

export interface Doc extends RenderedDoc {
  route: string;
}

export interface PageEntry {
  route: string;
  title: string;
  groupId: string;
  groupTitle: string;
  groupNumber: string;
  source?: string;
  /** An ADR's number. */
  number?: string;
  /** The index page a collection document is listed under. */
  parent?: string;
}

const rendered = new Map<string, Promise<Doc>>();

export function getDoc(source: string): Promise<Doc> {
  let doc = rendered.get(source);
  if (!doc) {
    const route = sourceRoutes().get(source);
    if (!route) throw new Error(`${source} has no route in src/config.ts`);
    doc = renderMarkdown(source).then((r) => ({ ...r, route }));
    rendered.set(source, doc);
  }
  return doc;
}

export async function getDocs(): Promise<Doc[]> {
  const sources = [...sourceRoutes().keys()].filter((s) => s.endsWith('.md'));
  return Promise.all(sources.map(getDoc));
}

export async function getCollection(key: 'adr' | 'runbooks'): Promise<Doc[]> {
  return Promise.all(collectionSources(key).map(getDoc));
}

let pages: Promise<PageEntry[]> | undefined;

export function pageIndex(): Promise<PageEntry[]> {
  pages ??= (async () => {
    const entries: PageEntry[] = [];
    for (const [g, group] of nav.entries()) {
      const groupNumber = String(g + 1).padStart(2, '0');
      const base = { groupId: group.id, groupTitle: group.title, groupNumber };
      for (const item of group.items) {
        entries.push({ ...base, route: item.route, title: item.title, source: item.source });
        if (item.collection) {
          for (const doc of await getCollection(item.collection)) {
            entries.push({ ...base, route: doc.route, title: doc.title, number: doc.meta.number, source: doc.source, parent: item.route });
          }
        }
      }
    }
    return entries;
  })();
  return pages;
}
