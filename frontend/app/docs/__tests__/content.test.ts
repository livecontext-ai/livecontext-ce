import { describe, it, expect } from 'vitest';
import { readFileSync, readdirSync, existsSync, statSync } from 'fs';
import path from 'path';
import { DOCS_PAGES } from '../_nav';
import { slugify } from '../_components/DocsToc';

// Source-level guards for the writing rules in app/the project docs, applied to every
// page in the IA. They catch the regressions a reviewer misses most easily.

const DOCS_DIR = path.resolve(__dirname, '..');
const fileFor = (href: string) => path.join(DOCS_DIR, href === '/' ? 'page.tsx' : `${href.slice(1)}/page.tsx`);
const pages = DOCS_PAGES.map((p) => ({ ...p, src: readFileSync(fileFor(p.href), 'utf8') }));
const byHref = new Map(pages.map((p) => [p.href, p]));
const cases = pages.map((p) => [p.href, p] as const);

/** Pure reference or map pages: nothing to configure, so nothing to troubleshoot. */
const NO_TROUBLESHOOTING = new Set(['/', '/glossary', '/concepts', '/nodes', '/workspace']);

/** Every anchor a page exposes: explicit ids, plus the slug DocsToc gives each h2/h3. */
function anchorsOf(src: string): Set<string> {
  const ids = Array.from(src.matchAll(/\bid="([^"]+)"/g), (m) => m[1]);
  const seen = new Map<string, number>();
  // Mirrors DocsToc: a repeated heading gets -1, -2, ... after its slug.
  const dedupe = (slug: string) => {
    const n = seen.get(slug) ?? 0;
    seen.set(slug, n + 1);
    return n === 0 ? slug : `${slug}-${n}`;
  };
  const headings = Array.from(src.matchAll(/<h[23](?![^>]*\bid=)[^>]*>([\s\S]*?)<\/h[23]>/g), (m) =>
    dedupe(slugify(
      m[1]
        .replace(/\{' '\}/g, ' ')
        .replace(/<[^>]+>/g, '')
        .replace(/&amp;/g, '&')
        .replace(/&apos;/g, "'")
        .replace(/\s+/g, ' ')
        .trim(),
    )),
  );
  return new Set([...ids, ...headings]);
}

/** Each <DocsTable ...> of a page: its head literal and whether it has a caption. */
function tablesOf(src: string) {
  return src
    .split('<DocsTable')
    .slice(1)
    .map((chunk) => ({
      head: chunk.match(/head=\{(\[[^\]]*\])\}/)?.[1]?.replace(/\s+/g, ' ') ?? '',
      // Props before `rows=` only: a caption can never hide inside a JSX cell.
      captioned: /\bcaption=/.test(chunk.split('rows={')[0]),
    }));
}

function filesUnder(dir: string): string[] {
  return readdirSync(dir).flatMap((name) => {
    const full = path.join(dir, name);
    if (statSync(full).isDirectory()) return name === '__tests__' ? [] : filesUnder(full);
    return /\.(tsx?|md)$/.test(name) ? [full] : [];
  });
}

describe('docs pages follow the writing rules', () => {
  it('no em-dash or en-dash anywhere in the docs tree (pages, nav, components, README)', () => {
    for (const file of filesUnder(DOCS_DIR)) {
      expect(readFileSync(file, 'utf8'), path.relative(DOCS_DIR, file)).not.toMatch(/[--]/);
    }
  });

  it.each(cases)('%s links only to docs pages and anchors that exist', (_href, page) => {
    for (const m of page.src.matchAll(/href="(\/[a-z0-9-]*)?(?:#([^"]+))?"/g)) {
      const [, target, anchor] = m;
      if (target === undefined && anchor === undefined) continue;
      const targetHref = target ?? page.href;
      expect(existsSync(fileFor(targetHref)), `${page.href} links to ${targetHref}, which has no page`).toBe(true);
      if (anchor) {
        const targetSrc = byHref.get(targetHref)?.src ?? '';
        expect(anchorsOf(targetSrc).has(anchor), `${page.href} links to ${targetHref}#${anchor}, which does not exist`).toBe(true);
      }
    }
  });

  it.each(cases)('%s uses clean docs paths, never the /docs-prefixed route', (_href, page) => {
    // On docs.livecontext.ai the pages are served at /agents; /docs/agents only redirects.
    expect(page.src).not.toMatch(/href=["'{`]*\/docs[/"'`]/);
  });

  it.each(cases)('%s shows its nav section as eyebrow and its nav title as heading', (_href, page) => {
    const eyebrow = page.src.match(/eyebrow="([^"]+)"/)?.[1];
    const title = page.src.match(/<DocsHero[\s\S]*?\btitle="([^"]+)"/)?.[1];
    expect(eyebrow, `${page.href} eyebrow`).toBe(page.section);
    expect(title, `${page.href} title`).toBe(page.title);
  });

  it.each(cases)('%s uses only h2 and h3 inside the article', (_href, page) => {
    // Code examples (template literals) may legitimately show HTML headings.
    const markup = page.src.replace(/`[^`]*`/g, '');
    expect(markup).not.toMatch(/<h1[\s>]/);
    expect(markup).not.toMatch(/<h4[\s>]/);
  });

  it.each(cases)('%s ends with a Related pages section (the Overview has its full map instead)', (href, page) => {
    if (href === '/') return;
    expect(page.src).toMatch(/<h2[^>]*>Related pages<\/h2>/);
  });

  it.each(cases)('%s has a Troubleshooting section unless it is a pure reference or map page', (href, page) => {
    if (NO_TROUBLESHOOTING.has(href)) return;
    // "Troubleshooting" or a more specific "Troubleshooting: why did my agent stop?".
    expect(page.src).toMatch(/<h2[^>]*>Troubleshooting\b/);
  });

  it.each(cases)('%s gives every table a caption (its accessible name)', (_href, page) => {
    for (const t of tablesOf(page.src)) {
      expect(t.captioned, `${page.href}: the table with head ${t.head} has no caption`).toBe(true);
    }
  });

  it.each(cases)('%s gives tables on the same page distinct captions', (_href, page) => {
    const captions = Array.from(page.src.matchAll(/\bcaption="([^"]+)"/g), (m) => m[1]);
    expect(new Set(captions).size, `${page.href} repeats a caption`).toBe(captions.length);
  });
});

describe('docs pages agree with each other', () => {
  it('has no orphan page: every page folder is in the navigation', () => {
    const inNav = new Set(DOCS_PAGES.map((p) => p.href));
    const folders = readdirSync(DOCS_DIR).filter(
      (name) => !name.startsWith('_') && name !== '__tests__' && existsSync(path.join(DOCS_DIR, name, 'page.tsx')),
    );
    for (const folder of folders) expect(inNav.has(`/${folder}`), `app/docs/${folder} is not in _nav.ts`).toBe(true);
  });

  it('keeps the own-key fee table on the billing page only (other pages link to it)', () => {
    const hasFeeTable = (src: string) => /Mid tier/.test(src) && /Top tier/.test(src);
    // If billing ever renames its bands this fails loudly instead of silently guarding nothing.
    expect(hasFeeTable(byHref.get('/billing')!.src)).toBe(true);
    expect(anchorsOf(byHref.get('/billing')!.src).has('own-key-fee')).toBe(true);
    for (const page of pages) {
      if (page.href !== '/billing') expect(hasFeeTable(page.src), `${page.href} repeats the own-key fee table`).toBe(false);
    }
  });

  it('lists all six production-gated triggers wherever a page enumerates them', () => {
    // The sentence that DEFINES production triggers ("Production triggers (A, B, ...)") must list
    // all six. Other lists (for example which triggers a pin re-syncs) legitimately differ.
    const lists = pages.flatMap((page) =>
      Array.from(page.src.matchAll(/Production triggers\s*\(([^)]*)\)/g), (m) => ({ href: page.href, list: m[1] })),
    );
    expect(lists.length).toBeGreaterThan(0);
    for (const { href, list } of lists) {
      for (const name of ['Webhook', 'Scheduler', 'Tables', 'Chat', 'Form', 'Workflows']) {
        expect(list, `${href}: production trigger list misses ${name}`).toContain(name);
      }
    }
  });

  it('states the same Enterprise storage on billing and files', () => {
    expect(byHref.get('/billing')!.src).toContain('500 GB to 5 TB, by Enterprise tier');
    expect(byHref.get('/files')!.src).toMatch(/Enterprise Basic', '500 GB'[\s\S]*Enterprise Ultimate', '5 TB'/);
  });

  it('never promises that an acquired publication is editable as installed (it is run-only)', () => {
    for (const page of pages) {
      if (/Acquir\w* a publication/.test(page.src)) {
        expect(page.src, `${page.href} describes acquiring without saying it is run-only`).toMatch(/run-only/);
      }
    }
  });

  it('states the same Enterprise member caps on billing and organizations', () => {
    // Organizations lists each Enterprise tier; billing summarises them as a range.
    expect(byHref.get('/organizations')!.src).toMatch(/Enterprise Basic', '25'[\s\S]*Enterprise Ultimate', '500'/);
    expect(byHref.get('/billing')!.src).toContain('25 to 500, by Enterprise tier');
  });

  it.each(cases)('%s names no <section>, so content groups never flood the landmark list', (_href, page) => {
    expect(page.src).not.toMatch(/<section[^>]*aria-label/);
  });
});

describe('anchorsOf (the anchor resolver this suite relies on)', () => {
  it('finds explicit ids and the slugs DocsToc generates for headings', () => {
    const anchors = anchorsOf('<h2>Versions and production</h2><h3 id="errors">Error codes</h3><h2>Loops &amp; ports</h2>');
    expect(anchors.has('versions-and-production')).toBe(true);
    expect(anchors.has('errors')).toBe(true);
    expect(anchors.has('loops-ports')).toBe(true);
    expect(anchors.has('error-codes')).toBe(false);
    // A repeated heading is reachable as slug-1, as DocsToc names it.
    const twice = anchorsOf('<h2>Examples</h2><h2>Examples</h2>');
    expect(twice.has('examples')).toBe(true);
    expect(twice.has('examples-1')).toBe(true);
  });
});
