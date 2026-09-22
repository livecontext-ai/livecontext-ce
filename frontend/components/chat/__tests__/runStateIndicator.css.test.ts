/**
 * The application run-state indicator is drawn entirely by the stylesheet: the
 * component contributes class names, a state attribute and a label, nothing
 * else. So every property that makes it READ correctly is invisible from a
 * rendered tree and can only be pinned here.
 *
 * Four of them are load-bearing:
 *  - each class the TSX applies must actually have a rule (a class that only
 *    ever existed in the TSX leaves the indicator drawn by nothing at all, and
 *    the component test still passes because the class IS on the element);
 *  - the sweep must name an animation, because motion is the whole reason the
 *    original pulsing ring was replaced;
 *  - the two states must not look alike: the canvas already settled blue for
 *    executing and amber for blocked-on-a-human, and the application surface
 *    was the last place that only knew blue;
 *  - the ring must survive `prefers-reduced-motion`, because a reduced-motion
 *    user who loses the sweep still has to be told what the run is doing.
 */
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { describe, expect, it } from 'vitest';

const css = readFileSync(join(process.cwd(), 'app', 'globals.css'), 'utf8');
const tsx = readFileSync(join(process.cwd(), 'components', 'chat', 'RunStateIndicator.tsx'), 'utf8');

/** Every `app-run-state…` class the component puts on an element. */
function classesUsedByComponent(): string[] {
  return [...new Set([...tsx.matchAll(/app-run-state[\w-]*/g)].map(m => m[0]))];
}

/**
 * Declaration bodies of every rule whose selector mentions EXACTLY this class.
 *
 * <p>`String.raw` and a real escape, because the obvious version is silently
 * wrong: inside a plain template literal `\.` collapses to `.` (which matches
 * any character) and `\w` collapses to `w`, so the trailing boundary degrades
 * to `(?![w-])` and `.app-run-state` starts matching `__sweep`, `__chip` and
 * `__dots` too.
 */
function rulesFor(className: string): string[] {
  const escaped = className.replace(/[.*+?^${}()|[\]\\-]/g, '\\$&');
  return [
    ...css.matchAll(new RegExp(String.raw`\.${escaped}(?![\w-])[^{}]*\{([^}]*)\}`, 'g')),
  ].map(m => m[1]);
}

/**
 * Body of every `@media (prefers-reduced-motion: reduce)` block, brace-counted.
 * A lazy regex walks straight out of the media block and finds a BASE rule
 * further down the file, which is how a missing override reads as present.
 */
function reducedMotionBlocks(): string[] {
  const blocks: string[] = [];
  const at = /@media\s*\(prefers-reduced-motion:\s*reduce\)\s*\{/g;
  for (let m = at.exec(css); m; m = at.exec(css)) {
    let depth = 1;
    let i = m.index + m[0].length;
    const start = i;
    while (i < css.length && depth > 0) {
      if (css[i] === '{') depth++;
      else if (css[i] === '}') depth--;
      i++;
    }
    blocks.push(css.slice(start, i - 1));
  }
  return blocks;
}

describe('RunStateIndicator stylesheet contract', () => {
  it('gives every class the component applies at least one rule', () => {
    const used = classesUsedByComponent();
    expect(used).toContain('app-run-state');
    expect(used).toContain('app-run-state__sweep');
    expect(used).toContain('app-run-state__chip');

    const undeclared = used.filter(c => rulesFor(c).length === 0);
    expect(
      undeclared,
      `RunStateIndicator applies these classes but globals.css declares none of them, so they `
        + `draw nothing while every component test still passes:\n  ${undeclared.join('\n  ')}`,
    ).toEqual([]);
  });

  it('matches the base class only, not its BEM children (the helper the rest relies on)', () => {
    // A boundary that collapses to `(?![w-])` makes rulesFor('app-run-state')
    // swallow the __sweep / __chip / __dots rules as well, so anything reading
    // `[0]` below would be looking at whichever rule came first in the file.
    // State-qualified and descendant rules ARE expected here (the selector
    // still starts with the base class); a CHILD's own rule is not.
    const base = rulesFor('app-run-state');
    expect(base.length, 'at least the base rule and its .dark override').toBeGreaterThanOrEqual(2);
    expect(base[0], 'the first match must be the base ring rule').toMatch(/box-shadow:/);

    const swallowedAChild = base.some(body => /height:\s*3px/.test(body) || /width:\s*40%/.test(body));
    expect(swallowedAChild, 'the sweep and dots rules must not be matched by the BASE class').toBe(false);
  });

  it('gives the two states different colours, per the canvas vocabulary', () => {
    // Asserted per THEME and per SELECTOR, with a negative check, because the
    // loose version of this was vacuous in the default theme: it collected every
    // rule whose selector merely CONTAINS the awaiting attribute - the chip's
    // amber `color`, the `.dark` ring - so repainting the LIGHT awaiting ring
    // the running blue kept the suite green, including the assertion whose own
    // message forbids exactly that. A positive "some rule is amber" can never
    // close this; the negative one can.
    const ringOf = (selector: string) => {
      const found = [...css.matchAll(
        new RegExp(String.raw`(^|\})\s*${selector}\s*\{([^}]*)\}`, 'gm'),
      )].map(m => m[2]);
      expect(found.length, `no rule for ${selector}`).toBe(1);
      return found[0];
    };

    const BLUE = /rgba\(59,\s*130,\s*246/;
    const BLUE_DARK = /rgba\(96,\s*165,\s*250/;
    const AMBER = /rgba\(245,\s*158,\s*11/;
    const AMBER_DARK = /rgba\(251,\s*191,\s*36/;

    const lightRunning = ringOf(String.raw`\.app-run-state`);
    expect(lightRunning).toMatch(BLUE);

    const lightAwaiting = ringOf(String.raw`\.app-run-state\[data-run-state="awaiting"\]`);
    expect(lightAwaiting).toMatch(AMBER);
    expect(lightAwaiting, 'the DEFAULT theme is where this matters most').not.toMatch(BLUE);

    const darkRunning = ringOf(String.raw`\.dark \.app-run-state`);
    expect(darkRunning).toMatch(BLUE_DARK);

    const darkAwaiting = ringOf(String.raw`\.dark \.app-run-state\[data-run-state="awaiting"\]`);
    expect(darkAwaiting).toMatch(AMBER_DARK);
    expect(darkAwaiting).not.toMatch(BLUE_DARK);
  });

  it('animates the sweep, which is the only moving part of the indicator', () => {
    const sweepAfter = [...css.matchAll(/\.app-run-state__sweep::after[^{}]*\{([^}]*)\}/g)].map(m => m[1]);
    expect(sweepAfter.length, 'no `.app-run-state__sweep::after` rule').toBeGreaterThan(0);
    const animated = sweepAfter.some(body => /animation:\s*app-run-sweep/.test(body));
    expect(animated, 'the sweep must run the app-run-sweep keyframes').toBe(true);
    expect(css).toMatch(/@keyframes\s+app-run-sweep\s*\{/);
  });

  it('moves the sweep at a constant speed, so it reads as progress not as a shuttle', () => {
    const sweepAfter = [...css.matchAll(/\.app-run-state__sweep::after[^{}]*\{([^}]*)\}/g)].map(m => m[1]);
    const timed = sweepAfter.find(body => /animation:\s*app-run-sweep/.test(body)) ?? '';
    expect(timed).toMatch(/animation:\s*app-run-sweep\s+[\d.]+m?s\s+linear\s+infinite/);
  });

  it('keeps the ring under prefers-reduced-motion and drops only the motion', () => {
    // Scoped to the ONE block that mentions this indicator. Joining every
    // `prefers-reduced-motion` block in globals.css made this vacuous: an
    // unrelated pre-existing block already contains `animation: none`, so
    // deleting the override that actually stops the sweep left the test green.
    const blocks = reducedMotionBlocks().filter(b => b.includes('app-run-state'));
    expect(blocks.length, 'no prefers-reduced-motion block mentions the indicator').toBe(1);
    const block = blocks[0];

    const stopsTheSweep = /\.app-run-state__sweep::after[^{}]*\{[^}]*animation:\s*none/.test(block);
    expect(stopsTheSweep, 'the travelling sweep must stop for a reduced-motion user').toBe(true);

    const stopsTheDots = /\.app-run-state__dots[^{}]*\{[^}]*animation:\s*none/.test(block);
    expect(stopsTheDots, 'the chip dots must stop too').toBe(true);

    // ...and ONLY the motion: the indicator must still be visible, or a
    // reduced-motion user is told nothing at all about the run.
    expect(block).not.toMatch(/display:\s*none/);
    expect(block).not.toMatch(/visibility:\s*hidden/);
  });

  it('states the ring with a box-shadow rather than a layout-affecting border', () => {
    const base = rulesFor('app-run-state')[0] ?? '';
    expect(base).toMatch(/box-shadow:/);
    expect(base).toMatch(/pointer-events:\s*none/);
  });
});
