'use client';

import React, { useState, useEffect } from 'react';
import { X, PackageX, ArrowRight } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { useRouter } from 'next/navigation';
import { useTranslations, useLocale } from 'next-intl';
import { IS_CE } from '@/lib/edition';
import { useAuth } from '@/lib/providers/smart-providers';

/**
 * Custom event name for triggering the CE "model not managed" modal.
 * Dispatch `new CustomEvent('modelNotManaged')` from anywhere to open it.
 */
export const MODEL_NOT_MANAGED_EVENT = 'modelNotManaged';

/** Helper to dispatch the model-not-managed event from anywhere. */
export function showModelNotManagedModal() {
  window.dispatchEvent(new CustomEvent(MODEL_NOT_MANAGED_EVENT));
}

/**
 * CE-only modal shown when a chat / agent / workflow LLM call relayed to the linked
 * LiveContext Cloud account is refused for MODEL_NOT_SUPPORTED: the selected model is not in
 * the cloud's curated catalog, i.e. not in any bundle this install holds (a stale or foreign
 * model catalog). The remedy is to refresh the model bundle so the available model list
 * matches the cloud again.
 *
 * Admins get a direct action to the bundles settings (where "Sync now" lives); non-admins are
 * told to ask their workspace admin, since bundle management is admin-gated. Gated to CE so a
 * stray dispatch in a Cloud build is a no-op.
 */
export default function ModelNotManagedModal() {
  const t = useTranslations('modals.modelNotManaged');
  const router = useRouter();
  const locale = useLocale();
  const { hasRole } = useAuth();
  const [open, setOpen] = useState(false);
  const isAdmin = hasRole?.('ADMIN') ?? false;

  useEffect(() => {
    if (!IS_CE) return;
    const handler = () => setOpen(true);
    window.addEventListener(MODEL_NOT_MANAGED_EVENT, handler);
    return () => window.removeEventListener(MODEL_NOT_MANAGED_EVENT, handler);
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
            <PackageX className="h-7 w-7 text-amber-500" />
          </div>
        </div>

        <h2 className="text-lg font-semibold text-theme-primary text-center mb-2">
          {t('title')}
        </h2>

        <p className="text-sm text-theme-secondary text-center mb-6">
          {isAdmin ? t('descriptionAdmin') : t('descriptionMember')}
        </p>

        <div className="flex flex-col gap-3">
          {isAdmin && (
            <Button
              className="w-full"
              onClick={() => {
                setOpen(false);
                router.push(`/${locale}/app/settings/cloud-account?tab=bundles`);
              }}
            >
              {t('updateBundle')}
              <ArrowRight className="h-4 w-4 ml-2" />
            </Button>
          )}
          <Button variant="outline" className="w-full" onClick={() => setOpen(false)}>
            {t('close')}
          </Button>
        </div>
      </div>
    </div>
  );
}
