'use client';

import React from 'react';
import { useQuery } from '@tanstack/react-query';
import { Loader2 } from 'lucide-react';
import { useOptionalAuth } from '@/lib/providers/smart-providers';
import { IS_CE } from '@/lib/edition';
import { partnerProgramApi } from '@/lib/api/services/partner-program-api.service';
import { mapPartnerOffer } from '@/lib/partners/partnerOfferPayload';
import { PartnerOfferView } from './PartnerOfferView';

/**
 * An offer the anonymous read calls gone, read again for the signed-in visitor: a client already
 * attributed to the offer's partner still gets it once the partner's code is used up or expired
 * (back from a cancelled payment, say), and can pay through it for its apps. Anyone else, or a
 * read that fails, sees {@code children} (the "no longer available" page).
 */
export function PartnerOfferForClient({ token, children }: { token: string; children: React.ReactNode }) {
  const auth = useOptionalAuth();
  const userId = auth?.numericUserId ?? null;
  const signedIn = !IS_CE && !!auth?.isAuthenticated && !!auth.isReady && !auth.isLoading && userId != null;
  const { data, isPending } = useQuery({
    queryKey: ['partner-offer-view', token, userId],
    queryFn: async () => mapPartnerOffer(await partnerProgramApi.offerView(token)),
    enabled: signedIn,
    retry: false,
    staleTime: 60_000,
  });

  // Still deciding who is looking, or reading the offer for them: nothing to say yet.
  const deciding = !IS_CE && (!!auth?.isLoading || (signedIn && isPending));
  if (deciding) {
    return (
      <main className="flex flex-1 items-center justify-center px-4 py-16" data-testid="partner-offer-for-client-loading" role="status">
        <Loader2 className="h-3.5 w-3.5 animate-spin text-theme-secondary" aria-hidden />
      </main>
    );
  }
  if (signedIn && data) return <PartnerOfferView offer={data} />;
  return <>{children}</>;
}

export default PartnerOfferForClient;
