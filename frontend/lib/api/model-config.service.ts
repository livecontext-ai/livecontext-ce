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
  /** Cloud-admin only (V493): whether a Free-plan grant may fund a turn on this model. */
  freeTierEnabled?: boolean;
  /**
   * V515: the model that replaces this one at execution time while it is disabled. Both
   * absent = the platform default model is used.
   */
  replacementProvider?: string;
  replacementModel?: string;
  rateLimitTpm?: number | null;
  rateLimitRpm?: number | null;
  rateLimitTpmPerTenant?: number | null;
  rateLimitRpmPerTenant?: number | null;
  /**
   * V156 - set per-row by the backend (`getEffectiveModelList`). One of
   * {@code 'cloud' | 'byok' | 'bridge'}. Used to hide bridges from the
   * browser_agent tab, where their rank has no runtime effect.
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
   * Admin panel only (`getEffectiveModelList`): the CLI bridge slug that could
   * EXECUTE this billed model verbatim (`anthropic` -> `claude-code`, `openai` ->
   * `codex`, `google` -> `gemini-cli`; `mistral` -> `mistral-vibe` is in the map but
   * matches nothing today, since that CLI's ids are local config aliases rather than
   * Mistral API ids). Present ONLY when that CLI routes this
   * model's family, so the Models panel can offer a one-click execution link the CLI
   * understands. Absent for every other model. It does not promise the binary on the
   * bridge host is new enough for a just-released id.
   */
  cliBridgeProvider?: string;
  /**
   * Could that CLI RUN on the bridge host right now? It is the strict signal: installed
   * AND logged in, since a logged-out CLI runs nothing. `null`/undefined = unknown
   * (bridge unreachable or URL unset). `false` is a warning, not a nuance: only an
   * entirely unwired bridge transport makes a linked run fall back to the billed
   * provider, so with the bridge up and the CLI unusable the run is dispatched and
   * fails.
   */
  cliBridgeAvailable?: boolean | null;
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
  /** True while a manual sync tick is in flight (survives a page navigation). */
  running?: boolean;
  /** When the in-flight sync started (ISO), null when idle. */
  startedAt?: string | null;
}

export interface ModelConfigOverrideInput {
  provider: string;
  /** Cloud-admin only (V381): what the CE bundle ships for this model. null resets to inherit. */
  bundleEnabled?: boolean | null;
  /** Cloud-admin only (V493): open/close this model to Free-plan grants. */
  freeTierEnabled?: boolean;
  /** V515: replacement while disabled. Both null clears it (back to the platform default). */
  replacementProvider?: string | null;
  replacementModel?: string | null;
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

/** A (provider, model id) pair, the key a retire / restore request names a model by. */
export interface ModelRef {
  provider: string;
  modelId: string;
}

/**
 * A retired model (V533). Retired models are gone from every picker and from the admin model
 * list; this is the only place they are listed, so they can be restored.
 */
export interface RetiredModelEntry {
  provider: string;
  modelId: string;
  displayName: string;
  providerKind?: 'cloud' | 'byok' | 'bridge' | string;
  /** ISO timestamp. */
  retiredAt: string;
  retiredBy: string | null;
  /** YYYY-MM-DD, when the catalog knows it. */
  releaseDate: string | null;
  /** YYYY-MM-DD, when the vendor announced one. */
  deprecationDate: string | null;
}

class ModelConfigService {
  /**
   * V156 - pass a category key (chat / browser_agent) to fetch the admin row
   * list with the per-category sidecar overlaid on top of the global fields.
   * Pass undefined for the legacy global view.
   *
   * The backend still answers for the retired `<format>_generation` keys, so
   * rows written under them stay readable; no screen sends one any more.
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

  /**
   * The providers switched off entirely. Only the exceptions: anything absent is on.
   *
   * The panel already knows which providers exist from the model list it just rendered, so
   * asking for the short list keeps one source for "which providers are there" and another
   * for "which are off", instead of two that can disagree.
   */
  async getDisabledProviders(): Promise<string[]> {
    return apiClient.get<string[]>('/model-config/providers-disabled');
  }

  /**
   * Switch a whole provider on or off. Every model it serves leaves the pickers at once, and
   * each model's own flag is left untouched, so switching it back on restores the curated
   * selection rather than turning everything on.
   */
  async setProviderEnabled(provider: string, enabled: boolean): Promise<void> {
    await apiClient.put(
      `/model-config/providers/${encodeURIComponent(provider)}/enabled`,
      { enabled },
    );
  }

  async deleteOverride(provider: string, modelId: string): Promise<void> {
    await apiClient.delete(`/model-config/overrides/${encodeURIComponent(provider)}/${encodeURIComponent(modelId)}`);
  }

  async resetAll(): Promise<void> {
    await apiClient.post('/model-config/reset', {});
  }

  // ── Retired models (V533) ────────────────────────────────────────────────
  // Admin: retire old models for good (no feed sync, seed or bundle brings them back) and
  // restore them from the retired list. A restored model comes back DISABLED.
  async listRetiredModels(): Promise<RetiredModelEntry[]> {
    return apiClient.get<RetiredModelEntry[]>('/model-config/retired');
  }

  /** Returns how many were newly retired (an already-retired model is not counted). */
  async retireModels(models: ModelRef[]): Promise<{ retired: number }> {
    return apiClient.post<{ retired: number }>('/model-config/retired', { models });
  }

  /** Returns how many were restored (a model that is not retired is not counted). */
  async restoreModels(models: ModelRef[]): Promise<{ restored: number }> {
    return apiClient.post<{ restored: number }>('/model-config/retired/restore', { models });
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

  /** Best-effort stop of an in-flight manual sync; returns the current status. */
  async syncCancel(): Promise<CatalogBundleSyncStatus> {
    return apiClient.post<CatalogBundleSyncStatus>('/model-config/bundles/sync-cancel', {});
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
    // Query-param form: a billed model id may contain '/' (OpenRouter ids like
    // meta-llama/llama-3.3-70b), which cannot travel as a path segment (%2F is
    // rejected by the backend URL firewall), so such links were undeletable.
    const params = new URLSearchParams({ billedProvider, billedModel, scope });
    await apiClient.delete(`/model-config/execution-links?${params.toString()}`);
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

/**
 * The third catalog source: each provider's own /models endpoint, which is the
 * only place a brand-new vendor model shows up on release day (the LiteLLM and
 * OpenRouter feeds mirror vendors on their own schedule and lag badly for some).
 *
 * `discoveredByProvider` rows are already counted inside `plan.added`. What the
 * breakdown adds is the part an admin must act on: those rows arrive WITHOUT a
 * price, because /models publishes none and an aggregator's rate is its resale
 * rate, not the vendor's. They stay disabled until priced.
 *
 * `skippedProviders` are the ones with no usable API key (or whose endpoint
 * errored) - they WERE asked and could not answer.
 *
 * `notAskedProviders` are the ones the pass never reached, because its time
 * budget ran out first. Deliberately separate from `skippedProviders`: a
 * vendor nobody called has not failed, and showing it as failed sends an admin
 * to debug a healthy provider. They go first on the next run.
 */
export interface CatalogSyncDiscovery {
  models: Array<Record<string, unknown>>;
  discoveredByProvider: Record<string, number>;
  skippedProviders: string[];
  notAskedProviders: string[];
}

export interface CatalogSyncPlan {
  stats: CatalogSyncFeedStats;
  added: Array<Record<string, unknown>>;
  updated: Array<Record<string, unknown>>;
  unchanged: number;
  flagged: CatalogSyncFlaggedRow[];
  guardFailures: CatalogSyncGuardFailure[];
  discovery: CatalogSyncDiscovery;
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
