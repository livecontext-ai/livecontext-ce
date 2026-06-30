'use client';

import React, { useState, useCallback, useEffect, useRef } from 'react';
import { useLocale, useTranslations } from 'next-intl';
import { useRouter, useSearchParams } from 'next/navigation';
import { ArrowRight } from 'lucide-react';
import { embeddedRegister } from '@/lib/providers/embedded-auth-provider';
import { IS_CLOUD } from '@/lib/edition';
import { useAuth } from '@/lib/providers/smart-providers';
import LoadingSpinner from '@/components/LoadingSpinner';
import { AuthLayout } from '@/components/auth/AuthLayout';
import Link from 'next/link';

export default function RegisterPage() {
  const t = useTranslations('auth.register');
  const locale = useLocale();
  const router = useRouter();
  const searchParams = useSearchParams();
  const { isAuthenticated, isLoading: isAuthLoading, loginWithRedirect } = useAuth();
  // PR-3 frontend: honour ?returnTo= so users coming from the invitation
  // accept page (/invitations/accept?token=...) land back there after
  // signup. The backend onboarding hook will auto-accept the pending
  // invitation by email match, but the user still expects the accept
  // page to confirm the join.
  const returnTo = searchParams.get('returnTo') || `/${locale}/app/chat`;
  const loginHref = `/${locale}/login?returnTo=${encodeURIComponent(returnTo)}`;

  const [firstName, setFirstName] = useState('');
  const [lastName, setLastName] = useState('');
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [confirmPassword, setConfirmPassword] = useState('');
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(false);
  const redirectStartedRef = useRef(false);

  useEffect(() => {
    if (!IS_CLOUD || isAuthLoading || redirectStartedRef.current) return;
    if (isAuthenticated) {
      redirectStartedRef.current = true;
      router.replace(returnTo);
      return;
    }
    redirectStartedRef.current = true;
    loginWithRedirect({ appState: { returnTo } }).catch(() => {
      redirectStartedRef.current = false;
      setError(t('error'));
    });
  }, [isAuthenticated, isAuthLoading, loginWithRedirect, returnTo, router, t]);

  const handleSubmit = useCallback(async (e: React.FormEvent) => {
    e.preventDefault();
    setError('');

    if (password.length < 8) {
      setError(t('passwordTooShort'));
      return;
    }

    if (password !== confirmPassword) {
      setError(t('passwordMismatch'));
      return;
    }

    setLoading(true);

    const result = await embeddedRegister(email, password, firstName, lastName);

    if (result.success) {
      window.location.href = returnTo;
    } else {
      setError(result.error || t('error'));
      setLoading(false);
    }
  }, [email, password, confirmPassword, firstName, lastName, t, returnTo]);

  if (IS_CLOUD) {
    return (
      <div className="min-h-screen flex items-center justify-center bg-[var(--bg-primary)] px-4">
        {error ? (
          <div className="w-full max-w-sm rounded-md bg-red-50 dark:bg-red-900/20 border border-red-200 dark:border-red-800 p-3">
            <p className="text-sm text-red-700 dark:text-red-400">{error}</p>
          </div>
        ) : (
          <LoadingSpinner size="lg" />
        )}
      </div>
    );
  }

  const inputCls =
    'block h-[46px] w-full rounded-[10px] border border-[var(--border-color)] bg-[var(--bg-secondary)] px-3.5 text-sm text-[var(--text-primary)] placeholder:text-[var(--text-secondary)]/60 transition-colors focus:border-[var(--text-secondary)] focus:outline-none focus:ring-4 focus:ring-[var(--accent-primary)]/15';
  const labelCls = 'mb-1.5 block text-[13px] font-medium text-[var(--text-secondary)]';

  return (
    <AuthLayout metaText={t('hasAccount')} metaLinkText={t('signIn')} metaLinkHref={loginHref}>
      <h1 className="mb-2 text-[28px] font-semibold leading-tight tracking-tight text-[var(--text-primary)]">
        {t('title')}
      </h1>
      <p className="mb-7 max-w-[340px] text-sm text-[var(--text-secondary)]">
        {t('subtitle')}
      </p>

      <form onSubmit={handleSubmit} className="space-y-3.5">
        {error && (
          <div className="rounded-[10px] border border-red-300/60 bg-red-50/70 px-3 py-2.5 text-[13px] text-red-700 dark:border-red-700/60 dark:bg-red-900/20 dark:text-red-400">
            {error}
          </div>
        )}

        <div className="grid grid-cols-2 gap-3">
          <div>
            <label htmlFor="firstName" className={labelCls}>
              {t('firstName')}
            </label>
            <input
              id="firstName"
              type="text"
              required
              autoFocus
              value={firstName}
              onChange={(e) => setFirstName(e.target.value)}
              placeholder={t('firstNamePlaceholder')}
              className={inputCls}
            />
          </div>
          <div>
            <label htmlFor="lastName" className={labelCls}>
              {t('lastName')}
            </label>
            <input
              id="lastName"
              type="text"
              required
              value={lastName}
              onChange={(e) => setLastName(e.target.value)}
              placeholder={t('lastNamePlaceholder')}
              className={inputCls}
            />
          </div>
        </div>

        <div>
          <label htmlFor="email" className={labelCls}>
            {t('email')}
          </label>
          <input
            id="email"
            type="email"
            required
            autoComplete="email"
            value={email}
            onChange={(e) => setEmail(e.target.value)}
            placeholder={t('emailPlaceholder')}
            className={inputCls}
          />
        </div>

        <div>
          <label htmlFor="password" className={labelCls}>
            {t('password')}
          </label>
          <input
            id="password"
            type="password"
            required
            autoComplete="new-password"
            value={password}
            onChange={(e) => setPassword(e.target.value)}
            placeholder={t('passwordPlaceholder')}
            className={inputCls}
          />
        </div>

        <div>
          <label htmlFor="confirmPassword" className={labelCls}>
            {t('confirmPassword')}
          </label>
          <input
            id="confirmPassword"
            type="password"
            required
            autoComplete="new-password"
            value={confirmPassword}
            onChange={(e) => setConfirmPassword(e.target.value)}
            placeholder={t('confirmPasswordPlaceholder')}
            className={inputCls}
          />
        </div>

        <button
          type="submit"
          disabled={loading}
          className="mt-1.5 inline-flex h-[46px] w-full items-center justify-center gap-2.5 rounded-[10px] border border-[var(--accent-primary)] bg-[var(--accent-primary)] px-4 text-sm font-semibold text-[var(--accent-foreground)] shadow-[0_1px_2px_rgba(17,17,17,0.06),0_6px_16px_var(--shadow-color)] transition-all hover:-translate-y-px hover:shadow-[0_1px_2px_rgba(17,17,17,0.06),0_10px_22px_var(--shadow-color)] active:scale-[0.985] disabled:cursor-wait disabled:opacity-90"
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

      <p className="mt-6 text-center text-[13px] text-[var(--text-secondary)]">
        {t('hasAccount')}{' '}
        <Link
          href={loginHref}
          className="border-b border-[var(--border-color)] pb-px font-medium text-[var(--text-primary)] transition-colors hover:border-[var(--text-primary)]"
        >
          {t('signIn')}
        </Link>
      </p>
    </AuthLayout>
  );
}
