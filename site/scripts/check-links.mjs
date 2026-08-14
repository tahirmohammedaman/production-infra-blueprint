#!/usr/bin/env node
// Every internal link in the built site must land on a page that exists, and every #fragment on
// an element with that id. The markdown pipeline already refuses a relative link to a missing
// file; this catches the rest — a renamed heading an anchor still points at, a route typed into a
// page by hand, a nav entry for a page nobody wrote.
//
// usage: node scripts/check-links.mjs <dist directory>

import fs from 'node:fs';
import path from 'node:path';

const dist = path.resolve(process.argv[2] ?? 'dist');
const base = (process.env.BASE_PATH || '/').replace(/\/?$/, '/');

function htmlFiles(dir) {
  return fs.readdirSync(dir, { withFileTypes: true }).flatMap((entry) => {
    const full = path.join(dir, entry.name);
    if (entry.isDirectory()) return htmlFiles(full);
    return entry.name.endsWith('.html') ? [full] : [];
  });
}

const decode = (s) => s.replace(/&amp;/g, '&').replace(/&#x27;|&#39;/g, "'").replace(/&quot;/g, '"');

const pages = new Map();
for (const file of htmlFiles(dist)) {
  const html = fs.readFileSync(file, 'utf8');
  const ids = new Set([...html.matchAll(/\sid="([^"]+)"/g)].map((m) => decode(m[1])));
  const hrefs = [...html.matchAll(/<a\s[^>]*?href="([^"]+)"/g)].map((m) => decode(m[1]));
  pages.set(file, { ids, hrefs });
}

function targetFile(pathname) {
  const relative = decodeURIComponent(pathname.slice(base.length));
  const candidates = [path.join(dist, relative, 'index.html'), path.join(dist, relative)];
  return candidates.find((c) => pages.has(c) || (fs.existsSync(c) && fs.statSync(c).isFile()));
}

const broken = [];
let checked = 0;
for (const [file, { hrefs }] of pages) {
  for (const href of hrefs) {
    if (/^[a-z][a-z0-9+.-]*:/i.test(href) || href.startsWith('//')) continue;
    checked++;
    const [pathname, fragment] = href.split('#');
    const target = pathname === '' ? file : pathname.startsWith(base) ? targetFile(pathname) : undefined;
    const where = path.relative(dist, file);
    if (!target) {
      broken.push(`${where}: ${href} — no such page`);
    } else if (fragment && pages.has(target) && !pages.get(target).ids.has(decodeURIComponent(fragment))) {
      broken.push(`${where}: ${href} — no element with id "${fragment}"`);
    }
  }
}

if (broken.length > 0) {
  console.error(`check-links: ${broken.length} broken of ${checked} internal links\n`);
  for (const line of broken) console.error(`  ${line}`);
  process.exit(1);
}
console.log(`check-links: ${checked} internal links across ${pages.size} pages, none broken`);
