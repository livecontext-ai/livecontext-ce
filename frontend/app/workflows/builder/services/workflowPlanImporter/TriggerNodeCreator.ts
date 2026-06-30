/**
 * TriggerNodeCreator - Handles creation of trigger nodes from plan data
 * Extracted from NodeCreationService for single responsibility
 */

import type { Node } from 'reactflow';
import type { BuilderNodeData } from '../../types';
import { ToolDataService } from './ToolDataService';
import { normalizeLabel } from '../../utils/labelNormalizer';
import {
  parsePosition,
  inputToParamExpressions,
  generateNodeId,
  NODE_SPACING,
} from './nodeCreationHelpers';

interface TriggerFromPlan {
  id: string;
  type: string;
  label?: string;
  description?: string;
  position?: { x?: number | string; y?: number | string };
  params?: Record<string, any>;
  graphNodeId?: string;
}

interface TriggerCreationResult {
  node: Node<BuilderNodeData>;
  mappings: {
    triggerId: string;
    normalizedLabel?: string;
  };
  incrementY: boolean;
}

/**
 * Build schedule trigger data from params.
 * Inspector derives the frequency dropdown selection from cronExpression at
 * render time, so we only persist the canonical fields the plan owns.
 */
function buildScheduleTriggerData(params: any): any {
  return {
    cronExpression: params.cron || '0 * * * *',
    timezone: params.timezone || 'UTC',
    maxExecutions: params.maxExecutions || null,
  };
}

/**
 * Build webhook trigger data from params
 */
function buildWebhookTriggerData(params: any): any {
  const authType = params.authType || 'none';
  const data: any = {
    httpMethod: params.httpMethod || 'POST',
    authType,
  };

  if (authType === 'basic') {
    data.basicAuth = {
      username: params.basicUsername || '',
      password: params.basicPassword || '',
    };
  } else if (authType === 'header') {
    data.headerAuth = {
      headerName: params.authHeaderName || 'X-API-Key',
      headerValue: params.authHeaderValue || '',
    };
  } else if (authType === 'jwt') {
    data.jwtAuth = {
      secretKey: params.jwtSecretKey || '',
      algorithm: params.jwtAlgorithm || 'HS256',
    };
  }

  return data;
}

/**
 * Coerce a select/multiselect/radio/checkboxGroup `options` array into the
 * canonical [{id, label, value}] shape the inspector and TriggerPanel preview
 * key on.
 *
 * Hardening for plans persisted before the V161 doc fix landed: an LLM could
 * have written `options: ["a", "b"]` (the natural shape when no schema is
 * specified). Without this coercion the inspector renders empty inputs and
 * the runtime preview emits `<SelectItem value={undefined}>{undefined}</…>`.
 *
 * Accepts: string shorthand, {label, value} objects (with optional id).
 * Drops malformed entries silently - the inspector will still show the
 * remaining good ones rather than blanking the whole field.
 */
export function normalizeFieldOptions(raw: unknown): Array<{ id: string; label: string; value: string }> {
  if (!Array.isArray(raw)) return [];
  const out: Array<{ id: string; label: string; value: string }> = [];
  for (let i = 0; i < raw.length; i++) {
    const item = raw[i];
    if (typeof item === 'string' && item.length > 0) {
      out.push({ id: `opt-${i}`, label: item, value: item });
      continue;
    }
    if (item && typeof item === 'object') {
      const o = item as { id?: unknown; label?: unknown; value?: unknown };
      const label = typeof o.label === 'string' ? o.label : '';
      const value = typeof o.value === 'string' ? o.value : '';
      if (label.length === 0 || value.length === 0) continue;
      const id = typeof o.id === 'string' && o.id.length > 0 ? o.id : `opt-${i}`;
      out.push({ id, label, value });
    }
  }
  return out;
}

/**
 * Coerce a single form-field map into the inspector's expected shape: ensure
 * stable `id`, normalize options for option-bearing types.
 */
export function normalizeFormField(field: any, index: number): any {
  if (!field || typeof field !== 'object') return field;
  const normalized: any = { ...field };
  if (typeof normalized.id !== 'string' || normalized.id.length === 0) {
    normalized.id = `field-${index}`;
  }
  const fieldType = typeof normalized.type === 'string' ? normalized.type : '';
  if (['select', 'multiselect', 'radio', 'checkboxGroup'].includes(fieldType)) {
    normalized.options = normalizeFieldOptions(normalized.options);
  }
  return normalized;
}

/**
 * Build form trigger data from params
 */
function buildFormTriggerData(params: any): any {
  const authType = params.authType || 'none';
  const rawFields: unknown = params.fields;
  const fields = Array.isArray(rawFields)
    ? rawFields.map((f, i) => normalizeFormField(f, i))
    : [];
  const data: any = {
    title: params.formTitle || '',
    description: params.formDescription || '',
    authType,
    submitButtonText: params.submitButtonText || 'Submit',
    fields,
  };

  if (authType === 'basic') {
    data.basicAuth = {
      username: params.basicUsername || '',
      password: params.basicPassword || '',
    };
  }

  return data;
}

/**
 * Determine the data.id based on trigger type
 */
function getDataIdForTrigger(trigger: TriggerFromPlan, hasDataSourceData: boolean, nodeId: string): string {
  const suffix = `${Date.now()}-${Math.random().toString(36).substr(2, 5)}`;
  switch (trigger.type) {
    case 'manual':
      return `manual-trigger-${suffix}`;
    case 'chat':
      return `chat-trigger-${suffix}`;
    case 'webhook':
      return `webhook-trigger-${suffix}`;
    case 'schedule':
      return `schedule-trigger-${suffix}`;
    case 'form':
      return `form-trigger-${suffix}`;
    case 'workflow':
      return `workflows-trigger-${trigger.id}-${suffix}`;
    case 'error':
      // Error trigger stores the parent workflow id in trigger.id (same convention
      // as workflow trigger) so the dispatcher can match. Embed it in the node id
      // so the inspector can resolve the parent workflow name from cache.
      return `error-trigger-${trigger.id}-${suffix}`;
    default:
      return hasDataSourceData ? `tables-trigger-${trigger.id}-${suffix}` : nodeId;
  }
}

/**
 * Create a single trigger node from plan data
 */
export async function createTriggerNode(
  trigger: TriggerFromPlan,
  tenantId: string,
  currentX: number,
  currentY: number
): Promise<TriggerCreationResult> {
  const nodeId = trigger.graphNodeId || generateNodeId('trigger', trigger.id);

  // Only fetch datasource data for datasource triggers
  let dataSourceData: BuilderNodeData['dataSourceData'] | undefined;
  const isDatasourceTrigger = !['manual', 'chat', 'webhook', 'schedule', 'form', 'workflow', 'error'].includes(trigger.type);

  if (isDatasourceTrigger) {
    const toolData = await ToolDataService.fetchDataSourceData(trigger.id, tenantId);
    dataSourceData = toolData.dataSourceData;
  }

  // Convert trigger params to columnExpressions if datasource
  let columnExpressions: Record<string, string> | undefined;
  let columnLabels: Record<string, string> | undefined;
  if (dataSourceData && trigger.params) {
    columnExpressions = {};
    columnLabels = {};
    for (const [key, value] of Object.entries(trigger.params)) {
      if (typeof value === 'string') {
        columnExpressions[key] = value;
        columnLabels[key] = key;
      } else if (value && typeof value === 'object' && 'template' in value) {
        columnExpressions[key] = (value as any).template;
        columnLabels[key] = key;
      }
    }
  }

  // Parse position
  const { position: triggerPosition, useSavedPosition } = parsePosition(
    trigger.position,
    currentX,
    currentY,
    `trigger ${trigger.id}`
  );

  // Get data ID
  const dataId = getDataIdForTrigger(trigger, !!dataSourceData, nodeId);

  // Build type-specific data
  const params = trigger.params || {};
  let scheduleTriggerData: any;
  let webhookTriggerData: any;
  let formTriggerData: any;
  let workflowData: any;

  if (trigger.type === 'schedule' && trigger.params) {
    scheduleTriggerData = buildScheduleTriggerData(params);
  }
  if (trigger.type === 'webhook' && trigger.params) {
    webhookTriggerData = buildWebhookTriggerData(params);
  }
  if (trigger.type === 'form' && trigger.params) {
    formTriggerData = buildFormTriggerData(params);
  }
  if (trigger.type === 'workflow') {
    workflowData = {
      workflowId: trigger.id,
      workflowName: params.workflowName || trigger.label || `Workflow ${trigger.id}`,
    };
  }
  // Error trigger uses the same workflowData shape as workflow trigger - the parent
  // workflow id sits in trigger.id and the inspector reads workflowData.workflowName
  // to render the "Watched Workflow" card. Without this, after refresh the inspector
  // shows "Selected Workflow" placeholder.
  if (trigger.type === 'error') {
    workflowData = {
      workflowId: trigger.id,
      workflowName: params.workflowName || trigger.label || `Workflow ${trigger.id}`,
    };
  }

  // Create the node
  const triggerNode: Node<BuilderNodeData> = {
    id: nodeId,
    type: 'flowNode',
    position: triggerPosition,
    positionAbsolute: useSavedPosition ? triggerPosition : undefined,
    data: {
      id: dataId,
      label: trigger.label || `Trigger ${trigger.id}`,
      description: trigger.description,
      kind: 'entry',
      dataSourceData: dataSourceData ? {
        ...dataSourceData,
        columnExpressions,
        columnLabels,
      } : undefined,
      ...(scheduleTriggerData ? { scheduleTriggerData } : {}),
      ...(webhookTriggerData ? { webhookTriggerData } : {}),
      ...(formTriggerData ? { formTriggerData } : {}),
      ...(workflowData ? { workflowData } : {}),
      // Restore standalone webhook reference from saved plan
      ...((params as any).webhookId ? { standaloneWebhookId: (params as any).webhookId } : {}),
    } as BuilderNodeData,
  };

  return {
    node: triggerNode,
    mappings: {
      triggerId: trigger.id,
      normalizedLabel: trigger.label ? normalizeLabel(trigger.label) : undefined,
    },
    incrementY: !useSavedPosition,
  };
}

/**
 * Create all trigger nodes from plan
 */
export async function createTriggerNodes(
  triggers: TriggerFromPlan[],
  tenantId: string,
  startX: number,
  startY: number
): Promise<{
  nodes: Node<BuilderNodeData>[];
  triggerIdToNodeIdMap: Map<string, string>;
  nextY: number;
}> {
  const nodes: Node<BuilderNodeData>[] = [];
  const triggerIdToNodeIdMap = new Map<string, string>();
  let currentY = startY;

  for (const trigger of triggers) {
    const result = await createTriggerNode(trigger, tenantId, startX, currentY);

    nodes.push(result.node);

    // Map trigger ID to node ID
    triggerIdToNodeIdMap.set(result.mappings.triggerId, result.node.id);
    if (result.mappings.normalizedLabel) {
      triggerIdToNodeIdMap.set(result.mappings.normalizedLabel, result.node.id);
    }

    // Increment Y if position wasn't saved
    if (result.incrementY) {
      currentY += NODE_SPACING.y;
    }
  }

  return {
    nodes,
    triggerIdToNodeIdMap,
    nextY: currentY,
  };
}
