'use client';

import { Suspense } from 'react';
import { useSearchParams } from 'next/navigation';
import PublicHeader from '@/components/sharing/PublicHeader';
import { RewardRedeemCard } from '@/components/reward/RewardRedeemCard';

/**
 * Public landing for a shared referral link ({origin}/redeem?code=CODE): it
 * pre-fills the redeem card with the code so the friend redeems in one step.
 * The redeem call itself requires a signed-in session (apiClient attaches the
 * OIDC token); an unauthenticated visitor is routed through login first.
 *
 * Like the other top-level public pages (shared chat/form), it carries the
 * PublicHeader so a visitor landing here from a pasted link can reach the app
 * via the LiveContext brand. The card keeps its own heading.
 */
function RedeemInner() {
  const params = useSearchParams();
  const code = params.get('code') ?? '';
  return (
    <div className="min-h-screen flex flex-col bg-theme-primary">
      <PublicHeader />
      <div className="flex-1 flex items-start justify-center px-4 py-8">
        <div className="w-full max-w-md">
          <RewardRedeemCard prefilledCode={code} />
        </div>
      </div>
    </div>
  );
}

export default function RedeemPage() {
  return (
    <Suspense fallback={null}>
      <RedeemInner />
    </Suspense>
  );
}
