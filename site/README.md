# Documentation site

A static site for this repository, rendered from the repository's own files. Nothing documented
here has a second copy: the site reads `docs/`, the component READMEs, the Makefile, the alert
rules and the semgrep rules when it is built, so editing any of them changes the site and nothing
in `site/` has to change with it.

```bash
make site       # npm ci, build into site/dist, fail on any broken internal link
make site-dev   # live reload at http://localhost:4321, including edits to docs/
```

Needs Node 22.12 or newer (`.nvmrc` pins the major version CI uses).

## Where each page comes from

| Pages | Source | How |
| --- | --- | --- |
| Architecture, Security, Operations, Cost analysis, SLOs | `docs/*.md` | rendered as written |
| Decision records, Runbooks | `docs/adr/`, `docs/runbooks/` | every file in the directory, discovered at build time |
| Terraform, Ansible, Kubernetes manifests, Observability stack | the READMEs beside the code | rendered as written |
| Alert catalog | `observability/prometheus/rules/*.yml` | parsed: name, severity, `for`, summary, runbook anchor |
| Make targets | `Makefile` | every target with a `##` description, under its section banner |
| Enforced invariants | `.semgrep/blueprint.yml` | parsed: id, message, paths, patterns |
| Introduction, Quickstart, Delivery pipeline, Measurements, Findings, Build record | `src/pages/` | written for the site; every figure comes from, and links to, a document above |

The markdown in `docs/` is written for GitHub first and knows nothing about this site. The
pipeline in `src/lib/markdown.ts` does the translation:

- relative links become site routes; a link to a file the site does not render goes to that file
  on GitHub; a link to a file that does not exist **fails the build**
- an inline `path/in/the/repo` that exists becomes a link, and so does "ADR 0007"
- heading ids use GitHub's slugs, so every anchor written for GitHub still resolves
- code is highlighted at build time; mermaid diagrams are drawn in the browser, in the site's palette
- an ADR's `Date:` and `Status:` lines, and a runbook's opening `**Symptom:**` paragraphs, become
  page metadata

After the build, `scripts/check-links.mjs` checks every internal `href` and `#fragment` in the
output. CI runs the same build on any change to the files above.

## Adding a document

A new ADR or runbook needs nothing: drop the file into its directory. Any other new markdown page
needs one entry in `src/config.ts`, which is also the sidebar.

## Hosting

The output in `dist/` is plain static files with no runtime requests: fonts are bundled, and there
is no analytics or external script. Any static host works.

```bash
SITE_URL=https://docs.example.com npm run build              # at the root of a domain
SITE_URL=https://example.com BASE_PATH=/blueprint/ npm run build   # under a path
```

`SITE_URL` sets canonical URLs; `BASE_PATH` prefixes every link, and the link check honours it.
Build from a full checkout of the repository, not `site/` alone — the site reads the directories
around it.

## Dependencies

Pinned exactly in `package.json` and locked in `package-lock.json`. Renovate proposes upgrades like
it does for everything else.
