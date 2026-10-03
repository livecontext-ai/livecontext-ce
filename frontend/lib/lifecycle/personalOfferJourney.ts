import { PERSONAL_OFFER_PARAM, readPendingPersonalOffer } from './pendingPersonalOffer';
import { isSafeReturnPath } from '@/lib/security/safeReturnPath';

export const PERSONAL_OFFER_JOURNEY_KEY = 'lc_personal_offer_journey_v1';
const MAX_AGE_MS = 4 * 24 * 60 * 60 * 1000;

export interface PersonalOfferJourney {
  planCode?: string;
  creditTierIndex: number;
  billingCycle: 'monthly' | 'yearly';
  returnToWork: string;
  userKey?: string;
  savedAt: number;
}

export function readPersonalOfferJourney(win: Window, userKey?: string, now = Date.now()): PersonalOfferJourney | null {
  try {
    const raw = win.sessionStorage.getItem(PERSONAL_OFFER_JOURNEY_KEY);
    if (!raw) return null;
    const value = JSON.parse(raw) as PersonalOfferJourney;
    if (!Number.isFinite(value.savedAt) || now < value.savedAt || now - value.savedAt >= MAX_AGE_MS ||
        !Number.isInteger(value.creditTierIndex) || value.creditTierIndex < 0 ||
        !['monthly', 'yearly'].includes(value.billingCycle) ||
        !isSafeReturnPath(value.returnToWork) ||
        (userKey && value.userKey && userKey !== value.userKey)) {
      win.sessionStorage.removeItem(PERSONAL_OFFER_JOURNEY_KEY);
      return null;
    }
    return value;
  } catch {
    return null;
  }
}

export function savePersonalOfferJourney(win: Window, value: Omit<PersonalOfferJourney, 'savedAt'>): void {
  try {
    win.sessionStorage.setItem(PERSONAL_OFFER_JOURNEY_KEY, JSON.stringify({ ...value, savedAt: Date.now() }));
  } catch {
    // A blocked store must not prevent checkout or sign-in.
  }
}

export function clearPersonalOfferJourney(win: Window): void {
  try {
    win.sessionStorage.removeItem(PERSONAL_OFFER_JOURNEY_KEY);
  } catch {
    // Nothing to clear in a blocked store.
  }
}

/** A local sign-in return path with the exact selection and candidate offer link. */
export function buildPersonalOfferSignInReturn(win: Window, selection: Pick<PersonalOfferJourney, 'planCode' | 'creditTierIndex' | 'billingCycle'>): string {
  const url = new URL(win.location.href);
  url.searchParams.delete('checkout');
  url.searchParams.delete('session_id');
  url.searchParams.set('pricingMode', 'subscription');
  url.searchParams.set('billingCycle', selection.billingCycle);
  url.searchParams.set('creditTierIndex', String(selection.creditTierIndex));
  if (selection.planCode) url.searchParams.set('planCode', selection.planCode);
  const code = readPendingPersonalOffer(win) ?? url.searchParams.get(PERSONAL_OFFER_PARAM);
  if (code) url.searchParams.set(PERSONAL_OFFER_PARAM, code);
  return `${url.pathname}${url.search}`;
}
