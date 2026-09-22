'use client';

import React, { useState, useEffect, useRef } from 'react';
import { createPortal } from 'react-dom';
import { useRouter } from 'next/navigation';
import { CheckCircle, X, Gift, Coins, AlertTriangle, AppWindow, Monitor, Workflow, PackagePlus, Table2, Link2, Bot, Zap, Network, Download, Server, Lock } from 'lucide-react';
import { Checkbox } from '@/components/ui/checkbox';
import { Button } from '@/components/ui/button';
import { useTranslations } from 'next-intl';
import type { WorkflowPublication } from '@/lib/api/orchestrator/types';
import { isCeMode } from '@/lib/format-cost';
import { useMarketplaceDemoInstall } from '@/lib/marketplace/demoInstallMode';
import { ceExclusiveFeatureKeys, isCeExclusiveBlocked } from '@/lib/marketplace/ceExclusive';
import { PublisherAvatar } from '@/components/marketplace/PublisherAvatar';
import { VerifiedBadge } from '@/components/profile/VerifiedBadge';
import { track } from '@/lib/analytics/analytics';
import { useMarketplaceInstallStore } from '@/lib/stores/marketplace-install-store';
import { InstalledResourcesList } from '@/components/marketplace/InstallSummaryModal';

type ModalState =
  | 'confirm'
  | 'processing'
  | 'success'
  | 'error'
  | 'ce-exclusive'
  /**
   * The app uses a capability this workspace's plan does not include, today vector search.
   * Separate from 'ce-exclusive' because that screen is a dead end by design, while this one has
   * a route out: upgrade. Reached only from a backend 403 PLAN_UPGRADE_REQUIRED, never
   * pre-emptively, because only the server knows the workspace's plan.
   */
  | 'plan-upgrade-required'
  | 'link-required'
  | 'insufficient-credits';

interface AcquirePublicationModalProps {
  isOpen: boolean;
  onClose: () => void;
  publication: WorkflowPublication;
  onSuccess?: (workflowId: string) => void;
  /** CE remote mode: acquire from cloud marketplace instead of local */
  ceMode?: boolean;
  /**
   * Whether admin demo mode may SIMULATE this install (see
   * `lib/marketplace/demoInstallMode`). Marketplace browsing surfaces opt in;
   * the chat's agent-driven install must NOT, because that modal answers a real
   * tool authorization in a real conversation: a simulated success leaves
   * `onSuccess` unfired (there is no acquired id), and closing the modal then
   * DENIES the authorization, telling the agent the user refused right after
   * showing them an "Installed" screen. Default false, so a new consumer never
   * inherits the simulation by accident.
   */
  demoEligible?: boolean;
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
  demoEligible = false,
  inlineProgress,
  onInstallStarted,
}: AcquirePublicationModalProps) {
  const t = useTranslations('modals.acquire');
  // CE-exclusive copy lives in the `marketplace` namespace so the badge, the
  // card and this modal all read the same strings.
  const tMarketplace = useTranslations('marketplace');
  const router = useRouter();
  const [mounted, setMounted] = useState(false);

  // Opt-in editable WORKFLOW copy, requested at install time.
  //
  // The copy re-clones the app's whole snapshot (a second set of its interfaces,
  // tables and agents), which is exactly why it stopped being minted on every
  // install. Offering it here as an unchecked box keeps the cheap install the
  // default while sparing the user who DOES want to edit the app a second trip
  // through the application's settings cog.
  const [withEditableCopy, setWithEditableCopy] = useState(false);

  // The install state machine (simulated 5-10s progress + acquire call +
  // error mapping) lives in the SHARED marketplace-install store so it
  // survives this modal closing/unmounting (inline-progress mode) and page
  // navigation. This component is a view over that store.
  const active = useMarketplaceInstallStore((s) => s.active);
  const startInstall = useMarketplaceInstallStore((s) => s.startInstall);
  const clearInstall = useMarketplaceInstallStore((s) => s.clear);
  // Admin demo mode: this modal is the SINGLE entry point into the install
  // machine, so gating it here is enough to guarantee that no acquire call is
  // ever made while the mode is on.
  const demoInstall = useMarketplaceDemoInstall() && demoEligible;
  const isMyInstall = active?.publication.id === publication.id;
  const state: ModalState = isMyInstall
    ? active.status === 'installing'
      ? 'processing'
      : active.status
    : 'confirm';
  const progress = isMyInstall ? active.progress : 0;
  const error = isMyInstall ? active.error : null;
  const acquiredId = isMyInstall ? active.acquiredId : null;
  // This particular install is a rehearsal (the flag is read off the machine, not
  // off the toggle, so a mode switched mid-install cannot rewrite what happened).
  const isDemoInstall = isMyInstall ? active.demo : false;
  // What the acquire reported creating - drives the success screen's recap.
  const resources = isMyInstall ? active.resources : {};
  // Editable copy outcome for the success screen (see the confirm-screen opt-in).
  const editableCopyRequested = isMyInstall ? active.withEditableCopy : false;
  const editableCopyWorkflowId = isMyInstall ? active.editableCopyWorkflowId : null;
  const editableCopyFailed = isMyInstall ? active.editableCopyFailed : false;

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
      // A fresh open starts from the confirm screen, so the tick starts unchecked
      // too - a box left ticked from a previous publication would silently clone a
      // second app the user never asked to edit.
      setWithEditableCopy(false);
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

  // Only an APPLICATION acquired from someone else has an editable copy to make:
  // the publisher owns the source workflow directly (no APPLICATION clone), and the
  // resource types below install a single resource with nothing to decouple. Mirrors
  // `canCreateEditableCopy` in PublicationInfoPanel, which offers the same action
  // after the fact.
  const canRequestEditableCopy =
    displayMode === 'APPLICATION' &&
    !isAgent && !isSkill && !isTable && !isInterface &&
    !publication.ownedByMe;

  // Another publication is mid-install: the store is single-flight, so
  // confirming would be silently dropped - disable the CTA instead of lying.
  const otherInstallRunning = !isMyInstall && active?.status === 'installing';

  // Managed cloud cannot run a CE-exclusive publication: show the explanation
  // instead of a CTA that the backend would refuse with 403 CE_EXCLUSIVE.
  const ceExclusiveBlocked = isCeExclusiveBlocked(publication);

  const handleConfirm = () => {
    if (ceExclusiveBlocked) return;
    const fromConfirm = state === 'confirm';
    const started = startInstall(publication, {
      ceMode,
      inline: Boolean(inlineProgress),
      withEditableCopy: canRequestEditableCopy && withEditableCopy,
      demo: demoInstall,
    });
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
    // Nothing was installed in demo mode, so this is a plain navigation, not a
    // post-install event.
    if (!isDemoInstall) {
      track('app_post_install_opened', {
        publication_id: publication.id,
        publication_type: publication.publicationType ?? null,
        acquired_id: acquiredId,
      });
    }
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

  // Plain router.push (not next-intl's Link): this modal is embedded in many trees
  // (chat included) and the middleware re-applies the /{locale} prefix - same
  // approach as every other navigation in this component.
  const handleOpenEditableCopy = () => {
    if (!editableCopyWorkflowId) return;
    handleClose();
    router.push(`/app/workflow/${editableCopyWorkflowId}`);
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
            <div className="w-16 h-16 bg-theme-secondary rounded-2xl flex items-center justify-center mx-auto mb-5">
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
            <div className="w-16 h-16 bg-theme-secondary rounded-2xl flex items-center justify-center mx-auto mb-5">
              <CheckCircle className="h-8 w-8 text-theme-primary" />
            </div>
            <h2 id="acquire-publication-success-title" className="text-xl font-semibold text-theme-primary mb-2">
              {t('successTitle')}
            </h2>
            <p className="text-sm text-theme-secondary mb-5">
              {t('successMessage', { title: publication.title })}
            </p>
            {/* What the install actually created. The inline-progress consumers get the
                same list from InstallSummaryModal; here it rides the success screen so
                no install path leaves the user guessing what landed. */}
            {/* The requested editable copy rides the same recap: it is a second
                workflow the user can open right now, listed under (not among) the
                app's own resources. A failed copy is a note there, never an error
                screen - the application itself installed fine. */}
            <div className="rounded-xl border border-theme bg-theme-secondary/40 p-4 mb-6 text-left">
              <InstalledResourcesList
                publication={publication}
                resources={resources}
                editableCopy={editableCopyRequested
                  ? { workflowId: editableCopyWorkflowId, failed: editableCopyFailed }
                  : undefined}
                onOpenEditableCopy={handleOpenEditableCopy}
              />
            </div>
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
            <div className="w-16 h-16 bg-theme-secondary rounded-2xl flex items-center justify-center mx-auto mb-5">
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

  // CE-exclusive state. Reached two ways: pre-emptively on managed cloud (the
  // publication is flagged, so the confirm screen is never shown), or after a
  // backend 403 CE_EXCLUSIVE. No retry button - retrying cannot succeed on this
  // deployment; the only resolution is a self-hosted install.
  if (ceExclusiveBlocked || state === 'ce-exclusive') {
    const featureLabels = ceExclusiveFeatureKeys(publication).map((key) => tMarketplace(key));
    return createPortal(
      <div
        className="fixed inset-0 bg-black/20 backdrop-blur-sm z-[9999] flex items-center justify-center p-4"
        onClick={handleClose}
      >
        <div
          className="max-w-md w-full bg-theme-primary rounded-2xl shadow-[0_16px_48px_rgba(0,0,0,0.16)] p-6 animate-in fade-in-0 zoom-in-95 duration-200 border border-theme max-h-[90vh] overflow-y-auto"
          role="dialog"
          aria-modal="true"
          aria-labelledby="acquire-publication-ce-exclusive-title"
          data-testid="acquire-modal-ce-exclusive"
          onClick={(e) => e.stopPropagation()}
        >
          <div className="text-center">
            <div className="w-16 h-16 bg-theme-secondary rounded-2xl flex items-center justify-center mx-auto mb-5">
              <Server className="h-8 w-8 text-theme-primary" />
            </div>
            <h2 id="acquire-publication-ce-exclusive-title" className="text-xl font-semibold text-theme-primary mb-2">
              {tMarketplace('ceExclusiveTitle')}
            </h2>
            <p className="text-sm text-theme-secondary mb-4">
              {tMarketplace('ceExclusiveDescription')}
            </p>
            {featureLabels.length > 0 && (
              <ul className="text-sm text-theme-secondary mb-6 space-y-1">
                {featureLabels.map((label) => (
                  <li key={label}>{label}</li>
                ))}
              </ul>
            )}
            <Button onClick={handleClose} variant="outline" className="w-full">
              {t('close')}
            </Button>
          </div>
        </div>
      </div>,
      document.body
    );
  }

  // Plan-upgrade state. Reached only from a backend 403 PLAN_UPGRADE_REQUIRED: the card cannot
  // predict it, because whether this workspace's plan covers the app's capabilities is a server
  // fact. Unlike the CE screen above this one is NOT a dead end, so it offers the pricing page.
  if (state === 'plan-upgrade-required') {
    return createPortal(
      <div
        className="fixed inset-0 bg-black/20 backdrop-blur-sm z-[9999] flex items-center justify-center p-4"
        onClick={handleClose}
      >
        <div
          className="max-w-md w-full bg-theme-primary rounded-2xl shadow-[0_16px_48px_rgba(0,0,0,0.16)] p-6 animate-in fade-in-0 zoom-in-95 duration-200 border border-theme max-h-[90vh] overflow-y-auto"
          role="dialog"
          aria-modal="true"
          aria-labelledby="acquire-publication-plan-title"
          data-testid="acquire-modal-plan-upgrade"
          onClick={(e) => e.stopPropagation()}
        >
          <div className="text-center">
            <div className="w-16 h-16 bg-amber-50 dark:bg-amber-950/40 rounded-2xl flex items-center justify-center mx-auto mb-5">
              <Lock className="h-8 w-8 text-amber-600 dark:text-amber-400" />
            </div>
            <h2 id="acquire-publication-plan-title" className="text-xl font-semibold text-theme-primary mb-2">
              {tMarketplace('planUpgradeTitle')}
            </h2>
            <p className="text-sm text-theme-secondary mb-6">
              {/* The localized sentence, not the backend's: that one is English only, and this
                  screen is the one place a non-English customer is being asked to spend money. */}
              {tMarketplace('planUpgradeDescription')}
            </p>
            <div className="flex gap-2">
              <Button onClick={handleClose} variant="outline" className="flex-1">
                {t('close')}
              </Button>
              <Button
                className="flex-1"
                // Plain router.push, like every other navigation in this component: the modal is
                // embedded in many trees and the middleware re-applies the /{locale} prefix.
                onClick={() => { handleClose(); router.push('/app/settings/pricing'); }}
              >
                {tMarketplace('planUpgradeCta')}
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
            <div className="w-16 h-16 bg-theme-secondary rounded-2xl flex items-center justify-center mx-auto mb-5">
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
            <div className="w-16 h-16 bg-theme-secondary rounded-2xl flex items-center justify-center mx-auto mb-5">
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
          <div className="w-10 h-10 rounded-xl bg-theme-secondary flex items-center justify-center shrink-0">
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
              <VerifiedBadge userId={publication.publisherId} size="xs" />
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

        {/* Editable copy opt-in - unchecked by default: the copy duplicates every
            interface, table and agent the app carries, so it is a deliberate choice,
            not a free extra. The same action stays available afterwards from the
            application's settings cog. */}
        {canRequestEditableCopy && (
          <label
            htmlFor="acquire-editable-copy"
            className="flex items-start gap-2.5 mb-5 p-3 rounded-xl border border-theme cursor-pointer hover:bg-theme-secondary/40 transition-colors"
          >
            <Checkbox
              id="acquire-editable-copy"
              data-testid="acquire-editable-copy-checkbox"
              checked={withEditableCopy}
              onCheckedChange={(checked) => setWithEditableCopy(checked === true)}
              className="mt-0.5"
            />
            <span className="min-w-0">
              <span className="flex items-center gap-1.5 text-sm font-medium text-theme-primary">
                <Workflow className="h-3.5 w-3.5 shrink-0" aria-hidden="true" />
                {tMarketplace('editableCopy.button')}
              </span>
              <span className="block mt-0.5 text-xs text-theme-secondary leading-snug">
                {tMarketplace('editableCopy.description')}
              </span>
            </span>
          </label>
        )}

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

        {/* Another install is mid-flight and the machine takes one at a time: say so,
            instead of leaving a disabled button with no explanation. */}
        {otherInstallRunning && (
          <p className="text-xs text-theme-secondary mb-2" role="status">
            {t('installBusy')}
          </p>
        )}

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
