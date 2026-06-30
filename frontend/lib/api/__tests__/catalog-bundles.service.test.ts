import { describe, it, expect, vi, beforeEach } from 'vitest';
import {
  catalogBundlesService,
  type ApiCatalogBundleSummary,
  type ApiCatalogBundleSyncStatus,
} from '../catalog-bundles.service';
import { apiClient } from '@/lib/api/api-client';

// Mock the apiClient module (same pattern as cloud-link.service.test.ts)
vi.mock('@/lib/api/api-client', () => ({
  apiClient: {
    get: vi.fn(),
    post: vi.fn(),
    put: vi.fn(),
    delete: vi.fn(),
  },
}));

const mockGet = vi.mocked(apiClient.get);
const mockPost = vi.mocked(apiClient.post);

const bundle: ApiCatalogBundleSummary = {
  id: 7,
  version: 3,
  schemaVersion: 1,
  checksum: 'abc123',
  signingKeyId: 'key-1',
  issuer: 'cloud',
  apiCount: 612,
  toolCount: 4810,
  rawBytesSize: 1048576,
  importedAt: '2026-06-10T10:00:00Z',
  activatedAt: null,
  isActive: false,
};

const syncStatus: ApiCatalogBundleSyncStatus = {
  lastAppliedVersion: 2,
  lastAppliedAt: '2026-06-10T09:00:00Z',
  lastFetchAt: '2026-06-10T09:30:00Z',
  lastFetchStatus: 'OK',
  lastFetchError: null,
  consecutiveFailures: 0,
};

describe('CatalogBundlesService', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('lists API catalog bundles from GET /catalog/bundles', async () => {
    mockGet.mockResolvedValue({ bundles: [bundle] });

    const result = await catalogBundlesService.listBundles();

    expect(result.bundles).toEqual([bundle]);
    expect(mockGet).toHaveBeenCalledWith('/catalog/bundles');
  });

  it('builds a new bundle with POST /catalog/bundles and returns its summary', async () => {
    mockPost.mockResolvedValue(bundle);

    const result = await catalogBundlesService.buildBundle();

    expect(result).toEqual(bundle);
    expect(mockPost).toHaveBeenCalledWith('/catalog/bundles', {});
  });

  it('activates a bundle by id with POST /catalog/bundles/{id}/activate', async () => {
    const activated = { ...bundle, isActive: true, activatedAt: '2026-06-10T11:00:00Z' };
    mockPost.mockResolvedValue(activated);

    const result = await catalogBundlesService.activateBundle(7);

    expect(result).toEqual(activated);
    expect(mockPost).toHaveBeenCalledWith('/catalog/bundles/7/activate', {});
  });

  it('reads the CE sync status from GET /catalog/bundles/sync-status', async () => {
    mockGet.mockResolvedValue(syncStatus);

    const result = await catalogBundlesService.getSyncStatus();

    expect(result).toEqual(syncStatus);
    expect(mockGet).toHaveBeenCalledWith('/catalog/bundles/sync-status');
  });

  it('forces a sync tick with POST /catalog/bundles/sync-now and returns the fresh status', async () => {
    mockPost.mockResolvedValue(syncStatus);

    const result = await catalogBundlesService.syncNow();

    expect(result).toEqual(syncStatus);
    expect(mockPost).toHaveBeenCalledWith('/catalog/bundles/sync-now', {});
  });

  it('propagates API errors to the caller (no swallowing in the service layer)', async () => {
    mockPost.mockRejectedValue(new Error('signing key unavailable'));

    await expect(catalogBundlesService.buildBundle()).rejects.toThrow('signing key unavailable');
  });
});
