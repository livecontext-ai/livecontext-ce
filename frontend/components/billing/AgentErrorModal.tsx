'use client';

import React, { useState, useEffect, useRef } from 'react';
import { X, AlertTriangle } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { useTranslations } from 'next-intl';
import { classifyAgentError, type AgentErrorKind } from '@/lib/chat/agentErrorKind';

/**
 * Custom event name for the agent/chat error modal.
 * Dispatch `new CustomEvent('agentError', { detail })` from anywhere to open it.
 */
export const AGENT_ERROR_EVENT = 'agentError';

/** What went wrong: the verbatim failure text and the stream error code, when known. */
export interface AgentErrorDetail {
  message?: string | null;
  code?: string | null;
}

/** Longest raw failure text shown under "Technical details" (provider bodies can be huge). */
const MAX_DETAIL_CHARS = 400;

/** Helper to dispatch the agent-error event from anywhere. */
export function showAgentErrorModal(detail: AgentErrorDetail = {}) {
  window.dispatchEvent(new CustomEvent<AgentErrorDetail>(AGENT_ERROR_EVENT, { detail }));
}

/**
 * Edition-agnostic (Cloud AND CE) modal shown when a chat / agent run fails with an
 * error that isn't one of the specific, actionable cases owning their own modal
 * (insufficient credit, unmanaged model, missing API key, storage quota). It is the
 * ONLY surface for such failures (the chat shows no error banner), so it explains
 * what happened for each kind of failure and what the user can do (its description
 * says whether sending again can help), and keeps the raw failure text under a
 * collapsed "Technical details". Its single button only dismisses it.
 */
export default function AgentErrorModal() {
  const t = useTranslations('modals.agentError');
  const [error, setError] = useState<{ kind: AgentErrorKind; message: string } | null>(null);
  const dismissRef = useRef<HTMLButtonElement>(null);
  const panelRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    const handler = (event: Event) => {
      const detail = (event as CustomEvent<AgentErrorDetail | undefined>).detail ?? {};
      const message = (detail.message ?? '').trim();
      setError({ kind: classifyAgentError(message, detail.code), message });
    };
    window.addEventListener(AGENT_ERROR_EVENT, handler);
    return () => window.removeEventListener(AGENT_ERROR_EVENT, handler);
  }, []);

  const open = error !== null;
  useEffect(() => {
    if (!open) return;
    // Remember where the user was (typically the composer) to give focus back on close.
    const previouslyFocused = document.activeElement as HTMLElement | null;
    dismissRef.current?.focus();
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape') {
        setError(null);
        return;
      }
      if (event.key !== 'Tab' || !panelRef.current) return;
      // Keep keyboard focus inside the dialog while it is open.
      const focusable = Array.from(panelRef.current.querySelectorAll<HTMLElement>('button, summary'));
      if (focusable.length === 0) return;
      const first = focusable[0];
      const last = focusable[focusable.length - 1];
      if (event.shiftKey && document.activeElement === first) {
        event.preventDefault();
        last.focus();
      } else if (!event.shiftKey && document.activeElement === last) {
        event.preventDefault();
        first.focus();
      }
    };
    window.addEventListener('keydown', onKeyDown);
    return () => {
      window.removeEventListener('keydown', onKeyDown);
      previouslyFocused?.focus?.();
    };
  }, [open]);

  if (!error) return null;

  const close = () => setError(null);
  const detail = error.message.length > MAX_DETAIL_CHARS
    ? `${error.message.slice(0, MAX_DETAIL_CHARS)}...`
    : error.message;

  return (
    <div className="fixed inset-0 z-[100] flex items-center justify-center">
      {/* Backdrop */}
      <div className="absolute inset-0 bg-black/50" onClick={close} data-testid="agent-error-backdrop" />

      {/* Modal */}
      <div
        ref={panelRef}
        role="dialog"
        aria-modal="true"
        aria-labelledby="agent-error-title"
        aria-describedby="agent-error-description"
        className="relative w-full max-w-md mx-4 rounded-2xl bg-theme-primary border border-theme shadow-[0_16px_48px_rgba(0,0,0,0.16)] p-6 max-h-[90vh] overflow-y-auto"
      >
        <button
          onClick={close}
          aria-label={t('close')}
          className="absolute top-4 right-4 text-theme-muted hover:text-theme-primary transition-colors"
        >
          <X className="h-5 w-5" />
        </button>

        <div className="flex justify-center mb-4">
          <div className="w-14 h-14 rounded-xl bg-amber-500/10 flex items-center justify-center">
            <AlertTriangle className="h-7 w-7 text-amber-500" />
          </div>
        </div>

        <h2 id="agent-error-title" className="text-lg font-semibold text-theme-primary text-center mb-2">
          {t(`kinds.${error.kind}.title`)}
        </h2>

        <p id="agent-error-description" className={`text-sm text-theme-secondary text-center ${detail ? 'mb-4' : 'mb-6'}`}>
          {t(`kinds.${error.kind}.description`)}
        </p>

        {detail && (
          <details className="mb-6 rounded-lg border border-theme bg-theme-secondary/40 px-3 py-2">
            <summary className="cursor-pointer text-sm text-theme-muted">{t('technicalDetails')}</summary>
            <p className="mt-2 text-sm font-mono break-words text-theme-secondary" data-testid="agent-error-detail">
              {detail}
            </p>
          </details>
        )}

        <div className="flex flex-col gap-3">
          <Button ref={dismissRef} className="w-full" onClick={close}>
            {t('gotIt')}
          </Button>
        </div>
      </div>
    </div>
  );
}
