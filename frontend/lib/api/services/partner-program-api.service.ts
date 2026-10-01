import { apiClient } from '../api-client';
import type { AmountsByCurrency } from '@/lib/partners/formatAmounts';
import type { PartnerTierKey, PartnerTierTerm } from '@/lib/partners/tiers';

/**
 * The terms every new partner code starts from. `commission_percent` is the entry (Silver)
 * rate; `tiers` lists every tier with its rate and the settled revenue that reaches it, in
 * `tier_currency` minor units. `founder_open` says whether founding partners are still being
 * named (until `founder_until`).
 */
export interface PartnerProgramTerms {
  commission_percent: number;
  commission_months: number;
  hold_days: number;
  audience_credits: number;
  tiers: PartnerTierTerm[];
  tier_currency: string;
  /** How old an invoice must be (and not refunded) before it counts toward a tier. */
  tier_settle_days: number | null;
  founder_until: string | null;
  founder_open: boolean;
}

/** Where a partner stands: tier reached, the settled revenue behind it, and the next step. */
export interface PartnerStanding {
  tier: PartnerTierKey;
  founder: boolean;
  revenue_minor: number;
  currency: string;
  next_tier: PartnerTierKey | null;
  next_threshold_minor: number | null;
  commission_percent: number;
}

export type PartnerApplicationStatus = 'pending' | 'approved' | 'rejected';

export interface PartnerApplication {
  id: number;
  status: PartnerApplicationStatus;
  company_name: string;
  website: string | null;
  audience: string | null;
  message: string | null;
  /** Written by the admin on a rejection, addressed to the applicant. */
  decision_note: string | null;
  created_at: string | null;
  reviewed_at: string | null;
}

/** One commission line as the partner sees it: money and state, never the customer. */
export interface PartnerCommissionLine {
  invoice_paid_at: string | null;
  currency: string;
  base_amount_minor: number;
  commission_minor: number;
  status: 'on_hold' | 'payable' | 'paid' | 'void';
  due_at: string | null;
  paid_at: string | null;
}

export interface PartnerAccount {
  code: string;
  /** The rate the next commission earns: the higher of the code's own rate and the tier's. */
  commission_percent: number | null;
  standing?: PartnerStanding | null;
  commission_months: number | null;
  hold_days: number;
  audience_credits: number;
  valid_until: string | null;
  /** How many new accounts the code can still be redeemed by, in total; null = no cap. */
  max_uses?: number | null;
  redemptions: number;
  paying_customers: number;
  commissions: {
    on_hold: AmountsByCurrency;
    payable: AmountsByCurrency;
    paid: AmountsByCurrency;
    voided: AmountsByCurrency;
  };
  lines: PartnerCommissionLine[];
}

/**
 * The signed-in user's partner page. `state` drives what it shows: `none` (never applied, or
 * may apply again), `pending`, `rejected`, `active` (live code) or `inactive` (code disabled
 * or expired). `partner` is present once a code exists.
 */
/**
 * Where the user stands with the Partner Program Terms (V557). `required`: a partner who has not
 * accepted the current version, whom the dashboard asks to accept it. `payouts_blocked`: a partner
 * who never accepted any version, who is paid nothing until they do.
 */
export interface PartnerAgreement {
  current_version: string;
  accepted_version: string | null;
  accepted_at: string | null;
  accepted_current: boolean;
  required: boolean;
  payouts_blocked: boolean;
}

export interface PartnerDashboardResponse {
  state: 'none' | 'pending' | 'rejected' | 'active' | 'inactive';
  terms: PartnerProgramTerms;
  application: PartnerApplication | null;
  partner: PartnerAccount | null;
  /** Absent from an older backend: read as nothing to accept. */
  agreement?: PartnerAgreement | null;
}

export interface PartnerApplicationRequest {
  company_name: string;
  website?: string;
  audience?: string;
  message?: string;
  /** The version of the Partner Program Terms the applicant ticked (PARTNER_TERMS_VERSION). */
  terms_version: string;
}

/** The react-query key of the signed-in user's partner page, shared by every screen that reads it. */
export const PARTNER_DASHBOARD_QUERY_KEY = ['partner-program', 'me'] as const;

/**
 * Partner-facing API of the partner program (cloud only; 503 on a self-hosted install). On a
 * typed failure apiClient throws an ApiError carrying the server's `error` token
 * (already_pending, already_partner, missing_company, invalid_website, too_long, and for the
 * Partner Program Terms terms_not_accepted, terms_outdated, not_partner).
 */
export const partnerProgramApi = {
  /** The public program terms (anonymous endpoint; also readable signed in). */
  terms: () => apiClient.get<PartnerProgramTerms>('/public/partner-program/terms'),
  me: () => apiClient.get<PartnerDashboardResponse>('/billing/partner/me'),
  apply: (body: PartnerApplicationRequest) =>
    apiClient.post<{ success: boolean; application: PartnerApplication }>('/billing/partner/applications', body),
  /** Accept the Partner Program Terms from the dashboard (a partner whose code predates them, or an older version). */
  acceptTerms: (termsVersion: string) =>
    apiClient.post<{ success: boolean; agreement: PartnerAgreement }>('/billing/partner/terms/accept', {
      terms_version: termsVersion,
    }),
};
