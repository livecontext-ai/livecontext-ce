import { describe, it, expect } from 'vitest';
import {
  clampPublicLinkTtlMinutes,
  PUBLIC_LINK_TTL_DEFAULT,
  PUBLIC_LINK_TTL_MAX,
  PUBLIC_LINK_TTL_MIN,
} from '../publicLinkParams';

/**
 * The public_link inspector's ttl_minutes field commits through
 * clampPublicLinkTtlMinutes on blur; these tests pin the 5-10080 bounds
 * (mirroring the backend clamp) and the default fallback so a typed value
 * can never leave the documented range or reach the plan raw.
 */
describe('clampPublicLinkTtlMinutes (public_link ttl_minutes inspector field)', () => {
  it('clamps values above the maximum to 10080 (7 days)', () => {
    expect(clampPublicLinkTtlMinutes(999999)).toBe(PUBLIC_LINK_TTL_MAX);
    expect(clampPublicLinkTtlMinutes(10081)).toBe(10080);
  });

  it('clamps values below the minimum to 5', () => {
    expect(clampPublicLinkTtlMinutes(1)).toBe(PUBLIC_LINK_TTL_MIN);
    expect(clampPublicLinkTtlMinutes(0)).toBe(5);
    expect(clampPublicLinkTtlMinutes(-30)).toBe(5);
  });

  it('keeps in-range values untouched', () => {
    expect(clampPublicLinkTtlMinutes(5)).toBe(5);
    expect(clampPublicLinkTtlMinutes(240)).toBe(240);
    expect(clampPublicLinkTtlMinutes(10080)).toBe(10080);
  });

  it('falls back to the default (240) on non-numeric input (cleared field, junk)', () => {
    expect(clampPublicLinkTtlMinutes('')).toBe(PUBLIC_LINK_TTL_DEFAULT);
    expect(clampPublicLinkTtlMinutes('abc')).toBe(PUBLIC_LINK_TTL_DEFAULT);
    expect(clampPublicLinkTtlMinutes(undefined)).toBe(PUBLIC_LINK_TTL_DEFAULT);
    expect(clampPublicLinkTtlMinutes(NaN)).toBe(PUBLIC_LINK_TTL_DEFAULT);
  });

  it('accepts numeric strings from the input element', () => {
    expect(clampPublicLinkTtlMinutes('60')).toBe(60);
    expect(clampPublicLinkTtlMinutes('999999')).toBe(10080);
  });
});
