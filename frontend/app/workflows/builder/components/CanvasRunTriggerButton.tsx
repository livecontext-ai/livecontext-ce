'use client';

import * as React from 'react';
import { createPortal } from 'react-dom';
import type { Node } from 'reactflow';
import { useTranslations } from 'next-intl';
import {
  Play, Zap, MessageSquare, FileText, Webhook, CalendarClock, Workflow, Database, AlertTriangle,
} from 'lucide-react';
import { canvasChromeCompactButtonClass } from '@/components/ui/canvas-chrome';
import { useWorkflowMode } from '@/contexts/WorkflowModeContext';
import { selectAllEpochs } from '@/components/workflow/run-panel/useDefaultEpochSelection';
import { boundRunId } from '@/components/workflow/run-panel/runPanelBus';
import { dispatchOpenTriggerTab, type TriggerPanelTab } from '@/lib/workflow/triggerTabEvent';
import { useStepByStep } from '../contexts/StepByStepContext';
import { deriveNodeContextFlags } from '../hooks/useNodeContextualButtons';
import { isFireableTrigger } from '../hooks/fireableTrigger';
import { usePortalMenu } from '../hooks/usePortalMenu';
import { findNodeClassById } from '../nodes/nodeClasses';
import { PANEL_TAB_BY_TRIGGER_VARIANT, type TriggerButtonVariant } from './NodePlayButton';
import type { BuilderNodeData } from '../types';
import { useWorkflowPanelHostSafe } from '@/contexts/WorkflowPanelHostContext';

/** Icon per trigger type, mirroring the trigger palette (the node play button
 * itself renders a plain Play for every variant, so this is a menu affordance,
 * not a parity requirement). */
const ICON_BY_VARIANT: Record<TriggerButtonVariant, React.ComponentType<{ className?: string }>> = {
  play: Play,
  lightning: Zap,
  message: MessageSquare,
  form: FileText,
  webhook: Webhook,
  schedule: CalendarClock,
  workflow: Workflow,
  table: Database,
  error: AlertTriangle,
};

interface TriggerEntry {
  nodeId: string;
  /** Backend step id, e.g. `trigger:my_webhook`. */
  stepId: string;
  label: string;
  variant: TriggerButtonVariant;
  /** Side-panel tab to open instead of firing directly (payload triggers). */
  panelTab?: TriggerPanelTab;
  canFire: boolean;
}

interface CanvasRunTriggerButtonProps {
  nodes: Node<BuilderNodeData>[];
}

/**
 * Run-mode toolbar control: a Play button opening a menu of the workflow's
 * triggers, so a run can be (re)fired from the canvas chrome without hunting
 * for the right trigger node.
 *
 * <p>Each entry does exactly what that trigger's own play button does: chat /
 * form / webhook triggers open their side-panel tab (they need a payload),
 * every other trigger fires immediately through the step-by-step context - the
 * single run-mode execution path.
 */
/** Widest the menu can get; the clamp and the class below read the same number. */
const TRIGGER_MENU_WIDTH = 320;

export function CanvasRunTriggerButton({ nodes }: CanvasRunTriggerButtonProps) {
  const t = useTranslations('workflowBuilder.canvas');
  const ctx = useStepByStep();
  const { workflowId, runId, setViewingEpoch } = useWorkflowMode();
  const panelHost = useWorkflowPanelHostSafe();
  // Anchored ABOVE the button: the toolbar sits at the bottom of the canvas.
  const { open, isVisible, toggle, close, triggerRef, menuRef, menuStyle } = usePortalMenu('above', TRIGGER_MENU_WIDTH);

  const triggers = React.useMemo<TriggerEntry[]>(() => {
    if (!ctx) return [];
    return nodes.flatMap((node) => {
      const data = node.data;
      if (!data) return [];
      // Canonical class id passed like every other call site (FlowNode, the
      // context menu, the run-info step row) so the flags never diverge.
      const flags = deriveNodeContextFlags(data, findNodeClassById(data.id ?? '')?.id ?? data.id);
      // Same predicate the node bottom bar uses to decide it owes a play button:
      // a generic entry node carries no fireable trigger type, so offering it
      // here would promise a run the node itself does not.
      if (!isFireableTrigger(flags)) return [];
      const stepId = ctx.resolveNodeId(node.id, { label: data.label, kind: data.kind });
      return [{
        nodeId: node.id,
        stepId,
        label: data.label || node.id,
        variant: flags.triggerVariant,
        panelTab: PANEL_TAB_BY_TRIGGER_VARIANT[flags.triggerVariant],
        canFire: ctx.canExecuteStep(stepId),
      }];
    });
  }, [nodes, ctx]);

  const fire = React.useCallback((entry: TriggerEntry) => {
    close();
    // Firing always targets the whole run, never the focused epoch - same as the
    // trigger node's focus-epoch play button. Keyed off the run the CANVAS is
    // bound to, which is the id the epoch selection is read back under.
    //
    // Before BOTH paths: a payload trigger's run starts later, from the panel,
    // which cannot return the canvas to all epochs. Leaving this below the early
    // return meant a chat, form or webhook launched into whichever epoch the user
    // had focused, and the new one stayed hidden behind the selector.
    selectAllEpochs(boundRunId(workflowId, runId, panelHost?.runSurfaceId), setViewingEpoch);
    if (entry.panelTab) {
      // triggerId lets the panel select THIS trigger's tab; without it the
      // listeners fall back to the first trigger sharing the same type.
      dispatchOpenTriggerTab({ nodeId: entry.nodeId, triggerId: entry.stepId, triggerType: entry.panelTab });
      return;
    }
    void ctx?.executeStep(entry.stepId, undefined);
  }, [ctx, workflowId, runId, panelHost?.runSurfaceId, setViewingEpoch, close]);

  // Nothing fireable (no trigger at all, or a terminal run where every trigger
  // is frozen) means no affordance: an enabled Play over an all-greyed menu is
  // a dead end, and the per-node plays are gone on a terminal run too. Read from
  // canExecuteStep rather than the node plays' interactive gate on purpose -
  // this button stays available while a single epoch is focused, because firing
  // returns to the all-epochs view first (selectAllEpochs above).
  if (!triggers.some((entry) => entry.canFire)) return null;

  return (
    <>
      <button
        type="button"
        ref={triggerRef}
        onClick={toggle}
        className={canvasChromeCompactButtonClass(open)}
        title={t('runWorkflow')}
        aria-haspopup="menu"
        aria-expanded={open}
        data-testid="canvas-run-trigger-button"
      >
        <Play className="h-4 w-4" fill="currentColor" strokeWidth={2} />
      </button>

      {isVisible && createPortal(
        <div
          ref={menuRef}
          role="menu"
          className="fixed z-[9999] min-w-[220px] max-w-[min(320px,calc(100vw-1rem))] bg-theme-primary rounded-2xl p-2 border border-gray-300/70 dark:border-gray-600/70 shadow-2xl animate-in fade-in-0 zoom-in-95 duration-150"
          style={menuStyle}
          onMouseDown={(e) => e.stopPropagation()}
        >
          <div className="px-3 py-2 text-sm font-medium text-theme-primary">
            {t('runWithTrigger')}
          </div>
          <div className="space-y-1">
            {triggers.map((entry) => {
              const Icon = ICON_BY_VARIANT[entry.variant];
              return (
                <button
                  key={entry.nodeId}
                  type="button"
                  role="menuitem"
                  // aria-disabled, not `disabled`: browsers swallow mouse events
                  // on a disabled control, so its `title` would never surface the
                  // reason. The click is guarded below instead.
                  aria-disabled={!entry.canFire}
                  onClick={() => { if (entry.canFire) fire(entry); }}
                  title={entry.canFire ? entry.label : t('triggerNotReady')}
                  className="w-full flex items-center gap-3 px-3 py-2.5 rounded-xl transition-colors text-theme-primary hover:bg-gray-100 dark:hover:bg-gray-800 aria-disabled:opacity-40 aria-disabled:cursor-not-allowed aria-disabled:hover:bg-transparent dark:aria-disabled:hover:bg-transparent"
                >
                  <Icon className="h-3.5 w-3.5 flex-shrink-0" />
                  <span className="text-sm flex-1 text-left truncate">{entry.label}</span>
                </button>
              );
            })}
          </div>
        </div>,
        document.body,
      )}
    </>
  );
}
