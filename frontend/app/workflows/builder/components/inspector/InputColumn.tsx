import * as React from 'react';
import { useTranslations } from 'next-intl';
import { MousePointerClick, MessageSquare, Webhook, Table, Clock, FileText } from 'lucide-react';
import type { Node, Edge } from 'reactflow';
import type { BuilderNodeData } from '../../types';
import { InspectorColumn } from './InspectorColumn';
import { DataSourceColumnsInspector } from './DataSourceColumnsInspector';
import { GlobalVariablesInspector } from './GlobalVariablesInspector';
import { AncestorNodesSection } from './AncestorNodesSection';
import { coreKey } from '../../utils/labelNormalizer';
import { useWorkflowInputsOutputs } from '../../hooks/useWorkflowOutputs';
import { Workflow } from 'lucide-react';
import { useNodeTypeDetection } from '../../hooks/useNodeTypeDetection';
import { useWorkflowMode } from '@/contexts/WorkflowModeContext';
import { nodeRegistry } from '../../registry/nodeRegistry';
import { ParentNodesDataPreview } from './outputs/ParentNodesDataPreview';
import { useStepByStep } from '../../contexts/StepByStepContext';
import { useApprovalReviewTarget } from '../../services/approvalReviewStore';
import { useWorkflowVariables } from '@/lib/hooks/useWorkflowVariables';

interface InputColumnProps {
  node: Node<BuilderNodeData> | null;
  allNodes: Node<BuilderNodeData>[];
  edges: Edge[];
  onSelectNode?: (nodeId: string, loopId?: string) => void;
  selectedLoopChild?: { loopId: string; childId: string } | null;
  isRunMode?: boolean;
  embedded?: boolean;
  showExecutionData?: boolean; // Toggle from parent header
  workflowId?: string;
  runId?: string;
}

export const InputColumn = ({
  node,
  allNodes,
  edges,
  onSelectNode,
  selectedLoopChild,
  isRunMode = false,
  embedded = false,
  showExecutionData = true,
  workflowId: propWorkflowId,
  runId: propRunId,
}: InputColumnProps) => {
  const t = useTranslations('workflowBuilder.inspector');
  const { isRunMode: contextIsRunMode, isPreviewOnly, runId: contextRunId } = useWorkflowMode();
  const effectiveRunId = propRunId || contextRunId;
  // In preview-only mode (marketplace), disable all drag-and-drop
  const canDrag = !isPreviewOnly;
  const stepByStep = useStepByStep();
  const isStepByStepMode = stepByStep?.isStepByStepMode ?? false;

  // Workflow variables ($vars) of the active scope - draggable like the other
  // global variables below. Disabled in preview-only mode (marketplace) where
  // the viewer may not even be the workflow owner.
  const { variables: workflowVariables } = useWorkflowVariables(!isPreviewOnly);

  // When an approval review is being driven for THIS node, the reviewer needs
  // the upstream context of the exact item under review: auto-expand the
  // collapsed Grandparents/Great-grandparents groups (distance >= 2) so their
  // navigators mount and land on the same (epoch, item) the review target sets.
  const reviewTarget = useApprovalReviewTarget();
  const ancestorReviewTarget =
    reviewTarget && node && reviewTarget.rfNodeId === node.id ? reviewTarget : null;

  // Check if we're editing a loop child
  const isLoopChild = !!selectedLoopChild && node?.id === selectedLoopChild.childId;
  const loopNode = isLoopChild ? allNodes.find(n => n.id === selectedLoopChild.loopId) : null;

  // Check if this is a tables trigger node with a datasource selected
  // CRUD nodes (create-row, read-row, etc.) also have dataSourceData but are NOT triggers
  const dataSourceData = (node?.data as any)?.dataSourceData;
  const isCrudNode = node ? nodeRegistry.isCrudNode(node) : false;
  const isTablesTrigger = !isCrudNode && (node?.data?.id?.startsWith('tables-trigger-') || !!dataSourceData);
  const dataSourceId = dataSourceData?.dataSourceId;

  // Check if this is a workflows trigger (not a sub-workflow action node)
  const workflowData = (node?.data as any)?.workflowData;
  const isSubWorkflow = node ? nodeRegistry.isSubWorkflowNode(node) : false;
  const isWorkflowsTrigger = !isSubWorkflow && (node?.data?.id?.startsWith('workflows-trigger-') || (!!workflowData && (node?.data as any)?.kind === 'trigger'));
  const workflowId = workflowData?.workflowId;
  const { data: workflowIO, isLoading: isLoadingWorkflowIO } = useWorkflowInputsOutputs(
    isWorkflowsTrigger && workflowId ? workflowId : null
  );

  // Check if this is a manual trigger
  const isManualTrigger = node?.data?.id === 'manual-trigger' ||
    node?.data?.id?.startsWith('manual-trigger-');

  // Check if this is a chat trigger
  const isChatTrigger = node?.data?.id === 'chat-trigger' ||
    node?.data?.id?.startsWith('chat-trigger-');

  // Check if this is a webhook trigger
  const isWebhookTrigger = node?.data?.id === 'webhook-trigger' ||
    node?.data?.id?.startsWith('webhook-trigger-');

  // Check if this is a schedule trigger
  const isScheduleTrigger = node?.data?.id === 'schedule-trigger' ||
    node?.data?.id?.startsWith('schedule-trigger-');

  // Check if this is a form trigger
  const isFormTrigger = node?.data?.id === 'form-trigger' ||
    node?.data?.id?.startsWith('form-trigger-');

  // Get trigger label for unified pattern: {{trigger:label.output.field}}
  const triggerLabel = node?.data?.label || (node?.data as any)?.name || 'default';

  // Determine node types for global variables
  const isWhileGroupNode = node ? nodeRegistry.isWhileGroupNode(node) : false;
  const isLoopNode = node ? nodeRegistry.isLoopNode(node) : false;
  const isSplitNode = node ? nodeRegistry.isSplitNode(node) : false;
  const isFindNode = node ? nodeRegistry.isFindNode(node) : false;

  // Use node type detection for branching nodes
  const nodeTypes = useNodeTypeDetection(node?.data || {} as BuilderNodeData, node);
  const { isDecisionNode, isSwitchNode, isForkNode } = nodeTypes;
  const isBranchingNode = isDecisionNode || isSwitchNode || isForkNode;

  // Check if this node is a trigger (no parent input data to show)
  const isTriggerNode = isManualTrigger || isChatTrigger || isWebhookTrigger || isScheduleTrigger || isFormTrigger || isTablesTrigger || isWorkflowsTrigger;

  // Get direct parent nodes for execution data display
  const directParentNodes = React.useMemo(() => {
    if (!node) return [];
    const parentIds = edges.filter(e => e.target === node.id).map(e => e.source);
    return allNodes.filter(n => parentIds.includes(n.id));
  }, [node, edges, allNodes]);

  // Can we show input execution data?
  const canShowInputExecutionData = contextIsRunMode && effectiveRunId && propWorkflowId && !isTriggerNode && directParentNodes.length > 0;

  // Check if we're editing a split child
  const isSplitChild = !!selectedLoopChild &&
    (() => { const loopNode = allNodes.find(n => n.id === selectedLoopChild.loopId); return loopNode ? nodeRegistry.isSplitNode(loopNode) : false; })() &&
    node?.id === selectedLoopChild.childId;
  const splitNode = isSplitChild ? allNodes.find(n => n.id === selectedLoopChild.loopId) : null;

  // FindNode is no longer split-like - no child context variables needed

  // Check if this node is inside a While loop body (DAG reachable from a While body handle)
  const parentWhileNode = React.useMemo(() => {
    if (!node || isWhileGroupNode) return null;
    for (const whileNode of allNodes) {
      if (!nodeRegistry.isWhileGroupNode(whileNode)) continue;
      // Find the body edge from this While node
      const bodyEdge = edges.find(
        (e) => e.source === whileNode.id && e.sourceHandle?.endsWith('-body'),
      );
      if (!bodyEdge) continue;
      // BFS forward from body target, stopping at While node
      const visited = new Set<string>();
      const queue = [bodyEdge.target];
      while (queue.length > 0) {
        const current = queue.shift()!;
        if (visited.has(current) || current === whileNode.id) continue;
        visited.add(current);
        if (current === node.id) return whileNode;
        for (const edge of edges) {
          if (edge.source === current && !visited.has(edge.target)) {
            queue.push(edge.target);
          }
        }
      }
    }
    return null;
  }, [node, isWhileGroupNode, allNodes, edges]);
  const isWhileBodyNode = parentWhileNode !== null;

  // Generate global variables based on node type
  const globalVariables = React.useMemo(() => {
    const variables: Array<{
      name: string;
      label: string;
      type: string;
      path: string;
      expressionToken?: boolean;
      properties?: Array<{ name: string; label: string; type: string; path: string }>;
    }> = [];

    // While / Loop: iteration counter - uses {{core:label.output.iteration}} format
    if (isWhileGroupNode || isWhileBodyNode || isLoopNode || isLoopChild) {
      const loopLabelSource = isWhileBodyNode && parentWhileNode ? parentWhileNode
        : isWhileGroupNode && node ? node
        : isLoopChild && loopNode ? loopNode
        : node;
      const loopLabel = loopLabelSource?.data?.label || loopLabelSource?.id || 'loop';
      const loopPrefix = coreKey(loopLabel) || `core:${loopLabel}`;

      variables.push({
        name: 'iteration',
        label: 'Iteration (i)',
        type: 'number',
        path: `{{${loopPrefix}.output.iteration}}`,
      });
      variables.push({
        name: 'maxIterations',
        label: 'Max Iterations',
        type: 'number',
        path: `{{${loopPrefix}.output.maxIterations}}`,
      });
    }

    // Split body context variables - uses {{core:label.output.current_item}} format
    // current_item and current_index are runtime context variables (per parallel branch)
    // items and item_count are persisted output fields
    // Available when editing a step inside a split body
    if (isSplitChild && splitNode) {
      const splitLabel = splitNode.data.label || splitNode.id;
      const splitPrefix = coreKey(splitLabel) || `core:${splitLabel}`;

      variables.push({
        name: 'current_item',
        label: 'Current Item',
        type: 'object',
        path: `{{${splitPrefix}.output.current_item}}`,
      });
      variables.push({
        name: 'current_index',
        label: 'Current Index',
        type: 'number',
        path: `{{${splitPrefix}.output.current_index}}`,
      });
      variables.push({
        name: 'items',
        label: 'Items List',
        type: 'array',
        path: `{{${splitPrefix}.output.items}}`,
      });
      variables.push({
        name: 'item_count',
        label: 'Item Count',
        type: 'number',
        path: `{{${splitPrefix}.output.item_count}}`,
      });
    }

    // Workflow variables ($vars) - org/personal reusable values, available in
    // every node regardless of type or position in the DAG. Rendered with the
    // SpEL token styling so they read as droppable expressions.
    for (const wfVar of workflowVariables) {
      variables.push({
        name: wfVar.name,
        label: `{{$vars.${wfVar.name}}}`,
        type: wfVar.type.toLowerCase(),
        path: `{{$vars.${wfVar.name}}}`,
        expressionToken: true,
      });
    }
    return variables;
  }, [isWhileGroupNode, isWhileBodyNode, parentWhileNode, isLoopNode, isLoopChild, loopNode, node, isSplitNode, isSplitChild, splitNode, workflowVariables]);

  // Render content (shared between embedded and non-embedded modes)
  const renderContent = () => {
    // If execution data mode is enabled and we have parent nodes, show their output data
    if (canShowInputExecutionData && showExecutionData) {
      return (
        <ParentNodesDataPreview
          parentNodes={directParentNodes}
          allNodes={allNodes}
          edges={edges}
          workflowId={propWorkflowId!}
          runId={effectiveRunId!}
          onSelectNode={onSelectNode}
          isDraggable={canDrag}
          autoExpandAncestors={!!ancestorReviewTarget}
          reviewRequestId={ancestorReviewTarget?.requestId}
        />
      );
    }

    // Manual trigger: entry point - no inputs needed
    if (isManualTrigger) {
      return (
        <div className="space-y-3">
          <div className="flex items-center gap-2 p-3 bg-slate-50 dark:bg-slate-800 rounded-lg border border-slate-200 dark:border-slate-700">
            <MousePointerClick className="h-5 w-5 text-slate-500 dark:text-slate-400 flex-shrink-0" />
            <div className="flex-1">
              <div className="text-sm font-medium text-slate-700 dark:text-slate-300">{t('manualTriggerTitle')}</div>
              <div className="text-sm text-slate-500 dark:text-slate-400">{t('manualTriggerDescription')}</div>
            </div>
          </div>
        </div>
      );
    }

    // Chat trigger: entry point - receives user message
    if (isChatTrigger) {
      return (
        <div className="space-y-3">
          <div className="flex items-center gap-2 p-3 bg-slate-50 dark:bg-slate-800 rounded-lg border border-slate-200 dark:border-slate-700">
            <MessageSquare className="h-5 w-5 text-slate-500 dark:text-slate-400 flex-shrink-0" />
            <div className="flex-1">
              <div className="text-sm font-medium text-slate-700 dark:text-slate-300">{t('chatTriggerTitle')}</div>
              <div className="text-sm text-slate-500 dark:text-slate-400">{t('chatTriggerDescription')}</div>
            </div>
          </div>
        </div>
      );
    }

    // Webhook trigger: entry point - receives HTTP request
    if (isWebhookTrigger) {
      return (
        <div className="space-y-3">
          <div className="flex items-center gap-2 p-3 bg-slate-50 dark:bg-slate-800 rounded-lg border border-slate-200 dark:border-slate-700">
            <Webhook className="h-5 w-5 text-slate-500 dark:text-slate-400 flex-shrink-0" />
            <div className="flex-1">
              <div className="text-sm font-medium text-slate-700 dark:text-slate-300">{t('webhookTriggerTitle')}</div>
              <div className="text-sm text-slate-500 dark:text-slate-400">{t('webhookTriggerDescription')}</div>
            </div>
          </div>
        </div>
      );
    }

    // Schedule trigger: entry point - runs on schedule
    if (isScheduleTrigger) {
      return (
        <div className="space-y-3">
          <div className="flex items-center gap-2 p-3 bg-slate-50 dark:bg-slate-800 rounded-lg border border-slate-200 dark:border-slate-700">
            <Clock className="h-5 w-5 text-slate-500 dark:text-slate-400 flex-shrink-0" />
            <div className="flex-1">
              <div className="text-sm font-medium text-slate-700 dark:text-slate-300">{t('scheduleTriggerTitle')}</div>
              <div className="text-sm text-slate-500 dark:text-slate-400">{t('scheduleTriggerDescription')}</div>
            </div>
          </div>
        </div>
      );
    }

    // Form trigger: entry point - receives form submission
    if (isFormTrigger) {
      return (
        <div className="space-y-3">
          <div className="flex items-center gap-2 p-3 bg-slate-50 dark:bg-slate-800 rounded-lg border border-slate-200 dark:border-slate-700">
            <FileText className="h-5 w-5 text-slate-500 dark:text-slate-400 flex-shrink-0" />
            <div className="flex-1">
              <div className="text-sm font-medium text-slate-700 dark:text-slate-300">{t('formTriggerTitle')}</div>
              <div className="text-sm text-slate-500 dark:text-slate-400">{t('formTriggerDescription')}</div>
            </div>
          </div>
        </div>
      );
    }

    // Branching nodes (decision/if-else, switch, fork): show upstream node data
    if (isBranchingNode) {
      return (
        <AncestorNodesSection
          currentNode={node}
          allNodes={allNodes}
          edges={edges}
          onSelectNode={onSelectNode}
          selectedLoopChild={selectedLoopChild}
          isRunMode={isRunMode}
        />
      );
    }

    // Workflows trigger with workflow selected: show info
    if (isWorkflowsTrigger && workflowId) {
      return (
        <div className="space-y-3">
          <div className="flex items-center gap-2 p-3 bg-slate-50 dark:bg-slate-800 rounded-lg border border-slate-200 dark:border-slate-700">
            <Workflow className="h-5 w-5 text-slate-500 dark:text-slate-400 flex-shrink-0" />
            <div className="flex-1">
              <div className="text-sm font-medium text-slate-700 dark:text-slate-300">{t('workflowsTriggerTitle')}</div>
              <div className="text-sm text-slate-500 dark:text-slate-400">{t('workflowsTriggerDescription')}</div>
            </div>
          </div>
        </div>
      );
    }

    // Workflows trigger without workflow selected: prompt to select one
    if (isWorkflowsTrigger && !workflowId) {
      return (
        <div className="space-y-3">
          <div className="flex items-center gap-2 p-3 bg-slate-50 dark:bg-slate-800 rounded-lg border border-slate-200 dark:border-slate-700">
            <Workflow className="h-5 w-5 text-slate-500 dark:text-slate-400 flex-shrink-0" />
            <div className="flex-1">
              <div className="text-sm font-medium text-slate-700 dark:text-slate-300">{t('workflowsTriggerTitle')}</div>
              <div className="text-sm text-slate-500 dark:text-slate-400">{t('workflowsTriggerSelectPrompt')}</div>
            </div>
          </div>
        </div>
      );
    }

    // Tables trigger without datasource selected: prompt to select one
    if (isTablesTrigger && !dataSourceId) {
      return (
        <div className="space-y-3">
          <div className="flex items-center gap-2 p-3 bg-slate-50 dark:bg-slate-800 rounded-lg border border-slate-200 dark:border-slate-700">
            <Table className="h-5 w-5 text-slate-500 dark:text-slate-400 flex-shrink-0" />
            <div className="flex-1">
              <div className="text-sm font-medium text-slate-700 dark:text-slate-300">{t('tablesTriggerTitle')}</div>
              <div className="text-sm text-slate-500 dark:text-slate-400">{t('tablesTriggerDescription')}</div>
            </div>
          </div>
        </div>
      );
    }

    // Standard content for other node types
    return (
      <>
        {/* Global Variables Section */}
        {globalVariables.length > 0 && (
          <div className="space-y-2 mb-4">
            <GlobalVariablesInspector
              variables={globalVariables}
              isDraggable={canDrag}
            />
          </div>
        )}

        {/* DataSource Columns Section - Show when datasource is selected */}
        {isTablesTrigger && dataSourceId && (
          <div className="space-y-2 mb-4">
            <DataSourceColumnsInspector
              dataSourceId={dataSourceId}
              triggerLabel={triggerLabel}
              isDraggable={canDrag}
            />
          </div>
        )}

        {/* Ancestor Nodes Section - Shows all upstream nodes organized by distance */}
        <AncestorNodesSection
          currentNode={node}
          allNodes={allNodes}
          edges={edges}
          onSelectNode={onSelectNode}
          selectedLoopChild={selectedLoopChild}
          isRunMode={isRunMode}
        />
      </>
    );
  };

  // If embedded, just render content without InspectorColumn wrapper
  if (embedded) {
    return (
      <div className="p-3 space-y-4">
        {renderContent()}
      </div>
    );
  }

  return (
    <InspectorColumn title={t('inputTitle')} className="border-slate-100 dark:border-slate-700" showRightBorder={true}>
      {renderContent()}
    </InspectorColumn>
  );
};
