import { describe, it, expect } from 'vitest';
import fs from 'node:fs';
import path from 'node:path';

/**
 * The half of the right-aligned "i" that lives in the CALLERS.
 *
 * <p>`FeatureLabel` pushes its icon out with `justify-between`, which does
 * nothing at all unless the row it sits in gives it the width. Its own test
 * pins the component side; this pins the other side, which is where the defect
 * actually was: three of the four call sites wrapped it in a shrink-to-fit
 * `<span>`, so every icon sat immediately after the last word and a label that
 * wrapped put its icon in the middle of the row.
 *
 * <p>jsdom computes no layout, so no rendering test can measure this. The rule
 * is a property of a source line, and that is what is checked.
 */

const ROOT = path.resolve(__dirname, '..', '..', '..');

/**
 * Every place the label is rendered, with the snippet that must grant it the
 * width. Each is read as the text immediately AROUND the tag, because the grant
 * lives on the element wrapping it (or, for a table cell and a flex item, on
 * the component's own `flex-1`).
 */
const CALL_SITES: ReadonlyArray<{ file: string; needs: RegExp; why: string }> = [
  {
    file: 'app/[locale]/_landing/PricingSection.tsx',
    needs: /className="flex flex-1 min-w-0"/,
    why: 'the landing plan cards wrap it in a span that must fill the bullet row',
  },
  {
    file: 'components/pricing/PlanSelector.tsx',
    needs: /className="flex flex-1 min-w-0 text-theme-secondary/,
    why: 'the settings plan cards wrap it in a span that must fill the bullet row',
  },
  {
    file: 'components/billing/InsufficientCreditsModal.tsx',
    needs: /className="flex items-start gap-1\.5 text-xs/,
    why: 'the modal makes it a flex ITEM directly, so its own flex-1 is the grant',
  },
  {
    file: 'components/pricing/PlanComparisonDialog.tsx',
    // A `<th>` whose tag CLOSES before this one opens. It does not prove the
    // cell is still open (a `</th><td ...>` inside the window would satisfy it
    // too), and it does not need to: this row and the modal's are a written
    // contract rather than a regression guard, because neither call site had to
    // change. What they buy is a failure the day someone rewraps one of them.
    needs: /<th[\s\S]*>\s*$/,
    why: 'the comparison table gives it a block context, where a flex fills the cell',
  },
];

describe('every FeatureLabel call site gives the row its width', () => {
  it.each(CALL_SITES)('$file: $why', ({ file, needs }) => {
    const full = path.join(ROOT, file);
    expect(fs.existsSync(full), `${file} has moved; this guard is scanning nothing`).toBe(true);
    const source = fs.readFileSync(full, 'utf8');

    // EVERY occurrence, not the first: a file may render the label twice, and a
    // row added later would otherwise inherit no guard at all.
    const positions: number[] = [];
    for (let at = source.indexOf('<FeatureLabel'); at !== -1; at = source.indexOf('<FeatureLabel', at + 1)) {
      positions.push(at);
    }
    expect(positions.length, `${file} no longer renders FeatureLabel`).toBeGreaterThan(0);

    for (const at of positions) {
      // Wide enough to reach the opening tag of the wrapper, which in the
      // comparison table is a `<th>` carrying a multi-line className.
      const before = source.slice(Math.max(0, at - 1000), at);
      expect(before, `${file} renders FeatureLabel in a row that does not grant it width`)
        .toMatch(needs);
    }
  });

  it('keeps the component itself asking for that width', () => {
    // The two halves are only a contract together: a caller granting width to a
    // component that no longer claims it is as broken as the reverse, and this
    // file would still pass on its own.
    const source = fs.readFileSync(path.join(ROOT, 'components/pricing/FeatureLabel.tsx'), 'utf8');
    expect(source).toMatch(/flex flex-1 min-w-0 items-start justify-between/);
  });
});
