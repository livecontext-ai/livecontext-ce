'use client';

import * as React from 'react';
import clsx from 'clsx';
import { Handle, NodeProps, Position } from 'reactflow';
import { CheckCircle, XCircle } from 'lucide-react';

import { getNodeVisual } from '../../data/nodeVisuals';
import type { BuilderNodeData, GuardrailRule, DerivedNodeStatus, NodeStatus } from '../../types';
import { createDefaultGuardrailRules } from '../../types';
import { useValidation } from '../../contexts/ValidationContext';
import { NodeActionButtons, NodeHeader, useHoverVisibility, getIconSlug, getStatusBorderColor } from './shared';
import { NodeStatusBadge } from '../NodeStatusBadge';
import { findNodeClassById } from '../../nodes/nodeClasses';
import { useWorkflowMode } from '@/contexts/WorkflowModeContext';
import { NodePlayButton } from '../NodePlayButton';
import { useNodeExecutionStatus } from '../../contexts/StepByStepContext';
import { NodeBottomBar } from './NodeBottomBar';
import { getEffectiveDefaultProvider } from '@/hooks/useModels';
import { getProviderIconSlug } from '@/lib/ai-providers/providerIcons';

import { useWorkflowLayoutDirectionSafe } from '@/contexts/WorkflowLayoutDirectionContext';
import { getTargetHandleGeometry, getBranchHandleGeometry, getBranchRowFlow } from './handleGeometry';
// Guardrail type labels for display
const GUARDRAIL_TYPE_LABELS: Record<string, string> = {
  pii_detection: 'PII Detection',
  toxic_language: 'Toxic Language',
  prompt_injection: 'Prompt Injection',
  keyword_filter: 'Keyword Filter',
  regex_pattern: 'Regex Pattern',
  length_check: 'Length Check',
  topic_restriction: 'Topic Restriction',
  competitor_mention: 'Competitor Mention',
  custom: 'Custom Rule',
};


/**
 * Get iconSlug for Guardrail node based on provider
 */
function getGuardrailIconSlug(data: BuilderNodeData): string | undefined {
  const provider = data.provider || getEffectiveDefaultProvider();
  return getProviderIconSlug(provider);
}

export function GuardrailNode({ data, selected, id }: NodeProps<BuilderNodeData>) {
  // Handle sides follow the canvas reading direction. Safe variant: nodes also
  // render on provider-less surfaces (marketplace preview, snapshots).
  const { direction: layoutDirection } = useWorkflowLayoutDirectionSafe();
  const targetHandle = getTargetHandleGeometry(layoutDirection);
  const branchOut = getBranchHandleGeometry(layoutDirection, true);

  const visuals = getNodeVisual('guardrail');
  const rules: GuardrailRule[] =
    (data.guardrailRules as GuardrailRule[] | undefined) ?? createDefaultGuardrailRules(data.id);
  const { targetRef: nodeRef, isVisible: showActions, show } = useHoverVisibility<HTMLDivElement>();
  const { isRunMode, viewingEpoch } = useWorkflowMode();

  // Get node class to determine family
  const nodeClass = React.useMemo(() => findNodeClassById(data.id || ''), [data.id]);
  const nodeFamily = nodeClass?.family;

  // Step-by-step execution status
  const executionStatus = useNodeExecutionStatus(id, { label: data.label, kind: 'guardrail' });

  // Use centralized validation context for error state
  const { hasNodeErrors: checkNodeErrors } = useValidation();
  const hasError = checkNodeErrors(id);

  // Determine effective status
  const effectiveStatus = React.useMemo((): DerivedNodeStatus | undefined => {
    if (viewingEpoch != null) return data.status;
    if (executionStatus.isStepByStepMode) {
      if (executionStatus.isRunning) return 'running';
      if (executionStatus.isFailed) return 'failed';
      if (executionStatus.isSkipped) return 'skipped';
      if (executionStatus.isCompleted || executionStatus.isEvaluated) return 'completed';
      if (executionStatus.isReady) return 'ready';
      if (data.status && data.status !== 'pending') {
        return data.status;
      }
      return 'pending';
    }
    return data.status;
  }, [viewingEpoch, executionStatus, data.status]);

  // Get border color based on status
  // Always use status color for border
  const statusBorderColor = getStatusBorderColor(effectiveStatus, hasError, isRunMode || viewingEpoch != null, data.statusCounts);
  const borderColor = statusBorderColor;
  const isSkipped = !executionStatus.isStepByStepMode && effectiveStatus === 'skipped';

  // Get the icon slug for the current provider
  const iconSlug = getGuardrailIconSlug(data);

  return (
    <div
      ref={nodeRef}
      className={clsx(
        'group relative rounded-[28px] bg-white/95 dark:bg-gray-800/95 px-5 py-4',
        'backdrop-blur focus-visible:outline focus-visible:outline-2 focus-visible:outline-[var(--accent-primary)] focus-visible:outline-offset-2',
        'border-2 transition-colors',
        isSkipped && !selected && 'opacity-50 focus-visible:opacity-100',
        isSkipped && selected && 'opacity-100',
      )}
      style={{
        borderColor,
        borderStyle: 'solid',
        position: 'relative',
        boxShadow: selected ? '0 0 0 2px var(--accent-primary)' : 'none',
      }}
      tabIndex={0}
    >
      {/* Shimmer scan effect for running state - show in all modes */}
      {effectiveStatus === 'running' && (
        <div
          className="absolute inset-0 pointer-events-none rounded-[26px]"
          style={{
            background: 'linear-gradient(90deg, transparent 0%, rgba(59, 130, 246, 0.15) 50%, transparent 100%)',
            backgroundSize: '200% 100%',
            animation: 'shimmer-scan 2.5s ease-in-out infinite',
          }}
        />
      )}

      {/* Header with provider icon */}
      <NodeHeader
        visuals={visuals}
        label={data.label}
        iconSlug={iconSlug}
        nodeId={id}
        nodeKind="reasoning"
      
        nodeFamily={nodeFamily}
      />

      {/* Output branches: Pass and Fail */}
      <div className={`mt-4 ${getBranchRowFlow(layoutDirection)} text-[11px]`} style={
          layoutDirection === 'vertical'
            ? { paddingRight: effectiveStatus && effectiveStatus !== 'pending' ? '10px' : '0' }
            : { paddingBottom: effectiveStatus && effectiveStatus !== 'pending' ? '10px' : '0' }
        }>
        {/* Pass output */}
        <div className="relative rounded-2xl border border-theme px-3 py-2">
          <div className="flex items-center gap-2">
            <span className="flex items-center justify-center w-5 h-5 rounded bg-green-100 dark:bg-green-900/30">
              <CheckCircle className="h-3 w-3 text-green-600 dark:text-green-400" />
            </span>
            <span className="text-slate-700 dark:text-slate-300 font-medium">Pass</span>
          </div>
          <Handle
            type="source"
            id="pass"
            position={branchOut.position}
            className="!h-3 !w-3 !rounded-full !border-2 !border-[var(--bg-primary)] nodrag nopan"
            style={{
              ...branchOut.style,
              backgroundColor: 'var(--border-color)',
              opacity: isRunMode ? 0 : 1,
              pointerEvents: isRunMode ? 'none' : 'auto'
            }}
          />
        </div>

        {/* Fail output */}
        <div className="relative rounded-2xl border border-theme px-3 py-2">
          <div className="flex items-center gap-2">
            <span className="flex items-center justify-center w-5 h-5 rounded bg-red-100 dark:bg-red-900/30">
              <XCircle className="h-3 w-3 text-red-600 dark:text-red-400" />
            </span>
            <span className="text-slate-700 dark:text-slate-300 font-medium">Fail</span>
          </div>
          <Handle
            type="source"
            id="fail"
            position={branchOut.position}
            className="!h-3 !w-3 !rounded-full !border-2 !border-[var(--bg-primary)] nodrag nopan"
            style={{
              ...branchOut.style,
              backgroundColor: 'var(--border-color)',
              opacity: isRunMode ? 0 : 1,
              pointerEvents: isRunMode ? 'none' : 'auto'
            }}
          />
        </div>
      </div>

      {/* Status badge */}
      {effectiveStatus && effectiveStatus !== 'pending' && (
        <div className="absolute bottom-2 right-2 z-10">
          <NodeStatusBadge status={effectiveStatus} statusCounts={data.statusCounts} />
        </div>
      )}

      {/* Step-by-step play button */}
      {executionStatus.isStepByStepMode && (
        <NodeBottomBar
          hover={{ isVisible: showActions, onHover: show }}
          borderColor={borderColor}
          isRunning={effectiveStatus === 'running'}
          playButton={{
            nodeId: id,
            variant: 'play',
            isAutoMode: false,
            isTriggerNode: false,
            stepByStepStatus: executionStatus,
          }}
        />
      )}

      <NodeActionButtons
        isVisible={showActions}
        onDelete={data.onDeleteNode ? () => data.onDeleteNode?.(data.id) : undefined}
        onDuplicate={data.onDuplicateNode ? () => data.onDuplicateNode?.(data.id) : undefined}
        onHover={show}
      />

      {/* Input handle on the left */}
      <Handle
        type="target"
        position={targetHandle.position}
        className="!h-3 !w-3 !rounded-full !border-2 !border-[var(--bg-primary)] nodrag nopan"
        style={{
          ...targetHandle.style,
          backgroundColor: 'var(--border-color)',
          opacity: isRunMode ? 0 : 1,
          pointerEvents: isRunMode ? 'none' : 'auto'
        }}
      />

    </div>
  );
}
