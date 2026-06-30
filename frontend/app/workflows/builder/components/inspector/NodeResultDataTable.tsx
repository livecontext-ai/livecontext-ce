'use client';

import * as React from 'react';
import type { Node } from 'reactflow';
import { WorkflowStepTable } from '@/components/workflow/WorkflowStepTable';
import { Breadcrumb } from '@/components/ui/breadcrumb';
import { extractStepAliasFromNode } from '../../services/idMatcherUtils';
import type { BuilderNodeData } from '../../types';

interface NodeResultDataTableProps {
  node: Node<BuilderNodeData> | null;
  runId?: string;
  workflowId?: string;
  onBreadcrumbChange?: (items: Array<{ label: string; onClick?: () => void }>) => void;
}

/**
 * Component to display step execution data for a node in the inspector panel.
 * Uses the DataTable (via WorkflowStepTable) with backend-driven columns, search, filters,
 * and breadcrumb navigation for nested JSON paths.
 */
export function NodeResultDataTable({
  node,
  runId,
  workflowId,
  onBreadcrumbChange,
}: NodeResultDataTableProps) {
  const [jsonPath, setJsonPath] = React.useState('');

  const stepAlias = React.useMemo(() => {
    return node ? extractStepAliasFromNode(node) || undefined : undefined;
  }, [node]);

  // Reset jsonPath when node changes
  React.useEffect(() => {
    setJsonPath('');
  }, [node?.id]);

  const handleNavigate = React.useCallback((newPath: string) => {
    setJsonPath(newPath);
  }, []);

  // Build breadcrumb items
  const breadcrumbItems = React.useMemo(() => {
    const items: Array<{ label: string; onClick?: () => void }> = [];

    items.push({
      label: stepAlias || node?.data?.label || 'Step',
      onClick: jsonPath ? () => setJsonPath('') : undefined,
    });

    if (jsonPath) {
      const segments = jsonPath.split('.').filter(s => s.length > 0);
      segments.forEach((segment, index) => {
        const pathUpToSegment = segments.slice(0, index + 1).join('.');
        const isLast = index === segments.length - 1;
        items.push({
          label: segment,
          onClick: !isLast ? () => setJsonPath(pathUpToSegment) : undefined,
        });
      });
    }

    return items;
  }, [stepAlias, node?.data?.label, jsonPath]);

  // Notify parent of breadcrumb changes
  React.useEffect(() => {
    if (onBreadcrumbChange) {
      onBreadcrumbChange(breadcrumbItems);
    }
  }, [breadcrumbItems, onBreadcrumbChange]);

  if (!node || !runId || !workflowId) {
    return (
      <div className="text-sm text-slate-400 p-4 text-center">
        {!node ? 'Select a node to view its step logs' : 'Run ID or Workflow ID is missing'}
      </div>
    );
  }

  return (
    <div className="p-3 h-full flex flex-col gap-2">
      {jsonPath && (
        <Breadcrumb items={breadcrumbItems} />
      )}
      <div className="flex-1 min-h-0">
        <WorkflowStepTable
          key={`inspector-${stepAlias}`}
          workflowId={workflowId}
          runId={runId}
          stepAlias={stepAlias || ''}
          jsonPath={jsonPath}
          onNavigate={handleNavigate}
          showIdColumn={false}
        />
      </div>
    </div>
  );
}
