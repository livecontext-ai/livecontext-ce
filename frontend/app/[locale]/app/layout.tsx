import type { ReactNode } from 'react';
import { headers } from 'next/headers';
import AppLayoutClient from './AppLayoutClient';
import HtmlLangSync from '@/components/security/HtmlLangSync';

/**
 * Thin Server Component wrapper for every `/app` route (LC-027 CASA E3, round 2).
 *
 * `/app` moved from the static ('self' 'unsafe-inline' https:) script-src class into the
 * per-request NONCE class (lib/security/securityHeaders.mjs's NONCE_CSP_PREFIXES, proxy.ts) - a
 * nonce is only meaningful on a per-request render, which needs a Server Component somewhere in
 * the tree to opt the route into dynamic rendering (`export const dynamic = 'force-dynamic'`).
 * The PREVIOUS `layout.tsx` was itself a Client Component (providers, AppShell, modals) and
 * cannot export route-segment config, so it moved verbatim into `./AppLayoutClient.tsx` and this
 * file wraps it. Nesting (a Server layout rendering a Client layout as `children`) rather than a
 * sibling `template.tsx` is deliberate: a template remounts everything below it on every
 * navigation, which would drop state this tree currently persists across in-app navigation (an
 * open settings tab, a running canvas). A layout does not remount its children on navigation, so
 * this split changes nothing about that persistence.
 *
 * `HtmlLangSync` re-stamps the `<html lang>` script with THIS request's nonce - see that
 * component's own comment for why `app/[locale]/layout.tsx` cannot do this itself.
 */
export const dynamic = 'force-dynamic';

export default async function AppLayout({
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
      <AppLayoutClient>{children}</AppLayoutClient>
    </>
  );
}
