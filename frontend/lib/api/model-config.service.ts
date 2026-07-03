import { apiClient } from './api-client';

export interface ModelConfigEntry {
  id: string;
  name: string;
  provider: string;
  isDefault?: boolean;
  displayOrder: number;
  pricing?: { input: number; output: number };
  tier?: string;
  recommended?: boolean;
  hasOverride?: boolean;
  isCustom?: boolean;
  enabled?: boolean;
  /**
   * Admin panel only (`getEffectiveModelList`): would the picker/runtime offer
   * this model under the tenant's LLM source? The admin panel lists the FULL
   * catalog (keyed or not) so every model can be ranked/priced; `available:false`
   * marks a model shown for configuration but not yet usable (no key). Absent =
   * treat as available (back-compat). Bridge rows use `bridgeAvailable` instead.
   */
  available?: boolean;
  /** Cloud-admin bundle override (V381): what the CE bundle ships. null/undefined = inherits enabled. */
  bundleEnabled?: boolean | null;
  rateLimitTpm?: number | null;
  rateLimitRpm?: number | null;
  rateLimitTpmPerTenant?: number | null;
  rateLimitRpmPerTenant?: number | null;
  /**
   * V156 - set per-row by the backend (`getEffectiveModelList`). One of
   * {@code 'cloud' | 'byok' | 'bridge'}. Used to hide bridges from category
   * tabs (browser_agent / image_generation) where their rank has no runtime
   * effect.
   */
  providerKind?: 'cloud' | 'byok' | 'bridge';
  // V125-enriched fields surfaced via ModelCatalogService.applyEnrichmentFields
  contextWindow?: number;
  maxOutputTokens?: number;
  supportsTools?: boolean;
  supportsVision?: boolean;
  supportsPromptCaching?: boolean;
  supportsReasoning?: boolean;
  supportsComputerUse?: boolean;
  supportsResponseSchema?: boolean;
  supportsWebSearch?: boolean;
  /**
   * Per-model admin default reasoning effort for CLI/bridge providers
   * (minimal|low|medium|high|xhigh). Lowest-precedence fallback below the
   * per-agent setting and the per-conversation chat-selector override.
   */
  defaultReasoningEffort?: string;
  mode?: string;
  priceInputBatch?: number;
  priceOutputBatch?: number;
  priceCacheRead?: number;
  priceCacheWrite?: number;
  deprecatedAt?: string;
  deprecationDate?: string;
  releaseDate?: string;
}

export interface CatalogBundleSummary {
  id: number;
  version: number;
  schemaVersion: number;
  checksum: string;
  signingKeyId: string;
  issuer: string;
  modelCount: number;
  rawBytesSize: number;
  isActive: boolean;
  importedAt: string;
  activatedAt: string | null;
}

export interface CatalogBundleListResponse {
  bundles: CatalogBundleSummary[];
  signingKeyId: string | null;
  publicKeyBase64: string | null;
}

export interface CatalogBundleSyncStatus {
  lastAppliedVersion: number | null;
  lastAppliedAt: string | null;
  lastFetchAt: string | null;
  lastFetchStatus: string | null;
  lastFetchError: string | null;
  consecutiveFailures: number;
  updatedAt: string | null;
  schedulerEnabled: boolean;
}

export interface ModelConfigOverrideInput {
  provider: string;
  /** Cloud-admin only (V381): what the CE bundle ships for this model. null resets to inherit. */
  bundleEnabled?: boolean | null;
  modelId: string;
  enabled?: boolean;
  displayName?: string;
  tier?: string;
  ranking?: number;
  recommended?: boolean;
  priceInput?: number;
  priceOutput?: number;
  isCustom?: boolean;
  rateLimitTpm?: number | null;
  rateLimitRpm?: number | null;
  rateLimitTpmPerTenant?: number | null;
  rateLimitRpmPerTenant?: number | null;
  /** minimal|low|medium|high|xhigh, or "" to clear (revert to no default). */
  defaultReasoningEffort?: string;
}

/**
 * Model execution link (CLOUD only). Decouples a model's BILLING identity from
 * its EXECUTION transport: {@code billedProvider/billedModel} keeps its
 * price/identity while the run is executed through {@code executionProvider} -
 * which may be a CLI bridge (claude-code | codex | gemini-cli | mistral-vibe) OR
 * a regular API provider (e.g. openrouter). {@code executionModel} null ⇒ reuse
 * the billed model id.
 */
/**
 * App surface a link applies to (mirrors the backend {@code ModelExecutionLinkScope}):
 * {@code ALL} (wildcard / default), {@code CHAT}, {@code WORKFLOW}, {@code WEBHOOK},
 * {@code WIDGET}, {@code SCHEDULE}, {@code TASK}, {@code TASK_REVIEW}.
 */
export type ModelExecutionLinkScope =
  | 'ALL' | 'CHAT' | 'WORKFLOW' | 'WEBHOOK' | 'WIDGET' | 'SCHEDULE' | 'TASK' | 'TASK_REVIEW';

export interface ModelExecutionLink {
  id?: number;
  billedProvider: string;
  billedModel: string;
  executionProvider: string;
  executionModel?: string | null;
  /** Surface the link is scoped to; absent ⇒ ALL (applies everywhere). */
  scope?: ModelExecutionLinkScope;
  enabled?: boolean;
}

class ModelConfigService {
  /**
   * V156 - pass a category key (chat / browser_agent / image_generation)
   * to fetch the admin row list with the per-category sidecar overlaid on
   * top of the global fields. Pass undefined for the legacy global view.
   */
  async getEffectiveModels(category?: string): Promise<ModelConfigEntry[]> {
    const url = category
      ? `/model-config?category=${encodeURIComponent(category)}`
      : '/model-config';
    return apiClient.get<ModelConfigEntry[]>(url);
  }

  async saveOverride(override: ModelConfigOverrideInput): Promise<{ id: number; provider: string; modelId: string }> {
    return apiClient.put('/model-config/overrides', override);
  }

  async bulkUpdateRankings(
    rankings: { provider: string; modelId: string; ranking: number }[],
    category?: string,
  ): Promise<{ updated: number }> {
    const url = category
      ? `/model-config/overrides/rankings?category=${encodeURIComponent(category)}`
      : '/model-config/overrides/rankings';
    return apiClient.put(url, rankings);
  }

  /**
   * V156 - per-category enable/disable for a single model. The same model
   * can be enabled in chat but disabled in browser_agent.
   */
  async setCategoryEnabled(
    provider: string,
    modelId: string,
    category: string,
    enabled: boolean,
  ): Promise<{ success: boolean }> {
    return apiClient.put(
      `/model-config/overrides/${encodeURIComponent(provider)}/${encodeURIComponent(modelId)}/category-enabled`,
      { category, enabled },
    );
  }

  async deleteOverride(provider: string, modelId: string): Promise<void> {
    await apiClient.delete(`/model-config/overrides/${encodeURIComponent(provider)}/${encodeURIComponent(modelId)}`);
  }

  async resetAll(): Promise<void> {
    await apiClient.post('/model-config/reset', {});
  }

  // ── Catalog Bundles ──────────────────────────────────────────────────────
  // Cloud admin: build + activate a signed bundle from the current catalog.
  // CE admin: view sync status, force a manual sync tick.
  async listBundles(): Promise<CatalogBundleListResponse> {
    return apiClient.get<CatalogBundleListResponse>('/model-config/bundles');
  }

  async buildBundle(): Promise<CatalogBundleSummary> {
    return apiClient.post<CatalogBundleSummary>('/model-config/bundles', {});
  }

  async activateBundle(id: number): Promise<CatalogBundleSummary> {
    return apiClient.post<CatalogBundleSummary>(`/model-config/bundles/${id}/activate`, {});
  }

  async getSyncStatus(): Promise<CatalogBundleSyncStatus> {
    return apiClient.get<CatalogBundleSyncStatus>('/model-config/bundles/sync-status');
  }

  async syncNow(): Promise<CatalogBundleSyncStatus> {
    return apiClient.post<CatalogBundleSyncStatus>('/model-config/bundles/sync-now', {});
  }

  async deleteBundle(id: number): Promise<void> {
    await apiClient.delete(`/model-config/bundles/${id}`);
  }

  // ── Catalog Sync (LiteLLM + OpenRouter) ──────────────────────────────────
  // Cloud admin: fetch live feeds, diff against current catalog, apply.
  async catalogSyncDryRun(): Promise<CatalogSyncResult> {
    return apiClient.post<CatalogSyncResult>('/model-config/catalog-sync?mode=dry-run', {});
  }

  async catalogSyncApply(overrideGuards: string[] = []): Promise<CatalogSyncResult> {
    const qs = overrideGuards.length
      ? `&overrideGuards=${encodeURIComponent(overrideGuards.join(','))}`
      : '';
    return apiClient.post<CatalogSyncResult>(`/model-config/catalog-sync?mode=apply${qs}`, {});
  }

  async catalogSyncHistory(limit = 25): Promise<CatalogSyncLogEntry[]> {
    return apiClient.get<CatalogSyncLogEntry[]>(`/model-config/catalog-sync/history?limit=${limit}`);
  }

  // ── Model Execution Links (CLOUD only) ───────────────────────────────────
  // Cloud admin: map a billed (provider, model) to a CLI-bridge execution
  // transport while keeping the billed price. Endpoints 404 in CE (the backend
  // controller is not loaded there).
  async listExecutionLinks(): Promise<ModelExecutionLink[]> {
    return apiClient.get<ModelExecutionLink[]>('/model-config/execution-links');
  }

  async saveExecutionLink(link: ModelExecutionLink): Promise<ModelExecutionLink> {
    return apiClient.put<ModelExecutionLink>('/model-config/execution-links', link);
  }

  async deleteExecutionLink(
    billedProvider: string,
    billedModel: string,
    scope: ModelExecutionLinkScope = 'ALL',
  ): Promise<void> {
    await apiClient.delete(
      `/model-config/execution-links/${encodeURIComponent(billedProvider)}/${encodeURIComponent(billedModel)}/${encodeURIComponent(scope)}`,
    );
  }
}

export const modelConfigService = new ModelConfigService();

// ── Catalog Sync types ─────────────────────────────────────────────────────

export interface CatalogSyncFlaggedRow {
  provider: string;
  modelId: string;
  reason: string;
  oldPriceInput?: number | string | null;
  newPriceInput?: number | string | null;
  oldPriceOutput?: number | string | null;
  newPriceOutput?: number | string | null;
}

export interface CatalogSyncGuardFailure {
  guard: string;
  detail: string;
  data: Record<string, unknown>;
}

export interface CatalogSyncFeedStats {
  liteLlmKept: number;
  openRouterKept: number;
  liteLlmRejected: Record<string, number>;
  openRouterRejected: Record<string, number>;
}

export interface CatalogSyncPlan {
  stats: CatalogSyncFeedStats;
  added: Array<Record<string, unknown>>;
  updated: Array<Record<string, unknown>>;
  unchanged: number;
  flagged: CatalogSyncFlaggedRow[];
  guardFailures: CatalogSyncGuardFailure[];
}

export interface CatalogSyncResult {
  plan: CatalogSyncPlan;
  applied: boolean;
  inserted: number;
  updatedCount: number;
  deprecated: number;
  syncLogId: number | null;
}

export interface CatalogSyncLogEntry {
  id: number;
  source: 'litellm' | 'openrouter' | 'both' | 'none';
  fetchedAt: string;
  modelCount: number;
  checksum: string | null;
  triggeredBy: string;
  dryRun: boolean;
  outcome: 'OK' | 'ABORTED_GUARD' | 'FETCH_ERROR' | 'SCHEMA_ERROR' | 'APPLY_ERROR';
  errorDetail: string | null;
  guardFailures: Record<string, unknown> | null;
  addedCount: number;
  updatedCount: number;
  deprecatedCount: number;
  flaggedCount: number;
  createdAt: string;
}
