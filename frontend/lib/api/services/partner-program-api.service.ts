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

/** What a partner earned in one calendar month (UTC, `yyyy-MM`), per currency, voided lines left out. */
export interface PartnerMonth {
  month: string;
  commissions: AmountsByCurrency;
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
  /** The last 12 calendar months, oldest first, the current one included. Absent from an older backend. */
  months?: PartnerMonth[];
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

/** What a partner code offers a new account, as the public endpoint states it (never its owner). */
export interface PartnerCodeOffer {
  code: string;
  /** The credits a new account receives with the code. */
  credits: number;
}

/** A partner's offer to one client (V559): the plan, credits and cycle they recommend, behind a short token. */
export interface PartnerOffer {
  token: string;
  plan_code: 'STARTER' | 'PRO' | 'TEAM';
  credit_tier_index: number;
  billing_cycle: 'monthly' | 'yearly';
  /** The partner's own note ("For Acme"), never shown to the client. */
  label: string | null;
  /** The partner's own applications the offer gives (publication ids, in the order shown). */
  app_ids?: string[];
  created_at: string | null;
}

export interface PartnerOfferRequest {
  plan_code: PartnerOffer['plan_code'];
  credit_tier_index: number;
  billing_cycle: PartnerOffer['billing_cycle'];
  label?: string;
  app_ids?: string[];
}

/** The react-query key of the partner's own offers. */
export const PARTNER_OFFERS_QUERY_KEY = ['partner-program', 'offers'] as const;

/** The partner a client came through, for their messages (null fields when the profile is private). */
export interface MyPartner {
  user_id: string;
  name: string | null;
  handle: string | null;
  tier: string | null;
}

/** The react-query key of the signed-in client's partner. */
export const MY_PARTNER_QUERY_KEY = ['partner-program', 'my-partner'] as const;

/** Where one app of the offer stands for the client who just paid through it. */
export type OfferAppDelivery = 'WAITING' | 'PENDING' | 'INSTALLED' | 'FAILED';

/**
 * What a client sees right after paying through a partner's offer: the partner (`user_id` only
 * when the client may write to them; name fields null when their profile is private) and each app
 * the offer gives, as a marketplace card with its delivery `status`. WAITING: the payment is not
 * confirmed yet; PENDING: being installed; INSTALLED: in their workspace; FAILED: it could not be.
 */
export interface PartnerOfferWelcome {
  partner: {
    user_id?: string;
    name: string | null;
    handle: string | null;
    avatar_url: string | null;
    tier: string | null;
  } | null;
  apps: Array<{
    id: string;
    title?: string;
    description?: string | null;
    publisherId?: string;
    publisherName?: string | null;
    status: OfferAppDelivery;
  }>;
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
  /** What a live partner code offers a new account (anonymous; 404 for anything else). */
  codeOffer: (code: string) =>
    apiClient.get<PartnerCodeOffer>(`/public/partner-program/codes/${encodeURIComponent(code)}`, { skipAuth: true }),
  /** The partner's live offers, newest first. */
  offers: () => apiClient.get<{ offers: PartnerOffer[] }>('/billing/partner/offers'),
  /** A new offer on the partner's own code (refusals: not_partner, code_inactive, invalid_*, too_many_offers). */
  createOffer: (body: PartnerOfferRequest) =>
    apiClient.post<{ success: boolean; offer: PartnerOffer }>('/billing/partner/offers', body),
  deactivateOffer: (token: string) => apiClient.delete(`/billing/partner/offers/${encodeURIComponent(token)}`),
  /** The partner the signed-in client came through, or null (cloud only). */
  myPartner: () => apiClient.get<{ partner: MyPartner | null }>('/billing/partner/my-partner'),
  /**
   * An offer as the signed-in caller sees it (raw payload, parsed by mapPartnerOffer): also once
   * the partner's code is used up, for a client already attributed to that partner (404 unknown_offer).
   */
  offerView: (token: string) => apiClient.get<unknown>(`/billing/partner/offers/${encodeURIComponent(token)}/view`),
  /** The welcome of a client who just paid through an offer (404 unknown_offer). */
  offerWelcome: (token: string) =>
    apiClient.get<PartnerOfferWelcome>(`/billing/partner/offers/${encodeURIComponent(token)}/welcome`),
  apply: (body: PartnerApplicationRequest) =>
    apiClient.post<{ success: boolean; application: PartnerApplication }>('/billing/partner/applications', body),
  /** Accept the Partner Program Terms from the dashboard (a partner whose code predates them, or an older version). */
  acceptTerms: (termsVersion: string) =>
    apiClient.post<{ success: boolean; agreement: PartnerAgreement }>('/billing/partner/terms/accept', {
      terms_version: termsVersion,
    }),
};
