/**
 * Server-side read of a partner's offer, for the full-screen offer page (/offer/<token>).
 *
 * <p>Same shape as {@link fetchPartnerTerms}: the gateway directly, never the browser-bound
 * api-client, `GATEWAY_SERVICE_URL` read at runtime. `/api/public/partner-program/offers/{token}`
 * is anonymous at the gateway and carries only what a client may see: the plan, the credits the
 * partner's code gives, and the partner's public identity. A malformed payload is a failed read
 * (`error`), never a dead offer, and never a page built on half an answer.
 */
import 'server-only';

import { cache } from 'react';
import { IS_CE } from '@/lib/edition';
import { gatewayBaseUrl } from '@/lib/marketplace/publicPublications';
import { OFFER_TOKEN_RE as TOKEN } from '@/lib/partners/offerToken';
import { mapPartnerOffer, type PublicPartnerOffer } from './partnerOfferPayload';

const GATEWAY_READ_TIMEOUT_MS = 8000;

export { mapPartnerOffer } from './partnerOfferPayload';
export type { OfferApp, OfferPartner, PublicPartnerOffer } from './partnerOfferPayload';

/**
 * What reading an offer gave: the live offer; `gone` when there is none to show (unknown or
 * deactivated, its code no longer bringing sign-ups, a malformed token, a self-hosted build); or
 * `error` when the read itself failed (5xx, timeout, network, an unreadable answer), which says
 * nothing about the offer.
 */
export type PartnerOfferRead =
  | { status: 'ok'; offer: PublicPartnerOffer }
  | { status: 'gone' }
  | { status: 'error' };

/**
 * The offer behind a token. Read once per request: the page and its metadata (the title a shared
 * link shows) both ask.
 */
export const fetchPartnerOffer = cache(async (token: string): Promise<PartnerOfferRead> => {
  if (IS_CE || !TOKEN.test(token)) return { status: 'gone' };
  try {
    const res = await fetch(`${gatewayBaseUrl()}/api/public/partner-program/offers/${encodeURIComponent(token)}`, {
      headers: { Accept: 'application/json' },
      // An offer can be deactivated at any time: never served from a cache.
      cache: 'no-store',
      signal: AbortSignal.timeout(GATEWAY_READ_TIMEOUT_MS),
    });
    // Only the endpoint's own "no such live offer" means gone; any other failure is passing,
    // including a 404 that is not its answer (a route missing during a deploy).
    if (res.status === 404) {
      const body = await res.json().catch(() => null) as { error?: unknown } | null;
      return body?.error === 'unknown_offer' ? { status: 'gone' } : { status: 'error' };
    }
    if (!res.ok) return { status: 'error' };
    const offer = mapPartnerOffer(await res.json());
    return offer ? { status: 'ok', offer } : { status: 'error' };
  } catch {
    return { status: 'error' };
  }
});
