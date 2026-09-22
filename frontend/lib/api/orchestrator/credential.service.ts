/**
 * Credential Service
 *
 * Handles credential CRUD, templates, and OAuth2 operations.
 * Single Responsibility: Only credential-related operations.
 */

import { apiClient } from '../api-client';
import type {
  Credential,
  CredentialStatus,
  PaginatedCredentialsResponse,
  CredentialTemplate,
  PaginatedTemplatesResponse,
  OAuth2InitiateRequest,
  OAuth2SimpleInitiateRequest,
  OAuth2InitiateResponse,
  PlatformCredentialsAvailability,
  PlatformCredential,
  CreatePlatformCredentialRequest,
  UpdatePlatformCredentialRequest,
  CategoryInfo,
  CredentialSourceInfo,
  LlmProviderStatus,
  BridgeStatusResponse,
  PricingVersion,
  PublishPricingVersionRequest,
  PlatformCredentialPublicInfo,
  MyOAuthApp,
  DeleteImpact,
  DeleteResult
} from './types';

export class CredentialService {
  // ========================================
  // User Credentials CRUD
  // ========================================

  /**
   * Get all credentials for current user (paginated)
   */
  async getCredentials(options: { page?: number; pageSize?: number; status?: CredentialStatus } = {}): Promise<PaginatedCredentialsResponse> {
    const { page = 1, pageSize = 10, status } = options;
    const params: Record<string, string> = { page: String(page), pageSize: String(pageSize) };
    if (status) params.status = status;
    return apiClient.get<PaginatedCredentialsResponse>('/credentials', { params });
  }

  /**
   * Get a single credential by ID
   */
  async getCredential(id: number): Promise<Credential> {
    return apiClient.get<Credential>(`/credentials/${id}`);
  }

  /**
   * Create a credential (for non-OAuth2 types)
   */
  async createCredential(credential: Partial<Credential>): Promise<Credential> {
    return apiClient.post<Credential>('/credentials', credential);
  }

  /**
   * Rename a credential (display label only). Returns the updated credential,
   * secrets stripped.
   *
   * The credential keeps its id, and everything that pins one pins the id
   * (workflow nodes, agent tool configs, published apps), so a rename does not
   * re-point them. The server refuses the renames that would move more than a
   * label, each with its own `code` on the thrown ApiError:
   * `duplicate_name` (409, the name already IDENTIFIES another credential of the OWNER
   * and would identify this one too, possibly across a workspace the caller cannot see;
   * a name that identifies neither, or only the other, is allowed) and
   * `name_is_identity` (422, the credential carries no integration, so its name
   * is what identifies it to the nodes that pinned it). Also `400`
   * (`invalid_name` / `workspace_required`) and `404` when the credential is not
   * in the caller's workspace.
   *
   * Replaces the former `updateCredential`, which PUT to a route the
   * auth-service never exposed (it had no caller, and would have 405'd).
   */
  async renameCredential(id: number, name: string): Promise<Credential> {
    return apiClient.patch<Credential>(`/credentials/${id}`, { name });
  }

  /**
   * Delete a credential
   */
  async deleteCredential(id: number): Promise<void> {
    return apiClient.delete<void>(`/credentials/${id}`);
  }

  /**
   * Get all credentials for current user (no pagination, for quick lookup)
   */
  async getAllCredentials(): Promise<Credential[]> {
    return apiClient.get<Credential[]>('/credentials/all');
  }

  /**
   * Set a credential as the default for its integration
   */
  async setDefaultCredential(id: number): Promise<void> {
    return apiClient.post<void>(`/credentials/${id}/set-default`, {});
  }

  /**
   * Clear the default flag from a credential
   */
  async clearDefaultCredential(id: number): Promise<void> {
    return apiClient.post<void>(`/credentials/${id}/clear-default`, {});
  }

  /**
   * Get credentials by integration name (ex: gmail, slack, github)
   */
  async getCredentialsByIntegration(integration: string): Promise<Credential[]> {
    return apiClient.get<Credential[]>(`/credentials/by-integration/${encodeURIComponent(integration)}`);
  }

  // ========================================
  // Credential Templates (from Catalog)
  // ========================================

  /**
   * Get credential templates from catalog (paginated)
   */
  async getCredentialTemplates(options: { page?: number; pageSize?: number; search?: string; includeInactive?: boolean } = {}): Promise<PaginatedTemplatesResponse> {
    const { page = 1, pageSize = 100, search, includeInactive } = options;
    const params: Record<string, string> = { page: String(page), pageSize: String(pageSize) };
    if (search) params.search = search;
    if (includeInactive) params.includeInactive = 'true';

    return apiClient.get<PaginatedTemplatesResponse>('/catalog/credentials', { params });
  }

  /**
   * Get a single credential template by ID
   */
  async getCredentialTemplate(id: string): Promise<CredentialTemplate> {
    return apiClient.get<CredentialTemplate>(`/catalog/credentials/${encodeURIComponent(id)}`);
  }

  /**
   * Get a credential template by exact credential_name (no ILIKE, no pagination).
   * Used by workflow nodes that know their credential name upfront (smtp, ssh, sftp, database).
   */
  async getCredentialTemplateByName(credentialName: string): Promise<CredentialTemplate | null> {
    try {
      return await apiClient.get<CredentialTemplate>('/catalog/credentials', {
        params: { name: credentialName },
      });
    } catch {
      return null;
    }
  }

  /**
   * List all auth variants for a credential_name. When an API exposes more than one auth
   * method (e.g. Gmail: OAuth2 + API_Key), the wizard renders tabs so the user can pick
   * which to configure. Single-variant APIs return a one-element array - the wizard then
   * skips the tab UI entirely. Unknown names return an empty array (not 404) so this is
   * safe to call opportunistically.
   */
  async getCredentialVariants(credentialName: string): Promise<CredentialTemplate[]> {
    try {
      return await apiClient.get<CredentialTemplate[]>(
        `/catalog/credentials/${encodeURIComponent(credentialName)}/variants`
      );
    } catch {
      return [];
    }
  }

  // ========================================
  // OAuth2 Flow
  // ========================================

  /**
   * Initiate OAuth2 flow - returns authorization URL.
   *
   * @param locale optional app UI locale (next-intl). When provided, the provider renders its
   *   consent screen + scope descriptions in that language (Google `hl`). Omit to fall back to the
   *   provider's account/browser default.
   */
  async initiateOAuth2(request: OAuth2InitiateRequest, locale?: string): Promise<OAuth2InitiateResponse> {
    const url = locale
      ? `/credentials/oauth2/initiate?locale=${encodeURIComponent(locale)}`
      : '/credentials/oauth2/initiate';
    return apiClient.post<OAuth2InitiateResponse>(url, request);
  }

  /**
   * Initiate OAuth2 flow using platform credentials only.
   *
   * @param locale optional app UI locale forwarded to the consent screen (see {@link initiateOAuth2}).
   */
  async initiateOAuth2Simple(request: OAuth2SimpleInitiateRequest, locale?: string): Promise<OAuth2InitiateResponse> {
    const url = locale
      ? `/credentials/oauth2/initiate-simple?locale=${encodeURIComponent(locale)}`
      : '/credentials/oauth2/initiate-simple';
    return apiClient.post<OAuth2InitiateResponse>(url, request);
  }

  /**
   * Check if platform credentials are available for an integration and whether
   * the OAuth sign-in warning should be displayed.
   */
  async getPlatformCredentialsAvailability(integration: string): Promise<PlatformCredentialsAvailability> {
    const response = await apiClient.get<PlatformCredentialsAvailability>(
      `/credentials/oauth2/has-platform-credentials?integration=${encodeURIComponent(integration)}`
    );
    return {
      available: response.available,
      showUnverifiedAppWarning: response.showUnverifiedAppWarning ?? response.available,
    };
  }

  /**
   * Check if platform credentials are available for an integration.
   */
  async hasPlatformCredentials(integration: string): Promise<boolean> {
    return (await this.getPlatformCredentialsAvailability(integration)).available;
  }

  /**
   * Refresh an expired OAuth2 token
   */
  async refreshOAuth2Token(credentialId: number): Promise<Credential> {
    return apiClient.post<Credential>(`/credentials/oauth2/refresh/${credentialId}`, {});
  }

  // ========================================
  // Platform Credentials (Admin)
  // ========================================

  /**
   * Get all platform credentials
   */
  async getPlatformCredentials(category?: string): Promise<PlatformCredential[]> {
    const params = category ? { category } : undefined;
    return apiClient.get<PlatformCredential[]>('/platform-credentials', { params });
  }

  /**
   * Get platform credential by integration name
   */
  async getPlatformCredential(integrationName: string): Promise<PlatformCredential> {
    return apiClient.get<PlatformCredential>(`/platform-credentials/${encodeURIComponent(integrationName)}`);
  }

  /**
   * Get all platform credential categories
   */
  async getPlatformCredentialCategories(): Promise<CategoryInfo[]> {
    return apiClient.get<CategoryInfo[]>('/platform-credentials/categories');
  }

  /**
   * Create or update a platform credential
   */
  async savePlatformCredential(data: CreatePlatformCredentialRequest): Promise<PlatformCredential> {
    return apiClient.post<PlatformCredential>('/platform-credentials', data);
  }

  /**
   * Create or update a tenant-scoped platform credential (user-accessible, no admin required).
   * Used by CredentialWizard when configuring OAuth2 for custom APIs.
   */
  async saveTenantPlatformCredential(data: CreatePlatformCredentialRequest): Promise<PlatformCredential> {
    return apiClient.post<PlatformCredential>('/platform-credentials/my', data);
  }

  /**
   * List the current tenant's BYOK custom OAuth connections (Phase 2). Returns
   * an explicit allowlist DTO (no secrets, only presence flags).
   */
  async getMyOAuthApps(): Promise<MyOAuthApp[]> {
    return apiClient.get<MyOAuthApp[]>('/platform-credentials/my');
  }

  /**
   * Peek the cascade impact of deleting a tenant BYOK row before confirming.
   * Returns affectedCredentialCount + truncated flag (capped at 999 by backend
   * to prevent precise tenant-size fingerprinting).
   */
  async getDeleteImpact(integrationName: string): Promise<DeleteImpact> {
    return apiClient.get<DeleteImpact>(
      `/platform-credentials/my/${encodeURIComponent(integrationName)}/delete-impact`);
  }

  /**
   * Delete a tenant BYOK row with cascade-revoke. The backend revokes
   * dependent user credentials (status → needs_reauth, scrub inline OAuth
   * client secret + tokens, invalidate Redis sentinels) BEFORE deleting the
   * BYOK row, so a partial failure leaves the BYOK row intact.
   */
  async deleteMyOAuthApp(integrationName: string): Promise<DeleteResult> {
    return apiClient.delete<DeleteResult>(
      `/platform-credentials/my/${encodeURIComponent(integrationName)}`);
  }

  /**
   * Update an existing platform credential
   */
  async updatePlatformCredential(integrationName: string, data: UpdatePlatformCredentialRequest): Promise<PlatformCredential> {
    return apiClient.put<PlatformCredential>(`/platform-credentials/${encodeURIComponent(integrationName)}`, data);
  }

  /**
   * Delete a platform credential
   */
  async deletePlatformCredential(integrationName: string): Promise<{ deleted: boolean }> {
    return apiClient.delete<{ deleted: boolean }>(`/platform-credentials/${encodeURIComponent(integrationName)}`);
  }

  /**
   * Enable a platform credential
   */
  async enablePlatformCredential(integrationName: string): Promise<{ success: boolean }> {
    return apiClient.put<{ success: boolean }>(`/platform-credentials/${encodeURIComponent(integrationName)}/enable`, {});
  }

  /**
   * Disable a platform credential
   */
  async disablePlatformCredential(integrationName: string): Promise<{ success: boolean }> {
    return apiClient.put<{ success: boolean }>(`/platform-credentials/${encodeURIComponent(integrationName)}/disable`, {});
  }

  /**
   * Enable a single variant row (Phase 2d). Used by the admin integration card
   * when a platform credential exposes more than one auth method - flipping
   * one variant leaves the others untouched. Returns the echoed payload so
   * callers can patch the one row without refetching the whole list. 404 from
   * the backend surfaces as a thrown error (no row matched - variant deleted
   * underneath the UI).
   */
  async enablePlatformCredentialVariant(
    integrationName: string,
    variant: string,
  ): Promise<{ success: boolean; integrationName: string; variant: string; enabled: boolean }> {
    return apiClient.put(
      `/platform-credentials/${encodeURIComponent(integrationName)}/${encodeURIComponent(variant)}/enable`,
      {},
    );
  }

  /**
   * Disable a single variant row (Phase 2d).
   */
  async disablePlatformCredentialVariant(
    integrationName: string,
    variant: string,
  ): Promise<{ success: boolean; integrationName: string; variant: string; enabled: boolean }> {
    return apiClient.put(
      `/platform-credentials/${encodeURIComponent(integrationName)}/${encodeURIComponent(variant)}/disable`,
      {},
    );
  }

  /**
   * Toggle endpoint enabled status
   */
  async togglePlatformCredentialEndpoint(
    integrationName: string,
    toolId: string,
    enabled: boolean
  ): Promise<{ success: boolean }> {
    return apiClient.put<{ success: boolean }>(
      `/platform-credentials/${encodeURIComponent(integrationName)}/endpoints/${encodeURIComponent(toolId)}/toggle`,
      { enabled }
    );
  }

  /**
   * Check if platform has credentials configured (DB or config)
   */
  async checkPlatformCredentialSource(integrationName: string): Promise<CredentialSourceInfo> {
    return apiClient.get<CredentialSourceInfo>(`/platform-credentials/${encodeURIComponent(integrationName)}/has-credentials`);
  }

  /**
   * Fetch the non-admin public view of a platform credential used by the
   * workflow inspector's "Platform credential" toggle. Never returns secrets.
   *
   * <p>When {@code apiToolId} is provided, {@code hasPricing} reflects the
   * rate resolved for that specific endpoint. Without it, the response falls
   * back to "any non-zero rate on this integration".
   *
   * <p>{@code modelId} and {@code quantity} quote a GENERATION model rather
   * than the endpoint as a whole. One endpoint can back several models at
   * different prices, and a generation usually scales with the size of the
   * request, so quoting the endpoint would show a number the customer is never
   * charged. With them, {@code markupCredits} is the price of a run of exactly
   * that size, and the components ({@code priceUnit}, {@code unitCredits}, ...)
   * are returned alongside so the surface can explain how it was reached.
   *
   * <p>{@code generation} states that the endpoint resells a generated asset.
   * The caller knows it from the catalog row it is bound to; the pricing
   * service cannot look it up, because the descriptor lives in a schema it
   * never queries. It decides whether the credential-wide default may be
   * quoted: a generation is never sold on a catch-all, so without it a step
   * bound to a generation endpoint but naming no model was quoted a price
   * execution refuses.
   */
  async getPlatformCredentialPublicInfo(
    integrationName: string,
    apiToolId?: string | null,
    quote?: {
      modelId?: string | null;
      quantity?: number | string | null;
      generation?: boolean | null;
      /**
       * What the call is COUNTED in (second, image, character, call), which a
       * model states as its `measuredUnit`. Not the unit it is SOLD by: a model
       * counted in seconds can be published per minute.
       */
      quantityUnit?: string | null;
      /**
       * What the call's own CHOICES do to the published rate, computed from the
       * model's declared modifiers (`price.modifiers`) and what is in the form.
       *
       * <p>A quote is a display, never a charge: the amount billed is resolved
       * again server-side from the real parameters, so a surface that sent a
       * smaller factor would only misquote a price to itself. Omitted, or 1,
       * quotes the published rate, which is what every caller sent before
       * modifiers existed.
       */
      priceMultiplier?: number | null;
    },
  ): Promise<PlatformCredentialPublicInfo> {
    const params: Record<string, string> = {};
    if (apiToolId) params.apiToolId = apiToolId;
    if (quote?.modelId) params.modelId = quote.modelId;
    if (quote?.quantity !== undefined && quote?.quantity !== null && quote.quantity !== '') {
      params.quantity = String(quote.quantity);
    }
    // Only sent when the caller can positively say so. Absent means "the caller
    // could not tell", which is the answer every pre-existing caller gives and
    // leaves the server on the behaviour it already had.
    if (quote?.generation) params.generation = 'true';
    // Sent so the quote can refuse a rate that cannot price this call at all
    // (a per-image rate against a call counted in seconds). Absent leaves that
    // question unasked, and it can only ever turn a quote OFF, so it is not a
    // channel for buying anything cheaply.
    if (quote?.quantityUnit) params.quantityUnit = quote.quantityUnit;
    // Sent only when it changes something. A factor of 1 is the same statement
    // as no factor, and leaving it out keeps an ordinary quote's request
    // identical to the one it made before modifiers existed.
    if (
      quote?.priceMultiplier !== undefined && quote?.priceMultiplier !== null
      && Number.isFinite(quote.priceMultiplier) && quote.priceMultiplier > 0
      && quote.priceMultiplier !== 1
    ) {
      params.priceMultiplier = String(quote.priceMultiplier);
    }
    return apiClient.get<PlatformCredentialPublicInfo>(
      `/platform-credentials/${encodeURIComponent(integrationName)}/public-info`,
      Object.keys(params).length > 0 ? { params } : undefined,
    );
  }

  // ========================================
  // Pricing Versions (admin markup management)
  // ========================================

  async listPricingVersions(credentialId: number): Promise<PricingVersion[]> {
    return apiClient.get<PricingVersion[]>(`/platform-credentials/${credentialId}/pricing-versions`);
  }

  async getLatestPricingVersion(credentialId: number): Promise<PricingVersion | null> {
    try {
      return await apiClient.get<PricingVersion>(`/platform-credentials/${credentialId}/pricing-versions/latest`);
    } catch (err: unknown) {
      if (err instanceof Error && /404/.test(err.message)) return null;
      throw err;
    }
  }

  async publishPricingVersion(
    credentialId: number,
    body: PublishPricingVersionRequest,
  ): Promise<PricingVersion> {
    return apiClient.post<PricingVersion>(
      `/platform-credentials/${credentialId}/pricing-versions`,
      body,
    );
  }

  // ========================================
  // LLM Provider Credentials
  // ========================================

  /**
   * Get status of all LLM providers (configured, source, hasDbKey)
   */
  async getLlmProviderStatus(): Promise<LlmProviderStatus[]> {
    return apiClient.get<LlmProviderStatus[]>('/llm-providers/status');
  }

  /**
   * The providers this install exposes at least one model for, lower-cased, CLI bridges never
   * included. Open to any signed-in user, unlike the status call above.
   *
   * The own-keys panel cannot answer this from the model catalogue: that one drops every
   * provider the caller holds no key for, which is exactly the set the panel is offering keys
   * for. Without this, the panel would invite a key for a provider whose models an admin has
   * switched off, and the key would be saved and serve nothing.
   */
  async getProvidersOfferingModels(): Promise<string[]> {
    return apiClient.get<string[]>('/llm-providers/offering-models');
  }

  /**
   * Check the agent-bridge reachability AND per-CLI availability.
   *
   * @param options.cli   restrict the probe to a single CLI; without it the
   *                      bridge probes all four (claudeCode/codex/geminiCli/mistralVibe)
   *                      and `connected` reflects "at least one installed".
   * @param options.force bypass the bridge's 30s detection cache (used by the
   *                      "Verify connection" button so retries actually re-probe).
   */
  async getBridgeStatus(options: { cli?: string; force?: boolean } = {}): Promise<BridgeStatusResponse> {
    const params: Record<string, string> = {};
    if (options.cli) params.cli = options.cli;
    if (options.force) params.force = '1';
    return apiClient.get<BridgeStatusResponse>('/llm-providers/bridge-status', {
      params: Object.keys(params).length ? params : undefined,
    });
  }

  /**
   * Invalidate cached LLM credentials (after save/delete)
   */
  async invalidateLlmCache(provider?: string): Promise<void> {
    const params = provider ? { provider } : undefined;
    await apiClient.post<void>('/llm-providers/invalidate-cache', {}, { params });
  }

  /**
   * Invalidate the CALLER's own cached LLM key for one provider (after saving, removing or
   * switching their own key). Self-scoped, so no admin role is needed; the platform-wide
   * invalidateLlmCache above stays admin-only.
   */
  async invalidateMyLlmCache(provider: string): Promise<void> {
    await apiClient.post<void>('/llm-providers/invalidate-cache/mine', {}, { params: { provider } });
  }

  /**
   * Ask the provider whether a key is accepted BEFORE saving it. `valid` is false only when
   * the provider rejected the key; `verified` false means the provider could not be asked,
   * which never blocks saving. The key is sent once, to agent-service, and is not stored.
   */
  async validateLlmKey(provider: string, apiKey: string): Promise<{ valid: boolean; verified: boolean; error?: string }> {
    return apiClient.post<{ valid: boolean; verified: boolean; error?: string }>(
      '/llm-providers/validate', { provider, apiKey });
  }

  /**
   * Switch whose key serves the caller's executions on one provider: `no_proxy` = the saved
   * key (their billing), `proxy` = the LiveContext key. The key stays saved either way.
   */
  async setLlmKeyMode(id: number, mode: 'no_proxy' | 'proxy'): Promise<Credential> {
    return apiClient.patch<Credential>(`/credentials/${id}/llm-mode`, { mode });
  }

  /**
   * After the caller saved, removed, or changed the default of a credential: if it is an LLM
   * key (integration `llm_<provider>`), drop their cached slot so the switch is not delayed by
   * the resolver TTL. Best effort: a failed invalidation only means the cache expires on its
   * own, so it never fails the operation that triggered it. No-op for any other credential.
   * Reaches the replica that serves the call; others keep a cached key for at most the TTL,
   * which only matters when switching the default from one saved key to another.
   */
  async invalidateMyLlmCacheIfLlmKey(integration: string | null | undefined): Promise<void> {
    if (!integration || !integration.startsWith('llm_')) return;
    const provider = integration.slice('llm_'.length);
    if (!provider) return;
    try {
      await this.invalidateMyLlmCache(provider);
    } catch (err) {
      console.warn('LLM key cache invalidation failed (will expire on its own):', err);
    }
  }
}

export const credentialService = new CredentialService();
