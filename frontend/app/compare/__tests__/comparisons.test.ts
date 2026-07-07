import { describe, it, expect } from 'vitest';
import { COMPARISONS, getComparison } from '../_lib/comparisons';

// The /compare pages are a primary SEO surface: these tests pin the invariants
// that search engines and the sitemap depend on.
describe('comparisons content source', () => {
  it('has unique, URL-safe slugs that end in "-alternative" (the target keyword shape)', () => {
    const slugs = COMPARISONS.map((c) => c.slug);
    expect(new Set(slugs).size).toBe(slugs.length);
    for (const slug of slugs) {
      expect(slug).toMatch(/^[a-z0-9-]+-alternative$/);
    }
  });

  it('covers the three competitors the landing page links to', () => {
    expect(getComparison('n8n-alternative')?.competitor).toBe('n8n');
    expect(getComparison('zapier-alternative')?.competitor).toBe('Zapier');
    expect(getComparison('make-alternative')?.competitor).toBe('Make');
    expect(getComparison('unknown')).toBeUndefined();
  });

  it('every comparison is complete: rows, reasons, honest section, migration steps and FAQ', () => {
    for (const c of COMPARISONS) {
      expect(c.rows.length).toBeGreaterThanOrEqual(6);
      expect(c.reasons.length).toBeGreaterThanOrEqual(3);
      expect(c.honest.length).toBeGreaterThanOrEqual(2);
      expect(c.migration.length).toBe(3);
      expect(c.faq.length).toBeGreaterThanOrEqual(4);
      expect(c.lastUpdated).toBeTruthy();
      expect(c.metaDescription.length).toBeLessThanOrEqual(160);
    }
  });

  it('contains no em-dash or en-dash anywhere (project-wide writing rule)', () => {
    const serialized = JSON.stringify(COMPARISONS);
    expect(serialized).not.toMatch(/[--]/);
  });

  it('FAQ answers are self-contained (long enough to be quoted in isolation)', () => {
    for (const c of COMPARISONS) {
      for (const item of c.faq) {
        expect(item.question.endsWith('?') || item.question.includes('?')).toBe(true);
        expect(item.answer.length).toBeGreaterThan(80);
      }
    }
  });
});
