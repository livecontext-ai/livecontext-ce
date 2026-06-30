'use client';

import React, { useState, useEffect } from 'react';
import { X, AlertTriangle, RotateCcw } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { useTranslations } from 'next-intl';

/**
 * Custom event name for the generic "something went wrong" agent/chat error modal.
 * Dispatch `new CustomEvent('agentError')` from anywhere to open it.
 */
export const AGENT_ERROR_EVENT = 'agentError';

/** Helper to dispatch the generic agent-error event from anywhere. */
export function showAgentErrorModal() {
  window.dispatchEvent(new CustomEvent(AGENT_ERROR_EVENT));
}

/**
 * Generic, edition-agnostic (Cloud AND CE) modal shown when a chat / agent run fails
 * with an UNEXPECTED error that isn't one of the specific, actionable cases
 * (insufficient credit, unmanaged model, missing API key, storage quota). It turns a
 * raw backend failure (e.g. "Provider not configured", a transient relay/provider
 * hiccup) into a friendly "something went wrong, try again" prompt instead of a silent
 * failure or a cryptic toast. Shown on top of the recorded error so the user always
 * gets an actionable surface.
 */
export default function AgentErrorModal() {
  const t = useTranslations('modals.agentError');
  const [open, setOpen] = useState(false);

  useEffect(() => {
    const handler = () => setOpen(true);
    window.addEventListener(AGENT_ERROR_EVENT, handler);
    return () => window.removeEventListener(AGENT_ERROR_EVENT, handler);
  }, []);

  if (!open) return null;

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
            <AlertTriangle className="h-7 w-7 text-amber-500" />
          </div>
        </div>

        <h2 className="text-lg font-semibold text-theme-primary text-center mb-2">
          {t('title')}
        </h2>

        <p className="text-sm text-theme-secondary text-center mb-6">
          {t('description')}
        </p>

        <div className="flex flex-col gap-3">
          <Button className="w-full" onClick={() => setOpen(false)}>
            <RotateCcw className="h-4 w-4 mr-2" />
            {t('retry')}
          </Button>
        </div>
      </div>
    </div>
  );
}
