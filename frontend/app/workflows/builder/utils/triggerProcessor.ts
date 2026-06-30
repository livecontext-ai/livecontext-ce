import type { Node } from 'reactflow';
import type { BuilderNodeData } from '../types';
import type { PlanGeneratorContext } from './planGeneratorContext';
import { normalizeLabel } from './labelNormalizer';
import { getNodePosition, hasKeys, convertParamExpressionsToInputs } from './planHelpers';

/**
 * Processes all trigger nodes and adds them to the plan.
 */
export function processTriggers(ctx: PlanGeneratorContext): void {
  const triggerNodes = filterTriggerNodes(ctx.nodes);

  console.log('[processTriggers] Total nodes:', ctx.nodes.length);
  console.log('[processTriggers] Found trigger nodes:', triggerNodes.length);
  triggerNodes.forEach((node, i) => {
    console.log(`[processTriggers] Trigger node[${i}]: id=${node.id}, data.id=${node.data.id}, label=${node.data.label}`);
  });

  triggerNodes.forEach((node) => {
    const trigger = buildTrigger(node, ctx);
    console.log('[processTriggers] Built trigger:', trigger.id, trigger.label, trigger.type);
    ctx.plan.triggers.push(trigger);
    ctx.triggerPlanByNodeId.set(node.id, trigger);
  });

  console.log('[processTriggers] Final triggers count in plan:', ctx.plan.triggers.length);
}

/**
 * Filters nodes to get only trigger nodes (excluding CRUD nodes).
 */
function filterTriggerNodes(nodes: Node<BuilderNodeData>[]): Node<BuilderNodeData>[] {
  return nodes.filter((node) => {
    // Exclude CRUD nodes (they have dataSourceData but are table operations, not triggers)
    const crudOperation = (node.data as any).dataSourceData?.crudOperation;
    if (
      crudOperation &&
      ['create-row', 'create-column', 'read-row', 'update-row', 'delete-row', 'find-row'].includes(crudOperation)
    ) {
      return false;
    }

    // Exclude sub_workflow nodes (they carry workflowData but are core nodes, not triggers)
    const nodeDataId = node.data.id || '';
    if (
      (node.data as any).kind === 'sub_workflow' ||
      nodeDataId === 'sub_workflow' ||
      nodeDataId.startsWith('sub_workflow-') ||
      nodeDataId.startsWith('sub-workflow-')
    ) {
      return false;
    }

    // Explicit trigger type checks (most reliable)
    const isManualTrigger = nodeDataId === 'manual-trigger' || nodeDataId.startsWith('manual-trigger-');
    const isChatTrigger = nodeDataId === 'chat-trigger' || nodeDataId.startsWith('chat-trigger-');
    const isWebhookTrigger = nodeDataId === 'webhook-trigger' || nodeDataId.startsWith('webhook-trigger-');
    const isScheduleTrigger = nodeDataId === 'schedule-trigger' || nodeDataId.startsWith('schedule-trigger-');
    const isFormTrigger = nodeDataId === 'form-trigger' || nodeDataId.startsWith('form-trigger-');
    const isTablesTrigger = nodeDataId === 'tables-trigger' || nodeDataId.startsWith('tables-trigger-');
    const isWorkflowsTrigger = nodeDataId === 'workflows-trigger' || nodeDataId.startsWith('workflows-trigger-');
    const isErrorTrigger = nodeDataId === 'error-trigger' || nodeDataId.startsWith('error-trigger-');

    return (
      // Explicit trigger types
      isManualTrigger ||
      isChatTrigger ||
      isWebhookTrigger ||
      isScheduleTrigger ||
      isFormTrigger ||
      isTablesTrigger ||
      isWorkflowsTrigger ||
      isErrorTrigger ||
      // Fallback: datasource/workflow data or entry kind
      node.data.dataSourceData ||
      (node.data as any).workflowData ||
      node.data.kind === 'entry'
    );
  });
}

/**
 * Builds a trigger object from a node.
 */
function buildTrigger(node: Node<BuilderNodeData>, ctx: PlanGeneratorContext): any {
  const dataSourceData = node.data.dataSourceData;
  const workflowData = (node.data as any).workflowData;
  const nodeDataId = node.data.id || '';

  const isManualTrigger = nodeDataId === 'manual-trigger' || nodeDataId.startsWith('manual-trigger-');
  const isChatTrigger = nodeDataId === 'chat-trigger' || nodeDataId.startsWith('chat-trigger-');
  const isWebhookTrigger = nodeDataId === 'webhook-trigger' || nodeDataId.startsWith('webhook-trigger-');
  const isScheduleTrigger = nodeDataId === 'schedule-trigger' || nodeDataId.startsWith('schedule-trigger-');
  const isWorkflowsTrigger = nodeDataId === 'workflows-trigger' || nodeDataId.startsWith('workflows-trigger-');
  const isErrorTrigger = nodeDataId === 'error-trigger' || nodeDataId.startsWith('error-trigger-');
  const isFormTrigger = nodeDataId === 'form-trigger' || nodeDataId.startsWith('form-trigger-');

  // Build trigger slug from label
  const triggerSlug = normalizeLabel(
    node.data.label ||
      node.data.id
        ?.replace('tables-trigger-', '')
        .replace('manual-trigger-', '')
        .replace('chat-trigger-', '')
        .replace('webhook-trigger-', '')
        .replace('schedule-trigger-', '')
        .replace('workflows-trigger-', '')
        .replace('error-trigger-', '')
        .replace('form-trigger-', '') ||
      node.id
  );
  ctx.triggerSlugMap.set(node.id, triggerSlug);

  // Determine trigger ID
  const triggerId = dataSourceData?.dataSourceId
    ? String(dataSourceData.dataSourceId)
    : workflowData?.workflowId
      ? String(workflowData.workflowId)
      : triggerSlug;

  // Determine trigger type
  let triggerType = 'datasource';
  if (isManualTrigger) triggerType = 'manual';
  else if (isChatTrigger) triggerType = 'chat';
  else if (isWebhookTrigger) triggerType = 'webhook';
  else if (isScheduleTrigger) triggerType = 'schedule';
  else if (isWorkflowsTrigger) triggerType = 'workflow';
  else if (isErrorTrigger) triggerType = 'error';
  else if (isFormTrigger) triggerType = 'form';

  const trigger: any = {
    id: triggerId,
    type: triggerType,
    label: node.data.label || triggerSlug,
    graphNodeId: node.id,
  };

  // Add position
  const nodePosition = getNodePosition(node);
  if (nodePosition) {
    trigger.position = nodePosition;
  }

  // Build trigger params
  const triggerParams = buildTriggerParams(node, dataSourceData, isManualTrigger);
  if (hasKeys(triggerParams)) {
    trigger.params = triggerParams;
  }

  // Add chat match config + standalone endpoint reference
  if (isChatTrigger) {
    trigger.chatMatch = buildChatMatchConfig(node);
    const standaloneChatEndpointId = (node.data as any).standaloneChatEndpointId;
    if (standaloneChatEndpointId) {
      trigger.params = {
        ...trigger.params,
        chatEndpointId: standaloneChatEndpointId,
      };
    }
  }

  // Add schedule config
  if (isScheduleTrigger) {
    addScheduleConfig(node, trigger);
  }

  // Add webhook config
  if (isWebhookTrigger) {
    addWebhookConfig(node, trigger);
  }

  // Add form config + standalone endpoint reference
  if (isFormTrigger) {
    addFormConfig(node, trigger);
    const standaloneFormEndpointId = (node.data as any).standaloneFormEndpointId;
    if (standaloneFormEndpointId) {
      trigger.params = {
        ...trigger.params,
        formEndpointId: standaloneFormEndpointId,
      };
    }
  }

  // Add workflow config
  if (isWorkflowsTrigger) {
    addWorkflowConfig(node, trigger);
  }

  // Error trigger config - same shape as workflow trigger: the parent workflow id
  // is stored in trigger.id so the dispatcher's WorkflowRepository.findByErrorTrigger
  // can match it. The display name is persisted in params.workflowName so the inspector
  // can show "On <name> failure" without re-fetching.
  if (isErrorTrigger) {
    addWorkflowConfig(node, trigger);
  }

  // Add datasource (tables) trigger config - event_types + optional filter
  if (triggerType === 'datasource') {
    addDatasourceConfig(dataSourceData, trigger);
  }

  return trigger;
}

/**
 * Adds datasource (event-driven tables trigger) configuration.
 * Persists event_types + optional filter into trigger.params.
 */
function addDatasourceConfig(dataSourceData: any, trigger: any): void {
  const allEventTypes: Array<'row_created' | 'row_updated' | 'row_deleted'> = [
    'row_created',
    'row_updated',
    'row_deleted',
  ];
  const eventTypes = Array.isArray(dataSourceData?.eventTypes) && dataSourceData.eventTypes.length > 0
    ? dataSourceData.eventTypes
    : allEventTypes;

  trigger.params = {
    ...trigger.params,
    event_types: eventTypes,
  };

  if (dataSourceData?.dataSourceId != null) {
    trigger.params.datasource_id = dataSourceData.dataSourceId;
  }

  const filter = dataSourceData?.filter;
  if (filter && typeof filter === 'object' && filter.column && filter.operator) {
    trigger.params.filter = {
      column: filter.column,
      operator: filter.operator,
      ...(filter.value !== undefined ? { value: filter.value } : {}),
    };
  }
}

/**
 * Builds trigger params from node data.
 */
function buildTriggerParams(
  node: Node<BuilderNodeData>,
  dataSourceData: any,
  isManualTrigger: boolean
): Record<string, any> {
  let triggerParams: Record<string, any> = {};

  if (dataSourceData?.columnExpressions) {
    const columnExpressions = dataSourceData.columnExpressions;
    const columnLabels = dataSourceData.columnLabels || {};

    for (const [normalizedField, expression] of Object.entries(columnExpressions)) {
      const mappedLabel = columnLabels[normalizedField] || normalizedField;
      const normalizedLabelSlug = normalizeLabel(mappedLabel);

      if (expression && (expression as string).trim()) {
        triggerParams[normalizedLabelSlug] = expression;
      }
    }
  } else if (node.data.paramExpressions) {
    triggerParams = convertParamExpressionsToInputs(node.data.paramExpressions);
  }

  // Add custom fields from manual trigger
  if (isManualTrigger) {
    const manualTriggerData = (node.data as any).manualTriggerData;
    if (manualTriggerData?.customFields && Array.isArray(manualTriggerData.customFields)) {
      for (const field of manualTriggerData.customFields) {
        if (field.name && field.name.trim()) {
          const normalizedFieldName = normalizeLabel(field.name);
          triggerParams[normalizedFieldName] = field.value || '';
        }
      }
    }
  }

  return triggerParams;
}

/**
 * Builds chat match configuration.
 */
function buildChatMatchConfig(node: Node<BuilderNodeData>): any {
  const chatTriggerData = (node.data as any).chatTriggerData;

  if (!chatTriggerData) {
    return {
      type: 'any',
      value: null,
      caseSensitive: false,
      trimPrefix: true,
      trimSuffix: true,
    };
  }

  const matchType = chatTriggerData.matchType || 'any';
  let backendType: string;
  let value: string | null = null;

  switch (matchType) {
    case 'any':
      backendType = 'any';
      break;
    case 'startsWith':
      backendType = 'starts_with';
      value = chatTriggerData.pattern || '';
      break;
    case 'endsWith':
      backendType = 'ends_with';
      value = chatTriggerData.pattern || '';
      break;
    case 'contains':
      backendType = 'contains';
      value = chatTriggerData.pattern || '';
      break;
    case 'equals':
      backendType = 'equals';
      value = chatTriggerData.pattern || '';
      break;
    case 'regex':
      backendType = 'regex';
      value = chatTriggerData.pattern || '';
      break;
    case 'command':
      backendType = 'starts_with';
      value = '/' + (chatTriggerData.pattern || '');
      break;
    default:
      backendType = 'any';
  }

  return {
    type: backendType,
    value,
    caseSensitive: chatTriggerData.caseSensitive || false,
    trimPrefix: true,
    trimSuffix: true,
  };
}

/**
 * Adds schedule configuration to trigger.
 */
function addScheduleConfig(node: Node<BuilderNodeData>, trigger: any): void {
  const scheduleTriggerData = (node.data as any).scheduleTriggerData;

  trigger.params = {
    ...trigger.params,
    cron: scheduleTriggerData?.cronExpression || '0 * * * *',
    timezone: scheduleTriggerData?.timezone || 'UTC',
    maxExecutions: scheduleTriggerData?.maxExecutions || null,
    enabled: true,
  };

  // Persist standalone schedule reference for round-trip
  const standaloneScheduleId = (node.data as any).standaloneScheduleId;
  if (standaloneScheduleId) {
    trigger.params.scheduleId = standaloneScheduleId;
  }
}

/**
 * Adds webhook configuration to trigger.
 * Serializes webhookTriggerData (HTTP method, auth type, auth credentials) to trigger.params.
 * If a standalone webhook is selected, only the webhookId is stored (config lives on the webhook entity).
 */
function addWebhookConfig(node: Node<BuilderNodeData>, trigger: any): void {
  const standaloneWebhookId = (node.data as any).standaloneWebhookId;

  // If using a standalone webhook, store only the reference
  if (standaloneWebhookId) {
    trigger.params = {
      ...trigger.params,
      webhookId: standaloneWebhookId,
    };
    return;
  }

  const webhookTriggerData = (node.data as any).webhookTriggerData;

  // Default values if no config exists
  const httpMethod = webhookTriggerData?.httpMethod || 'POST';
  const authType = webhookTriggerData?.authType || 'none';

  trigger.params = {
    ...trigger.params,
    httpMethod,
    authType,
  };

  // Add auth-specific fields based on auth type
  if (authType === 'basic' && webhookTriggerData?.basicAuth) {
    trigger.params.basicUsername = webhookTriggerData.basicAuth.username || '';
    trigger.params.basicPassword = webhookTriggerData.basicAuth.password || '';
  } else if (authType === 'header' && webhookTriggerData?.headerAuth) {
    trigger.params.authHeaderName = webhookTriggerData.headerAuth.headerName || 'X-API-Key';
    trigger.params.authHeaderValue = webhookTriggerData.headerAuth.headerValue || '';
  } else if (authType === 'jwt' && webhookTriggerData?.jwtAuth) {
    trigger.params.jwtSecretKey = webhookTriggerData.jwtAuth.secretKey || '';
    trigger.params.jwtAlgorithm = webhookTriggerData.jwtAuth.algorithm || 'HS256';
  }
}

/**
 * Adds form configuration to trigger.
 * Serializes formTriggerData (title, description, fields, auth) to trigger.params.
 */
function addFormConfig(node: Node<BuilderNodeData>, trigger: any): void {
  const formTriggerData = (node.data as any).formTriggerData;

  if (!formTriggerData) {
    trigger.params = {
      ...trigger.params,
      formTitle: '',
      formDescription: '',
      authType: 'none',
      submitButtonText: 'Submit',
      fields: [],
    };
    return;
  }

  trigger.params = {
    ...trigger.params,
    formTitle: formTriggerData.title || '',
    formDescription: formTriggerData.description || '',
    authType: formTriggerData.authType || 'none',
    submitButtonText: formTriggerData.submitButtonText || 'Submit',
    fields: formTriggerData.fields || [],
  };

  // Add basic auth if configured
  if (formTriggerData.authType === 'basic' && formTriggerData.basicAuth) {
    trigger.params.basicUsername = formTriggerData.basicAuth.username || '';
    trigger.params.basicPassword = formTriggerData.basicAuth.password || '';
  }
}

/**
 * Adds workflow configuration to trigger.
 * Serializes workflowData (workflowName) to trigger.params for round-trip preservation.
 */
function addWorkflowConfig(node: Node<BuilderNodeData>, trigger: any): void {
  const workflowData = (node.data as any).workflowData;

  if (workflowData) {
    trigger.params = {
      ...trigger.params,
      workflowName: workflowData.workflowName || '',
    };
  }
}
