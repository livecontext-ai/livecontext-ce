// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

const deleteMock = vi.fn();

vi.mock('../api-client', () => ({
  apiClient: {
    getTokenProvider: () => async () => 'tok-123',
    delete: (...a: unknown[]) => deleteMock(...a),
  },
}));
vi.mock('@/lib/stores/current-org-store', () => ({
  getActiveOrgHeaderForRequest: () => ({ 'X-Active-Organization-ID': 'org-1' }),
}));

import { organizationApi } from '../organization-api';

describe('organizationApi workspace avatar', () => {
  beforeEach(() => {
    deleteMock.mockReset();
  });
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it('uploadAvatar POSTs multipart through the proxy with the bearer token and returns the result', async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      json: async () => ({ storageId: 's1', avatarUrl: '/api/organizations/org-1/avatar?v=s1' }),
    });
    vi.stubGlobal('fetch', fetchMock);

    const file = new File(['bytes'], 'logo.png', { type: 'image/png' });
    const result = await organizationApi.uploadAvatar('org-1', file);

    expect(result).toEqual({ storageId: 's1', avatarUrl: '/api/organizations/org-1/avatar?v=s1' });
    const [url, init] = fetchMock.mock.calls[0];
    expect(url).toBe('/api/proxy/organizations/org-1/avatar');
    expect(init.method).toBe('POST');
    expect(init.headers.Authorization).toBe('Bearer tok-123');
    expect(init.headers['X-Active-Organization-ID']).toBe('org-1');
    expect(init.body).toBeInstanceOf(FormData);
  });

  it('uploadAvatar surfaces a friendly message on 413 (too large)', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: false, status: 413, json: async () => ({}) }));
    const file = new File(['x'], 'big.png', { type: 'image/png' });
    await expect(organizationApi.uploadAvatar('org-1', file)).rejects.toThrow('Image too large');
  });

  it('uploadAvatar surfaces the server error message on other failures', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({
      ok: false,
      status: 400,
      json: async () => ({ error: 'Unsupported image type: application/pdf' }),
    }));
    const file = new File(['x'], 'x.pdf', { type: 'application/pdf' });
    await expect(organizationApi.uploadAvatar('org-1', file)).rejects.toThrow('Unsupported image type');
  });

  it('deleteAvatar calls DELETE on the org avatar endpoint', async () => {
    await organizationApi.deleteAvatar('org-1');
    expect(deleteMock).toHaveBeenCalledWith('/organizations/org-1/avatar');
  });
});
