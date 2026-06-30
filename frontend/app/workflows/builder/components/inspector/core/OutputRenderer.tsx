/**
 * OutputRenderer - Central component for rendering node outputs.
 *
 * This component:
 * 1. Detects the node type
 * 2. Extracts data using the registry's extractData function
 * 3. Renders loading/error/empty states
 * 4. Renders custom content or falls back to LazyStructureTree
 * 5. Always includes NavigationButtons
 *
 * 80% of nodes use LazyStructureTree by default.
 * Only nodes with special display (Decision, Loop, etc.) define custom renderContent.
 */

'use client';

import React from 'react';
import { Node } from 'reactflow';
import { BuilderNodeData } from '../../../types';
import { detectNodeType, ExecutionData } from './types';
import { outputRegistry, defaultOutputDefinition } from '../registry/output-registry';
import { SimpleDataTree } from '../shared/SimpleDataTree';
import { OutputSkeleton } from '../shared/OutputSkeleton';
import { OutputEmpty } from '../shared/OutputEmpty';
import { OutputError } from '../shared/OutputError';
import { OutputContainer } from '../shared/OutputContainer';
import { NavigationButtons } from '../shared/NavigationButtons';

interface OutputRendererProps {
  node: Node<BuilderNodeData>;
  executionData: ExecutionData;
  isLoading?: boolean;
  error?: string;
  nextNodes?: Node<BuilderNodeData>[];
  onNavigateToNode?: (nodeId: string) => void;
}

export function OutputRenderer({
  node,
  executionData,
  isLoading = false,
  error,
  nextNodes = [],
  onNavigateToNode,
}: OutputRendererProps) {
  // 1. Detect node type
  const nodeType = detectNodeType(node);

  // 2. Get output definition from registry (or default)
  const definition = outputRegistry[nodeType] ?? defaultOutputDefinition;

  // 3. Extract data
  const data = definition.extractData(node, executionData);

  // 4. Handle states
  if (isLoading) {
    return (
      <OutputContainer title={definition.title}>
        <OutputSkeleton />
      </OutputContainer>
    );
  }

  if (error) {
    return (
      <OutputContainer title={definition.title}>
        <OutputError error={error} />
        <NavigationButtons nodes={nextNodes} onNavigate={onNavigateToNode} />
      </OutputContainer>
    );
  }

  if (!data || (typeof data === 'object' && Object.keys(data).length === 0)) {
    return (
      <OutputContainer title={definition.title}>
        <OutputEmpty message={definition.emptyMessage} />
        <NavigationButtons nodes={nextNodes} onNavigate={onNavigateToNode} />
      </OutputContainer>
    );
  }

  // 5. Render content (custom or default SimpleDataTree)
  const content = definition.renderContent
    ? definition.renderContent(data, node)
    : <SimpleDataTree data={data} />;

  return (
    <OutputContainer title={definition.title}>
      {content}
      <NavigationButtons nodes={nextNodes} onNavigate={onNavigateToNode} />
    </OutputContainer>
  );
}

export default OutputRenderer;
