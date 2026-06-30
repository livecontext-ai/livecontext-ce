'use client';

/**
 * NavigationButtons - Reusable next node navigation buttons
 *
 * Features:
 * - Single or multiple next nodes
 * - Error state highlighting
 * - Loop ID handling
 */

import * as React from 'react';
import clsx from 'clsx';
import type { Node } from 'reactflow';
import type { BuilderNodeData } from '../../../types';
import { NodeIcon, getIconSlug } from '../../nodes/shared';

interface NavigationButtonsProps {
  nextNodes: Node<BuilderNodeData>[];
  onNavigateToNode?: (nodeId: string, loopId?: string) => void;
  checkNodeError: (node: Node<BuilderNodeData>) => boolean;
  getLoopIdFromNode: (node: Node<BuilderNodeData>) => string | undefined;
  ArrowIcon: React.ComponentType<{ node: Node<BuilderNodeData> }>;
}

export function NavigationButtons({
  nextNodes,
  onNavigateToNode,
  checkNodeError,
  getLoopIdFromNode,
  ArrowIcon,
}: NavigationButtonsProps) {
  if (nextNodes.length === 0) return null;

  if (nextNodes.length === 1) {
    const node = nextNodes[0];
    const hasError = checkNodeError(node);
    return (
      <div className="text-sm text-[var(--text-primary)] mb-1 flex justify-end">
        <button
          onClick={(e) => {
            e.stopPropagation();
            e.preventDefault();
            const loopId = getLoopIdFromNode(node);
            onNavigateToNode?.(node.id, loopId);
          }}
          className={clsx(
            "inline-flex items-center gap-1.5 px-2 py-1.5 rounded-lg text-sm transition-colors",
            hasError
              ? "text-red-600 dark:text-red-400 hover:text-red-700 dark:hover:text-red-300 hover:bg-red-50 dark:hover:bg-red-900/20"
              : "text-slate-600 dark:text-slate-400 hover:text-slate-900 dark:hover:text-slate-200 hover:bg-slate-100 dark:hover:bg-slate-800"
          )}
          title="Go to next node"
        >
          <NodeIcon nodeId={node.data?.id || node.id} iconSlug={getIconSlug(node.data)} nodeKind={node.data?.kind as any} avatarUrl={(node.data as any)?.agentAvatarUrl} size="xs" />
          <span className="truncate max-w-[220px] text-sm">{node.data?.label || node.id}</span>
          <ArrowIcon node={node} />
        </button>
      </div>
    );
  }

  return (
    <div className="flex flex-col gap-2 mb-1 items-end">
      {nextNodes.map(n => (
        <button
          key={n.id}
          onClick={(e) => {
            e.stopPropagation();
            e.preventDefault();
            const loopId = getLoopIdFromNode(n);
            onNavigateToNode?.(n.id, loopId);
          }}
          className={clsx(
            "inline-flex items-center gap-1.5 px-2 py-1.5 rounded-lg text-sm transition-colors",
            checkNodeError(n)
              ? "text-red-600 dark:text-red-400 hover:text-red-700 dark:hover:text-red-300 hover:bg-red-50 dark:hover:bg-red-900/20"
              : "text-slate-600 dark:text-slate-400 hover:text-slate-900 dark:hover:text-slate-200 hover:bg-slate-100 dark:hover:bg-slate-800"
          )}
          title="Go to next node"
        >
          <NodeIcon nodeId={n.data?.id || n.id} iconSlug={getIconSlug(n.data)} nodeKind={n.data?.kind as any} avatarUrl={(n.data as any)?.agentAvatarUrl} size="xs" />
          <span className="truncate max-w-[220px] text-sm">{n.data?.label || n.id}</span>
          <ArrowIcon node={n} />
        </button>
      ))}
    </div>
  );
}
