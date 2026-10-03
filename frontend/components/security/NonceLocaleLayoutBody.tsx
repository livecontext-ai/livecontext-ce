import type { ReactNode } from 'react';
import { headers } from 'next/headers';
import HtmlLangSync from './HtmlLangSync';

/**
 * Shared body for a nonce-class area layout that needs nothing beyond re-stamping the html-lang
 * script with the per-request nonce (LC-027 CASA E3, round 2) - `/login`, `/register`,
 * `/onboarding`, `/forgot-password`, `/reset-password`, `/invitations`, `/auth`. `/app` and
 * `/ce-setup` have their own bespoke layout body (the app shell providers; a plain fragment) and
 * do not use this component, but read the same nonce the same way.
 *
 * Reading `headers()` here is safe only because every caller already opted its OWN route segment
 * into dynamic rendering (`export const dynamic = 'force-dynamic'` in that segment's own
 * `layout.tsx` - a route-segment config can only be read from the segment's own file, so it
 * cannot be re-exported from here). Each of those areas is a single-purpose auth/onboarding
 * flow: none of them is a candidate for static generation regardless of CSP (they read
 * search params / cookies for tokens today), so forcing them dynamic is not a new behaviour
 * change beyond the nonce work itself.
 */
export default async function NonceLocaleLayoutBody({
  children,
  params,
}: {
  children: ReactNode;
  params: Promise<{ locale: string }>;
}) {
  const { locale } = await params;
  const nonce = (await headers()).get('x-nonce');
  return (
    <>
      <HtmlLangSync locale={locale} nonce={nonce} />
      {children}
    </>
  );
}
