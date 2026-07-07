'use client';

import React, { useState, useCallback, useEffect, useRef } from 'react';
import { useRouter, useSearchParams } from 'next/navigation';
import { useLocale, useTranslations } from 'next-intl';
import { ArrowRight } from 'lucide-react';
import { embeddedLogin } from '@/lib/providers/embedded-auth-provider';
import { IS_CLOUD } from '@/lib/edition';
import { useAuth } from '@/lib/providers/smart-providers';
import LoadingSpinner from '@/components/LoadingSpinner';
import { AuthLayout } from '@/components/auth/AuthLayout';
import Link from 'next/link';
import { apiClient } from '@/lib/api';
import { CE_STATUS_API_PATH } from '@/components/security/onboardingStatus';
import { isCeFirstRun, type CeFirstRunStatus } from '@/lib/auth/ceFirstRun';

export default function LoginPage() {
  const t = useTranslations('auth.login');
  const locale = useLocale();
  const router = useRouter();
  const searchParams = useSearchParams();
  const { isAuthenticated, isLoading: isAuthLoading, loginWithRedirect } = useAuth();
  const returnTo = searchParams.get('returnTo') || `/${locale}/app/chat`;
  const registerHref = `/${locale}/register?returnTo=${encodeURIComponent(returnTo)}`;

  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
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

  useEffect(() => {
    // CE first-run: a virgin install has no account to sign into, so a
    // "Welcome back" login page is a dead end - route straight to the
    // admin-account creation. Fail open (stay on login) on any fetch issue.
    if (IS_CLOUD) return;
    let cancelled = false;
    (async () => {
      try {
        // skipAuth: the endpoint is public and the visitor is by definition
        // unauthenticated here - without it apiClient fails fast with NO_TOKEN
        // and the request never goes out.
        const status = await apiClient.get<CeFirstRunStatus>(CE_STATUS_API_PATH, { skipAuth: true });
        if (!cancelled && isCeFirstRun(status)) {
          router.replace(registerHref);
        }
      } catch {
        // Unknown state: keep the normal login form.
      }
    })();
    return () => { cancelled = true; };
  }, [registerHref, router]);

  const handleSubmit = useCallback(async (e: React.FormEvent) => {
    e.preventDefault();
    setError('');
    setLoading(true);

    const result = await embeddedLogin(email, password);

    if (result.success) {
      // Force full page reload so EmbeddedAuthProvider picks up tokens
      window.location.href = returnTo;
    } else {
      setError(result.error || t('error'));
      setLoading(false);
    }
  }, [email, password, returnTo, t]);

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

  return (
    <AuthLayout metaText={t('noAccount')} metaLinkText={t('createAccount')} metaLinkHref={registerHref}>
      <h1 className="mb-2 text-[28px] font-semibold leading-tight tracking-tight text-[var(--text-primary)]">
        {t('title')}
      </h1>
      <p className="mb-7 max-w-[340px] text-sm text-[var(--text-secondary)]">
        {t('subtitle')}
      </p>

      <form onSubmit={handleSubmit} className="space-y-3.5">
        {error && (
          <div className="flex items-start gap-2.5 rounded-[10px] border border-red-300/60 bg-red-50/70 px-3 py-2.5 text-[13px] text-red-700 dark:border-red-700/60 dark:bg-red-900/20 dark:text-red-400">
            <span>{error}</span>
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

        <div>
          <label htmlFor="password" className="mb-1.5 block text-[13px] font-medium text-[var(--text-secondary)]">
            {t('password')}
          </label>
          <input
            id="password"
            type="password"
            required
            autoComplete="current-password"
            value={password}
            onChange={(e) => setPassword(e.target.value)}
            placeholder={t('passwordPlaceholder')}
            className="block h-[46px] w-full rounded-[10px] border border-[var(--border-color)] bg-[var(--bg-secondary)] px-3.5 text-sm text-[var(--text-primary)] placeholder:text-[var(--text-secondary)]/60 transition-colors focus:border-[var(--text-secondary)] focus:outline-none focus:ring-4 focus:ring-[var(--accent-primary)]/15"
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
        {t('noAccount')}{' '}
        <Link
          href={registerHref}
          className="border-b border-[var(--border-color)] pb-px font-medium text-[var(--text-primary)] transition-colors hover:border-[var(--text-primary)]"
        >
          {t('createAccount')}
        </Link>
      </p>
    </AuthLayout>
  );
}
