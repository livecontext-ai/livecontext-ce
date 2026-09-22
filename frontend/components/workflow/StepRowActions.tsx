'use client';

import * as React from 'react';
import type { Node } from 'reactflow';
import type { BuilderNodeData } from '@/app/workflows/builder/types';
import { useWorkflowMode } from '@/contexts/WorkflowModeContext';
import { selectAllEpochs } from '@/components/workflow/run-panel/useDefaultEpochSelection';
import { boundRunId } from '@/components/workflow/run-panel/runPanelBus';
import { findNodeClassById } from '@/app/workflows/builder/nodes/nodeClasses';
import { deriveNodeContextFlags, useNodeContextualButtons } from '@/app/workflows/builder/hooks/useNodeContextualButtons';
import { isFireableTrigger } from '@/app/workflows/builder/hooks/fireableTrigger';
import { canvasNodeButtonClass } from '@/components/ui/canvas-chrome';
import { TriggerNodePinButton } from '@/app/workflows/builder/components/nodes/TriggerNodePinButton';
import { NodePlayButton } from '@/app/workflows/builder/components/NodePlayButton';
import { FileText } from 'lucide-react';
import { useTranslations } from 'next-intl';
import { useWorkflowLogsSidePanel } from '@/components/workflow/useWorkflowLogsSidePanel';
import { useWorkflowPanelHostSafe } from '@/contexts/WorkflowPanelHostContext';

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
  const t = useTranslations();
  const { isRunMode, runId, setViewingEpoch } = useWorkflowMode();
  const panelHost = useWorkflowPanelHostSafe();
  const { openWorkflowLogs, canOpenWorkflowLogs } = useWorkflowLogsSidePanel();
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
  const showPlay = isFireableTrigger(flags) && isRunActive;
  const showPin = flags.isTriggerNode && !!workflowId;
  const showLogs = !!workflowId && !!runId && canOpenWorkflowLogs;

  if (sideButtons.length === 0 && !showPlay && !showPin && !showLogs) return null;

  /**
   * Leave the focused epoch, so the epoch this launches is visible when it
   * arrives. Passed as `onBeforeLaunch` rather than folded into `fireTrigger`:
   * a chat, form or webhook trigger never reaches `fireTrigger` - its play opens
   * a side-panel tab and the run starts from there - so hanging this off the fire
   * path alone left the popover's payload triggers launching into a view the user
   * had focused elsewhere.
   */
  const returnToAllEpochs = () => selectAllEpochs(
    boundRunId(workflowId, runId, panelHost?.runSurfaceId),
    setViewingEpoch,
  );

  // Fire THIS trigger from the run-info popover: dispatch to WorkflowBuilder,
  // which calls the canonical handleExecuteStep path. epoch=undefined → fresh
  // epoch. workflowId scopes the event so a sub-workflow panel's WorkflowBuilder
  // ignores it.
  const fireTrigger = () => {
    window.dispatchEvent(new CustomEvent('workflowExecuteStep', { detail: { stepId: step.alias, workflowId } }));
  };

  return (
    <div
      className="flex items-center gap-1.5 mt-2 pt-2 border-t border-theme"
      onClick={(e) => e.stopPropagation()}
    >
      {showLogs && (
        <button
          type="button"
          onClick={(event) => {
            event.stopPropagation();
            openWorkflowLogs({
              workflowId: workflowId!,
              runId: runId!,
              initialStepAlias: step.alias,
              reuseActiveWorkflowTab: true,
            });
          }}
          title={t('workflow.logs.openNodeLogs')}
          aria-label={t('workflow.logs.openNodeLogs')}
          className={canvasNodeButtonClass}
        >
          <FileText className="h-3.5 w-3.5" />
        </button>
      )}
      {showPin && <TriggerNodePinButton workflowId={workflowId!} />}
      {sideButtons.map(({ key, icon, title, onClick }) => (
        <button
          key={key}
          type="button"
          onClick={(e) => { e.stopPropagation(); onClick(e); }}
          title={title}
          className={canvasNodeButtonClass}
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
          onBeforeLaunch={returnToAllEpochs}
          variant={flags.triggerVariant}
          // Names THIS trigger in the open-tab event a chat/form/webhook play
          // dispatches: several triggers can share a type, and matching on type
          // alone would activate the first tab of the family instead.
          triggerId={step.alias}
          isAutoMode={!isStepByStep}
          position="bottom-center"
        />
      )}
    </div>
  );
}
