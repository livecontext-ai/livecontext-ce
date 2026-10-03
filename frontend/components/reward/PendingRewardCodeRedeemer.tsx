'use client';

/**
 * Applies the partner / creator code a visitor followed a link with, once they are signed in
 * (the redeem call needs a session, the link was usually opened signed out). See
 * {@link capturePendingRewardCode}.
 *
 * <p>A query rather than an effect: react-query dedupes by key and never loops. A typed server
 * answer (applied, unknown, already used...) is final and forgets the code; anything else (no
 * token yet, network, 5xx) keeps it for the next page load. The outcome is shown once in a small dismissible notice.
 * Mounted in the /app shell and on a partner's offer page (cloud only).
 */

import React, { useMemo, useState } from 'react';
import { useQuery, useQueryClient, type QueryClient } from '@tanstack/react-query';
import { useTranslations } from 'next-intl';
import { Check, Gift, X } from 'lucide-react';
import { useOptionalAuth } from '@/lib/providers/smart-providers';
import { ApiError } from '@/lib/api/api-client';
import { rewardApi, type RewardRedeemResult } from '@/lib/api/services/reward-api.service';
import {
  capturePendingRewardCode,
  clearPendingRewardCode,
  readPendingRewardCode,
} from '@/lib/lifecycle/pendingRewardCode';
import { useRedeemSuccessMessage, redeemErrorKey } from './redeemMessages';

/** One shape rather than a union: the frontend compiles without strictNullChecks, so a
 *  discriminated union would not narrow on `ok`. */
export type PendingRedeemOutcome = { ok: boolean; code: string; result?: RewardRedeemResult; errorKey?: string };

/**
 * The redeem of a waiting code, as one react-query entry per account and code. Shared so that a
 * page about to open a checkout (a partner's offer) can wait for this very redeem, deduped with
 * the notice's own query, instead of racing it: `queryClient.fetchQuery` returns its settled
 * outcome, joins it in flight, or starts it.
 */
export function pendingRedeemQuery(queryClient: QueryClient, numericUserId: number | null, code: string | null) {
  return {
    queryKey: ['reward', 'pending-redeem', numericUserId, code] as const,
    queryFn: async (): Promise<PendingRedeemOutcome> => {
      const pending = code as string;
      try {
        const result = await rewardApi.redeem(pending);
        clearPendingRewardCode(window);
        // The balance and the plan just changed: refetch whatever shows them.
        void queryClient.invalidateQueries();
        return { ok: true, code: pending, result };
      } catch (e) {
        // Only the redeem endpoint's typed refusals (404 unknown, 409 used / not eligible) are
        // final. NO_TOKEN, a timeout, a 401 or a 5xx say nothing about the code: keep it.
        if (e instanceof ApiError && (e.status === 404 || e.status === 409)) {
          clearPendingRewardCode(window);
          return { ok: false, code: pending, errorKey: redeemErrorKey(e.code) };
        }
        // Not final: the same code works once the email is verified. Say so, keep the code.
        if (e instanceof ApiError && e.code === 'EMAIL_NOT_VERIFIED') {
          return { ok: false, code: pending, errorKey: redeemErrorKey(e.code) };
        }
        throw e; // network / 5xx: keep the code, try again on the next page load
      }
    },
    staleTime: Infinity,
    gcTime: Infinity,
    retry: false,
  };
}

export default function PendingRewardCodeRedeemer() {
  const auth = useOptionalAuth();
  const t = useTranslations('reward.redeem');
  const describeSuccess = useRedeemSuccessMessage();
  const queryClient = useQueryClient();
  const [dismissed, setDismissed] = useState(false);
  const ready = !!auth && auth.isAuthenticated && auth.isReady && !auth.isLoading && auth.numericUserId != null;
  // Capture first: this renders BEFORE the root capture's effect runs, so a signed-in person
  // landing straight on /app?lc_ref=CODE would otherwise be missed until the next load.
  const code = useMemo(() => {
    if (typeof window === 'undefined') return null;
    capturePendingRewardCode(window);
    return readPendingRewardCode(window);
  }, []);

  const { data } = useQuery({
    ...pendingRedeemQuery(queryClient, auth?.numericUserId ?? null, code),
    enabled: ready && !!code,
    refetchOnWindowFocus: false,
    refetchOnReconnect: false,
    refetchOnMount: false,
  });

  // An unknown code (a mistyped or retired link) is dropped silently: there is nothing the
  // person can do about it, and a notice about a code they never typed only confuses.
  // An unknown code, or one already redeemed (by hand on /redeem), says nothing useful here.
  const silent = !data?.ok && (data?.errorKey === 'errors.invalidCode' || data?.errorKey === 'errors.alreadyRedeemed');
  if (!data || dismissed || silent) return null;

  return (
    <div
      role="status"
      className="fixed bottom-4 right-4 z-[60] w-[min(22rem,calc(100vw-2rem))] rounded-xl border border-theme bg-theme-secondary p-4 shadow-lg"
    >
      <div className="flex items-start gap-3">
        {data.ok ? (
          <Check className="h-3.5 w-3.5 mt-0.5 text-emerald-500 flex-shrink-0" />
        ) : (
          <Gift className="h-3.5 w-3.5 mt-0.5 text-theme-secondary flex-shrink-0" />
        )}
        <div className="min-w-0 flex-1 text-sm">
          <p className="font-medium text-theme-primary">{t('pendingTitle', { code: data.code })}</p>
          <p className="text-theme-secondary">{data.ok ? describeSuccess(data.result) : t(data.errorKey ?? 'errors.generic')}</p>
        </div>
        <button
          type="button"
          onClick={() => setDismissed(true)}
          aria-label={t('dismiss')}
          className="text-theme-muted hover:text-theme-primary"
        >
          <X className="h-3.5 w-3.5" />
        </button>
      </div>
    </div>
  );
}
