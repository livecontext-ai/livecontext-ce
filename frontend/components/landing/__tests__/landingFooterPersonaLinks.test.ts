import { readFileSync } from 'node:fs';
import path from 'node:path';
import { describe, expect, it } from 'vitest';
import { PERSONA_FOOTER_LABELS, PERSONA_KEYS, personaHref } from '@/components/landing/personas/personas';
import { DEFAULT_SHELL_LABELS } from '@/components/landing/LandingShell';

/**
 * The footer's "Use cases" row is the ONLY site-wide entry point to the persona pages.
 *
 * <p>Their other internal links are the hero pills, which exist on the home page and on
 * the persona pages themselves. Nothing on /integrations (977 pages), /models, /compare,
 * /marketplace or the docs pointed at them, so six pages carrying the product's clearest
 * commercial copy hung off one page's worth of links. Deleting this row would pass every
 * other test in the repo.
 *
 * <p>Source-level, like `landingFooterVideosLink.test.ts` and its siblings: the footer
 * renders on public pages that have no intl context, so it is not mounted in a test.
 */
const shellSrc = readFileSync(path.resolve(__dirname, '../LandingShell.tsx'), 'utf8');

/** Just the Use cases row, so a match cannot come from a column above it. */
const personaRow = (() => {
  const start = shellSrc.indexOf('aria-label={labels.personasHeading}');
  expect(start).toBeGreaterThan(-1);
  const end = shellSrc.indexOf('</nav>', start);
  expect(end).toBeGreaterThan(start);
  return shellSrc.slice(start, end);
})();

describe('landing footer use-case row', () => {
  it('links every persona page from the chrome of every public page', () => {
    expect(personaRow).toContain('PERSONA_KEYS.map');
    expect(personaRow).toContain('personaHref(persona, labels.locale)');
    expect(personaRow).toContain('labels.personas[persona]');
  });

  it('routes the links through withBase, so they resolve from the docs host too', () => {
    // A literal href="/for/ops" would 404 when the chrome renders on docs.livecontext.ai.
    expect(personaRow).toContain('withBase(siteBaseUrl, personaHref(persona, labels.locale))');
    expect(personaRow).not.toMatch(/href="\/for\//);
  });

  it('names a destination rather than a category, for every persona', () => {
    // The label IS the anchor text, which is most of what an internal link is worth. A
    // persona added without one would render `undefined` in the footer of every page.
    for (const persona of PERSONA_KEYS) {
      const label = PERSONA_FOOTER_LABELS[persona];
      expect(label).toBeTruthy();
      expect(label.split(' ').length).toBeGreaterThan(1);
      expect(personaHref(persona)).toBe(`/for/${persona}`);
    }
    expect(new Set(Object.values(PERSONA_FOOTER_LABELS)).size).toBe(PERSONA_KEYS.length);
  });

  it('stays free of intl context, like the rest of the chrome', () => {
    // The footer renders on pages OUTSIDE the [locale] tree, where a translator call throws
    // and takes the page into the error boundary. That is why the copy arrives as a PROP with
    // an English default rather than being read here: the localised pages hand over their own
    // labels (shellLabels.ts), the others pass nothing and keep the English.
    expect(shellSrc).not.toMatch(/useTranslations\(/);
    expect(shellSrc).not.toMatch(/getTranslations\(/);
    expect(personaRow).toContain('labels.personas[persona]');
  });

  it('keeps an English default for every persona, for the pages that hand over nothing', () => {
    // /docs, /legal and /marketplace render this same footer with no labels at all. If the
    // defaults lost the persona names, that row would read "undefined" six times there while
    // every localised page still looked correct.
    //
    // Asserted per key against the six personas rather than by comparing the two objects:
    // `DEFAULT_SHELL_LABELS.personas` IS `PERSONA_FOOTER_LABELS`, so `toEqual` between them
    // compared a value with itself and could not fail.
    for (const persona of PERSONA_KEYS) {
      const label = DEFAULT_SHELL_LABELS.personas[persona];
      expect(label, `no English default for ${persona}`).toBeTruthy();
      expect(label.split(' ').length, `${persona} needs a destination, not a category`).toBeGreaterThan(1);
    }
    expect(Object.keys(DEFAULT_SHELL_LABELS.personas).sort()).toEqual([...PERSONA_KEYS].sort());
  });
});
