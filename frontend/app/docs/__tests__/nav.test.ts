import { describe, it, expect } from 'vitest';
import { DOCS_NAV, DOCS_PAGES, getAdjacentPages, isActiveDocPath } from '../_nav';

describe('docs IA - DOCS_NAV / DOCS_PAGES', () => {
  it('derives DOCS_PAGES from every linked nav item, excluding roadmap stubs', () => {
    const linkedItems = DOCS_NAV.flatMap((s) => s.items).filter((i) => i.href);
    const stubItems = DOCS_NAV.flatMap((s) => s.items).filter((i) => !i.href);
    expect(DOCS_PAGES).toHaveLength(linkedItems.length);
    // Roadmap stubs (badge, no href) must never leak into the live/sitemap set.
    expect(stubItems.length).toBeGreaterThan(0);
    for (const stub of stubItems) {
      expect(DOCS_PAGES.find((p) => p.title === stub.title)).toBeUndefined();
    }
  });

  it('keeps every live href unique and clean (rooted at the docs subdomain)', () => {
    const hrefs = DOCS_PAGES.map((p) => p.href);
    expect(new Set(hrefs).size).toBe(hrefs.length);
    for (const href of hrefs) {
      expect(href.startsWith('/')).toBe(true);
      // Clean paths: never the /docs-prefixed route form.
      expect(href.startsWith('/docs')).toBe(false);
    }
  });

  it('starts the reading order at the Overview (/)', () => {
    expect(DOCS_PAGES[0].href).toBe('/');
  });

  it('splits AI and Data into separate sections', () => {
    const titles = DOCS_NAV.map((s) => s.title);
    expect(titles).toContain('AI');
    expect(titles).toContain('Data');
    expect(titles).not.toContain('AI & data');
    expect(DOCS_NAV.find((s) => s.title === 'AI')!.items.map((i) => i.href)).toContain('/agents');
    expect(DOCS_NAV.find((s) => s.title === 'Data')!.items.map((i) => i.href)).toContain('/tables');
  });

  it('preserves the nav order when flattening to DOCS_PAGES', () => {
    const flattened = DOCS_NAV.flatMap((s) =>
      s.items.filter((i) => i.href).map((i) => i.href),
    );
    expect(DOCS_PAGES.map((p) => p.href)).toEqual(flattened);
  });

  it('tags each page with its owning section title', () => {
    const build = DOCS_NAV.find((s) => s.title === 'Build')!;
    const workflows = DOCS_PAGES.find((p) => p.href === '/workflows')!;
    expect(workflows.section).toBe(build.title);
  });
});

describe('getAdjacentPages', () => {
  it('returns no previous for the first page', () => {
    const { prev, next } = getAdjacentPages('/');
    expect(prev).toBeNull();
    expect(next?.href).toBe(DOCS_PAGES[1].href);
  });

  it('returns no next for the last page', () => {
    const last = DOCS_PAGES[DOCS_PAGES.length - 1];
    const { prev, next } = getAdjacentPages(last.href);
    expect(next).toBeNull();
    expect(prev?.href).toBe(DOCS_PAGES[DOCS_PAGES.length - 2].href);
  });

  it('returns both neighbours for a middle page', () => {
    const middle = DOCS_PAGES[2];
    const { prev, next } = getAdjacentPages(middle.href);
    expect(prev?.href).toBe(DOCS_PAGES[1].href);
    expect(next?.href).toBe(DOCS_PAGES[3].href);
  });

  it('returns nulls for an unknown path', () => {
    expect(getAdjacentPages('/does-not-exist')).toEqual({ prev: null, next: null });
  });
});

describe('isActiveDocPath', () => {
  it('matches the Overview only on the exact / path', () => {
    expect(isActiveDocPath('/', '/')).toBe(true);
    expect(isActiveDocPath('/agents', '/')).toBe(false);
  });

  it('matches a sub-page exactly and as a parent of deeper paths', () => {
    expect(isActiveDocPath('/agents', '/agents')).toBe(true);
    expect(isActiveDocPath('/agents/budgets', '/agents')).toBe(true);
  });

  it('does not match a sibling whose href is a string prefix', () => {
    // '/agent' must not light up for '/agents' (no false prefix hit).
    expect(isActiveDocPath('/agents', '/agent')).toBe(false);
  });

  it('is false when the pathname is null or undefined', () => {
    expect(isActiveDocPath(null, '/agents')).toBe(false);
    expect(isActiveDocPath(undefined, '/agents')).toBe(false);
  });
});
