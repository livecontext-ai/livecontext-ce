/**
 * NodeCreationService - Orchestrates node creation from plan data
 * Delegates to specialized creators for each node type
 */

import type { Node } from 'reactflow';
import type { BuilderNodeData, LoopChildDescriptor } from '../../types';
import { ToolDataService, type ToolDataResult } from './ToolDataService';
import type { WorkflowPlan } from './PlanParserService';
import { normalizeLabel, coreKey } from '../../utils/labelNormalizer';
import { createInterfaceNodes } from './InterfaceNodeCreator';
import { createStepNodes } from './StepNodeCreator';
import { createAgentNodes } from './AgentNodeCreator';
import { createNoteNodes } from './NoteNodeCreator';
import { extractConditionsFromIfLogic } from './CoreNodeUtils';
import {
  parsePosition,
  inputToParamExpressions,
  makeInnerLoopNodeId,
  normalizeWhereCondition,
  NODE_SPACING,
  INITIAL_POSITION,
} from './nodeCreationHelpers';
import { createDefaultDecisionConditions, createDefaultSwitchCases, createDefaultOptionChoices, createDefaultApprovalOutputs } from '../../types';
import { nodeRegistry } from '../../registry/nodeRegistry';
import { sanitizeNodePolicy } from '../../utils/nodePolicy';

export interface NodeCreationResult {
  nodes: Node<BuilderNodeData>[];
  labelToNodeIdMap: Map<string, string>;
  triggerIdToNodeIdMap: Map<string, string>;
  interfaceIdToNodeIdMap: Map<string, string>;
  interfaceLabelToNodeIdMap: Map<string, string>;
}

export class NodeCreationService {
  /**
   * Collect all tool IDs for batch fetching
   */
  private static collectAllToolIds(plan: WorkflowPlan): string[] {
    const toolIds: string[] = [];
    const loopNodeIds = new Set<string>();

    if (plan.cores) {
      plan.cores.forEach(cn => {
        if (cn.type === 'loop' && cn.id) loopNodeIds.add(cn.id);
      });
    }

    for (const step of plan.mcps) {
      if (step.id === 'agent') continue;
      if (step.id.startsWith('crud/')) continue;
      if (step.id === '__passthrough__' ||
          step.label?.startsWith('gateway_to_while_') ||
          step.label?.startsWith('__gateway_to_while_') ||
          step.label?.startsWith('__gateway__') ||
          step.label?.startsWith('__merge__') ||
          step.label?.startsWith('__sync__')) continue;

      const parentLoopId = (step as any)?.parentLoopId;
      if (parentLoopId && loopNodeIds.has(parentLoopId)) {
        toolIds.push(step.id);
        continue;
      }
      toolIds.push(step.id);
    }

    return toolIds;
  }

  /**
   * Create loop children for a loop node
   */
  private static async createLoopChildren(
    loopNodeId: string,
    loopNode: Node<BuilderNodeData>,
    plan: WorkflowPlan
  ): Promise<{ descriptors: LoopChildDescriptor[] }> {
    const loopSteps = plan.mcps.filter((step) => {
      const parentLoopId = (step as any).parentLoopId;
      return parentLoopId === loopNodeId;
    });

    if (loopSteps.length === 0) return { descriptors: [] };

    if (!Array.isArray(loopNode.data.loopChildren)) {
      loopNode.data.loopChildren = [];
    }

    const stepsToCreate: Array<{ step: typeof loopSteps[0]; index: number }> = [];
    for (let i = 0; i < loopSteps.length; i++) {
      const planStep = loopSteps[i];
      const stepLabel = planStep.label;
      const childNodeId = makeInnerLoopNodeId(loopNodeId, stepLabel, i);
      const existingDescriptor = loopNode.data.loopChildren.find(d => d.id === childNodeId);
      if (!existingDescriptor) {
        stepsToCreate.push({ step: planStep, index: i });
      }
    }

    if (stepsToCreate.length === 0) return { descriptors: [] };

    const toolDataResults: ToolDataResult[] = [];
    for (const { step } of stepsToCreate) {
      const toolId = step.id || step.label;
      const cachedResult = ToolDataService.getFromBatchCache(toolId);
      if (cachedResult) {
        toolDataResults.push(cachedResult);
      } else {
        try {
          const result = await ToolDataService.fetchToolDataFromPlan(toolId);
          toolDataResults.push(result);
        } catch (error) {
          console.warn(`[NodeCreation] Failed to fetch tool data for ${step.label}:`, error);
          toolDataResults.push({} as ToolDataResult);
        }
      }
    }

    const descriptors: LoopChildDescriptor[] = stepsToCreate.map(({ step, index }, fetchIndex) => {
      const stepLabel = step.label;
      const childNodeId = makeInnerLoopNodeId(loopNodeId, stepLabel, index);
      const toolDataResult = toolDataResults[fetchIndex];

      return {
        id: childNodeId,
        label: step.label || stepLabel,
        kind: 'action' as const,
        nodeType: 'flowNode' as const,
        toolData: toolDataResult.toolData,
        apiData: toolDataResult.apiData,
        paramExpressions: inputToParamExpressions((step as any).params),
      };
    });

    return { descriptors };
  }

  /**
   * Create trigger nodes inline (simpler than external creator for this case)
   */
  private static async createTriggerNodesInline(
    triggers: WorkflowPlan['triggers'],
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
      const nodeId = trigger.graphNodeId || `trigger-${trigger.id}-${Date.now()}-${Math.random().toString(36).substr(2, 9)}`;
      triggerIdToNodeIdMap.set(trigger.id, nodeId);
      if (trigger.label) {
        triggerIdToNodeIdMap.set(normalizeLabel(trigger.label), nodeId);
      }

      // Fetch datasource data for datasource triggers
      let dataSourceData: BuilderNodeData['dataSourceData'] | undefined;
      if (!['manual', 'chat', 'webhook', 'schedule', 'form', 'workflow', 'error'].includes(trigger.type)) {
        const toolData = await ToolDataService.fetchDataSourceData(trigger.id, tenantId);
        dataSourceData = toolData.dataSourceData;
      }

      // Extract event-driven config (event_types, filter) from trigger.params.
      // Table triggers no longer use column mappings - rows flow through via row/previous_row
      // on event fires (see TableTriggerNodeSpec + TriggerPayloadBuilder.promoteEventFields).
      let triggerEventTypes: Array<'row_created' | 'row_updated' | 'row_deleted'> | undefined;
      let triggerFilter: NonNullable<BuilderNodeData['dataSourceData']>['filter'] | undefined;
      if (dataSourceData && trigger.params) {
        const rawEvents = (trigger.params as any).event_types ?? (trigger.params as any).eventTypes;
        if (Array.isArray(rawEvents)) {
          triggerEventTypes = rawEvents.filter((e): e is 'row_created' | 'row_updated' | 'row_deleted' =>
            e === 'row_created' || e === 'row_updated' || e === 'row_deleted'
          );
        }
        const rawFilter = (trigger.params as any).filter;
        if (rawFilter && typeof rawFilter === 'object' && rawFilter.column && rawFilter.operator) {
          triggerFilter = {
            column: String(rawFilter.column),
            operator: rawFilter.operator,
            ...(rawFilter.value !== undefined ? { value: rawFilter.value } : {}),
          };
        }
      }

      // Parse position
      const { position: triggerPosition, useSavedPosition } = parsePosition(
        trigger.position,
        startX,
        currentY,
        `trigger ${trigger.id}`
      );

      // Determine data ID - must be unique per node to avoid update collisions
      const idSuffix = `${Date.now()}-${Math.random().toString(36).substr(2, 5)}`;
      let dataId: string;
      if (trigger.type === 'manual') dataId = `manual-trigger-${idSuffix}`;
      else if (trigger.type === 'chat') dataId = `chat-trigger-${idSuffix}`;
      else if (trigger.type === 'webhook') dataId = `webhook-trigger-${idSuffix}`;
      else if (trigger.type === 'schedule') dataId = `schedule-trigger-${idSuffix}`;
      else if (trigger.type === 'form') dataId = `form-trigger-${idSuffix}`;
      else if (trigger.type === 'workflow') dataId = `workflows-trigger-${trigger.id}-${idSuffix}`;
      else if (trigger.type === 'error') dataId = `error-trigger-${trigger.id}-${idSuffix}`;
      else dataId = dataSourceData ? `tables-trigger-${trigger.id}-${idSuffix}` : nodeId;

      // Build type-specific data
      const params = trigger.params || {};
      let scheduleTriggerData: any, webhookTriggerData: any, formTriggerData: any, workflowData: any, chatTriggerData: any;

      if (trigger.type === 'chat') {
        // Reverse the mapping done in triggerProcessor.buildChatMatchConfig:
        //   frontend matchType  →  backend type (stored in plan)
        //     any               →  any
        //     startsWith        →  starts_with
        //     endsWith          →  ends_with
        //     contains          →  contains
        //     equals            →  equals
        //     regex             →  regex
        //     command           →  starts_with   (value prefixed with '/')
        //
        // Going backend → frontend: starts_with with a '/'-prefixed value is
        // rendered back as 'command' so the inspector shows the command UI.
        const chatMatch = (trigger as any).chatMatch as
          | { type?: string; value?: string | null; caseSensitive?: boolean }
          | undefined;
        const backendType = (chatMatch?.type || 'any').toLowerCase();
        const rawValue = chatMatch?.value ?? '';

        let matchType: string;
        let pattern: string;
        switch (backendType) {
          case 'starts_with':
          case 'startswith':
            if (typeof rawValue === 'string' && rawValue.startsWith('/')) {
              matchType = 'command';
              pattern = rawValue.slice(1);
            } else {
              matchType = 'startsWith';
              pattern = rawValue ?? '';
            }
            break;
          case 'ends_with':
          case 'endswith':
            matchType = 'endsWith';
            pattern = rawValue ?? '';
            break;
          case 'contains':
            matchType = 'contains';
            pattern = rawValue ?? '';
            break;
          case 'equals':
            matchType = 'equals';
            pattern = rawValue ?? '';
            break;
          case 'regex':
            matchType = 'regex';
            pattern = rawValue ?? '';
            break;
          default:
            matchType = 'any';
            pattern = '';
        }

        chatTriggerData = {
          matchType,
          pattern,
          caseSensitive: chatMatch?.caseSensitive === true,
        };
      }

      if (trigger.type === 'schedule' && params) {
        // Inspector derives the frequency dropdown selection from cronExpression
        // at render time, so we only persist the canonical fields the plan owns.
        scheduleTriggerData = {
          cronExpression: (params as any).cron || '0 * * * *',
          timezone: (params as any).timezone || 'UTC',
          maxExecutions: (params as any).maxExecutions || null,
        };
      }

      // Restore standalone schedule ID from params (round-trip)
      const standaloneScheduleId = trigger.type === 'schedule' && (params as any).scheduleId
        ? (params as any).scheduleId : undefined;

      if (trigger.type === 'webhook' && params) {
        const authType = (params as any).authType || 'none';
        webhookTriggerData = { httpMethod: (params as any).httpMethod || 'POST', authType };
        if (authType === 'basic') {
          webhookTriggerData.basicAuth = { username: (params as any).basicUsername || '', password: (params as any).basicPassword || '' };
        } else if (authType === 'header') {
          webhookTriggerData.headerAuth = { headerName: (params as any).authHeaderName || 'X-API-Key', headerValue: (params as any).authHeaderValue || '' };
        } else if (authType === 'jwt') {
          webhookTriggerData.jwtAuth = { secretKey: (params as any).jwtSecretKey || '', algorithm: (params as any).jwtAlgorithm || 'HS256' };
        }
      }

      if (trigger.type === 'form' && params) {
        const authType = (params as any).authType || 'none';
        formTriggerData = {
          title: (params as any).formTitle || '',
          description: (params as any).formDescription || '',
          authType,
          submitButtonText: (params as any).submitButtonText || 'Submit',
          fields: (params as any).fields || [],
        };
        if (authType === 'basic') {
          formTriggerData.basicAuth = { username: (params as any).basicUsername || '', password: (params as any).basicPassword || '' };
        }
      }

      if (trigger.type === 'workflow') {
        workflowData = {
          workflowId: trigger.id,
          workflowName: (params as any)?.workflowName || trigger.label || `Workflow ${trigger.id}`,
        };
      }
      // Error trigger reuses the workflowData shape so the inspector renders the
      // "Watched Workflow" card from workflowData.workflowName after refresh.
      if (trigger.type === 'error') {
        workflowData = {
          workflowId: trigger.id,
          workflowName: (params as any)?.workflowName || trigger.label || `Workflow ${trigger.id}`,
        };
      }

      const triggerNode: Node<BuilderNodeData> = {
        id: nodeId,
        type: 'flowNode',
        position: triggerPosition,
        positionAbsolute: useSavedPosition ? triggerPosition : undefined,
        data: {
          id: dataId,
          label: trigger.label || `Trigger ${trigger.id}`,
          description: (trigger as any).description,
          kind: 'entry',
          dataSourceData: dataSourceData
            ? {
                ...dataSourceData,
                ...(triggerEventTypes && triggerEventTypes.length > 0 ? { eventTypes: triggerEventTypes } : {}),
                ...(triggerFilter ? { filter: triggerFilter } : {}),
              }
            : undefined,
          ...(scheduleTriggerData ? { scheduleTriggerData } : {}),
          ...(webhookTriggerData ? { webhookTriggerData } : {}),
          ...(formTriggerData ? { formTriggerData } : {}),
          ...(workflowData ? { workflowData } : {}),
          ...(chatTriggerData ? { chatTriggerData } : {}),
          // Restore standalone endpoint references from saved plan
          ...((params as any).webhookId ? { standaloneWebhookId: (params as any).webhookId } : {}),
          ...((params as any).chatEndpointId ? { standaloneChatEndpointId: (params as any).chatEndpointId } : {}),
          ...((params as any).formEndpointId ? { standaloneFormEndpointId: (params as any).formEndpointId } : {}),
          ...(standaloneScheduleId ? { standaloneScheduleId } : {}),
        } as BuilderNodeData,
      };

      nodes.push(triggerNode);

      if (!useSavedPosition) currentY += NODE_SPACING.y;
    }

    return { nodes, triggerIdToNodeIdMap, nextY: currentY };
  }

  /**
   * Create control nodes inline
   */
  private static createCoreNodesInline(
    cores: WorkflowPlan['cores'],
    planSteps: WorkflowPlan['mcps'],
    startX: number,
    startY: number
  ): {
    nodes: Node<BuilderNodeData>[];
    labelToNodeIdMap: Map<string, string>;
    nextY: number;
  } {
    const nodes: Node<BuilderNodeData>[] = [];
    const labelToNodeIdMap = new Map<string, string>();
    let currentY = startY;

    if (!cores) return { nodes, labelToNodeIdMap, nextY: currentY };

    for (const cn of cores) {
      const { position, useSavedPosition } = parsePosition(cn.position, startX, currentY);

      if (cn.type === 'decision') {
        const nodeId = cn.graphNodeId || cn.id || `decision-${Date.now()}-${Math.random().toString(36).substr(2, 9)}`;
        const decisionConditions = cn.decisionConditions?.length
          ? cn.decisionConditions.map((c, i) => ({
              id: `${nodeId}-${c.type}${c.type === 'elseif' ? `-${i}` : ''}`,
              type: c.type as 'if' | 'elseif' | 'else',
              label: c.label,
              expression: c.expression || '',
            }))
          : createDefaultDecisionConditions(nodeId);

        nodes.push({
          id: nodeId,
          type: 'decisionNode',
          position,
          positionAbsolute: position,
          data: { id: nodeId, label: cn.label || 'Decision', kind: 'decision', decisionConditions },
        });
        labelToNodeIdMap.set(cn.label || nodeId, nodeId);
        const normalizedDecision = normalizeLabel(cn.label || nodeId);
        if (normalizedDecision) labelToNodeIdMap.set(normalizedDecision, nodeId);
      } else if (cn.type === 'switch') {
        const nodeId = cn.graphNodeId || cn.id || `switch-${Date.now()}-${Math.random().toString(36).substr(2, 9)}`;
        const switchCases = cn.switchCases?.length
          ? cn.switchCases.map((c, i) => ({
              id: `${nodeId}-${c.type}${c.type === 'case' ? `-${i}` : ''}`,
              type: c.type as 'case' | 'default',
              label: c.label,
              value: c.value || '',
            }))
          : createDefaultSwitchCases(nodeId);

        nodes.push({
          id: nodeId,
          type: 'switchNode',
          position,
          positionAbsolute: position,
          data: { id: nodeId, label: cn.label || 'Switch', kind: 'switch', switchExpression: cn.switchExpression || '', switchCases },
        });
        labelToNodeIdMap.set(cn.label || nodeId, nodeId);
        const normalizedSwitch = normalizeLabel(cn.label || nodeId);
        if (normalizedSwitch) labelToNodeIdMap.set(normalizedSwitch, nodeId);
      } else if (cn.type === 'loop') {
        const nodeId = cn.graphNodeId || cn.id || `while-group-${Date.now()}-${Math.random().toString(36).substr(2, 9)}`;
        nodes.push({
          id: nodeId,
          type: 'whileGroupNode',
          position,
          positionAbsolute: position,
          data: { id: nodeId, label: cn.label || 'While', kind: 'loop', whileCondition: cn.loopCondition ?? (cn as any).condition ?? '', maxIterations: cn.maxIterations ?? 10 },
        });
        labelToNodeIdMap.set(cn.label || nodeId, nodeId);
        const normalizedLoop = normalizeLabel(cn.label || nodeId);
        if (normalizedLoop) labelToNodeIdMap.set(normalizedLoop, nodeId);
      } else if (cn.type === 'split') {
        const nodeId = cn.graphNodeId || cn.id || `split-${Date.now()}-${Math.random().toString(36).substr(2, 9)}`;
        // DEPRECATED: bodySteps are deprecated in the new split system.
        // Split now completes immediately after spawning items, and edges define the flow.
        // This is kept for backward compatibility when importing older workflow plans.
        let bodySteps = (cn as any).bodySteps || [];
        if (bodySteps.length === 0 && planSteps) {
          const splitKey = coreKey(cn.label || '') ?? `core:`;
          bodySteps = planSteps.filter((s: any) => s.parentLoopId === splitKey || s.parentLoopId === nodeId)
            .map((s: any) => ({ label: s.label, stepId: s.id }));
        }
        const loopChildren = bodySteps.map((bs: any, idx: number) => ({
          id: makeInnerLoopNodeId(nodeId, bs.label || bs.stepId, idx),
          label: bs.label || bs.stepId,
          kind: 'action' as const,
          nodeType: 'flowNode' as const,
        }));

        nodes.push({
          id: nodeId,
          type: 'splitNode',
          position,
          positionAbsolute: position,
          data: { id: nodeId, label: cn.label || 'Split', kind: 'split', list: (cn as any).list || (cn as any).listExpression || '', maxItems: (cn as any).maxItems, splitStrategy: (cn as any).splitStrategy || 'stop-on-error', loopChildren },
        });
      } else if (cn.type === 'merge') {
        const nodeId = cn.graphNodeId || cn.id || `merge-${Date.now()}-${Math.random().toString(36).substr(2, 9)}`;
        // Default merge inputs - will be expanded by distributeMergeTargetHandles if more edges exist
        const mergeInputs = [{ id: `${nodeId}-input-1`, label: '' }, { id: `${nodeId}-input-2`, label: '' }];

        nodes.push({
          id: nodeId,
          type: 'mergeNode',
          position,
          positionAbsolute: position,
          data: { id: nodeId, label: cn.label || 'Merge', kind: 'merge', mergeInputs } as any,
        });

        labelToNodeIdMap.set(cn.label || nodeId, nodeId);
        const normalized = normalizeLabel(cn.label || nodeId);
        if (normalized) labelToNodeIdMap.set(normalized, nodeId);
      } else if (cn.type === 'fork') {
        const nodeId = cn.graphNodeId || cn.id || `fork-${Date.now()}-${Math.random().toString(36).substr(2, 9)}`;
        const forkOutputs = cn.forkOutputs?.length
          ? cn.forkOutputs.map((output: any, i: number) => ({
              id: output.id || `${nodeId}-output-${i}`,
              label: output.label || `Branch ${i + 1}`,
            }))
          : [{ id: `${nodeId}-output-0`, label: 'Branch 1' }, { id: `${nodeId}-output-1`, label: 'Branch 2' }];

        nodes.push({
          id: nodeId,
          type: 'forkNode',
          position,
          positionAbsolute: position,
          data: { id: nodeId, label: cn.label || 'Fork', kind: 'fork', forkOutputs } as any,
        });

        labelToNodeIdMap.set(cn.label || nodeId, nodeId);
        const normalized = normalizeLabel(cn.label || nodeId);
        if (normalized) labelToNodeIdMap.set(normalized, nodeId);
      } else if (cn.type === 'transform') {
        const nodeId = cn.graphNodeId || cn.id || `transform-${Date.now()}-${Math.random().toString(36).substr(2, 9)}`;
        const transformMappings = (cn as any).transform?.mappings || [];

        nodes.push({
          id: nodeId,
          type: 'flowNode',
          position,
          positionAbsolute: position,
          data: {
            id: nodeId,  // Use nodeId (starts with 'transform-') for consistent detection
            label: cn.label || 'Transform',
            kind: 'transform',
            transformMappings: transformMappings.map((m: any) => ({
              label: m.label,
              expression: m.expression || '',
            })),
            paramExpressions: inputToParamExpressions((cn as any).params),
          } as any,
        });

        labelToNodeIdMap.set(cn.label || nodeId, nodeId);
        const normalized = normalizeLabel(cn.label || nodeId);
        if (normalized) labelToNodeIdMap.set(normalized, nodeId);
      } else if (cn.type === 'filter') {
        const nodeId = cn.graphNodeId || cn.id || `filter-${Date.now()}-${Math.random().toString(36).substr(2, 9)}`;
        const filterConfig = (cn as any).filter || {};
        const filterConditions = filterConfig.conditions || [];
        const filterMode = filterConfig.mode || 'and';

        nodes.push({
          id: nodeId,
          type: 'flowNode',
          position,
          positionAbsolute: position,
          data: {
            id: nodeId,
            label: cn.label || 'Filter',
            kind: 'filter',
            filterConditions: filterConditions.map((c: any) => ({
              field: c.field || '',
              operator: c.operator || 'equals',
              value: c.value || '',
            })),
            filterMode,
            filterInput: (cn as any).params?.input || (cn as any).filter?.input || (cn as any).input || '',
            paramExpressions: inputToParamExpressions((cn as any).params),
          } as any,
        });

        labelToNodeIdMap.set(cn.label || nodeId, nodeId);
        const normalizedFilter = normalizeLabel(cn.label || nodeId);
        if (normalizedFilter) labelToNodeIdMap.set(normalizedFilter, nodeId);
      } else if (cn.type === 'sort') {
        const nodeId = cn.graphNodeId || cn.id || `sort-${Date.now()}-${Math.random().toString(36).substr(2, 9)}`;
        const sortFields = (cn as any).sort?.fields || [];

        nodes.push({
          id: nodeId,
          type: 'flowNode',
          position,
          positionAbsolute: position,
          data: {
            id: nodeId,
            label: cn.label || 'Sort',
            kind: 'sort',
            sortFields: sortFields.map((f: any) => ({
              field: f.field,
              direction: f.direction || 'asc',
            })),
            sortInput: (cn as any).params?.input || (cn as any).sort?.input || (cn as any).input || '',
            paramExpressions: inputToParamExpressions((cn as any).params),
          } as any,
        });

        labelToNodeIdMap.set(cn.label || nodeId, nodeId);
        const normalizedSort = normalizeLabel(cn.label || nodeId);
        if (normalizedSort) labelToNodeIdMap.set(normalizedSort, nodeId);
      } else if (cn.type === 'limit') {
        const nodeId = cn.graphNodeId || cn.id || `limit-${Date.now()}-${Math.random().toString(36).substr(2, 9)}`;
        const limitConfig = (cn as any).limit || {};

        nodes.push({
          id: nodeId,
          type: 'flowNode',
          position,
          positionAbsolute: position,
          data: {
            id: nodeId,
            label: cn.label || 'Limit',
            kind: 'limit',
            limitCount: limitConfig.count ?? 10,
            limitFrom: limitConfig.from ?? 'first',
            limitOffset: limitConfig.offset ?? 0,
            limitInput: (cn as any).params?.input || limitConfig.input || (cn as any).input || '',
            paramExpressions: inputToParamExpressions((cn as any).params),
          } as any,
        });

        labelToNodeIdMap.set(cn.label || nodeId, nodeId);
        const normalizedLimit = normalizeLabel(cn.label || nodeId);
        if (normalizedLimit) labelToNodeIdMap.set(normalizedLimit, nodeId);
      } else if (cn.type === 'remove_duplicates') {
        const nodeId = cn.graphNodeId || cn.id || `remove-duplicates-${Date.now()}-${Math.random().toString(36).substr(2, 9)}`;
        const removeDuplicatesConfig = (cn as any).removeDuplicates || {};

        nodes.push({
          id: nodeId,
          type: 'flowNode',
          position,
          positionAbsolute: position,
          data: {
            id: nodeId,
            label: cn.label || 'Remove Duplicates',
            kind: 'remove_duplicates',
            dedupFields: removeDuplicatesConfig.fields || [],
            dedupKeep: removeDuplicatesConfig.keep || 'first',
            dedupInput: (cn as any).params?.input || removeDuplicatesConfig.input || (cn as any).input || '',
            paramExpressions: inputToParamExpressions((cn as any).params),
          } as any,
        });

        labelToNodeIdMap.set(cn.label || nodeId, nodeId);
        const normalizedDedup = normalizeLabel(cn.label || nodeId);
        if (normalizedDedup) labelToNodeIdMap.set(normalizedDedup, nodeId);
      } else if (cn.type === 'summarize') {
        const nodeId = cn.graphNodeId || cn.id || `summarize-${Date.now()}-${Math.random().toString(36).substr(2, 9)}`;
        const summarizeConfig = (cn as any).summarize || {};

        nodes.push({
          id: nodeId,
          type: 'flowNode',
          position,
          positionAbsolute: position,
          data: {
            id: nodeId,
            label: cn.label || 'Summarize',
            kind: 'summarize',
            summarizeAggregations: summarizeConfig.aggregations || [],
            summarizeGroupBy: summarizeConfig.groupBy || [],
            summarizeInput: (cn as any).params?.input || summarizeConfig.input || (cn as any).input || '',
            paramExpressions: inputToParamExpressions((cn as any).params),
          } as any,
        });

        labelToNodeIdMap.set(cn.label || nodeId, nodeId);
        const normalizedSummarize = normalizeLabel(cn.label || nodeId);
        if (normalizedSummarize) labelToNodeIdMap.set(normalizedSummarize, nodeId);
      } else if (cn.type === 'date_time') {
        const nodeId = cn.graphNodeId || cn.id || `date-time-${Date.now()}-${Math.random().toString(36).substr(2, 9)}`;
        const dateTimeConfig = (cn as any).dateTime || {};

        nodes.push({
          id: nodeId,
          type: 'flowNode',
          position,
          positionAbsolute: position,
          data: {
            id: nodeId,
            label: cn.label || 'Date/Time',
            kind: 'date_time',
            dateTimeOperation: dateTimeConfig.operation || 'format',
            dateTimeValue: dateTimeConfig.value,
            dateTimeInputFormat: dateTimeConfig.inputFormat,
            dateTimeOutputFormat: dateTimeConfig.outputFormat,
            dateTimeTimezone: dateTimeConfig.timezone,
            dateTimeTargetTimezone: dateTimeConfig.targetTimezone,
            dateTimeDurationUnit: dateTimeConfig.durationUnit,
            dateTimeDurationAmount: dateTimeConfig.durationAmount,
            dateTimeSecondValue: dateTimeConfig.secondValue,
            dateTimeExtractPart: dateTimeConfig.extractPart,
            paramExpressions: inputToParamExpressions((cn as any).params),
          } as any,
        });

        labelToNodeIdMap.set(cn.label || nodeId, nodeId);
        const normalizedDt = normalizeLabel(cn.label || nodeId);
        if (normalizedDt) labelToNodeIdMap.set(normalizedDt, nodeId);
      } else if (cn.type === 'crypto_jwt') {
        const nodeId = cn.graphNodeId || cn.id || `crypto-jwt-${Date.now()}-${Math.random().toString(36).substr(2, 9)}`;
        const cryptoConfig = (cn as any).cryptoJwt || {};

        nodes.push({
          id: nodeId,
          type: 'flowNode',
          position,
          positionAbsolute: position,
          data: {
            id: nodeId,
            label: cn.label || 'Crypto/JWT',
            kind: 'crypto_jwt',
            cryptoOperation: cryptoConfig.operation || 'hash',
            cryptoAlgorithm: cryptoConfig.algorithm || 'SHA-256',
            cryptoValue: cryptoConfig.value,
            cryptoKey: cryptoConfig.key,
            cryptoSecret: cryptoConfig.secret,
            cryptoToken: cryptoConfig.token,
            cryptoPayload: cryptoConfig.payload,
            cryptoEncoding: cryptoConfig.encoding,
            paramExpressions: inputToParamExpressions((cn as any).params),
          } as any,
        });

        labelToNodeIdMap.set(cn.label || nodeId, nodeId);
        const normalizedCrypto = normalizeLabel(cn.label || nodeId);
        if (normalizedCrypto) labelToNodeIdMap.set(normalizedCrypto, nodeId);
      } else if (cn.type === 'wait') {
        const nodeId = cn.graphNodeId || cn.id || `wait-${Date.now()}-${Math.random().toString(36).substr(2, 9)}`;
        const waitDuration = (cn as any).wait?.duration || 0;

        nodes.push({
          id: nodeId,
          type: 'flowNode',
          position,
          positionAbsolute: position,
          data: {
            id: nodeId,  // Use nodeId (starts with 'wait-') for consistent detection
            label: cn.label || 'Wait',
            kind: 'wait',
            waitDuration,
            paramExpressions: inputToParamExpressions((cn as any).params),
          } as any,
        });

        labelToNodeIdMap.set(cn.label || nodeId, nodeId);
        const normalized = normalizeLabel(cn.label || nodeId);
        if (normalized) labelToNodeIdMap.set(normalized, nodeId);
      } else if (cn.type === 'download_file') {
        const nodeId = cn.graphNodeId || cn.id || `download_file-${Date.now()}-${Math.random().toString(36).substr(2, 9)}`;
        const downloadConfig = (cn as any).download || {};

        // Preserve optional download fields that the agent writes but no UI surfaces.
        // Without passthrough, the first frontend save round-trip silently drops
        // download.headers and download.timeout - agent-authored configs would lose
        // them even though the stored plan keeps them otherwise.
        const rawHeaders = downloadConfig.headers;
        const downloadHeaders =
          rawHeaders && typeof rawHeaders === 'object' && !Array.isArray(rawHeaders)
            ? Object.fromEntries(
                Object.entries(rawHeaders).filter(
                  ([k, v]) => typeof k === 'string' && typeof v === 'string'
                )
              )
            : undefined;
        const downloadTimeout =
          typeof downloadConfig.timeout === 'number' ? downloadConfig.timeout : undefined;

        nodes.push({
          id: nodeId,
          type: 'flowNode',
          position,
          positionAbsolute: position,
          data: {
            id: nodeId,  // Use nodeId (starts with 'download_file-') for consistent detection
            label: cn.label || 'Download File',
            kind: 'download_file',
            downloadUrl: downloadConfig.url || '',
            downloadFilename: downloadConfig.filename || '',
            ...(downloadHeaders && Object.keys(downloadHeaders).length > 0
              ? { downloadHeaders }
              : {}),
            ...(downloadTimeout !== undefined ? { downloadTimeout } : {}),
            paramExpressions: inputToParamExpressions((cn as any).params),
          } as any,
        });

        labelToNodeIdMap.set(cn.label || nodeId, nodeId);
        const normalized = normalizeLabel(cn.label || nodeId);
        if (normalized) labelToNodeIdMap.set(normalized, nodeId);
      } else if (cn.type === 'http_request') {
        const nodeId = cn.graphNodeId || cn.id || `http-request-${Date.now()}-${Math.random().toString(36).substr(2, 9)}`;
        const httpConfig = (cn as any).httpRequest || {};

        // Convert httpRequest config from plan to node format
        const httpRequestData = {
          method: httpConfig.method || 'GET',
          url: httpConfig.url || '',
          authType: httpConfig.authType || 'none',
          authConfig: httpConfig.authConfig,
          queryParams: httpConfig.queryParams?.map((p: any, i: number) => ({
            id: `${nodeId}-qp-${i}`,
            key: p.key,
            value: p.value || '',
          })) || [],
          headers: httpConfig.headers?.map((h: any, i: number) => ({
            id: `${nodeId}-hdr-${i}`,
            key: h.key,
            value: h.value || '',
          })) || [],
          bodyType: httpConfig.bodyType || 'none',
          body: httpConfig.body || '',
          contentType: httpConfig.contentType,
          timeout: httpConfig.timeout || 30000,
        };

        nodes.push({
          id: nodeId,
          type: 'flowNode',
          position,
          positionAbsolute: position,
          data: {
            id: nodeId,  // Use nodeId (starts with 'http-request-') for consistent detection
            label: cn.label || 'HTTP Request',
            kind: 'http_request',
            httpRequestData,
            paramExpressions: inputToParamExpressions((cn as any).params),
          } as any,
        });

        labelToNodeIdMap.set(cn.label || nodeId, nodeId);
        const normalized = normalizeLabel(cn.label || nodeId);
        if (normalized) labelToNodeIdMap.set(normalized, nodeId);
      } else if (cn.type === 'aggregate') {
        const nodeId = cn.graphNodeId || cn.id || `aggregate-${Date.now()}-${Math.random().toString(36).substr(2, 9)}`;
        const aggregateConfig = (cn as any).aggregate || {};

        // Convert aggregate fields from plan to node format
        const aggregateFields = aggregateConfig.fields?.map((field: any, index: number) => ({
          id: `${nodeId}-field-${index}`,
          label: field.label,
          expression: field.expression || '',
        })) || [{ id: `${nodeId}-field-0`, label: 'field_1', expression: '' }];

        nodes.push({
          id: nodeId,
          type: 'aggregateNode',
          position,
          positionAbsolute: position,
          data: {
            id: nodeId,  // Use nodeId (starts with 'aggregate-') for consistent detection
            label: cn.label || 'Aggregate',
            kind: 'aggregate',
            aggregateFields,
            paramExpressions: inputToParamExpressions((cn as any).params),
          } as any,
        });

        labelToNodeIdMap.set(cn.label || nodeId, nodeId);
        const normalized = normalizeLabel(cn.label || nodeId);
        if (normalized) labelToNodeIdMap.set(normalized, nodeId);
      } else if (cn.type === 'exit') {
        const nodeId = cn.graphNodeId || cn.id || `exit-${Date.now()}-${Math.random().toString(36).substr(2, 9)}`;

        nodes.push({
          id: nodeId,
          type: 'exitNode',
          position,
          positionAbsolute: position,
          data: {
            id: nodeId,  // Use nodeId (starts with 'exit-') for consistent detection
            label: cn.label || 'Exit',
            kind: 'exit',
            paramExpressions: inputToParamExpressions((cn as any).params),
          } as any,
        });

        labelToNodeIdMap.set(cn.label || nodeId, nodeId);
        const normalized = normalizeLabel(cn.label || nodeId);
        if (normalized) labelToNodeIdMap.set(normalized, nodeId);
      } else if (cn.type === 'response') {
        const nodeId = cn.graphNodeId || cn.id || `response-${Date.now()}-${Math.random().toString(36).substr(2, 9)}`;
        const responseConfig = (cn as any).response || {};

        nodes.push({
          id: nodeId,
          type: 'responseNode',
          position,
          positionAbsolute: position,
          data: {
            id: nodeId,  // Use nodeId (starts with 'response-') for consistent detection
            label: cn.label || 'Response',
            kind: 'output',
            responseMessage: responseConfig.message || '',
            paramExpressions: inputToParamExpressions((cn as any).params),
          } as any,
        });

        labelToNodeIdMap.set(cn.label || nodeId, nodeId);
        const normalized = normalizeLabel(cn.label || nodeId);
        if (normalized) labelToNodeIdMap.set(normalized, nodeId);
      } else if (cn.type === 'option') {
        const nodeId = cn.graphNodeId || cn.id || `option-${Date.now()}-${Math.random().toString(36).substr(2, 9)}`;

        // Use optionChoices from plan if available, otherwise create defaults
        const optionChoices = (cn as any).optionChoices?.length
          ? (cn as any).optionChoices.map((choice: any, i: number) => ({
              id: `${nodeId}-option-${i}`,
              label: choice.label,
              expression: choice.expression || '',
              description: choice.description || '',
            }))
          : createDefaultOptionChoices(nodeId);

        nodes.push({
          id: nodeId,
          type: 'optionNode',
          position,
          positionAbsolute: position,
          data: {
            id: nodeId,  // Use nodeId (starts with 'option-') for consistent detection
            label: cn.label || 'Option',
            kind: 'option',
            optionChoices,
            paramExpressions: inputToParamExpressions((cn as any).params),
          } as any,
        });

        labelToNodeIdMap.set(cn.label || nodeId, nodeId);
        const normalized = normalizeLabel(cn.label || nodeId);
        if (normalized) labelToNodeIdMap.set(normalized, nodeId);
      } else if (cn.type === 'approval') {
        const nodeId = cn.graphNodeId || cn.id || `user-approval-${Date.now()}-${Math.random().toString(36).substr(2, 9)}`;

        // Use approvalOutputs from plan if available, otherwise create defaults
        const approvalOutputs = (cn as any).approvalOutputs?.length
          ? (cn as any).approvalOutputs.map((output: any, i: number) => ({
              id: `${nodeId}-path-${i}`,
              label: output.label,
            }))
          : createDefaultApprovalOutputs(nodeId);

        // Preserve approval config that the agent writes but the UI doesn't surface.
        // Without this passthrough, approverRoles/requiredApprovals/contextTemplate are
        // silently dropped on the first frontend save round-trip (exporter in
        // edgeProcessor.ts re-emits only the fields it reads back from node.data).
        const approvalCfg = (cn as any).approval || {};
        const rawApproverRoles = approvalCfg.approverRoles;
        const approverRoles = Array.isArray(rawApproverRoles)
          ? rawApproverRoles.filter((r: unknown): r is string => typeof r === 'string')
          : undefined;
        const requiredApprovals = typeof approvalCfg.requiredApprovals === 'number'
          ? approvalCfg.requiredApprovals
          : undefined;
        const contextTemplate = typeof approvalCfg.contextTemplate === 'string'
          && approvalCfg.contextTemplate.trim() !== ''
          ? approvalCfg.contextTemplate
          : undefined;

        nodes.push({
          id: nodeId,
          type: 'userApprovalNode',
          position,
          positionAbsolute: position,
          data: {
            id: nodeId,
            label: cn.label || 'User Approval',
            kind: 'approval',
            approvalOutputs,
            approvalTimeoutMs: approvalCfg.timeoutMs ?? (cn as any).approvalTimeoutMs,
            ...(approverRoles && approverRoles.length > 0 ? { approverRoles } : {}),
            ...(requiredApprovals !== undefined ? { requiredApprovals } : {}),
            ...(contextTemplate !== undefined ? { approvalContextTemplate: contextTemplate } : {}),
            paramExpressions: inputToParamExpressions((cn as any).params),
          } as any,
        });

        labelToNodeIdMap.set(cn.label || nodeId, nodeId);
        const normalized = normalizeLabel(cn.label || nodeId);
        if (normalized) labelToNodeIdMap.set(normalized, nodeId);
      } else if (cn.type === 'data_input') {
        const nodeId = cn.graphNodeId || cn.id || `data-input-${Date.now()}-${Math.random().toString(36).substr(2, 9)}`;
        const dataInputConfig = (cn as any).dataInput || {};
        const dataInputItems: Array<{ id: string; label: string; type: 'text' | 'file'; text?: string; file?: any }> =
          Array.isArray(dataInputConfig.items) ? dataInputConfig.items : [];

        const diW = (cn as any).dataInputWidth;
        const diH = (cn as any).dataInputHeight;
        nodes.push({
          id: nodeId,
          type: 'flowNode',
          position,
          positionAbsolute: position,
          ...(diW || diH ? { style: { width: diW || 220, height: diH } } : {}),
          data: {
            id: nodeId,
            label: cn.label || 'Data Input',
            kind: 'data_input',
            dataInputItems,
            dataInputWidth: diW,
            dataInputHeight: diH,
            paramExpressions: inputToParamExpressions((cn as any).params),
          } as any,
        });

        labelToNodeIdMap.set(cn.label || nodeId, nodeId);
        const normalized = normalizeLabel(cn.label || nodeId);
        if (normalized) labelToNodeIdMap.set(normalized, nodeId);
      } else if (cn.type === 'xml') {
        const nodeId = cn.graphNodeId || cn.id || `xml-${Date.now()}-${Math.random().toString(36).substr(2, 9)}`;
        const xmlConfig = (cn as any).xml || {};

        nodes.push({
          id: nodeId,
          type: 'flowNode',
          position,
          positionAbsolute: position,
          data: {
            id: nodeId,
            label: cn.label || 'XML',
            kind: 'xml',
            xmlOperation: xmlConfig.operation || 'xmlToJson',
            xmlInput: xmlConfig.value || '',
            xmlRootElement: xmlConfig.rootElement,
            xmlPreserveAttributes: xmlConfig.preserveAttributes ?? true,
            paramExpressions: inputToParamExpressions((cn as any).params),
          } as any,
        });

        labelToNodeIdMap.set(cn.label || nodeId, nodeId);
        const normalizedXml = normalizeLabel(cn.label || nodeId);
        if (normalizedXml) labelToNodeIdMap.set(normalizedXml, nodeId);
      } else if (cn.type === 'compression') {
        const nodeId = cn.graphNodeId || cn.id || `compression-${Date.now()}-${Math.random().toString(36).substr(2, 9)}`;
        const compressionConfig = (cn as any).compression || {};

        nodes.push({
          id: nodeId,
          type: 'flowNode',
          position,
          positionAbsolute: position,
          data: {
            id: nodeId,
            label: cn.label || 'Compression',
            kind: 'compression',
            compressionOperation: compressionConfig.operation || 'compress',
            compressionFormat: compressionConfig.format || 'gzip',
            compressionInput: compressionConfig.value,
            compressionFilename: compressionConfig.filename,
            paramExpressions: inputToParamExpressions((cn as any).params),
          } as any,
        });

        labelToNodeIdMap.set(cn.label || nodeId, nodeId);
        const normalizedComp = normalizeLabel(cn.label || nodeId);
        if (normalizedComp) labelToNodeIdMap.set(normalizedComp, nodeId);
      } else if (cn.type === 'rss') {
        const nodeId = cn.graphNodeId || cn.id || `rss-${Date.now()}-${Math.random().toString(36).substr(2, 9)}`;
        const rssConfig = (cn as any).rss || {};

        nodes.push({
          id: nodeId,
          type: 'flowNode',
          position,
          positionAbsolute: position,
          data: {
            id: nodeId,
            label: cn.label || 'RSS',
            kind: 'rss',
            rssUrl: rssConfig.url || '',
            rssMaxItems: rssConfig.maxItems ?? 20,
            paramExpressions: inputToParamExpressions((cn as any).params),
          } as any,
        });

        labelToNodeIdMap.set(cn.label || nodeId, nodeId);
        const normalizedRss = normalizeLabel(cn.label || nodeId);
        if (normalizedRss) labelToNodeIdMap.set(normalizedRss, nodeId);
      } else if (cn.type === 'convert_to_file') {
        const nodeId = cn.graphNodeId || cn.id || `convert-to-file-${Date.now()}-${Math.random().toString(36).substr(2, 9)}`;
        const config = (cn as any).convertToFile || {};

        nodes.push({
          id: nodeId,
          type: 'flowNode',
          position,
          positionAbsolute: position,
          data: {
            id: nodeId,
            label: cn.label || 'Convert to File',
            kind: 'convert_to_file',
            convertFormat: config.format || 'csv',
            convertValue: config.value,
            convertFileName: config.filename || 'export',
            convertDelimiter: config.delimiter || ',',
            convertIncludeHeaders: config.includeHeaders === false ? 'no' : 'yes',
            paramExpressions: inputToParamExpressions((cn as any).params),
          } as any,
        });

        labelToNodeIdMap.set(cn.label || nodeId, nodeId);
        const normalizedCtf = normalizeLabel(cn.label || nodeId);
        if (normalizedCtf) labelToNodeIdMap.set(normalizedCtf, nodeId);
      } else if (cn.type === 'extract_from_file') {
        const nodeId = cn.graphNodeId || cn.id || `extract-from-file-${Date.now()}-${Math.random().toString(36).substr(2, 9)}`;
        const config = (cn as any).extractFromFile || {};

        nodes.push({
          id: nodeId,
          type: 'flowNode',
          position,
          positionAbsolute: position,
          data: {
            id: nodeId,
            label: cn.label || 'Extract from File',
            kind: 'extract_from_file',
            extractFormat: config.format || 'csv',
            extractValue: config.value,
            extractMode: config.mode || 'structured',
            extractDelimiter: config.delimiter || ',',
            extractSheetName: config.sheetName,
            extractHasHeaders: config.hasHeaders === false ? 'no' : 'yes',
            extractChunking: config.chunking || false,
            extractChunkSize: config.chunkSize != null ? String(config.chunkSize) : '500',
            extractOverlap: config.overlap != null ? String(config.overlap) : '50',
            extractChunkingStrategy: config.chunkingStrategy || 'fixed_size',
            extractSeparator: config.separator || '\\n\\n',
            extractChunkUnit: config.chunkUnit || 'char',
            paramExpressions: inputToParamExpressions((cn as any).params),
          } as any,
        });

        labelToNodeIdMap.set(cn.label || nodeId, nodeId);
        const normalizedEff = normalizeLabel(cn.label || nodeId);
        if (normalizedEff) labelToNodeIdMap.set(normalizedEff, nodeId);
      } else if (cn.type === 'compare_datasets') {
        const nodeId = cn.graphNodeId || cn.id || `compare-datasets-${Date.now()}-${Math.random().toString(36).substr(2, 9)}`;
        const config = (cn as any).compareDatasets || {};

        nodes.push({
          id: nodeId,
          type: 'flowNode',
          position,
          positionAbsolute: position,
          data: {
            id: nodeId,
            label: cn.label || 'Compare Datasets',
            kind: 'compare_datasets',
            compareInputA: config.inputA,
            compareInputB: config.inputB,
            compareMatchFields: config.matchFields || [],
            compareReturnMatched: config.returnMatched !== false,
            compareReturnOnlyA: config.returnOnlyA !== false,
            compareReturnOnlyB: config.returnOnlyB !== false,
            paramExpressions: inputToParamExpressions((cn as any).params),
          } as any,
        });

        labelToNodeIdMap.set(cn.label || nodeId, nodeId);
        const normalizedCd = normalizeLabel(cn.label || nodeId);
        if (normalizedCd) labelToNodeIdMap.set(normalizedCd, nodeId);
      } else if (cn.type === 'set') {
        const nodeId = cn.graphNodeId || cn.id || `set-${Date.now()}-${Math.random().toString(36).substr(2, 9)}`;
        const config = (cn as any).set || {};

        nodes.push({
          id: nodeId,
          type: 'flowNode',
          position,
          positionAbsolute: position,
          data: {
            id: nodeId,
            label: cn.label || 'Set / Edit Fields',
            kind: 'set',
            setInput: config.input || '',
            setKeepOnlySet: config.keepOnlySet === true,
            setAssignments: Array.isArray(config.assignments) ? config.assignments : [],
            paramExpressions: inputToParamExpressions((cn as any).params),
          } as any,
        });

        labelToNodeIdMap.set(cn.label || nodeId, nodeId);
        const normalizedSet = normalizeLabel(cn.label || nodeId);
        if (normalizedSet) labelToNodeIdMap.set(normalizedSet, nodeId);
      } else if (cn.type === 'html_extract') {
        const nodeId = cn.graphNodeId || cn.id || `html-extract-${Date.now()}-${Math.random().toString(36).substr(2, 9)}`;
        const config = (cn as any).htmlExtract || {};

        nodes.push({
          id: nodeId,
          type: 'flowNode',
          position,
          positionAbsolute: position,
          data: {
            id: nodeId,
            label: cn.label || 'HTML Extract',
            kind: 'html_extract',
            htmlExtractSource: config.sourceHtml || '',
            htmlExtractMode: config.extractionMode || 'single',
            htmlExtractRootSelector: config.rootSelector || '',
            htmlExtractCleanWhitespace: config.cleanWhitespace !== false,
            htmlExtractFields: Array.isArray(config.fields) ? config.fields : [],
            paramExpressions: inputToParamExpressions((cn as any).params),
          } as any,
        });

        labelToNodeIdMap.set(cn.label || nodeId, nodeId);
        const normalizedHx = normalizeLabel(cn.label || nodeId);
        if (normalizedHx) labelToNodeIdMap.set(normalizedHx, nodeId);
      } else if ((cn.type as string) === 'task') {
        const nodeId = cn.graphNodeId || cn.id || `task-${Date.now()}-${Math.random().toString(36).substr(2, 9)}`;
        const config = (cn as any).task || {};

        nodes.push({
          id: nodeId,
          type: 'flowNode',
          position,
          positionAbsolute: position,
          data: {
            id: nodeId,
            label: cn.label || 'Task',
            kind: 'task',
            taskOperation: config.operation || 'create_task',
            taskTaskId: config.taskId || '',
            taskTitle: config.title || '',
            taskInstructions: config.instructions || '',
            taskPriority: config.priority || '',
            taskAgentId: config.agentId || '',
            taskReviewerAgentId: config.reviewerAgentId || '',
            taskStatus: config.status || '',
            taskSearch: config.search || '',
            taskLimit: config.limit || 50,
            taskContextJson: config.taskContext && typeof config.taskContext === 'object'
              && Object.keys(config.taskContext).length > 0
              ? JSON.stringify(config.taskContext, null, 2)
              : '',
            paramExpressions: inputToParamExpressions((cn as any).params),
          } as any,
        });

        labelToNodeIdMap.set(cn.label || nodeId, nodeId);
        const normalizedTask = normalizeLabel(cn.label || nodeId);
        if (normalizedTask) labelToNodeIdMap.set(normalizedTask, nodeId);
      } else if (cn.type === 'sub_workflow') {
        const nodeId = cn.graphNodeId || cn.id || `sub-workflow-${Date.now()}-${Math.random().toString(36).substr(2, 9)}`;
        const config = (cn as any).subWorkflow || {};

        nodes.push({
          id: nodeId,
          type: 'flowNode',
          position,
          positionAbsolute: position,
          data: {
            id: nodeId,
            label: cn.label || 'Sub-Workflow',
            kind: 'sub_workflow',
            subWorkflowId: config.workflowId,
            subWorkflowInputMapping: config.inputMapping,
            subWorkflowTimeoutSeconds: config.timeoutSeconds || 300,
            subWorkflowMaxDepth: config.maxDepth || 5,
            ...(config.workflowName ? { workflowData: { workflowId: config.workflowId, workflowName: config.workflowName } } : {}),
            paramExpressions: inputToParamExpressions((cn as any).params),
          } as any,
        });

        labelToNodeIdMap.set(cn.label || nodeId, nodeId);
        const normalizedSw = normalizeLabel(cn.label || nodeId);
        if (normalizedSw) labelToNodeIdMap.set(normalizedSw, nodeId);
      } else if (cn.type === 'respond_to_webhook') {
        const nodeId = cn.graphNodeId || cn.id || `respond-to-webhook-${Date.now()}-${Math.random().toString(36).substr(2, 9)}`;
        const config = (cn as any).respondToWebhook || {};

        nodes.push({
          id: nodeId,
          type: 'flowNode',
          position,
          positionAbsolute: position,
          data: {
            id: nodeId,
            label: cn.label || 'Respond to Webhook',
            kind: 'respond_to_webhook',
            webhookResponseStatusCode: config.statusCode || 200,
            webhookResponseBody: config.body,
            webhookResponseContentType: config.contentType || 'application/json',
            webhookResponseHeaders: config.headers || {},
            paramExpressions: inputToParamExpressions((cn as any).params),
          } as any,
        });

        labelToNodeIdMap.set(cn.label || nodeId, nodeId);
        const normalizedRw = normalizeLabel(cn.label || nodeId);
        if (normalizedRw) labelToNodeIdMap.set(normalizedRw, nodeId);
      } else if (cn.type === 'send_email') {
        const nodeId = cn.graphNodeId || cn.id || `send-email-${Date.now()}-${Math.random().toString(36).substr(2, 9)}`;
        const config = (cn as any).sendEmail || {};

        nodes.push({
          id: nodeId,
          type: 'flowNode',
          position,
          positionAbsolute: position,
          data: {
            id: nodeId,
            label: cn.label || 'Send Email',
            kind: 'send_email',
            emailTo: config.toEmail || '',
            emailCc: config.ccEmail || '',
            emailBcc: config.bccEmail || '',
            emailFromName: config.fromName || '',
            emailSubject: config.subject || '',
            emailBody: config.body || '',
            emailIsHtml: config.isHtml === true ? 'true' : 'false',
            emailInReplyTo: config.inReplyTo || '',
            emailReferences: config.references || '',
            smtpCredentialId: config.credentialId || null,
            paramExpressions: inputToParamExpressions((cn as any).params),
          } as any,
        });

        labelToNodeIdMap.set(cn.label || nodeId, nodeId);
        const normalizedSe = normalizeLabel(cn.label || nodeId);
        if (normalizedSe) labelToNodeIdMap.set(normalizedSe, nodeId);
      } else if (cn.type === 'email_inbox') {
        const nodeId = cn.graphNodeId || cn.id || `email-inbox-${Date.now()}-${Math.random().toString(36).substr(2, 9)}`;
        const config = (cn as any).emailInbox || {};

        nodes.push({
          id: nodeId,
          type: 'flowNode',
          position,
          positionAbsolute: position,
          data: {
            id: nodeId,
            label: cn.label || 'Email Inbox',
            kind: 'email_inbox',
            emailFolder: config.folder || '',
            emailUnreadOnly: config.unreadOnly === true ? 'true' : 'false',
            emailLimit: config.limit != null ? String(config.limit) : '',
            emailMarkSeen: config.markSeen === true ? 'true' : 'false',
            emailSinceDays: config.sinceDays != null ? String(config.sinceDays) : '',
            emailAction: config.action || 'none',
            emailMessageUid: config.messageUid || '',
            emailTargetFolder: config.targetFolder || '',
            emailFromContains: config.fromContains || '',
            emailSubjectContains: config.subjectContains || '',
            emailBodyContains: config.bodyContains || '',
            emailFlaggedOnly: config.flaggedOnly === true ? 'true' : 'false',
            emailBeforeDays: config.beforeDays != null ? String(config.beforeDays) : '',
            emailDownloadAttachments: config.downloadAttachments === true ? 'true' : 'false',
            imapCredentialId: config.credentialId || null,
            paramExpressions: inputToParamExpressions((cn as any).params),
          } as any,
        });

        labelToNodeIdMap.set(cn.label || nodeId, nodeId);
        const normalizedEi = normalizeLabel(cn.label || nodeId);
        if (normalizedEi) labelToNodeIdMap.set(normalizedEi, nodeId);
      } else if (cn.type === 'code') {
        const nodeId = cn.graphNodeId || cn.id || `code-${Date.now()}-${Math.random().toString(36).substr(2, 9)}`;
        const config = (cn as any).code || {};

        nodes.push({
          id: nodeId,
          type: 'flowNode',
          position,
          positionAbsolute: position,
          data: {
            id: nodeId,
            label: cn.label || 'Code',
            kind: 'code',
            codeLanguage: config.language || 'javascript',
            codeContent: config.code || '',
            codeTimeoutSeconds: config.timeoutSeconds || 10,
            paramExpressions: inputToParamExpressions((cn as any).params),
          } as any,
        });

        labelToNodeIdMap.set(cn.label || nodeId, nodeId);
        const normalizedCo = normalizeLabel(cn.label || nodeId);
        if (normalizedCo) labelToNodeIdMap.set(normalizedCo, nodeId);
      } else if (cn.type === 'stop_on_error') {
        const nodeId = cn.graphNodeId || cn.id || `stop_on_error-${Date.now()}-${Math.random().toString(36).substr(2, 9)}`;
        const config = (cn as any).stopOnError || {};

        nodes.push({
          id: nodeId,
          type: 'flowNode',
          position,
          positionAbsolute: position,
          data: {
            id: nodeId,
            label: cn.label || 'Stop on Error',
            kind: 'stop_on_error',
            stopOnErrorMessage: config.errorMessage || '',
            stopOnErrorCode: config.errorCode || '',
            paramExpressions: inputToParamExpressions((cn as any).params),
          } as any,
        });

        labelToNodeIdMap.set(cn.label || nodeId, nodeId);
        const normalizedSoe = normalizeLabel(cn.label || nodeId);
        if (normalizedSoe) labelToNodeIdMap.set(normalizedSoe, nodeId);
      } else if (cn.type === 'ssh') {
        const nodeId = cn.graphNodeId || cn.id || `ssh-${Date.now()}-${Math.random().toString(36).substr(2, 9)}`;
        const config = (cn as any).ssh || {};

        nodes.push({
          id: nodeId,
          type: 'flowNode',
          position,
          positionAbsolute: position,
          data: {
            id: nodeId,
            label: cn.label || 'SSH',
            kind: 'ssh',
            sshCredentialId: config.credentialId || null,
            sshCommand: config.command || '',
            sshTimeout: config.timeout || 30000,
            paramExpressions: inputToParamExpressions((cn as any).params),
          } as any,
        });

        labelToNodeIdMap.set(cn.label || nodeId, nodeId);
        const normalizedSsh = normalizeLabel(cn.label || nodeId);
        if (normalizedSsh) labelToNodeIdMap.set(normalizedSsh, nodeId);
      } else if (cn.type === 'sftp') {
        const nodeId = cn.graphNodeId || cn.id || `sftp-${Date.now()}-${Math.random().toString(36).substr(2, 9)}`;
        const config = (cn as any).sftp || {};

        nodes.push({
          id: nodeId,
          type: 'flowNode',
          position,
          positionAbsolute: position,
          data: {
            id: nodeId,
            label: cn.label || 'SFTP',
            kind: 'sftp',
            sftpCredentialId: config.credentialId || null,
            sftpOperation: config.operation || 'upload',
            sftpRemotePath: config.remotePath || '',
            sftpLocalContent: config.localContent || '',
            sftpNewPath: config.newPath || '',
            sftpTimeout: config.timeout || 30000,
            paramExpressions: inputToParamExpressions((cn as any).params),
          } as any,
        });

        labelToNodeIdMap.set(cn.label || nodeId, nodeId);
        const normalizedSftp = normalizeLabel(cn.label || nodeId);
        if (normalizedSftp) labelToNodeIdMap.set(normalizedSftp, nodeId);
      } else if (cn.type === 'database') {
        const nodeId = cn.graphNodeId || cn.id || `database-${Date.now()}-${Math.random().toString(36).substr(2, 9)}`;
        const config = (cn as any).database || {};

        nodes.push({
          id: nodeId,
          type: 'flowNode',
          position,
          positionAbsolute: position,
          data: {
            id: nodeId,
            label: cn.label || 'Database',
            kind: 'database',
            dbCredentialId: config.credentialId || null,
            dbOperation: config.operation || 'select',
            dbQuery: config.query || '',
            dbQueryParams: config.queryParams || [],
            dbTimeout: config.timeout || 30000,
            paramExpressions: inputToParamExpressions((cn as any).params),
          } as any,
        });

        labelToNodeIdMap.set(cn.label || nodeId, nodeId);
        const normalizedDb = normalizeLabel(cn.label || nodeId);
        if (normalizedDb) labelToNodeIdMap.set(normalizedDb, nodeId);
      }

      if (!useSavedPosition) currentY += NODE_SPACING.y;
    }

    return { nodes, labelToNodeIdMap, nextY: currentY };
  }

  /**
   * Create CRUD table nodes inline.
   *
   * Async because table nodes need the real datasource display name -
   * otherwise the InspectorPanel and FlowNode show "DataSource 38" (placeholder
   * id) instead of the user-given name. {@link ToolDataService.fetchDataSourceData}
   * caches the datasources list across calls so the loop stays cheap (one
   * HTTP round-trip even with many table nodes).
   */
  private static async createTableNodesInline(
    tables: WorkflowPlan['tables'],
    startX: number,
    startY: number,
    tenantId: string
  ): Promise<{
    nodes: Node<BuilderNodeData>[];
    labelToNodeIdMap: Map<string, string>;
    nextY: number;
  }> {
    const nodes: Node<BuilderNodeData>[] = [];
    const labelToNodeIdMap = new Map<string, string>();
    let currentY = startY;

    if (!tables) return { nodes, labelToNodeIdMap, nextY: currentY };

    for (const table of tables) {
      const { position, useSavedPosition } = parsePosition(table.position, startX, currentY);

      // Extract CRUD operation from type (e.g., 'crud-create-row' -> 'create-row')
      // Normalize plural forms: 'find-rows' -> 'find-row', 'find' -> 'find-row'
      let crudOperation = table.type?.replace('crud-', '') || 'read-row';
      if (crudOperation === 'find-rows' || crudOperation === 'find') {
        crudOperation = 'find-row';
      }

      // Use normalized crudOperation for nodeId so it matches ID-based detection
      // (e.g., 'find-row-...' instead of 'find-...' which would be misdetected as a trigger)
      const nodeId = (table as any).graphNodeId || `${crudOperation}-${Date.now()}-${Math.random().toString(36).substr(2, 9)}`;

      // Resolve the real datasource display name. Falls back to the
      // "DataSource <id>" placeholder if the fetch fails or the id is
      // invalid - same contract as ToolDataService.fetchDataSourceData.
      let dataSourceName = table.dataSourceId
        ? `DataSource ${table.dataSourceId}`
        : 'DataSource (not configured)';
      if (table.dataSourceId) {
        try {
          const fetched = await ToolDataService.fetchDataSourceData(table.dataSourceId, tenantId);
          if (fetched.dataSourceData?.dataSourceName) {
            dataSourceName = fetched.dataSourceData.dataSourceName;
          }
        } catch (e) {
          console.warn(`[NodeCreation] Failed to resolve datasource name for id=${table.dataSourceId}`, e);
        }
      }

      // Build dataSourceData
      const dataSourceData: any = {
        dataSourceId: table.dataSourceId || 0,
        dataSourceName,
        crudOperation,
      };

      // Map operation-specific fields from crud config
      const crud: any = table.crud || {};
      switch (crudOperation) {
        case 'create-row':
          if (crud.rows && Array.isArray(crud.rows)) {
            dataSourceData.rows = crud.rows.map((row: any, index: number) => ({
              id: row.id || `row${index + 1}`,
              name: row.id || `row${index + 1}`,
              columns: row.columns || {},
            }));
          }
          break;

        case 'create-column':
          if (crud.columns && Array.isArray(crud.columns)) {
            dataSourceData.newColumns = crud.columns.map((col: any, index: number) => ({
              id: `col${index + 1}`,
              name: col.name,
              type: col.type || 'text',
              defaultValue: col.defaultValue || '',
            }));
          }
          break;

        case 'read-row':
          if (crud.where) {
            dataSourceData.whereCondition = normalizeWhereCondition(crud.where);
          }
          dataSourceData.limit = crud.limit ?? 100;
          break;

        case 'update-row':
          if (crud.where) {
            dataSourceData.whereCondition = normalizeWhereCondition(crud.where);
          }
          if (crud.set && typeof crud.set === 'object') {
            dataSourceData.setColumns = crud.set;
          }
          break;

        case 'delete-row':
          if (crud.where) {
            dataSourceData.whereCondition = normalizeWhereCondition(crud.where);
          }
          break;

        case 'find':
        case 'find-row': {
          // Check for similarity config in params or crud (vector search)
          let sim: any = null;
          const simRaw = table.params?.similarity ?? crud.similarity;
          if (simRaw) {
            sim = typeof simRaw === 'string'
              ? (() => { try { return JSON.parse(simRaw); } catch { return null; } })()
              : simRaw;
          }

          if (sim) {
            // Similarity search → whereCondition with SIMILAR_TO operator
            const simColumn = sim.column || '';
            dataSourceData.whereCondition = {
              column: simColumn.startsWith('data.') ? simColumn : (simColumn && simColumn !== 'id' ? `data.${simColumn}` : simColumn),
              operator: 'SIMILAR_TO',
              value: '',
              queryVector: sim.queryVector || '',
              topK: sim.topK ?? 5,
            };
          } else if (crud.where) {
            dataSourceData.whereCondition = normalizeWhereCondition(crud.where);
          }
          dataSourceData.limit = crud.limit ?? 100;
          break;
        }
      }

      nodes.push({
        id: nodeId,
        type: 'flowNode',
        position,
        positionAbsolute: position,
        data: {
          id: nodeId,
          label: table.label,
          kind: crudOperation === 'find-row' ? 'find' : 'crud',
          dataSourceData,
          paramExpressions: inputToParamExpressions(table.params),
        } as any,
      });

      labelToNodeIdMap.set(table.label, nodeId);
      const normalized = normalizeLabel(table.label);
      if (normalized && normalized !== table.label) {
        labelToNodeIdMap.set(normalized, nodeId);
      }

      if (!useSavedPosition) currentY += NODE_SPACING.y;
    }

    return { nodes, labelToNodeIdMap, nextY: currentY };
  }

  /**
   * Create all nodes from the plan - main orchestration method
   */
  static async createNodes(
    plan: WorkflowPlan,
    existingNodes: Node<BuilderNodeData>[] = []
  ): Promise<NodeCreationResult> {
    const nodes: Node<BuilderNodeData>[] = [];
    const labelToNodeIdMap = new Map<string, string>();
    const triggerIdToNodeIdMap = new Map<string, string>();
    const interfaceIdToNodeIdMap = new Map<string, string>();
    const interfaceLabelToNodeIdMap = new Map<string, string>();

    // tenantId is not in the plan - backend extracts it from JWT via apiClient
    const tenantId = '';
    const maxX = existingNodes.length > 0
      ? Math.max(...existingNodes.map(n => (n.position?.x || 0) + 400))
      : INITIAL_POSITION.x;
    let currentX = maxX;
    let currentY = INITIAL_POSITION.y;

    // Batch fetch tool data
    const allToolIds = this.collectAllToolIds(plan);
    if (allToolIds.length > 0) {
      console.log(`[NodeCreation] Batch fetching ${allToolIds.length} tools`);
      try {
        await ToolDataService.fetchToolsBatch(allToolIds);
      } catch (error) {
        console.warn('[NodeCreation] Batch fetch failed:', error);
      }
    }

    // 1. Create interface nodes
    if (Array.isArray(plan.interfaces)) {
      const result = createInterfaceNodes(plan.interfaces as any, currentX, currentY);
      nodes.push(...result.nodes);
      result.interfaceIdToNodeIdMap.forEach((v, k) => interfaceIdToNodeIdMap.set(k, v));
      result.interfaceLabelToNodeIdMap.forEach((v, k) => interfaceLabelToNodeIdMap.set(k, v));
    }

    // 2. Create trigger nodes
    const triggerResult = await this.createTriggerNodesInline(plan.triggers, tenantId, currentX, currentY);
    nodes.push(...triggerResult.nodes);
    triggerResult.triggerIdToNodeIdMap.forEach((v, k) => triggerIdToNodeIdMap.set(k, v));
    currentY = triggerResult.nextY;

    // 3. Collect loop node IDs (only old-style loopNode containers, NOT whileGroupNode)
    // WhileGroupNode body steps are regular step nodes connected by edges, not nested children.
    const loopNodeIds = new Set<string>();

    // 4. Create step nodes (MCPs)
    const stepResult = await createStepNodes(plan.mcps as any, loopNodeIds, currentX, currentY, nodes.length);
    nodes.push(...stepResult.nodes);
    stepResult.labelToNodeIdMap.forEach((v, k) => labelToNodeIdMap.set(k, v));
    currentY = stepResult.nextY;
    currentX = stepResult.nextX;

    // 5. Create table nodes (CRUD)
    if (plan.tables && Array.isArray(plan.tables)) {
      const tableResult = await this.createTableNodesInline(plan.tables, currentX, currentY, tenantId);
      nodes.push(...tableResult.nodes);
      tableResult.labelToNodeIdMap.forEach((v, k) => labelToNodeIdMap.set(k, v));
      currentY = tableResult.nextY;
    }

    // 6. Create agent nodes
    if (plan.agents && Array.isArray(plan.agents)) {
      const agentResult = createAgentNodes(plan.agents as any, currentX, currentY, nodes.length);
      nodes.push(...agentResult.nodes);
      agentResult.labelToNodeIdMap.forEach((v, k) => labelToNodeIdMap.set(k, v));
      currentY = agentResult.nextY;
      currentX = agentResult.nextX;
    }

    // 7. Create control nodes (cores: decision, switch, loop, split, merge, fork, transform, wait)
    const controlResult = this.createCoreNodesInline(plan.cores, plan.mcps, currentX, currentY);
    nodes.push(...controlResult.nodes);
    controlResult.labelToNodeIdMap.forEach((v, k) => labelToNodeIdMap.set(k, v));
    currentY = controlResult.nextY;

    // 8. Create note nodes
    if (plan.notes) {
      const noteResult = createNoteNodes(plan.notes as any, currentX, currentY);
      nodes.push(...noteResult.nodes);
      currentY = noteResult.nextY;
    }

    // 9. Update control nodes from edges
    for (const edge of plan.edges as any[]) {
      if (edge.if) {
        const conditions = extractConditionsFromIfLogic(edge.if, 'temp-id');
        const conditionExpression = conditions.find(c => c.type === 'if')?.expression || 'false';

        let existingNode = nodes.find(n => {
          if (!nodeRegistry.isDecisionNode(n)) return false;
          if (edge.if.position) {
            const ifPosX = typeof edge.if.position.x === 'number' ? edge.if.position.x : parseFloat(edge.if.position.x);
            const ifPosY = typeof edge.if.position.y === 'number' ? edge.if.position.y : parseFloat(edge.if.position.y);
            if (!isNaN(ifPosX) && !isNaN(ifPosY)) {
              return Math.abs(n.position.x - ifPosX) < 10 && Math.abs(n.position.y - ifPosY) < 10;
            }
          }
          return false;
        });

        if (!existingNode) {
          existingNode = nodes.find(n =>
            nodeRegistry.isDecisionNode(n) &&
            n.data.decisionConditions?.some(c => c.type === 'if' && c.expression === conditionExpression)
          );
        }

        if (!existingNode) {
          existingNode = nodes.find(n =>
            nodeRegistry.isDecisionNode(n) &&
            (!n.data.decisionConditions || n.data.decisionConditions.length === 0 ||
              n.data.decisionConditions.every(c => c.expression === 'false'))
          );
        }

        if (existingNode) {
          const planCoreNode = plan.cores?.find(cn => cn.id === existingNode!.id);
          if (planCoreNode?.decisionConditions?.length) {
            const extractedConditions = extractConditionsFromIfLogic(edge.if, existingNode.id, planCoreNode.decisionConditions);
            const extractedMap = new Map(extractedConditions.map(c => [c.type, c]));
            existingNode.data.decisionConditions = planCoreNode.decisionConditions.map((c, i) => ({
              id: `${existingNode!.id}-${c.type}${c.type === 'elseif' ? `-${i}` : ''}`,
              type: c.type as 'if' | 'elseif' | 'else',
              label: c.label,
              expression: extractedMap.get(c.type)?.expression || c.expression || '',
            }));
          } else {
            existingNode.data.decisionConditions = extractConditionsFromIfLogic(edge.if, existingNode.id, planCoreNode?.decisionConditions);
          }
          if (planCoreNode?.label) existingNode.data.label = planCoreNode.label;
        }
      }
    }

    // 10. Create loop children
    const allLoopNodes = nodes.filter(n => nodeRegistry.isLoopNode(n));
    for (const loopNode of allLoopNodes) {
      if (!loopNode.data.loopChildren?.length) {
        const { descriptors } = await this.createLoopChildren(loopNode.id, loopNode, plan);
        if (!Array.isArray(loopNode.data.loopChildren)) loopNode.data.loopChildren = [];
        loopNode.data.loopChildren = loopNode.data.loopChildren.concat(descriptors);

        const loopSteps = plan.mcps.filter(s => (s as any).parentLoopId === loopNode.id);
        loopSteps.forEach((ps, i) => {
          const childNodeId = makeInnerLoopNodeId(loopNode.id, ps.label, i);
          labelToNodeIdMap.set(ps.label, childNodeId);
          const normalized = normalizeLabel(ps.label);
          if (normalized && normalized !== ps.label) labelToNodeIdMap.set(normalized, childNodeId);
        });
      }
    }

    // 11. Hydrate per-node execution policies (nodePolicy) back onto builder node data
    this.hydrateNodePolicies(plan, nodes, labelToNodeIdMap, interfaceIdToNodeIdMap, interfaceLabelToNodeIdMap);

    return {
      nodes,
      labelToNodeIdMap,
      triggerIdToNodeIdMap,
      interfaceIdToNodeIdMap,
      interfaceLabelToNodeIdMap,
    };
  }

  /**
   * Reads the optional `nodePolicy` block of every executable plan entry
   * (mcps / tables / agents / cores / interfaces) back into the created
   * builder node's data, so the inspector Settings section shows it and the
   * generator round-trips it. Triggers and notes never carry one.
   *
   * Resolution order mirrors how entries reference their builder node:
   * `graphNodeId` (round-tripped by the generator) → label map (raw +
   * normalized, covers hand-written plans) → plan entry id (cores reuse it
   * as the node id).
   */
  private static hydrateNodePolicies(
    plan: WorkflowPlan,
    nodes: Node<BuilderNodeData>[],
    labelToNodeIdMap: Map<string, string>,
    interfaceIdToNodeIdMap: Map<string, string>,
    interfaceLabelToNodeIdMap: Map<string, string>
  ): void {
    const nodesById = new Map(nodes.map(n => [n.id, n]));

    const byLabel = (label?: string): Node<BuilderNodeData> | undefined => {
      if (!label) return undefined;
      const direct = labelToNodeIdMap.get(label);
      if (direct) return nodesById.get(direct);
      const normalized = normalizeLabel(label);
      const viaNormalized = normalized ? labelToNodeIdMap.get(normalized) : undefined;
      return viaNormalized ? nodesById.get(viaNormalized) : undefined;
    };

    const hydrate = (entries?: Array<Record<string, any>>) => {
      if (!Array.isArray(entries)) return;
      for (const entry of entries) {
        if (!entry?.nodePolicy) continue;
        const policy = sanitizeNodePolicy(entry.nodePolicy);
        if (!policy) continue;
        const node =
          (entry.graphNodeId && nodesById.get(entry.graphNodeId)) ||
          byLabel(entry.label) ||
          (entry.id && nodesById.get(entry.id)) ||
          undefined;
        if (node) {
          node.data.nodePolicy = policy;
        }
      }
    };

    hydrate(plan.mcps as Array<Record<string, any>>);
    hydrate((plan as any).tables);
    hydrate((plan as any).agents);
    hydrate((plan as any).cores);

    // Interfaces resolve through their dedicated id/label maps
    const interfaces = (plan as any).interfaces as Array<Record<string, any>> | undefined;
    if (Array.isArray(interfaces)) {
      for (const entry of interfaces) {
        if (!entry?.nodePolicy) continue;
        const policy = sanitizeNodePolicy(entry.nodePolicy);
        if (!policy) continue;
        const normalized = entry.label ? normalizeLabel(entry.label) : '';
        const nodeId =
          (entry.id && interfaceIdToNodeIdMap.get(entry.id)) ||
          (normalized && interfaceLabelToNodeIdMap.get(normalized)) ||
          undefined;
        const node = nodeId ? nodesById.get(nodeId) : undefined;
        if (node) {
          node.data.nodePolicy = policy;
        }
      }
    }
  }
}
