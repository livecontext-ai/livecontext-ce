'use client';

import React from 'react';
import { ArrowRight } from 'lucide-react';

import { getAuthGateStrings, authGateBody } from '@/lib/i18n/authGateMessages';

interface SessionGateProps {
  /**
   * True iff a previously-valid session ended (cross-tab logout, the persisted
   * OIDC user vanished, or the login-redirect loop breaker tripped). When true the
   * body prepends "Your session has expired."; when false (cold first visit on a
   * fresh slot / CE with no prior login, or a transient post-signin 401) the body
   * is the neutral "sign in to continue" copy only.
   */
  sessionExpired: boolean;
  /** Invoked when the user clicks the sign-in button. */
  onSignIn: () => void;
  /**
   * Active app locale. Defaults to the client-resolved locale; injectable so this
   * component can be rendered and tested in isolation. This gate renders above
   * NextIntlClientProvider, so its copy comes from {@link getAuthGateStrings}
   * rather than next-intl (see authGateMessages.ts).
   */
  locale?: string;
}

/**
 * Body of the full-screen session gate: heading, contextual sub-copy, and the
 * sign-in button. The surrounding overlay + {@link AuthLayout} chrome live in
 * `smart-providers.tsx`, which decides when to show the gate and passes the
 * `sessionExpired` flag.
 */
export function SessionGate({ sessionExpired, onSignIn, locale }: SessionGateProps) {
  const strings = getAuthGateStrings(locale);

  return (
    <>
      <h1 className="mb-2 text-[28px] font-semibold leading-tight tracking-tight text-[var(--text-primary)]">
        {strings.title}
      </h1>
      <p className="mb-7 max-w-[340px] text-sm text-[var(--text-secondary)]">
        {authGateBody(strings, sessionExpired)}
      </p>
      <button
        type="button"
        onClick={onSignIn}
        className="inline-flex h-[46px] w-full items-center justify-center gap-2.5 rounded-[10px] border border-[var(--accent-primary)] bg-[var(--accent-primary)] px-4 text-sm font-semibold text-[var(--accent-foreground)] shadow-[0_1px_2px_rgba(17,17,17,0.06),0_6px_16px_var(--shadow-color)] transition-all hover:-translate-y-px hover:shadow-[0_1px_2px_rgba(17,17,17,0.06),0_10px_22px_var(--shadow-color)] active:scale-[0.985]"
      >
        <span>{strings.submit}</span>
        <ArrowRight className="h-3.5 w-3.5" strokeWidth={2.5} />
      </button>
    </>
  );
}
