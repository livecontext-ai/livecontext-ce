'use client';

import * as React from 'react';
import type { Node } from 'reactflow';
import type { BuilderNodeData } from '@/app/workflows/builder/types';
import { useWorkflowMode } from '@/contexts/WorkflowModeContext';
import { findNodeClassById } from '@/app/workflows/builder/nodes/nodeClasses';
import { deriveNodeContextFlags, useNodeContextualButtons } from '@/app/workflows/builder/hooks/useNodeContextualButtons';
import { TriggerNodePinButton } from '@/app/workflows/builder/components/nodes/TriggerNodePinButton';
import { NodePlayButton } from '@/app/workflows/builder/components/NodePlayButton';

interface StepRowActionsProps {
  /** Aggregated run step - `alias` is the backend step id used to fire it. */
  step: { alias: string };
  /** Canvas node matched to this step (provides node-type data). */
  matchedNode: Node<BuilderNodeData>;
  workflowId?: string;
  /** Whether the run is in step-by-step mode (controls the play auto/SBS hint). */
  isStepByStep: boolean;
  /**
   * Whether the run is non-terminal. The trigger play is hidden on terminal runs
   * (COMPLETED/FAILED/…) to match the canvas, which hides it once nothing is
   * ready, and because the backend dispatcher rejects firing into a terminal run.
   */
  isRunActive: boolean;
}

const SIDE_BTN_CLS =
  'relative inline-flex items-center justify-center h-7 w-7 rounded-full bg-white dark:bg-gray-800 text-slate-600 dark:text-slate-300 hover:bg-slate-100 dark:hover:bg-slate-700 hover:scale-110 shadow-md transition-all duration-200 border border-slate-200 dark:border-slate-700';

/**
 * Contextual action buttons for a run-info step row, shown inside the hover
 * popover. Mirrors the node bottom-bar buttons - pin (triggers) + side-panel
 * (agent config/conversation, table data, sub-workflow; Files excluded) +
 * trigger play - reusing the exact same builders (useNodeContextualButtons,
 * TriggerNodePinButton, NodePlayButton) so behavior stays in lock-step with the
 * canvas.
 *
 * The popover lives OUTSIDE the StepByStepProvider (it is a sibling of
 * WorkflowBuilder), so the trigger play fires by dispatching the
 * `workflowExecuteStep` window event - scoped by workflowId so a concurrently
 * mounted sub-workflow builder panel does not also fire - which WorkflowBuilder
 * handles via the canonical handleExecuteStep path.
 */
export function StepRowActions({ step, matchedNode, workflowId, isStepByStep, isRunActive }: StepRowActionsProps) {
  const { isRunMode, setViewingEpoch } = useWorkflowMode();
  const data = matchedNode.data;
  const nodeClass = findNodeClassById(data.id || '');
  const flags = deriveNodeContextFlags(data, nodeClass?.id);
  const sideButtons = useNodeContextualButtons({
    data,
    nodeUiId: matchedNode.id,
    isRunMode,
    flags,
  });

  // Same trigger set the node bottom bar exposes a play for. chat/form/webhook
  // open the trigger tab from inside NodePlayButton; the rest fire the trigger.
  // Gated on an active (non-terminal) run, mirroring the canvas.
  const isFireableTrigger =
    flags.isManualTrigger || flags.isChatTrigger || flags.isFormTrigger ||
    flags.isWebhookTrigger || flags.isScheduleTrigger || flags.isWorkflowsTriggerNode ||
    flags.isTablesTrigger || flags.isErrorTrigger;
  const showPlay = isFireableTrigger && isRunActive;
  const showPin = flags.isTriggerNode && !!workflowId;

  if (sideButtons.length === 0 && !showPlay && !showPin) return null;

  // Fire THIS trigger from the run-info popover: return to all-epochs (same as
  // the focus-epoch node play) then dispatch to WorkflowBuilder, which calls the
  // canonical handleExecuteStep path. epoch=undefined → fresh epoch. workflowId
  // scopes the event so a sub-workflow panel's WorkflowBuilder ignores it.
  const fireTrigger = () => {
    setViewingEpoch(null);
    window.dispatchEvent(new CustomEvent('workflowExecuteStep', { detail: { stepId: step.alias, workflowId } }));
  };

  return (
    <div
      className="flex items-center gap-1.5 mt-2 pt-2 border-t border-theme"
      onClick={(e) => e.stopPropagation()}
    >
      {showPin && <TriggerNodePinButton workflowId={workflowId!} />}
      {sideButtons.map(({ key, icon, title, onClick }) => (
        <button
          key={key}
          type="button"
          onClick={(e) => { e.stopPropagation(); onClick(e); }}
          title={title}
          className={SIDE_BTN_CLS}
        >
          {icon}
        </button>
      ))}
      {showPlay && (
        <NodePlayButton
          nodeId={matchedNode.id}
          status="ready"
          canExecute
          onExecute={fireTrigger}
          variant={flags.triggerVariant}
          isAutoMode={!isStepByStep}
          position="bottom-center"
        />
      )}
    </div>
  );
}
