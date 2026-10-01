import { apiClient } from '../api-client';
import type { AmountsByCurrency } from '@/lib/partners/formatAmounts';
import type { PartnerApplication, PartnerStanding } from './partner-program-api.service';
import type { PartnerTierTerm } from '@/lib/partners/tiers';

export type { AmountsByCurrency };

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
  /**
   * The owner's latest acceptance of the Partner Program Terms (V557). Null when they never
   * accepted any version: no payout is possible until they do (mark-paid answers 409
   * terms_not_accepted). Absent from an older backend.
   */
  terms_accepted_version?: string | null;
  terms_accepted_at?: string | null;
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
  /** Partner codes only: the owner's tier, refreshed on every read. */
  standing?: PartnerStanding;
  /** Partner codes only: what the next commission earns (code rate or tier rate, the higher). */
  effective_commission_percent?: number;
}

export interface PartnerProgramOverview {
  codes: PartnerCodeRow[];
  defaults: PartnerProgramDefaults;
  tiers?: PartnerTierTerm[];
  tier_currency?: string;
  founder_until?: string;
  /** Whether an admin may still name founding partners. */
  founder_open?: boolean;
}

/** An application as the admin queue shows it: the applicant's account next to what they wrote. */
export interface PartnerApplicationRow extends PartnerApplication {
  user_id: number;
  email: string | null;
  reward_code_id: number | null;
}

export interface ApprovePartnerApplicationRequest {
  /** Wanted partner code; omitted = generated. */
  code?: string;
  /** Overrides the default commission for this partner; omitted = program default. */
  commission_percent?: number;
  /** Grant the founder tier (Platinum for life); refused with founder_closed after the deadline. */
  founder?: boolean;
}

export interface PartnerApplicationDecision {
  success: boolean;
  application: PartnerApplication;
  code?: string | null;
  /** Whether the applicant was e-mailed the decision. */
  mailed: boolean;
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
  /** Grant a partner code's owner the founder tier (Platinum for life); 409 founder_closed after the deadline. */
  grantFounder: (id: number) =>
    apiClient.post<{ success: boolean; standing: PartnerStanding }>(`/admin/credits/partners/codes/${id}/founder`),
  /** End the owner's founder status (terms clause 7.5): back to the tier their revenue earned. */
  endFounder: (id: number) =>
    apiClient.delete<{ success: boolean; standing: PartnerStanding }>(`/admin/credits/partners/codes/${id}/founder`),
  markPaid: (id: number) =>
    apiClient.post<{ success: boolean; lines: number; amounts: AmountsByCurrency }>(
      `/admin/credits/partners/codes/${id}/mark-paid`,
    ),
  /** The application queue: pending only, or every decision too (newest first, capped). */
  applications: (status: 'pending' | 'all') =>
    apiClient.get<{ applications: PartnerApplicationRow[] }>('/admin/credits/partners/applications', {
      params: { status },
    }),
  /** Creates the applicant's partner code and e-mails them. */
  approveApplication: (id: number, body: ApprovePartnerApplicationRequest) =>
    apiClient.post<PartnerApplicationDecision>(`/admin/credits/partners/applications/${id}/approve`, body),
  /** `note` is shown to the applicant. */
  rejectApplication: (id: number, note?: string) =>
    apiClient.post<PartnerApplicationDecision>(`/admin/credits/partners/applications/${id}/reject`, { note }),
};
