// @vitest-environment jsdom
import { afterEach, describe, expect, it, vi } from 'vitest';
import { clearApplyDraft, loadApplyDraft, PARTNER_APPLY_DRAFT_KEY, saveApplyDraft } from '../applyDraft';

const DRAFT = { company: 'Acme', website: 'https://acme.io', audience: 'SMBs', message: 'Hello' };

describe('partner application draft (carried across the sign-in)', () => {
  afterEach(() => {
    window.sessionStorage.clear();
    vi.restoreAllMocks();
  });

  it('round-trips what the visitor typed, then clears it', () => {
    saveApplyDraft(DRAFT);
    expect(loadApplyDraft()).toEqual(DRAFT);

    clearApplyDraft();
    expect(loadApplyDraft()).toBeNull();
  });

  it('ignores a malformed or empty draft rather than prefilling garbage', () => {
    window.sessionStorage.setItem(PARTNER_APPLY_DRAFT_KEY, '{not json');
    expect(loadApplyDraft()).toBeNull();

    window.sessionStorage.setItem(PARTNER_APPLY_DRAFT_KEY, JSON.stringify({ company: 3 }));
    expect(loadApplyDraft()).toBeNull();

    window.sessionStorage.setItem(PARTNER_APPLY_DRAFT_KEY, JSON.stringify({ ...DRAFT, company: '   ' }));
    expect(loadApplyDraft()).toBeNull();
  });

  it('V557: keeps the terms version the visitor ticked, and still reads a draft saved before the terms existed', () => {
    saveApplyDraft({ ...DRAFT, termsVersion: '2026-10-01' });
    expect(loadApplyDraft()?.termsVersion).toBe('2026-10-01');

    window.sessionStorage.setItem(PARTNER_APPLY_DRAFT_KEY, JSON.stringify(DRAFT));
    expect(loadApplyDraft()).toEqual(DRAFT);
    expect(loadApplyDraft()?.termsVersion).toBeUndefined();
  });

  it('V557: a draft whose terms version is not a string is dropped, not trusted as an acceptance', () => {
    window.sessionStorage.setItem(PARTNER_APPLY_DRAFT_KEY, JSON.stringify({ ...DRAFT, termsVersion: true }));
    expect(loadApplyDraft()).toBeNull();
  });

  it('storage refused (private window, blocked site data): never throws, simply keeps nothing', () => {
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => { throw new Error('QuotaExceeded'); });
    vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => { throw new Error('SecurityError'); });
    vi.spyOn(Storage.prototype, 'removeItem').mockImplementation(() => { throw new Error('SecurityError'); });

    expect(() => saveApplyDraft(DRAFT)).not.toThrow();
    expect(loadApplyDraft()).toBeNull();
    expect(() => clearApplyDraft()).not.toThrow();
  });
});
