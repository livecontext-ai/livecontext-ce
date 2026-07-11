'use client';

import React, { useState, useEffect, useRef } from 'react';
import { createPortal } from 'react-dom';
import { useRouter } from 'next/navigation';
import { CheckCircle, X, Gift, Coins, AlertTriangle, AppWindow, Monitor, Workflow, PackagePlus, Table2, Link2, Bot, Zap, Network, Download } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { useTranslations } from 'next-intl';
import type { WorkflowPublication } from '@/lib/api/orchestrator/types';
import { isCeMode } from '@/lib/format-cost';
import { PublisherAvatar } from '@/components/marketplace/PublisherAvatar';
import { track } from '@/lib/analytics/analytics';
import { useMarketplaceInstallStore } from '@/lib/stores/marketplace-install-store';

type ModalState = 'confirm' | 'processing' | 'success' | 'error' | 'link-required' | 'insufficient-credits';

interface AcquirePublicationModalProps {
  isOpen: boolean;
  onClose: () => void;
  publication: WorkflowPublication;
  onSuccess?: (workflowId: string) => void;
  /** CE remote mode: acquire from cloud marketplace instead of local */
  ceMode?: boolean;
  /**
   * Inline-progress mode (marketplace grid + preview header): confirming the
   * install CLOSES the modal instead of showing the in-modal progress bar -
   * the caller renders the same progress on the publication CARD (the
   * interface preview un-greys as the gauge fills, via the shared
   * marketplace-install store). Error states (error / link-required /
   * insufficient-credits) are still rendered by this modal: the caller
   * re-mounts it while the store holds a terminal error. The success screen
   * is skipped too - the card flips to its "Open" button instead.
   */
  inlineProgress?: boolean;
  /**
   * Fired once when an inline-progress install actually starts from the
   * confirm screen (not on retry). Lets the preview page navigate back to
   * /app/marketplace so the user watches the card's progress there.
   */
  onInstallStarted?: () => void;
}

export default function AcquirePublicationModal({
  isOpen,
  onClose,
  publication,
  onSuccess,
  ceMode,
  inlineProgress,
  onInstallStarted,
}: AcquirePublicationModalProps) {
  const t = useTranslations('modals.acquire');
  const router = useRouter();
  const [mounted, setMounted] = useState(false);

  // The install state machine (simulated 5-10s progress + acquire call +
  // error mapping) lives in the SHARED marketplace-install store so it
  // survives this modal closing/unmounting (inline-progress mode) and page
  // navigation. This component is a view over that store.
  const active = useMarketplaceInstallStore((s) => s.active);
  const startInstall = useMarketplaceInstallStore((s) => s.startInstall);
  const clearInstall = useMarketplaceInstallStore((s) => s.clear);
  const isMyInstall = active?.publication.id === publication.id;
  const state: ModalState = isMyInstall
    ? active.status === 'installing'
      ? 'processing'
      : active.status
    : 'confirm';
  const progress = isMyInstall ? active.progress : 0;
  const error = isMyInstall ? active.error : null;
  const acquiredId = isMyInstall ? active.acquiredId : null;

  // Notify the caller exactly once per completed install (non-inline flow -
  // in inline mode the modal is already closed and the caller watches the
  // store directly). `sawInstallingRef` gates the notification on having
  // observed THIS install actually run during this mount: a lingering
  // 'success' left in the store by another consumer must never fire onSuccess
  // on mount (in ChatCore that would auto-approve a tool authorization for an
  // install this modal never ran).
  const successNotifiedRef = useRef(false);
  const sawInstallingRef = useRef(false);
  useEffect(() => {
    if (isMyInstall && active.status === 'installing') {
      sawInstallingRef.current = true;
    }
    if (isMyInstall && active.status === 'success' && active.acquiredId) {
      if (sawInstallingRef.current && !successNotifiedRef.current) {
        successNotifiedRef.current = true;
        onSuccess?.(active.acquiredId);
      }
      return;
    }
    if (!isMyInstall) {
      successNotifiedRef.current = false;
      sawInstallingRef.current = false;
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [isMyInstall, active?.status, active?.acquiredId]);

  // Lingering TERMINAL state hygiene on the open transition (a fresh open must
  // start at the confirm screen, not replay another consumer's outcome):
  // - 'success' is always dropped (the inline consumer that wanted it has
  //   already had its chance; a fresh open means the user is re-installing).
  // - errors are dropped only for NON-inline consumers: the inline flow
  //   deliberately re-mounts this modal to DISPLAY those error screens, so
  //   dropping them there would make errors vanish.
  const wasOpenRef = useRef(false);
  useEffect(() => {
    if (isOpen && !wasOpenRef.current) {
      const current = useMarketplaceInstallStore.getState().active;
      if (current?.publication.id === publication.id) {
        const isTerminal = current.status !== 'installing';
        if (current.status === 'success' || (isTerminal && !inlineProgress && !sawInstallingRef.current)) {
          clearInstall();
        }
      }
    }
    wasOpenRef.current = isOpen;
  }, [isOpen, publication.id, clearInstall, inlineProgress]);

  useEffect(() => {
    setMounted(true);
    return () => setMounted(false);
  }, []);

  if (!isOpen || !mounted) return null;

  const isFree = !publication.creditsPerUse || publication.creditsPerUse === 0;
  const displayMode = publication.displayMode || 'WORKFLOW';
  const isAgent = publication.publicationType === 'AGENT';
  const isSkill = publication.publicationType === 'SKILL';
  const isTable = publication.publicationType === 'TABLE';
  const isInterface = publication.publicationType === 'INTERFACE';

  // Another publication is mid-install: the store is single-flight, so
  // confirming would be silently dropped - disable the CTA instead of lying.
  const otherInstallRunning = !isMyInstall && active?.status === 'installing';

  const handleConfirm = () => {
    const fromConfirm = state === 'confirm';
    const started = startInstall(publication, { ceMode, inline: Boolean(inlineProgress) });
    if (!started) return; // another install is running - keep the modal as-is
    if (inlineProgress && fromConfirm) {
      // Return to the page: the caller's card takes over progress rendering.
      onClose();
      onInstallStarted?.();
    }
    // Inline retry (state was 'error'): the caller keeps this modal mounted
    // only while the store holds a terminal error, so flipping the store back
    // to 'installing' unmounts it naturally - no onClose here (it would race
    // the caller's derived mount condition).
  };

  const handleClose = () => {
    if (state === 'processing' && !inlineProgress) return; // prevent close while in-flight
    if (isMyInstall && active.status !== 'installing') {
      clearInstall();
    }
    onClose();
  };

  const handleGoToApplications = () => {
    track('app_post_install_opened', {
      publication_id: publication.id,
      publication_type: publication.publicationType ?? null,
      acquired_id: acquiredId,
    });
    handleClose();
    // Route to the post-install destination that matches the resource type so
    // the user lands on the page that actually shows what they just installed.
    if (isAgent) {
      router.push('/app/agent');
      return;
    }
    if (isTable) {
      router.push('/app/data');
      return;
    }
    if (isInterface) {
      router.push('/app/interface');
      return;
    }
    if (isSkill) {
      router.push('/app/agent');
      return;
    }
    router.push('/app/applications');
  };

  const handleGoToSettings = () => {
    handleClose();
    router.push('/app/settings/cloud-account');
  };

  // Inline-progress mode: processing renders on the caller's CARD and success
  // flips the card to its "Open" button - the modal stays out of the way.
  if (inlineProgress && (state === 'processing' || state === 'success')) {
    return null;
  }

  // Processing state - install progress bar (5-10s simulated duration). The
  // bar drives perception of work happening even when the backend responds
  // sub-second; a slower backend simply pauses at 95% until the call returns.
  if (state === 'processing') {
    return createPortal(
      <div
        className="fixed inset-0 bg-black/20 backdrop-blur-sm z-[9999] flex items-center justify-center p-4"
        onClick={handleClose}
      >
        <div
          className="max-w-md w-full bg-theme-primary rounded-2xl shadow-[0_16px_48px_rgba(0,0,0,0.16)] p-6 animate-in fade-in-0 zoom-in-95 duration-200 border border-theme max-h-[90vh] overflow-y-auto"
          role="dialog"
          aria-modal="true"
          aria-labelledby="acquire-publication-success-title"
          onClick={(e) => e.stopPropagation()}
        >
          <div className="text-center">
            <div className="w-16 h-16 bg-theme-secondary rounded-full flex items-center justify-center mx-auto mb-5">
              <Download className="h-7 w-7 text-theme-primary animate-pulse" />
            </div>
            <h2 className="text-xl font-semibold text-theme-primary mb-2">
              {t('processingTitle')}
            </h2>
            <p className="text-sm text-theme-secondary mb-5">
              {t('processingMessage')}
            </p>
            {/* Progress bar - explicit slate track + accent fill so it stays
              * visible in BOTH light and dark mode. Previously used
              * `bg-theme-primary` for the fill, which collapses to the same
              * value as the surrounding chrome in dark mode and made the bar
              * effectively invisible. */}
            <div
              role="progressbar"
              aria-valuemin={0}
              aria-valuemax={100}
              aria-valuenow={Math.round(progress)}
              aria-label={t('processingTitle')}
              className="w-full h-2.5 rounded-full bg-slate-200 dark:bg-slate-700 overflow-hidden ring-1 ring-slate-300/50 dark:ring-slate-600/60"
            >
              <div
                className="h-full bg-[var(--accent-primary)] shadow-[0_0_8px_var(--accent-primary)]"
                style={{
                  width: `${progress}%`,
                  // Smooth out the 50ms tick so the bar reads as a continuous
                  // fill rather than discrete jumps.
                  transition: 'width 100ms linear',
                }}
              />
            </div>
            <p className="mt-2 text-xs text-theme-secondary tabular-nums">
              {Math.round(progress)}%
            </p>
          </div>
        </div>
      </div>,
      document.body
    );
  }

  // Success state
  if (state === 'success') {
    return createPortal(
      <div
        className="fixed inset-0 bg-black/20 backdrop-blur-sm z-[9999] flex items-center justify-center p-4"
        onClick={handleClose}
      >
        <div
          className="max-w-md w-full bg-theme-primary rounded-2xl shadow-[0_16px_48px_rgba(0,0,0,0.16)] p-6 animate-in fade-in-0 zoom-in-95 duration-200 border border-theme max-h-[90vh] overflow-y-auto"
          onClick={(e) => e.stopPropagation()}
        >
          <div className="text-center">
            <div className="w-16 h-16 bg-theme-secondary rounded-full flex items-center justify-center mx-auto mb-5">
              <CheckCircle className="h-8 w-8 text-theme-primary" />
            </div>
            <h2 id="acquire-publication-success-title" className="text-xl font-semibold text-theme-primary mb-2">
              {t('successTitle')}
            </h2>
            <p className="text-sm text-theme-secondary mb-6">
              {t('successMessage', { title: publication.title })}
            </p>
            <Button
              onClick={handleGoToApplications}
              className="w-full"
            >
              {isAgent || isSkill ? t('goToAgents') : t('goToApplications')}
            </Button>
          </div>
        </div>
      </div>,
      document.body
    );
  }

  // Error state
  if (state === 'error') {
    return createPortal(
      <div
        className="fixed inset-0 bg-black/20 backdrop-blur-sm z-[9999] flex items-center justify-center p-4"
        onClick={handleClose}
      >
        <div
          className="max-w-md w-full bg-theme-primary rounded-2xl shadow-[0_16px_48px_rgba(0,0,0,0.16)] p-6 animate-in fade-in-0 zoom-in-95 duration-200 border border-theme max-h-[90vh] overflow-y-auto"
          onClick={(e) => e.stopPropagation()}
        >
          <div className="text-center">
            <div className="w-16 h-16 bg-theme-secondary rounded-full flex items-center justify-center mx-auto mb-5">
              <AlertTriangle className="h-8 w-8 text-theme-primary" />
            </div>
            <h2 className="text-xl font-semibold text-theme-primary mb-2">
              {t('errorTitle')}
            </h2>
            <p className="text-sm text-theme-secondary mb-6">
              {error || t('errorMessage')}
            </p>
            <div className="flex gap-3">
              <Button onClick={handleClose} variant="outline" className="flex-1">
                {t('close')}
              </Button>
              <Button onClick={handleConfirm} className="flex-1">
                {t('retry')}
              </Button>
            </div>
          </div>
        </div>
      </div>,
      document.body
    );
  }

  // Cloud account not linked state (CE paid publications)
  if (state === 'link-required') {
    return createPortal(
      <div
        className="fixed inset-0 bg-black/20 backdrop-blur-sm z-[9999] flex items-center justify-center p-4"
        onClick={handleClose}
      >
        <div
          className="max-w-md w-full bg-theme-primary rounded-2xl shadow-[0_16px_48px_rgba(0,0,0,0.16)] p-6 animate-in fade-in-0 zoom-in-95 duration-200 border border-theme max-h-[90vh] overflow-y-auto"
          onClick={(e) => e.stopPropagation()}
        >
          <div className="text-center">
            <div className="w-16 h-16 bg-theme-secondary rounded-full flex items-center justify-center mx-auto mb-5">
              <Link2 className="h-8 w-8 text-theme-primary" />
            </div>
            <h2 className="text-xl font-semibold text-theme-primary mb-2">
              {t('linkRequired')}
            </h2>
            <p className="text-sm text-theme-secondary mb-6">
              {t('linkRequiredDescription')}
            </p>
            <div className="flex gap-3">
              <Button onClick={handleClose} variant="outline" className="flex-1">
                {t('cancel')}
              </Button>
              <Button onClick={handleGoToSettings} className="flex-1">
                {t('goToSettings')}
              </Button>
            </div>
          </div>
        </div>
      </div>,
      document.body
    );
  }

  // Insufficient credits state
  if (state === 'insufficient-credits') {
    return createPortal(
      <div
        className="fixed inset-0 bg-black/20 backdrop-blur-sm z-[9999] flex items-center justify-center p-4"
        onClick={handleClose}
      >
        <div
          className="max-w-md w-full bg-theme-primary rounded-2xl shadow-[0_16px_48px_rgba(0,0,0,0.16)] p-6 animate-in fade-in-0 zoom-in-95 duration-200 border border-theme max-h-[90vh] overflow-y-auto"
          onClick={(e) => e.stopPropagation()}
        >
          <div className="text-center">
            <div className="w-16 h-16 bg-theme-secondary rounded-full flex items-center justify-center mx-auto mb-5">
              <Coins className="h-8 w-8 text-theme-primary" />
            </div>
            <h2 className="text-xl font-semibold text-theme-primary mb-2">
              {isCeMode ? t('insufficientBalance') : t('insufficientCredits')}
            </h2>
            <p className="text-sm text-theme-secondary mb-6">
              {t('creditsRequired', { required: publication.creditsPerUse ?? 0, balance: 0 })}
            </p>
            <Button onClick={handleClose} variant="outline" className="w-full">
              {t('close')}
            </Button>
          </div>
        </div>
      </div>,
      document.body
    );
  }

  // Confirm state (default)
  return createPortal(
    <div
      className="fixed inset-0 bg-black/20 backdrop-blur-sm z-[9999] flex items-center justify-center p-4"
      onClick={handleClose}
    >
      <div
        className="max-w-md w-full bg-theme-primary rounded-2xl shadow-[0_16px_48px_rgba(0,0,0,0.16)] p-6 animate-in fade-in-0 zoom-in-95 duration-200 border border-theme max-h-[90vh] overflow-y-auto"
        role="dialog"
        aria-modal="true"
        aria-labelledby="acquire-publication-title"
        onClick={(e) => e.stopPropagation()}
      >
        {/* Header: icon + title + publisher */}
        <div className="flex items-start gap-3 mb-5">
          <div className="w-10 h-10 rounded-full bg-theme-secondary flex items-center justify-center shrink-0">
            <PackagePlus className="h-5 w-5 text-theme-primary" />
          </div>
          <div className="min-w-0">
            <h2 id="acquire-publication-title" className="text-base font-semibold text-theme-primary truncate">
              {publication.title}
            </h2>
            <div className="flex items-center gap-1.5 mt-0.5">
              <PublisherAvatar userId={publication.publisherId} name={publication.publisherName} size={14} variant="neutral" remote={ceMode} />
              <span className="text-xs text-theme-secondary">
                {publication.publisherName || t('anonymous')}
              </span>
            </div>
          </div>
        </div>

        {publication.description && (
          <p className="text-sm text-theme-secondary mb-5 line-clamp-2">
            {publication.description}
          </p>
        )}

        {/* Included recap */}
        <p className="text-xs font-medium text-theme-secondary uppercase tracking-wide mb-2">
          {t('includedLabel')}
        </p>
        <div className="flex flex-col gap-1.5 mb-5">
          {isAgent ? (
            <>
              <div className="flex items-center gap-2">
                <Bot className="h-3.5 w-3.5 text-theme-secondary shrink-0" />
                <span className="text-sm text-theme-primary">{t('includesAgent')}</span>
              </div>
              {(publication.agentCount ?? 0) > 0 && (
                <div className="flex items-center gap-2">
                  <Network className="h-3.5 w-3.5 text-theme-secondary shrink-0" />
                  <span className="text-sm text-theme-primary">
                    {t('includesSubAgents', { count: publication.agentCount })}
                  </span>
                </div>
              )}
              {(publication.skillCount ?? 0) > 0 && (
                <div className="flex items-center gap-2">
                  <Zap className="h-3.5 w-3.5 text-theme-secondary shrink-0" />
                  <span className="text-sm text-theme-primary">
                    {t('includesSkills', { count: publication.skillCount })}
                  </span>
                </div>
              )}
            </>
          ) : isSkill ? (
            <div className="flex items-center gap-2">
              <Zap className="h-3.5 w-3.5 text-theme-secondary shrink-0" />
              <span className="text-sm text-theme-primary">{t('includesSkill')}</span>
            </div>
          ) : isTable ? (
            <div className="flex items-center gap-2">
              <Table2 className="h-3.5 w-3.5 text-theme-secondary shrink-0" />
              <span className="text-sm text-theme-primary">{t('includesDatasource')}</span>
            </div>
          ) : isInterface ? (
            <div className="flex items-center gap-2">
              <Monitor className="h-3.5 w-3.5 text-theme-secondary shrink-0" />
              <span className="text-sm text-theme-primary">{t('includesInterface')}</span>
            </div>
          ) : (
            <>
              {displayMode === 'APPLICATION' && (
                <div className="flex items-center gap-2">
                  <AppWindow className="h-3.5 w-3.5 text-theme-secondary shrink-0" />
                  <span className="text-sm text-theme-primary">{t('includesApplication')}</span>
                </div>
              )}
              <div className="flex items-center gap-2">
                <Workflow className="h-3.5 w-3.5 text-theme-secondary shrink-0" />
                <span className="text-sm text-theme-primary">{t('includesWorkflow')}</span>
              </div>
            </>
          )}
          {(publication.interfaceCount ?? 0) > 0 && (
            <div className="flex items-center gap-2">
              <Monitor className="h-3.5 w-3.5 text-theme-secondary shrink-0" />
              <span className="text-sm text-theme-primary">
                {(publication.interfaceCount ?? 0) === 1
                  ? t('includesInterface')
                  : t('includesInterfaces', { count: publication.interfaceCount })}
              </span>
            </div>
          )}
          {(publication.datasourceCount ?? 0) > 0 && (
            <div className="flex items-center gap-2">
              <Table2 className="h-3.5 w-3.5 text-theme-secondary shrink-0" />
              <span className="text-sm text-theme-primary">
                {(publication.datasourceCount ?? 0) === 1
                  ? t('includesDatasource')
                  : t('includesDatasources', { count: publication.datasourceCount })}
              </span>
            </div>
          )}
        </div>

        {/* Price */}
        <div className="flex items-center gap-2 mb-6">
          {isFree ? (
            <>
              <Gift className="h-4 w-4 text-theme-primary" />
              <span className="text-sm font-medium text-theme-primary">{t('freeApplication')}</span>
            </>
          ) : (
            <>
              <Coins className="h-4 w-4 text-theme-primary" />
              <span className="text-sm font-medium text-theme-primary">
                {isCeMode ? t('oneTimeCostDollar', { amount: publication.creditsPerUse }) : t('oneTimeCost', { credits: publication.creditsPerUse })}
              </span>
            </>
          )}
        </div>

        {/* Buttons */}
        <div className="flex gap-3 mt-8">
          <Button onClick={handleClose} variant="outline" className="flex-1">
            {t('cancel')}
          </Button>
          <Button onClick={handleConfirm} disabled={otherInstallRunning} className="flex-1">
            {isFree ? t('addToApplications') : (isCeMode ? t('purchaseForDollar', { amount: publication.creditsPerUse }) : t('purchaseFor', { credits: publication.creditsPerUse }))}
          </Button>
        </div>
      </div>
    </div>,
    document.body
  );
}
