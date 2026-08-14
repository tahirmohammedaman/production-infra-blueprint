// Renders a markdown file from the repository into a page.
//
// The documents are written for GitHub first, and they stay that way: nothing in docs/ knows
// this site exists. Everything that makes them read well here happens in this file:
//
//   - the H1 becomes the page title, and an ADR's Date/Status lines become its metadata
//   - a runbook's opening **Symptom:** paragraphs become a brief at the top of the page
//   - relative links are rewritten to site routes; a link to a file the site does not render
//     goes to that file on GitHub; a link to nothing fails the build
//   - an inline `path/in/the/repo` that exists becomes a link, and so does "ADR 0007"
//   - code is highlighted at build time; mermaid is left for the browser to draw
//   - heading ids use GitHub's slugs, so every anchor written for GitHub still works

import path from 'node:path';
import { unified } from 'unified';
import remarkParse from 'remark-parse';
import remarkGfm from 'remark-gfm';
import remarkRehype from 'remark-rehype';
import rehypeStringify from 'rehype-stringify';
import { visit, SKIP } from 'unist-util-visit';
import { toString as hastText } from 'hast-util-to-string';
import GithubSlugger from 'github-slugger';
import { bundledLanguages, createHighlighter, type Highlighter } from 'shiki';
import { blueprintDark, blueprintLight } from './shiki-themes';
import { githubUrl, readRepoFile, repoPathExists, routeForSource, sourceRoutes, withBase } from './paths';

// The unist trees are walked structurally; their type packages are not direct dependencies.
// eslint-disable-next-line @typescript-eslint/no-explicit-any
type Node = any;

export interface Heading {
  depth: number;
  id: string;
  text: string;
  index?: string;
}

export interface Section {
  id: string;
  heading: string;
  text: string;
}

export interface RenderedDoc {
  source: string;
  title: string;
  /** "Runbook", "Decision record" — what kind of document the H1 said this was. */
  kind: 'doc' | 'adr' | 'runbook';
  meta: { number?: string; date?: string; status?: string };
  lede: string;
  /** One plain sentence or two, for index pages and search results. */
  summary: string;
  headings: Heading[];
  sections: Section[];
  html: string;
  minutes: number;
}

let highlighterPromise: Promise<Highlighter> | undefined;

const LANGS = ['bash', 'sql', 'yaml', 'json', 'hcl', 'java', 'dockerfile', 'ini', 'javascript', 'diff'];

function highlighter(): Promise<Highlighter> {
  highlighterPromise ??= createHighlighter({ themes: [blueprintDark, blueprintLight], langs: LANGS });
  return highlighterPromise;
}

const LANG_LABELS: Record<string, string> = { bash: 'shell', sh: 'shell', text: 'text' };

function mdText(node: Node): string {
  if (!node) return '';
  if (typeof node.value === 'string') return node.value;
  return (node.children ?? []).map(mdText).join('');
}

function el(tagName: string, properties: Record<string, unknown> = {}, children: Node[] = []): Node {
  return { type: 'element', tagName, properties, children };
}

function text(value: string): Node {
  return { type: 'text', value };
}

function capitalise(s: string): string {
  return s.charAt(0).toUpperCase() + s.slice(1);
}

/** Resolve a link written relative to `source` into something that exists. */
function resolveHref(href: string, source: string): { href: string; external: boolean } {
  if (/^[a-z][a-z0-9+.-]*:/i.test(href) || href.startsWith('//')) return { href, external: true };
  if (href.startsWith('#')) return { href, external: false };
  const [target, hash] = href.split('#');
  const repoPath = path.posix.normalize(path.posix.join(path.posix.dirname(source), target)).replace(/\/$/, '');
  const suffix = hash ? `#${hash}` : '';
  const route = routeForSource(repoPath);
  if (route) return { href: withBase(route) + suffix, external: false };
  if (repoPathExists(repoPath)) return { href: githubUrl(repoPath) + suffix, external: true };
  throw new Error(`${source}: the link "${href}" resolves to ${repoPath}, which does not exist`);
}

const REPO_PATH = /^\.?[\w@-][\w.@-]*(\/[\w.@*-]+)*\/?$/;
const ROOT_FILES = new Set(['Makefile', 'README.md', 'LICENSE', '.env.example', '.sops.yaml', '.checkov.yaml', '.trivyignore.yaml', '.gitleaks.toml', '.pre-commit-config.yaml']);

/** A link for an inline code span that names a real repository path, if it does. */
function codePathHref(value: string, source: string): { href: string; external: boolean } | undefined {
  if (!REPO_PATH.test(value) || value.includes('*')) return undefined;
  let clean = value.replace(/\/$/, '');
  // A sibling document named on its own, as the runbooks name each other: `rollback.md`.
  const sibling = path.posix.join(path.posix.dirname(source), clean);
  if (!clean.includes('/') && clean.endsWith('.md') && routeForSource(sibling)) clean = sibling;
  if (!clean.includes('/') && !ROOT_FILES.has(clean)) return undefined;
  if (!repoPathExists(clean)) return undefined;
  const route = routeForSource(clean);
  return route ? { href: withBase(route), external: false } : { href: githubUrl(clean), external: true };
}

function adrRoute(number: string): string | undefined {
  for (const [source, route] of sourceRoutes()) {
    if (source.startsWith(`docs/adr/${number}-`)) return route;
  }
  return undefined;
}

interface State {
  source: string;
  title: string;
  kind: RenderedDoc['kind'];
  meta: RenderedDoc['meta'];
  lede: string;
  summary: string;
  headings: Heading[];
  sections: Section[];
}

function transformMarkdown(tree: Node, state: State): void {
  const children: Node[] = tree.children;

  // Title.
  const h1 = children.findIndex((n) => n.type === 'heading' && n.depth === 1);
  let rawTitle = '';
  if (h1 !== -1) {
    rawTitle = mdText(children[h1]);
    children.splice(h1, 1);
  }
  let title = rawTitle;
  const adr = /^(\d+)\.\s+(.+)$/.exec(rawTitle);
  const runbook = /^Runbook:\s*(.+)$/.exec(rawTitle);
  if (state.source.startsWith('docs/adr/') && adr) {
    state.kind = 'adr';
    state.meta.number = adr[1].padStart(4, '0');
    title = adr[2];
  } else if (runbook) {
    state.kind = 'runbook';
    title = capitalise(runbook[1]);
  }
  state.title = title;

  // ADR metadata: a paragraph of "Date: …" and "Status: …" lines directly under the title.
  const first = children[h1 === -1 ? 0 : h1];
  if (first?.type === 'paragraph') {
    const lines = mdText(first).split('\n');
    if (lines.every((l) => /^(Date|Status):\s*\S/.test(l))) {
      for (const line of lines) {
        const [, key, value] = /^(Date|Status):\s*(.+)$/.exec(line)!;
        state.meta[key.toLowerCase() as 'date' | 'status'] = value.trim();
      }
      children.splice(children.indexOf(first), 1);
    }
  }

  // A runbook's brief: the leading paragraphs that open with a bold label.
  let briefEnd = 0;
  while (children[briefEnd]?.type === 'paragraph' && children[briefEnd].children[0]?.type === 'strong') briefEnd++;
  if (briefEnd > 0) {
    const paragraphs = children.splice(0, briefEnd);
    state.summary = mdText(paragraphs[0]).replace(/^[^:]+:\s*/, '');
    // "**Symptom:** …" reads as a field label on the page; a bold phrase that is part of the
    // sentence ("**The goal of the first fifteen minutes** is …") stays inline.
    for (const p of paragraphs) {
      const label = p.children[0];
      const tail = label.children?.[label.children.length - 1];
      if (tail?.type === 'text' && /:\s*$/.test(tail.value)) {
        tail.value = tail.value.replace(/:\s*$/, '');
        label.data = { hProperties: { className: ['brief-label'] } };
      }
    }
    const brief = { type: 'brief', children: paragraphs, data: { hName: 'div', hProperties: { className: ['brief'] } } };
    if (children[0]?.type === 'thematicBreak') children.shift();
    children.unshift(brief);
  } else if (children[0]?.type === 'paragraph') {
    children[0].data = { hProperties: { className: ['lede'] } };
    state.lede = mdText(children[0]);
    state.summary = state.lede;
  }

  // Headings: GitHub's slugs, and a sheet index on each H2.
  const slugger = new GithubSlugger();
  if (rawTitle) slugger.slug(rawTitle);
  const h2s = children.filter((n) => n.type === 'heading' && n.depth === 2);
  const numbered = h2s.some((n) => /^\d+\.\s/.test(mdText(n)));
  let counter = 0;
  visit(tree, 'heading', (node: Node) => {
    const original = mdText(node);
    const id = slugger.slug(original);
    let label = original;
    let index: string | undefined;
    if (node.depth === 2) {
      const m = /^(\d+)\.\s+/.exec(original);
      if (m) {
        index = m[1].padStart(2, '0');
        const lead = node.children[0];
        if (lead?.type === 'text') lead.value = lead.value.replace(/^\d+\.\s+/, '');
        label = original.slice(m[0].length);
      } else if (!numbered) {
        index = String(++counter).padStart(2, '0');
      }
    }
    node.data = { hProperties: { id, ...(index ? { dataIndex: index } : {}) } };
    if (node.depth <= 3) state.headings.push({ depth: node.depth, id, text: label, index });
  });

  // Links, and code spans that name a path.
  visit(tree, (node: Node, index: number | undefined, parent: Node) => {
    if (node.type === 'link') {
      const { href, external } = resolveHref(node.url, state.source);
      node.url = href;
      if (external) node.data = { hProperties: { className: ['ext'], rel: 'noopener' } };
      return;
    }
    if (node.type === 'inlineCode' && parent && parent.type !== 'link' && index !== undefined) {
      const target = codePathHref(node.value, state.source);
      if (!target) return;
      parent.children[index] = {
        type: 'link',
        url: target.href,
        children: [node],
        data: { hProperties: { className: target.external ? ['code-link', 'ext'] : ['code-link'], ...(target.external ? { rel: 'noopener' } : {}) } },
      };
      return SKIP;
    }
    if (node.type === 'text' && parent && parent.type !== 'link' && parent.type !== 'heading' && index !== undefined) {
      const pattern = /\bADR (\d{4})\b/g;
      if (!pattern.test(node.value)) return;
      pattern.lastIndex = 0;
      const parts: Node[] = [];
      let last = 0;
      for (const m of node.value.matchAll(pattern)) {
        const route = adrRoute(m[1]);
        if (!route) continue;
        if (m.index! > last) parts.push({ type: 'text', value: node.value.slice(last, m.index) });
        parts.push({ type: 'link', url: withBase(route), children: [{ type: 'text', value: m[0] }] });
        last = m.index! + m[0].length;
      }
      if (parts.length === 0) return;
      if (last < node.value.length) parts.push({ type: 'text', value: node.value.slice(last) });
      parent.children.splice(index, 1, ...parts);
      return [SKIP, index + parts.length];
    }
  });

  // Diagrams, and quotations that are really notes.
  visit(tree, (node: Node, index: number | undefined, parent: Node) => {
    if (node.type === 'code' && node.lang === 'mermaid' && parent && index !== undefined) {
      parent.children[index] = {
        type: 'mermaid',
        data: {
          hName: 'figure',
          hProperties: { className: ['diagram', 'diagram-mermaid'] },
          hChildren: [el('pre', { className: ['mermaid-source'] }, [text(node.value)])],
        },
      };
      return SKIP;
    }
    if (node.type === 'blockquote') node.data = { hProperties: { className: ['callout'] } };
  });
}

function transformHtml(tree: Node, state: State, hl: Highlighter): void {
  visit(tree, 'element', (node: Node, index: number | undefined, parent: Node) => {
    if (!parent || index === undefined) return;

    if (node.tagName === 'pre' && node.children[0]?.tagName === 'code') {
      const code = node.children[0];
      const className: string[] = code.properties.className ?? [];
      const declared = className.find((c) => c.startsWith('language-'))?.slice('language-'.length);
      const lang = declared && declared in bundledLanguages && LANGS.includes(declared) ? declared : 'text';
      const source = hastText(code).replace(/\n$/, '');
      const highlighted = hl.codeToHast(source, {
        lang,
        themes: { dark: 'blueprint-dark', light: 'blueprint-light' },
        defaultColor: false,
      });
      const pre = highlighted.children[0] as Node;
      pre.properties.tabIndex = 0;
      parent.children[index] = el('figure', { className: ['code'] }, [
        el('div', { className: ['code-head'] }, [
          el('span', { className: ['code-lang'] }, [text(LANG_LABELS[declared ?? 'text'] ?? declared ?? 'text')]),
          el('button', { type: 'button', className: ['code-copy'], dataCopy: '' }, [text('Copy')]),
        ]),
        pre,
      ]);
      return SKIP;
    }

    if (node.tagName === 'table') {
      parent.children[index] = el('div', { className: ['table-wrap'], tabIndex: 0, role: 'region', ariaLabel: 'Table' }, [node]);
      return SKIP;
    }

    if (/^h[2-4]$/.test(node.tagName) && node.properties.id) {
      const idx = node.properties.dataIndex;
      delete node.properties.dataIndex;
      node.children = [
        ...(idx ? [el('span', { className: ['h-index'], ariaHidden: 'true' }, [text(String(idx))])] : []),
        el('span', { className: ['h-text'] }, node.children),
        el('a', { className: ['h-anchor'], href: `#${node.properties.id}`, ariaLabel: 'Link to this section' }, [text('#')]),
      ];
      return SKIP;
    }
  });

  // Plain-text sections, for search.
  let current: Section | undefined = { id: '', heading: state.title, text: '' };
  for (const node of tree.children as Node[]) {
    if (node.type !== 'element') continue;
    if (/^h[23]$/.test(node.tagName)) {
      if (current) state.sections.push(current);
      const label = node.children.find((c: Node) => c.properties?.className?.includes('h-text'));
      current = { id: node.properties.id, heading: hastText(label ?? node), text: '' };
    } else if (current && current.text.length < 400 && !['figure', 'pre'].includes(node.tagName)) {
      current.text = `${current.text} ${hastText(node)}`.replace(/\s+/g, ' ').trim().slice(0, 400);
    }
  }
  if (current) state.sections.push(current);
}

/** A highlighted code figure for pages written for the site, identical to the markdown ones. */
export async function highlightCode(code: string, lang = 'bash'): Promise<string> {
  const hl = await highlighter();
  const pre = hl
    .codeToHtml(code.replace(/^\n+|\n+$/g, ''), {
      lang: LANGS.includes(lang) ? lang : 'text',
      themes: { dark: 'blueprint-dark', light: 'blueprint-light' },
      defaultColor: false,
    })
    .replace('<pre ', '<pre tabindex="0" ');
  const label = LANG_LABELS[lang] ?? lang;
  return `<figure class="code"><div class="code-head"><span class="code-lang">${label}</span><button type="button" class="code-copy" data-copy>Copy</button></div>${pre}</figure>`;
}

export async function renderMarkdown(source: string): Promise<RenderedDoc> {
  const markdown = readRepoFile(source);
  const hl = await highlighter();
  const state: State = { source, title: '', kind: 'doc', meta: {}, lede: '', summary: '', headings: [], sections: [] };

  const file = await unified()
    .use(remarkParse)
    .use(remarkGfm)
    .use(() => (tree: Node) => transformMarkdown(tree, state))
    .use(remarkRehype)
    .use(() => (tree: Node) => transformHtml(tree, state, hl))
    .use(rehypeStringify)
    .process(markdown);

  const words = markdown.split(/\s+/).length;
  return {
    source,
    title: state.title,
    kind: state.kind,
    meta: state.meta,
    lede: state.lede,
    summary: state.summary,
    headings: state.headings,
    sections: state.sections.filter((s) => s.text || s.id),
    html: String(file),
    minutes: Math.max(1, Math.round(words / 230)),
  };
}
