'use client';

import React, { useState, useEffect } from 'react';
import { X, Coins, ExternalLink } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { useTranslations, useLocale } from 'next-intl';
import { IS_CE, CLOUD_WEB_BASE_URL } from '@/lib/edition';

/**
 * Custom event name for triggering the CE "cloud credits exhausted" modal.
 * Dispatch `new CustomEvent('ceCloudCredit')` from anywhere to open it.
 */
export const CE_CLOUD_CREDIT_EVENT = 'ceCloudCredit';

/** Helper to dispatch the CE cloud-credit event from anywhere. */
export function showCeCloudCreditModal() {
  window.dispatchEvent(new CustomEvent(CE_CLOUD_CREDIT_EVENT));
}

/**
 * CE-only modal shown when a chat / agent / workflow LLM call relayed to the linked
 * LiveContext Cloud account is refused for INSUFFICIENT_CREDITS. CE has no local Stripe
 * checkout, so the action sends the user to the cloud billing page (new tab) to top up or
 * upgrade - mirroring how CE pricing already delegates payments to the cloud account.
 *
 * The Cloud edition uses {@code InsufficientCreditsModal} (Stripe) instead; this one is gated
 * to CE so a stray dispatch in a Cloud build is a no-op.
 */
export default function CeCloudCreditModal() {
  const t = useTranslations('modals.ceCloudCredit');
  const locale = useLocale();
  const [open, setOpen] = useState(false);

  useEffect(() => {
    // Cloud edition never surfaces this modal (it has its own Stripe flow).
    if (!IS_CE) return;
    const handler = () => setOpen(true);
    window.addEventListener(CE_CLOUD_CREDIT_EVENT, handler);
    return () => window.removeEventListener(CE_CLOUD_CREDIT_EVENT, handler);
  }, []);

  if (!IS_CE || !open) return null;

  return (
    <div className="fixed inset-0 z-[100] flex items-center justify-center">
      {/* Backdrop */}
      <div className="absolute inset-0 bg-black/50" onClick={() => setOpen(false)} />

      {/* Modal */}
      <div className="relative w-full max-w-md mx-4 rounded-2xl bg-theme-primary border border-theme shadow-2xl p-6">
        <button
          onClick={() => setOpen(false)}
          className="absolute top-4 right-4 text-theme-muted hover:text-theme-primary transition-colors"
        >
          <X className="h-5 w-5" />
        </button>

        <div className="flex justify-center mb-4">
          <div className="w-14 h-14 rounded-full bg-amber-500/10 flex items-center justify-center">
            <Coins className="h-7 w-7 text-amber-500" />
          </div>
        </div>

        <h2 className="text-lg font-semibold text-theme-primary text-center mb-2">
          {t('title')}
        </h2>

        <p className="text-sm text-theme-secondary text-center mb-6">
          {t('description')}
        </p>

        <div className="flex flex-col gap-3">
          <Button
            className="w-full"
            onClick={() => {
              setOpen(false);
              // Billing lives on the linked cloud account; open its pricing page in a new tab
              // (CE has no in-app Stripe). CLOUD_WEB_BASE_URL is the canonical cloud web origin.
              window.open(`${CLOUD_WEB_BASE_URL}/${locale}/app/settings/pricing`, '_blank', 'noopener,noreferrer');
            }}
          >
            {t('topUp')}
            <ExternalLink className="h-4 w-4 ml-2" />
          </Button>
          <Button variant="outline" className="w-full" onClick={() => setOpen(false)}>
            {t('close')}
          </Button>
        </div>
      </div>
    </div>
  );
}
