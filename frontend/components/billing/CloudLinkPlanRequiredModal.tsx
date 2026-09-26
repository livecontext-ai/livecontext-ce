'use client';

import React, { useState, useEffect } from 'react';
import { X, Lock, ExternalLink } from 'lucide-react';
import { useTranslations } from 'next-intl';
import { Button } from '@/components/ui/button';
import { IS_CE } from '@/lib/edition';
import { CLOUD_PRICING_URL } from '@/lib/edition/cloudWebUrl';

/**
 * Custom event name for the CE "cloud link needs a paid plan" modal.
 * Dispatch `new CustomEvent('cloudLinkPlanRequired')` from anywhere to open it.
 */
export const CLOUD_LINK_PLAN_REQUIRED_EVENT = 'cloudLinkPlanRequired';

/** Helper to dispatch the cloud-link-plan-required event from anywhere. */
export function showCloudLinkPlanRequiredModal() {
  window.dispatchEvent(new CustomEvent(CLOUD_LINK_PLAN_REQUIRED_EVENT));
}

/**
 * CE-only modal shown when a call relayed to the linked LiveContext Cloud account (LLM, catalog,
 * web search, bundles) is refused with CLOUD_LINK_PLAN_REQUIRED: the cloud account is not on a
 * paid plan. The link is kept, so the only remedy is choosing a plan on the cloud, after which
 * the install reconnects by itself. Opens the cloud pricing page in a new tab. Gated to CE so a
 * stray dispatch in a Cloud build is a no-op. Same pattern as ModelNotManagedModal.
 */
export default function CloudLinkPlanRequiredModal() {
  const t = useTranslations('ceCloudLink.planRequiredModal');
  const [open, setOpen] = useState(false);

  useEffect(() => {
    if (!IS_CE) return;
    const handler = () => setOpen(true);
    window.addEventListener(CLOUD_LINK_PLAN_REQUIRED_EVENT, handler);
    return () => window.removeEventListener(CLOUD_LINK_PLAN_REQUIRED_EVENT, handler);
  }, []);

  if (!IS_CE || !open) return null;

  return (
    <div className="fixed inset-0 z-[100] flex items-center justify-center">
      <div className="absolute inset-0 bg-black/50" onClick={() => setOpen(false)} />

      <div
        role="dialog"
        aria-modal="true"
        className="relative w-full max-w-md mx-4 rounded-2xl bg-theme-primary border border-theme shadow-[0_16px_48px_rgba(0,0,0,0.16)] p-6 max-h-[90vh] overflow-y-auto"
      >
        <button
          onClick={() => setOpen(false)}
          aria-label={t('close')}
          className="absolute top-4 right-4 text-theme-muted hover:text-theme-primary transition-colors"
        >
          <X className="h-5 w-5" />
        </button>

        <div className="flex justify-center mb-4">
          <div className="w-14 h-14 rounded-xl bg-amber-500/10 flex items-center justify-center">
            <Lock className="h-7 w-7 text-amber-500" />
          </div>
        </div>

        <h2 className="text-lg font-semibold text-theme-primary text-center mb-2">{t('title')}</h2>

        <p className="text-sm text-theme-secondary text-center mb-6">{t('description')}</p>

        <div className="flex flex-col gap-3">
          <Button asChild className="w-full">
            <a
              href={CLOUD_PRICING_URL}
              target="_blank"
              rel="noopener noreferrer"
              onClick={() => setOpen(false)}
            >
              {t('cta')}
              <ExternalLink className="h-4 w-4 ml-2" />
            </a>
          </Button>
          <Button variant="outline" className="w-full" onClick={() => setOpen(false)}>
            {t('close')}
          </Button>
        </div>
      </div>
    </div>
  );
}
