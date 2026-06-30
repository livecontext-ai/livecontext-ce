'use client';

import { useTranslations } from 'next-intl';
import { useWorkflowMode } from '@/contexts/WorkflowModeContext';
import { useRun } from '@/contexts/WorkflowRunContext';

/**
 * Window event the node right-click menu dispatches to ask the mounted trigger
 * pin button (matched by nodeId) to open its pin/unpin confirmation flow. The
 * button owns the modal, so routing through it keeps the confirmation alive
 * after the ephemeral context menu closes.
 */
export const TRIGGER_PIN_REQUEST_EVENT = 'workflowRequestPin';

/** Dispatches {@link TRIGGER_PIN_REQUEST_EVENT} for a specific trigger node. */
export function requestTriggerPin(workflowId: string, nodeId: string): void {
  window.dispatchEvent(
    new CustomEvent(TRIGGER_PIN_REQUEST_EVENT, { detail: { workflowId, nodeId } }),
  );
}

export interface TriggerPinVersionState {
  /** Version the pin action targets: the run's plan version in run mode, else the canvas/active version. */
  targetVersion: number | null;
  /** Unsaved edits exist (edit mode only) - pinning must save first. */
  effectiveDirty: boolean;
  /** Version metadata has loaded - below this the affordance is hidden. */
  loaded: boolean;
  /** The canvas/run version is the currently pinned production one - the affordance unpins. */
  isAlreadyPinned: boolean;
  /** A different version is pinned as production. */
  hasOtherPin: boolean;
  /** Mirror of the pin button's render gating - whether the affordance is offered at all. */
  shouldRender: boolean;
}

/**
 * Pure version-math for the trigger pin affordance. Extracted so the pin button
 * under the node and the node right-click menu derive identical state from a
 * single tested source of truth (no React, no i18n - just the version
 * comparison + visibility gating that used to live inline in the button).
 */
export function computeTriggerPinState(input: {
  isRunMode: boolean;
  runPlanVersion: number | null;
  currentVersion: number | null;
  activeVersion: number | null;
  pinnedVersion: number | null;
  workflowDirty: boolean;
}): TriggerPinVersionState {
  const { isRunMode, runPlanVersion, currentVersion, activeVersion, pinnedVersion, workflowDirty } = input;
  // In edit mode the canvas may show a restored older version, not HEAD - compare
  // against activeVersion (canvas-truth), not currentVersion (HEAD).
  const targetVersion = isRunMode ? runPlanVersion : (activeVersion ?? currentVersion);
  // Dirty only matters in edit mode - run mode can't save, the canvas just views
  // the run's frozen version.
  const effectiveDirty = workflowDirty && !isRunMode;
  const loaded = currentVersion !== null || pinnedVersion !== null;
  const isAlreadyPinned = !effectiveDirty && targetVersion != null && targetVersion === pinnedVersion;
  const hasOtherPin = !isAlreadyPinned && pinnedVersion !== null && pinnedVersion !== targetVersion;
  const shouldRender =
    loaded &&
    !(isRunMode && targetVersion == null) &&
    !(!isRunMode && targetVersion == null && !effectiveDirty) &&
    // Run mode + already on the production run: nothing to do. Edit mode flips to
    // unpin (still a useful fast affordance on the canvas).
    !(isAlreadyPinned && isRunMode);
  return { targetVersion, effectiveDirty, loaded, isAlreadyPinned, hasOtherPin, shouldRender };
}

/**
 * Builds the human pin label from the derived state. Shared by the pin button
 * (its `title`) and the menu item so the two can never drift. Takes a plain
 * translator so it stays a pure, testable function (no hook).
 */
export function triggerPinTitle(
  t: (key: string) => string,
  state: TriggerPinVersionState,
  pinnedVersion: number | null,
): string {
  if (state.effectiveDirty) return t('versionHistory.pinSaveTitle');
  if (state.isAlreadyPinned && state.targetVersion != null) {
    return `${t('versionHistory.unpin')} v${state.targetVersion}`;
  }
  if (state.hasOtherPin && state.targetVersion != null) {
    return `${t('versionHistory.pin')} v${state.targetVersion} (${t('versionHistory.pinned')}: v${pinnedVersion})`;
  }
  return state.targetVersion != null
    ? `${t('versionHistory.pin')} v${state.targetVersion}`
    : t('versionHistory.pin');
}

export interface TriggerPinDisplay {
  /** Whether the pin/unpin affordance should be offered at all. */
  shouldRender: boolean;
  /** True when the affordance unpins (canvas/run version is the pinned production one). */
  isAlreadyPinned: boolean;
  /** Human label, e.g. "Set as production v3" / "Remove from production v3". */
  buttonTitle: string;
}

/**
 * Read-only hook returning the trigger pin affordance's visibility + label from
 * WorkflowModeContext (plus the live run's plan version in run mode). Shared by
 * the pin button under the node and the node right-click menu so both show the
 * exact same Pin/Unpin state. No side effects: the menu dispatches
 * {@link requestTriggerPin} to run the actual flow on the mounted button.
 */
export function useTriggerPinDisplay(): TriggerPinDisplay {
  const t = useTranslations();
  const { isRunMode, runId, currentVersion, activeVersion, pinnedVersion, workflowDirty } = useWorkflowMode();
  const [runState] = useRun(runId ?? undefined);
  const runPlanVersion: number | null = runState?.rawRunState?.planVersion ?? null;
  const state = computeTriggerPinState({
    isRunMode, runPlanVersion, currentVersion, activeVersion, pinnedVersion, workflowDirty,
  });

  return {
    shouldRender: state.shouldRender,
    isAlreadyPinned: state.isAlreadyPinned,
    buttonTitle: triggerPinTitle(t, state, pinnedVersion),
  };
}
