/**
 * Lifecycle calls on UserApiService: the endpoints and bodies the auth-service contract expects,
 * and an explicit-locale report that can never fail the language switch.
 */
import { describe, it, expect, vi, beforeEach } from 'vitest';

const clientMock = vi.hoisted(() => ({ get: vi.fn(), put: vi.fn() }));
vi.mock('../../api-client', () => ({ apiClient: clientMock }));

import { UserApiService } from '../user-api.service';

describe('UserApiService lifecycle calls', () => {
  const service = new UserApiService();

  beforeEach(() => {
    clientMock.get.mockReset();
    clientMock.put.mockReset();
  });

  it('PUTs the profile context as given', async () => {
    clientMock.put.mockResolvedValue(undefined);
    const payload = { locale: 'fr', localeExplicit: false, timeZone: 'Europe/Paris' };

    await service.reportProfileContext(payload);

    expect(clientMock.put).toHaveBeenCalledWith('/users/profile/context', payload);
  });

  it('reports an explicit locale with localeExplicit=true and swallows a failure', async () => {
    clientMock.put.mockRejectedValue(new Error('503'));

    await expect(service.reportExplicitLocale('zh')).resolves.toBeUndefined();
    expect(clientMock.put).toHaveBeenCalledWith('/users/profile/context', { locale: 'zh', localeExplicit: true });
  });

  it('reads and writes the marketing consent', async () => {
    clientMock.get.mockResolvedValue({ consent: true, updatedAt: null });
    clientMock.put.mockResolvedValue(undefined);

    await expect(service.getMarketingConsent()).resolves.toEqual({ consent: true, updatedAt: null });
    await service.setMarketingConsent(false);

    expect(clientMock.get).toHaveBeenCalledWith('/users/profile/marketing-consent');
    expect(clientMock.put).toHaveBeenCalledWith('/users/profile/marketing-consent', { consent: false });
  });
});
