'use client';

/**
 * Chooses a new password from a reset link, for embedded (self-hosted) auth.
 *
 * The token arrives in the query string and is the whole authorisation, so the
 * page never asks who you are. Three rules the UI has to respect:
 *
 * - Confirm the password client-side, because getting it wrong here means
 *   burning the link and asking for another mail.
 * - On success, do NOT sign the person in. The backend has just revoked every
 *   refresh token for that account, which is the point of a reset; sending them
 *   to the login screen is the honest next step.
 * - Take the token OUT of the URL as soon as it has been read. It is a bearer
 *   credential for the account, and a URL is the least private place in a
 *   browser: it sits in the address bar over someone's shoulder, in history, in
 *   whatever a screenshot catches, and in any Referer or analytics page-view the
 *   page happens to emit. It is captured into state on the first render, so
 *   rewriting the address cannot lose it.
 */

import React, { useCallback, useEffect, useState } from 'react';
import { useLocale, useTranslations } from 'next-intl';
import { useRouter, useSearchParams } from 'next/navigation';
import Link from 'next/link';
import { ArrowRight, CheckCircle2 } from 'lucide-react';
import { AuthLayout } from '@/components/auth/AuthLayout';
import { embeddedResetPassword } from '@/lib/providers/embedded-auth-provider';
import { IS_CLOUD } from '@/lib/edition';

/** Mirrors the backend rule in PasswordAuthService: at least 8 characters. */
const MIN_PASSWORD_LENGTH = 8;

export default function ResetPasswordPage() {
  const t = useTranslations('auth.resetPassword');
  // The cloud notice already exists on the forgot-password screen; saying the
  // same thing needs the same string, not a second copy of it in six locales.
  const tForgot = useTranslations('auth.forgotPassword');
  const locale = useLocale();
  const searchParams = useSearchParams();
  const router = useRouter();
  // Read ONCE, so clearing the query string below cannot take it away again.
  const [token] = useState(() => searchParams.get('token') || '');
  const loginHref = `/${locale}/login`;
  const forgotHref = `/${locale}/forgot-password`;

  const [password, setPassword] = useState('');
  const [confirm, setConfirm] = useState('');
  const [error, setError] = useState('');
  const [done, setDone] = useState(false);
  const [loading, setLoading] = useState(false);

  useEffect(() => {
    // replace, not push: the entry with the token in it must not stay in
    // history where Back would put it on screen again.
    if (token) router.replace(`/${locale}/reset-password`, { scroll: false });
  }, [token, router, locale]);

  const handleSubmit = useCallback(async (e: React.FormEvent) => {
    e.preventDefault();
    setError('');

    // Checked here so a typo costs a keystroke, not the link.
    if (password.length < MIN_PASSWORD_LENGTH) {
      setError(t('tooShort', { min: MIN_PASSWORD_LENGTH }));
      return;
    }
    if (password !== confirm) {
      setError(t('mismatch'));
      return;
    }

    setLoading(true);
    const result = await embeddedResetPassword(token, password);
    if (result.success) {
      setDone(true);
    } else {
      // The LOCALIZED string, not result.error. The backend's messages are
      // English-only by design (one uniform refusal for unknown, spent, expired
      // and lost-the-race), so surfacing them showed English to /fr, /de, /es,
      // /pt and /zh users and left auth.resetPassword.error dead in six locales.
      // Everything the backend can refuse here means the same thing to the
      // reader: this link is no good, ask for another.
      setError(t('error'));
    }
    setLoading(false);
  }, [confirm, password, t, token]);

  // Gated like its sibling, for two reasons. The endpoint behind this form only
  // exists in embedded auth, so on cloud the submit could only ever 404; and a
  // cloud build runs the analytics provider, whose page-view carries
  // $current_url, so a page that can hold a token in its URL is one this edition
  // should not render at all.
  if (IS_CLOUD) {
    return (
      <AuthLayout metaText={t('needAnother')} metaLinkText={t('goToLogin')} metaLinkHref={loginHref}>
        <h1 className="text-[22px] font-semibold text-[var(--text-primary)]">{t('missingTokenTitle')}</h1>
        <p className="mt-2 text-sm text-[var(--text-secondary)]">{tForgot('cloudNotice')}</p>
      </AuthLayout>
    );
  }

  // A link opened without its token cannot be recovered from on this page.
  if (!token) {
    return (
      <AuthLayout metaText={t('needAnother')} metaLinkText={t('requestAnother')} metaLinkHref={forgotHref}>
        <h1 className="text-[22px] font-semibold text-[var(--text-primary)]">{t('missingTokenTitle')}</h1>
        <p className="mt-2 text-sm text-[var(--text-secondary)]">{t('missingTokenBody')}</p>
      </AuthLayout>
    );
  }

  if (done) {
    return (
      <AuthLayout metaText="" metaLinkText="" metaLinkHref={loginHref}>
        <div className="flex items-center gap-2.5">
          <CheckCircle2 className="h-5 w-5 text-emerald-600" strokeWidth={2} />
          <h1 className="text-[22px] font-semibold text-[var(--text-primary)]">{t('doneTitle')}</h1>
        </div>
        <p className="mt-3 text-sm text-[var(--text-secondary)]">{t('doneBody')}</p>
        <Link
          href={loginHref}
          className="mt-6 inline-flex h-[46px] w-full items-center justify-center gap-2.5 rounded-[10px] border border-[var(--accent-primary)] bg-[var(--accent-primary)] px-4 text-sm font-semibold text-[var(--accent-foreground)] transition-all hover:-translate-y-px"
        >
          {t('goToLogin')}
        </Link>
      </AuthLayout>
    );
  }

  return (
    <AuthLayout metaText={t('needAnother')} metaLinkText={t('requestAnother')} metaLinkHref={forgotHref}>
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
          <label htmlFor="password" className="mb-1.5 block text-[13px] font-medium text-[var(--text-secondary)]">
            {t('newPassword')}
          </label>
          <input
            id="password"
            type="password"
            required
            autoComplete="new-password"
            autoFocus
            value={password}
            onChange={(e) => setPassword(e.target.value)}
            placeholder={t('newPasswordPlaceholder', { min: MIN_PASSWORD_LENGTH })}
            className="block h-[46px] w-full rounded-[10px] border border-[var(--border-color)] bg-[var(--bg-secondary)] px-3.5 text-sm text-[var(--text-primary)] placeholder:text-[var(--text-secondary)]/60 transition-colors focus:border-[var(--text-secondary)] focus:outline-none focus:ring-4 focus:ring-[var(--accent-primary)]/15"
          />
          <p className="mt-1.5 text-[12px] text-[var(--text-secondary)]/80">
            {t('rule', { min: MIN_PASSWORD_LENGTH })}
          </p>
        </div>

        <div>
          <label htmlFor="confirm" className="mb-1.5 block text-[13px] font-medium text-[var(--text-secondary)]">
            {t('confirm')}
          </label>
          <input
            id="confirm"
            type="password"
            required
            autoComplete="new-password"
            value={confirm}
            onChange={(e) => setConfirm(e.target.value)}
            placeholder={t('confirmPlaceholder')}
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
