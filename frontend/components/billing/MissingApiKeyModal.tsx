'use client';

import React, { useState, useEffect } from 'react';
import { X, KeyRound, ArrowRight } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { useRouter } from 'next/navigation';
import { useLocale, useTranslations } from 'next-intl';

/**
 * Custom event name for triggering the missing API key modal.
 * Dispatch `new CustomEvent('missingApiKey')` from anywhere to open it.
 */
export const MISSING_API_KEY_EVENT = 'missingApiKey';

/**
 * Which key the run was missing, which decides what the modal says and where it sends.
 *
 * `platform` is the original case: nothing is configured at all, and the place to fix it is
 * the credentials page. `own-key` is a user who HAS a key of their own and whose provider has
 * just refused it, so the fix is one page over, on the tab that holds that key.
 */
export type MissingApiKeyKind = 'platform' | 'own-key';

/**
 * Helper to dispatch the missing API key event from anywhere.
 */
export function showMissingApiKeyModal(kind: MissingApiKeyKind = 'platform') {
  window.dispatchEvent(new CustomEvent(MISSING_API_KEY_EVENT, { detail: { kind } }));
}

/**
 * Modal shown when a chat/agent message fails because no LLM API key is configured.
 * Redirects to the credentials settings page where the user can add their key.
 */
export default function MissingApiKeyModal() {
  const router = useRouter();
  const locale = useLocale();
  const t = useTranslations('modals.missingApiKey');
  const [open, setOpen] = useState(false);
  const [kind, setKind] = useState<MissingApiKeyKind>('platform');

  useEffect(() => {
    const handler = (event: Event) => {
      const detail = (event as CustomEvent<{ kind?: MissingApiKeyKind }>).detail;
      setKind(detail?.kind === 'own-key' ? 'own-key' : 'platform');
      setOpen(true);
    };
    window.addEventListener(MISSING_API_KEY_EVENT, handler);
    return () => window.removeEventListener(MISSING_API_KEY_EVENT, handler);
  }, []);

  if (!open) return null;

  const ownKey = kind === 'own-key';
  // Two whole literals rather than one computed segment after the app prefix. The
  // conversation route guard treats a segment interpolated right there as a surface being
  // chosen at run time, which is the bug it exists to catch; writing each destination out
  // means there is nothing to compute and nothing to flag. (It scans file TEXT, so spelling
  // the pattern out in a comment trips it too.) These are settings pages, never a
  // conversation.
  const settingsHref = ownKey
    ? `/${locale}/app/settings/ai-providers`
    : `/${locale}/app/settings/credentials`;

  return (
    <div className="fixed inset-0 z-[100] flex items-center justify-center">
      {/* Backdrop */}
      <div className="absolute inset-0 bg-black/50" onClick={() => setOpen(false)} />

      {/* Modal */}
      <div className="relative w-full max-w-md mx-4 rounded-2xl bg-theme-primary border border-theme shadow-[0_16px_48px_rgba(0,0,0,0.16)] p-6 max-h-[90vh] overflow-y-auto">
        {/* Close button */}
        <button
          onClick={() => setOpen(false)}
          className="absolute top-4 right-4 text-theme-muted hover:text-theme-primary transition-colors"
        >
          <X className="h-5 w-5" />
        </button>

        {/* Icon */}
        <div className="flex justify-center mb-4">
          <div className="w-14 h-14 rounded-xl bg-amber-500/10 flex items-center justify-center">
            <KeyRound className="h-7 w-7 text-amber-500" />
          </div>
        </div>

        {/* Title */}
        <h2 className="text-lg font-semibold text-theme-primary text-center mb-2">
          {ownKey ? t('rejectedTitle') : t('title')}
        </h2>

        {/* Description */}
        <p className="text-sm text-theme-secondary text-center mb-6" data-testid="missing-api-key-body">
          {ownKey ? t('rejectedDescription') : t('description')}
        </p>

        {/* Actions */}
        <div className="flex flex-col gap-3">
          <Button
            className="w-full"
            data-testid="missing-api-key-cta"
            onClick={() => {
              setOpen(false);
              router.push(settingsHref);
            }}
          >
            {ownKey ? t('rejectedCta') : t('cta')}
            <ArrowRight className="h-4 w-4 ml-2" />
          </Button>
          <Button
            variant="outline"
            className="w-full"
            onClick={() => setOpen(false)}
          >
            {t('close')}
          </Button>
        </div>
      </div>
    </div>
  );
}
