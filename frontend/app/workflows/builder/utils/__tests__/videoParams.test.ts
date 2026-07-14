import { describe, it, expect } from 'vitest';
import {
  clampVideoMaxDuration,
  VIDEO_DURATION_DEFAULT,
  VIDEO_DURATION_MAX,
  VIDEO_DURATION_MIN,
} from '../videoParams';

/**
 * Regression: the inspector's video max-duration field displayed badly and was NOT bounded -
 * a user could type 999 (or 0) and the raw value reached the plan. The field now commits
 * through clampVideoMaxDuration on blur; these tests pin the 5-120 bounds and the fallback.
 */
describe('clampVideoMaxDuration (regression: unbounded max-duration inspector field)', () => {
  it('clamps values above the maximum to 120', () => {
    expect(clampVideoMaxDuration(999)).toBe(VIDEO_DURATION_MAX);
    expect(clampVideoMaxDuration(121)).toBe(120);
  });

  it('clamps values below the minimum to 5', () => {
    expect(clampVideoMaxDuration(1)).toBe(VIDEO_DURATION_MIN);
    expect(clampVideoMaxDuration(0)).toBe(5);
    expect(clampVideoMaxDuration(-30)).toBe(5);
  });

  it('keeps in-range values untouched', () => {
    expect(clampVideoMaxDuration(5)).toBe(5);
    expect(clampVideoMaxDuration(45)).toBe(45);
    expect(clampVideoMaxDuration(120)).toBe(120);
  });

  it('falls back to the default on non-numeric input (cleared field, junk)', () => {
    expect(clampVideoMaxDuration('')).toBe(VIDEO_DURATION_DEFAULT);
    expect(clampVideoMaxDuration('abc')).toBe(VIDEO_DURATION_DEFAULT);
    expect(clampVideoMaxDuration(undefined)).toBe(VIDEO_DURATION_DEFAULT);
    expect(clampVideoMaxDuration(NaN)).toBe(VIDEO_DURATION_DEFAULT);
  });

  it('accepts numeric strings from the input element', () => {
    expect(clampVideoMaxDuration('60')).toBe(60);
    expect(clampVideoMaxDuration('999')).toBe(120);
  });
});
