'use client';

/**
 * MergeNodeOutput - Merge node output renderer
 *
 * Shows merged data structure from each input source
 */

import * as React from 'react';
import clsx from 'clsx';
import type { Node, Edge } from 'reactflow';
import type { BuilderNodeData } from '../../../types';
import { getFieldTypeColor } from '../../../types';
import { NavigationButtons } from './NavigationButtons';
import { SourceDataSourceInspector } from '../SourceDataSourceInspector';
import { SourceNodeInspector } from '../SourceNodeInspector';
import { EmptyState } from '../../shared/EmptyState';

interface MergeNodeOutputProps {
  mergeInputSources: Array<{ input: any; sourceNode: Node<BuilderNodeData> | undefined }>;
  nextNodes: Node<BuilderNodeData>[];
  allNodes: Node<BuilderNodeData>[];
  edges: Edge[];
  onNavigateToNode?: (nodeId: string, loopId?: string) => void;
  checkNodeError: (node: Node<BuilderNodeData>) => boolean;
  getLoopIdFromNode: (node: Node<BuilderNodeData>) => string | undefined;
  ArrowIcon: React.ComponentType<{ node: Node<BuilderNodeData> }>;
  isRunMode: boolean;
}

export function MergeNodeOutput({
  mergeInputSources,
  nextNodes,
  allNodes,
  edges,
  onNavigateToNode,
  checkNodeError,
  getLoopIdFromNode,
  ArrowIcon,
  isRunMode,
}: MergeNodeOutputProps) {
  return (
    <div className="w-full space-y-2">
      <NavigationButtons
        nextNodes={nextNodes}
        onNavigateToNode={onNavigateToNode}
        checkNodeError={checkNodeError}
        getLoopIdFromNode={getLoopIdFromNode}
        ArrowIcon={ArrowIcon}
      />

      {/* Merge output - merged_data parent with nested sources */}
      <div className="space-y-1">
        {/* merged_data header */}
        <div className="flex items-center justify-between text-sm font-normal text-[var(--text-primary)] w-full px-1 py-1">
          <span className="text-sm font-medium">merged_data</span>
          <span className={clsx("text-xs px-1.5 py-0.5 rounded uppercase tracking-wider font-mono", getFieldTypeColor('array'))}>array</span>
        </div>
        {/* Nested sources */}
        <div className="pl-4 border-l-2 border-slate-200 dark:border-slate-700 ml-2">
          {mergeInputSources.length > 0 ? (
            <div className="space-y-2">
              {mergeInputSources.map(({ input, sourceNode }) => {
                if (!sourceNode) {
                  return (
                    <div key={input.id} className="text-sm text-slate-400 dark:text-slate-500 italic px-1">
                      {input.label}: not connected
                    </div>
                  );
                }

                const isSourceDataSource = sourceNode.data?.id?.startsWith('tables-trigger-') ||
                  !!(sourceNode.data as any)?.dataSourceData;

                if (isSourceDataSource) {
                  return (
                    <SourceDataSourceInspector
                      key={input.id}
                      node={sourceNode}
                      onNavigateToNode={onNavigateToNode}
                      isRunMode={isRunMode}
                    />
                  );
                }

                return (
                  <SourceNodeInspector
                    key={input.id}
                    node={sourceNode}
                    allNodes={allNodes}
                    edges={edges}
                    onNavigateToNode={onNavigateToNode}
                    isDraggable={true}
                    isRunMode={isRunMode}
                  />
                );
              })}
            </div>
          ) : (
            <EmptyState message="No inputs connected" />
          )}
        </div>
      </div>
    </div>
  );
}
