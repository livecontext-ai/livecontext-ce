'use client';

import React, { useState, useEffect, useRef } from 'react';
import { createPortal } from 'react-dom';
import { useRouter } from 'next/navigation';
import { CheckCircle, X, Gift, Coins, AlertTriangle, AppWindow, Monitor, Workflow, PackagePlus, Table2, Link2, Bot, Zap, Network, Download } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { useTranslations } from 'next-intl';
import { publicationService } from '@/lib/api/orchestrator/publication.service';
import type { WorkflowPublication } from '@/lib/api/orchestrator/types';
import { isCeMode } from '@/lib/format-cost';
import { PublisherAvatar } from '@/components/marketplace/PublisherAvatar';
import { track } from '@/lib/analytics/analytics';

type ModalState = 'confirm' | 'processing' | 'success' | 'error' | 'link-required' | 'insufficient-credits';

interface AcquirePublicationModalProps {
  isOpen: boolean;
  onClose: () => void;
  publication: WorkflowPublication;
  onSuccess?: (workflowId: string) => void;
  /** CE remote mode: acquire from cloud marketplace instead of local */
  ceMode?: boolean;
}

export default function AcquirePublicationModal({
  isOpen,
  onClose,
  publication,
  onSuccess,
  ceMode,
}: AcquirePublicationModalProps) {
  const t = useTranslations('modals.acquire');
  const router = useRouter();
  const [state, setState] = useState<ModalState>('confirm');
  const [error, setError] = useState<string | null>(null);
  const [acquiredWorkflowId, setAcquiredWorkflowId] = useState<string | null>(null);
  const [mounted, setMounted] = useState(false);
  // Install progress (0-100) - drives the download bar shown during the
  // 'processing' state. The bar's animation duration is randomized between
  // 5-10s on each acquire, while the real HTTP call runs in parallel; the
  // success screen is gated on BOTH the animation reaching 100% AND the
  // call returning, so a slow backend never lies and a fast backend never
  // looks instantaneous.
  const [progress, setProgress] = useState(0);
  const mountedRef = useRef(true);
  const progressIntervalRef = useRef<ReturnType<typeof setInterval> | null>(null);

  useEffect(() => {
    setMounted(true);
    mountedRef.current = true;
    return () => {
      mountedRef.current = false;
      setMounted(false);
      if (progressIntervalRef.current) {
        clearInterval(progressIntervalRef.current);
        progressIntervalRef.current = null;
      }
    };
  }, []);

  if (!isOpen || !mounted) return null;

  const isFree = !publication.creditsPerUse || publication.creditsPerUse === 0;
  const displayMode = publication.displayMode || 'WORKFLOW';
  const isAgent = publication.publicationType === 'AGENT';
  const isSkill = publication.publicationType === 'SKILL';
  const isTable = publication.publicationType === 'TABLE';
  const isInterface = publication.publicationType === 'INTERFACE';
  // TABLE / INTERFACE / SKILL go through `/publications/acquire-resource/{id}`,
  // not the workflow `/acquire` endpoint. Routing is keyed on publicationType
  // so the modal stays a single entry point for every install path (the
  // alternative - duplicating the progress bar in ResourceMarketplaceGrid +
  // MarketplacePage.handleReinstall - would drift fast).
  const isResource =
    isTable ||
    isInterface ||
    isSkill;

  const handleConfirm = async () => {
    setError(null);
    setState('processing');
    setProgress(0);

    track('app_install_started', {
      publication_id: publication.id,
      publication_type: publication.publicationType ?? null,
      is_free: isFree,
      ce_mode: Boolean(ceMode),
    });

    // Roll a 5-10s "install" duration. The progress bar eases from 0 to 95%
    // over this window via easeOutCubic so the early ramp feels fast and the
    // last 30% takes its time - looks like a real download/install. The
    // remaining 5% is reserved for a final snap-to-100 once the HTTP call
    // returns (so a backend slower than the visual budget keeps the bar at
    // 95% rather than lying about completion).
    const minDuration = 5000;
    const maxDuration = 10000;
    const targetDuration = minDuration + Math.floor(Math.random() * (maxDuration - minDuration + 1));
    const startTime = performance.now();

    if (progressIntervalRef.current) {
      clearInterval(progressIntervalRef.current);
    }
    progressIntervalRef.current = setInterval(() => {
      if (!mountedRef.current) return;
      const elapsed = performance.now() - startTime;
      const ratio = Math.min(elapsed / targetDuration, 1);
      const eased = 1 - Math.pow(1 - ratio, 3); // easeOutCubic
      setProgress(Math.min(95, eased * 95));
      if (ratio >= 1 && progressIntervalRef.current) {
        clearInterval(progressIntervalRef.current);
        progressIntervalRef.current = null;
      }
    }, 50);

    // CE-cloud (ceMode): the publication lives on the cloud, so EVERY type -
    // agent, resource, workflow - installs through the unified remote acquire
    // (/publications/remote/{id}/acquire). That endpoint charges the linked
    // cloud account for paid publications (insufficient funds → an error here),
    // clones the right payload locally, and returns the matching id
    // (agentId / resourceId / workflowId) the success handler reads below.
    // Off CE-cloud, each type keeps its own local acquire endpoint.
    const acquireCall = ceMode
      ? publicationService.acquireRemotePublication(publication.id)
      : isAgent
        ? publicationService.acquireAgentPublication(publication.id)
        : isResource
          ? publicationService.acquireResourcePublication(publication.id)
          : publicationService.acquirePublication(publication.id);

    try {
      const result = await acquireCall;
      if (!mountedRef.current) return;

      // Wait until the visual minimum has elapsed before showing success -
      // a sub-second backend response would otherwise flash the bar past
      // and feel jarring.
      const remaining = Math.max(0, targetDuration - (performance.now() - startTime));
      if (remaining > 0) {
        await new Promise((r) => setTimeout(r, remaining));
      }
      if (!mountedRef.current) return;

      if (progressIntervalRef.current) {
        clearInterval(progressIntervalRef.current);
        progressIntervalRef.current = null;
      }
      // Snap to 100% then a short hold so the user sees the bar fill.
      setProgress(100);
      await new Promise((r) => setTimeout(r, 300));
      if (!mountedRef.current) return;

      // Result shape varies by acquire endpoint - agent → {agentId},
      // resource → {resourceId, type}, workflow/remote → {workflowId, ...}.
      // The success handler only needs the canonical id; downstream consumers
      // route by publication type from their own context.
      const id = isAgent
        ? (result as { agentId: string }).agentId
        : isResource
          ? (result as { resourceId: string }).resourceId
          : (result as { workflowId: string }).workflowId;
      setAcquiredWorkflowId(id);
      setState('success');
      track('app_install_succeeded', {
        publication_id: publication.id,
        publication_type: publication.publicationType ?? null,
        is_free: isFree,
        acquired_id: id,
        duration_ms: Math.round(performance.now() - startTime),
      });
      onSuccess?.(id);
    } catch (err: any) {
      if (progressIntervalRef.current) {
        clearInterval(progressIntervalRef.current);
        progressIntervalRef.current = null;
      }
      if (!mountedRef.current) return;
      let outcome: string;
      if (err.status === 403 && err.code === 'CLOUD_ACCOUNT_NOT_LINKED') {
        setState('link-required');
        outcome = 'link_required';
      } else if (err.status === 402) {
        setState('insufficient-credits');
        outcome = 'insufficient_credits';
      } else {
        setError(err.message || t('errorMessage'));
        setState('error');
        outcome = 'error';
      }
      track('app_install_failed', {
        publication_id: publication.id,
        publication_type: publication.publicationType ?? null,
        outcome,
        error_code: err?.code ?? (err?.status != null ? String(err.status) : null),
      });
    }
  };

  const handleClose = () => {
    if (state === 'processing') return; // prevent close while in-flight
    setState('confirm');
    setError(null);
    setAcquiredWorkflowId(null);
    onClose();
  };

  const handleGoToApplications = () => {
    track('app_post_install_opened', {
      publication_id: publication.id,
      publication_type: publication.publicationType ?? null,
      acquired_id: acquiredWorkflowId,
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
          className="max-w-md w-full bg-theme-primary rounded-3xl shadow-2xl p-8 animate-in fade-in-0 zoom-in-95 duration-300 border border-theme"
          role="dialog"
          aria-modal="true"
          aria-labelledby="acquire-publication-success-title"
          onClick={(e) => e.stopPropagation()}
        >
          <div className="text-center">
            <div className="w-16 h-16 bg-theme-secondary rounded-full flex items-center justify-center mx-auto mb-5">
              <Download className="h-7 w-7 text-theme-primary animate-pulse" />
            </div>
            <h2 className="text-2xl font-semibold text-theme-primary mb-2">
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
          className="max-w-md w-full bg-theme-primary rounded-3xl shadow-2xl p-8 animate-in fade-in-0 zoom-in-95 duration-300 border border-theme"
          onClick={(e) => e.stopPropagation()}
        >
          <div className="text-center">
            <div className="w-16 h-16 bg-theme-secondary rounded-full flex items-center justify-center mx-auto mb-5">
              <CheckCircle className="h-8 w-8 text-theme-primary" />
            </div>
            <h2 id="acquire-publication-success-title" className="text-2xl font-semibold text-theme-primary mb-2">
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
          className="max-w-md w-full bg-theme-primary rounded-3xl shadow-2xl p-8 animate-in fade-in-0 zoom-in-95 duration-300 border border-theme"
          onClick={(e) => e.stopPropagation()}
        >
          <div className="text-center">
            <div className="w-16 h-16 bg-theme-secondary rounded-full flex items-center justify-center mx-auto mb-5">
              <AlertTriangle className="h-8 w-8 text-theme-primary" />
            </div>
            <h2 className="text-2xl font-semibold text-theme-primary mb-2">
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
          className="max-w-md w-full bg-theme-primary rounded-3xl shadow-2xl p-8 animate-in fade-in-0 zoom-in-95 duration-300 border border-theme"
          onClick={(e) => e.stopPropagation()}
        >
          <div className="text-center">
            <div className="w-16 h-16 bg-theme-secondary rounded-full flex items-center justify-center mx-auto mb-5">
              <Link2 className="h-8 w-8 text-theme-primary" />
            </div>
            <h2 className="text-2xl font-semibold text-theme-primary mb-2">
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
          className="max-w-md w-full bg-theme-primary rounded-3xl shadow-2xl p-8 animate-in fade-in-0 zoom-in-95 duration-300 border border-theme"
          onClick={(e) => e.stopPropagation()}
        >
          <div className="text-center">
            <div className="w-16 h-16 bg-theme-secondary rounded-full flex items-center justify-center mx-auto mb-5">
              <Coins className="h-8 w-8 text-theme-primary" />
            </div>
            <h2 className="text-2xl font-semibold text-theme-primary mb-2">
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
        className="max-w-md w-full bg-theme-primary rounded-3xl shadow-2xl p-8 animate-in fade-in-0 zoom-in-95 duration-300 border border-theme max-h-[90vh] overflow-y-auto"
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
          <Button onClick={handleConfirm} className="flex-1">
            {isFree ? t('addToApplications') : (isCeMode ? t('purchaseForDollar', { amount: publication.creditsPerUse }) : t('purchaseFor', { credits: publication.creditsPerUse }))}
          </Button>
        </div>
      </div>
    </div>,
    document.body
  );
}
