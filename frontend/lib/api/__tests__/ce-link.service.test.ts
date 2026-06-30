import { afterEach, describe, expect, it, vi } from 'vitest';
import { apiClient } from '../api-client';
import { ceLinkService } from '../ce-link.service';

vi.mock('../api-client', () => ({
  apiClient: {
    get: vi.fn(),
    post: vi.fn(),
    delete: vi.fn(),
  },
}));

afterEach(() => {
  vi.clearAllMocks();
});

describe('ceLinkService.mine', () => {
  it('GETs /ce-link/mine with default pagination', async () => {
    (apiClient.get as ReturnType<typeof vi.fn>).mockResolvedValue({
      content: [],
      totalElements: 0,
      totalPages: 0,
      number: 0,
      size: 20,
    });

    await ceLinkService.mine();

    expect(apiClient.get).toHaveBeenCalledWith('/ce-link/mine', {
      params: { page: '0', size: '20' },
    });
  });

  it('forwards custom page + size as strings (gateway query-param contract)', async () => {
    (apiClient.get as ReturnType<typeof vi.fn>).mockResolvedValue({
      content: [], totalElements: 0, totalPages: 0, number: 2, size: 5,
    });

    await ceLinkService.mine(2, 5);

    expect(apiClient.get).toHaveBeenCalledWith('/ce-link/mine', {
      params: { page: '2', size: '5' },
    });
  });
});

describe('ceLinkService.revoke', () => {
  it('DELETEs /ce-link/{installId}', async () => {
    (apiClient.delete as ReturnType<typeof vi.fn>).mockResolvedValue(undefined);

    await ceLinkService.revoke('11111111-2222-3333-4444-555555555555');

    expect(apiClient.delete).toHaveBeenCalledWith(
      '/ce-link/11111111-2222-3333-4444-555555555555',
    );
  });
});

describe('ceLinkService.consumeRecovery', () => {
  it('URL-encodes the token to keep `+` / `=` / `/` safe in the path', async () => {
    (apiClient.post as ReturnType<typeof vi.fn>).mockResolvedValue({});

    await ceLinkService.consumeRecovery('tok+en/with=symbols');

    expect(apiClient.post).toHaveBeenCalledWith(
      `/ce-link/squat-recovery/${encodeURIComponent('tok+en/with=symbols')}`,
      {},
      { skipAuth: true },
    );
  });

  it('POSTs an empty body - server reads the token from the path, not the body', async () => {
    (apiClient.post as ReturnType<typeof vi.fn>).mockResolvedValue({});

    await ceLinkService.consumeRecovery('plain-token');

    expect(apiClient.post).toHaveBeenCalledWith('/ce-link/squat-recovery/plain-token', {}, {
      skipAuth: true,
    });
  });

  it('does not require an app session because the recovery token is the credential', async () => {
    (apiClient.post as ReturnType<typeof vi.fn>).mockResolvedValue({});

    await ceLinkService.consumeRecovery('public-token');

    expect(apiClient.post).toHaveBeenCalledWith('/ce-link/squat-recovery/public-token', {}, {
      skipAuth: true,
    });
  });
});
