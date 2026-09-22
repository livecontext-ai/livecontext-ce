/**
 * Balanced column choice for the Home highlights row.
 *
 * The row must never end on a lonely single card when a wider-but-shorter split
 * exists: 4 cards in a 3-column tier render 2 + 2, not 3 + 1. The counterpart
 * matters just as much - a step down that does NOT remove the orphan (7 cards
 * strand one at 3, 2 AND 1 columns) would only buy taller rows, so the tier keeps
 * its natural column count.
 */
import path from 'node:path';
import fs from 'node:fs';
import { describe, expect, it } from 'vitest';
import { highlightGridColumns } from '../highlightGridColumns';

/** Columns the class string asks for at one tier, e.g. tierOf(cls, 720) === 2. */
function tierOf(classes: string, threshold: number): number {
  const hit = classes.split(' ').find(c => c.startsWith(`@min-[${threshold}px]:`));
  if (!hit) throw new Error(`no @min-[${threshold}px] class in "${classes}"`);
  return Number(hit.slice(hit.lastIndexOf('-') + 1));
}

const MD = 720;
const LG = 960;

describe('highlightGridColumns', () => {
  it('splits 4 cards into 2 + 2 rather than 3 + 1 when a row of 4 does not fit', () => {
    const cls = highlightGridColumns(4);
    expect(tierOf(cls, MD)).toBe(2);
    // A row of 4 still fits at the widest tier, so nothing is rebalanced there.
    expect(tierOf(cls, LG)).toBe(4);
  });

  it('keeps one card per row on the narrowest tier', () => {
    for (const count of [0, 1, 2, 3, 4, 5, 6, 8, 13]) {
      expect(highlightGridColumns(count).split(' ')[0]).toBe('grid-cols-1');
    }
  });

  it('keeps the tier column count when the cards already wrap evenly', () => {
    expect(tierOf(highlightGridColumns(6), MD)).toBe(3); // 3 + 3
    expect(tierOf(highlightGridColumns(8), LG)).toBe(4); // 4 + 4
    expect(tierOf(highlightGridColumns(8), MD)).toBe(3); // 3 + 3 + 2, no orphan
  });

  it('keeps the tier column count when every card fits on one row', () => {
    // Fewer cards than columns must NOT shrink the grid, or 2 cards would blow up
    // to half the row each instead of keeping the card size of a full row.
    for (const count of [0, 1, 2, 3]) {
      expect(tierOf(highlightGridColumns(count), LG)).toBe(4);
    }
    expect(tierOf(highlightGridColumns(3), MD)).toBe(3);
  });

  it('steps down again when the widest tier would also strand a single card', () => {
    expect(tierOf(highlightGridColumns(9), LG)).toBe(3); // 4 + 4 + 1 -> 3 + 3 + 3
    expect(tierOf(highlightGridColumns(5), LG)).toBe(3); // 4 + 1 -> 3 + 2
    expect(tierOf(highlightGridColumns(5), MD)).toBe(3); // 3 + 2 is already fine
  });

  it('keeps the widest tier when NO column count avoids the orphan', () => {
    // 7 cards strand one at 3, 2 and 1 columns alike. Narrowing would make the
    // cards larger and the row taller and still leave the lonely card, so the
    // tier must stay where it is instead of paying for nothing.
    expect(tierOf(highlightGridColumns(7), MD)).toBe(3);
    // 13 strands one at 4, 3 and 2 columns alike.
    expect(tierOf(highlightGridColumns(13), LG)).toBe(4);
    expect(tierOf(highlightGridColumns(13), MD)).toBe(3);
  });

  it('never emits a column count outside what each tier can render', () => {
    // The class tables hold exactly the counts each tier can return; anything else
    // would silently join `undefined` into the class string (the tsconfig is not
    // strict, so nothing else catches it).
    for (let count = 0; count <= 40; count++) {
      const cls = highlightGridColumns(count);
      expect(cls).not.toContain('undefined');
      expect(tierOf(cls, 480)).toBe(2);
      expect([2, 3]).toContain(tierOf(cls, MD));
      // 2 columns is unreachable at the widest tier: it would need a count that is
      // odd (4 rejected it) and even (2 accepted it) at the same time.
      expect([3, 4]).toContain(tierOf(cls, LG));
    }
  });

  it('never narrows a tier below what the card count needs', () => {
    // Guards the whole family at once: a tier may only step down when the step
    // actually removes an orphan last row.
    for (let count = 0; count <= 40; count++) {
      for (const [threshold, max] of [[MD, 3], [LG, 4]] as const) {
        const cols = tierOf(highlightGridColumns(count), threshold);
        if (cols === max) continue;
        expect(count % cols).not.toBe(1); // the step removed the orphan
        expect(count % max).toBe(1);      // and there was one to remove
      }
    }
  });
});

/**
 * The helper is only useful if Tailwind can actually turn the strings it emits
 * into CSS. A typo in a variant (or a version that drops the `@min-[...]`
 * container query) produces NO rule and no error: the grid would silently fall
 * back to one column at every width. So compile the real candidates and assert
 * that EACH ONE owns a rule, matched on its own escaped selector - a substring
 * search over the whole stylesheet would be satisfied by any sibling candidate
 * and would miss the very class this layout exists to produce.
 */
describe('the emitted classes compile', () => {
  it('gives every class it emits a rule with the right columns and threshold', async () => {
    const { compile } = await import('tailwindcss');
    const twDir = path.join(process.cwd(), 'node_modules', 'tailwindcss');
    const tailwind = await compile('@import "tailwindcss";', {
      base: process.cwd(),
      loadStylesheet: async (id: string, base: string) => {
        const file = id === 'tailwindcss' ? path.join(twDir, 'index.css') : path.resolve(base, id);
        return { path: file, base: path.dirname(file), content: fs.readFileSync(file, 'utf8') };
      },
    });

    const candidates = new Set<string>(['@container']);
    for (let count = 0; count <= 12; count++) {
      highlightGridColumns(count).split(' ').forEach(c => candidates.add(c));
    }
    // Every tier of every reachable count, or the sweep proves nothing.
    expect(candidates.size).toBe(7);

    const css = tailwind.build([...candidates]);
    // The wrapper class has to establish the container, or every query below it
    // resolves against nothing.
    expect(css).toContain('container-type: inline-size');

    for (const candidate of candidates) {
      if (candidate === '@container') continue;
    // Tailwind escapes @, : and [] in the class selector it emits.
    const escaped = candidate.replace(/[^a-zA-Z0-9-]/g, ch => "\\" + ch);
    const selector = '.' + escaped;
      const rule = css.slice(css.indexOf(selector + ' {'));
      expect(css, `${candidate} has no rule of its own`).toContain(selector + ' {');
      const cols = candidate.slice(candidate.lastIndexOf('-') + 1);
      expect(rule.slice(0, 400)).toContain(`repeat(${cols}, minmax(0, 1fr))`);
      const threshold = candidate.match(/@min-\[(\d+px)\]/)?.[1];
      if (threshold) expect(rule.slice(0, 400)).toContain(`width >= ${threshold}`);
    }
  });
});
