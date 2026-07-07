import { describe, it, expect, vi, beforeEach } from 'vitest';
import { cloudLinkService } from '../cloud-link.service';
import { apiClient } from '@/lib/api/api-client';

// Mock the apiClient module (same pattern as quota-api.service.test.ts)
vi.mock('@/lib/api/api-client', () => ({
  apiClient: {
    get: vi.fn(),
    post: vi.fn(),
    put: vi.fn(),
    delete: vi.fn(),
  },
}));

const mockGet = vi.mocked(apiClient.get);
const mockPut = vi.mocked(apiClient.put);

describe('CloudLinkService.getCloudUsageSummary', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('mirrors the bound cloud account usage summary from the cloud-link endpoint', async () => {
    const summary = {
      balance: 100,
      totalConsumedLast30Days: 1.23,
      breakdownByType: { CE_LLM_RELAY: { count: 5, credits: 1.23 } },
    };
    mockGet.mockResolvedValue(summary);

    const result = await cloudLinkService.getCloudUsageSummary();

    expect(result).toEqual(summary);
    expect(mockGet).toHaveBeenCalledWith('/cloud-link/usage-summary');
  });

  it('returns null when the cloud reports the summary unavailable (fall back to local)', async () => {
    mockGet.mockResolvedValue({ available: false });

    const result = await cloudLinkService.getCloudUsageSummary();

    expect(result).toBeNull();
  });

  it('returns null on an empty response', async () => {
    mockGet.mockResolvedValue(null);

    const result = await cloudLinkService.getCloudUsageSummary();

    expect(result).toBeNull();
  });
});

describe('CloudLinkService.getCloudUsageHistory', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('mirrors the cloud account paginated history forwarding only page/size (relay scope is server-side)', async () => {
    const pageData = { content: [], totalPages: 0, number: 0 };
    mockGet.mockResolvedValue(pageData);

    const result = await cloudLinkService.getCloudUsageHistory(2, 15);

    expect(result).toEqual(pageData);
    expect(mockGet).toHaveBeenCalledWith('/cloud-link/usage-history', {
      params: { page: '2', size: '15' },
    });
  });

  it('sends default page/size when called with no args', async () => {
    mockGet.mockResolvedValue({ content: [], totalPages: 0, number: 0 });

    await cloudLinkService.getCloudUsageHistory();

    expect(mockGet).toHaveBeenCalledWith('/cloud-link/usage-history', {
      params: { page: '0', size: '15' },
    });
  });

  it('returns null when the cloud reports it unavailable', async () => {
    mockGet.mockResolvedValue({ available: false });

    const result = await cloudLinkService.getCloudUsageHistory();

    expect(result).toBeNull();
  });
});

describe('CloudLinkService catalog (integration credential) source', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('reads the integration credential source from the catalog-source endpoint', async () => {
    mockGet.mockResolvedValue({ source: 'CLOUD' });

    const result = await cloudLinkService.getCatalogSource();

    expect(result).toBe('CLOUD');
    expect(mockGet).toHaveBeenCalledWith('/cloud-link/catalog-source');
  });

  it('saves the integration credential source via PUT and returns the persisted value', async () => {
    mockPut.mockResolvedValue({ source: 'BYOK' });

    const result = await cloudLinkService.setCatalogSource('BYOK');

    expect(result).toBe('BYOK');
    expect(mockPut).toHaveBeenCalledWith('/cloud-link/catalog-source', { source: 'BYOK' });
  });
});
