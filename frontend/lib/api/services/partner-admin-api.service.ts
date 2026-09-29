import { apiClient } from '../api-client';

/** Money per currency code, in minor units (cents). */
export type AmountsByCurrency = Record<string, number>;

export interface PartnerProgramDefaults {
  creatorPlanCode: string;
  creatorPlanDays: number;
  creatorCredits: number;
  creatorMaxUses: number;
  creatorValidDays: number;
  audienceCredits: number;
  commissionBps: number;
  commissionMonths: number;
  holdDays: number;
}

export interface PartnerCodeRow {
  id: number;
  code: string;
  kind: 'creator' | 'partner';
  label: string | null;
  owner_user_id: number | null;
  owner_email: string | null;
  credits: number;
  plan_code: string | null;
  plan_days: number;
  max_uses: number | null;
  commission_percent: number | null;
  commission_months: number | null;
  hold_days: number;
  active: boolean;
  valid_until: string | null;
  created_at: string | null;
  redemptions: number;
  paying_customers: number;
  commissions: {
    on_hold: AmountsByCurrency;
    payable: AmountsByCurrency;
    paid: AmountsByCurrency;
    voided: AmountsByCurrency;
  };
}

export interface PartnerProgramOverview {
  codes: PartnerCodeRow[];
  defaults: PartnerProgramDefaults;
}

export interface CreateCreatorCodeRequest {
  code?: string;
  label?: string;
  /** PRO / STARTER / TEAM, or NONE for credits only. */
  plan_code?: string;
  plan_days?: number;
  credits?: number;
  max_uses?: number;
  valid_days?: number;
}

export interface CreatePartnerCodeRequest {
  partner_email: string;
  code?: string;
  label?: string;
  audience_credits?: number;
  commission_percent?: number;
  commission_months?: number;
  hold_days?: number;
  valid_days?: number;
  /** Hard cap on sign-ups through the link; omitted = uncapped. */
  max_uses?: number;
}

/**
 * Admin API of the partner / influencer program (cloud only). On a typed failure apiClient
 * throws an ApiError carrying the server's `error` token (code_taken, user_not_found,
 * partner_already_has_code, invalid_code_format, invalid_values, ...).
 */
export const partnerAdminApi = {
  overview: () => apiClient.get<PartnerProgramOverview>('/admin/credits/partners'),
  createCreatorCode: (body: CreateCreatorCodeRequest) =>
    apiClient.post<{ success: boolean; code: PartnerCodeRow }>('/admin/credits/partners/creator-codes', body),
  createPartnerCode: (body: CreatePartnerCodeRequest) =>
    apiClient.post<{ success: boolean; code: PartnerCodeRow }>('/admin/credits/partners/partner-codes', body),
  setActive: (id: number, active: boolean) =>
    apiClient.post<{ success: boolean }>(`/admin/credits/partners/codes/${id}/active`, { active }),
  markPaid: (id: number) =>
    apiClient.post<{ success: boolean; lines: number; amounts: AmountsByCurrency }>(
      `/admin/credits/partners/codes/${id}/mark-paid`,
    ),
};
