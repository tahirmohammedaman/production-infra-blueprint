// The site renders files that live outside it — docs/, the component READMEs, the Makefile,
// the alert rules — so nothing documented here can drift from what the repository says.
// This config only has to make two things true: the build can read the parent directory, and
// the dev server reloads when one of those files changes.

import { fileURLToPath } from 'node:url';
import { defineConfig } from 'astro/config';

const repoRoot = fileURLToPath(new URL('..', import.meta.url));

/** Reload the browser when a source file outside site/ changes. */
function watchRepositorySources() {
  const watched = ['docs', 'deploy/k8s/README.md', 'infra', 'observability', 'Makefile', '.semgrep'];
  return {
    name: 'watch-repository-sources',
    configureServer(server) {
      server.watcher.add(watched.map((p) => repoRoot + p));
      server.watcher.on('change', (file) => {
        if (!file.startsWith(repoRoot + 'site/')) server.ws.send({ type: 'full-reload' });
      });
    },
  };
}

export default defineConfig({
  // Hosted independently of the system it documents. Both are set by whoever deploys it:
  //   SITE_URL=https://docs.example.com BASE_PATH=/ npm run build
  site: process.env.SITE_URL || undefined,
  base: process.env.BASE_PATH || '/',
  trailingSlash: 'always',
  build: { format: 'directory' },
  devToolbar: { enabled: false },
  // Prose in the pages is wrapped across lines next to inline elements; compression joins
  // those lines without the space between them.
  compressHTML: false,
  // Two screenshots, already sized. Nothing to optimise, so no native image dependency.
  image: { service: { entrypoint: 'astro/assets/services/noop' } },
  vite: {
    server: { fs: { allow: [repoRoot] } },
    plugins: [watchRepositorySources()],
  },
});
