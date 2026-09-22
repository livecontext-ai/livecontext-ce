'use client';

import React, { useEffect, useState } from 'react';
import { createPortal } from 'react-dom';
import { useEnterRunMode } from '@/hooks/useEnterRunMode';
import { Pin, PinOff, Save, X } from 'lucide-react';
import { useTranslations } from 'next-intl';
import { Button } from '@/components/ui/button';
import { cn } from '@/lib/utils';
import { canvasChromeCompactButtonClass, canvasNodeButtonClass } from '@/components/ui/canvas-chrome';
import LoadingSpinner from '@/components/LoadingSpinner';
import { orchestratorApi } from '@/lib/api';
import { track } from '@/lib/analytics/analytics';
import { useRefreshHomeStatus } from '@/hooks/useHomeStatus';
import { useWorkflowMode } from '@/contexts/WorkflowModeContext';
import { useRun } from '@/contexts/WorkflowRunContext';
import { computeTriggerPinState, triggerPinTitle, TRIGGER_PIN_REQUEST_EVENT } from '../../hooks/useTriggerPin';

interface TriggerNodePinButtonProps {
  workflowId: string;
  /**
   * React Flow node id of the trigger this button sits under. Used to match the
   * pin-request event the node right-click menu dispatches so only this button
   * (not every trigger's button) opens the confirmation flow.
   */
  nodeId?: string;
  /** Border color synced with node status - applied as 2px border to match NodeBottomBar buttons */
  borderColor?: string;
  /**
   * Where the button is rendered. `node` (default) is the badge attached
   * under a trigger node and answers the context menu's pin request; `toolbar`
   * is the flat variant living in the canvas toolbar, which stays silent on
   * that event so a menu-triggered flow never opens two confirmations at once.
   */
  variant?: 'node' | 'toolbar';
}

type ModalKind = 'pin-fresh' | 'pin-replace' | 'pin-save-then' | 'unpin';

/**
 * Pin button rendered to the left of every trigger node. The workflow-level
 * state (current/active/pinned version + dirty flag) lives in
 * WorkflowModeContext so every trigger button shares it and the state
 * survives node remounts when a restored version has different node ids.
 */
export const TriggerNodePinButton: React.FC<TriggerNodePinButtonProps> = ({ workflowId, nodeId, borderColor, variant = 'node' }) => {
  const t = useTranslations();
  const enterRunMode = useEnterRunMode(workflowId);
  const {
    isRunMode,
    runId,
    currentVersion,
    activeVersion,
    pinnedVersion,
    workflowDirty,
  } = useWorkflowMode();
  const [runState] = useRun(runId ?? undefined);
  const runPlanVersion: number | null = runState?.rawRunState?.planVersion ?? null;

  const refreshAutomations = useRefreshHomeStatus();

  const [mounted, setMounted] = useState(false);
  const [pinning, setPinning] = useState(false);
  const [modal, setModal] = useState<ModalKind | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => { setMounted(true); return () => setMounted(false); }, []);

  // Version-math + render gating shared with the node right-click menu so both
  // surfaces show the exact same Pin/Unpin affordance (single source of truth).
  const pinState = computeTriggerPinState({ isRunMode, runPlanVersion, currentVersion, activeVersion, pinnedVersion, workflowDirty });
  const { targetVersion, effectiveDirty, isAlreadyPinned, shouldRender } = pinState;

  const openModalForState = (freshCurrent: number, freshPinned: number | null) => {
    const freshActive: number | null = isRunMode ? runPlanVersion : (activeVersion ?? freshCurrent);
    let chosen: ModalKind | null = null;
    if (isRunMode) {
      if (runPlanVersion == null) chosen = null;
      else if (freshPinned === runPlanVersion) chosen = null;
      else if (freshPinned !== null) chosen = 'pin-replace';
      else chosen = 'pin-fresh';
    } else if (effectiveDirty) {
      chosen = 'pin-save-then';
    } else if (freshActive == null || freshActive <= 0) {
      chosen = null;
    } else if (freshPinned === freshActive) {
      chosen = 'unpin';
    } else if (freshPinned !== null) {
      chosen = 'pin-replace';
    } else {
      chosen = 'pin-fresh';
    }
    if (chosen !== null) setModal(chosen);
  };

  const handleClick = async (e?: React.MouseEvent) => {
    e?.stopPropagation();
    if (pinning || modal !== null) return;
    try {
      const data = await orchestratorApi.listVersions(workflowId);
      openModalForState(data.currentVersion ?? 0, data.pinnedVersion ?? null);
    } catch (err: any) {
      setError(err?.message || t('versionHistory.pinError'));
      setTimeout(() => setError(null), 5000);
    }
  };

  // Latest-closure ref so the request-pin listener (registered once per
  // workflow/node) always invokes the current handleClick without re-subscribing
  // each render. Updated via effect (not a render-time write) to stay lint-clean.
  const handleClickRef = React.useRef(handleClick);
  useEffect(() => { handleClickRef.current = handleClick; });

  // The node right-click menu can't own the confirmation modal (it unmounts the
  // instant an item is clicked), so its Pin/Unpin item dispatches
  // TRIGGER_PIN_REQUEST_EVENT and this always-mounted button - matched by nodeId
  // so siblings stay silent - opens the same flow.
  useEffect(() => {
    // The toolbar copy is not attached to any node, so it must not answer a
    // node-scoped pin request (it would stack a second modal on the node's).
    if (variant !== 'node') return;
    const onRequest = (e: Event) => {
      const detail = (e as CustomEvent).detail;
      if (detail?.workflowId !== workflowId) return;
      if (nodeId && detail?.nodeId && detail.nodeId !== nodeId) return;
      void handleClickRef.current();
    };
    window.addEventListener(TRIGGER_PIN_REQUEST_EVENT, onRequest);
    return () => window.removeEventListener(TRIGGER_PIN_REQUEST_EVENT, onRequest);
  }, [workflowId, nodeId, variant]);

  // Resolves to { ok: true } on a successful save, or { ok: false, message } on
  // failure (or 15s timeout). The detail.error from BuilderCanvas/WorkflowBuilder
  // is surfaced so users see what actually went wrong instead of a generic toast.
  // The `workflowId` check filters out save events from concurrent dispatches
  // on a different workflow (e.g. a breadcrumb rename in another tab/panel).
  const waitForSave = (): Promise<{ ok: boolean; message?: string }> => new Promise((resolve) => {
    const handler = (e: Event) => {
      const detail = (e as CustomEvent).detail;
      if (detail?.workflowId !== workflowId) return;
      window.removeEventListener('workflowViewSaveComplete', handler);
      resolve(detail?.success ? { ok: true } : { ok: false, message: detail?.error });
    };
    window.addEventListener('workflowViewSaveComplete', handler);
    window.dispatchEvent(new CustomEvent('workflowViewSave', { detail: { workflowId } }));
    setTimeout(() => {
      window.removeEventListener('workflowViewSaveComplete', handler);
      resolve({ ok: false });
    }, 15000);
  });

  const callPin = async (versionToPin: number | null) => {
    const result = await orchestratorApi.pinVersion(workflowId, versionToPin);
    if (result.success) {
      // Pinning is what puts a workflow in the bell's Triggers rows, and in the imminent-fire
      // ring that shows WITHOUT the bell being opened. Nothing else invalidates that payload,
      // so without this the user pins, looks, and reads state from before the action.
      refreshAutomations();
      window.dispatchEvent(new CustomEvent('workflowPinnedVersionChange', {
        detail: { pinnedVersion: result.pinnedVersion, workflowId },
      }));
      track('workflow_version_pinned', {
        workflow_id: workflowId,
        version: result.pinnedVersion ?? null,
        is_unpin: versionToPin === null,
        has_production_run: Boolean(result.productionRunIdPublic),
        flow: 'trigger_button',
      });
      if (result.productionRunIdPublic) {
        // Binds in place on an embedded canvas rather than routing the app away.
        enterRunMode(result.productionRunIdPublic);
      }
    }
  };

  const confirm = async () => {
    const kind = modal;
    setModal(null);
    setPinning(true);
    try {
      if (kind === 'pin-save-then') {
        const saveResult = await waitForSave();
        if (!saveResult.ok) {
          setError(saveResult.message || t('versionHistory.pinError'));
          setTimeout(() => setError(null), 5000);
          return;
        }
        const data = await orchestratorApi.listVersions(workflowId);
        const freshCurrent = data.currentVersion ?? 0;
        if (freshCurrent <= 0) {
          setError(t('versionHistory.pinError'));
          setTimeout(() => setError(null), 5000);
          return;
        }
        await callPin(freshCurrent);
      } else if (kind === 'unpin') {
        await callPin(null);
      } else if (kind === 'pin-fresh' || kind === 'pin-replace') {
        const versionToPin = isRunMode ? runPlanVersion : (activeVersion ?? currentVersion);
        if (versionToPin == null) return;
        await callPin(versionToPin);
      }
    } catch (err: any) {
      setError(err?.message || t('versionHistory.pinError'));
      setTimeout(() => setError(null), 5000);
    } finally {
      setPinning(false);
    }
  };

  const buttonTitle = triggerPinTitle(t, pinState, pinnedVersion);

  const renderModalBody = () => {
    if (modal === 'unpin') {
      return (
        <>
          <div className="text-center mb-6">
            <div className="w-14 h-14 bg-red-100 dark:bg-red-900/30 rounded-xl flex items-center justify-center mx-auto mb-4">
              <PinOff className="w-7 h-7 text-red-600 dark:text-red-400" />
            </div>
            <h3 className="text-lg font-semibold text-theme-primary">
              {t('versionHistory.unpin')} - v{targetVersion}
            </h3>
            <p className="text-sm text-theme-secondary mt-2">
              {t('versionHistory.unpinConfirm')}
            </p>
            <div className="mt-3 p-3 bg-red-50 dark:bg-red-900/20 rounded-xl border border-red-200 dark:border-red-800">
              <p className="text-xs text-red-600 dark:text-red-400">
                {t('versionHistory.unpinWarning')}
              </p>
            </div>
          </div>
          <div className="flex gap-3">
            <Button variant="outline" onClick={() => setModal(null)} className="flex-1">
              {t('common.cancel')}
            </Button>
            <Button onClick={confirm} className="flex-1 bg-red-600 hover:bg-red-700 text-white">
              {t('versionHistory.unpin')}
            </Button>
          </div>
        </>
      );
    }
    if (modal === 'pin-replace') {
      return (
        <>
          <div className="text-center mb-6">
            <div className="w-14 h-14 bg-amber-100 dark:bg-amber-900/30 rounded-xl flex items-center justify-center mx-auto mb-4">
              <Pin className="w-7 h-7 text-amber-600 dark:text-amber-400" />
            </div>
            <h3 className="text-lg font-semibold text-theme-primary">
              {t('versionHistory.pinReplaceTitle', { current: targetVersion ?? 0, pinned: pinnedVersion ?? 0 })}
            </h3>
            <p className="text-sm text-theme-secondary mt-2">
              {t('versionHistory.pinReplaceConfirm', { current: targetVersion ?? 0, pinned: pinnedVersion ?? 0 })}
            </p>
          </div>
          <div className="flex gap-3">
            <Button variant="outline" onClick={() => setModal(null)} className="flex-1">
              {t('common.cancel')}
            </Button>
            <Button onClick={confirm} className="flex-1">
              {t('versionHistory.pin')}
            </Button>
          </div>
        </>
      );
    }
    if (modal === 'pin-save-then') {
      return (
        <>
          <div className="text-center mb-6">
            <div className="w-14 h-14 bg-amber-100 dark:bg-amber-900/30 rounded-xl flex items-center justify-center mx-auto mb-4">
              <Save className="w-7 h-7 text-amber-600 dark:text-amber-400" />
            </div>
            <h3 className="text-lg font-semibold text-theme-primary">
              {t('versionHistory.pinSaveTitle')}
            </h3>
            <p className="text-sm text-theme-secondary mt-2">
              {pinnedVersion != null
                ? t('versionHistory.pinSaveReplaceConfirm', { pinned: pinnedVersion })
                : t('versionHistory.pinSaveConfirm')}
            </p>
          </div>
          <div className="flex gap-3">
            <Button variant="outline" onClick={() => setModal(null)} className="flex-1">
              {t('common.cancel')}
            </Button>
            <Button onClick={confirm} className="flex-1">
              {t('versionHistory.pinSaveAction')}
            </Button>
          </div>
        </>
      );
    }
    // pin-fresh
    return (
      <>
        <div className="text-center mb-6">
          <div className="w-14 h-14 bg-amber-100 dark:bg-amber-900/30 rounded-xl flex items-center justify-center mx-auto mb-4">
            <Pin className="w-7 h-7 text-amber-600 dark:text-amber-400" />
          </div>
          <h3 className="text-lg font-semibold text-theme-primary">
            {t('versionHistory.pin')} - v{targetVersion}
          </h3>
          <p className="text-sm text-theme-secondary mt-2">
            {t('versionHistory.pinConfirm')}
          </p>
        </div>
        <div className="flex gap-3">
          <Button variant="outline" onClick={() => setModal(null)} className="flex-1">
            {t('common.cancel')}
          </Button>
          <Button onClick={confirm} className="flex-1">
            {t('versionHistory.pin')}
          </Button>
        </div>
      </>
    );
  };

  // Sober styling: the same theme-token surface as every other bottom-bar
  // button. Pin state is conveyed by the icon (Pin vs PinOff) and the title -
  // no amber accents in either edit or run mode.
  const statusBorderStyle = borderColor
    ? { borderWidth: 2, borderStyle: 'solid' as const, borderColor }
    : undefined;
  const iconClass = variant === 'toolbar' ? 'w-4 h-4' : 'w-3.5 h-3.5';

  return (
    <>
      {/* The button is hidden when the affordance doesn't apply, but the
          modals below always render so a menu-triggered flow can still show its
          confirmation even if the button itself is gated out at that instant. */}
      {shouldRender && (
        <button
          type="button"
          onClick={handleClick}
          onMouseDown={(e) => e.stopPropagation()}
          disabled={pinning}
          title={buttonTitle}
          style={variant === 'toolbar' ? undefined : statusBorderStyle}
          data-testid={variant === 'toolbar' ? 'canvas-toolbar-pin-button' : 'node-pin-button'}
          className={
            variant === 'toolbar'
              // Shared canvas-chrome control: same square Button shape, height,
              // hover ladder and focus ring as every other toolbar button.
              ? canvasChromeCompactButtonClass(false, 'relative')
              // Under a node: the shared square node button. Same shape as the
              // toolbar one above, opaque because nothing sits under it. Its
              // hairline border shows through wherever no status border is passed
              // (the run-step popover), so there is nothing to add here.
              : cn(canvasNodeButtonClass, 'nodrag nopan')
          }
        >
          {/* Unpin only ever shows in edit mode: run mode gates the affordance
              out entirely once the viewed version IS the pinned one. */}
          {pinning ? (
            <LoadingSpinner size="xs" />
          ) : isAlreadyPinned ? (
            <PinOff className={iconClass} />
          ) : (
            <Pin className={iconClass} />
          )}
        </button>
      )}

      {mounted && modal !== null && createPortal(
        <div
          className="fixed inset-0 bg-black/20 backdrop-blur-sm z-[9999] flex items-center justify-center p-4"
          onClick={() => setModal(null)}
        >
          <div
            className="max-w-sm w-full bg-theme-primary rounded-3xl shadow-2xl p-8 animate-in fade-in-0 zoom-in-95 duration-300 border border-theme"
            onClick={(e) => e.stopPropagation()}
          >
            {renderModalBody()}
          </div>
        </div>,
        document.body
      )}

      {mounted && error && createPortal(
        <div className="fixed top-4 right-4 z-[9999] max-w-sm animate-in fade-in-0 slide-in-from-top-2 duration-300">
          <div className="flex items-start gap-3 px-4 py-3 rounded-xl bg-red-50 dark:bg-red-900/30 border border-red-200 dark:border-red-800 shadow-lg">
            <X
              className="w-4 h-4 text-red-500 shrink-0 mt-0.5 cursor-pointer"
              onClick={() => setError(null)}
            />
            <p className="text-sm text-red-600 dark:text-red-400">{error}</p>
          </div>
        </div>,
        document.body
      )}
    </>
  );
};
