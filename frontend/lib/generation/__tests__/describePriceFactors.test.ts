// @vitest-environment node
import { describe, it, expect, vi } from 'vitest';

vi.mock('@/lib/utils/locale', () => ({ getClientLocale: () => locale.value }));
const locale = { value: 'en' };

import { describePriceFactors } from '../price';

/**
 * WHY a price is not the rate times the size.
 *
 * <p>The amount already includes the factor. Without the sentence, a total that is not the product
 * of the two numbers printed beside it reads as an arithmetic error, and the reader's only way to
 * test it is to spend.
 */

/** The `generation` namespace, narrowed to what the helper reads. */
function translator(dictionary: Record<string, string>) {
  const t = (key: string, values?: Record<string, unknown>) => {
    const template = dictionary[key] ?? key;
    return template.replace(/\{(\w+)\}/g, (_, name) => String(values?.[name] ?? ''));
  };
  t.has = (key: string) => key in dictionary;
  return t as never;
}

const DICTIONARY = {
  'price.factor': '{param} x{factor}',
  'price.factors': 'includes {list}',
  'params.resolution': 'Resolution',
};

describe('describePriceFactors', () => {
  it('says nothing when nothing moved the price', () => {
    expect(describePriceFactors([], translator(DICTIONARY))).toBe('');
  });

  it('names the parameter in the reader\'s own words', () => {
    expect(describePriceFactors([{ param: 'resolution', factor: 2 }], translator(DICTIONARY)))
      .toBe('includes Resolution x2');
  });

  it('falls back to the contract name for a parameter this build has no word for', () => {
    // The catalogue ships new parameters without the app being rebuilt. The platform's own name
    // for it is readable and is what any documentation about it says; a raw key path is neither.
    expect(describePriceFactors([{ param: 'reference_image', factor: 1.1 }], translator(DICTIONARY)))
      .toBe('includes reference_image x1.1');
  });

  it('joins several factors', () => {
    const sentence = describePriceFactors(
      [{ param: 'resolution', factor: 2 }, { param: 'reference_image', factor: 1.1 }],
      translator(DICTIONARY),
    );
    expect(sentence).toContain('Resolution x2');
    expect(sentence).toContain('reference_image x1.1');
  });

  it('joins them with the MARK the reader\'s language enumerates with', () => {
    // A hard-coded ", " is a Latin-script assumption: Chinese enumerates with a different mark.
    //
    // This asserted `chinese !== english` and nothing else, which any difference satisfies -
    // including no separator at all. The shipped join asked ListFormat for `type: 'unit'`, and
    // `unit` in `zh` emits NOTHING between the items: the screen read `分辨率 x2参考图 x1.1`,
    // run together, and this test was green the whole time. Assert the mark, not the inequality.
    const factors = [{ param: 'resolution', factor: 2 }, { param: 'reference_image', factor: 1.1 }];

    locale.value = 'zh';
    const chinese = describePriceFactors(factors, translator(DICTIONARY));
    locale.value = 'en';
    const english = describePriceFactors(factors, translator(DICTIONARY));

    expect(chinese).toContain('、');
    expect(english).toContain(', ');
    // And the two items are still whole: a separator that ate a character would satisfy the two
    // assertions above.
    expect(chinese).toContain('Resolution x2');
    expect(chinese).toContain('reference_image x1.1');
  });

  it('never runs two factors together, in any locale the app ships', () => {
    // The generalisation of the bug above. A locale whose ListFormat data lacks the pattern this
    // code asks for silently produces a joinless string, and only the locale that happens to be
    // tested would show it.
    const factors = [{ param: 'resolution', factor: 2 }, { param: 'reference_image', factor: 1.1 }];

    for (const code of ['en', 'fr', 'de', 'es', 'pt', 'zh']) {
      locale.value = code;
      const sentence = describePriceFactors(factors, translator(DICTIONARY));
      expect(sentence, `${code} must put something between the two factors`)
        .not.toContain('x2Reference');
      expect(sentence, `${code} must put something between the two factors`)
        .not.toContain('x2reference');
    }
    locale.value = 'en';
  });

  it('formats the factor in the app locale, never the browser\'s', () => {
    locale.value = 'fr';
    const french = describePriceFactors([{ param: 'resolution', factor: 1.5 }], translator(DICTIONARY));
    locale.value = 'en';

    expect(french).toContain('1,5');
  });
});
