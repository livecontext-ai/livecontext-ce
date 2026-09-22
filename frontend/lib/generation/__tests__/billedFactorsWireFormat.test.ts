// @vitest-environment node
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { describe, expect, it, vi } from 'vitest';

import { describeBilledFactors } from '../price';

vi.mock('@/lib/utils/locale', () => ({ getClientLocale: () => 'en' }));

/**
 * One wire format for `billed_multiplier_reasons`, written in Java and read in TypeScript.
 *
 * <p>The server builds each line in `GenerationSpec.explainFactor` as
 * {@code param + " x" + factor}; `describeBilledFactors` parses it back so a finished turn card
 * can word the reason through the same dictionary the estimate uses. Each side is pinned by its
 * own literal example, and those two examples agree today by nothing more than the fact that one
 * author wrote both.
 *
 * <p>Change the separator in Java and every suite stays green while every finished turn card falls
 * to the unparsed branch: the raw contract name, in English, beside a translated estimate - the
 * exact regression `describeBilledFactors` was written to fix. So the format itself is asserted,
 * from the Java source, rather than assumed.
 */
describe('the billed-reason wire format is one format', () => {
  const JAVA = join(
    process.cwd(), '..', 'backend', 'catalog-service', 'src', 'main', 'java', 'com',
    'apimarketplace', 'catalog', 'service', 'generation', 'GenerationSpec.java',
  );

  /** The `generation` namespace, rendering keys so an assertion can see which was chosen. */
  const t = ((key: string, values?: Record<string, unknown>) => {
    if (key === 'price.factor') return `${values?.param} x${values?.factor}`;
    if (key === 'price.factors') return `includes ${values?.list}`;
    if (key.startsWith('params.')) return key.slice('params.'.length);
    return key;
  }) as never;
  (t as unknown as { has: (k: string) => boolean }).has = (key: string) => key.startsWith('params.');

  it('still builds its lines as `param x<factor>` on the Java side', () => {
    // Read from the source rather than restated, so a change to the separator fails HERE, on a
    // test that says what depends on it, instead of silently downgrading every turn card.
    const java = readFileSync(JAVA, 'utf8');

    expect(
      java,
      'GenerationSpec.explainFactor must still join a param and its factor with " x"',
    ).toContain('out.add(m.param() + " x" + f.stripTrailingZeros().toPlainString());');
  });

  it('parses the shape that Java line produces, for every factor it can produce', () => {
    // `stripTrailingZeros().toPlainString()` yields an integer, a short decimal, or a long one.
    // All three are shapes the parser must recognise, or the reason falls through untranslated.
    for (const [line, param, factor] of [
      ['resolution x2', 'resolution', '2'],
      ['input_image x1.05', 'input_image', '1.05'],
      ['reference_image x1.1025', 'reference_image', '1.1025'],
    ] as const) {
      expect(describeBilledFactors([line], t), line)
        .toBe(`includes ${param} x${factor}`);
    }
  });

  it('would NOT silently accept a different separator as a parameter name', () => {
    // The failure mode this guards: a line the parser half-understands is worse than one it
    // rejects, because it would name a parameter nobody has.
    expect(describeBilledFactors(['resolution*2'], t)).toBe('includes resolution*2');
  });
});
