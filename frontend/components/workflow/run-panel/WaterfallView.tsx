'use client';

import type { Node } from 'reactflow';
import { Tooltip, TooltipContent, TooltipTrigger } from '@/components/ui/tooltip';
import { getIconSlug, NodeIcon, nodeIconRadiusClass } from '@/app/workflows/builder/components/nodes/shared';
import { findNodeClassById } from '@/app/workflows/builder/nodes/nodeClasses';
import type { BuilderNodeData } from '@/app/workflows/builder/types';
import { StepRowActions } from '@/components/workflow/StepRowActions';
import { StepTooltipContent } from './StepTooltipContent';
import { deriveEffectiveStatus, formatCompactDuration, getBarColor, stepDisplayDurationMs, type StepEntry } from './runFormatting';

interface WaterfallViewProps {
  steps: StepEntry[];
  findNodeForStep: (alias: string) => Node<BuilderNodeData> | undefined;
  /** Whether viewing all epochs (cumulative) vs a specific epoch */
  showCumulative?: boolean;
  /** Contextual-action props forwarded to StepRowActions (same as list view). */
  workflowId?: string;
  isStepByStep?: boolean;
  isRunActive?: boolean;
}

/** Duration-gauge view of the run steps (the "waterfall" toggle). */
export function WaterfallView({ steps, findNodeForStep, showCumulative, workflowId, isStepByStep, isRunActive }: WaterfallViewProps) {
  const entries = steps
    .map(s => {
      // null = no honest figure (skipped, never timed): the row shows no gauge and no "<1s".
      const durationMs = stepDisplayDurationMs(s, !!showCumulative);
      const matchedNode = findNodeForStep(s.alias);
      const matchedData = matchedNode?.data;
      const nodeClass = matchedData ? findNodeClassById(matchedData.id || '') : null;
      const label = matchedData?.label || s.alias.replace(/^(mcp|core|agent|trigger|table|interface):/, '');
      // Derive effective status from statusCounts when available (matches list view behavior)
      const effectiveStatus = deriveEffectiveStatus(s.status, s.statusCounts);
      return { alias: s.alias, label, status: effectiveStatus, durationMs, matchedData, matchedNode, nodeClass, statusCounts: s.statusCounts };
    });

  if (entries.length === 0) return null;

  const maxDuration = Math.max(...entries.map(e => e.durationMs ?? 0), 1);

  return (
    <div className="py-0.5">
      {entries.map(entry => {
        const barPct = entry.durationMs == null ? 0 : Math.max(5, (entry.durationMs / maxDuration) * 100);
        const sourceStep = steps.find(s => s.alias === entry.alias)!;
        return (
          <Tooltip key={entry.alias} delayDuration={150}>
          <TooltipTrigger asChild>
          <div
            className="flex items-center gap-1.5 px-3 py-1 cursor-pointer hover:bg-gray-100/60 dark:hover:bg-gray-700/40 transition-colors"
            onClick={(e) => {
              e.stopPropagation();
              window.dispatchEvent(new CustomEvent('workflowFocusNode', {
                detail: { stepAlias: entry.alias },
              }));
            }}
          >
            {/* Icon - w-6 to match epoch number column */}
            <div className="w-6 shrink-0 flex items-center justify-center">
              {entry.matchedData ? (
                <NodeIcon
                  iconSlug={getIconSlug(entry.matchedData)}
                  nodeId={entry.matchedData.id || ''}
                  nodeKind={entry.matchedData.kind}
                  nodeFamily={entry.nodeClass?.family}
                  avatarUrl={(entry.matchedData as any)?.agentAvatarUrl}
                  size="xs"
                />
              ) : (
                <div className={`h-4 w-4 ${nodeIconRadiusClass('xs')} bg-gray-100 dark:bg-gray-700`} />
              )}
            </div>
            {/* Label - w-[80px] to match epoch label column */}
            <span className="w-[96px] min-w-[96px] text-sm font-medium text-gray-900 dark:text-gray-100 truncate shrink-0">{entry.label}</span>
            {/* Status counts - compact indicators like list view */}
            {entry.statusCounts && (
              <div className="flex items-center gap-0.5 shrink-0">
                {(entry.statusCounts.completed ?? 0) > 0 && (
                  <span className="text-xs text-emerald-600 dark:text-emerald-400">✓{entry.statusCounts.completed}</span>
                )}
                {(entry.statusCounts.failed ?? 0) > 0 && (
                  <span className="text-xs text-red-600 dark:text-red-400">✗{entry.statusCounts.failed}</span>
                )}
                {(entry.statusCounts.skipped ?? 0) > 0 && (
                  <span className="text-xs text-gray-500 dark:text-gray-400">⊘{entry.statusCounts.skipped}</span>
                )}
              </div>
            )}
            {/* Gauge */}
            <div className="flex-1 h-[3px] rounded-full bg-gray-100 dark:bg-white/[0.06] overflow-hidden min-w-0">
              <div
                className={`h-full rounded-full ${getBarColor(entry.status)}`}
                style={{ width: `${barPct}%` }}
              />
            </div>
            {/* Duration */}
            <span className="min-w-[40px] text-right text-xs tabular-nums text-gray-500 dark:text-gray-400 shrink-0">
              {entry.durationMs != null ? formatCompactDuration(entry.durationMs) : ''}
            </span>
          </div>
          </TooltipTrigger>
          <TooltipContent
            side="left"
            sideOffset={8}
            align="center"
            className="px-3 py-2.5"
          >
            <StepTooltipContent
              step={sourceStep}
              label={entry.label}
              showCumulative={!!showCumulative}
            />
            {entry.matchedNode && (
              <StepRowActions
                step={{ alias: entry.alias }}
                matchedNode={entry.matchedNode}
                workflowId={workflowId}
                isStepByStep={!!isStepByStep}
                isRunActive={!!isRunActive}
              />
            )}
          </TooltipContent>
          </Tooltip>
        );
      })}
    </div>
  );
}
