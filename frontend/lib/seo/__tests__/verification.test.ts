import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { buildVerification } from '../verification';

// The root layout is imported for its metadata only. Its font loaders are a
// build-time Next transform with no runtime implementation.
vi.mock('next/font/google', () => ({
  Inter: () => ({ variable: '--font-inter' }),
  Outfit: () => ({ variable: '--font-outfit' }),
}));

describe('search-engine verification', () => {
  it('emits nothing when no token is configured', () => {
    expect(buildVerification(false, {})).toBeUndefined();
    // Whitespace is not a token: a variable set to "" or " " in a deployment
    // would otherwise emit an empty meta tag that fails verification.
    expect(buildVerification(false, { google: '  ', bing: '', yandex: undefined })).toBeUndefined();
  });

  it('claims only the properties that have a token', () => {
    expect(buildVerification(false, { google: 'g-token' })).toEqual({ google: 'g-token' });
    expect(buildVerification(false, { yandex: 'y-token' })).toEqual({ yandex: 'y-token' });
    expect(buildVerification(false, { bing: 'b-token' })).toEqual({ other: { 'msvalidate.01': 'b-token' } });
  });

  it('uses the native field where Next has one, and names the tag where it does not', () => {
    // Google and Yandex have native fields; Bing does not. Routing Yandex
    // through `other` worked, and hid the fact that the framework already knew
    // the tag name. The test below this describe block asserts the metadata
    // OBJECT the layout exports, not rendered HTML: the mapping from these keys
    // to the meta names is Next's, and was confirmed on a running server.
    expect(buildVerification(false, { google: 'g', bing: 'b', yandex: 'y' })).toEqual({
      google: 'g',
      yandex: 'y',
      other: { 'msvalidate.01': 'b' },
    });
  });

  it('trims a token pasted with surrounding whitespace', () => {
    expect(buildVerification(false, { google: ' g-token \n' })).toEqual({ google: 'g-token' });
  });

  it('emits nothing on a self-hosted edition, whatever is configured', () => {
    // A CE install runs on someone else's domain: our tokens verify nothing
    // there, and asserting ownership of a property we do not share is wrong.
    expect(buildVerification(true, { google: 'g', bing: 'b', yandex: 'y' })).toBeUndefined();
  });
});

describe('search-engine verification - wired into the root layout', () => {
  const saved = { ...process.env };

  beforeEach(() => vi.resetModules());
  afterEach(() => {
    process.env = { ...saved };
  });

  it('reaches the metadata Next renders, under the names the engines read', async () => {
    // Without this, a typo in an env var name, or the call being dropped from
    // `app/layout.tsx` altogether, ships green: every unit above passes on a
    // function nothing invokes.
    process.env.GOOGLE_SITE_VERIFICATION = 'g-wired';
    process.env.BING_SITE_VERIFICATION = 'b-wired';
    process.env.YANDEX_SITE_VERIFICATION = 'y-wired';

    const { metadata } = await import('@/app/layout');

    expect(metadata.verification).toEqual({
      google: 'g-wired',
      yandex: 'y-wired',
      other: { 'msvalidate.01': 'b-wired' },
    });
  });

  it('emits no verification block at all when nothing is configured', async () => {
    delete process.env.GOOGLE_SITE_VERIFICATION;
    delete process.env.BING_SITE_VERIFICATION;
    delete process.env.YANDEX_SITE_VERIFICATION;

    const { metadata } = await import('@/app/layout');
    expect(metadata.verification).toBeUndefined();
  });
});
