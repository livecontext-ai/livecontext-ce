'use client';

import React, { useState, useEffect } from 'react';
import { useTranslations } from 'next-intl';
import { PackagePlus, Play, CheckCircle, Ban } from 'lucide-react';
import LoadingSpinner from '@/components/LoadingSpinner';
import { Button } from '@/components/ui/button';
import { PublicationCard, PublicationCardSkeleton } from '@/components/marketplace/PublicationCard';
import { publicationService } from '@/lib/api/orchestrator/publication.service';
import type { WorkflowPublication } from '@/lib/api/orchestrator/types';
import type { PendingToolAuthorization } from '@/contexts/StreamingContext';

export interface ToolAuthorizationCardProps {
  /** Conversation ID */
  conversationId: string;
  /** Pending tool-authorization request */
  pendingAuthorization: PendingToolAuthorization;
  /**
   * Approve - receives the rule, `blanket` (whether the user ticked "ne plus
   * demander dans cette conversation", turning on `chatConfig.autoAuthorizeTools`),
   * and the card's toolCallId so the caller can clear THIS card without
   * suppressing a sibling card of the same rule (F16).
   */
  onApproved?: (rule: string, blanket: boolean, toolCallId?: string) => void;
  /** Decline - receives the rule and the card's toolCallId (see onApproved). */
  onDenied?: (rule: string, toolCallId?: string) => void;
  className?: string;
}

/**
 * Inline action card shown when the chat agent requests a sensitive action.
 *
 * Neutral (not a warning), styled like the marketplace cards. For
 * `application:acquire` it shows the actual marketplace publication preview (the
 * exact {@link PublicationCard} visual, minus its install CTA - the card's own
 * "Install" button performs it). The copy adapts: install vs run. A "ne plus
 * demander dans cette conversation" checkbox flips the per-conversation
 * auto-authorize toggle.
 */
export function ToolAuthorizationCard({
  pendingAuthorization,
  onApproved,
  onDenied,
  className = '',
}: ToolAuthorizationCardProps) {
  const t = useTranslations('toolAuthorization');
  const [isApproved, setIsApproved] = useState(false);
  const [isDenied, setIsDenied] = useState(false);
  const [pending, setPending] = useState(false);
  const [blanket, setBlanket] = useState(false);

  const rule = pendingAuthorization.rule;
  const isInstall = rule === 'application:acquire';
  const applicationId = pendingAuthorization.applicationId;

  // Per-action title/subtitle so the user sees a clear description of what the
  // agent is about to do (e.g. continue a paused interface / resolve an approval)
  // rather than the generic "run a sensitive action". Falls back to the generic
  // run copy for any other gated action.
  const titleKey = isInstall ? 'installTitle'
    : rule === 'workflow:continue_interface' ? 'continueInterfaceTitle'
    : rule === 'workflow:resolve_approval' ? 'resolveApprovalTitle'
    : 'runTitle';
  const subtitleKey = isInstall ? 'installSubtitle'
    : rule === 'workflow:continue_interface' ? 'continueInterfaceSubtitle'
    : rule === 'workflow:resolve_approval' ? 'resolveApprovalSubtitle'
    : 'runSubtitle';

  // Load the publication so we render its marketplace preview instead of raw args.
  // Public (marketplace-safe) fetch - the app may not be owned yet (we're acquiring it).
  const [publication, setPublication] = useState<WorkflowPublication | null>(null);
  const [pubLoading, setPubLoading] = useState(!!applicationId);

  useEffect(() => {
    if (!applicationId) {
      setPubLoading(false);
      return;
    }
    let cancelled = false;
    setPubLoading(true);
    publicationService
      .getPublicationByIdPublic(applicationId)
      .then((p) => { if (!cancelled) setPublication(p as WorkflowPublication); })
      .catch(() => { if (!cancelled) setPublication(null); })
      .finally(() => { if (!cancelled) setPubLoading(false); });
    return () => { cancelled = true; };
  }, [applicationId]);

  const approve = () => {
    setPending(true);
    setIsApproved(true);
    onApproved?.(rule, blanket, pendingAuthorization.toolCallId);
  };

  const deny = () => {
    setIsDenied(true);
    onDenied?.(rule, pendingAuthorization.toolCallId);
  };

  if (isApproved) {
    return (
      <div className={`my-4 ${className}`}>
        <div className="rounded-2xl border border-green-200 dark:border-green-800 bg-green-50 dark:bg-green-900/20 p-4">
          <div className="flex items-center gap-3">
            <div className="flex items-center justify-center w-8 h-8 rounded-full bg-white dark:bg-slate-800">
              <CheckCircle className="w-4 h-4 text-green-600 dark:text-green-400" />
            </div>
            <div className="flex-1">
              <h3 className="text-sm font-semibold text-slate-900 dark:text-slate-100">
                {isInstall ? t('installing') : t('approved')}
              </h3>
              <p className="text-xs text-slate-600 dark:text-slate-400">
                {isInstall ? t('installingMessage') : t('approvedMessage')}
              </p>
            </div>
          </div>
        </div>
      </div>
    );
  }

  if (isDenied) {
    return (
      <div className={`my-4 ${className}`}>
        <div className="rounded-2xl border border-theme bg-theme-secondary/50 p-4">
          <div className="flex items-center gap-3">
            <div className="flex items-center justify-center w-8 h-8 rounded-full bg-theme-primary">
              <Ban className="w-4 h-4 text-theme-secondary" />
            </div>
            <div className="flex-1">
              <h3 className="text-sm font-semibold text-theme-secondary">{t('denied')}</h3>
              <p className="text-xs text-theme-muted">{t('deniedMessage')}</p>
            </div>
          </div>
        </div>
      </div>
    );
  }

  return (
    <div className={`my-4 ${className}`} data-testid="tool-authorization-card">
      <div className="rounded-2xl border border-theme bg-gradient-to-br from-[var(--bg-secondary)] to-[var(--bg-tertiary)] p-4 shadow-sm">
        {/* Header */}
        <div className="flex items-center gap-3 mb-3">
          <div className="flex items-center justify-center w-8 h-8 rounded-full bg-theme-primary border border-theme shrink-0">
            {isInstall ? (
              <PackagePlus className="w-4 h-4 text-theme-primary" />
            ) : (
              <Play className="w-4 h-4 text-theme-primary" />
            )}
          </div>
          <div className="min-w-0">
            <h3 className="text-sm font-semibold text-theme-primary">
              {t(titleKey)}
            </h3>
            <p className="text-xs text-theme-secondary">
              {t(subtitleKey)}
            </p>
          </div>
        </div>

        {/* Application preview - the exact marketplace card, no install CTA (the card's
            own Install button performs it) and non-navigating (pointer-events-none). */}
        {applicationId && (
          <div className="rounded-2xl bg-theme-primary border border-theme p-3">
            {pubLoading ? (
              <PublicationCardSkeleton />
            ) : publication ? (
              <div className="pointer-events-none select-none">
                <PublicationCard publication={publication} />
              </div>
            ) : null}
          </div>
        )}

        {/* Footer: "don't ask again" checkbox + actions */}
        <div className="mt-3 flex items-center justify-between gap-3">
          <label className="flex items-center gap-2 text-xs text-theme-secondary cursor-pointer select-none">
            <input
              type="checkbox"
              checked={blanket}
              onChange={(e) => setBlanket(e.target.checked)}
              disabled={pending}
              className="h-3.5 w-3.5 rounded border-theme accent-[var(--accent-primary)] cursor-pointer"
              data-testid="tool-authorization-dont-ask"
            />
            {t('dontAskAgain')}
          </label>
          <div className="flex gap-2">
            <Button variant="ghost" size="sm" onClick={deny} disabled={pending}>
              {t('deny')}
            </Button>
            <Button variant="default" size="sm" onClick={approve} disabled={pending} className="gap-2">
              {pending ? (
                <>
                  <LoadingSpinner size="xs" />
                  {isInstall ? t('installing') : t('approving')}
                </>
              ) : (
                <>{isInstall ? t('install') : t('approve')}</>
              )}
            </Button>
          </div>
        </div>
      </div>
    </div>
  );
}

export default ToolAuthorizationCard;
