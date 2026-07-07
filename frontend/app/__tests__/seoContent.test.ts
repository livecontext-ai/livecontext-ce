import { readFileSync, readdirSync, statSync } from 'node:fs';
import { join } from 'node:path';
import { describe, expect, it } from 'vitest';
import { DOCS_PAGES } from '../docs/_nav';
import { COMPARISONS } from '../compare/_lib/comparisons';

// llms.txt is the GEO entry point for LLM crawlers; the landing page carries
// the FAQ + JSON-LD copy. Both are plain text the writing rules apply to.

const llmsTxt = readFileSync(join(__dirname, '../../public/llms.txt'), 'utf8');
const landingSource = readFileSync(join(__dirname, '../[locale]/page.tsx'), 'utf8');

describe('llms.txt', () => {
  it('contains no em-dash or en-dash (project-wide writing rule)', () => {
    expect(llmsTxt).not.toMatch(/[--]/);
  });

  it('only links docs pages that exist in the docs IA (no dead GEO links)', () => {
    const docsUrls = [...llmsTxt.matchAll(/https:\/\/docs\.livecontext\.ai([^\s):]*)/g)].map((m) => m[1] || '/');
    expect(docsUrls.length).toBeGreaterThan(0);
    const known = new Set(DOCS_PAGES.map((p) => p.href));
    for (const path of docsUrls) {
      expect(known.has(path), `llms.txt links unknown docs page: ${path}`).toBe(true);
    }
  });

  it('links every live comparison page', () => {
    for (const comparison of COMPARISONS) {
      expect(llmsTxt).toContain(`https://livecontext.ai/compare/${comparison.slug}`);
    }
  });
});

describe('landing page copy', () => {
  it('contains no em-dash or en-dash (FAQ + JSON-LD text ships to crawlers verbatim)', () => {
    expect(landingSource).not.toMatch(/[--]/);
  });
});

// React 19 only renders <style> content when it is a SINGLE string child.
// `<style>{a}{b}</style>` (two expression children) emits an EMPTY style tag
// server-side and the full text client-side: a hydration mismatch (React
// #418) on every page plus unstyled server HTML. Concatenate instead:
// `<style>{a + b}</style>`. Same rule for <script> and <title>.
describe('no multi-child <style> tags (SSR-empty + hydration mismatch)', () => {
  const roots = [join(__dirname, '..'), join(__dirname, '../../components')];

  function collectTsx(dir: string, out: string[]): string[] {
    for (const entry of readdirSync(dir)) {
      if (entry === 'node_modules' || entry.startsWith('.')) continue;
      const full = join(dir, entry);
      if (statSync(full).isDirectory()) collectTsx(full, out);
      else if (entry.endsWith('.tsx')) out.push(full);
    }
    return out;
  }

  it('no .tsx file renders <style> with more than one expression child', () => {
    const offenders: string[] = [];
    for (const root of roots) {
      for (const file of collectTsx(root, [])) {
        if (/<style[^>]*>\{[^}]*\}\{/.test(readFileSync(file, 'utf8'))) {
          offenders.push(file);
        }
      }
    }
    expect(offenders, offenders.join('\n')).toEqual([]);
  });
});
