import { describe, expect, it } from 'vitest';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';

/**
 * Every surface that states what a generation COST says it the same way.
 *
 * <p><b>This is not a hypothetical rule.</b> It broke once inside the change that introduced it:
 * the studio turn card was written with its own copy of the expression and quoted credits on a
 * self-hosted install, ten pixels above a history card quoting dollars for the same asset. The
 * per-edition render suites catch that for the two components they mount, and they cannot catch it
 * for the next surface somebody adds, which is what happened.
 *
 * <p>So the invariant is checked in the source: a file that shows a charge goes through
 * {@code describeCharge}, and none of them re-derives the edition split or the wording locally.
 * The rendered output of that helper is pinned exactly, per edition and per locale, in
 * `components/generation/__tests__/GenerationCard.price.realIntl.test.tsx`.
 */
const frontendRoot = join(__dirname, '..', '..', '..');

/**
 * The file's CODE, with comments stripped.
 *
 * <p>These files explain themselves at length, and every phrase this suite looks for appears in
 * those explanations: read raw, "billed_credits > 0" matches the sentence describing the guard as
 * readily as the guard. A check a comment can satisfy is one that survives the deletion of the
 * thing it names.
 */
const read = (rel: string) => readFileSync(join(frontendRoot, rel), 'utf-8')
  .replace(/\/\*[\s\S]*?\*\//g, ' ')
  .replace(/^\s*\/\/.*$/gm, ' ');

/** Every file that puts an amount of money in front of a reader for a generation. */
const CHARGE_SURFACES = [
  'components/generation/GenerationCard.tsx',
  'components/studio/StudioTurnCard.tsx',
  'components/chat/CreateGenerationModal.tsx',
];

describe('one expression for what a generation cost', () => {
  it.each(CHARGE_SURFACES)('%s states a charge through the shared helper', (rel) => {
    const source = read(rel);

    expect(source).toMatch(/describeCharge\s*\(/);
  });

  it.each(CHARGE_SURFACES)('%s re-derives neither the edition split nor the wording', (rel) => {
    const source = read(rel);

    // `isCeMode ? … : …` beside a price is the split coming back locally, and a bare `cost` message
    // lookup is the wording coming back with it. Both are what `describeCharge` exists to own.
    expect(source).not.toMatch(/isCeMode\s*\?/);
    expect(source).not.toMatch(/\(\s*['"]cost['"]\s*,/);
  });

  it('guards every one of them on a positive, finite amount', () => {
    // Absent is not zero, and none of these surfaces may draw a charge of nothing: a generation the
    // platform did not bill was paid for at the provider. The guard travels with each call site
    // because the helper takes a number and cannot refuse one.
    for (const rel of CHARGE_SURFACES) {
      const source = read(rel);
      const guarded = /billedCredits\s*>\s*0/.test(source)
        || /billed_credits\s*>\s*0/.test(source)
        || /cost\s*!==\s*null/.test(source);

      expect(guarded, `${rel} states a charge without checking it is positive`).toBe(true);
    }
  });
});
