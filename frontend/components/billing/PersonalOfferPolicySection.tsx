'use client';

import React, { useEffect, useState } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { useLocale, useTranslations } from 'next-intl';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { CREDIT_TIERS, STARTER_MAX_CREDITS, PAYG_USD_PER_1K } from '@/lib/billing/pricing-constants';
import { formatUtcDateTime } from '@/lib/utils/dateFormatters';
import {
  personalOfferAdminApi,
  type OfferPolicy,
  type OfferPolicyInput,
} from '@/lib/api/services/personal-offer-admin.service';

const PLANS = ['STARTER', 'PRO', 'TEAM'] as const;
const CAMPAIGN_KEY = 'free-credit-upgrade';

function emptyDraft(): OfferPolicyInput {
  return {
    campaignKey: CAMPAIGN_KEY,
    label: '',
    waitHours: 4,
    validityHours: 72,
    checkoutHoldMinutes: 30,
    reminderEnabled: false,
    reminderHours: 12,
    paygCreditsPerUsd: 1000 / PAYG_USD_PER_1K,
    allowConversionStack: false,
    matrix: [],
  };
}

function editable(policy: OfferPolicy): OfferPolicyInput {
  const { id: _id, version: _version, state: _state, ...input } = policy;
  return { ...input, allowConversionStack: false, matrix: [...policy.matrix] };
}

/** Draft policy editor in the existing admin Credits & Plans page. */
export default function PersonalOfferPolicySection() {
  const t = useTranslations('adminCredits.personalOffers');
  const locale = useLocale();
  const queryClient = useQueryClient();
  const policiesQuery = useQuery({
    queryKey: ['admin', 'personal-offer', 'policies'],
    queryFn: personalOfferAdminApi.getPolicies,
  });
  const codesQuery = useQuery({
    queryKey: ['admin', 'personal-offer', 'codes'],
    queryFn: personalOfferAdminApi.getCodes,
  });
  const policies = policiesQuery.data?.policies ?? [];
  const [selectedId, setSelectedId] = useState<number | null>(null);
  const [creating, setCreating] = useState(false);
  const [draft, setDraft] = useState<OfferPolicyInput>(emptyDraft);
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState<{ text: string; error: boolean } | null>(null);
  const selected = policies.find((policy) => policy.id === selectedId) ?? policies[0];
  const canEdit = creating || !selected || selected.state === 'DRAFT';

  useEffect(() => {
    if (creating) return;
    if (selected) setDraft(editable(selected));
    else setDraft(emptyDraft());
  }, [creating, selected?.id, selected?.version, selected?.state]);

  const refresh = async () => {
    await queryClient.invalidateQueries({ queryKey: ['admin', 'personal-offer'] });
  };

  const setNumber = (key: 'waitHours' | 'validityHours' | 'checkoutHoldMinutes' | 'reminderHours' | 'paygCreditsPerUsd', value: string) => {
    setDraft((prior) => ({ ...prior, [key]: Number(value) }));
  };

  const setCell = (planCode: string, monthlyCredits: number, value: string) => {
    setDraft((prior) => ({
      ...prior,
      matrix: [
        ...prior.matrix.filter((cell) => cell.planCode !== planCode || cell.monthlyCredits !== monthlyCredits),
        ...(value.trim() === '' ? [] : [{ planCode, monthlyCredits, bonusCredits: Number(value) }]),
      ],
    }));
  };

  const save = async () => {
    if (!canEdit || busy) return;
    const invalid = !draft.label.trim() || draft.label.trim().length > 128 ||
      !Number.isInteger(draft.waitHours) || draft.waitHours < 0 || draft.waitHours > 8760 ||
      !Number.isInteger(draft.validityHours) || draft.validityHours < 1 || draft.validityHours > 8760 ||
      !Number.isInteger(draft.checkoutHoldMinutes) || draft.checkoutHoldMinutes < 30 || draft.checkoutHoldMinutes > 1440 ||
      !Number.isInteger(draft.reminderHours) || draft.reminderHours < 1 || draft.reminderHours >= draft.validityHours ||
      !Number.isInteger(draft.paygCreditsPerUsd) || draft.paygCreditsPerUsd < 1 ||
      draft.matrix.length === 0 || draft.matrix.length > 30 ||
      draft.matrix.some((cell) => !Number.isInteger(cell.bonusCredits) || cell.bonusCredits < 0);
    if (invalid) {
      setMessage({ text: t('invalid'), error: true });
      return;
    }
    setBusy(true);
    setMessage(null);
    try {
      const input = { ...draft, campaignKey: CAMPAIGN_KEY, allowConversionStack: false as const };
      const response = creating || !selected
        ? await personalOfferAdminApi.createPolicy(input)
        : await personalOfferAdminApi.updatePolicy(selected.id, input);
      setSelectedId(response.policy.id);
      setCreating(false);
      setMessage({ text: t('saved'), error: false });
      await refresh();
    } catch {
      setMessage({ text: t('saveFailed'), error: true });
    } finally {
      setBusy(false);
    }
  };

  const changeState = async (action: 'activate' | 'pause') => {
    if (!selected || busy || !window.confirm(t(action === 'activate' ? 'activateConfirm' : 'pauseConfirm'))) return;
    setBusy(true);
    try {
      if (action === 'activate') await personalOfferAdminApi.activatePolicy(selected.id);
      else await personalOfferAdminApi.pausePolicy(selected.id);
      setMessage({ text: t(action === 'activate' ? 'activated' : 'paused'), error: false });
      await refresh();
    } catch {
      setMessage({ text: t('stateFailed'), error: true });
    } finally {
      setBusy(false);
    }
  };

  const disableCode = async (id: number) => {
    if (busy || !window.confirm(t('disableConfirm'))) return;
    setBusy(true);
    try {
      await personalOfferAdminApi.disableCode(id);
      setMessage({ text: t('disabled'), error: false });
      await refresh();
    } catch {
      setMessage({ text: t('stateFailed'), error: true });
    } finally {
      setBusy(false);
    }
  };

  return (
    <section className="bg-theme-secondary rounded-xl p-4 sm:p-6 space-y-5" aria-labelledby="personal-offers-heading">
      <div>
        <h3 id="personal-offers-heading" className="text-base font-semibold text-theme-primary">{t('title')}</h3>
        <p className="text-sm text-theme-secondary">{t('subtitle')}</p>
      </div>
      {policiesQuery.isPending ? <p className="text-sm" role="status">{t('loading')}</p> : policiesQuery.isError ? (
        <div role="alert" className="text-sm text-red-600">{t('loadFailed')} <button type="button" className="underline" onClick={() => void policiesQuery.refetch()}>{t('retry')}</button></div>
      ) : (
        <>
          <div className="flex flex-wrap items-end gap-3">
            <div className="space-y-1">
              <Label htmlFor="personal-offer-policy">{t('version')}</Label>
              <select id="personal-offer-policy" value={creating ? 'new' : selected?.id ?? 'new'} onChange={(event) => {
                if (event.target.value === 'new') { setDraft(selected ? editable(selected) : emptyDraft()); setCreating(true); }
                else { setCreating(false); setSelectedId(Number(event.target.value)); }
                setMessage(null);
              }} className="h-9 rounded-md border border-theme bg-theme-tertiary px-3 text-sm text-theme-primary">
                {policies.map((policy) => <option key={policy.id} value={policy.id}>{t('versionOption', { version: policy.version, state: t(`states.${policy.state.toLowerCase()}`) })}</option>)}
                <option value="new">{t('newDraft')}</option>
              </select>
            </div>
            {!creating && selected && selected.state !== 'DRAFT' && <Button type="button" variant="outline" onClick={() => { setDraft(editable(selected)); setCreating(true); }}>{t('newDraftFromVersion')}</Button>}
            {(selected?.state === 'DRAFT' || selected?.state === 'PAUSED') && !creating && <Button type="button" variant="outline" disabled={busy} onClick={() => void changeState('activate')}>{t(selected.state === 'PAUSED' ? 'resume' : 'activate')}</Button>}
            {selected?.state === 'ACTIVE' && !creating && <Button type="button" variant="outline" disabled={busy} onClick={() => void changeState('pause')}>{t('pause')}</Button>}
          </div>

          <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-4 gap-4">
            <div className="space-y-1 sm:col-span-2"><Label htmlFor="offer-label">{t('label')}</Label><Input id="offer-label" value={draft.label} maxLength={128} disabled={!canEdit || busy} onChange={(event) => setDraft((prior) => ({ ...prior, label: event.target.value }))} /></div>
            {(['waitHours', 'validityHours', 'checkoutHoldMinutes', 'reminderHours', 'paygCreditsPerUsd'] as const).map((key) => (
              <div key={key} className="space-y-1"><Label htmlFor={`offer-${key}`}>{t(key)}</Label><Input id={`offer-${key}`} type="number" min={key === 'checkoutHoldMinutes' ? 30 : key === 'waitHours' ? 0 : 1} step="1" value={draft[key]} disabled={!canEdit || busy} onChange={(event) => setNumber(key, event.target.value)} /></div>
            ))}
            <label className="flex items-center gap-2 text-sm text-theme-primary"><input type="checkbox" checked={draft.reminderEnabled} disabled={!canEdit || busy} onChange={(event) => setDraft((prior) => ({ ...prior, reminderEnabled: event.target.checked }))} />{t('reminderEnabled')}</label>
          </div>
          <p className="text-sm text-theme-secondary">{t('noStacking')}</p>

          <div>
            <h4 className="text-sm font-semibold text-theme-primary">{t('matrixTitle')}</h4>
            <p className="text-sm text-theme-secondary">{t('matrixHelp')}</p>
            <div className="overflow-x-auto mt-3">
              <table className="w-full min-w-[34rem] border-collapse text-sm">
                <thead><tr><th scope="col" className="p-2 text-left">{t('monthlyCredits')}</th>{PLANS.map((plan) => <th key={plan} scope="col" className="p-2 text-left">{plan}</th>)}</tr></thead>
                <tbody>{CREDIT_TIERS.map((monthlyCredits) => <tr key={monthlyCredits} className="border-t border-theme">
                  <th scope="row" className="p-2 text-left font-normal">{monthlyCredits.toLocaleString(locale)}</th>
                  {PLANS.map((planCode) => {
                    const supported = planCode !== 'STARTER' || monthlyCredits <= STARTER_MAX_CREDITS;
                    const cell = draft.matrix.find((item) => item.planCode === planCode && item.monthlyCredits === monthlyCredits);
                    return <td key={planCode} className="p-2">{supported ? <Input type="number" min="0" step="1" value={cell?.bonusCredits ?? ''} disabled={!canEdit || busy} aria-label={t('cellLabel', { plan: planCode, credits: monthlyCredits.toLocaleString(locale) })} onChange={(event) => setCell(planCode, monthlyCredits, event.target.value)} className="w-28" /> : <span className="text-theme-muted">{t('notAvailable')}</span>}</td>;
                  })}
                </tr>)}</tbody>
              </table>
            </div>
          </div>
          {canEdit && <div className="flex justify-end"><Button type="button" disabled={busy} onClick={() => void save()}>{busy ? t('saving') : t('saveDraft')}</Button></div>}
          {message && <p role={message.error ? 'alert' : 'status'} className={message.error ? 'text-sm text-red-600' : 'text-sm text-emerald-600'}>{message.text}</p>}
        </>
      )}

      <div className="border-t border-theme pt-4">
        <h4 className="text-sm font-semibold text-theme-primary">{t('issuedTitle')}</h4>
        {codesQuery.isPending ? <p className="text-sm" role="status">{t('loading')}</p> : codesQuery.isError ? <p className="text-sm text-red-600" role="alert">{t('loadFailed')}</p> : (
          <div className="overflow-x-auto mt-2"><table className="w-full min-w-[38rem] text-sm"><thead><tr><th className="p-2 text-left">{t('recipient')}</th><th className="p-2 text-left">{t('issuedAt')}</th><th className="p-2 text-left">{t('expiresAt')}</th><th className="p-2 text-left">{t('status')}</th><th className="p-2 text-left">{t('action')}</th></tr></thead><tbody>
            {(codesQuery.data?.codes ?? []).slice(0, 20).map((code) => <tr key={code.id} className="border-t border-theme"><td className="p-2">#{code.recipientUserId}</td><td className="p-2">{formatUtcDateTime(code.issuedAt, { locale })}</td><td className="p-2">{formatUtcDateTime(code.expiresAt, { locale })}</td><td className="p-2">{t(`codeStatuses.${code.status.toLowerCase()}` as 'codeStatuses.available')}</td><td className="p-2">{code.active && <button type="button" disabled={busy} className="underline" onClick={() => void disableCode(code.id)}>{t('disable')}</button>}</td></tr>)}
            {(codesQuery.data?.codes ?? []).length === 0 && <tr><td colSpan={5} className="p-2 text-theme-secondary">{t('noCodes')}</td></tr>}
          </tbody></table></div>
        )}
      </div>
    </section>
  );
}
