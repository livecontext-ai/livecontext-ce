/**
 * Billing API Service
 * Single Responsibility: All billing, subscription, and quota operations
 * Uses apiClient for all HTTP requests (unified auth system)
 */

import { apiClient } from '../api-client';

export interface PlanChangeRequest {
  targetPlanCode: string;
  billingCycle?: 'monthly' | 'yearly';
  immediate?: boolean;
  creditTierIndex?: number;
}

export interface PlanChangeResponse {
  success: boolean;
  changeType: string;
  message: string;
  effectiveDate?: string;
  url?: string;
  scheduleId?: string;
}

export interface ScheduledChange {
  scheduleId: string;
  currentPlanCode: string;
  currentPlanName: string;
  targetPlanCode: string;
  targetPlanName: string;
  effectiveDate: string;
  changeType: 'downgrade' | 'billing_cycle_change' | 'credit_tier_change';
  status: string;
  cancellable: boolean;
  userMessage: string;
  currentCreditQty?: number;
  targetCreditQty?: number;
}

export interface ScheduledChangeResponse {
  hasScheduledChange: boolean;
  scheduledChange?: ScheduledChange;
}

/**
 * V250 - PAYG top-up tier as exposed by {@code GET /api/billing/payg-tiers}.
 * Mirrors {@code StripeBillingService.PaygTierView}: each row carries the
 * canonical credit grant + the Stripe price hooked up by ops. When
 * {@code configured=false}, the corresponding tier row in {@code auth.price}
 * has not yet been wired (V251 seed inserts placeholders), so the frontend
 * should disable the card rather than POSTing into a 503.
 */
export interface PaygTier {
  tier: 'small' | 'medium' | 'large';
  credits: number;
  amountCents: number;
  currency: string;
  configured: boolean;
}

export interface PaygTiersResponse {
  tiers: PaygTier[];
  configured: boolean;
}

export interface PaygCheckoutResponse {
  url: string;
  tier: string;
}

/**
 * Customer-facing invoice record returned by GET /api/billing/invoices.
 * Mirrors {@code com.apimarketplace.auth.domain.dto.BillingInvoiceDto}.
 *
 * Both `amountPaid` and `amountDue` are smallest-unit longs. The display
 * helper picks which to show by status: paid → amountPaid; open / draft /
 * uncollectible → amountDue; void → dash.
 *
 * `created`, `periodStart`, `periodEnd` are ISO-8601 strings with trailing
 * "Z" (Jackson serializes Java Instant that way).
 *
 * `hostedInvoiceUrl` and `invoicePdf` are signed Stripe URLs - may be null
 * for non-finalized invoices.
 */
export interface BillingInvoice {
  id: string;
  number: string | null;
  amountPaid: number;
  amountDue: number;
  currency: string;
  status: 'paid' | 'open' | 'uncollectible' | 'void' | 'draft' | string;
  created: string;
  periodStart: string | null;
  periodEnd: string | null;
  hostedInvoiceUrl: string | null;
  invoicePdf: string | null;
}

export interface InvoiceListResponse {
  invoices: BillingInvoice[];
}

export class BillingApiService {
  constructor() {}

  async getBillingData(): Promise<any> {
    try {
      return await apiClient.get<any>('/billing/me');
    } catch (error) {
      console.error('Error fetching billing data:', error);
      throw error;
    }
  }

  async createCheckout(planData: any): Promise<any> {
    try {
      return await apiClient.post<any>('/billing/checkout', planData);
    } catch (error) {
      console.error('Error creating checkout:', error);
      throw error;
    }
  }

  async getAvailablePlans(): Promise<any> {
    try {
      return await apiClient.get<any>('/billing/plans');
    } catch (error) {
      console.error('Error fetching available plans:', error);
      throw error;
    }
  }

  async finalizeCheckout(sessionId: string): Promise<any> {
    try {
      return await apiClient.get<any>(`/billing/checkout/finalize`, { params: { session_id: sessionId } });
    } catch (error) {
      console.error('Error finalizing checkout:', error);
      throw error;
    }
  }

  async createSubscription(planCode: string, billingCycle: 'monthly' | 'yearly'): Promise<any> {
    try {
      return await apiClient.post<any>('/billing/subscription', {
        planCode,
        billingCycle
      });
    } catch (error) {
      console.error('Error creating subscription:', error);
      throw error;
    }
  }

  async openBillingPortal(returnUrl?: string): Promise<any> {
    try {
      return await apiClient.post<any>('/billing/portal', {
        returnUrl: returnUrl || window.location.origin
      });
    } catch (error) {
      console.error('Error opening billing portal:', error);
      throw error;
    }
  }

  async getBillingDataWithCache(): Promise<any> {
    try {
      return await apiClient.get<any>('/billing/data');
    } catch (error) {
      console.error('Error fetching billing data with cache:', error);
      throw error;
    }
  }

  async forceRefreshBillingData(): Promise<any> {
    try {
      return await apiClient.get<any>('/billing/data', { params: { forceRefresh: 'true' } });
    } catch (error) {
      console.error('Error force refreshing billing data:', error);
      throw error;
    }
  }

  async changePlan(request: PlanChangeRequest): Promise<PlanChangeResponse> {
    try {
      return await apiClient.post<PlanChangeResponse>('/billing/change-plan', request);
    } catch (error) {
      console.error('Error changing plan:', error);
      throw error;
    }
  }

  async scheduleDowngrade(targetPlanCode: string): Promise<PlanChangeResponse> {
    try {
      return await apiClient.post<PlanChangeResponse>('/billing/downgrade', {
        targetPlanCode
      });
    } catch (error) {
      console.error('Error scheduling downgrade:', error);
      throw error;
    }
  }

  async changeBillingCycle(billingCycle: 'monthly' | 'yearly'): Promise<PlanChangeResponse> {
    try {
      return await apiClient.post<PlanChangeResponse>('/billing/change-cycle', {
        billingCycle
      });
    } catch (error) {
      console.error('Error changing billing cycle:', error);
      throw error;
    }
  }

  async changeCreditTier(creditTierIndex: number): Promise<PlanChangeResponse> {
    try {
      return await apiClient.post<PlanChangeResponse>('/billing/change-credit-tier', { creditTierIndex });
    } catch (error) {
      console.error('Error changing credit tier:', error);
      throw error;
    }
  }

  async getScheduledChange(): Promise<ScheduledChangeResponse> {
    try {
      return await apiClient.get<ScheduledChangeResponse>('/billing/scheduled-change');
    } catch (error) {
      console.error('Error getting scheduled change:', error);
      return { hasScheduledChange: false };
    }
  }

  async cancelScheduledChange(): Promise<{ success: boolean; message: string }> {
    try {
      return await apiClient.delete<{ success: boolean; message: string }>('/billing/scheduled-change');
    } catch (error) {
      console.error('Error cancelling scheduled change:', error);
      throw error;
    }
  }

  async cancelSubscription(reason: string, feedback?: string): Promise<PlanChangeResponse> {
    try {
      return await apiClient.post<PlanChangeResponse>('/billing/cancel-subscription', {
        reason,
        ...(feedback && { feedback })
      });
    } catch (error) {
      console.error('Error cancelling subscription:', error);
      throw error;
    }
  }

  async reactivateSubscription(): Promise<{ success: boolean; message: string }> {
    try {
      return await apiClient.post<{ success: boolean; message: string }>('/billing/reactivate-subscription', {});
    } catch (error) {
      console.error('Error reactivating subscription:', error);
      throw error;
    }
  }

  /**
   * Fetch up to 12 most-recent invoices for the authenticated user.
   * Backs the invoices table on /settings/billing. Backend caps the
   * Stripe call at 12 - older invoices live in the Stripe Customer Portal
   * (link rendered by the page footer).
   */
  async getInvoices(): Promise<InvoiceListResponse> {
    try {
      return await apiClient.get<InvoiceListResponse>('/billing/invoices');
    } catch (error) {
      console.error('Error fetching invoices:', error);
      throw error;
    }
  }

  /**
   * V250 - Fetch the PAYG top-up tier catalog. Read-only, no org-owner gate
   * needed (the actual checkout requires the workspace-owner guard server-side).
   * The wallet UI reads {@code configured} to disable cards whose Stripe price
   * hasn't been wired by ops yet.
   */
  async getPaygTiers(): Promise<PaygTiersResponse> {
    try {
      return await apiClient.get<PaygTiersResponse>('/billing/payg-tiers');
    } catch (error) {
      console.error('Error fetching PAYG tiers:', error);
      throw error;
    }
  }

  /**
   * V250 - Create a Stripe one-time {@code mode=PAYMENT} checkout session for
   * the given PAYG tier. Backend gates on {@link requireActiveOrgOwner}, so
   * a TEAM member calling this from a TEAM workspace will receive a 403 -
   * the frontend should switch to the personal workspace before retrying.
   * Returns the Stripe URL the caller should redirect to.
   */
  async createPaygCheckout(tier: 'small' | 'medium' | 'large'): Promise<PaygCheckoutResponse> {
    try {
      return await apiClient.post<PaygCheckoutResponse>('/billing/payg-checkout', { tier });
    } catch (error) {
      console.error('Error creating PAYG checkout:', error);
      throw error;
    }
  }

}
