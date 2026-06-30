// @vitest-environment jsdom
/**
 * Tests for the CE → cloud URL rewriter.
 *
 * `cloudWebUrl` reads `IS_CE`, which the edition resolver freezes once at module
 * load from `NEXT_PUBLIC_*`. Each test therefore stubs the env vars, resets the
 * module cache, and dynamically imports a fresh `cloudWebUrl` so the real
 * resolver re-runs against the stubbed edition (no mocking of the helper itself).
 */

import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';

async function importCloudWebUrl(edition: 'ce' | 'cloud') {
  vi.resetModules();
  vi.stubEnv('NEXT_PUBLIC_APP_EDITION', edition);
  vi.stubEnv('NEXT_PUBLIC_AUTH_MODE', '');
  return await import('../cloudWebUrl');
}

describe('cloudWebUrl - CE rewrites onto the cloud origin', () => {
  beforeEach(() => {
    vi.resetModules();
  });
  afterEach(() => {
    vi.unstubAllEnvs();
  });

  it('rewrites a relative path onto the cloud origin, preserving query', async () => {
    const { cloudWebUrl, CLOUD_WEB_BASE_URL } = await importCloudWebUrl('ce');
    expect(CLOUD_WEB_BASE_URL).toBe('https://livecontext.ai');
    expect(cloudWebUrl('/contact?category=abuse&message=hello')).toBe(
      'https://livecontext.ai/contact?category=abuse&message=hello',
    );
  });

  it('swaps the origin of an absolute same-origin (localhost) URL for the cloud', async () => {
    const { cloudWebUrl } = await importCloudWebUrl('ce');
    expect(cloudWebUrl('http://localhost:3000/en/app/marketplace/pub-1/preview')).toBe(
      'https://livecontext.ai/en/app/marketplace/pub-1/preview',
    );
  });

  it('preserves the hash fragment', async () => {
    const { cloudWebUrl } = await importCloudWebUrl('ce');
    expect(cloudWebUrl('/legal/terms#takedown')).toBe('https://livecontext.ai/legal/terms#takedown');
  });

  it('is idempotent for a URL already on the cloud origin', async () => {
    const { cloudWebUrl } = await importCloudWebUrl('ce');
    expect(cloudWebUrl('https://livecontext.ai/contact?category=bug')).toBe(
      'https://livecontext.ai/contact?category=bug',
    );
  });

  it('returns an empty string unchanged (no spurious cloud root)', async () => {
    const { cloudWebUrl } = await importCloudWebUrl('ce');
    expect(cloudWebUrl('')).toBe('');
  });

  it('passes opaque non-web schemes (mailto:, tel:) through untouched', async () => {
    const { cloudWebUrl } = await importCloudWebUrl('ce');
    expect(cloudWebUrl('mailto:abuse@livecontext.ai?subject=Report')).toBe(
      'mailto:abuse@livecontext.ai?subject=Report',
    );
    expect(cloudWebUrl('tel:+33123456789')).toBe('tel:+33123456789');
  });
});

describe('cloudWebUrl - cloud build is a no-op', () => {
  beforeEach(() => {
    vi.resetModules();
  });
  afterEach(() => {
    vi.unstubAllEnvs();
  });

  it('returns a relative path unchanged', async () => {
    const { cloudWebUrl } = await importCloudWebUrl('cloud');
    expect(cloudWebUrl('/contact?category=abuse&message=hello')).toBe(
      '/contact?category=abuse&message=hello',
    );
  });

  it('returns an absolute same-origin URL unchanged', async () => {
    const { cloudWebUrl } = await importCloudWebUrl('cloud');
    expect(cloudWebUrl('http://localhost:3000/en/app/marketplace/pub-1/preview')).toBe(
      'http://localhost:3000/en/app/marketplace/pub-1/preview',
    );
  });
});
