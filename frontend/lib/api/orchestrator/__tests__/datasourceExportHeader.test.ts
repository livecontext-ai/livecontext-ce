import { describe, it, expect, vi, beforeEach } from 'vitest';

// Mock the active-org helper: export must attach the workspace header like every other raw fetch.
vi.mock('@/lib/stores/current-org-store', () => ({
  getActiveOrgHeaderForRequest: () => ({ 'X-Active-Organization-ID': 'org-active-123' }),
}));

vi.mock('../../api-client', () => ({
  apiClient: { getTokenProvider: () => async () => 'tok-abc' },
  ApiError: class ApiError extends Error {
    status: number;
    constructor(message: string, status: number) {
      super(message);
      this.status = status;
    }
  },
}));

import { dataSourceService } from '../datasource.service';

describe('DataSourceService.exportDataSource', () => {
  beforeEach(() => {
    vi.restoreAllMocks();
  });

  it('sends the X-Active-Organization-ID header so export resolves under the active workspace', async () => {
    const fetchMock = vi.fn(async () => ({
      ok: true,
      blob: async () => new Blob(['x']),
    })) as unknown as typeof fetch;
    vi.stubGlobal('fetch', fetchMock);

    await dataSourceService.exportDataSource('ds-1', { format: 'csv' });

    expect(fetchMock).toHaveBeenCalledTimes(1);
    const [, init] = (fetchMock as unknown as ReturnType<typeof vi.fn>).mock.calls[0];
    const headers = (init as RequestInit).headers as Record<string, string>;
    // Regression: pre-fix the raw fetch sent only Authorization, so the gateway used the DEFAULT org.
    expect(headers['X-Active-Organization-ID']).toBe('org-active-123');
    expect(headers['Authorization']).toBe('Bearer tok-abc');
  });
});
