'use client';

/**
 * TransformNodeOutput - Transform node output renderer
 *
 * Shows transform mappings as output fields
 */

import * as React from 'react';
import type { Node } from 'reactflow';
import type { BuilderNodeData } from '../../../types';
import { NavigationButtons } from './NavigationButtons';

interface TransformNodeOutputProps {
  transformMappings: Array<{ id: string; label: string; expression: string }>;
  nextNodes: Node<BuilderNodeData>[];
  onNavigateToNode?: (nodeId: string, loopId?: string) => void;
  checkNodeError: (node: Node<BuilderNodeData>) => boolean;
  getLoopIdFromNode: (node: Node<BuilderNodeData>) => string | undefined;
  ArrowIcon: React.ComponentType<{ node: Node<BuilderNodeData> }>;
}

export function TransformNodeOutput({
  transformMappings,
  nextNodes,
  onNavigateToNode,
  checkNodeError,
  getLoopIdFromNode,
  ArrowIcon,
}: TransformNodeOutputProps) {
  return (
    <div className="w-full space-y-2">
      <NavigationButtons
        nextNodes={nextNodes}
        onNavigateToNode={onNavigateToNode}
        checkNodeError={checkNodeError}
        getLoopIdFromNode={getLoopIdFromNode}
        ArrowIcon={ArrowIcon}
      />

      {/* Transform output fields */}
      <div className="space-y-1">
        {transformMappings.map((mapping) => (
          <div
            key={mapping.id}
            className="flex items-center justify-between text-sm font-normal text-[var(--text-primary)] w-full px-1 py-1"
          >
            <span className="truncate flex-1 min-w-0 text-sm" title={mapping.label}>
              {mapping.label}
            </span>
            <span className="text-xs px-1.5 py-0.5 rounded uppercase tracking-wider font-mono bg-gray-200 dark:bg-gray-700 text-gray-600 dark:text-gray-400">
              any
            </span>
          </div>
        ))}
      </div>
    </div>
  );
}
