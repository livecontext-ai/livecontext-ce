import * as React from 'react';
import * as ReactDOM from 'react-dom';
import type { Node } from 'reactflow';
import type { BuilderNodeData, FieldType } from '../../types';
import { getFieldTypeColor } from '../../types';
import { LazyStructureTree } from './LazyStructureTree';
import { useMcpToolDetails } from '../../hooks/useMcpData';
import { normalizeLabel } from '../../utils/labelNormalizer';
import { useTranslations } from 'next-intl';
import { RefreshCcw, Info, X, ArrowLeft } from 'lucide-react';
import { NodeIcon, getIconSlug } from '../nodes/shared';
import { usePopoverPosition } from '../../hooks/ui/usePopoverPosition';
import { nodeRegistry } from '../../registry/nodeRegistry';
import { extractFormFields, extractFormFieldsByAction } from '../../utils/interfaceHtmlUtils';

interface SourceNodeInspectorProps {
  node: Node<BuilderNodeData>;
  allNodes?: Node<BuilderNodeData>[];
  edges?: any[];
  onNavigateToNode?: (nodeId: string, loopId?: string) => void;
  onSelectNode?: (nodeId: string, loopId?: string) => void;
  isDraggable?: boolean;
  useIterationArrow?: boolean;
  isRunMode?: boolean; // En mode run, désactiver le drag
}

// Info tooltip component for loop nodes
const LoopInfoTooltip = ({ isIterationInput }: { isIterationInput: boolean }) => {
  const t = useTranslations('workflowBuilder.inspector');
  const [isOpen, setIsOpen] = React.useState(false);
  const { buttonRef, popoverPosition } = usePopoverPosition(isOpen, 256);

  const title = isIterationInput ? t('previousIteration') : t('loopOutput');
  const explanation = isIterationInput
    ? t('previousIterationExplanation')
    : t('loopOutputExplanation');

  return (
    <div className="relative inline-flex ml-1">
      <button
        ref={buttonRef}
        onClick={(e) => {
          e.stopPropagation();
          setIsOpen(!isOpen);
        }}
        className="p-0.5 rounded-full hover:bg-slate-200 dark:hover:bg-slate-700 transition-colors"
        title={t('clickForInfo')}
      >
        <Info className="h-3 w-3 text-slate-400 dark:text-slate-500 hover:text-slate-600 dark:hover:text-slate-300" />
      </button>
      {isOpen && typeof document !== 'undefined' && ReactDOM.createPortal(
        <>
          {/* Backdrop */}
          <div
            className="fixed inset-0 z-[9998]"
            onClick={() => setIsOpen(false)}
          />
          {/* Tooltip popup */}
          <div
            className="fixed z-[9999] w-64 p-3 bg-white dark:bg-slate-800 rounded-lg border border-slate-200 dark:border-slate-700"
            style={{ top: popoverPosition.top, left: popoverPosition.left }}
          >
            <div className="flex items-start justify-between gap-2 mb-2">
              <span className="font-medium text-sm text-slate-700 dark:text-slate-200">
                {title}
              </span>
              <button
                onClick={(e) => {
                  e.stopPropagation();
                  setIsOpen(false);
                }}
                className="p-0.5 rounded hover:bg-slate-100 dark:hover:bg-slate-700"
              >
                <X className="h-3.5 w-3.5 text-slate-400" />
              </button>
            </div>
            <p className="text-xs text-slate-500 dark:text-slate-400 leading-relaxed">
              {explanation}
            </p>
          </div>
        </>,
        document.body
      )}
    </div>
  );
};

export const SourceNodeInspector = React.memo(function SourceNodeInspector({
  node,
  onNavigateToNode,
  onSelectNode,
  isDraggable = true,
  useIterationArrow = false,
  isRunMode = false
}: SourceNodeInspectorProps) {
  const t = useTranslations('workflowBuilder.inspector');
  // Check node types
  const isToolNode = node.data.kind === 'tool' || (node.data as any).toolData;
  const toolSlug = (node.data as any).toolData?.toolSlug;

  const nodeDataId = node.data.id || '';

  // Check if this is a Classify node (must be before Agent check since kind is 'reasoning')
  const isClassifyNode = nodeRegistry.isClassifyNode(node);

  // Check if this is a Guardrail node (must be before Agent check since kind is 'reasoning')
  const isGuardrailNode = nodeRegistry.isGuardrailNode(node);

  // Check if this is an AI Agent node (exclude Classify and Guardrail)
  const isAgentNode = !isClassifyNode && !isGuardrailNode && (
    node.data.kind === 'reasoning' ||
    nodeDataId === 'ai-agent' ||
    nodeDataId === 'agent' ||
    nodeDataId.startsWith('ai-agent-') ||
    nodeDataId.startsWith('agent-')
  );

  // Check if this is a Data Input node
  const isDataInputNode = nodeRegistry.isDataInputNode(node);
  const dataInputItems = isDataInputNode ? ((node.data as any)?.dataInputItems || []) : [];

  // Check if this is a Transform node
  const isTransformNode = nodeDataId === 'transform' || nodeDataId.startsWith('transform-');
  const transformMappings = isTransformNode
    ? ((node.data as any)?.transformMappings || [{ id: 'default-field-1', label: 'field_1', expression: '' }])
    : [];

  // Check if this is a Merge node
  const isMergeNode = nodeRegistry.isMergeNode(node);

  // Check if this is an Aggregate node
  const isAggregateNode = nodeRegistry.isAggregateNode(node);
  const aggregateFields = isAggregateNode
    ? ((node.data as any)?.aggregateFields || [{ id: 'default-field-1', label: 'field_1', expression: '' }])
    : [];

  // Check if this is a Manual Trigger
  const isManualTrigger = nodeDataId === 'manual-trigger' ||
    nodeDataId.startsWith('manual-trigger-');

  // Check if this is a Chat Trigger
  const isChatTrigger = nodeDataId === 'chat-trigger' ||
    nodeDataId.startsWith('chat-trigger-');

  // Check if this is a Webhook Trigger
  const isWebhookTrigger = nodeDataId === 'webhook-trigger' ||
    nodeDataId.startsWith('webhook-trigger-');

  // Check if this is a Schedule Trigger
  const isScheduleTrigger = nodeDataId === 'schedule-trigger' ||
    nodeDataId.startsWith('schedule-trigger-');

  // Check if this is a Workflows Trigger
  const isWorkflowsTrigger = nodeDataId === 'workflows-trigger' ||
    nodeDataId.startsWith('workflows-trigger-');

  // Check if this is a Form Trigger
  const isFormTrigger = nodeDataId === 'form-trigger' ||
    nodeDataId.startsWith('form-trigger-');

  // Check if this node comes from a loop output - show loop icon
  const isFromLoopOutput = !!(node.data as any)?._isFromLoopOutput || !!(node.data as any)?._loopId;
  const isIterationInput = !!(node.data as any)?._isIterationInput;
  const showLoopIcon = useIterationArrow || isFromLoopOutput || isIterationInput;

  // Fetch tool details using React Query
  const { data: toolDetails } = useMcpToolDetails(isToolNode ? toolSlug : null);

  // Get structure ID from tool details
  const structureId = React.useMemo(() => {
    if (!toolDetails?.responses) return null;
    const defaultResponse = toolDetails.responses.find((r: any) => r.isDefault) || toolDetails.responses[0];
    return defaultResponse?.id || null;
  }, [toolDetails]);

  // Use either callback for navigation
  const handleNavigate = React.useCallback(() => {
    const loopId = (node.data as any)?._loopId;
    if (onSelectNode) {
      onSelectNode(node.id, loopId);
    } else if (onNavigateToNode) {
      onNavigateToNode(node.id, loopId);
    }
  }, [node.id, node.data, onNavigateToNode, onSelectNode]);

  // Normalize label to match backend LabelNormalizer format
  const normalizedNodeLabel = node.data?.label ? (normalizeLabel(node.data.label) || node.id) : node.id;

  // For Tool nodes, show the full structure tree
  if (isToolNode) {
    return (
      <div className="mb-3">
        <LazyStructureTree
          structureId={structureId}
          dragPrefix={`mcp:${normalizedNodeLabel}.output`}
          isDraggable={isDraggable}
          rootLabel="output"
          parentLabel={node.data.label}
          parentDirection="prev"
          onParentClick={handleNavigate}
          useIterationArrow={showLoopIcon}
          includeHttpStatus={true}
          infoTooltip={showLoopIcon ? <LoopInfoTooltip isIterationInput={isIterationInput} /> : undefined}
          parentNodeId={node.data?.id || node.id}
          parentIconSlug={getIconSlug(node.data)}
          parentNodeKind={node.data?.kind}
          parentAvatarUrl={(node.data as any)?.agentAvatarUrl}
        />
      </div>
    );
  }

  // For AI Agent nodes, show the agent output structure
  if (isAgentNode) {
    // Agent output fields that can be dragged
    const agentOutputFields: Array<{ name: string; type: FieldType }> = [
      { name: 'response', type: 'text' },
      { name: 'model', type: 'text' },
      { name: 'provider', type: 'text' },
      { name: 'tokens_used', type: 'number' },
      { name: 'iterations', type: 'number' },
      { name: 'tool_calls', type: 'number' },
      { name: 'tool_calls_detail', type: 'array' },
    ];

    return (
      <div className="mb-3">
        {/* Parent label with navigation */}
        <div className="flex items-center mb-2">
          <button
            onClick={handleNavigate}
            className="inline-flex items-center gap-1.5 px-2 py-1.5 rounded-lg text-sm transition-colors text-slate-600 dark:text-slate-400 hover:text-slate-900 dark:hover:text-slate-200 hover:bg-slate-100 dark:hover:bg-slate-800"
            title={t('goToNode', { label: node.data.label })}
          >
            <ArrowLeft className="h-3 w-3 flex-shrink-0" />
            {showLoopIcon ? (
              <RefreshCcw className="h-3.5 w-3.5" />
            ) : (
              <NodeIcon nodeId={node.data?.id || node.id} iconSlug={getIconSlug(node.data)} nodeKind={node.data?.kind as any} avatarUrl={(node.data as any)?.agentAvatarUrl} size="xs" />
            )}
            <span className="truncate max-w-[200px] font-medium">{node.data.label}</span>
          </button>
          {showLoopIcon && <LoopInfoTooltip isIterationInput={isIterationInput} />}
        </div>
        {/* Agent output fields - draggable */}
        <div className="space-y-1 pl-4">
          {agentOutputFields.map((field) => (
            <div
              key={field.name}
              draggable={true}
              onDragStart={(e) => {
                if (true) {
                  // Use agent: prefix with .output. for consistency
                  const dragValue = `{{agent:${normalizedNodeLabel}.output.${field.name}}}`;
                  e.dataTransfer.setData('text/plain', dragValue);
                  e.dataTransfer.effectAllowed = 'copy';
                }
              }}
              className={`flex items-center justify-between text-sm font-normal text-[var(--text-primary)] w-full px-1 py-1 rounded cursor-grab hover:bg-slate-50 dark:hover:bg-slate-800`}
            >
              <span className="text-sm">{field.name}</span>
              <span className={`text-xs px-1.5 py-0.5 rounded uppercase tracking-wider font-mono ${getFieldTypeColor(field.type)}`}>{field.type}</span>
            </div>
          ))}
        </div>
      </div>
    );
  }

  // For Classify nodes, show classification output fields
  if (isClassifyNode) {
    const classifyOutputFields: Array<{ name: string; type: FieldType }> = [
      { name: 'category', type: 'text' },
      { name: 'confidence', type: 'number' },
      { name: 'model', type: 'text' },
      { name: 'provider', type: 'text' },
    ];

    return (
      <div className="mb-3">
        {/* Parent label with navigation */}
        <div className="flex items-center mb-2">
          <button
            onClick={handleNavigate}
            className="inline-flex items-center gap-1.5 px-2 py-1.5 rounded-lg text-sm transition-colors text-slate-600 dark:text-slate-400 hover:text-slate-900 dark:hover:text-slate-200 hover:bg-slate-100 dark:hover:bg-slate-800"
            title={t('goToNode', { label: node.data.label })}
          >
            <ArrowLeft className="h-3 w-3 flex-shrink-0" />
            {showLoopIcon ? (
              <RefreshCcw className="h-3.5 w-3.5" />
            ) : (
              <NodeIcon nodeId={node.data?.id || node.id} iconSlug={getIconSlug(node.data)} nodeKind={node.data?.kind as any} avatarUrl={(node.data as any)?.agentAvatarUrl} size="xs" />
            )}
            <span className="truncate max-w-[200px] font-medium">{node.data.label}</span>
          </button>
          {showLoopIcon && <LoopInfoTooltip isIterationInput={isIterationInput} />}
        </div>
        {/* Classify output fields - draggable */}
        <div className="space-y-1 pl-4">
          {classifyOutputFields.map((field) => (
            <div
              key={field.name}
              draggable={true}
              onDragStart={(e) => {
                if (true) {
                  // Use agent: prefix with .output. for consistency
                  const dragValue = `{{agent:${normalizedNodeLabel}.output.${field.name}}}`;
                  e.dataTransfer.setData('text/plain', dragValue);
                  e.dataTransfer.effectAllowed = 'copy';
                }
              }}
              className={`flex items-center justify-between text-sm font-normal text-[var(--text-primary)] w-full px-1 py-1 rounded cursor-grab hover:bg-slate-50 dark:hover:bg-slate-800`}
            >
              <span className="text-sm">{field.name}</span>
              <span className={`text-xs px-1.5 py-0.5 rounded uppercase tracking-wider font-mono ${getFieldTypeColor(field.type)}`}>{field.type}</span>
            </div>
          ))}
        </div>
      </div>
    );
  }

  // For Guardrail nodes, show validation output fields
  if (isGuardrailNode) {
    const guardrailOutputFields: Array<{ name: string; type: FieldType }> = [
      { name: 'passed', type: 'boolean' },
      { name: 'violations', type: 'array' },
      { name: 'score', type: 'number' },
      { name: 'input', type: 'text' },
      { name: 'model', type: 'text' },
      { name: 'provider', type: 'text' },
    ];

    return (
      <div className="mb-3">
        {/* Parent label with navigation */}
        <div className="flex items-center mb-2">
          <button
            onClick={handleNavigate}
            className="inline-flex items-center gap-1.5 px-2 py-1.5 rounded-lg text-sm transition-colors text-slate-600 dark:text-slate-400 hover:text-slate-900 dark:hover:text-slate-200 hover:bg-slate-100 dark:hover:bg-slate-800"
            title={t('goToNode', { label: node.data.label })}
          >
            <ArrowLeft className="h-3 w-3 flex-shrink-0" />
            {showLoopIcon ? (
              <RefreshCcw className="h-3.5 w-3.5" />
            ) : (
              <NodeIcon nodeId={node.data?.id || node.id} iconSlug={getIconSlug(node.data)} nodeKind={node.data?.kind as any} avatarUrl={(node.data as any)?.agentAvatarUrl} size="xs" />
            )}
            <span className="truncate max-w-[200px] font-medium">{node.data.label}</span>
          </button>
          {showLoopIcon && <LoopInfoTooltip isIterationInput={isIterationInput} />}
        </div>
        {/* Guardrail output fields - draggable */}
        <div className="space-y-1 pl-4">
          {guardrailOutputFields.map((field) => (
            <div
              key={field.name}
              draggable={true}
              onDragStart={(e) => {
                if (true) {
                  // Use agent: prefix with .output. for consistency
                  const dragValue = `{{agent:${normalizedNodeLabel}.output.${field.name}}}`;
                  e.dataTransfer.setData('text/plain', dragValue);
                  e.dataTransfer.effectAllowed = 'copy';
                }
              }}
              className={`flex items-center justify-between text-sm font-normal text-[var(--text-primary)] w-full px-1 py-1 rounded cursor-grab hover:bg-slate-50 dark:hover:bg-slate-800`}
            >
              <span className="text-sm">{field.name}</span>
              <span className={`text-xs px-1.5 py-0.5 rounded uppercase tracking-wider font-mono ${getFieldTypeColor(field.type)}`}>{field.type}</span>
            </div>
          ))}
        </div>
      </div>
    );
  }

  // For Transform nodes, show the transform mappings as output fields
  if (isTransformNode) {
    return (
      <div className="mb-3">
        {/* Parent label with navigation */}
        <div className="flex items-center mb-2">
          <button
            onClick={handleNavigate}
            className="inline-flex items-center gap-1.5 px-2 py-1.5 rounded-lg text-sm transition-colors text-slate-600 dark:text-slate-400 hover:text-slate-900 dark:hover:text-slate-200 hover:bg-slate-100 dark:hover:bg-slate-800"
            title={t('goToNode', { label: node.data.label })}
          >
            <ArrowLeft className="h-3 w-3 flex-shrink-0" />
            {showLoopIcon ? (
              <RefreshCcw className="h-3.5 w-3.5" />
            ) : (
              <NodeIcon nodeId={node.data?.id || node.id} iconSlug={getIconSlug(node.data)} nodeKind={node.data?.kind as any} avatarUrl={(node.data as any)?.agentAvatarUrl} size="xs" />
            )}
            <span className="truncate max-w-[200px] font-medium">{node.data.label}</span>
          </button>
          {showLoopIcon && <LoopInfoTooltip isIterationInput={isIterationInput} />}
        </div>
        {/* Transform output fields - draggable */}
        <div className="space-y-1 pl-4">
          {transformMappings.map((mapping: { id: string; label: string; expression: string }) => (
            <div
              key={mapping.id}
              draggable={true}
              onDragStart={(e) => {
                if (true) {
                  // Transform is a core node
                  const dragValue = `{{core:${normalizedNodeLabel}.output.${mapping.label}}}`;
                  e.dataTransfer.setData('text/plain', dragValue);
                  e.dataTransfer.effectAllowed = 'copy';
                }
              }}
              className={`flex items-center justify-between text-sm font-normal text-[var(--text-primary)] w-full px-1 py-1 rounded cursor-grab hover:bg-slate-50 dark:hover:bg-slate-800`}
            >
              <span className="text-sm">{mapping.label}</span>
              <span className="text-xs px-1.5 py-0.5 rounded uppercase tracking-wider font-mono bg-gray-200 dark:bg-gray-700 text-gray-600 dark:text-gray-400">any</span>
            </div>
          ))}
        </div>
      </div>
    );
  }

  // For Data Input nodes, show configured items as output fields
  if (isDataInputNode && dataInputItems.length > 0) {
    return (
      <div className="mb-3">
        {/* Parent label with navigation */}
        <div className="flex items-center mb-2">
          <button
            onClick={handleNavigate}
            className="inline-flex items-center gap-1.5 px-2 py-1.5 rounded-lg text-sm transition-colors text-slate-600 dark:text-slate-400 hover:text-slate-900 dark:hover:text-slate-200 hover:bg-slate-100 dark:hover:bg-slate-800"
            title={t('goToNode', { label: node.data.label })}
          >
            <ArrowLeft className="h-3 w-3 flex-shrink-0" />
            {showLoopIcon ? (
              <RefreshCcw className="h-3.5 w-3.5" />
            ) : (
              <NodeIcon nodeId={node.data?.id || node.id} iconSlug={getIconSlug(node.data)} nodeKind={node.data?.kind as any} avatarUrl={(node.data as any)?.agentAvatarUrl} size="xs" />
            )}
            <span className="truncate max-w-[200px] font-medium">{node.data.label}</span>
          </button>
          {showLoopIcon && <LoopInfoTooltip isIterationInput={isIterationInput} />}
        </div>
        {/* Data Input output fields - draggable */}
        <div className="space-y-1 pl-4">
          {dataInputItems.map((item: { id: string; label: string; type: string }) => (
            <div
              key={item.id}
              draggable={true}
              onDragStart={(e) => {
                const dragValue = `{{core:${normalizedNodeLabel}.output.${item.label}}}`;
                e.dataTransfer.setData('text/plain', dragValue);
                e.dataTransfer.effectAllowed = 'copy';
              }}
              className="flex items-center justify-between text-sm font-normal text-[var(--text-primary)] w-full px-1 py-1 rounded cursor-grab hover:bg-slate-50 dark:hover:bg-slate-800"
            >
              <span className="text-sm">{item.label}</span>
              <span className={`text-xs px-1.5 py-0.5 rounded uppercase tracking-wider font-mono ${getFieldTypeColor(item.type === 'file' ? 'object' : 'text')}`}>
                {item.type === 'file' ? 'object' : 'text'}
              </span>
            </div>
          ))}
        </div>
      </div>
    );
  }

  // For Aggregate nodes, show configured fields as array outputs + count
  if (isAggregateNode) {
    return (
      <div className="mb-3">
        {/* Parent label with navigation */}
        <div className="flex items-center mb-2">
          <button
            onClick={handleNavigate}
            className="inline-flex items-center gap-1.5 px-2 py-1.5 rounded-lg text-sm transition-colors text-slate-600 dark:text-slate-400 hover:text-slate-900 dark:hover:text-slate-200 hover:bg-slate-100 dark:hover:bg-slate-800"
            title={t('goToNode', { label: node.data.label })}
          >
            <ArrowLeft className="h-3 w-3 flex-shrink-0" />
            {showLoopIcon ? (
              <RefreshCcw className="h-3.5 w-3.5" />
            ) : (
              <NodeIcon nodeId={node.data?.id || node.id} iconSlug={getIconSlug(node.data)} nodeKind={node.data?.kind as any} avatarUrl={(node.data as any)?.agentAvatarUrl} size="xs" />
            )}
            <span className="truncate max-w-[200px] font-medium">{node.data.label}</span>
          </button>
          {showLoopIcon && <LoopInfoTooltip isIterationInput={isIterationInput} />}
        </div>
        {/* Aggregate output fields - each configured field becomes an array */}
        <div className="space-y-1 pl-4">
          {aggregateFields.map((field: { id: string; label: string; expression: string }) => (
            <div
              key={field.id}
              draggable={true}
              onDragStart={(e) => {
                if (true) {
                  // Aggregate is a core node
                  const dragValue = `{{core:${normalizedNodeLabel}.output.${field.label}}}`;
                  e.dataTransfer.setData('text/plain', dragValue);
                  e.dataTransfer.effectAllowed = 'copy';
                }
              }}
              className={`flex items-center justify-between text-sm font-normal text-[var(--text-primary)] w-full px-1 py-1 rounded cursor-grab hover:bg-slate-50 dark:hover:bg-slate-800`}
            >
              <span className="text-sm">{field.label}</span>
              <span className={`text-xs px-1.5 py-0.5 rounded uppercase tracking-wider font-mono ${getFieldTypeColor('array')}`}>array</span>
            </div>
          ))}
          {/* Count field - always present */}
          <div
            draggable={true}
            onDragStart={(e) => {
              if (true) {
                const dragValue = `{{core:${normalizedNodeLabel}.output.count}}`;
                e.dataTransfer.setData('text/plain', dragValue);
                e.dataTransfer.effectAllowed = 'copy';
              }
            }}
            className={`flex items-center justify-between text-sm font-normal text-[var(--text-primary)] w-full px-1 py-1 rounded cursor-grab hover:bg-slate-50 dark:hover:bg-slate-800`}
          >
            <span className="text-sm">count</span>
            <span className={`text-xs px-1.5 py-0.5 rounded uppercase tracking-wider font-mono ${getFieldTypeColor('number')}`}>number</span>
          </div>
        </div>
      </div>
    );
  }

  // For Merge nodes, show merge output fields
  if (isMergeNode) {
    const mergeFields = [
      { key: 'strategy', type: 'text' },
      { key: 'source_count', type: 'number' },
      { key: 'success_count', type: 'number' },
      { key: 'sources', type: 'object' },
      { key: 'merged_items', type: 'array' },
      { key: 'item_count', type: 'number' },
    ];
    return (
      <div className="mb-3">
        {/* Parent label with navigation */}
        <div className="flex items-center mb-2">
          <button
            onClick={handleNavigate}
            className="inline-flex items-center gap-1.5 px-2 py-1.5 rounded-lg text-sm transition-colors text-slate-600 dark:text-slate-400 hover:text-slate-900 dark:hover:text-slate-200 hover:bg-slate-100 dark:hover:bg-slate-800"
            title={t('goToNode', { label: node.data.label })}
          >
            <ArrowLeft className="h-3 w-3 flex-shrink-0" />
            {showLoopIcon ? (
              <RefreshCcw className="h-3.5 w-3.5" />
            ) : (
              <NodeIcon nodeId={node.data?.id || node.id} iconSlug={getIconSlug(node.data)} nodeKind={node.data?.kind as any} avatarUrl={(node.data as any)?.agentAvatarUrl} size="xs" />
            )}
            <span className="truncate max-w-[200px] font-medium">{node.data.label}</span>
          </button>
          {showLoopIcon && <LoopInfoTooltip isIterationInput={isIterationInput} />}
        </div>
        {/* Merge output fields */}
        <div className="space-y-1 pl-4">
          {mergeFields.map((field) => (
            <div
              key={field.key}
              draggable={true}
              onDragStart={(e) => {
                const dragValue = `{{core:${normalizedNodeLabel}.output.${field.key}}}`;
                e.dataTransfer.setData('text/plain', dragValue);
                e.dataTransfer.effectAllowed = 'copy';
              }}
              className={`flex items-center justify-between text-sm font-normal text-[var(--text-primary)] w-full px-1 py-1 rounded cursor-grab hover:bg-slate-50 dark:hover:bg-slate-800`}
            >
              <span className="text-sm">{field.key}</span>
              <span className={`text-xs px-1.5 py-0.5 rounded uppercase tracking-wider font-mono ${getFieldTypeColor(field.type)}`}>{field.type}</span>
            </div>
          ))}
        </div>
      </div>
    );
  }

  // For Manual Trigger, show triggeredAt output + custom fields
  if (isManualTrigger) {
    const customFields = (node.data as any)?.manualTriggerData?.customFields || [];
    const validCustomFields = customFields.filter((f: any) => f.name);

    const manualTriggerFields: Array<{ name: string; type: FieldType }> = [
      { name: 'triggeredAt', type: 'datetime' },
      ...validCustomFields.map((f: any) => ({
        name: f.name,
        type: 'text' as FieldType,
      })),
    ];

    return (
      <div className="mb-3">
        {/* Parent label with navigation */}
        <div className="flex items-center mb-2">
          <button
            onClick={handleNavigate}
            className="inline-flex items-center gap-1.5 px-2 py-1.5 rounded-lg text-sm transition-colors text-slate-600 dark:text-slate-400 hover:text-slate-900 dark:hover:text-slate-200 hover:bg-slate-100 dark:hover:bg-slate-800"
            title={t('goToNode', { label: node.data.label })}
          >
            <ArrowLeft className="h-3 w-3 flex-shrink-0" />
            <NodeIcon nodeId={node.data?.id || node.id} iconSlug={getIconSlug(node.data)} nodeKind={node.data?.kind as any} avatarUrl={(node.data as any)?.agentAvatarUrl} size="xs" />
            <span className="truncate max-w-[200px] font-medium">{node.data.label}</span>
          </button>
        </div>
        {/* Manual trigger outputs */}
        <div className="space-y-1 pl-4">
          {manualTriggerFields.map((field) => (
            <div
              key={field.name}
              draggable={true}
              onDragStart={(e) => {
                if (true) {
                  const dragValue = `{{trigger:${normalizedNodeLabel}.output.${field.name}}}`;
                  e.dataTransfer.setData('text/plain', dragValue);
                  e.dataTransfer.effectAllowed = 'copy';
                }
              }}
              className={`flex items-center justify-between text-sm font-normal text-[var(--text-primary)] w-full px-1 py-1 rounded cursor-grab hover:bg-slate-50 dark:hover:bg-slate-800`}
            >
              <span className="text-sm">{field.name}</span>
              <span className={`text-xs px-1.5 py-0.5 rounded uppercase tracking-wider font-mono ${getFieldTypeColor(field.type)}`}>{field.type}</span>
            </div>
          ))}
        </div>
      </div>
    );
  }

  // For Chat Trigger, show message and triggeredAt outputs
  if (isChatTrigger) {
    const chatTriggerFields: Array<{ name: string; type: FieldType }> = [
      { name: 'message', type: 'text' },
      { name: 'triggeredAt', type: 'datetime' },
    ];

    return (
      <div className="mb-3">
        {/* Parent label with navigation */}
        <div className="flex items-center mb-2">
          <button
            onClick={handleNavigate}
            className="inline-flex items-center gap-1.5 px-2 py-1.5 rounded-lg text-sm transition-colors text-slate-600 dark:text-slate-400 hover:text-slate-900 dark:hover:text-slate-200 hover:bg-slate-100 dark:hover:bg-slate-800"
            title={t('goToNode', { label: node.data.label })}
          >
            <ArrowLeft className="h-3 w-3 flex-shrink-0" />
            <NodeIcon nodeId={node.data?.id || node.id} iconSlug={getIconSlug(node.data)} nodeKind={node.data?.kind as any} avatarUrl={(node.data as any)?.agentAvatarUrl} size="xs" />
            <span className="truncate max-w-[200px] font-medium">{node.data.label}</span>
          </button>
        </div>
        {/* Chat trigger outputs */}
        <div className="space-y-1 pl-4">
          {chatTriggerFields.map((field) => (
            <div
              key={field.name}
              draggable={true}
              onDragStart={(e) => {
                if (true) {
                  const dragValue = `{{trigger:${normalizedNodeLabel}.output.${field.name}}}`;
                  e.dataTransfer.setData('text/plain', dragValue);
                  e.dataTransfer.effectAllowed = 'copy';
                }
              }}
              className={`flex items-center justify-between text-sm font-normal text-[var(--text-primary)] w-full px-1 py-1 rounded cursor-grab hover:bg-slate-50 dark:hover:bg-slate-800`}
            >
              <span className="text-sm">{field.name}</span>
              <span className={`text-xs px-1.5 py-0.5 rounded uppercase tracking-wider font-mono ${getFieldTypeColor(field.type)}`}>{field.type}</span>
            </div>
          ))}
        </div>
      </div>
    );
  }

  // For Webhook Trigger, show payload, token, trigger_id, triggeredAt outputs
  if (isWebhookTrigger) {
    const webhookTriggerFields: Array<{ name: string; type: FieldType }> = [
      { name: 'payload', type: 'object' },
      { name: 'token', type: 'text' },
      { name: 'trigger_id', type: 'text' },
      { name: 'triggeredAt', type: 'datetime' },
    ];

    return (
      <div className="mb-3">
        {/* Parent label with navigation */}
        <div className="flex items-center mb-2">
          <button
            onClick={handleNavigate}
            className="inline-flex items-center gap-1.5 px-2 py-1.5 rounded-lg text-sm transition-colors text-slate-600 dark:text-slate-400 hover:text-slate-900 dark:hover:text-slate-200 hover:bg-slate-100 dark:hover:bg-slate-800"
            title={t('goToNode', { label: node.data.label })}
          >
            <ArrowLeft className="h-3 w-3 flex-shrink-0" />
            <NodeIcon nodeId={node.data?.id || node.id} iconSlug={getIconSlug(node.data)} nodeKind={node.data?.kind as any} avatarUrl={(node.data as any)?.agentAvatarUrl} size="xs" />
            <span className="truncate max-w-[200px] font-medium">{node.data.label}</span>
          </button>
        </div>
        {/* Webhook trigger outputs */}
        <div className="space-y-1 pl-4">
          {webhookTriggerFields.map((field) => (
            <div
              key={field.name}
              draggable={true}
              onDragStart={(e) => {
                if (true) {
                  const dragValue = `{{trigger:${normalizedNodeLabel}.output.${field.name}}}`;
                  e.dataTransfer.setData('text/plain', dragValue);
                  e.dataTransfer.effectAllowed = 'copy';
                }
              }}
              className={`flex items-center justify-between text-sm font-normal text-[var(--text-primary)] w-full px-1 py-1 rounded cursor-grab hover:bg-slate-50 dark:hover:bg-slate-800`}
            >
              <span className="text-sm">{field.name}</span>
              <span className={`text-xs px-1.5 py-0.5 rounded uppercase tracking-wider font-mono ${getFieldTypeColor(field.type)}`}>{field.type}</span>
            </div>
          ))}
        </div>
      </div>
    );
  }

  // For Schedule Trigger, show contract-aligned snake_case outputs
  if (isScheduleTrigger) {
    const scheduleTriggerFields: Array<{ name: string; type: FieldType }> = [
      { name: 'triggered_at', type: 'datetime' },
      { name: 'execution_count', type: 'number' },
      { name: 'next_execution', type: 'datetime' },
    ];

    return (
      <div className="mb-3">
        {/* Parent label with navigation */}
        <div className="flex items-center mb-2">
          <button
            onClick={handleNavigate}
            className="inline-flex items-center gap-1.5 px-2 py-1.5 rounded-lg text-sm transition-colors text-slate-600 dark:text-slate-400 hover:text-slate-900 dark:hover:text-slate-200 hover:bg-slate-100 dark:hover:bg-slate-800"
            title={t('goToNode', { label: node.data.label })}
          >
            <ArrowLeft className="h-3 w-3 flex-shrink-0" />
            <NodeIcon nodeId={node.data?.id || node.id} iconSlug={getIconSlug(node.data)} nodeKind={node.data?.kind as any} avatarUrl={(node.data as any)?.agentAvatarUrl} size="xs" />
            <span className="truncate max-w-[200px] font-medium">{node.data.label}</span>
          </button>
        </div>
        {/* Schedule trigger outputs */}
        <div className="space-y-1 pl-4">
          {scheduleTriggerFields.map((field) => (
            <div
              key={field.name}
              draggable={true}
              onDragStart={(e) => {
                if (true) {
                  const dragValue = `{{trigger:${normalizedNodeLabel}.output.${field.name}}}`;
                  e.dataTransfer.setData('text/plain', dragValue);
                  e.dataTransfer.effectAllowed = 'copy';
                }
              }}
              className={`flex items-center justify-between text-sm font-normal text-[var(--text-primary)] w-full px-1 py-1 rounded cursor-grab hover:bg-slate-50 dark:hover:bg-slate-800`}
            >
              <span className="text-sm">{field.name}</span>
              <span className={`text-xs px-1.5 py-0.5 rounded uppercase tracking-wider font-mono ${getFieldTypeColor(field.type)}`}>{field.type}</span>
            </div>
          ))}
        </div>
      </div>
    );
  }

  // For Form Trigger, show formData, submittedAt, and custom field outputs
  if (isFormTrigger) {
    const formTriggerData = (node.data as any)?.formTriggerData;
    const customFields = formTriggerData?.fields || [];
    const validCustomFields = customFields.filter((f: any) => f.name);

    const formTriggerFields: Array<{ name: string; type: FieldType }> = [
      { name: 'formData', type: 'object' },
      { name: 'submittedAt', type: 'datetime' },
      ...validCustomFields.map((f: any) => ({
        name: f.name,
        type: (f.type === 'number' ? 'number' :
               f.type === 'checkbox' ? 'boolean' :
               f.type === 'date' || f.type === 'datetime' || f.type === 'time' ? 'datetime' :
               f.type === 'multiselect' || f.type === 'checkboxGroup' ? 'array' :
               f.type === 'file' ? 'object' :
               'text') as FieldType,
      })),
    ];

    return (
      <div className="mb-3">
        {/* Parent label with navigation */}
        <div className="flex items-center mb-2">
          <button
            onClick={handleNavigate}
            className="inline-flex items-center gap-1.5 px-2 py-1.5 rounded-lg text-sm transition-colors text-slate-600 dark:text-slate-400 hover:text-slate-900 dark:hover:text-slate-200 hover:bg-slate-100 dark:hover:bg-slate-800"
            title={t('goToNode', { label: node.data.label })}
          >
            <ArrowLeft className="h-3 w-3 flex-shrink-0" />
            <NodeIcon nodeId={node.data?.id || node.id} iconSlug={getIconSlug(node.data)} nodeKind={node.data?.kind as any} avatarUrl={(node.data as any)?.agentAvatarUrl} size="xs" />
            <span className="truncate max-w-[200px] font-medium">{node.data.label}</span>
          </button>
        </div>
        {/* Form trigger outputs */}
        <div className="space-y-1 pl-4">
          {formTriggerFields.map((field) => (
            <div
              key={field.name}
              draggable={true}
              onDragStart={(e) => {
                if (true) {
                  const dragValue = `{{trigger:${normalizedNodeLabel}.output.${field.name}}}`;
                  e.dataTransfer.setData('text/plain', dragValue);
                  e.dataTransfer.effectAllowed = 'copy';
                }
              }}
              className={`flex items-center justify-between text-sm font-normal text-[var(--text-primary)] w-full px-1 py-1 rounded cursor-grab hover:bg-slate-50 dark:hover:bg-slate-800`}
            >
              <span className="text-sm">{field.name}</span>
              <span className={`text-xs px-1.5 py-0.5 rounded uppercase tracking-wider font-mono ${getFieldTypeColor(field.type)}`}>{field.type}</span>
            </div>
          ))}
        </div>
      </div>
    );
  }

  // For Workflows Trigger, show result and status outputs
  if (isWorkflowsTrigger) {
    const workflowsTriggerFields: Array<{ name: string; type: FieldType }> = [
      { name: 'result', type: 'object' },
      { name: 'status', type: 'text' },
    ];

    return (
      <div className="mb-3">
        {/* Parent label with navigation */}
        <div className="flex items-center mb-2">
          <button
            onClick={handleNavigate}
            className="inline-flex items-center gap-1.5 px-2 py-1.5 rounded-lg text-sm transition-colors text-slate-600 dark:text-slate-400 hover:text-slate-900 dark:hover:text-slate-200 hover:bg-slate-100 dark:hover:bg-slate-800"
            title={t('goToNode', { label: node.data.label })}
          >
            <ArrowLeft className="h-3 w-3 flex-shrink-0" />
            <NodeIcon nodeId={node.data?.id || node.id} iconSlug={getIconSlug(node.data)} nodeKind={node.data?.kind as any} avatarUrl={(node.data as any)?.agentAvatarUrl} size="xs" />
            <span className="truncate max-w-[200px] font-medium">{node.data.label}</span>
          </button>
        </div>
        {/* Workflows trigger outputs */}
        <div className="space-y-1 pl-4">
          {workflowsTriggerFields.map((field) => (
            <div
              key={field.name}
              draggable={true}
              onDragStart={(e) => {
                if (true) {
                  const dragValue = `{{trigger:${normalizedNodeLabel}.output.${field.name}}}`;
                  e.dataTransfer.setData('text/plain', dragValue);
                  e.dataTransfer.effectAllowed = 'copy';
                }
              }}
              className={`flex items-center justify-between text-sm font-normal text-[var(--text-primary)] w-full px-1 py-1 rounded cursor-grab hover:bg-slate-50 dark:hover:bg-slate-800`}
            >
              <span className="text-sm">{field.name}</span>
              <span className={`text-xs px-1.5 py-0.5 rounded uppercase tracking-wider font-mono ${getFieldTypeColor(field.type)}`}>{field.type}</span>
            </div>
          ))}
        </div>
      </div>
    );
  }

  // For Interface nodes, show output schema organized by action names
  if (nodeRegistry.isInterfaceNode(node)) {
    const interfaceData = (node.data as any)?.interfaceData;
    const htmlTemplate = interfaceData?.editorExpression || '';
    const formFields = extractFormFields(htmlTemplate);
    const actionMapping: Record<string, string> = interfaceData?.actionMapping || {};
    const actionNames = Object.keys(actionMapping).filter(k => k.length > 0);
    const fieldsByAction = extractFormFieldsByAction(htmlTemplate, actionNames);

    // Node-level outputs gated by InterfaceNode toggles (InterfaceNodeSpec declares them
    // conditional - emitted only when the matching toggle is on). Surfaced here so
    // downstream nodes can drag them into their editor panel.
    const nodeLevelOutputs: Array<{ name: string; type: FieldType }> = [];
    if (interfaceData?.generateScreenshot === true) {
      nodeLevelOutputs.push({ name: 'screenshot', type: 'object' });
    }
    if (interfaceData?.exposeRenderedSource === true) {
      nodeLevelOutputs.push({ name: 'rendered_html', type: 'text' });
      nodeLevelOutputs.push({ name: 'rendered_css', type: 'text' });
      nodeLevelOutputs.push({ name: 'rendered_js', type: 'text' });
    }

    const renderNodeLevelOutputs = () => {
      if (nodeLevelOutputs.length === 0) return null;
      return (
        <div>
          <div className="text-xs font-semibold text-indigo-500 dark:text-indigo-400 mb-1 px-1">
            {t('nodeOutputs')}
          </div>
          <div className="space-y-0.5 pl-3 border-l-2 border-indigo-200 dark:border-indigo-800">
            {nodeLevelOutputs.map((field) => (
              <div
                key={field.name}
                draggable={isDraggable}
                onDragStart={(e) => {
                  const dragValue = `{{interface:${normalizedNodeLabel}.output.${field.name}}}`;
                  e.dataTransfer.setData('text/plain', dragValue);
                  e.dataTransfer.setData('application/x-field-type', field.type);
                  e.dataTransfer.effectAllowed = 'copy';
                }}
                className="flex items-center justify-between text-sm font-normal text-[var(--text-primary)] w-full px-1 py-0.5 rounded cursor-grab hover:bg-slate-50 dark:hover:bg-slate-800"
              >
                <span className="text-sm">{field.name}</span>
                <span className={`text-xs px-1.5 py-0.5 rounded uppercase tracking-wider font-mono ${getFieldTypeColor(field.type)}`}>{field.type}</span>
              </div>
            ))}
          </div>
        </div>
      );
    };

    // When actionMapping is configured, show output grouped by action name
    // Each action only shows its OWN scoped form fields (not all fields globally)
    if (actionNames.length > 0) {
      return (
        <div className="mb-3">
          <div className="flex items-center mb-2">
            <button
              onClick={handleNavigate}
              className="inline-flex items-center gap-1.5 px-2 py-1.5 rounded-lg text-sm transition-colors text-slate-600 dark:text-slate-400 hover:text-slate-900 dark:hover:text-slate-200 hover:bg-slate-100 dark:hover:bg-slate-800"
              title={t('goToNode', { label: node.data.label })}
            >
              <ArrowLeft className="h-3 w-3 flex-shrink-0" />
              <NodeIcon nodeId={node.data?.id || node.id} iconSlug={getIconSlug(node.data)} nodeKind={node.data?.kind as any} avatarUrl={(node.data as any)?.agentAvatarUrl} size="xs" />
              <span className="truncate max-w-[200px] font-medium">{node.data.label}</span>
            </button>
          </div>
          <div className="space-y-2 pl-4">
            {actionNames.map((actionName) => {
              const actionFields = fieldsByAction.get(actionName) || [];
              return (
                <div key={actionName}>
                  {/* Action name header */}
                  <div className="text-xs font-semibold text-indigo-500 dark:text-indigo-400 mb-1 px-1">
                    {actionName}
                  </div>
                  <div className="space-y-0.5 pl-3 border-l-2 border-indigo-200 dark:border-indigo-800">
                    {/* Form fields scoped to this action */}
                    {actionFields.map((field) => (
                      <div
                        key={`${actionName}-${field}`}
                        draggable={isDraggable}
                        onDragStart={(e) => {
                          const dragValue = `{{interface:${normalizedNodeLabel}.output.${actionName}.${field}}}`;
                          e.dataTransfer.setData('text/plain', dragValue);
                          e.dataTransfer.setData('application/x-field-type', 'text');
                          e.dataTransfer.effectAllowed = 'copy';
                        }}
                        className="flex items-center justify-between text-sm font-normal text-[var(--text-primary)] w-full px-1 py-0.5 rounded cursor-grab hover:bg-slate-50 dark:hover:bg-slate-800"
                      >
                        <span className="text-sm">{field}</span>
                        <span className={`text-xs px-1.5 py-0.5 rounded uppercase tracking-wider font-mono ${getFieldTypeColor('text')}`}>text</span>
                      </div>
                    ))}
                    {/* fired_at always present */}
                    <div
                      draggable={isDraggable}
                      onDragStart={(e) => {
                        const dragValue = `{{interface:${normalizedNodeLabel}.output.${actionName}.fired_at}}`;
                        e.dataTransfer.setData('text/plain', dragValue);
                        e.dataTransfer.effectAllowed = 'copy';
                      }}
                      className="flex items-center justify-between text-sm font-normal text-[var(--text-primary)] w-full px-1 py-0.5 rounded cursor-grab hover:bg-slate-50 dark:hover:bg-slate-800"
                    >
                      <span className="text-sm">fired_at</span>
                      <span className={`text-xs px-1.5 py-0.5 rounded uppercase tracking-wider font-mono ${getFieldTypeColor('datetime')}`}>datetime</span>
                    </div>
                  </div>
                </div>
              );
            })}
            {renderNodeLevelOutputs()}
          </div>
        </div>
      );
    }

    // Fallback: no actionMapping configured - show flat form fields (all global fields)
    const formFieldItems: Array<{ name: string; type: FieldType }> = formFields.map(f => ({
      name: f,
      type: 'text' as FieldType,
    }));

    return (
      <div className="mb-3">
        <div className="flex items-center mb-2">
          <button
            onClick={handleNavigate}
            className="inline-flex items-center gap-1.5 px-2 py-1.5 rounded-lg text-sm transition-colors text-slate-600 dark:text-slate-400 hover:text-slate-900 dark:hover:text-slate-200 hover:bg-slate-100 dark:hover:bg-slate-800"
            title={t('goToNode', { label: node.data.label })}
          >
            <ArrowLeft className="h-3 w-3 flex-shrink-0" />
            <NodeIcon nodeId={node.data?.id || node.id} iconSlug={getIconSlug(node.data)} nodeKind={node.data?.kind as any} avatarUrl={(node.data as any)?.agentAvatarUrl} size="xs" />
            <span className="truncate max-w-[200px] font-medium">{node.data.label}</span>
          </button>
        </div>
        <div className="space-y-1 pl-4">
          {formFieldItems.map((field) => (
            <div
              key={field.name}
              draggable={isDraggable}
              onDragStart={(e) => {
                const dragValue = `{{interface:${normalizedNodeLabel}.output.${field.name}}}`;
                e.dataTransfer.setData('text/plain', dragValue);
                e.dataTransfer.setData('application/x-field-type', field.type);
                e.dataTransfer.effectAllowed = 'copy';
              }}
              className="flex items-center justify-between text-sm font-normal text-[var(--text-primary)] w-full px-1 py-1 rounded cursor-grab hover:bg-slate-50 dark:hover:bg-slate-800"
            >
              <span className="text-sm">{field.name}</span>
              <span className={`text-xs px-1.5 py-0.5 rounded uppercase tracking-wider font-mono ${getFieldTypeColor(field.type)}`}>{field.type}</span>
            </div>
          ))}
          {formFieldItems.length === 0 && nodeLevelOutputs.length === 0 && (
            <p className="text-xs text-slate-400 dark:text-slate-500 italic px-1">
              No form fields detected. Add &lt;input name=&quot;...&quot;&gt; to the template.
            </p>
          )}
          {renderNodeLevelOutputs()}
        </div>
      </div>
    );
  }

  // For non-tool nodes, show a simple navigation link
  return (
    <div className="mb-3">
      <div className="flex items-center">
        <button
          onClick={handleNavigate}
          className="inline-flex items-center gap-1.5 px-2 py-1.5 rounded-lg text-sm transition-colors text-slate-600 dark:text-slate-400 hover:text-slate-900 dark:hover:text-slate-200 hover:bg-slate-100 dark:hover:bg-slate-800"
          title={t('goToNode', { label: node.data.label })}
        >
          <ArrowLeft className="h-3 w-3 flex-shrink-0" />
          {showLoopIcon ? (
            <RefreshCcw className="h-3.5 w-3.5" />
          ) : (
            <NodeIcon nodeId={node.data?.id || node.id} iconSlug={getIconSlug(node.data)} nodeKind={node.data?.kind as any} avatarUrl={(node.data as any)?.agentAvatarUrl} size="xs" />
          )}
          <span className="truncate max-w-[200px] font-medium">{node.data.label}</span>
        </button>
        {showLoopIcon && <LoopInfoTooltip isIterationInput={isIterationInput} />}
      </div>
    </div>
  );
});
