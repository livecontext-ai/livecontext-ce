import { apiClient } from './api-client';

/**
 * API catalog bundle distribution (cloud → CE), the API-integration twin of
 * the LLM model bundles in `model-config.service.ts`.
 *
 * Cloud admin: build + activate a signed bundle from the current API catalog.
 * CE admin: view sync status, force a manual sync tick.
 */
export interface ApiCatalogBundleSummary {
  id: number;
  version: number;
  schemaVersion: number;
  checksum: string;
  signingKeyId: string;
  issuer: string;
  apiCount: number;
  toolCount: number;
  rawBytesSize: number;
  importedAt: string;
  activatedAt: string | null;
  isActive: boolean;
}

export interface ApiCatalogBundleListResponse {
  bundles: ApiCatalogBundleSummary[];
}

export interface ApiCatalogBundleSyncStatus {
  lastAppliedVersion: number | null;
  lastAppliedAt: string | null;
  lastFetchAt: string | null;
  lastFetchStatus: string | null;
  lastFetchError: string | null;
  consecutiveFailures: number;
}

class CatalogBundlesService {
  async listBundles(): Promise<ApiCatalogBundleListResponse> {
    return apiClient.get<ApiCatalogBundleListResponse>('/catalog/bundles');
  }

  async buildBundle(): Promise<ApiCatalogBundleSummary> {
    return apiClient.post<ApiCatalogBundleSummary>('/catalog/bundles', {});
  }

  async activateBundle(id: number): Promise<ApiCatalogBundleSummary> {
    return apiClient.post<ApiCatalogBundleSummary>(`/catalog/bundles/${id}/activate`, {});
  }

  async getSyncStatus(): Promise<ApiCatalogBundleSyncStatus> {
    return apiClient.get<ApiCatalogBundleSyncStatus>('/catalog/bundles/sync-status');
  }

  async syncNow(): Promise<ApiCatalogBundleSyncStatus> {
    return apiClient.post<ApiCatalogBundleSyncStatus>('/catalog/bundles/sync-now', {});
  }
}

export const catalogBundlesService = new CatalogBundlesService();
