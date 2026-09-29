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
 * (not applied yet, e.g. the email is not verified), the line says so and pre-fills it.
 */

import React, { useEffect, useState } from 'react';
import { Check, Gift, Loader2 } from 'lucide-react';
import { useTranslations } from 'next-intl';
import { Button } from '@/components/ui/button';
import { readPendingRewardCode } from '@/lib/lifecycle/pendingRewardCode';
import { useRedeemCode } from './useRedeemCode';

export function RewardCodeInline({ className = '' }: { className?: string }) {
  const t = useTranslations('reward.redeem');
  const tInline = useTranslations('reward.inline');
  const [open, setOpen] = useState(false);
  const [code, setCode] = useState('');
  const [pending, setPending] = useState<string | null>(null);
  const { redeem, submitting, errorKey, successText, clearError } = useRedeemCode();

  // Reading browser storage is a client-only side effect, not a fetch.
  useEffect(() => {
    if (typeof window === 'undefined') return;
    const waiting = readPendingRewardCode(window);
    if (waiting) {
      setPending(waiting);
      setCode(waiting);
    }
  }, []);

  const submit = async () => {
    if (await redeem(code)) {
      setCode('');
      setPending(null);
    }
  };

  if (successText) {
    return (
      <div className={`flex items-start gap-2 text-sm text-theme-primary ${className}`} role="status">
        <Check className="h-3.5 w-3.5 mt-0.5 text-emerald-500 flex-shrink-0" />
        <span>{successText}</span>
      </div>
    );
  }

  if (!open) {
    return (
      <div className={`text-sm ${className}`}>
        <button
          type="button"
          onClick={() => setOpen(true)}
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
      <div className="flex flex-wrap items-center gap-2">
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
