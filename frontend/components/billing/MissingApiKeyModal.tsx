'use client';

import React, { useState, useEffect } from 'react';
import { X, KeyRound, ArrowRight } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { useRouter } from 'next/navigation';
import { useLocale } from 'next-intl';

/**
 * Custom event name for triggering the missing API key modal.
 * Dispatch `new CustomEvent('missingApiKey')` from anywhere to open it.
 */
export const MISSING_API_KEY_EVENT = 'missingApiKey';

/**
 * Helper to dispatch the missing API key event from anywhere.
 */
export function showMissingApiKeyModal() {
  window.dispatchEvent(new CustomEvent(MISSING_API_KEY_EVENT));
}

/**
 * Modal shown when a chat/agent message fails because no LLM API key is configured.
 * Redirects to the credentials settings page where the user can add their key.
 */
export default function MissingApiKeyModal() {
  const router = useRouter();
  const locale = useLocale();
  const [open, setOpen] = useState(false);

  useEffect(() => {
    const handler = () => setOpen(true);
    window.addEventListener(MISSING_API_KEY_EVENT, handler);
    return () => window.removeEventListener(MISSING_API_KEY_EVENT, handler);
  }, []);

  if (!open) return null;

  return (
    <div className="fixed inset-0 z-[100] flex items-center justify-center">
      {/* Backdrop */}
      <div className="absolute inset-0 bg-black/50" onClick={() => setOpen(false)} />

      {/* Modal */}
      <div className="relative w-full max-w-md mx-4 rounded-2xl bg-theme-primary border border-theme shadow-2xl p-6">
        {/* Close button */}
        <button
          onClick={() => setOpen(false)}
          className="absolute top-4 right-4 text-theme-muted hover:text-theme-primary transition-colors"
        >
          <X className="h-5 w-5" />
        </button>

        {/* Icon */}
        <div className="flex justify-center mb-4">
          <div className="w-14 h-14 rounded-full bg-amber-500/10 flex items-center justify-center">
            <KeyRound className="h-7 w-7 text-amber-500" />
          </div>
        </div>

        {/* Title */}
        <h2 className="text-lg font-semibold text-theme-primary text-center mb-2">
          API Key Required
        </h2>

        {/* Description */}
        <p className="text-sm text-theme-secondary text-center mb-6">
          No LLM API key is configured. Add your OpenAI, Anthropic, or other provider key to start using the chat and agents.
        </p>

        {/* Actions */}
        <div className="flex flex-col gap-3">
          <Button
            className="w-full"
            onClick={() => {
              setOpen(false);
              router.push(`/${locale}/app/settings/credentials`);
            }}
          >
            Configure API Keys
            <ArrowRight className="h-4 w-4 ml-2" />
          </Button>
          <Button
            variant="outline"
            className="w-full"
            onClick={() => setOpen(false)}
          >
            Close
          </Button>
        </div>
      </div>
    </div>
  );
}
