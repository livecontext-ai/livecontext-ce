/**
 * "Connect to Cloud" goes through ONE helper: the cloud onboarding entry (startUrl) when the
 * backend offers it, else the bare Keycloak authorization (authUrl, older backend). The source
 * guard at the end pins that every caller uses it, since a caller reading `authUrl` directly
 * would skip the cloud onboarding and the paid-plan step without any test noticing.
 */
import fs from 'node:fs';
import path from 'node:path';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { apiClient } from '@/lib/api/api-client';
import { cloudLinkService, resolveConnectUrl } from '../cloud-link.service';

vi.mock('@/lib/api/api-client', () => ({
  apiClient: { get: vi.fn(), post: vi.fn(), put: vi.fn(), delete: vi.fn() },
}));

const mockGet = vi.mocked(apiClient.get);

describe('resolveConnectUrl', () => {
  it('prefers the cloud onboarding startUrl', () => {
    expect(
      resolveConnectUrl({
        authUrl: 'https://kc.example/auth',
        state: 's',
        startUrl: 'https://livecontext.ai/onboarding?ce_link=1',
      }),
    ).toBe('https://livecontext.ai/onboarding?ce_link=1');
  });

  it('falls back to authUrl when the backend sends no startUrl (older backend)', () => {
    expect(resolveConnectUrl({ authUrl: 'https://kc.example/auth', state: 's' })).toBe('https://kc.example/auth');
  });

  it('falls back to authUrl when startUrl is blank', () => {
    expect(resolveConnectUrl({ authUrl: 'https://kc.example/auth', state: 's', startUrl: '' })).toBe(
      'https://kc.example/auth',
    );
  });
});

describe('CloudLinkService.getConnectUrl', () => {
  beforeEach(() => vi.clearAllMocks());

  it('asks the auth-url endpoint with the returnPath and returns the resolved URL', async () => {
    mockGet.mockResolvedValue({
      authUrl: 'https://kc.example/auth',
      state: 's',
      startUrl: 'https://livecontext.ai/onboarding?ce_link=1',
    });

    await expect(cloudLinkService.getConnectUrl('/en/ce-setup')).resolves.toBe(
      'https://livecontext.ai/onboarding?ce_link=1',
    );
    expect(mockGet).toHaveBeenCalledWith('/cloud-link/auth-url', { params: { returnPath: '/en/ce-setup' } });
  });

  it('propagates a failure so the caller can show its connect error', async () => {
    mockGet.mockRejectedValue(new Error('boom'));
    await expect(cloudLinkService.getConnectUrl()).rejects.toThrow('boom');
  });
});

describe('every Connect-to-Cloud caller uses the one helper', () => {
  const CALLERS = [
    'app/[locale]/ce-setup/page.tsx',
    'app/[locale]/app/settings/cloud-account/page.tsx',
    'components/ai/NoProviderCta.tsx',
    'app/[locale]/app/marketplace/page.tsx',
  ];

  it.each(CALLERS)('%s navigates through getConnectUrl, never a raw authUrl', (file) => {
    const source = fs.readFileSync(path.join(process.cwd(), file), 'utf8');
    expect(source).toContain('cloudLinkService.getConnectUrl(');
    expect(source).not.toContain('cloudLinkService.getAuthUrl(');
  });
});
