import { apiClient } from '../api-client';

export interface OfferPolicyCell {
  planCode: string;
  monthlyCredits: number;
  bonusCredits: number;
}

export interface OfferPolicyInput {
  campaignKey: string;
  label: string;
  waitHours: number;
  validityHours: number;
  checkoutHoldMinutes: number;
  reminderEnabled: boolean;
  reminderHours: number;
  paygCreditsPerUsd: number;
  allowConversionStack: false;
  matrix: OfferPolicyCell[];
}

export interface OfferPolicy extends OfferPolicyInput {
  id: number;
  version: number;
  state: 'DRAFT' | 'ACTIVE' | 'PAUSED';
}

export interface IssuedPersonalOffer {
  id: number;
  recipientUserId: number;
  policyVersionId: number;
  issuedAt: string;
  expiresAt: string;
  active: boolean;
  status: string;
  bonusCredits: number | null;
}

const BASE = '/admin/credits/offers';

export const personalOfferAdminApi = {
  getPolicies: () => apiClient.get<{ policies: OfferPolicy[] }>(`${BASE}/policies`),
  createPolicy: (input: OfferPolicyInput) => apiClient.post<{ policy: OfferPolicy }>(`${BASE}/policies`, input),
  updatePolicy: (id: number, input: OfferPolicyInput) => apiClient.put<{ policy: OfferPolicy }>(`${BASE}/policies/${id}`, input),
  activatePolicy: (id: number) => apiClient.post<{ policy: OfferPolicy }>(`${BASE}/policies/${id}/activate`, {}),
  pausePolicy: (id: number) => apiClient.post<{ policy: OfferPolicy }>(`${BASE}/policies/${id}/pause`, {}),
  getCodes: () => apiClient.get<{ codes: IssuedPersonalOffer[] }>(`${BASE}/codes`),
  disableCode: (id: number) => apiClient.post<void>(`${BASE}/codes/${id}/disable`, {}),
};
