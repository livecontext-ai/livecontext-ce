import type { Metadata } from 'next';
import NonceLocaleLayoutBody from '@/components/security/NonceLocaleLayoutBody';

// LC-027 CASA E3 (round 2): this area now gets the per-request nonce script-src
// (lib/security/securityHeaders.mjs's NONCE_CSP_PREFIXES, proxy.ts) instead of the static
// 'unsafe-inline' one. A nonce is only meaningful on a per-request render - see
// NonceLocaleLayoutBody for why reading headers() here is safe.
export const dynamic = 'force-dynamic';

/**
 * An account page, not a search result: without this it inherited the landing's
 * title, description and `index, follow`, so any crawler that fetched it saw a
 * duplicate of the home page. (Login and register are also disallowed in
 * robots.txt; this keeps them out of the index if a crawler reaches them anyway.)
 */
export const metadata: Metadata = {
  robots: { index: false, follow: false },
};

export default NonceLocaleLayoutBody;
