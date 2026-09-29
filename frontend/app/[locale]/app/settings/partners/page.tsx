'use client';

import React, { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useLocale, useTranslations } from 'next-intl';
import { Copy, Handshake, Shield, ShieldAlert, User } from 'lucide-react';
import { useAuthGuard } from '@/hooks/useAuthGuard';
import { useAuth } from '@/lib/providers/smart-providers';
import { IS_CE } from '@/lib/edition';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import Toast, { useToast } from '@/components/Toast';
import { ApiError } from '@/lib/api/api-client';
import { formatUtcDate } from '@/lib/utils/dateFormatters';
import { PARTNER_LINK_PARAM } from '@/lib/lifecycle/pendingRewardCode';
import {
  partnerAdminApi,
  type AmountsByCurrency,
  type PartnerCodeRow,
  type PartnerProgramDefaults,
} from '@/lib/api/services/partner-admin-api.service';

const OVERVIEW_KEY = ['admin', 'partner-program'];

/** Empty field = use the server default; otherwise an integer. */
function intOrUndefined(v: string): number | undefined {
  if (v.trim() === '') return undefined;
  const n = Number(v);
  return Number.isFinite(n) ? Math.trunc(n) : undefined;
}

/**
 * Admin screen of the partner / influencer program (cloud only, admin only).
 *
 * <p>Creates the two kinds of code (a single-use creator code: complimentary plan + PAYG
 * credits; a partner code: credits for the audience + a revenue share for the partner), shows
 * the link to hand out, and reports per code what it brought and what is owed. Money never
 * moves here: "Mark paid" records that the admin transferred the payable commissions.
 */
export default function PartnersAdminPage() {
  const { isAuthenticated, isAuthChecking, isLoading: isAuthLoading } = useAuthGuard();
  const { loginWithRedirect, hasRole } = useAuth();
  const t = useTranslations('partnerProgram');
  const tSettings = useTranslations('settings');
  const locale = useLocale();
  const queryClient = useQueryClient();
  const { toasts, addToast, removeToast } = useToast();
  const isAdmin = hasRole('ADMIN');

  const overview = useQuery({
    queryKey: OVERVIEW_KEY,
    queryFn: () => partnerAdminApi.overview(),
    enabled: !IS_CE && isAuthenticated && isAdmin,
    retry: false,
  });

  const fail = (err: unknown) => {
    const code = err instanceof ApiError ? err.code : undefined;
    const known = code && t.has(`errors.${code}`) ? t(`errors.${code}`) : t('errors.generic');
    addToast({ type: 'error', title: t('toasts.failureTitle'), message: known, duration: 8000 });
  };
  const refresh = () => queryClient.invalidateQueries({ queryKey: OVERVIEW_KEY });

  const setActive = useMutation({
    mutationFn: ({ id, active }: { id: number; active: boolean }) => partnerAdminApi.setActive(id, active),
    onSuccess: refresh,
    onError: fail,
  });
  const markPaid = useMutation({
    mutationFn: (id: number) => partnerAdminApi.markPaid(id),
    onSuccess: (res) => {
      addToast({
        type: 'success',
        title: t('toasts.paidTitle'),
        message: t('toasts.paidMessage', { lines: res.lines, amount: formatAmounts(res.amounts, locale) || '0' }),
        duration: 6000,
      });
      void refresh();
    },
    onError: fail,
  });

  const copy = async (text: string) => {
    try {
      await navigator.clipboard.writeText(text);
      addToast({ type: 'success', title: t('toasts.copied'), message: text, duration: 3000 });
    } catch {
      addToast({ type: 'error', title: t('toasts.failureTitle'), message: text, duration: 6000 });
    }
  };

  if (IS_CE) {
    return (
      <div className="flex flex-col items-center justify-center py-16 text-center">
        <ShieldAlert className="h-12 w-12 text-theme-tertiary mb-4" />
        <h2 className="text-base font-medium text-theme-primary mb-2">{t('selfHosted.title')}</h2>
        <p className="text-sm text-theme-secondary">{t('selfHosted.body')}</p>
      </div>
    );
  }
  if (isAuthChecking || isAuthLoading) {
    return <div className="bg-theme-secondary rounded-xl p-6 animate-pulse h-24" />;
  }
  if (!isAuthenticated) {
    return (
      <div className="min-h-[300px] flex items-center justify-center text-center">
        <div>
          <h1 className="text-2xl font-bold text-theme-primary mb-4">{tSettings('unauthorized')}</h1>
          <p className="text-theme-secondary mb-6">{tSettings('mustBeLoggedIn')}</p>
          <Button onClick={() => loginWithRedirect()} size="sm" className="h-8 px-3">
            <User className="h-3.5 w-3.5 mr-1" />
            {tSettings('signIn')}
          </Button>
        </div>
      </div>
    );
  }
  if (!isAdmin) {
    return (
      <div className="min-h-[300px] flex items-center justify-center text-center">
        <div>
          <Shield className="w-10 h-10 text-theme-muted mx-auto mb-3" />
          <h2 className="text-lg font-semibold text-theme-primary mb-2">{tSettings('unauthorized')}</h2>
          <p className="text-sm text-theme-secondary">{t('errors.adminOnly')}</p>
        </div>
      </div>
    );
  }

  const origin = typeof window !== 'undefined' ? window.location.origin : '';
  const defaults = overview.data?.defaults;

  return (
    <div className="space-y-8">
      {toasts.length > 0 && (
        <div className="fixed top-4 right-4 z-50 flex flex-col gap-2">
          {toasts.map((toast) => (
            <Toast key={toast.id} id={toast.id} type={toast.type} title={toast.title} message={toast.message} onClose={removeToast} />
          ))}
        </div>
      )}

      <div className="bg-theme-secondary rounded-xl p-6 flex items-center gap-3">
        <div className="w-10 h-10 bg-theme-tertiary rounded-xl flex items-center justify-center">
          <Handshake className="w-5 h-5 text-theme-primary" />
        </div>
        <div>
          <h2 className="text-lg font-semibold text-theme-primary">{t('title')}</h2>
          <p className="text-sm text-theme-secondary">{t('subtitle')}</p>
        </div>
      </div>

      {defaults && (
        <div className="grid gap-6 lg:grid-cols-2">
          <CreatorCodeForm defaults={defaults} onCreated={refresh} onError={fail} onDone={(code) => {
            addToast({ type: 'success', title: t('toasts.createdTitle'), message: code, duration: 6000 });
          }} />
          <PartnerCodeForm defaults={defaults} onCreated={refresh} onError={fail} onDone={(code) => {
            addToast({ type: 'success', title: t('toasts.createdTitle'), message: code, duration: 6000 });
          }} />
        </div>
      )}

      <div className="bg-theme-secondary rounded-xl p-6">
        <h3 className="text-base font-semibold text-theme-primary mb-4">{t('list.title')}</h3>
        {overview.isLoading && <p className="text-sm text-theme-secondary">{t('list.loading')}</p>}
        {overview.isError && <p className="text-sm text-red-500">{t('errors.generic')}</p>}
        {overview.data && overview.data.codes.length === 0 && (
          <p className="text-sm text-theme-secondary">{t('list.empty')}</p>
        )}
        {overview.data && overview.data.codes.length > 0 && (
          <div className="overflow-x-auto">
            <table className="w-full text-sm">
              <thead>
                <tr className="text-left text-theme-secondary border-b border-theme">
                  <th className="py-2 pr-4 font-medium">{t('list.code')}</th>
                  <th className="py-2 pr-4 font-medium">{t('list.benefit')}</th>
                  <th className="py-2 pr-4 font-medium">{t('list.redemptions')}</th>
                  <th className="py-2 pr-4 font-medium">{t('list.commissions')}</th>
                  <th className="py-2 font-medium">{t('list.actions')}</th>
                </tr>
              </thead>
              <tbody>
                {overview.data.codes.map((row) => (
                  <CodeRow
                    key={row.id}
                    row={row}
                    locale={locale}
                    link={row.kind === 'partner' ? `${origin}/?${PARTNER_LINK_PARAM}=${row.code}` : `${origin}/redeem?code=${row.code}`}
                    onCopy={copy}
                    onToggle={() => setActive.mutate({ id: row.id, active: !row.active })}
                    onMarkPaid={() => markPaid.mutate(row.id)}
                    busy={setActive.isPending || markPaid.isPending}
                  />
                ))}
              </tbody>
            </table>
          </div>
        )}
      </div>
    </div>
  );
}

/** "12.00 USD, 3.50 EUR" in the app locale; empty string when nothing. */
function formatAmounts(amounts: AmountsByCurrency | undefined, locale: string): string {
  if (!amounts) return '';
  return Object.entries(amounts)
    .filter(([, minor]) => minor > 0)
    .map(([currency, minor]) => {
      try {
        return (minor / 100).toLocaleString(locale, { style: 'currency', currency: currency.toUpperCase() });
      } catch {
        return `${(minor / 100).toLocaleString(locale)} ${currency.toUpperCase()}`;
      }
    })
    .join(', ');
}

function CodeRow({
  row, locale, link, onCopy, onToggle, onMarkPaid, busy,
}: {
  row: PartnerCodeRow;
  locale: string;
  link: string;
  onCopy: (text: string) => void;
  onToggle: () => void;
  onMarkPaid: () => void;
  busy: boolean;
}) {
  const t = useTranslations('partnerProgram');
  const credits = row.credits.toLocaleString(locale);
  const benefit = row.kind === 'partner'
    ? t('list.partnerBenefit', { credits, percent: row.commission_percent ?? 0, months: row.commission_months ?? 0 })
    : row.plan_code
      ? t('list.creatorBenefitPlan', { credits, plan: row.plan_code, days: row.plan_days })
      : t('list.creatorBenefitCredits', { credits });
  const payable = formatAmounts(row.commissions.payable, locale);
  const onHold = formatAmounts(row.commissions.on_hold, locale);
  const paid = formatAmounts(row.commissions.paid, locale);
  return (
    <tr className="border-b border-theme align-top">
      <td className="py-3 pr-4">
        <div className="font-mono text-theme-primary">{row.code}</div>
        <div className="text-xs text-theme-secondary">
          {row.kind === 'partner' ? t('list.kindPartner') : t('list.kindCreator')}
          {row.owner_email ? ` · ${row.owner_email}` : ''}
          {row.label ? ` · ${row.label}` : ''}
        </div>
        {!row.active && <div className="text-xs text-amber-600">{t('list.inactive')}</div>}
        {row.valid_until && (
          <div className="text-xs text-theme-secondary">{t('list.validUntil', { date: formatUtcDate(row.valid_until, { locale }) })}</div>
        )}
      </td>
      <td className="py-3 pr-4 text-theme-primary">{benefit}</td>
      <td className="py-3 pr-4 text-theme-primary">
        {row.max_uses != null ? t('list.usesOf', { used: row.redemptions, max: row.max_uses }) : row.redemptions}
        {row.kind === 'partner' && (
          <div className="text-xs text-theme-secondary">{t('list.payingCustomers', { count: row.paying_customers })}</div>
        )}
      </td>
      <td className="py-3 pr-4 text-theme-primary">
        {row.kind === 'partner' ? (
          <div className="space-y-0.5">
            <div>{t('list.payable')}: {payable || '-'}</div>
            <div className="text-xs text-theme-secondary">{t('list.onHold')}: {onHold || '-'}</div>
            <div className="text-xs text-theme-secondary">{t('list.paid')}: {paid || '-'}</div>
          </div>
        ) : '-'}
      </td>
      <td className="py-3">
        <div className="flex flex-wrap gap-2">
          <Button size="sm" variant="outline" className="gap-1" onClick={() => onCopy(link)}>
            <Copy className="h-3.5 w-3.5" />
            {t('list.copyLink')}
          </Button>
          <Button size="sm" variant="outline" disabled={busy} onClick={onToggle}>
            {row.active ? t('list.deactivate') : t('list.activate')}
          </Button>
          {row.kind === 'partner' && payable && (
            <Button size="sm" disabled={busy} onClick={onMarkPaid}>{t('list.markPaid')}</Button>
          )}
        </div>
      </td>
    </tr>
  );
}

function NumberField({ id, label, value, onChange, hint }: {
  id: string; label: string; value: string; onChange: (v: string) => void; hint?: string;
}) {
  return (
    <div className="space-y-1">
      <Label htmlFor={id} className="text-sm">{label}</Label>
      <Input id={id} inputMode="numeric" value={value} onChange={(e) => onChange(e.target.value)} className="h-9 text-sm" />
      {hint && <p className="text-xs text-theme-secondary">{hint}</p>}
    </div>
  );
}

function CreatorCodeForm({ defaults, onCreated, onError, onDone }: {
  defaults: PartnerProgramDefaults;
  onCreated: () => void;
  onError: (e: unknown) => void;
  onDone: (code: string) => void;
}) {
  const t = useTranslations('partnerProgram');
  const [code, setCode] = useState('');
  const [label, setLabel] = useState('');
  const [plan, setPlan] = useState(defaults.creatorPlanCode);
  const [planDays, setPlanDays] = useState(String(defaults.creatorPlanDays));
  const [credits, setCredits] = useState(String(defaults.creatorCredits));
  const [maxUses, setMaxUses] = useState(String(defaults.creatorMaxUses));
  const [validDays, setValidDays] = useState(String(defaults.creatorValidDays));

  const create = useMutation({
    mutationFn: () => partnerAdminApi.createCreatorCode({
      code: code.trim() || undefined,
      label: label.trim() || undefined,
      plan_code: plan,
      plan_days: intOrUndefined(planDays),
      credits: intOrUndefined(credits),
      max_uses: intOrUndefined(maxUses),
      valid_days: intOrUndefined(validDays),
    }),
    onSuccess: (res) => {
      onDone(res.code.code);
      setCode('');
      setLabel('');
      onCreated();
    },
    onError,
  });

  return (
    <form
      onSubmit={(e) => { e.preventDefault(); create.mutate(); }}
      className="bg-theme-secondary rounded-xl p-6 space-y-4"
    >
      <div>
        <h3 className="text-base font-semibold text-theme-primary">{t('creator.title')}</h3>
        <p className="text-sm text-theme-secondary">{t('creator.subtitle')}</p>
      </div>
      <div className="grid gap-4 sm:grid-cols-2">
        <div className="space-y-1">
          <Label htmlFor="creator-code" className="text-sm">{t('fields.code')}</Label>
          <Input id="creator-code" value={code} onChange={(e) => setCode(e.target.value)} placeholder={t('fields.codeAuto')} className="h-9 text-sm" />
        </div>
        <div className="space-y-1">
          <Label htmlFor="creator-label" className="text-sm">{t('fields.label')}</Label>
          <Input id="creator-label" value={label} onChange={(e) => setLabel(e.target.value)} className="h-9 text-sm" />
        </div>
        <div className="space-y-1">
          <Label htmlFor="creator-plan" className="text-sm">{t('fields.plan')}</Label>
          <select
            id="creator-plan"
            value={plan}
            onChange={(e) => setPlan(e.target.value)}
            className="h-9 w-full rounded-md border border-theme bg-theme-tertiary px-2 text-sm text-theme-primary"
          >
            <option value="PRO">PRO</option>
            <option value="STARTER">STARTER</option>
            <option value="TEAM">TEAM</option>
            <option value="NONE">{t('fields.planNone')}</option>
          </select>
        </div>
        <NumberField id="creator-plan-days" label={t('fields.planDays')} value={planDays} onChange={setPlanDays} />
        <NumberField id="creator-credits" label={t('fields.credits')} value={credits} onChange={setCredits} hint={t('fields.creditsHint')} />
        <NumberField id="creator-max-uses" label={t('fields.maxUses')} value={maxUses} onChange={setMaxUses} />
        <NumberField id="creator-valid-days" label={t('fields.validDays')} value={validDays} onChange={setValidDays} hint={t('fields.validDaysHint')} />
      </div>
      <Button type="submit" size="sm" disabled={create.isPending}>{t('creator.submit')}</Button>
    </form>
  );
}

function PartnerCodeForm({ defaults, onCreated, onError, onDone }: {
  defaults: PartnerProgramDefaults;
  onCreated: () => void;
  onError: (e: unknown) => void;
  onDone: (code: string) => void;
}) {
  const t = useTranslations('partnerProgram');
  const [email, setEmail] = useState('');
  const [code, setCode] = useState('');
  const [label, setLabel] = useState('');
  const [credits, setCredits] = useState(String(defaults.audienceCredits));
  const [percent, setPercent] = useState(String(defaults.commissionBps / 100));
  const [months, setMonths] = useState(String(defaults.commissionMonths));
  const [holdDays, setHoldDays] = useState(String(defaults.holdDays));
  const [maxUses, setMaxUses] = useState('');

  const create = useMutation({
    mutationFn: () => partnerAdminApi.createPartnerCode({
      partner_email: email.trim(),
      code: code.trim() || undefined,
      label: label.trim() || undefined,
      audience_credits: intOrUndefined(credits),
      commission_percent: percent.trim() === '' ? undefined : Number(percent),
      commission_months: intOrUndefined(months),
      hold_days: intOrUndefined(holdDays),
      max_uses: intOrUndefined(maxUses),
    }),
    onSuccess: (res) => {
      onDone(res.code.code);
      setEmail('');
      setCode('');
      setLabel('');
      onCreated();
    },
    onError,
  });

  return (
    <form
      onSubmit={(e) => { e.preventDefault(); create.mutate(); }}
      className="bg-theme-secondary rounded-xl p-6 space-y-4"
    >
      <div>
        <h3 className="text-base font-semibold text-theme-primary">{t('partner.title')}</h3>
        <p className="text-sm text-theme-secondary">{t('partner.subtitle')}</p>
      </div>
      <div className="grid gap-4 sm:grid-cols-2">
        <div className="space-y-1 sm:col-span-2">
          <Label htmlFor="partner-email" className="text-sm">{t('fields.partnerEmail')}</Label>
          <Input id="partner-email" type="email" required value={email} onChange={(e) => setEmail(e.target.value)} className="h-9 text-sm" />
          <p className="text-xs text-theme-secondary">{t('fields.partnerEmailHint')}</p>
        </div>
        <div className="space-y-1">
          <Label htmlFor="partner-code" className="text-sm">{t('fields.code')}</Label>
          <Input id="partner-code" value={code} onChange={(e) => setCode(e.target.value)} placeholder={t('fields.codeAuto')} className="h-9 text-sm" />
        </div>
        <div className="space-y-1">
          <Label htmlFor="partner-label" className="text-sm">{t('fields.label')}</Label>
          <Input id="partner-label" value={label} onChange={(e) => setLabel(e.target.value)} className="h-9 text-sm" />
        </div>
        <NumberField id="partner-credits" label={t('fields.audienceCredits')} value={credits} onChange={setCredits} hint={t('fields.creditsHint')} />
        <NumberField id="partner-percent" label={t('fields.commissionPercent')} value={percent} onChange={setPercent} />
        <NumberField id="partner-months" label={t('fields.commissionMonths')} value={months} onChange={setMonths} />
        <NumberField id="partner-hold" label={t('fields.holdDays')} value={holdDays} onChange={setHoldDays} hint={t('fields.holdDaysHint')} />
        <NumberField id="partner-max-uses" label={t('fields.partnerMaxUses')} value={maxUses} onChange={setMaxUses} hint={t('fields.partnerMaxUsesHint')} />
      </div>
      <Button type="submit" size="sm" disabled={create.isPending || !email.trim()}>{t('partner.submit')}</Button>
    </form>
  );
}
