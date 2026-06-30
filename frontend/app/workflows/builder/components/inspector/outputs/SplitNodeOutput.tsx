'use client';

/**
 * SplitNodeOutput - Split node output renderer
 *
 * Shows iteration variables + child nodes outputs
 */

import * as React from 'react';
import clsx from 'clsx';
import type { Node, Edge } from 'reactflow';
import type { BuilderNodeData } from '../../../types';
import { getFieldTypeColor } from '../../../types';
import { normalizeLabel } from '../../../utils/labelNormalizer';
import { NavigationButtons } from './NavigationButtons';
import { SourceDataSourceInspector } from '../SourceDataSourceInspector';
import { SourceNodeInspector } from '../SourceNodeInspector';

interface SplitNodeOutputProps {
  currentNode?: Node<BuilderNodeData> | null;
  splitChildNodes: Node<BuilderNodeData>[];
  nextNodes: Node<BuilderNodeData>[];
  allNodes: Node<BuilderNodeData>[];
  edges: Edge[];
  onNavigateToNode?: (nodeId: string, loopId?: string) => void;
  checkNodeError: (node: Node<BuilderNodeData>) => boolean;
  getLoopIdFromNode: (node: Node<BuilderNodeData>) => string | undefined;
  ArrowIcon: React.ComponentType<{ node: Node<BuilderNodeData> }>;
  isRunMode: boolean;
}

export function SplitNodeOutput({
  currentNode,
  splitChildNodes,
  nextNodes,
  allNodes,
  edges,
  onNavigateToNode,
  checkNodeError,
  getLoopIdFromNode,
  ArrowIcon,
  isRunMode,
}: SplitNodeOutputProps) {
  const draggableFieldProps = (fieldName: string) => ({
    draggable: true,
    onDragStart: (e: React.DragEvent) => {
      const normalizedLabel = normalizeLabel(currentNode?.data?.label || '');
      const dragValue = `{{core:${normalizedLabel}.output.${fieldName}}}`;
      e.dataTransfer.setData('text/plain', dragValue);
      e.dataTransfer.effectAllowed = 'copy';
    },
    className: clsx(
      "flex items-center justify-between text-sm font-normal text-[var(--text-primary)] w-full px-1 py-1 rounded",
      "cursor-grab hover:bg-slate-50 dark:hover:bg-slate-800"
    ),
  });

  return (
    <div className="w-full space-y-2">
      <NavigationButtons
        nextNodes={nextNodes}
        onNavigateToNode={onNavigateToNode}
        checkNodeError={checkNodeError}
        getLoopIdFromNode={getLoopIdFromNode}
        ArrowIcon={ArrowIcon}
      />

      {/* Split output fields */}
      <div className="space-y-1">
        <div {...draggableFieldProps('current_item')} title="Runtime context variable available in body nodes only">
          <span className="text-sm">current_item</span>
          <span className={clsx("text-xs px-1.5 py-0.5 rounded uppercase tracking-wider font-mono", getFieldTypeColor('object'))}>object</span>
        </div>
        <div {...draggableFieldProps('current_index')} title="Runtime context variable available in body nodes only">
          <span className="text-sm">current_index</span>
          <span className={clsx("text-xs px-1.5 py-0.5 rounded uppercase tracking-wider font-mono", getFieldTypeColor('number'))}>number</span>
        </div>
        <div {...draggableFieldProps('items')}>
          <span className="text-sm">items</span>
          <span className={clsx("text-xs px-1.5 py-0.5 rounded uppercase tracking-wider font-mono", getFieldTypeColor('text'))}>array</span>
        </div>
        <div {...draggableFieldProps('item_count')}>
          <span className="text-sm">item_count</span>
          <span className={clsx("text-xs px-1.5 py-0.5 rounded uppercase tracking-wider font-mono", getFieldTypeColor('number'))}>number</span>
        </div>
        <div {...draggableFieldProps('split_id')}>
          <span className="text-sm">split_id</span>
          <span className={clsx("text-xs px-1.5 py-0.5 rounded uppercase tracking-wider font-mono", getFieldTypeColor('text'))}>text</span>
        </div>
        <div {...draggableFieldProps('spawn_reason')}>
          <span className="text-sm">spawn_reason</span>
          <span className={clsx("text-xs px-1.5 py-0.5 rounded uppercase tracking-wider font-mono", getFieldTypeColor('text'))}>text</span>
        </div>
        <div {...draggableFieldProps('terminated')}>
          <span className="text-sm">terminated</span>
          <span className={clsx("text-xs px-1.5 py-0.5 rounded uppercase tracking-wider font-mono", getFieldTypeColor('boolean'))}>boolean</span>
        </div>
      </div>

      {/* Split body outputs */}
      {splitChildNodes.length > 0 && (
        <div className="space-y-2 mt-3">
          <div className="text-xs font-semibold uppercase tracking-[0.2em] text-slate-500 px-1 mb-2">Body Outputs</div>
          {splitChildNodes.map((childNode: Node<BuilderNodeData>) => {
            const childDataSourceData = (childNode.data as any)?.dataSourceData;
            const isChildDataSource = childNode.data?.id?.startsWith('tables-trigger-') || !!childDataSourceData;

            if (isChildDataSource) {
              return (
                <SourceDataSourceInspector
                  key={childNode.id}
                  node={childNode}
                  onNavigateToNode={(nodeId) => onNavigateToNode?.(nodeId, currentNode?.id)}
                  isRunMode={isRunMode}
                />
              );
            }

            return (
              <SourceNodeInspector
                key={childNode.id}
                node={childNode}
                allNodes={allNodes}
                edges={edges}
                onNavigateToNode={(nodeId) => onNavigateToNode?.(nodeId, currentNode?.id)}
                isDraggable={true}
                isRunMode={isRunMode}
              />
            );
          })}
        </div>
      )}
    </div>
  );
}
