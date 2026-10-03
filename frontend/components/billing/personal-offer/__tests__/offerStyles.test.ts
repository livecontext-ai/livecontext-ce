import { readFileSync } from 'node:fs';
import path from 'node:path';
import { describe, expect, it } from 'vitest';

/**
 * What the offer page's styles hold to, read from the source because jsdom compiles no CSS.
 *
 * <p>`bg-theme-*` and friends are hand-written classes (globals.css, `@layer components`), so
 * Tailwind generates no opacity or state variants for them: `bg-theme-primary/90` and
 * `hover:bg-theme-secondary` compiled to nothing, leaving the summary card transparent over the
 * colour glows and the tiers without hover feedback. The CSS variables take both.
 */
const read = (file: string) => readFileSync(path.resolve(__dirname, '..', file), 'utf8');
const sources = { 'PersonalOfferView.tsx': read('PersonalOfferView.tsx'), 'OfferCountdown.tsx': read('OfferCountdown.tsx') };

describe('the offer page styles', () => {
  it('regression: no theme class with a variant Tailwind cannot generate', () => {
    for (const [file, src] of Object.entries(sources)) {
      // Any opacity, numeric or arbitrary: bg-theme-primary/90, border-theme/60, bg-theme-primary/[0.9].
      expect(src, `${file}: an opacity on a theme class compiles to nothing`).not.toMatch(/\b(bg|text|border)-theme(-[a-z]+)?\//);
      // Any variant prefix: hover:, focus:, dark:, md:, group-hover:...
      expect(src, `${file}: a variant on a theme class compiles to nothing`).not.toMatch(/[a-z0-9\]-]:(bg|text|border)-theme\b/);
    }
  });

  it('regression: the light-theme gradient text keeps 700 shades (4.5:1 on white and on the selected tier\'s tint)', () => {
    const match = /const GRADIENT_TEXT = '([^']+)'/.exec(sources['PersonalOfferView.tsx']);
    expect(match, 'GRADIENT_TEXT is gone').not.toBeNull();
    const light = match![1].split(/\s+/).filter((token) => /^(from|via|to)-[a-z]+-\d+$/.test(token));
    // Measured: the 500s read 2.1:1 (amber), and a rose-600 middle 4.07:1 on the tier's tint.
    expect(light).toHaveLength(3);
    for (const token of light) {
      expect(Number(token.split('-').pop()), `${token} is too light for text on white`).toBeGreaterThanOrEqual(700);
    }
  });
});
