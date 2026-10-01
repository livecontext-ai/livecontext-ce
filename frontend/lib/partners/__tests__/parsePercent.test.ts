import { describe, expect, it } from 'vitest';
import { parsePercent } from '../parsePercent';

describe('parsePercent', () => {
  it('empty means the program default: no override is sent', () => {
    expect(parsePercent('')).toEqual({ ok: true, value: undefined });
    expect(parsePercent('   ')).toEqual({ ok: true, value: undefined });
  });

  it('reads whole and decimal numbers, with a dot or a comma', () => {
    expect(parsePercent('40')).toEqual({ ok: true, value: 40 });
    expect(parsePercent(' 12.5 ')).toEqual({ ok: true, value: 12.5 });
    // Regression: "12,5" used to become NaN, then null over JSON, then the 50% default.
    expect(parsePercent('12,5')).toEqual({ ok: true, value: 12.5 });
    expect(parsePercent('0')).toEqual({ ok: true, value: 0 });
    expect(parsePercent('100')).toEqual({ ok: true, value: 100 });
  });

  it('refuses anything that is not a percentage, instead of falling back to the default', () => {
    for (const bad of ['abc', '12,5,1', '1e2', '-5', '100.5', '150', '12 %', '.5', 'NaN']) {
      expect(parsePercent(bad), bad).toEqual({ ok: false });
    }
  });
});
