/**
 * Cloud Link Service
 *
 * CE-only: Manages the OAuth link between CE instance and LiveContext cloud account.
 */

import { apiClient } from './api-client';
import type { CreditSummary, CreditHistoryPage } from './services/quota-api.service';

export interface CloudLinkStatus {
  linked: boolean;
  registered?: boolean;
  installId?: string;
  cloudUsername?: string;
  linkedAt?: string;
  llmSource?: 'CLOUD' | 'BYOK';
  /**
   * Source of third-party integration (catalog) credentials: CLOUD relays integration
   * executions to the linked cloud account (its platform credentials, per-call markup
   * billed there); BYOK keeps credentials configured and used locally on this install.
   */
  catalogSource?: 'CLOUD' | 'BYOK';
  /**
   * Plan code of the bound cloud account when it GOVERNS this install's entitlements
   * (populated when EITHER the LLM source or the catalog credential source is CLOUD;
   * all-BYOK installs keep their local plan). Mirrors the backend EffectivePlanResolver
   * contract so frontend affordance gates match server enforcement.
   */
  cloudPlanCode?: string;
  /**
   * The governing cloud subscription's credit-tier index + billing cadence (CLOUD-governed installs
   * only). Lets the CE pricing page align its credit slider + billing toggle with the cloud account,
   * so changing either de-highlights "current" and routes the plan change to the cloud.
   */
  cloudCreditTierIndex?: number;
  cloudCadence?: 'monthly' | 'yearly';
  /**
   * INSTALL-GLOBAL: true if the install has ANY active registered cloud link (the admin's),
   * regardless of which user asks. Drives VISIBILITY (a non-owner MEMBER of an admin-linked
   * install inherits the admin's cloud marketplace + plan badge) while {@code linked} stays
   * PER-USER and drives the management surface (only the link owner manages it).
   */
  installLinked?: boolean;
  /**
   * INSTALL-GLOBAL: the install link's cloud plan code, present only for the inheriting-member
   * case (member: {@code linked} false but {@code installLinked} true). Lets a member display
   * the admin's cloud plan ("CE <plan>") without being able to manage the link.
   */
  installCloudPlanCode?: string;
  /**
   * The cloud refused this install's link because the cloud account is not on a paid plan
   * (register or heartbeat answered 403 CLOUD_LINK_PLAN_REQUIRED). The link itself is KEPT:
   * paying again on the cloud restores it automatically, with no re-link. Cleared on the next
   * successful register or heartbeat.
   */
  planRequired?: boolean;
  /** The refusing cloud account's plan code (e.g. FREE) when {@link planRequired}, else null. */
  planRequiredPlanCode?: string | null;
}

/**
 * Sentinel cloudPlanCode the cloud returns when the bound account has NO governing subscription
 * (mirrors backend PlanLimitService.NO_SUBSCRIPTION). Treat it as "no cloud plan governs" → fall
 * back to the local plan, exactly like EffectivePlanResolver (so a connected free account is not
 * shown as a literal "None").
 */
export const CLOUD_NO_SUBSCRIPTION = '__NONE__';

/**
 * Whether the bound cloud account can pay for work relayed to it: a platform
 * credential, a generation, any call the cloud bills.
 *
 * <p>Mirrors the server's own answer, which is the authority
 * ({@code InternalAuthController.ceLinkEntitlements}: an active plan that is
 * neither the no-subscription sentinel NOR {@code FREE}). Stating it here in
 * one place matters because the two are not the same test, and a surface that
 * only checked for the sentinel would tell a Free-linked install that
 * everything is fine right up to the moment every relayed call is refused.
 *
 * <p>The Free plan is excluded for the same reason it is on the cloud itself:
 * its monthly credits fund workflow runs, not the platform's provider keys.
 */
export function cloudSubscriptionPays(cloudPlanCode?: string | null): boolean {
  if (!cloudPlanCode) return false;
  return cloudPlanCode !== CLOUD_NO_SUBSCRIPTION && cloudPlanCode.toUpperCase() !== 'FREE';
}

export interface AuthUrlResponse {
  authUrl: string;
  state: string;
  /**
   * Cloud onboarding entry that carries the same OAuth parameters
   * ({@code <cloud web>/onboarding?ce_link=1&...}). A new cloud account completes the
   * cloud onboarding (email verification, profile, a paid plan) there before the cloud
   * continues to the very same Keycloak authorization. Absent on an older backend.
   */
  startUrl?: string;
}

/**
 * Where "Connect to Cloud" sends the browser: the cloud onboarding entry when the backend
 * offers one, else the bare Keycloak authorization URL (older backend).
 */
export function resolveConnectUrl(response: AuthUrlResponse): string {
  return response.startUrl || response.authUrl;
}

/**
 * Result of probing the cloud host for a TLS-intercepting antivirus / corporate
 * proxy whose root CA this install does not yet trust. When {@code intercepted}
 * is true, {@code caPem} is the interceptor root the admin can one-click trust.
 */
export interface TlsInterceptProbe {
  intercepted: boolean;
  reachable: boolean;
  host?: string;
  caSubject?: string;
  caIssuer?: string;
  caSha256?: string;
  caPem?: string;
  /**
   * False when the proxy presented only a re-signed leaf (no self-signed root): trusting it
   * covers this host only, so the admin should also trust the issuing root ({@code caIssuer}).
   */
  rootPresented?: boolean;
  error?: string;
}

export class CloudLinkService {
  async getStatus(): Promise<CloudLinkStatus> {
    return apiClient.get<CloudLinkStatus>('/cloud-link/status');
  }

  async getAuthUrl(returnPath?: string): Promise<AuthUrlResponse> {
    return apiClient.get<AuthUrlResponse>('/cloud-link/auth-url', {
      params: { returnPath },
    });
  }

  /**
   * THE entry point of every "Connect to Cloud" button: asks the backend for a fresh OAuth
   * flow and returns the URL to navigate to ({@link resolveConnectUrl}). Callers assign it to
   * {@code window.location.href}; none of them reads {@code authUrl} directly.
   */
  async getConnectUrl(returnPath?: string): Promise<string> {
    return resolveConnectUrl(await this.getAuthUrl(returnPath));
  }

  async connect(state: string): Promise<CloudLinkStatus> {
    return apiClient.post<CloudLinkStatus>('/cloud-link/connect', { state });
  }

  async disconnect(): Promise<void> {
    await apiClient.delete('/cloud-link/disconnect');
  }

  /**
   * Detect whether a TLS-intercepting antivirus/proxy is blocking the cloud
   * connection (its root CA is not trusted), and capture that CA so the admin
   * can trust it. Admin-only; CE-only. Returns {@code intercepted:false} when
   * the cloud is reachable normally (nothing to do).
   */
  async probeTlsIntercept(): Promise<TlsInterceptProbe> {
    return apiClient.get<TlsInterceptProbe>('/ce/tls/probe');
  }

  /**
   * Trust an intercepting proxy/AV root CA (PEM). Takes effect immediately
   * (no restart) and is persisted across restarts. Admin-only; CE-only.
   */
  async trustInterceptCa(pem: string): Promise<{ trusted: boolean; subject?: string; sha256?: string }> {
    return apiClient.post<{ trusted: boolean; subject?: string; sha256?: string }>('/ce/tls/trust', { pem });
  }

  async getLlmSource(): Promise<'CLOUD' | 'BYOK'> {
    const response = await apiClient.get<{ source: 'CLOUD' | 'BYOK' }>('/cloud-link/llm-source');
    return response.source;
  }

  async setLlmSource(source: 'CLOUD' | 'BYOK'): Promise<'CLOUD' | 'BYOK'> {
    const response = await apiClient.put<{ source: 'CLOUD' | 'BYOK' }>('/cloud-link/llm-source', { source });
    return response.source;
  }

  async getCatalogSource(): Promise<'CLOUD' | 'BYOK'> {
    const response = await apiClient.get<{ source: 'CLOUD' | 'BYOK' }>('/cloud-link/catalog-source');
    return response.source;
  }

  async setCatalogSource(source: 'CLOUD' | 'BYOK'): Promise<'CLOUD' | 'BYOK'> {
    const response = await apiClient.put<{ source: 'CLOUD' | 'BYOK' }>('/cloud-link/catalog-source', { source });
    return response.source;
  }

  /**
   * Mirror of the bound cloud account's credit usage summary. The relay meters LLM spend
   * against the cloud account, so a CLOUD-linked CE reads its spend (in $) from here rather
   * than its own (empty) local ledger. Returns null when the cloud reports it unavailable
   * (not linked/registered or unreachable) so the caller can fall back to the local summary.
   */
  async getCloudUsageSummary(): Promise<CreditSummary | null> {
    const resp = await apiClient.get<CreditSummary & { available?: boolean }>('/cloud-link/usage-summary');
    if (!resp || resp.available === false) return null;
    return resp;
  }

  /**
   * Mirror of THIS install's relay rows in the bound cloud account's usage history. The
   * backend scopes the query to CE_LLM_RELAY server-side, so there is no client source-type
   * filter - a CE only ever views its own relay slice. Returns null when unavailable so the
   * caller falls back to the local history.
   */
  async getCloudUsageHistory(page = 0, size = 15): Promise<CreditHistoryPage | null> {
    const params: Record<string, string> = { page: String(page), size: String(size) };
    const resp = await apiClient.get<CreditHistoryPage & { available?: boolean }>('/cloud-link/usage-history', { params });
    if (!resp || resp.available === false) return null;
    return resp;
  }
}

export const cloudLinkService = new CloudLinkService();
