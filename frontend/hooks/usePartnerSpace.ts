'use client';

import { useQuery } from '@tanstack/react-query';
import { useAuth } from '@/lib/providers/smart-providers';
import { IS_CE } from '@/lib/edition';
import {
  PARTNER_DASHBOARD_QUERY_KEY,
  partnerProgramApi,
  type PartnerDashboardResponse,
} from '@/lib/api/services/partner-program-api.service';

/**
 * Whether this answer of the partner endpoint gives the user a partner space to come back to:
 * a partner (code live or disabled) or an applicant under review. Someone who never applied, or
 * was refused, has nothing there yet: /partners is where they apply (a refusal and its note
 * reach the applicant by e-mail; the page still shows them to anyone who opens it by URL).
 */
export function hasPartnerSpace(state: PartnerDashboardResponse['state'] | undefined): boolean {
  return state === 'active' || state === 'inactive' || state === 'pending';
}

/**
 * The signed-in user's partner space, for the navigation entries that lead to it. Reads the same
 * query as the partner page, so opening the page after the nav costs no second request, and an
 * application sent from /partners (which refreshes that query) brings the entry in. Never asked on
 * a self-hosted install, where the program does not exist.
 *
 * <p>Cost, accepted on purpose: the settings nav asks once per five minutes for every signed-in
 * cloud user (the answer is small and cached), and a partner sees the entry appear when that
 * answer lands. The global search bar, mounted on every page, sets `enabled` only once
 * something is typed.
 */
export function useHasPartnerSpace({ enabled = true }: { enabled?: boolean } = {}): boolean {
  const { isAuthenticated } = useAuth();
  const { data } = useQuery({
    queryKey: PARTNER_DASHBOARD_QUERY_KEY,
    queryFn: () => partnerProgramApi.me(),
    enabled: enabled && !IS_CE && !!isAuthenticated,
    staleTime: 5 * 60_000,
    retry: false,
  });
  return hasPartnerSpace(data?.state);
}
