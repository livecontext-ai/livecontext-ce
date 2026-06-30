'use client';

import React, { useState } from 'react';
import { getClientLocale } from '@/lib/utils/locale';
import { Coins, Shield, User, AlertTriangle, CheckCircle2, Crown } from 'lucide-react';
import { useTranslations } from 'next-intl';
import { useAuthGuard } from '@/hooks/useAuthGuard';
import { useAuth } from '@/lib/providers/smart-providers';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import Toast, { useToast } from '@/components/Toast';
import { quotaApi } from '@/lib/api';
import type {
  AdminGrantResponse,
  AdminAssignPlanResponse,
  AdminGrantablePlanCode,
} from '@/lib/api/services/quota-api.service';

type TargetMode = 'email' | 'id';

/** Plans an admin may grant. FREE = revert a comp account to free. */
const GRANTABLE_PLANS: AdminGrantablePlanCode[] = ['FREE', 'STARTER', 'PRO', 'TEAM'];

/**
 * Shared "identify the target user" fields (email vs user-ID toggle + input). Used by BOTH the
 * credit-grant form and the plan-assignment form so the markup + validation styling stay in one
 * place. Each form owns its own state - the two operations are intentionally independent.
 */
function AdminTargetFields({
  idPrefix,
  mode,
  onModeChange,
  email,
  onEmailChange,
  userId,
  onUserIdChange,
  emailError,
  userIdError,
  disabled,
  t,
}: {
  idPrefix: string;
  mode: TargetMode;
  onModeChange: (m: TargetMode) => void;
  email: string;
  onEmailChange: (v: string) => void;
  userId: string;
  onUserIdChange: (v: string) => void;
  emailError?: string;
  userIdError?: string;
  disabled: boolean;
  t: ReturnType<typeof useTranslations>;
}) {
  return (
    <div className="space-y-2">
      <Label className="text-sm font-medium text-theme-primary">{t('form.targetModeLabel')}</Label>
      <div className="inline-flex rounded-lg border border-theme bg-[var(--bg-primary)] p-0.5">
        <button
          type="button"
          onClick={() => onModeChange('email')}
          disabled={disabled}
          className={`px-4 py-1.5 text-sm rounded-md transition-colors ${
            mode === 'email'
              ? 'bg-theme-tertiary text-theme-primary font-medium'
              : 'text-theme-secondary hover:text-theme-primary'
          }`}
        >
          {t('form.targetModeEmail')}
        </button>
        <button
          type="button"
          onClick={() => onModeChange('id')}
          disabled={disabled}
          className={`px-4 py-1.5 text-sm rounded-md transition-colors ${
            mode === 'id'
              ? 'bg-theme-tertiary text-theme-primary font-medium'
              : 'text-theme-secondary hover:text-theme-primary'
          }`}
        >
          {t('form.targetModeId')}
        </button>
      </div>

      {mode === 'email' ? (
        <div className="space-y-2 pt-1">
          <Label htmlFor={`${idPrefix}-targetEmail`} className="text-sm font-medium text-theme-primary">
            {t('form.emailLabel')}
          </Label>
          <Input
            id={`${idPrefix}-targetEmail`}
            type="email"
            value={email}
            onChange={(e) => onEmailChange(e.target.value)}
            placeholder={t('form.emailPlaceholder')}
            autoComplete="off"
            disabled={disabled}
            className={emailError ? 'border-red-500 focus-visible:ring-red-500' : ''}
          />
          {emailError && <p className="text-xs text-red-500">{emailError}</p>}
        </div>
      ) : (
        <div className="space-y-2 pt-1">
          <Label htmlFor={`${idPrefix}-targetUserId`} className="text-sm font-medium text-theme-primary">
            {t('form.userIdLabel')}
          </Label>
          <Input
            id={`${idPrefix}-targetUserId`}
            type="number"
            min={1}
            step={1}
            value={userId}
            onChange={(e) => onUserIdChange(e.target.value)}
            placeholder={t('form.userIdPlaceholder')}
            disabled={disabled}
            className={userIdError ? 'border-red-500 focus-visible:ring-red-500' : ''}
          />
          {userIdError && <p className="text-xs text-red-500">{userIdError}</p>}
        </div>
      )}
    </div>
  );
}

/**
 * Admin-only credit grant + plan assignment page. Cloud-only (hidden in CE via nav config).
 *
 * Two independent admin operations, each hitting a hardened endpoint under the gateway-authenticated
 * /admin/credits/ prefix (NOT the public /credits/ prefix):
 *   - POST /admin/credits/grant - atomically add credits to a user's balance.
 *   - POST /admin/credits/plan  - assign a comp subscription tier (FREE/STARTER/PRO/TEAM). Changes
 *     ONLY capabilities + storage quota; credits stay capped at the standard 5,000/month base.
 */
export default function AdminCreditsPage() {
  const { isAuthenticated, isAuthChecking, isLoading: isAuthLoading } = useAuthGuard();
  const { loginWithRedirect, hasRole } = useAuth();
  const t = useTranslations('adminCredits');
  const tSettings = useTranslations('settings');
  const { toasts, addToast, removeToast } = useToast();

  // ── Credit grant form state ──
  const [targetMode, setTargetMode] = useState<TargetMode>('email');
  const [targetEmail, setTargetEmail] = useState<string>('');
  const [targetUserId, setTargetUserId] = useState<string>('');
  const [amount, setAmount] = useState<string>('');
  const [description, setDescription] = useState<string>('');
  const [submitting, setSubmitting] = useState(false);
  const [lastGrant, setLastGrant] = useState<AdminGrantResponse | null>(null);
  const [fieldErrors, setFieldErrors] = useState<{
    targetEmail?: string;
    targetUserId?: string;
    amount?: string;
  }>({});

  // ── Plan assignment form state (independent target) ──
  const [planTargetMode, setPlanTargetMode] = useState<TargetMode>('email');
  const [planTargetEmail, setPlanTargetEmail] = useState<string>('');
  const [planTargetUserId, setPlanTargetUserId] = useState<string>('');
  const [planCode, setPlanCode] = useState<AdminGrantablePlanCode | ''>('');
  const [submittingPlan, setSubmittingPlan] = useState(false);
  const [lastPlan, setLastPlan] = useState<AdminAssignPlanResponse | null>(null);
  const [planErrors, setPlanErrors] = useState<{
    targetEmail?: string;
    targetUserId?: string;
    planCode?: string;
  }>({});

  // Shared target validation. Returns errors for the given mode/values.
  const validateTarget = (
    mode: TargetMode,
    email: string,
    userId: string
  ): { targetEmail?: string; targetUserId?: string } => {
    const errors: { targetEmail?: string; targetUserId?: string } = {};
    if (mode === 'email') {
      const trimmed = email.trim();
      if (!trimmed || !trimmed.includes('@')) errors.targetEmail = t('errors.invalidEmail');
    } else {
      const parsed = Number(userId);
      if (!userId || !Number.isInteger(parsed) || parsed <= 0) errors.targetUserId = t('errors.invalidUserId');
    }
    return errors;
  };

  const targetPayload = (mode: TargetMode, email: string, userId: string) =>
    mode === 'email' ? { target_email: email.trim() } : { target_user_id: Number(userId) };

  const targetLabel = (mode: TargetMode, email: string, userId: string) =>
    mode === 'email' ? email.trim() : `#${userId}`;

  // ─────────────────────── Credit grant submit ───────────────────────

  const validate = (): boolean => {
    const errors = validateTarget(targetMode, targetEmail, targetUserId) as typeof fieldErrors;
    const parsedAmount = Number(amount);
    if (!amount || Number.isNaN(parsedAmount) || parsedAmount <= 0) {
      errors.amount = t('errors.invalidAmount');
    } else if (parsedAmount > 1_000_000) {
      errors.amount = t('errors.amountAboveCap');
    }
    setFieldErrors(errors);
    return Object.keys(errors).length === 0;
  };

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!validate()) return;

    // Extra confirmation for large amounts - hyper-protection against fat fingers
    // on top of the backend's 1M cap. Threshold is an intentional UX speed bump,
    // not a security control.
    const parsedAmount = Number(amount);
    const label = targetLabel(targetMode, targetEmail, targetUserId);
    if (parsedAmount >= 10_000) {
      const confirmed = window.confirm(
        t('confirmLargeGrant', { amount: parsedAmount.toLocaleString(getClientLocale()), target: label })
      );
      if (!confirmed) return;
    }

    setSubmitting(true);
    setLastGrant(null);
    try {
      const response = await quotaApi.adminGrantCredits({
        ...targetPayload(targetMode, targetEmail, targetUserId),
        amount: parsedAmount,
        description: description.trim() || undefined,
      });
      setLastGrant(response);
      addToast({
        type: 'success',
        title: t('toasts.successTitle'),
        message: t('toasts.successMessage', { amount: parsedAmount.toLocaleString(getClientLocale()), target: label }),
        duration: 6000,
      });
      // Reset only the amount + description, keep target in case the admin wants to
      // do a follow-up grant on the same user.
      setAmount('');
      setDescription('');
    } catch (err: unknown) {
      const message = err instanceof Error ? err.message : String(err);
      addToast({
        type: 'error',
        title: t('toasts.failureTitle'),
        message: message || t('errors.unknown'),
        duration: 8000,
      });
    } finally {
      setSubmitting(false);
    }
  };

  // ─────────────────────── Plan assignment submit ───────────────────────

  const handleAssignPlan = async (e: React.FormEvent) => {
    e.preventDefault();
    const errors = validateTarget(planTargetMode, planTargetEmail, planTargetUserId) as typeof planErrors;
    if (!planCode) errors.planCode = t('plan.errors.planRequired');
    setPlanErrors(errors);
    if (Object.keys(errors).length > 0) return;

    const label = targetLabel(planTargetMode, planTargetEmail, planTargetUserId);
    const planName = t(`plan.options.${(planCode as string).toLowerCase()}`);
    const confirmed = window.confirm(t('plan.confirm', { plan: planName, target: label }));
    if (!confirmed) return;

    setSubmittingPlan(true);
    setLastPlan(null);
    try {
      const response = await quotaApi.adminAssignPlan({
        ...targetPayload(planTargetMode, planTargetEmail, planTargetUserId),
        plan_code: planCode as AdminGrantablePlanCode,
      });
      setLastPlan(response);
      addToast({
        type: 'success',
        title: t('plan.toasts.successTitle'),
        message: t('plan.toasts.successMessage', { plan: planName, target: label }),
        duration: 6000,
      });
    } catch (err: unknown) {
      const message = err instanceof Error ? err.message : String(err);
      addToast({
        type: 'error',
        title: t('plan.toasts.failureTitle'),
        message: message || t('errors.unknown'),
        duration: 8000,
      });
    } finally {
      setSubmittingPlan(false);
    }
  };

  // ─────────────────────── Guards ───────────────────────

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

  // ─────────────────────── Page ───────────────────────

  return (
    <div className="space-y-8">
      {/* Toast notifications - manual render loop because Toast takes a single toast, not a list */}
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

      {/* Header card */}
      <div className="bg-theme-secondary rounded-xl p-6">
        <div className="flex items-center justify-between">
          <div className="flex items-center gap-3">
            <div className="w-10 h-10 bg-theme-tertiary rounded-full flex items-center justify-center">
              <Coins className="w-5 h-5 text-theme-primary" />
            </div>
            <div>
              <h2 className="text-lg font-semibold text-theme-primary">{t('title')}</h2>
              <p className="text-sm text-theme-secondary">{t('subtitle')}</p>
            </div>
          </div>
          <div className="hidden sm:flex items-center gap-2 px-3 py-1.5 rounded-full bg-amber-500/10 text-amber-700 dark:text-amber-400 text-xs font-medium">
            <Shield className="w-3.5 h-3.5" />
            {t('adminOnlyBadge')}
          </div>
        </div>
      </div>

      {/* Warning card */}
      <div className="rounded-xl border border-amber-200 dark:border-amber-900/40 bg-amber-50/50 dark:bg-amber-950/20 p-4">
        <div className="flex items-start gap-3">
          <AlertTriangle className="w-5 h-5 text-amber-600 dark:text-amber-400 shrink-0 mt-0.5" />
          <div className="text-sm text-amber-900 dark:text-amber-200">
            <p className="font-medium mb-1">{t('warning.title')}</p>
            <p className="text-amber-800 dark:text-amber-300">{t('warning.body')}</p>
          </div>
        </div>
      </div>

      {/* Grant credits card */}
      <form onSubmit={handleSubmit} className="bg-theme-secondary rounded-xl p-6 space-y-5">
        <div>
          <h3 className="text-base font-semibold text-theme-primary mb-1">{t('form.sectionTitle')}</h3>
          <p className="text-sm text-theme-secondary">{t('form.sectionSubtitle')}</p>
        </div>

        <AdminTargetFields
          idPrefix="credit"
          mode={targetMode}
          onModeChange={(m) => {
            setTargetMode(m);
            setFieldErrors((prev) => ({ ...prev, targetEmail: undefined, targetUserId: undefined }));
          }}
          email={targetEmail}
          onEmailChange={(v) => {
            setTargetEmail(v);
            if (fieldErrors.targetEmail) setFieldErrors((prev) => ({ ...prev, targetEmail: undefined }));
          }}
          userId={targetUserId}
          onUserIdChange={(v) => {
            setTargetUserId(v);
            if (fieldErrors.targetUserId) setFieldErrors((prev) => ({ ...prev, targetUserId: undefined }));
          }}
          emailError={fieldErrors.targetEmail}
          userIdError={fieldErrors.targetUserId}
          disabled={submitting}
          t={t}
        />

        <div className="space-y-2">
          <Label htmlFor="amount" className="text-sm font-medium text-theme-primary">
            {t('form.amountLabel')}
          </Label>
          <Input
            id="amount"
            type="number"
            min={0.0001}
            step="any"
            value={amount}
            onChange={(e) => {
              setAmount(e.target.value);
              if (fieldErrors.amount) setFieldErrors((prev) => ({ ...prev, amount: undefined }));
            }}
            placeholder={t('form.amountPlaceholder')}
            disabled={submitting}
            className={fieldErrors.amount ? 'border-red-500 focus-visible:ring-red-500' : ''}
          />
          {fieldErrors.amount && <p className="text-xs text-red-500">{fieldErrors.amount}</p>}
          <p className="text-xs text-theme-tertiary">{t('form.amountHint')}</p>
        </div>

        <div className="space-y-2">
          <Label htmlFor="description" className="text-sm font-medium text-theme-primary">
            {t('form.descriptionLabel')}
          </Label>
          <Input
            id="description"
            type="text"
            value={description}
            onChange={(e) => setDescription(e.target.value)}
            placeholder={t('form.descriptionPlaceholder')}
            maxLength={500}
            disabled={submitting}
          />
          <p className="text-xs text-theme-tertiary">{t('form.descriptionHint')}</p>
        </div>

        <div className="flex items-center justify-end gap-3 pt-2">
          <Button
            type="button"
            variant="ghost"
            onClick={() => {
              setTargetEmail('');
              setTargetUserId('');
              setAmount('');
              setDescription('');
              setLastGrant(null);
              setFieldErrors({});
            }}
            disabled={submitting}
          >
            {t('form.reset')}
          </Button>
          <Button type="submit" disabled={submitting} className="gap-2">
            <Coins className="w-4 h-4" />
            {submitting ? t('form.submitting') : t('form.submit')}
          </Button>
        </div>
      </form>

      {/* Last grant result */}
      {lastGrant && (
        <div className="rounded-xl border border-emerald-200 dark:border-emerald-900/40 bg-emerald-50/50 dark:bg-emerald-950/20 p-5">
          <div className="flex items-start gap-3">
            <CheckCircle2 className="w-5 h-5 text-emerald-600 dark:text-emerald-400 shrink-0 mt-0.5" />
            <div className="flex-1 min-w-0">
              <p className="text-sm font-medium text-emerald-900 dark:text-emerald-200 mb-3">
                {t('result.title')}
              </p>
              <dl className="grid grid-cols-1 sm:grid-cols-2 gap-x-6 gap-y-2 text-sm">
                <div>
                  <dt className="text-emerald-800/70 dark:text-emerald-300/70">{t('result.targetUser')}</dt>
                  <dd className="font-medium text-emerald-900 dark:text-emerald-100">
                    {lastGrant.target_email
                      ? `${lastGrant.target_email} (#${lastGrant.target_user_id})`
                      : `#${lastGrant.target_user_id}`}
                  </dd>
                </div>
                <div>
                  <dt className="text-emerald-800/70 dark:text-emerald-300/70">{t('result.amountGranted')}</dt>
                  <dd className="font-medium text-emerald-900 dark:text-emerald-100">
                    +{Number(lastGrant.amount_granted).toLocaleString(getClientLocale(), { maximumFractionDigits: 4 })}
                  </dd>
                </div>
                <div>
                  <dt className="text-emerald-800/70 dark:text-emerald-300/70">{t('result.newBalance')}</dt>
                  <dd className="font-medium text-emerald-900 dark:text-emerald-100">
                    {Number(lastGrant.new_balance).toLocaleString(getClientLocale(), { maximumFractionDigits: 4 })}
                  </dd>
                </div>
                <div className="min-w-0">
                  <dt className="text-emerald-800/70 dark:text-emerald-300/70">{t('result.sourceId')}</dt>
                  <dd className="font-mono text-xs text-emerald-900 dark:text-emerald-100 truncate" title={lastGrant.source_id}>
                    {lastGrant.source_id}
                  </dd>
                </div>
              </dl>
            </div>
          </div>
        </div>
      )}

      {/* Assign plan card */}
      <form onSubmit={handleAssignPlan} className="bg-theme-secondary rounded-xl p-6 space-y-5">
        <div className="flex items-center gap-2">
          <Crown className="w-4 h-4 text-theme-primary" />
          <div>
            <h3 className="text-base font-semibold text-theme-primary mb-1">{t('plan.sectionTitle')}</h3>
            <p className="text-sm text-theme-secondary">{t('plan.sectionSubtitle')}</p>
          </div>
        </div>

        <AdminTargetFields
          idPrefix="plan"
          mode={planTargetMode}
          onModeChange={(m) => {
            setPlanTargetMode(m);
            setPlanErrors((prev) => ({ ...prev, targetEmail: undefined, targetUserId: undefined }));
          }}
          email={planTargetEmail}
          onEmailChange={(v) => {
            setPlanTargetEmail(v);
            if (planErrors.targetEmail) setPlanErrors((prev) => ({ ...prev, targetEmail: undefined }));
          }}
          userId={planTargetUserId}
          onUserIdChange={(v) => {
            setPlanTargetUserId(v);
            if (planErrors.targetUserId) setPlanErrors((prev) => ({ ...prev, targetUserId: undefined }));
          }}
          emailError={planErrors.targetEmail}
          userIdError={planErrors.targetUserId}
          disabled={submittingPlan}
          t={t}
        />

        <div className="space-y-2">
          <Label className="text-sm font-medium text-theme-primary">{t('plan.planLabel')}</Label>
          <div className="flex flex-wrap gap-2">
            {GRANTABLE_PLANS.map((code) => (
              <button
                key={code}
                type="button"
                onClick={() => {
                  setPlanCode(code);
                  if (planErrors.planCode) setPlanErrors((prev) => ({ ...prev, planCode: undefined }));
                }}
                disabled={submittingPlan}
                className={`px-4 py-1.5 text-sm rounded-lg border transition-colors ${
                  planCode === code
                    ? 'border-theme bg-theme-tertiary text-theme-primary font-medium'
                    : 'border-theme bg-[var(--bg-primary)] text-theme-secondary hover:text-theme-primary'
                }`}
              >
                {t(`plan.options.${code.toLowerCase()}`)}
              </button>
            ))}
          </div>
          {planErrors.planCode && <p className="text-xs text-red-500">{planErrors.planCode}</p>}
          <p className="text-xs text-theme-tertiary">{t('plan.planHint')}</p>
        </div>

        <div className="flex items-center justify-end gap-3 pt-2">
          <Button
            type="button"
            variant="ghost"
            onClick={() => {
              setPlanTargetEmail('');
              setPlanTargetUserId('');
              setPlanCode('');
              setLastPlan(null);
              setPlanErrors({});
            }}
            disabled={submittingPlan}
          >
            {t('form.reset')}
          </Button>
          <Button type="submit" disabled={submittingPlan} className="gap-2">
            <Crown className="w-4 h-4" />
            {submittingPlan ? t('plan.submitting') : t('plan.submit')}
          </Button>
        </div>
      </form>

      {/* Last plan result */}
      {lastPlan && (
        <div className="rounded-xl border border-emerald-200 dark:border-emerald-900/40 bg-emerald-50/50 dark:bg-emerald-950/20 p-5">
          <div className="flex items-start gap-3">
            <CheckCircle2 className="w-5 h-5 text-emerald-600 dark:text-emerald-400 shrink-0 mt-0.5" />
            <div className="flex-1 min-w-0">
              <p className="text-sm font-medium text-emerald-900 dark:text-emerald-200 mb-3">
                {t('plan.result.title')}
              </p>
              <dl className="grid grid-cols-1 sm:grid-cols-2 gap-x-6 gap-y-2 text-sm">
                <div>
                  <dt className="text-emerald-800/70 dark:text-emerald-300/70">{t('plan.result.targetUser')}</dt>
                  <dd className="font-medium text-emerald-900 dark:text-emerald-100">
                    {lastPlan.target_email
                      ? `${lastPlan.target_email} (#${lastPlan.target_user_id})`
                      : `#${lastPlan.target_user_id}`}
                  </dd>
                </div>
                <div>
                  <dt className="text-emerald-800/70 dark:text-emerald-300/70">{t('plan.result.newPlan')}</dt>
                  <dd className="font-medium text-emerald-900 dark:text-emerald-100">{lastPlan.plan_code}</dd>
                </div>
                <div>
                  <dt className="text-emerald-800/70 dark:text-emerald-300/70">{t('plan.result.previousPlan')}</dt>
                  <dd className="font-medium text-emerald-900 dark:text-emerald-100">
                    {lastPlan.previous_plan_code ?? t('plan.result.none')}
                  </dd>
                </div>
              </dl>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
