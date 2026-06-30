'use client';

import React, { useState } from 'react';
import { Globe, Clock, XCircle, Lock } from 'lucide-react';
import { useTranslations } from 'next-intl';
import {
  Dialog,
  DialogContent,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog';

interface PublicationStatusIconProps {
  isShared: boolean;
  isPending: boolean;
  isRejected: boolean;
  rejectionReason?: string | null;
  className?: string;
  /**
   * When true, render a Lock for the "not shared at all" state (not published, not pending,
   * not rejected) instead of nothing - the private counterpart to the shared Globe. Opt-in so
   * call sites that only want the shared/pending/rejected markers (e.g. the skill tree) are
   * unaffected. The resource listing pages set it so every card shows a Globe (public) or Lock
   * (private) marker.
   */
  showPrivate?: boolean;
}

export function PublicationStatusIcon({
  isShared,
  isPending,
  isRejected,
  rejectionReason,
  className = '',
  showPrivate = false,
}: PublicationStatusIconProps) {
  const t = useTranslations();
  const [open, setOpen] = useState(false);

  if (isShared) {
    return (
      <span
        title={t('workflow.shared')}
        className={`shrink-0 text-theme-muted ${className}`}
      >
        <Globe className="h-3 w-3" />
      </span>
    );
  }

  if (isRejected) {
    return (
      <>
        <button
          type="button"
          title={rejectionReason || t('marketplace.rejected')}
          aria-label={t('marketplace.viewRejectionReason')}
          onClick={(e) => {
            e.stopPropagation();
            if (rejectionReason) setOpen(true);
          }}
          className={`shrink-0 text-red-500 hover:text-red-600 transition-colors ${rejectionReason ? 'cursor-pointer' : 'cursor-default'} ${className}`}
        >
          <XCircle className="h-3 w-3" />
        </button>
        <Dialog open={open} onOpenChange={setOpen}>
          <DialogContent className="max-w-md">
            <DialogHeader>
              <DialogTitle className="flex items-center gap-2 text-red-600 dark:text-red-400">
                <XCircle className="h-4 w-4" />
                {t('marketplace.rejectionReasonTitle')}
              </DialogTitle>
            </DialogHeader>
            <div className="mt-2 text-sm text-theme-secondary whitespace-pre-wrap break-words">
              {rejectionReason || t('marketplace.noRejectionReason')}
            </div>
          </DialogContent>
        </Dialog>
      </>
    );
  }

  if (isPending) {
    return (
      <span
        title={t('marketplace.pendingReview')}
        className={`shrink-0 text-amber-500 ${className}`}
      >
        <Clock className="h-3 w-3" />
      </span>
    );
  }

  if (showPrivate) {
    return (
      <span
        title={t('common.visibilityPrivate')}
        aria-label={t('common.visibilityPrivate')}
        className={`shrink-0 text-theme-muted ${className}`}
      >
        <Lock className="h-3 w-3" />
      </span>
    );
  }

  return null;
}
