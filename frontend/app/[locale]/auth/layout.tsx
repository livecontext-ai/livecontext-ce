import NonceLocaleLayoutBody from '@/components/security/NonceLocaleLayoutBody';

// LC-027 CASA E3 (round 2): this area now gets the per-request nonce script-src
// (lib/security/securityHeaders.mjs's NONCE_CSP_PREFIXES, proxy.ts) instead of the static
// 'unsafe-inline' one. A nonce is only meaningful on a per-request render - see
// NonceLocaleLayoutBody for why reading headers() here is safe.
export const dynamic = 'force-dynamic';

export default NonceLocaleLayoutBody;
