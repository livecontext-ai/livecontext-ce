'use client';

/**
 * Redeem-a-reward-code card (referral, promo, creator or partner code).
 *
 * A signed-in user enters a code to claim a benefit: an immediate benefit (credits, a
 * complimentary plan), or a referral that pays out when they take a paid subscription. The
 * redeem itself, its error mapping and its success wording live in {@link useRedeemCode}, shared
 * with the "Have a code?" line shown next to every Stripe checkout ({@link RewardCodeInline}).
 *
 * Style mirrors the former RedeemPromoCard / BalanceBreakdownCard: theme tokens,
 * `text-sm` default, lucide icons at `h-3.5 w-3.5`.
 */

import React, { useState } from 'react';
import { Gift, Check, Loader2 } from 'lucide-react';
import { useTranslations } from 'next-intl';
import { Button } from '@/components/ui/button';
import { useRedeemCode } from './useRedeemCode';

export { redeemErrorKey, useRedeemSuccessMessage } from './redeemMessages';

export function RewardRedeemCard({ prefilledCode = '' }: { prefilledCode?: string }) {
  const t = useTranslations('reward.redeem');
  const [code, setCode] = useState(prefilledCode);
  const { redeem, submitting, errorKey, successText, clearError } = useRedeemCode();

  const handleRedeem = async () => {
    if (await redeem(code)) setCode('');
  };

  return (
    <div className="rounded-xl border border-theme p-6">
      <div className="flex items-center gap-3 mb-4">
        <div className="w-10 h-10 bg-theme-secondary rounded-xl flex items-center justify-center">
          <Gift className="w-5 h-5 text-theme-primary" />
        </div>
        <div>
          <h2 className="text-lg font-semibold text-theme-primary">{t('title')}</h2>
          <p className="text-sm text-theme-secondary">{t('subtitle')}</p>
        </div>
      </div>

      {successText && (
        <div className="mb-4 flex items-start gap-2 rounded-lg bg-theme-secondary p-3 text-sm">
          <Check className="h-3.5 w-3.5 mt-0.5 text-emerald-500 flex-shrink-0" />
          <div className="text-theme-primary">{successText}</div>
        </div>
      )}

      <div className="flex flex-wrap items-center gap-2">
        <input
          type="text"
          value={code}
          onChange={(e) => {
            setCode(e.target.value);
            if (errorKey) clearError();
          }}
          onKeyDown={(e) => {
            if (e.key === 'Enter') void handleRedeem();
          }}
          placeholder={t('placeholder')}
          autoCapitalize="characters"
          spellCheck={false}
          className="h-9 min-w-0 flex-1 rounded-[10px] border border-theme bg-theme-tertiary px-3 text-sm text-theme-primary placeholder:text-theme-muted focus:outline-none focus:ring-1 focus:ring-theme"
        />
        <Button
          onClick={() => void handleRedeem()}
          disabled={submitting || code.trim().length === 0}
          variant="default"
          size="sm"
          className="gap-1"
        >
          {submitting ? (
            <Loader2 className="h-3.5 w-3.5 animate-spin" />
          ) : (
            <Gift className="h-3.5 w-3.5" />
          )}
          {submitting ? t('redeeming') : t('button')}
        </Button>
      </div>

      {errorKey && (
        <p className="mt-2 text-sm text-red-500" role="alert">
          {t(errorKey)}
        </p>
      )}
    </div>
  );
}

export default RewardRedeemCard;
