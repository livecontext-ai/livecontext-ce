import { describe, it, expect } from 'vitest';
import { orgScopeRequestOptions } from '../current-org-store';

/**
 * The per-request workspace override that powers the Quota / Storage page
 * workspace filters. It must produce a spreadable apiClient-options object that
 * sets `X-Active-Organization-ID` ONLY when a real org id is provided - any
 * falsy input must return undefined so the call falls back to the global active
 * workspace (no behavioural change for the common single-workspace user).
 */
describe('orgScopeRequestOptions', () => {
  it('builds the X-Active-Organization-ID header for a real org id', () => {
    expect(orgScopeRequestOptions('org-123')).toEqual({
      headers: { 'X-Active-Organization-ID': 'org-123' },
    });
  });

  it('returns undefined for undefined / null / empty string (no override)', () => {
    expect(orgScopeRequestOptions()).toBeUndefined();
    expect(orgScopeRequestOptions(undefined)).toBeUndefined();
    expect(orgScopeRequestOptions(null)).toBeUndefined();
    expect(orgScopeRequestOptions('')).toBeUndefined();
  });

  it('produces an object safe to spread into request options (no-override = no keys)', () => {
    // The merge form used by getHistory/getAnalytics: `{ params, ...(opts ?? {}) }`.
    const merged = { params: { days: '30' }, ...(orgScopeRequestOptions(null) ?? {}) };
    expect(merged).toEqual({ params: { days: '30' } });

    const mergedScoped = { params: { days: '30' }, ...(orgScopeRequestOptions('org-9') ?? {}) };
    expect(mergedScoped).toEqual({
      params: { days: '30' },
      headers: { 'X-Active-Organization-ID': 'org-9' },
    });
  });
});
