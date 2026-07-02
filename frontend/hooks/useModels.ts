'use client';

import { useState, useEffect, useCallback, useMemo } from 'react';
import { apiClient } from '@/lib/api';
import { useOptionalAuth } from '@/lib/providers/smart-providers';

/**
 * Capabilities a model exposes. A model with no {@code capabilities}
 * field is treated as {@code ['chat']} by {@link modelHasCapability}.
 * Image-generation models declare {@code ['image']} (no chat fallback).
 */
export type ModelCapability = 'chat' | 'image';

export interface AIModel {
  id: string;
  name: string;
  provider: string;
  isDefault?: boolean;
  displayOrder?: number;
  tier?: 'top' | 'high' | 'mid' | 'budget';
  recommended?: boolean;
  pricing?: {
    input: number;
    output: number;
  };
  /**
   * Optional capability list. When absent, callers default to
   * {@code ['chat']} via {@link modelHasCapability}. The backend
   * doesn't yet emit this field - the frontend tags known image-gen
   * model ids client-side via {@link IMAGE_MODEL_IDS} so the
   * {@code ModelPicker filterCapability='image'} surface can find them.
   */
  capabilities?: ModelCapability[];
  // ── Enriched fields (V125 columns surfaced by ModelCatalogService.applyEnrichmentFields) ──
  // All optional - every field can be null when the catalog row was seeded
  // from YAML only (no DB override) or sync-fed without that attribute set.
  contextWindow?: number;
  maxOutputTokens?: number;
  supportsTools?: boolean;
  supportsVision?: boolean;
  supportsPromptCaching?: boolean;
  supportsReasoning?: boolean;
  supportsComputerUse?: boolean;
  supportsResponseSchema?: boolean;
  supportsWebSearch?: boolean;
  mode?: string;
  priceInputBatch?: number;
  priceOutputBatch?: number;
  priceCacheRead?: number;
  priceCacheWrite?: number;
  rateLimitTpm?: number | null;
  rateLimitRpm?: number | null;
  rateLimitTpmPerTenant?: number | null;
  rateLimitRpmPerTenant?: number | null;
  providerKind?: 'cloud' | 'byok' | 'bridge';
  /** ISO-8601 instant. When set, model is end-of-life; UI should warn. */
  deprecatedAt?: string;
  /** ISO date (YYYY-MM-DD) - provider-published EOL date. */
  deprecationDate?: string;
  releaseDate?: string;
}

/**
 * Hardcoded mirror of {@code ImageProviderCatalog.OPENAI_MODEL_FAMILIES}
 * + {@code ImageProviderCatalog.GOOGLE_MODELS} from the backend.
 *
 * <p><b>Strict alignment</b> &mdash; this set MUST match the
 * Java-side {@code ImageProviderCatalog.OPENAI_MODEL_FAMILIES}
 * ({@code agent-common/.../imagegen/ImageProviderCatalog.java}) and
 * {@code GOOGLE_MODELS} verbatim. Adding a model here that the backend
 * doesn't accept makes the picker offer a model the runtime rejects with
 * {@code INVALID_REQUEST}. Legacy {@code dall-e-*} / {@code gpt-image-1}
 * are intentionally absent - the backend module rejects them.
 *
 * <p>Source of truth lives Java-side; this set is a UI tag for filtering
 * until the backend exposes capabilities on the {@code /v3/chat/models}
 * payload.
 */
export const IMAGE_MODEL_IDS: ReadonlySet<string> = new Set([
  // OpenAI - must match ImageProviderCatalog.OPENAI_MODEL_FAMILIES
  'gpt-image-1.5',
  'gpt-image-1-mini',
  // Google - must match ImageProviderCatalog.GOOGLE_MODELS
  'gemini-2.5-flash-image',
  'gemini-3-pro-image',
]);

/** Tag every model with a default capability list when the backend doesn't supply one. */
function deriveCapabilities(model: AIModel): ModelCapability[] {
  if (model.capabilities && model.capabilities.length > 0) return model.capabilities;
  if (IMAGE_MODEL_IDS.has(model.id)) return ['image'];
  return ['chat'];
}

/** True iff the model supports the requested capability. */
export function modelHasCapability(model: AIModel, capability: ModelCapability): boolean {
  return deriveCapabilities(model).includes(capability);
}

/**
 * The four CLI-bridge providers. They run on the platform admin's shared CLI
 * subscription (Claude Code / Codex / Gemini CLI / Mistral Vibe) and are
 * admin-gated in auth-service - non-admin users are blocked from dispatching
 * them. Mirrors the backend {@code BridgeAvailabilityFilter.BRIDGE_PROVIDER_TO_CLI_ID}
 * key set.
 */
export const BRIDGE_PROVIDER_NAMES: ReadonlySet<string> = new Set([
  'claude-code',
  'codex',
  'gemini-cli',
  'mistral-vibe',
]);

/**
 * True iff the model is served through a CLI bridge. Prefers the
 * backend-supplied {@code providerKind === 'bridge'} flag and falls back to
 * the provider name so a row that predates the flag is still recognised.
 */
export function isBridgeModel(model: Pick<AIModel, 'providerKind' | 'provider'>): boolean {
  if (model.providerKind === 'bridge') return true;
  return BRIDGE_PROVIDER_NAMES.has((model.provider ?? '').toLowerCase());
}

export interface AIProvider {
  name: string;
  defaultModel: string;
  supportsStreaming: boolean;
  supportsToolCalling: boolean;
  displayOrder?: number;
  models: AIModel[];
}

export interface ModelsData {
  providers: AIProvider[];
  defaultProvider: string | null;
  defaultModel: string | null;
}

interface UseModelsResult {
  models: AIModel[];
  providers: AIProvider[];
  defaultModel: string | null;
  defaultProvider: string | null;
  isLoading: boolean;
  error: string | null;
  refresh: () => Promise<void>;
}

// Cache the models data to avoid refetching on every mount
let modelsCache: ModelsData | null = null;
let cacheTimestamp: number = 0;
let modelsRequest: Promise<ModelsData> | null = null;
const CACHE_TTL = 5 * 60 * 1000; // 5 minutes

/** Clear the models cache so the next useModels() call refetches from the server. */
export function clearModelsCache() {
  modelsCache = null;
  cacheTimestamp = 0;
  modelsRequest = null;
}

export function useModels(): UseModelsResult {
  const [data, setData] = useState<ModelsData | null>(modelsCache);
  const [isLoading, setIsLoading] = useState(!modelsCache);
  const [error, setError] = useState<string | null>(null);

  const fetchModels = useCallback(async (force: boolean = false) => {
    // Check cache
    if (!force && modelsCache && Date.now() - cacheTimestamp < CACHE_TTL) {
      setData(modelsCache);
      setIsLoading(false);
      return;
    }

    setIsLoading(true);
    setError(null);

    try {
      const response = await getModelsOnce(force);
      modelsCache = response;
      cacheTimestamp = Date.now();
      setData(response);
    } catch (err) {
      console.error('Failed to fetch models:', err);
      setError(err instanceof Error ? err.message : 'Failed to fetch models');
      setData(null);
    } finally {
      setIsLoading(false);
    }
  }, []);

  useEffect(() => {
    fetchModels();
  }, [fetchModels]);

  // Sort providers by displayOrder (ascending - lower displayOrder = first/better)
  const sortedProviders = (data?.providers ?? [])
    .slice()
    .sort((a, b) => (a.displayOrder ?? 999) - (b.displayOrder ?? 999));

  // Flatten all models and sort globally by displayOrder (ascending).
  // Lower displayOrder = better model (like LMArena rank 1, 2, 3...).
  //
  // Dedupe by (provider, id): since V128 removed the "-cc" suffix, bridge ids
  // match their cloud counterpart exactly (e.g. claude-code/claude-opus-4-7
  // and anthropic/claude-opus-4-7). Deduping by id ALONE would collapse them
  // and hide whichever has the worse displayOrder - the user loses the
  // ability to pick the bridge path explicitly. Keep both so the selector
  // can render each with its own icon (claude-code.svg vs anthropic.svg)
  // and the user sees "Codex: gpt-5.4" alongside "OpenAI: gpt-5.4".
  const flat = (data?.providers?.flatMap(p =>
    p.models.map(m => ({ ...m, provider: m.provider ?? p.name }))
  ) ?? [])
    .slice()
    .sort((a, b) => (a.displayOrder ?? 999) - (b.displayOrder ?? 999));
  const seenKeys = new Set<string>();
  const allModels: AIModel[] = [];
  for (const m of flat) {
    const key = `${m.provider}:${m.id}`;
    if (seenKeys.has(key)) continue;
    seenKeys.add(key);
    allModels.push(m);
  }

  return {
    models: allModels,
    providers: sortedProviders,
    defaultModel: data?.defaultModel ?? null,
    defaultProvider: data?.defaultProvider ?? null,
    isLoading,
    error,
    refresh: () => fetchModels(true)
  };
}

async function getModelsOnce(force: boolean): Promise<ModelsData> {
  if (!force && modelsRequest) {
    return modelsRequest;
  }

  const request = (async () => {
    // Send auth when a token is available so the backend can resolve the
    // per-tenant LLM source (CLOUD vs BYOK) and return the matching catalog
    // (a CLOUD-linked CE must see the cloud models). Stays anonymous
    // (skipAuth) pre-login so public surfaces keep working without a token.
    const tokenProvider = apiClient.getTokenProvider();
    const token = tokenProvider ? await tokenProvider().catch(() => null) : null;
    return apiClient.get<ModelsData>('/v3/chat/models', { skipAuth: !token });
  })();
  modelsRequest = request.finally(() => {
    if (modelsRequest === request) {
      modelsRequest = null;
    }
  });
  return modelsRequest;
}

/**
 * Pure role filter behind {@link useVisibleModels} - extracted so it can be
 * unit-tested without a React context. Admins get {@code base} unchanged;
 * everyone else loses CLI-bridge models (and any provider left empty).
 */
export function filterVisibleModels(base: UseModelsResult, isAdmin: boolean): UseModelsResult {
  if (isAdmin) return base;
  const providers = base.providers
    .map(p => ({ ...p, models: p.models.filter(m => !isBridgeModel(m)) }))
    .filter(p => p.models.length > 0);
  const models = base.models.filter(m => !isBridgeModel(m));
  return { ...base, providers, models };
}

/**
 * Role-aware view of {@link useModels} for the model-PICKER surfaces.
 *
 * <p>Hides CLI-bridge models (claude-code/codex/gemini-cli/mistral-vibe) from
 * non-admin users - they are dispatch-blocked from them anyway (admin's shared
 * subscription), so offering them in a picker only leads to a 403 at run time.
 * Admins see the full catalog unchanged. The platform default and the
 * module-level cache/accessors are left untouched (a non-admin never has a
 * bridge model selected, and resolution helpers must stay role-agnostic).
 *
 * <p>Uses {@link useOptionalAuth} (non-throwing) so a picker rendered without
 * the auth provider - or in a test - degrades to the safe "non-admin" view
 * instead of crashing. Plain {@link useModels} stays role-agnostic for the
 * many non-picker consumers (selection resolution, pricing display, …).
 */
export function useVisibleModels(): UseModelsResult {
  const base = useModels();
  const auth = useOptionalAuth();
  const isAdmin = auth?.hasRole('ADMIN') ?? false;
  return useMemo(() => filterVisibleModels(base, isAdmin), [base, isAdmin]);
}

/**
 * Non-React accessor to read the cached models data.
 * Returns null if the cache hasn't been populated yet (e.g. before first fetch).
 */
export function getModelsCache(): ModelsData | null {
  return modelsCache;
}

/**
 * Get the effective default model ID from the cache.
 * Returns the backend-provided defaultModel, or falls back to the first
 * model in the first provider (rank #1). Never returns a hardcoded model ID.
 */
export function getEffectiveDefaultModel(): string | null {
  if (!modelsCache) return null;
  if (modelsCache.defaultModel) return modelsCache.defaultModel;
  // Fallback: first model of the first provider
  const firstProvider = modelsCache.providers
    ?.slice()
    .sort((a, b) => (a.displayOrder ?? 999) - (b.displayOrder ?? 999))[0];
  return firstProvider?.models?.[0]?.id ?? null;
}

/**
 * Get the effective default provider name from the cache.
 * Returns the backend-provided defaultProvider, or falls back to the first
 * configured provider (by displayOrder). Never returns a hardcoded provider name.
 */
export function getEffectiveDefaultProvider(): string | null {
  if (!modelsCache) return null;
  if (modelsCache.defaultProvider) return modelsCache.defaultProvider;
  // Fallback: first provider by displayOrder
  const firstProvider = modelsCache.providers
    ?.slice()
    .sort((a, b) => (a.displayOrder ?? 999) - (b.displayOrder ?? 999))[0];
  return firstProvider?.name ?? null;
}

/**
 * Derive the LLM provider name from a model ID.
 *
 * <p>Accepts both bare ids ({@code "claude-opus-4-7"}) and provider-qualified
 * ids ({@code "claude-code:claude-opus-4-7"}). The qualified form is used by
 * the chat selector state to preserve the user's explicit provider choice
 * across dedup'd ids - without it, a plain {@code "claude-opus-4-7"} would
 * match both {@code anthropic} and {@code claude-code} rows and the cache
 * walk below would pick whichever came first (order depends on displayOrder),
 * breaking the "I picked Claude Code, why did it try to charge my Anthropic
 * API key" case. Callers that want the raw id without provider prefix
 * should use {@link parseSelectedModel} first.
 *
 * @param modelId - The model identifier. May be {@code "provider:id"} when
 *                  the caller tracked the user's provider choice.
 * @param fallbackProvider - Optional fallback when model can't be matched
 *                           (defaults to cached defaultProvider).
 */
export function getProviderFromModel(modelId: string, fallbackProvider?: string): string {
  const cache = modelsCache;
  const ultimateFallback = fallbackProvider ?? cache?.defaultProvider ?? getEffectiveDefaultProvider() ?? '';

  if (!modelId) return ultimateFallback;

  // Provider-qualified form: trust the prefix verbatim - the user picked it
  // explicitly and it's the whole point of passing the qualifier.
  if (modelId.includes(':')) {
    const [prov] = modelId.split(':', 2);
    if (prov) return prov;
  }

  // First try cached providers for exact match
  if (cache?.providers) {
    for (const provider of cache.providers) {
      if (provider.models.some(m => m.id === modelId)) {
        return provider.name;
      }
    }
  }

  // Fallback: prefix-based heuristics. The "-cc" suffix fallback was dropped
  // in V128 - bridge ids now match their cloud counterpart verbatim, and the
  // provider-qualified selector above handles bridge routing explicitly.
  const lower = modelId.toLowerCase();
  if (lower.startsWith('codex-')) {
    return 'codex';
  }
  if (lower.startsWith('gpt-') || lower.startsWith('o1') || lower.startsWith('o3') || lower.startsWith('o4')) {
    return 'openai';
  }
  if (lower.startsWith('claude-')) {
    return 'anthropic';
  }
  if (lower.startsWith('gemini-')) {
    return 'google';
  }
  if (lower.startsWith('mistral-') || lower.startsWith('codestral-')) {
    return 'mistral';
  }
  if (lower.startsWith('deepseek-')) {
    return 'deepseek';
  }

  return ultimateFallback;
}

/**
 * Typed selected-model pair. Single source of truth for "which model the
 * user has picked" across the whole app. Stored as an object instead of a
 * {@code "provider:id"} string so the compiler forces every call-site to
 * pass both fields explicitly - no more "oops I sent the qualified string
 * as model to the backend and the pricing lookup 402'd".
 *
 * <p>{@code provider} is the SDK-routing key (e.g. {@code "claude-code"},
 * {@code "anthropic"}). {@code id} is the bare model id (e.g.
 * {@code "claude-opus-4-7"}, never the qualified form).
 *
 * <p>The empty selector {@code { provider: "", id: "" }} represents
 * "not chosen yet" - use {@link isEmptySelectedModel} to test.
 */
export interface SelectedModel {
  provider: string;
  id: string;
}

/**
 * Sentinel for "no model chosen yet". Frozen so a buggy caller can't mutate
 * it and poison every other reference - {@link EMPTY_SELECTED_MODEL} is
 * reused by default across the app.
 */
export const EMPTY_SELECTED_MODEL: Readonly<SelectedModel> = Object.freeze({ provider: '', id: '' });

export function isEmptySelectedModel(sel: SelectedModel | null | undefined): boolean {
  return !sel || !sel.id;
}

/**
 * Split a {@code "provider:id"} selector string into its parts. When the
 * input has no colon, {@code provider} is {@code null} (caller falls back
 * to heuristic / cache lookup) and {@code id} is the raw input.
 *
 * <p>Example: {@code parseSelectedModel("claude-code:claude-opus-4-7")
 * → { provider: "claude-code", id: "claude-opus-4-7" }}.
 *
 * @deprecated Kept for legacy localStorage deserialization and AIModel-list
 * inputs. New code should consume/produce {@link SelectedModel} directly.
 */
export function parseSelectedModel(selector: string | null | undefined): {
  provider: string | null;
  id: string;
} {
  if (!selector) return { provider: null, id: '' };
  const colon = selector.indexOf(':');
  if (colon < 0) return { provider: null, id: selector };
  // Empty-string provider (e.g. ":foo") is treated as "missing" so the
  // consumer's fallback / heuristic kicks in - otherwise we'd skip the
  // resolution step and leak an empty provider into state.
  const providerPart = selector.slice(0, colon);
  return {
    provider: providerPart.length > 0 ? providerPart : null,
    id: selector.slice(colon + 1),
  };
}

/**
 * Parse any representation of a selected model (typed object, qualified
 * string, or bare id) into the canonical {@link SelectedModel} shape.
 * Fills in a missing provider via {@link getProviderFromModel}'s heuristic.
 *
 * <p>This is the ONE entry point for turning "something the user/storage
 * gave us" into the typed shape. Every component that holds selection state
 * should normalise through this on ingest.
 */
export function toSelectedModel(
  input: SelectedModel | string | null | undefined,
  fallbackProvider?: string,
): SelectedModel {
  if (!input) return EMPTY_SELECTED_MODEL;
  // Normalise both paths (object / string) into the same shape: a canonical
  // EMPTY when no id is present, otherwise try to fill a missing provider
  // from the heuristic+cache. Doing this in one place means callers can't
  // accidentally ship a half-populated { provider, id:'' } row to the UI.
  let rawId = '';
  let rawProvider = '';
  if (typeof input === 'object') {
    rawId = input.id ?? '';
    rawProvider = input.provider ?? '';
  } else {
    const parsed = parseSelectedModel(input);
    rawId = parsed.id;
    rawProvider = parsed.provider ?? '';
  }
  if (!rawId) return EMPTY_SELECTED_MODEL;
  // Empty-string provider triggers the fallback (|| not ??) - {provider:'', id:'foo'}
  // should be resolved like a bare id, not shipped with an empty provider.
  const provider = rawProvider || getProviderFromModel(rawId, fallbackProvider) || '';
  // Lowercase the provider - it's a routing slug (auth.model_pricing primary
  // key is case-sensitive). Legacy localStorage entries may hold "Claude-code"
  // from the pre-fix capitalization bug; normalise on ingest so stored state
  // self-heals without requiring users to clear their browser.
  return { provider: provider.toLowerCase(), id: rawId };
}

/**
 * Render a {@link SelectedModel} as a {@code "provider:id"} string for
 * storage keys, URL params, and log lines. Returns the empty string for
 * an empty selection.
 */
export function formatSelectedModel(sel: SelectedModel | null | undefined): string {
  if (!sel || !sel.id) return '';
  return sel.provider ? `${sel.provider}:${sel.id}` : sel.id;
}

/**
 * Does {@code model} (from a provider/model list) match the user's current
 * selection? Matches on {@code (provider, id)} when the selection carries
 * a provider, otherwise falls back to id-only. Case-insensitive on provider
 * to tolerate "Anthropic" vs "anthropic" inconsistencies in cached rows.
 *
 * <p>Use this EVERYWHERE instead of {@code m.id === selectedString}.
 */
export function modelMatches(
  model: Pick<AIModel, 'id' | 'provider'>,
  selected: SelectedModel | null | undefined,
): boolean {
  if (!selected || !selected.id) return false;
  // Symmetric case-insensitive compare on both id and provider. Backend
  // normalises ids to lowercase in practice, but cached rows from older
  // feeds / user-typed overrides occasionally drift in casing - the same
  // argument that justifies it for provider applies for id.
  if (model.id.toLowerCase() !== selected.id.toLowerCase()) return false;
  if (!selected.provider) return true;
  return (model.provider ?? '').toLowerCase() === selected.provider.toLowerCase();
}

/**
 * Structural equality for two {@link SelectedModel} values. Case-insensitive
 * on both fields to match {@link modelMatches}. Used by useEffect dependencies
 * and reset-to-default guards so a casing drift doesn't cause a spurious reset.
 */
export function selectedModelEquals(
  a: SelectedModel | null | undefined,
  b: SelectedModel | null | undefined,
): boolean {
  if (a === b) return true;
  if (!a || !b) return false;
  return (
    a.id.toLowerCase() === b.id.toLowerCase() &&
    (a.provider ?? '').toLowerCase() === (b.provider ?? '').toLowerCase()
  );
}

/** Derive a {@link SelectedModel} from an {@link AIModel}. */
export function selectedModelFromAIModel(model: Pick<AIModel, 'id' | 'provider'>): SelectedModel {
  return { provider: model.provider ?? '', id: model.id };
}

/** Get the effective default selection, or empty if cache isn't populated. */
export function getEffectiveDefaultSelectedModel(): SelectedModel {
  const id = getEffectiveDefaultModel();
  const provider = getEffectiveDefaultProvider();
  if (!id) return EMPTY_SELECTED_MODEL;
  return { provider: provider ?? getProviderFromModel(id), id };
}

/**
 * Coerce a seed selection into one that can serve a bare single completion
 * (e.g. the compaction summariser): CLI-bridge providers can never serve
 * those, so a bridge seed is replaced by the first non-bridge provider's
 * default model (by displayOrder) from {@code data}. A non-bridge seed is
 * returned unchanged; {@link EMPTY_SELECTED_MODEL} when no non-bridge
 * provider exists (callers skip seeding). Pure so it can be unit-tested;
 * pass {@link getModelsCache} for {@code data} at the call-site.
 */
export function toNonBridgeSelectedModel(
  seed: SelectedModel,
  data: ModelsData | null,
): SelectedModel {
  if (!isEmptySelectedModel(seed) && !isBridgeModel({ provider: seed.provider })) {
    return seed;
  }
  const sorted = (data?.providers ?? [])
    .slice()
    .sort((a, b) => (a.displayOrder ?? 999) - (b.displayOrder ?? 999));
  for (const p of sorted) {
    if (isBridgeModel({ provider: p.name })) continue;
    const models = p.models.filter(
      m => !isBridgeModel({ providerKind: m.providerKind, provider: m.provider ?? p.name }),
    );
    if (models.length === 0) continue;
    const id = models.some(m => m.id === p.defaultModel) ? p.defaultModel : models[0].id;
    return { provider: p.name, id };
  }
  return EMPTY_SELECTED_MODEL;
}

// Helper function to get provider icon name
export function getProviderIcon(provider: string): string {
  const icons: Record<string, string> = {
    openai: 'Brain',
    anthropic: 'Bot',
    google: 'Sparkles',
    mistral: 'Zap',
    deepseek: 'Code'
  };
  return icons[provider.toLowerCase()] || 'Brain';
}
