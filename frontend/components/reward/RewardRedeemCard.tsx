'use client';

/**
 * Redeem-a-reward-code card (referral or promo).
 *
 * A signed-in user enters a code to claim a benefit: an immediate promo benefit,
 * or a referral that pays out when they take a paid subscription. Typed server
 * error codes are mapped to localized messages; a 202 (pending conversion) shows
 * an explanatory success state rather than an error.
 *
 * Style mirrors the former RedeemPromoCard / BalanceBreakdownCard: theme tokens,
 * `text-sm` default, lucide icons at `h-3.5 w-3.5`.
 */

import React, { useCallback, useMemo, useState } from 'react';
import { Gift, Check, Loader2 } from 'lucide-react';
import { useTranslations } from 'next-intl';
import { Button } from '@/components/ui/button';
import { RewardApiService } from '@/lib/api/services/reward-api.service';
import { ApiError } from '@/lib/api/api-client';

const ERROR_KEY_BY_CODE: Record<string, string> = {
  INVALID_CODE: 'errors.invalidCode',
  NOT_REDEEMABLE: 'errors.notRedeemable',
  ALREADY_REDEEMED: 'errors.alreadyRedeemed',
  EXHAUSTED: 'errors.exhausted',
  SELF_REFERRAL: 'errors.selfReferral',
  ALREADY_PAID: 'errors.alreadyPaid',
  CLOUD_LINK_REQUIRED: 'errors.cloudLinkRequired',
};

export function RewardRedeemCard({ prefilledCode = '' }: { prefilledCode?: string }) {
  const t = useTranslations('reward.redeem');
  const rewardApi = useMemo(() => new RewardApiService(), []);

  const [code, setCode] = useState(prefilledCode);
  const [submitting, setSubmitting] = useState(false);
  const [errorKey, setErrorKey] = useState<string | null>(null);
  const [successKey, setSuccessKey] = useState<string | null>(null);

  const handleRedeem = useCallback(async () => {
    const trimmed = code.trim();
    if (!trimmed || submitting) return;
    setSubmitting(true);
    setErrorKey(null);
    setSuccessKey(null);
    try {
      const result = await rewardApi.redeem(trimmed);
      setCode('');
      setSuccessKey(result.code === 'REDEEMED' ? 'successGranted' : 'successPending');
    } catch (e) {
      const codeStr = e instanceof ApiError ? e.code : undefined;
      setErrorKey((codeStr && ERROR_KEY_BY_CODE[codeStr]) || 'errors.generic');
    } finally {
      setSubmitting(false);
    }
  }, [code, submitting, rewardApi]);

  return (
    <div className="rounded-xl border border-theme p-6">
      <div className="flex items-center gap-3 mb-4">
        <div className="w-10 h-10 bg-theme-secondary rounded-full flex items-center justify-center">
          <Gift className="w-5 h-5 text-theme-primary" />
        </div>
        <div>
          <h2 className="text-lg font-semibold text-theme-primary">{t('title')}</h2>
          <p className="text-sm text-theme-secondary">{t('subtitle')}</p>
        </div>
      </div>

      {successKey && (
        <div className="mb-4 flex items-start gap-2 rounded-lg bg-theme-secondary p-3 text-sm">
          <Check className="h-3.5 w-3.5 mt-0.5 text-emerald-500 flex-shrink-0" />
          <div className="text-theme-primary">{t(successKey)}</div>
        </div>
      )}

      <div className="flex flex-wrap items-center gap-2">
        <input
          type="text"
          value={code}
          onChange={(e) => {
            setCode(e.target.value);
            if (errorKey) setErrorKey(null);
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
          variant="contrast"
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
