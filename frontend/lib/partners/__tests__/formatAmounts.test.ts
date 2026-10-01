import { describe, expect, it } from 'vitest';
import { formatAmounts, formatMinor, formatPercent } from '../formatAmounts';

describe('formatAmounts', () => {
  it('formats minor units as currency in the given app locale', () => {
    expect(formatMinor(1250, 'usd', 'en')).toBe('$12.50');
    // The app locale decides, never the browser: French groups and places the symbol its way.
    expect(formatMinor(1250, 'eur', 'fr')).toMatch(/12,50\s€/);
  });

  it('joins several currencies and drops zero amounts', () => {
    expect(formatAmounts({ usd: 1200, eur: 0, gbp: 300 }, 'en')).toBe('$12.00, £3.00');
  });

  it('answers an empty string for nothing', () => {
    expect(formatAmounts(undefined, 'en')).toBe('');
    expect(formatAmounts({}, 'en')).toBe('');
    expect(formatAmounts({ usd: 0 }, 'en')).toBe('');
  });

  it('formats a rate in the app locale, without trailing zeros', () => {
    expect(formatPercent(50, 'en')).toBe('50');
    expect(formatPercent(12.5, 'en')).toBe('12.5');
    // Regression: a raw number in an ICU placeholder printed "12.5 %" to French readers.
    expect(formatPercent(12.5, 'fr')).toBe('12,5');
    expect(formatPercent(12.5, 'de')).toBe('12,5');
  });

  it('falls back to "amount CODE" for a currency Intl does not know', () => {
    expect(formatMinor(500, 'xx1', 'en')).toBe('5 XX1');
  });
});
