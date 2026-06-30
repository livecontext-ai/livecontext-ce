'use client';

import React from 'react';
import { createPortal } from 'react-dom';
import { ExternalLink } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { useTranslations } from 'next-intl';

interface OpenLinkConfirmModalProps {
  /** The external URL the interface wants to open, or null/empty when the modal is closed. */
  url: string | null;
  /** Open the link (in a new tab) - wired by the parent so the gesture stays user-initiated. */
  onConfirm: () => void;
  /** Dismiss without opening. */
  onCancel: () => void;
}

/**
 * Confirmation modal shown before opening an external link clicked inside a sandboxed
 * interface iframe.
 *
 * <p>Interface iframes are sandboxed without {@code allow-popups} / {@code allow-top-navigation},
 * so a raw link click (or {@code window.open}) is silently swallowed and the link looks dead.
 * The injected navigation gate instead asks the parent ({@code InterfaceIframe}) to confirm here,
 * and on confirm the link opens in a new tab. Matches the app's default confirmation-modal style
 * (portal overlay, rounded card, icon + title + message, Cancel | primary action).</p>
 */
export function OpenLinkConfirmModal({ url, onConfirm, onCancel }: OpenLinkConfirmModalProps) {
  const t = useTranslations('interfaceLinkGate');
  const tc = useTranslations('common');

  if (!url) return null;
  const titleId = 'open-link-confirm-title';

  return createPortal(
    <div
      className="fixed inset-0 bg-black/20 backdrop-blur-sm z-[9999] flex items-center justify-center p-4"
      onClick={onCancel}
    >
      <div
        className="max-w-md w-full bg-theme-primary rounded-3xl shadow-2xl p-8 animate-in fade-in-0 zoom-in-95 duration-300 border border-theme max-h-[90vh] overflow-y-auto"
        onClick={(e) => e.stopPropagation()}
        role="dialog"
        aria-modal="true"
        aria-labelledby={titleId}
      >
        {/* Icon */}
        <div className="w-16 h-16 bg-blue-100 dark:bg-blue-950/40 rounded-full flex items-center justify-center mx-auto mb-5">
          <ExternalLink className="h-8 w-8 text-blue-600 dark:text-blue-400" />
        </div>

        {/* Title */}
        <h2 id={titleId} className="text-2xl font-semibold text-theme-primary text-center mb-2">
          {t('title')}
        </h2>

        {/* Message */}
        <p className="text-theme-secondary text-center mb-4">
          {t('description')}
        </p>

        {/* Destination, shown verbatim so the user can vet it before opening. */}
        <p className="text-sm text-theme-secondary text-center mb-8 break-all font-mono bg-black/5 dark:bg-white/5 rounded-lg px-3 py-2">
          {url}
        </p>

        {/* Actions */}
        <div className="flex gap-3">
          <Button onClick={onCancel} variant="outline" className="flex-1">
            {tc('cancel')}
          </Button>
          <Button onClick={onConfirm} variant="default" className="flex-1">
            {t('openAction')}
          </Button>
        </div>
      </div>
    </div>,
    document.body
  );
}

export default OpenLinkConfirmModal;
