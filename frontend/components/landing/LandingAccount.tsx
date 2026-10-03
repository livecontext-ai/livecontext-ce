'use client';

import React, { useSyncExternalStore } from 'react';
import Link from 'next/link';
import SignInButton from '@/app/[locale]/_landing/SignInButton';
import { useOptionalAuth } from '@/lib/providers/smart-providers';
import { SESSION_HINT_ATTR } from '@/components/landing/sessionHint';
import { accountDisplayName, initials, readSiteSessionHint } from '@/lib/auth/siteSessionHint';

// Re-exported: the header's tests and older imports read it from here.
export { initials };

const PILL = 'inline-flex items-center gap-1 h-9 px-3 lg:px-4 rounded-xl text-sm font-medium whitespace-nowrap transition-colors hover:bg-[var(--accent-hover)] active:scale-[0.98] cursor-pointer';

// The hint and the cookie are set before the first paint and never change while the page is
// open: nothing to subscribe to. The server knows neither, so its snapshot is "none".
const subscribe = () => () => {};
const readHint = () => document.documentElement.hasAttribute(SESSION_HINT_ATTR);
const noHintOnServer = () => false;
const readSiteHint = () => readSiteSessionHint(document.cookie);
const noSiteHintOnServer = () => null;
const hydrated = () => true;
const notHydrated = () => false;

/**
 * The right-hand end of the public header: "Sign in" and "Get started" for a visitor, and the
 * signed-in account itself (its avatar, and the way back into the app) for someone who already
 * is. A signed-in user reading the landing was offered to "get started" with an account they
 * already have.
 *
 * <p>The public pages stay the same for everyone (cached, rendered without a session): the
 * account is resolved in the browser. Until it is, a browser holding a stored session (see
 * `sessionHint`) keeps the space of the signed-in end, and the server's visitor end stays
 * invisible before that; every other visitor sees the visitor end straight away. Intl-free,
 * like the rest of the header.
 *
 * <p>Off the main host (`baseUrl`, the docs subdomain) the session is another origin's: the app
 * leaves a cookie on the site's domain while an account is signed in (initials only, see
 * `lib/auth/siteSessionHint`), and this end shows that account, linking back to the main site.
 *
 * <p>The signed-in end takes no more room than the visitor end: its pill is the same pill with
 * a label no longer than "Get started", and the avatar is hidden exactly where "Sign in" is.
 */
export default function LandingAccount({
  baseUrl,
  signIn,
  getStarted,
  openApp,
  account,
}: {
  baseUrl?: string;
  signIn: string;
  getStarted: string;
  openApp: string;
  account: string;
}) {
  const auth = useOptionalAuth();
  const resolving = !auth || auth.isLoading;
  // False on the server and during hydration, the document's hint right after: no mismatch.
  const storedSession = useSyncExternalStore(subscribe, readHint, noHintOnServer);
  const siteHint = useSyncExternalStore(subscribe, readSiteHint, noSiteHintOnServer);
  const isHydrated = useSyncExternalStore(subscribe, hydrated, notHydrated);

  const offMainHost = Boolean(baseUrl);
  const signedIn = offMainHost ? siteHint !== null : !resolving && !!auth?.isAuthenticated;
  const probablySignedIn = !offMainHost && resolving && storedSession;
  const appHref = (path: string) => `${baseUrl ?? ''}${path}`;

  if (signedIn || probablySignedIn) {
    const name = offMainHost ? null : accountDisplayName(auth?.user);
    const letters = offMainHost ? siteHint : signedIn ? initials(name) : '';
    return (
      <span className="contents" data-testid="landing-account" aria-busy={probablySignedIn || undefined}>
        {/* Plain links: a signed-in reader is not an acquisition, and a link works even while
            the account is still being read. Not prefetched, like the rest of the bar: every
            public page a signed-in reader views would otherwise fetch the app's pages. */}
        <Link
          href={appHref('/app/chat')}
          prefetch={false}
          className={PILL}
          style={{ background: 'var(--accent-primary)', color: 'var(--accent-foreground)' }}
          data-testid="landing-open-app"
        >
          {openApp}
        </Link>
        <Link
          href={appHref('/app/settings')}
          prefetch={false}
          className="hidden sm:inline-flex md:max-[859px]:hidden h-9 w-9 shrink-0 items-center justify-center overflow-hidden rounded-full border border-[var(--border-color)] text-sm font-semibold transition-opacity hover:opacity-80"
          aria-label={name ? `${account} (${name})` : account}
          title={name ?? account}
          style={{ background: 'var(--bg-secondary)', color: 'var(--text-primary)' }}
          data-testid="landing-account-avatar"
        >
          {signedIn && !offMainHost && auth?.avatarUrl
            // eslint-disable-next-line @next/next/no-img-element
            ? <img src={auth.avatarUrl} alt="" className="h-full w-full object-cover" />
            : <span aria-hidden>{letters}</span>}
        </Link>
      </span>
    );
  }

  // "pending" while the account is unknown: invisible when the document carries the session
  // hint (the browser holds a session, so this end is probably about to be replaced). Off the
  // main host it is known as soon as the page runs (the cookie), so pending until hydration.
  const pending = offMainHost ? !isHydrated : resolving;
  return (
    <span
      className={pending ? 'contents [html[data-lc-session]_&]:invisible' : 'contents'}
      data-testid="landing-visitor-end"
      data-state={pending ? 'pending' : 'ready'}
    >
      {/* Hidden from 768px to 858px, and nowhere else: the one entry in the bar that is not
          load-bearing (see LandingHeader). */}
      <SignInButton variant="link" baseUrl={baseUrl} className="hidden sm:inline-flex md:max-[859px]:hidden text-sm whitespace-nowrap cursor-pointer">
        {signIn}
      </SignInButton>
      <SignInButton
        variant="primary"
        baseUrl={baseUrl}
        className={PILL}
      >
        {getStarted}
      </SignInButton>
    </span>
  );
}
