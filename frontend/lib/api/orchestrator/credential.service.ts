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
   * Update a credential
   */
  async updateCredential(id: number, updates: Partial<Credential>): Promise<Credential> {
    return apiClient.put<Credential>(`/credentials/${id}`, updates);
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
   */
  async getPlatformCredentialPublicInfo(
    integrationName: string,
    apiToolId?: string | null,
  ): Promise<PlatformCredentialPublicInfo> {
    const params = apiToolId ? { apiToolId } : undefined;
    return apiClient.get<PlatformCredentialPublicInfo>(
      `/platform-credentials/${encodeURIComponent(integrationName)}/public-info`,
      params ? { params } : undefined,
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
}

export const credentialService = new CredentialService();
