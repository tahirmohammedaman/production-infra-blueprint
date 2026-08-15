// Reference pages that are read out of the repository's own configuration at build time: the
// Makefile's targets, the Prometheus alert rules, and the semgrep rules. Edit those files and
// these pages follow; there is no second copy to forget.

import { parse } from 'yaml';
import { readRepoFile } from './paths';

// ---------------------------------------------------------------- Makefile

export interface MakeTarget {
  name: string;
  description: string;
}

export interface MakeSection {
  title: string;
  targets: MakeTarget[];
}

/** Targets documented with `## `, grouped under the Makefile's `# ---- section` banners. */
export function makeTargets(): MakeSection[] {
  const sections: MakeSection[] = [];
  let current: MakeSection = { title: 'General', targets: [] };
  for (const line of readRepoFile('Makefile').split('\n')) {
    const banner = /^#\s*-{4,}\s*(.+?)\s*$/.exec(line);
    if (banner) {
      if (current.targets.length) sections.push(current);
      current = { title: banner[1].replace(/\s*\/\s*/g, ' / '), targets: [] };
      continue;
    }
    const target = /^([a-zA-Z0-9_.%-]+):.*?##\s*(.+)$/.exec(line);
    if (target) current.targets.push({ name: target[1].replace('%', '<name>'), description: target[2] });
  }
  if (current.targets.length) sections.push(current);
  return sections;
}

// ---------------------------------------------------------------- alerts

export interface AlertRule {
  name: string;
  group: string;
  file: string;
  severity: string;
  service: string;
  for: string;
  summary: string;
  description: string;
  runbookAnchor: string;
}

interface RuleFile {
  groups: { name: string; rules: Array<Record<string, any>> }[];
}

export function alertRules(): AlertRule[] {
  const files = ['observability/prometheus/rules/slo.yml', 'observability/prometheus/rules/alerts.yml'];
  const alerts: AlertRule[] = [];
  for (const file of files) {
    const doc = parse(readRepoFile(file)) as RuleFile;
    for (const group of doc.groups) {
      for (const rule of group.rules) {
        if (!rule.alert) continue;
        const runbook: string = rule.annotations?.runbook_url ?? '';
        const existing = alerts.find((a) => a.name === rule.alert);
        // The burn-rate alerts are written once per window pair; list each name once.
        if (existing) continue;
        alerts.push({
          name: rule.alert,
          group: group.name,
          file,
          severity: rule.labels?.severity ?? '',
          service: rule.labels?.service ?? '',
          for: rule.for ?? '—',
          summary: rule.annotations?.summary ?? '',
          description: (rule.annotations?.description ?? '').replace(/\s+/g, ' ').trim(),
          runbookAnchor: runbook.includes('#') ? runbook.split('#')[1] : '',
        });
      }
    }
  }
  return alerts;
}

// ---------------------------------------------------------------- semgrep

export interface SemgrepRule {
  id: string;
  severity: string;
  languages: string[];
  message: string;
  paths: string[];
  patterns: string[];
}

function collectPatterns(rule: Record<string, any>): string[] {
  const out: string[] = [];
  const walk = (value: unknown, key?: string) => {
    if (typeof value === 'string' && key && /^pattern(-regex|-not-inside|-inside)?$/.test(key)) {
      out.push(`${key}: ${value.trim()}`);
    } else if (Array.isArray(value)) {
      value.forEach((v) => walk(v));
    } else if (value && typeof value === 'object') {
      for (const [k, v] of Object.entries(value)) {
        if (k === 'metavariable-regex') {
          const mv = v as { metavariable: string; regex: string };
          out.push(`metavariable-regex: ${mv.metavariable} =~ ${mv.regex}`);
        } else walk(v, k);
      }
    }
  };
  walk({ pattern: rule.pattern, 'pattern-regex': rule['pattern-regex'], patterns: rule.patterns, 'pattern-either': rule['pattern-either'] });
  return out;
}

export function semgrepRules(): { preamble: string; rules: SemgrepRule[] } {
  const source = readRepoFile('.semgrep/blueprint.yml');
  const preamble = source
    .split('\n')
    .filter((l, i, all) => l.startsWith('#') && all.slice(0, i).every((p) => p.startsWith('#') || p.trim() === ''))
    .map((l) => l.replace(/^#\s?/, ''))
    .join(' ')
    .replace(/\s+/g, ' ')
    .trim();
  const doc = parse(source) as { rules: Array<Record<string, any>> };
  return {
    preamble,
    rules: doc.rules.map((rule) => ({
      id: rule.id,
      severity: rule.severity,
      languages: rule.languages ?? [],
      message: String(rule.message ?? '').replace(/\s+/g, ' ').trim(),
      paths: rule.paths?.include ?? [],
      patterns: collectPatterns(rule),
    })),
  };
}
