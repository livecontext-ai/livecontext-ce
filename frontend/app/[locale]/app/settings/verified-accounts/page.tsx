'use client';

import React, { useState } from 'react';
import { BadgeCheck, Shield, ShieldAlert, User } from 'lucide-react';
import { useTranslations } from 'next-intl';
import { useAuthGuard } from '@/hooks/useAuthGuard';
import { useAuth } from '@/lib/providers/smart-providers';
import { IS_MANAGED_CLOUD } from '@/lib/edition';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import Toast, { useToast } from '@/components/Toast';
import { unifiedApiService } from '@/lib/api/unified-api-service';
import { VerifiedBadgeIcon } from '@/components/profile/VerifiedBadgeIcon';
import type { AdminVerifiedAccountResponse } from '@/lib/api/services/user-api.service';

type TargetMode = 'email' | 'id';

/**
 * Admin screen for the verified badge - the blue check shown next to a name on a
 * profile, a marketplace listing, a review and a message.
 *
 * <p>Platform admins carry the badge from their role and never need a row here. This
 * page is for everyone else: the publishers and creators verified one at a time.
 *
 * <p>Cloud-only (hidden from the CE nav) and admin-only, mirroring
 * {@code admin-credits}: the same target-by-email-or-id shape, the same guards, the
 * same audited single write.
 */
export default function VerifiedAccountsPage() {
  const { isAuthenticated, isAuthChecking, isLoading: isAuthLoading } = useAuthGuard();
  const { loginWithRedirect, hasRole } = useAuth();
  const t = useTranslations('verifiedAccounts');
  const tSettings = useTranslations('settings');
  const { toasts, addToast, removeToast } = useToast();

  const [targetMode, setTargetMode] = useState<TargetMode>('email');
  const [targetEmail, setTargetEmail] = useState('');
  const [targetUserId, setTargetUserId] = useState('');
  const [submitting, setSubmitting] = useState<'grant' | 'revoke' | null>(null);
  const [lastResult, setLastResult] = useState<AdminVerifiedAccountResponse | null>(null);
  const [fieldErrors, setFieldErrors] = useState<{ targetEmail?: string; targetUserId?: string }>({});

  const validate = (): boolean => {
    const errors: { targetEmail?: string; targetUserId?: string } = {};
    if (targetMode === 'email') {
      const trimmed = targetEmail.trim();
      if (!trimmed || !trimmed.includes('@')) errors.targetEmail = t('errors.invalidEmail');
    } else {
      const parsed = Number(targetUserId);
      if (!targetUserId || !Number.isInteger(parsed) || parsed <= 0) {
        errors.targetUserId = t('errors.invalidUserId');
      }
    }
    setFieldErrors(errors);
    return Object.keys(errors).length === 0;
  };

  const targetLabel = targetMode === 'email' ? targetEmail.trim() : `#${targetUserId}`;

  const submit = async (verified: boolean) => {
    if (!validate()) return;
    setSubmitting(verified ? 'grant' : 'revoke');
    setLastResult(null);
    try {
      const response = await unifiedApiService.adminSetVerified({
        ...(targetMode === 'email'
          ? { target_email: targetEmail.trim() }
          : { target_user_id: Number(targetUserId) }),
        verified,
      });
      setLastResult(response);
      addToast({
        type: 'success',
        title: verified ? t('toasts.grantedTitle') : t('toasts.revokedTitle'),
        message: verified
          ? t('toasts.grantedMessage', { target: targetLabel })
          : t('toasts.revokedMessage', { target: targetLabel }),
        duration: 6000,
      });
    } catch (err: unknown) {
      const message = err instanceof Error ? err.message : String(err);
      addToast({
        type: 'error',
        title: t('toasts.failureTitle'),
        message: message || t('errors.unknown'),
        duration: 8000,
      });
    } finally {
      setSubmitting(null);
    }
  };

  // --------------------------- Guards ---------------------------

  // Managed-cloud-only feature. The nav entry is already gated, so this is the
  // defensive guard for a direct URL hit; without it a self-hosted admin gets a
  // working-looking form whose every submit answers 503.
  //
  // IS_MANAGED_CLOUD, not IS_CE: the backend gate is AppEditionProvider.isManagedCloud(),
  // which is false for SELF-HOSTED ENTERPRISE too - and that deployment reads as
  // "cloud" under the binary IS_CE.
  if (!IS_MANAGED_CLOUD) {
    return (
      <div className="flex flex-col items-center justify-center py-16 text-center">
        <ShieldAlert className="h-12 w-12 text-theme-tertiary mb-4" />
        <h2 className="text-base font-medium text-theme-primary mb-2">{t('selfHosted.title')}</h2>
        <p className="text-sm text-theme-secondary">{t('selfHosted.body')}</p>
      </div>
    );
  }

  if (isAuthChecking || isAuthLoading) {
    return (
      <div className="space-y-8">
        <div className="bg-theme-secondary rounded-xl p-6 animate-pulse">
          <div className="h-6 bg-theme-tertiary rounded w-1/3 mb-4" />
          <div className="h-3 bg-theme-tertiary rounded-full" />
        </div>
      </div>
    );
  }

  if (!isAuthenticated) {
    return (
      <div className="space-y-8">
        <div className="mx-auto max-w-4xl">
          <div className="min-h-[300px] flex items-center justify-center">
            <div className="text-center">
              <h1 className="text-2xl font-bold text-theme-primary mb-4">{tSettings('unauthorized')}</h1>
              <p className="text-theme-secondary mb-6">{tSettings('mustBeLoggedIn')}</p>
              <Button onClick={() => loginWithRedirect()} size="sm" className="h-8 px-3">
                <User className="w-4 h-4 mr-1" />
                {tSettings('signIn')}
              </Button>
            </div>
          </div>
        </div>
      </div>
    );
  }

  if (!hasRole('ADMIN')) {
    return (
      <div className="min-h-[300px] flex items-center justify-center">
        <div className="text-center">
          <Shield className="w-10 h-10 text-theme-muted mx-auto mb-3" />
          <h2 className="text-lg font-semibold text-theme-primary mb-2">{tSettings('unauthorized')}</h2>
          <p className="text-sm text-theme-secondary">{t('errors.adminOnly')}</p>
        </div>
      </div>
    );
  }

  // --------------------------- Page ---------------------------

  return (
    <div className="space-y-8">
      {toasts.length > 0 && (
        <div className="fixed top-4 right-4 z-50 flex flex-col gap-2">
          {toasts.map((toast) => (
            <Toast
              key={toast.id}
              id={toast.id}
              type={toast.type}
              title={toast.title}
              message={toast.message}
              onClose={removeToast}
            />
          ))}
        </div>
      )}

      <div className="bg-theme-secondary rounded-xl p-6">
        <div className="flex items-center justify-between">
          <div className="flex items-center gap-3">
            <div className="w-10 h-10 bg-theme-tertiary rounded-xl flex items-center justify-center">
              <BadgeCheck className="w-5 h-5 text-theme-primary" />
            </div>
            <div>
              <h2 className="text-lg font-semibold text-theme-primary">{t('title')}</h2>
              <p className="text-sm text-theme-secondary">{t('subtitle')}</p>
            </div>
          </div>
          <div className="hidden sm:flex items-center gap-2 px-3 py-1.5 rounded-md bg-amber-500/10 text-amber-700 dark:text-amber-400 text-xs font-medium">
            <Shield className="w-3.5 h-3.5" />
            {t('adminOnlyBadge')}
          </div>
        </div>
      </div>

      <form
        onSubmit={(e) => {
          e.preventDefault();
          void submit(true);
        }}
        className="bg-theme-secondary rounded-xl p-6 space-y-5"
      >
        <div>
          <h3 className="text-base font-semibold text-theme-primary mb-1">{t('form.sectionTitle')}</h3>
          <p className="text-sm text-theme-secondary">{t('form.sectionSubtitle')}</p>
        </div>

        <div className="space-y-2">
          <Label className="text-sm font-medium text-theme-primary">{t('form.targetModeLabel')}</Label>
          <div className="inline-flex rounded-lg border border-theme bg-[var(--bg-primary)] p-0.5">
            {(['email', 'id'] as TargetMode[]).map((mode) => (
              <button
                key={mode}
                type="button"
                onClick={() => {
                  setTargetMode(mode);
                  setFieldErrors({});
                }}
                disabled={!!submitting}
                className={`px-4 py-1.5 text-sm rounded-md transition-colors ${
                  targetMode === mode
                    ? 'bg-theme-tertiary text-theme-primary font-medium'
                    : 'text-theme-secondary hover:text-theme-primary'
                }`}
              >
                {mode === 'email' ? t('form.targetModeEmail') : t('form.targetModeId')}
              </button>
            ))}
          </div>
        </div>

        {targetMode === 'email' ? (
          <div className="space-y-2">
            <Label htmlFor="verified-email" className="text-sm font-medium text-theme-primary">
              {t('form.emailLabel')}
            </Label>
            <Input
              id="verified-email"
              type="email"
              value={targetEmail}
              onChange={(e) => setTargetEmail(e.target.value)}
              placeholder={t('form.emailPlaceholder')}
              disabled={!!submitting}
              className={fieldErrors.targetEmail ? 'border-red-500' : ''}
            />
            {fieldErrors.targetEmail && (
              <p className="text-xs text-red-500">{fieldErrors.targetEmail}</p>
            )}
          </div>
        ) : (
          <div className="space-y-2">
            <Label htmlFor="verified-user-id" className="text-sm font-medium text-theme-primary">
              {t('form.userIdLabel')}
            </Label>
            <Input
              id="verified-user-id"
              type="number"
              value={targetUserId}
              onChange={(e) => setTargetUserId(e.target.value)}
              placeholder={t('form.userIdPlaceholder')}
              disabled={!!submitting}
              className={fieldErrors.targetUserId ? 'border-red-500' : ''}
            />
            {fieldErrors.targetUserId && (
              <p className="text-xs text-red-500">{fieldErrors.targetUserId}</p>
            )}
          </div>
        )}

        <div className="flex flex-wrap items-center gap-2">
          <Button type="submit" size="sm" className="h-8 px-3" disabled={!!submitting}>
            <BadgeCheck className="w-4 h-4 mr-1" />
            {submitting === 'grant' ? t('form.granting') : t('form.grant')}
          </Button>
          <Button
            type="button"
            size="sm"
            variant="outline"
            className="h-8 px-3"
            disabled={!!submitting}
            onClick={() => void submit(false)}
          >
            {submitting === 'revoke' ? t('form.revoking') : t('form.revoke')}
          </Button>
        </div>

        {lastResult && (
          <div className="rounded-lg border border-theme p-4 text-sm">
            <div className="flex items-center gap-2 text-theme-primary">
              <span className="font-medium">
                {lastResult.email ?? `#${lastResult.userId}`}
              </span>
              <VerifiedBadgeIcon verified={lastResult.effectivelyVerified} />
            </div>
            {/* What was STORED, said in the direction of the write. Reading this off
                effectivelyVerified alone printed "no longer shows the verified badge"
                right after a grant on a withdrawn profile, contradicting the toast that
                had just said the opposite. What a reader will actually see is the badge
                beside the name above, plus the two explainers below. */}
            <p className="mt-1 text-theme-secondary">
              {lastResult.verified ? t('result.granted') : t('result.revoked')}
            </p>
            {/* The one outcome that surprises an operator: revoking the manual flag on
                an admin changes nothing visible, because the role grants the badge. */}
            {lastResult.verifiedByRole && !lastResult.verified && (
              <p className="mt-2 text-theme-muted">{t('result.stillVerifiedByRole')}</p>
            )}
            {/* And its mirror: the grant is stored, and shows nowhere, because this
                account withdrew its public profile. */}
            {lastResult.profileWithdrawn && (
              <p className="mt-2 text-theme-muted">{t('result.profileWithdrawn')}</p>
            )}
          </div>
        )}
      </form>
    </div>
  );
}
