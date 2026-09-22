// @vitest-environment node
import { describe, expect, it, vi } from 'vitest';

import { describeBilledFactors } from '../price';

vi.mock('@/lib/utils/locale', () => ({ getClientLocale: () => 'en' }));

/**
 * The reason a FINISHED turn cost what it did, worded from what the server sent.
 *
 * <p>The server writes its reasons as `param x<factor>` using the raw contract name, in English.
 * Printed verbatim beside an estimate that says "includes Resolution x2", translated and
 * list-joined per locale, one fact appears in two vocabularies on one screen. So the lines are
 * parsed back into the shape the estimate uses and worded by the same function.
 *
 * <p>Reached through `StudioTurnCard` by two fixed shapes and by nothing else, which left the
 * branch that mixes parsed and unparsed lines untested - and that branch is the one a future wire
 * format lands in.
 */
describe('describeBilledFactors', () => {
  /** The `generation` namespace, rendering keys so an assertion can see which was chosen. */
  const t = ((key: string, values?: Record<string, unknown>) => {
    if (key === 'price.factor') return `${values?.param} x${values?.factor}`;
    if (key === 'price.factors') return `includes ${values?.list}`;
    if (key.startsWith('params.')) return key.slice('params.'.length).replace(/^\w/, (c) => c.toUpperCase());
    return key;
  }) as never;
  // The label helper asks whether a key exists before falling back to the raw parameter name.
  (t as unknown as { has: (k: string) => boolean }).has = (key: string) => key.startsWith('params.');

  it('says nothing when there is nothing to say', () => {
    // Absent and empty are both "this call ran at the published rate", and a card that printed an
    // empty explanation would draw a slot with nothing in it on every ordinary turn.
    expect(describeBilledFactors(undefined, t)).toBe('');
    expect(describeBilledFactors([], t)).toBe('');
  });

  it('words a server line through the SAME dictionary the estimate uses', () => {
    // The whole point: `resolution x2` from the wire must read as the estimate's "Resolution x2",
    // not as the raw contract name.
    expect(describeBilledFactors(['resolution x2'], t)).toBe('includes Resolution x2');
  });

  it('joins several the way the reader\'s language joins a list', () => {
    expect(describeBilledFactors(['resolution x2', 'reference_image x1.1'], t))
      .toBe('includes Resolution x2, Reference_image x1.1');
  });

  it('hands back a line it cannot parse, rather than dropping it', () => {
    // A future wire format reaches the reader as the server's own words instead of vanishing,
    // which is the difference between an explanation that is unfamiliar and one that is absent.
    expect(describeBilledFactors(['something new entirely'], t))
      .toBe('includes something new entirely');
  });

  it('MIXES parsed and unparsed lines, keeping both', () => {
    // The branch nothing reached. A partial format change must not silently drop the half this
    // build still understands, nor the half it does not.
    const sentence = describeBilledFactors(['resolution x2', 'a shape from a later release'], t);

    expect(sentence).toContain('Resolution x2');
    expect(sentence).toContain('a shape from a later release');
  });

  it('tolerates the spacing and precision the server can emit', () => {
    // `stripTrailingZeros().toPlainString()` on the Java side produces `1.05`, `2`, `1.1025`, and
    // the line is trimmed before matching.
    expect(describeBilledFactors(['  input_image x1.1025  '], t))
      .toBe('includes Input_image x1.1025');
  });

  it('does not mistake a parameter whose NAME contains a number for a factor', () => {
    // `x2` has to be the tail of the line, not something found in the middle of it.
    expect(describeBilledFactors(['image_2_reference x3'], t))
      .toBe('includes Image_2_reference x3');
  });
});
