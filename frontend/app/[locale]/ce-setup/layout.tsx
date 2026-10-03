// Force dynamic rendering so that NextIntlClientProvider messages are available at runtime, and
// (LC-027 CASA E3 round 2) so the per-request CSP nonce this route already gets
// (lib/security/securityHeaders.mjs's NONCE_CSP_PREFIXES, proxy.ts) is genuine.
export const dynamic = 'force-dynamic';

// NonceLocaleLayoutBody re-stamps `<html lang>` with this request's nonce - see its own comment
// (`components/security/NonceLocaleLayoutBody.tsx`) and `HtmlLangSync` for why the shared
// `app/[locale]/layout.tsx` cannot do this itself. Before this batch, ce-setup rendered ONLY that
// unnonced copy, which the strict script-src silently blocked (harmless: the lang was left at
// whatever `app/layout.tsx`'s fixed `<html lang="en">` already set - a real, if minor, residual
// from the round-1 rollout of this route's nonce CSP).
export { default } from '@/components/security/NonceLocaleLayoutBody';
