'use client';

/**
 * "Have a code?" line shown next to every Stripe checkout: the pricing page (plans and
 * pay-as-you-go), the top-up modal, and the insufficient credits / storage modals. Those are
 * the only four places that send someone to Stripe; every other upgrade button leads to the
 * pricing page, so the line is in front of every payment.
 *
 * A code is applied HERE, before Stripe: Stripe's own promotion-code field can only discount a
 * price, while our codes grant credits or a plan, and a partner code must attribute the person
 * BEFORE their first payment (an already-paying customer is never attributed).
 *
 * Collapsed to one discreet link by default. When a code from a partner link is still waiting
 * (not applied yet, e.g. the email is not verified), the line says so and pre-fills it. Once
 * opened, the field can be closed again (the X button or Escape), back to the discreet link.
 */

import React, { useEffect, useId, useRef, useState } from 'react';
import { Check, Gift, Loader2, X } from 'lucide-react';
import { useLocale, useTranslations } from 'next-intl';
import { Button } from '@/components/ui/button';
import { readPendingRewardCode } from '@/lib/lifecycle/pendingRewardCode';
import { usePersonalOffer } from '@/lib/hooks/usePersonalOffer';
import { formatUtcDateTime } from '@/lib/utils/dateFormatters';
import { useRedeemCode } from './useRedeemCode';

export function RewardCodeInline({
  className = '',
  subscriptionCheckout = false,
  creditTierIndex = 0,
  billingCycle = 'yearly',
}: {
  className?: string;
  subscriptionCheckout?: boolean;
  creditTierIndex?: number;
  billingCycle?: 'monthly' | 'yearly';
}) {
  const t = useTranslations('reward.redeem');
  const tInline = useTranslations('reward.inline');
  const tOffer = useTranslations('reward.personalOffer');
  const locale = useLocale();
  const inputId = useId();
  const [open, setOpen] = useState(false);
  const [code, setCode] = useState('');
  const [pending, setPending] = useState<string | null>(null);
  const { redeem, submitting, errorKey, successText, lastResult, clearError } = useRedeemCode();
  const personal = usePersonalOffer(creditTierIndex, billingCycle, subscriptionCheckout);
  const toggleRef = useRef<HTMLButtonElement>(null);
  const returnFocus = useRef(false);

  // After the field is closed, hand focus back to the link that opened it.
  useEffect(() => {
    if (!open && returnFocus.current) {
      returnFocus.current = false;
      toggleRef.current?.focus();
    }
  }, [open]);

  // Reading browser storage is a client-only side effect, not a fetch.
  useEffect(() => {
    if (typeof window === 'undefined') return;
    const waiting = readPendingRewardCode(window);
    if (waiting) {
      setPending(waiting);
      setCode(waiting);
    }
  }, []);

  // Not while a code is being applied: its answer would land on a collapsed field.
  const close = () => {
    if (submitting) return;
    returnFocus.current = true;
    setOpen(false);
    if (errorKey) clearError();
  };

  const reopen = () => {
    if (errorKey) clearError();
    setOpen(true);
  };

  const submit = async () => {
    if (await redeem(code)) {
      setCode('');
      setPending(null);
    }
  };

  const offerStatus = personal.current?.status;
  const hasOffer = subscriptionCheckout && !!personal.current?.offerId;
  const expiresAt = personal.current?.expiresAt;
  const sessionExpiresAt = personal.current?.sessionExpiresAt;
  const offerError = subscriptionCheckout && personal.isError;
  const lockedStatus = offerStatus === 'REVIEW_REQUIRED' || offerStatus === 'ALREADY_USED';
  const personalReady = subscriptionCheckout && lastResult?.code === 'OFFER_READY';
  const personalCandidate = subscriptionCheckout && !!personal.candidateCode;

  if (successText && (lastResult?.code !== 'OFFER_READY' || !subscriptionCheckout)) {
    return (
      <div className={`flex items-start gap-2 text-sm text-theme-primary ${className}`} role="status">
        <Check className="h-3.5 w-3.5 mt-0.5 text-emerald-500 flex-shrink-0" />
        <span>{successText}</span>
      </div>
    );
  }

  if ((!open || lockedStatus) && (hasOffer || offerError || personalCandidate || personalReady)) {
    const error = personal.errorCode;
    const errorKey = error === 'OFFER_EXPIRED' ? 'expired'
      : error === 'OFFER_ALREADY_USED' ? 'alreadyUsed'
      : error === 'OFFER_CONFLICT' ? 'conflict'
      : error === 'OFFER_NOT_ELIGIBLE' ? 'notEligible'
      : error === 'OFFER_CHECKOUT_ACTIVE' ? 'checkoutActive'
      : error === 'OFFER_REVIEW_REQUIRED' ? 'reviewRequired'
      : error === 'OFFER_UNAVAILABLE' ? 'unavailable'
      : 'generic';
    return (
      <div className={`text-sm ${className}`}>
        <div role={offerError || offerStatus === 'REVIEW_REQUIRED' ? 'alert' : 'status'} className="flex flex-wrap items-center justify-center gap-2 text-theme-primary">
          <Gift className="h-3.5 w-3.5 shrink-0" aria-hidden="true" />
          <span>
            {offerError ? tOffer(`errors.${errorKey}` as 'errors.generic')
              : hasOffer && offerStatus === 'GRANTED' ? tOffer('granted', { credits: (personal.current?.grantedCredits ?? 0).toLocaleString(locale) })
              : hasOffer && offerStatus === 'NO_BONUS' ? tOffer('usedWithoutBonus')
              : hasOffer && offerStatus === 'CLAWED_BACK' ? tOffer('reversed')
              : hasOffer && offerStatus === 'REVIEW_REQUIRED' ? tOffer('reviewRequired')
              : hasOffer && offerStatus === 'ALREADY_USED' ? tOffer('usedWithoutOffer')
              : hasOffer && offerStatus === 'PENDING_PAYMENT' ? tOffer('paymentPending')
              : hasOffer && offerStatus === 'PROCESSING' ? tOffer('processing')
              : hasOffer && offerStatus === 'CHECKOUT_OPEN' && sessionExpiresAt ? tOffer('reservedUntil', { date: formatUtcDateTime(sessionExpiresAt, { locale }) })
              : hasOffer && offerStatus === 'CHECKOUT_OPEN' ? tOffer('errors.checkoutActive')
              : hasOffer && offerStatus === 'EXPIRED' ? tOffer('errors.expired')
              : hasOffer && offerStatus === 'DISABLED' ? tOffer('errors.unavailable')
              : hasOffer && offerStatus === 'CONFLICT' ? tOffer('errors.conflict')
              : hasOffer && offerStatus === 'CHECKOUT_CREATING' ? tOffer('errors.checkoutActive')
              : hasOffer && expiresAt ? tOffer('appliedUntil', { date: formatUtcDateTime(expiresAt, { locale }) })
              : hasOffer ? tOffer('applied')
              : personalReady ? tOffer('checking')
              : personal.isAuthenticated ? tOffer('checking') : tOffer('signIn')}
          </span>
          {!lockedStatus && <button ref={toggleRef} type="button" onClick={reopen} className="underline underline-offset-2 focus-visible:outline-2 focus-visible:outline-theme-primary">
            {tOffer('changeCode')}
          </button>}
        </div>
      </div>
    );
  }

  if (!open) {
    return (
      <div className={`text-sm ${className}`}>
        <button
          ref={toggleRef}
          type="button"
          onClick={reopen}
          aria-expanded={false}
          aria-controls={inputId}
          className="inline-flex items-center gap-1 text-theme-secondary hover:text-theme-primary underline"
        >
          <Gift className="h-3.5 w-3.5" />
          {pending ? tInline('pending', { code: pending }) : tInline('toggle')}
        </button>
      </div>
    );
  }

  return (
    <div className={`text-sm ${className}`}>
      {hasOffer && <p className="mb-2 text-theme-primary" role="status">{tOffer('applied')}</p>}
      <div id={inputId} className="flex flex-wrap items-center gap-2">
        <input
          type="text"
          value={code}
          autoFocus
          aria-label={t('placeholder')}
          onChange={(e) => {
            setCode(e.target.value);
            if (errorKey) clearError();
          }}
          onKeyDown={(e) => {
            if (e.key === 'Enter') void submit();
            if (e.key === 'Escape') close();
          }}
          placeholder={t('placeholder')}
          autoCapitalize="characters"
          spellCheck={false}
          className="h-8 w-48 min-w-0 rounded-[10px] border border-theme bg-theme-tertiary px-3 text-sm text-theme-primary placeholder:text-theme-muted focus:outline-none focus:ring-1 focus:ring-theme"
        />
        <Button size="sm" onClick={() => void submit()} disabled={submitting || code.trim().length === 0} className="h-8 gap-1">
          {submitting && <Loader2 className="h-3.5 w-3.5 animate-spin" />}
          {submitting ? t('redeeming') : tInline('apply')}
        </Button>
        <button
          type="button"
          onClick={close}
          disabled={submitting}
          aria-label={tInline('close')}
          title={tInline('close')}
          className="inline-flex h-8 w-8 items-center justify-center rounded-lg text-theme-muted transition-colors hover:bg-surface-hover hover:text-theme-primary disabled:opacity-50"
        >
          <X className="h-3.5 w-3.5" />
        </button>
      </div>
      {errorKey && (
        <p className="mt-1 text-sm text-red-500" role="alert">
          {t(errorKey)}
        </p>
      )}
    </div>
  );
}

export default RewardCodeInline;
