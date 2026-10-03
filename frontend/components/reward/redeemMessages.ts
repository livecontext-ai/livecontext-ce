'use client';

import { useCallback } from 'react';
import { useLocale, useTranslations } from 'next-intl';
import type { RewardRedeemResult } from '@/lib/api/services/reward-api.service';
import { formatUtcDate } from '@/lib/utils/dateFormatters';

const ERROR_KEY_BY_CODE: Record<string, string> = {
  INVALID_CODE: 'errors.invalidCode',
  NOT_REDEEMABLE: 'errors.notRedeemable',
  ALREADY_REDEEMED: 'errors.alreadyRedeemed',
  EXHAUSTED: 'errors.exhausted',
  SELF_REFERRAL: 'errors.selfReferral',
  ALREADY_PAID: 'errors.alreadyPaid',
  ALREADY_ATTRIBUTED: 'errors.alreadyAttributed',
  PARTNER_ACCOUNT: 'errors.partnerAccount',
  EMAIL_NOT_VERIFIED: 'errors.emailNotVerified',
  REDEEM_RETRY: 'errors.retry',
  NOT_NEW_ACCOUNT: 'errors.notNewAccount',
  NOTHING_TO_GRANT: 'errors.nothingToGrant',
  CLOUD_LINK_REQUIRED: 'errors.cloudLinkRequired',
  OFFER_UNAVAILABLE: 'errors.offerUnavailable',
  OFFER_EXPIRED: 'errors.offerExpired',
  OFFER_ALREADY_USED: 'errors.offerAlreadyUsed',
  OFFER_NOT_ELIGIBLE: 'errors.offerNotEligible',
  OFFER_CONFLICT: 'errors.offerConflict',
  OFFER_CHECKOUT_ACTIVE: 'errors.offerCheckoutActive',
};

/** The `reward.redeem` message key for a typed server error code. */
export function redeemErrorKey(code: string | undefined): string {
  return (code && ERROR_KEY_BY_CODE[code]) || 'errors.generic';
}

/**
 * Says what a redeem actually gave: the PAYG credits and the complimentary plan of a creator
 * or partner code, or the generic granted / pending line for the older code kinds.
 */
export function useRedeemSuccessMessage(): (result: RewardRedeemResult) => string {
  const t = useTranslations('reward.redeem');
  const locale = useLocale();
  return useCallback(
    (result: RewardRedeemResult) => {
      if (result.code === 'OFFER_READY') return t('personalOfferReady');
      if (result.code !== 'REDEEMED') return t('successPending');
      const credits = result.grantedCredits ?? 0;
      const creditsText = credits.toLocaleString(locale);
      if (result.grantedPlan && result.planEndsAt) {
        const until = formatUtcDate(result.planEndsAt, { locale });
        return credits > 0
          ? t('successCreditsAndPlan', { credits: creditsText, plan: result.grantedPlan, until })
          : t('successPlan', { plan: result.grantedPlan, until });
      }
      if (credits > 0) return t('successCredits', { credits: creditsText });
      return t('successGranted');
    },
    [t, locale],
  );
}
