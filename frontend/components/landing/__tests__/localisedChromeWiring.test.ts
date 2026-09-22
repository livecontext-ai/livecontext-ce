import { readFileSync } from 'node:fs';
import path from 'node:path';
import { describe, expect, it } from 'vitest';

/**
 * The two pages inside the `[locale]` tree must hand the chrome its translated labels.
 *
 * <p><strong>Without this, deleting one prop silently reverts the bug.</strong> `LandingHeader`
 * and `LandingFooter` default to `DEFAULT_SHELL_LABELS`, which is the English copy: that default
 * is what keeps `/docs`, `/legal` and `/marketplace` rendering correctly, and it is also what
 * makes the omission invisible. Drop `labels={shell}` from either page and nothing throws,
 * nothing fails to typecheck, every other test stays green, and `/fr` goes straight back to a
 * French page wrapped in an English header and footer. That is exactly how it shipped the
 * first time.
 *
 * <p>Source-level because what it pins is the WIRING, which is a fact about these two page
 * files rather than about rendered output. The chrome itself IS mounted elsewhere, in the
 * component tests that hand it labels and read the DOM back: after this change it needs no intl
 * context, so nothing stops it being rendered.
 */
const read = (rel: string) => readFileSync(path.resolve(__dirname, rel), 'utf8');

const SURFACES = [
  { name: 'the home page', src: read('../../../app/[locale]/page.tsx') },
  { name: 'the persona pages', src: read('../personas/PersonaLanding.tsx') },
];

describe('localised surfaces hand their own labels to the chrome', () => {
  for (const { name, src } of SURFACES) {
    it(`${name} builds the labels for its own locale`, () => {
      expect(src).toContain("import { shellLabels } from '@/components/landing/shellLabels';");
      // The locale is the page's, not a constant: a hardcoded one would translate every
      // language into the same chrome, which is the same bug wearing a different hat.
      expect(src).toContain('await shellLabels(locale)');
    });

    it(`${name} passes them to BOTH the header and the footer`, () => {
      // Both, because they are separate components: the header alone was translated at one
      // point during this work and the footer below it stayed English.
      expect(src).toContain('<LandingHeader labels={shell} />');
      expect(src).toContain('<LandingFooter labels={shell} />');
      // No bare-tag assertion here: `toContain('<LandingHeader labels={shell} />')` above
      // already excludes the propless form, and a second check on the same fact only looks
      // like extra coverage.
    });
  }

  it('leaves the chrome able to render with no labels at all', () => {
    // The pages outside the [locale] tree pass nothing, so the prop has to stay optional with
    // an English default. Making it required would move the breakage to those 14 pages.
    const shell = read('../LandingShell.tsx');
    expect(shell).toContain('labels = DEFAULT_SHELL_LABELS');
    expect(shell).toContain('labels?: ShellLabels');
    expect(shell).toContain('export const DEFAULT_SHELL_LABELS: ShellLabels');
  });
});
