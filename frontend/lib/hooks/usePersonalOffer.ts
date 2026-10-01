'use client';

import { useEffect, useRef, useState } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { useOptionalAuth } from '@/lib/providers/smart-providers';
import { ApiError } from '@/lib/api/api-client';
import {
  rewardApi,
  type PersonalOfferCurrent,
  type PersonalOfferPreview,
} from '@/lib/api/services/reward-api.service';
import {
  capturePendingPersonalOffer,
  clearPendingPersonalOffer,
  readPendingPersonalOffer,
} from '@/lib/lifecycle/pendingPersonalOffer';

const PREVIEWABLE = new Set(['AVAILABLE', 'CHECKOUT_OPEN']);

/** The server owns the entitlement; the URL and session storage only carry a candidate code. */
export function usePersonalOffer(creditTierIndex: number, billingCycle: 'monthly' | 'yearly', enabled = true) {
  const auth = useOptionalAuth();
  const queryClient = useQueryClient();
  const [candidateCode, setCandidateCode] = useState<string | null>(null);
  const ready = !!auth && auth.isAuthenticated && auth.isReady && !auth.isLoading && auth.numericUserId != null;
  const userId = auth?.numericUserId ?? null;
  const priorUserId = useRef<number | null>(null);

  useEffect(() => {
    if (typeof window === 'undefined') return;
    setCandidateCode(capturePendingPersonalOffer(window) ?? readPendingPersonalOffer(window));
  }, []);

  useEffect(() => {
    if (priorUserId.current != null && priorUserId.current !== userId) {
      if (typeof window !== 'undefined') clearPendingPersonalOffer(window);
      setCandidateCode(null);
    }
    priorUserId.current = userId;
  }, [userId]);

  const currentQuery = useQuery<PersonalOfferCurrent>({
    queryKey: ['personal-offer', 'current', userId],
    queryFn: () => rewardApi.getCurrentPersonalOffer(),
    enabled: ready && enabled,
    staleTime: 15_000,
    refetchOnWindowFocus: true,
    refetchInterval: (query) => ['CHECKOUT_OPEN', 'PENDING_PAYMENT', 'PROCESSING'].includes(query.state.data?.status ?? '') ? 2_000 : false,
    retry: false,
  });

  const current = currentQuery.data;
  const hasCurrent = !!current?.offerId && PREVIEWABLE.has(current.status);
  const previewQuery = useQuery<PersonalOfferPreview>({
    queryKey: ['personal-offer', 'preview', userId, candidateCode, current?.offerId, current?.offerVersion, creditTierIndex, billingCycle],
    queryFn: () => rewardApi.previewPersonalOffer({
      ...(candidateCode ? { code: candidateCode } : { offerId: current?.offerId ?? undefined }),
      creditTierIndex,
      billingCycle,
    }),
    enabled: ready && enabled && (!!candidateCode || hasCurrent),
    staleTime: 15_000,
    retry: false,
  });

  // A verified link no longer needs browser storage. Keep a rejected code in component state
  // so its refusal remains visible until the person changes code or leaves the page.
  useEffect(() => {
    if (!candidateCode || !previewQuery.data || typeof window === 'undefined') return;
    // Preview only reads an offer that was already issued to this account. Resolve current
    // again before dropping the link candidate, so another surface can use the same offer.
    void currentQuery.refetch().then((result) => {
      if (result.data?.offerId !== previewQuery.data?.offerId) return;
      clearPendingPersonalOffer(window);
      setCandidateCode(null);
    });
  }, [candidateCode, previewQuery.data, currentQuery.refetch]);

  const previewErrorCode = previewQuery.error instanceof ApiError ? previewQuery.error.code : null;
  const currentErrorCode = currentQuery.error instanceof ApiError ? currentQuery.error.code : null;

  return {
    current,
    preview: previewQuery.data,
    candidateCode,
    errorCode: previewErrorCode ?? currentErrorCode,
    isError: currentQuery.isError || previewQuery.isError,
    isLoading: ready && enabled && (currentQuery.isPending || previewQuery.isPending && (!!candidateCode || hasCurrent)),
    isAuthenticated: ready,
    refresh: async () => {
      await queryClient.invalidateQueries({ queryKey: ['personal-offer'] });
    },
    clearCandidate: () => {
      if (typeof window !== 'undefined') clearPendingPersonalOffer(window);
      setCandidateCode(null);
    },
  };
}
