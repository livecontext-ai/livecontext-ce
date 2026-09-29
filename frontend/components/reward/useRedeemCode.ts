'use client';

import { useCallback, useContext, useMemo, useState } from 'react';
import { QueryClientContext } from '@tanstack/react-query';
import { RewardApiService } from '@/lib/api/services/reward-api.service';
import { ApiError } from '@/lib/api/api-client';
import { clearPendingRewardCode, readPendingRewardCode } from '@/lib/lifecycle/pendingRewardCode';
import { redeemErrorKey, useRedeemSuccessMessage } from './redeemMessages';

/**
 * The one redeem-a-code action behind every place a code can be typed (the Refer & earn card,
 * and the "Have a code?" line next to each Stripe checkout). Keeping it in one hook keeps the
 * rules identical everywhere:
 * <ul>
 *   <li>a code settled by hand is forgotten from the pending slot, so the /app shell never
 *       auto-redeems it a second time (a typed 404/409 is final; a 403 unverified is not);</li>
 *   <li>after a success, cached balances and plan are refetched (the code just changed them);</li>
 *   <li>the success line says what was actually granted.</li>
 * </ul>
 * The query client is read OPTIONALLY: the card also renders where no provider is mounted.
 */
export function useRedeemCode() {
  const rewardApi = useMemo(() => new RewardApiService(), []);
  const queryClient = useContext(QueryClientContext);
  const describeSuccess = useRedeemSuccessMessage();
  const [submitting, setSubmitting] = useState(false);
  const [errorKey, setErrorKey] = useState<string | null>(null);
  const [successText, setSuccessText] = useState<string | null>(null);

  const forgetIfPending = (code: string) => {
    if (typeof window === 'undefined') return;
    if (readPendingRewardCode(window) === code.toUpperCase()) clearPendingRewardCode(window);
  };

  const redeem = useCallback(
    async (raw: string): Promise<boolean> => {
      const code = raw.trim();
      if (!code || submitting) return false;
      setSubmitting(true);
      setErrorKey(null);
      setSuccessText(null);
      try {
        const result = await rewardApi.redeem(code);
        forgetIfPending(code);
        setSuccessText(describeSuccess(result));
        if (queryClient) void queryClient.invalidateQueries();
        return true;
      } catch (e) {
        if (e instanceof ApiError && (e.status === 404 || e.status === 409)) forgetIfPending(code);
        setErrorKey(redeemErrorKey(e instanceof ApiError ? e.code : undefined));
        return false;
      } finally {
        setSubmitting(false);
      }
    },
    [submitting, rewardApi, describeSuccess, queryClient],
  );

  const clearError = useCallback(() => setErrorKey(null), []);

  return { redeem, submitting, errorKey, successText, clearError };
}
