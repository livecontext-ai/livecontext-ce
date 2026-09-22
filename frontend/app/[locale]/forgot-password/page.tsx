'use client';

/**
 * "Forgot your password" for embedded (self-hosted) auth.
 *
 * On cloud, Keycloak owns this screen, so the page renders a notice pointing at
 * the sign-in flow rather than offering a form our backend would refuse. It does
 * not redirect: the reset entry point on cloud lives inside Keycloak's own login
 * page, which is not a URL this app should be sending people to directly.
 *
 * The success state is shown for ANY submitted address, including one with no
 * account. That is not sloppiness: the backend answers identically by design,
 * and a page that distinguished the two would leak which addresses are
 * registered. The copy is written to be true in both cases.
 *
 * There is also no "this install cannot send mail" state, which is why sentHint
 * names the possibility out loud instead. The backend cannot detect it honestly
 * (spring.mail.host defaults to localhost in every edition, so it is never
 * blank), and a delivery failure is only ever observable for an address that
 * exists, so reporting one here would answer the question the rest of this flow
 * refuses to answer.
 */

import React, { useCallback, useState } from 'react';
import { useLocale, useTranslations } from 'next-intl';
import Link from 'next/link';
import { ArrowRight, MailCheck } from 'lucide-react';
import { AuthLayout } from '@/components/auth/AuthLayout';
import { embeddedForgotPassword } from '@/lib/providers/embedded-auth-provider';
import { IS_CLOUD } from '@/lib/edition';

export default function ForgotPasswordPage() {
  const t = useTranslations('auth.forgotPassword');
  const locale = useLocale();
  const loginHref = `/${locale}/login`;

  const [email, setEmail] = useState('');
  const [sent, setSent] = useState(false);
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(false);

  const handleSubmit = useCallback(async (e: React.FormEvent) => {
    e.preventDefault();
    setError('');
    setLoading(true);

    const result = await embeddedForgotPassword(email);

    if (result.success) {
      setSent(true);
    } else {
      // Reaching here means the request itself failed (offline, proxy, 500).
      // A rejected or unknown address does NOT come back as an error: the
      // backend answers 200 for both, on purpose.
      //
      // The localized string, not result.error: what the provider returns here is
      // either a raw server message or the English literal 'Network error', and
      // neither belongs on a page that ships in six languages.
      setError(t('error'));
    }
    setLoading(false);
  }, [email, t]);

  if (IS_CLOUD) {
    // Keycloak's own reset page is the real one on cloud.
    return (
      <AuthLayout metaText={t('remembered')} metaLinkText={t('backToLogin')} metaLinkHref={loginHref}>
        <h1 className="text-[22px] font-semibold text-[var(--text-primary)]">{t('title')}</h1>
        <p className="mt-2 text-sm text-[var(--text-secondary)]">{t('cloudNotice')}</p>
      </AuthLayout>
    );
  }

  if (sent) {
    return (
      <AuthLayout metaText={t('remembered')} metaLinkText={t('backToLogin')} metaLinkHref={loginHref}>
        <div className="flex items-center gap-2.5">
          <MailCheck className="h-5 w-5 text-[var(--text-primary)]" strokeWidth={2} />
          <h1 className="text-[22px] font-semibold text-[var(--text-primary)]">{t('sentTitle')}</h1>
        </div>
        <p className="mt-3 text-sm text-[var(--text-secondary)]">{t('sentBody')}</p>
        <p className="mt-2 text-[13px] text-[var(--text-secondary)]/80">{t('sentHint')}</p>
        <Link
          href={loginHref}
          className="mt-6 inline-flex h-[46px] w-full items-center justify-center gap-2.5 rounded-[10px] border border-[var(--border-color)] px-4 text-sm font-semibold text-[var(--text-primary)] transition-colors hover:bg-[var(--bg-secondary)]"
        >
          {t('backToLogin')}
        </Link>
      </AuthLayout>
    );
  }

  return (
    <AuthLayout metaText={t('remembered')} metaLinkText={t('backToLogin')} metaLinkHref={loginHref}>
      <h1 className="text-[22px] font-semibold text-[var(--text-primary)]">{t('title')}</h1>
      <p className="mt-2 text-sm text-[var(--text-secondary)]">{t('subtitle')}</p>

      <form onSubmit={handleSubmit} className="mt-7 flex flex-col gap-4">
        {error && (
          <div
            role="alert"
            className="rounded-[10px] border border-red-200 bg-red-50 p-3 text-sm text-red-700 dark:border-red-800 dark:bg-red-900/20 dark:text-red-400"
          >
            {error}
          </div>
        )}

        <div>
          <label htmlFor="email" className="mb-1.5 block text-[13px] font-medium text-[var(--text-secondary)]">
            {t('email')}
          </label>
          <input
            id="email"
            type="email"
            required
            autoComplete="email"
            autoFocus
            value={email}
            onChange={(e) => setEmail(e.target.value)}
            placeholder={t('emailPlaceholder')}
            className="block h-[46px] w-full rounded-[10px] border border-[var(--border-color)] bg-[var(--bg-secondary)] px-3.5 text-sm text-[var(--text-primary)] placeholder:text-[var(--text-secondary)]/60 transition-colors focus:border-[var(--text-secondary)] focus:outline-none focus:ring-4 focus:ring-[var(--accent-primary)]/15"
          />
        </div>

        <button
          type="submit"
          disabled={loading}
          className="mt-1.5 inline-flex h-[46px] w-full items-center justify-center gap-2.5 rounded-[10px] border border-[var(--accent-primary)] bg-[var(--accent-primary)] px-4 text-sm font-semibold text-[var(--accent-foreground)] transition-all hover:-translate-y-px active:scale-[0.985] disabled:cursor-wait disabled:opacity-90"
        >
          {loading ? (
            <span className="inline-block h-3.5 w-3.5 animate-spin rounded-full border-2 border-current border-t-transparent" />
          ) : (
            <>
              <span>{t('submit')}</span>
              <ArrowRight className="h-3.5 w-3.5" strokeWidth={2.5} />
            </>
          )}
        </button>
      </form>
    </AuthLayout>
  );
}
