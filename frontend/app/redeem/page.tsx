'use client';

import { Suspense } from 'react';
import { useSearchParams } from 'next/navigation';
import { useTranslations } from 'next-intl';
import { LogIn } from 'lucide-react';
import PublicHeader from '@/components/sharing/PublicHeader';
import { RewardRedeemCard } from '@/components/reward/RewardRedeemCard';
import { Button } from '@/components/ui/button';
import { useOptionalAuth } from '@/lib/providers/smart-providers';

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
  const auth = useOptionalAuth();
  const t = useTranslations('reward.redeem');
  // Signed out: the code is already remembered (PendingRewardCodeCapture, root providers) and
  // is applied automatically after sign-in or sign-up, so the page only has to send them there.
  const signedOut = !!auth && !auth.isLoading && !auth.isAuthenticated;
  return (
    <div className="min-h-screen flex flex-col bg-theme-primary">
      <PublicHeader />
      <div className="flex-1 flex items-start justify-center px-4 py-8">
        <div className="w-full max-w-md">
          {signedOut ? (
            <div className="rounded-xl border border-theme p-6 space-y-4">
              <h2 className="text-lg font-semibold text-theme-primary">{t('signedOutTitle')}</h2>
              <p className="text-sm text-theme-secondary">
                {code ? t('signedOutBodyWithCode', { code: code.toUpperCase() }) : t('signedOutBody')}
              </p>
              <Button
                size="sm"
                className="gap-1"
                onClick={() => void auth.loginWithRedirect({ appState: { returnTo: '/app' }, resetLoopGuards: true })}
              >
                <LogIn className="h-3.5 w-3.5" />
                {t('signedOutCta')}
              </Button>
            </div>
          ) : (
            <RewardRedeemCard prefilledCode={code} />
          )}
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
