// The search index: one entry per section of every page, plus every alert, make target and
// semgrep rule. Built once, fetched the first time someone opens search.

import type { APIRoute } from 'astro';
import { getDocs, pageIndex } from '../lib/content';
import { outlines } from '../lib/outlines';
import { alertRules, makeTargets, semgrepRules } from '../lib/repo-data';
import { withBase } from '../lib/paths';

interface Entry {
  title: string;
  heading: string;
  group: string;
  url: string;
  text: string;
}

export const GET: APIRoute = async () => {
  const pages = await pageIndex();
  const groupOf = (route: string) => pages.find((p) => p.route === route)?.groupTitle ?? '';
  const titleOf = (route: string) => pages.find((p) => p.route === route)?.title ?? '';
  const entries: Entry[] = [];

  for (const doc of await getDocs()) {
    const title = doc.meta.number ? `ADR ${doc.meta.number} — ${doc.title}` : doc.title;
    for (const s of doc.sections) {
      entries.push({
        title,
        heading: s.id ? s.heading : title,
        group: groupOf(doc.route),
        url: withBase(doc.route) + (s.id ? `#${s.id}` : ''),
        text: s.id ? s.text : doc.summary,
      });
    }
  }

  for (const [route, outline] of Object.entries(outlines)) {
    const title = titleOf(route);
    entries.push({ title, heading: title, group: groupOf(route), url: withBase(route), text: outline.list.map((s) => s.text).join(' · ') });
    for (const s of outline.list) {
      entries.push({ title, heading: s.text, group: groupOf(route), url: `${withBase(route)}#${s.id}`, text: s.summary });
    }
  }

  for (const a of alertRules()) {
    entries.push({ title: 'Alert catalog', heading: a.name, group: 'Reliability', url: `${withBase('/alerts/')}#${a.name.toLowerCase()}`, text: `${a.severity} · ${a.summary}` });
  }
  for (const section of makeTargets()) {
    for (const t of section.targets) {
      const id = `make-${t.name.replace(/[^a-z0-9]+/gi, '-').replace(/-+$/, '').toLowerCase()}`;
      entries.push({ title: 'Make targets', heading: `make ${t.name}`, group: 'Reference', url: `${withBase('/reference/make/')}#${id}`, text: t.description });
    }
  }
  for (const r of semgrepRules().rules) {
    entries.push({ title: 'Enforced invariants', heading: r.id, group: 'Reference', url: `${withBase('/reference/invariants/')}#${r.id}`, text: r.message });
  }

  return new Response(JSON.stringify(entries), { headers: { 'Content-Type': 'application/json' } });
};
