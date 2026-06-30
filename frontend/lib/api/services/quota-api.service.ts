/**
 * Quota API Service
 * Handles credit balance, usage summary, history, and pricing operations.
 */

import { apiClient } from '@/lib/api/api-client';
import { orgScopeRequestOptions } from '@/lib/stores/current-org-store';

export interface CreditBalance {
  balance: number;
  /**
   * V250+: subscription bucket - credits granted on plan signup / renewal.
   * Wiped on every renewal cycle. Sum {@code subBalance + paygBalance} always
   * equals {@code balance}. Optional on the type so legacy clients reading
   * pre-V250 responses keep compiling - runtime today always populates it.
   */
  subBalance?: number;
  /**
   * V250+: PAYG (pay-as-you-go) bucket - credits added by one-time Stripe
   * top-up checkouts. Persists across renewal cycles. Consumed AFTER the
   * subscription bucket via {@code CreditService.splitBuckets}.
   */
  paygBalance?: number;
  /**
   * V148+: account delinquency flag. True when the last platform tool-call
   * commit ran into a partial-charge or floored state. While true, the
   * delinquent gate refuses fresh chat reservations and workflow run-init
   * reservations (in-flight workflow per-step reserves still pass through -
   * atomicity is preferred over enforcement granularity). Cleared on the
   * next positive balance transition (refill / refund / release).
   */
  delinquent?: boolean;
}

export interface CreditSummary {
  balance: number;
  totalConsumedLast30Days: number;
  breakdownByType: Record<string, { count: number; credits: number }>;
  delinquent?: boolean;
}

export interface CreditHistoryEntry {
  id: number;
  userId: number;
  amount: number;
  balanceAfter: number;
  sourceType: string;
  sourceId: string | null;
  provider: string | null;
  model: string | null;
  promptTokens: number | null;
  completionTokens: number | null;
  // V363: cache-read token subset of promptTokens for LLM rows (billed at the
  // discounted cache rate). null on pre-V363 rows and non-LLM rows.
  cachedTokens: number | null;
  description: string | null;
  createdAt: string;
}

export interface CreditHistoryPage {
  content: CreditHistoryEntry[];
  totalElements: number;
  totalPages: number;
  number: number;
  size: number;
}

export interface ModelPricingEntry {
  id: number;
  provider: string;
  model: string;
  inputRate: number;
  outputRate: number;
  fixedCost: number;
}

export interface DailyUsageEntry {
  date: string;
  sourceType: string;
  credits: number;
  count: number;
  tokens: number;
}

export interface UsageAnalytics {
  dailyUsage: DailyUsageEntry[];
  providers: string[];
  models: string[];
  sourceTypes: string[];
}

/**
 * Provide EXACTLY ONE of {target_user_id, target_email}. The backend returns 400
 * "ambiguous_target" if both are set and 400 "missing_target" if neither is.
 */
export interface AdminGrantRequest {
  target_user_id?: number;
  target_email?: string;
  amount: string | number;
  description?: string;
}

export interface AdminGrantResponse {
  success: boolean;
  target_user_id: number;
  /** Only present when the grant was initiated by email. */
  target_email?: string;
  amount_granted: number;
  new_balance: number;
  source_id: string;
}

/** Plan codes an admin may grant from the admin page. FREE = revert a comp account to free. */
export type AdminGrantablePlanCode = 'FREE' | 'STARTER' | 'PRO' | 'TEAM';

/**
 * Provide EXACTLY ONE of {target_user_id, target_email}. The backend returns 400
 * "ambiguous_target" if both are set and 400 "missing_target" if neither is.
 */
export interface AdminAssignPlanRequest {
  target_user_id?: number;
  target_email?: string;
  plan_code: AdminGrantablePlanCode;
}

export interface AdminAssignPlanResponse {
  success: boolean;
  target_user_id: number;
  /** Only present when the grant was initiated by email. */
  target_email?: string;
  plan_code: string;
  /** The plan the user was on before this grant; null when the subscription was just created. */
  previous_plan_code: string | null;
}

class QuotaApiService {
  async getBalance(): Promise<CreditBalance> {
    return apiClient.get<CreditBalance>('/credits/balance');
  }

  /**
   * Admin-only: grant credits to a specific user. Calls the hardened
   * {@code POST /credits/admin/grant} endpoint which updates subscription +
   * ledger atomically. Returns 403 if the caller is not ADMIN, 503 in CE,
   * 400 on invalid input, 404 if the target user has no active subscription.
   */
  async adminGrantCredits(request: AdminGrantRequest): Promise<AdminGrantResponse> {
    // Intentional prefix /admin/credits/ - NOT /credits/admin/. The /credits/ prefix is
    // whitelisted by auth-service as a public path (no gateway HMAC required), which would
    // let an attacker with network access forge X-User-Roles: ADMIN. The /admin/ prefix
    // enforces the gateway authentication filter.
    return apiClient.post<AdminGrantResponse>('/admin/credits/grant', request);
  }

  /**
   * Admin-only: grant a complimentary subscription plan (FREE/STARTER/PRO/TEAM) to a
   * specific user. Changes ONLY the plan tier (capabilities + storage quota) and grants
   * the standard 5k base credits - never the plan's larger allowance. FREE reverts a comp
   * account to free. Returns 403 if not ADMIN, 503 in CE, 400 on invalid input, 404 if the
   * target user doesn't exist, 409 if the user has an active paid (Stripe) subscription.
   *
   * Mounted under {@code /admin/credits/} on purpose - same hardened, gateway-authenticated
   * prefix as {@link adminGrantCredits} (NOT the public {@code /credits/} prefix).
   */
  async adminAssignPlan(request: AdminAssignPlanRequest): Promise<AdminAssignPlanResponse> {
    return apiClient.post<AdminAssignPlanResponse>('/admin/credits/plan', request);
  }

  /**
   * @param orgId optional workspace override - scope the summary to a workspace
   *   OTHER than the globally-active one (Quota page workspace filter). Omit to
   *   use the active workspace. See {@link orgScopeRequestOptions}.
   * @param allWorkspaces when true, aggregate consumption across EVERY workspace
   *   (V366 "All workspaces" view): sends `allWorkspaces=true` so the backend
   *   drops the org reporting filter and returns the full payer aggregate
   *   (including unattributed/legacy rows). The org override is not sent in this
   *   mode (the backend ignores it). The balance is the single owner-pays wallet
   *   either way.
   */
  async getSummary(orgId?: string | null, allWorkspaces?: boolean): Promise<CreditSummary> {
    if (allWorkspaces) {
      return apiClient.get<CreditSummary>('/credits/summary', { params: { allWorkspaces: 'true' } });
    }
    const opts = orgScopeRequestOptions(orgId);
    return opts
      ? apiClient.get<CreditSummary>('/credits/summary', opts)
      : apiClient.get<CreditSummary>('/credits/summary');
  }

  /** @param orgId/allWorkspaces optional workspace scope (see {@link getSummary}). */
  async getHistory(page = 0, size = 20, sourceType?: string, orgId?: string | null, allWorkspaces?: boolean): Promise<CreditHistoryPage> {
    const params: Record<string, string> = { page: String(page), size: String(size) };
    if (sourceType) params.sourceType = sourceType;
    if (allWorkspaces) params.allWorkspaces = 'true';
    const scope = allWorkspaces ? undefined : orgScopeRequestOptions(orgId);
    return apiClient.get<CreditHistoryPage>('/credits/history', { params, ...(scope ?? {}) });
  }

  /** @param orgId/allWorkspaces optional workspace scope (see {@link getSummary}). */
  async getAnalytics(days = 30, sourceType?: string, provider?: string, model?: string, orgId?: string | null, allWorkspaces?: boolean): Promise<UsageAnalytics> {
    const params: Record<string, string> = { days: String(days) };
    if (sourceType) params.sourceType = sourceType;
    if (provider) params.provider = provider;
    if (model) params.model = model;
    if (allWorkspaces) params.allWorkspaces = 'true';
    const scope = allWorkspaces ? undefined : orgScopeRequestOptions(orgId);
    return apiClient.get<UsageAnalytics>('/credits/analytics', { params, ...(scope ?? {}) });
  }

  async getPricing(): Promise<ModelPricingEntry[]> {
    return apiClient.get<ModelPricingEntry[]>('/credits/pricing');
  }
}

export const quotaApi = new QuotaApiService();
