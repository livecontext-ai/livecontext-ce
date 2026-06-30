/**
 * StepNodeCreator - Handles creation of step nodes from plan data
 * Includes: regular steps, agent steps, CRUD steps, transform steps, wait steps
 * Extracted from NodeCreationService for single responsibility
 */

import type { Node, XYPosition } from 'reactflow';
import type { BuilderNodeData } from '../../types';
import { ToolDataService, type ToolDataResult } from './ToolDataService';
import { normalizeLabel } from '../../utils/labelNormalizer';
import {
  parsePosition,
  inputToParamExpressions,
  normalizeWhereCondition,
  NODE_SPACING,
  INITIAL_POSITION,
} from './nodeCreationHelpers';

interface StepFromPlan {
  id?: string;
  type?: string; // 'mcp' | 'crud-*' for steps in mcps/tables arrays
  label: string;
  description?: string;
  position?: { x?: number | string; y?: number | string };
  params?: Record<string, any>;
  parentLoopId?: string;
  crud?: any;
  dataSourceId?: number;
  graphNodeId?: string;
  selectedCredentialId?: number | null;
  credentialSource?: 'user' | 'platform';
  platformCredentialId?: number | null;
}

interface StepCreationResult {
  nodes: Node<BuilderNodeData>[];
  labelToNodeIdMap: Map<string, string>;
  nextY: number;
  nextX: number;
}

/**
 * Check if step should be skipped (gateway/passthrough steps)
 */
function shouldSkipStep(step: StepFromPlan): boolean {
  return (
    step.id === '__passthrough__' ||
    step.label?.startsWith('gateway_to_while_') ||
    step.label?.startsWith('__gateway_to_while_') ||
    step.label?.startsWith('__gateway__') ||
    step.label?.startsWith('__merge__') ||
    step.label?.startsWith('__sync__')
  );
}

/**
 * Generate node ID based on step type
 */
function generateStepNodeId(step: StepFromPlan, isCrudStep: boolean, crudOperation: string | null): string {
  // Preserve graphNodeId from saved plan to maintain stable IDs across reloads
  if (step.graphNodeId) return step.graphNodeId;

  const timestamp = Date.now();
  const random = Math.random().toString(36).substr(2, 9);

  if (isCrudStep && crudOperation) {
    // Normalize legacy formats
    let normalizedOp = crudOperation;
    if (crudOperation === 'insert_row' || crudOperation === 'insert_rows') normalizedOp = 'create-row';
    else if (crudOperation === 'read_row' || crudOperation === 'read_rows') normalizedOp = 'read-row';
    else if (crudOperation === 'update_row' || crudOperation === 'update_rows') normalizedOp = 'update-row';
    else if (crudOperation === 'delete_row' || crudOperation === 'delete_rows') normalizedOp = 'delete-row';
    else if (crudOperation === 'find_row' || crudOperation === 'find_rows' || crudOperation === 'find' || crudOperation === 'find-rows') normalizedOp = 'find-row';
    return `${normalizedOp}-${step.label}-${timestamp}-${random}`;
  }
  return `step-${step.label}-${timestamp}-${random}`;
}

/**
 * Build CRUD dataSourceData from step.
 *
 * Async to resolve the real datasource display name via
 * {@link ToolDataService.fetchDataSourceData} - otherwise the FlowNode title
 * and InspectorPanel show the "DataSource <id>" placeholder for legacy CRUD
 * steps that arrive through {@code plan.mcps[]} (id starts with `crud/`).
 * The fetch is cached by ToolDataService so this stays a single round-trip
 * across the whole import.
 */
async function buildCrudDataSourceData(step: StepFromPlan, crudOperation: string): Promise<any> {
  const stepCrud = step.crud || {};
  const dataSourceId = step.dataSourceId || 0;

  // Normalize operation
  let normalizedOp = crudOperation;
  if (crudOperation === 'insert_row' || crudOperation === 'insert_rows') normalizedOp = 'create-row';
  else if (crudOperation === 'read_row' || crudOperation === 'read_rows') normalizedOp = 'read-row';
  else if (crudOperation === 'update_row' || crudOperation === 'update_rows') normalizedOp = 'update-row';
  else if (crudOperation === 'delete_row' || crudOperation === 'delete_rows') normalizedOp = 'delete-row';
  else if (crudOperation === 'find_row' || crudOperation === 'find_rows' || crudOperation === 'find' || crudOperation === 'find-rows') normalizedOp = 'find-row';

  // Resolve the real datasource name. Falls back to the "DataSource <id>"
  // placeholder when the fetch fails or the id is invalid - same contract
  // as ToolDataService.fetchDataSourceData and the table-import path.
  let dataSourceName = dataSourceId === 0
    ? 'DataSource (not configured)'
    : `DataSource ${dataSourceId}`;
  if (dataSourceId !== 0) {
    try {
      const fetched = await ToolDataService.fetchDataSourceData(dataSourceId);
      if (fetched.dataSourceData?.dataSourceName) {
        dataSourceName = fetched.dataSourceData.dataSourceName;
      }
    } catch (e) {
      console.warn(`[StepNodeCreator] Failed to resolve datasource name for id=${dataSourceId}`, e);
    }
  }

  const crudData: any = {
    dataSourceId,
    dataSourceName,
    crudOperation: normalizedOp,
  };

  // Map operation-specific fields using normalizedOp for consistent matching
  switch (normalizedOp) {
    case 'create-row':
      if (stepCrud.rows && Array.isArray(stepCrud.rows)) {
        crudData.rows = stepCrud.rows.map((row: any, index: number) => ({
          id: row.id || `row${index + 1}`,
          name: row.name || row.id || `row${index + 1}`,
          columns: row.columns || {},
        }));
      }
      break;

    case 'create-column':
      if (stepCrud.columns && Array.isArray(stepCrud.columns)) {
        crudData.newColumns = stepCrud.columns.map((col: any, index: number) => ({
          id: col.id || `col${index + 1}`,
          name: col.name,
          type: col.type || 'text',
          defaultValue: col.defaultValue || '',
        }));
      }
      break;

    case 'read-row':
      if (stepCrud.where) {
        crudData.whereCondition = normalizeWhereCondition(stepCrud.where);
      }
      crudData.limit = stepCrud.limit ?? 50;
      break;

    case 'update-row':
      if (stepCrud.where) {
        crudData.whereCondition = normalizeWhereCondition(stepCrud.where);
      }
      if (stepCrud.set && typeof stepCrud.set === 'object') {
        crudData.setColumns = stepCrud.set;
      }
      break;

    case 'delete-row':
      if (stepCrud.where) {
        crudData.whereCondition = normalizeWhereCondition(stepCrud.where);
      }
      break;

    case 'find-row': {
      // Extract similarity config from params or crud (vector search)
      let sim: any = null;
      const simRaw = step.params?.similarity ?? stepCrud.similarity;
      if (simRaw) {
        sim = typeof simRaw === 'string'
          ? (() => { try { return JSON.parse(simRaw); } catch { return null; } })()
          : simRaw;
      }

      if (sim) {
        // Similarity search → merge into whereCondition with SIMILAR_TO operator
        const simColumn = sim.column || '';
        crudData.whereCondition = {
          column: simColumn.startsWith('data.') ? simColumn : (simColumn && simColumn !== 'id' ? `data.${simColumn}` : simColumn),
          operator: 'SIMILAR_TO',
          value: '',
          queryVector: sim.queryVector || '',
          topK: sim.topK ?? 5,
        };
      } else if (stepCrud.where) {
        crudData.whereCondition = normalizeWhereCondition(stepCrud.where);
      }
      crudData.limit = stepCrud.limit ?? 100;
      break;
    }
  }

  return crudData;
}

/**
 * Create a single step node (MCP catalog tools or CRUD operations only).
 * Note: Transform and Wait are now handled as Core nodes, not Step nodes.
 */
async function createStepNode(
  step: StepFromPlan,
  currentX: number,
  currentY: number
): Promise<{
  node: Node<BuilderNodeData>;
  mappings: { label: string; normalizedLabel: string };
  incrementY: boolean;
}> {
  // Determine step type from 'type' field (new format) or 'id' field (legacy)
  const stepType = step.type || 'mcp';
  const isCrudStep = stepType.startsWith('crud-') || (step.id && step.id.startsWith('crud/'));
  let crudOperation: string | null = null;

  if (isCrudStep) {
    if (stepType.startsWith('crud-')) {
      crudOperation = stepType.replace('crud-', '');
    } else if (step.id) {
      crudOperation = step.id.replace('crud/', '');
      if (crudOperation.includes('/')) {
        crudOperation = crudOperation.split('/')[0];
      }
    }
  }

  const isAgentStep = step.id === 'agent';

  // Generate node ID
  const nodeId = generateStepNodeId(step, isCrudStep, crudOperation);

  // Fetch tool data (skip for CRUD steps)
  let toolDataResult: ToolDataResult = {};
  if (!isAgentStep && !isCrudStep && step.id) {
    const cachedResult = ToolDataService.getFromBatchCache(step.id);
    if (cachedResult) {
      toolDataResult = cachedResult;
    } else {
      try {
        toolDataResult = await ToolDataService.fetchToolDataFromPlan(step.id);
      } catch (error) {
        console.warn(`Could not fetch tool data for ${step.id}:`, error);
        toolDataResult = {};
      }
    }
  }

  // Parse position
  const { position: stepPosition, useSavedPosition } = parsePosition(
    step.position,
    currentX,
    currentY,
    `step ${step.label}`
  );

  // Determine data ID - must be unique per node to avoid collision in handleNodeUpdate
  // Agent nodes keep 'ai-agent' prefix for matchNodeClass compatibility
  let dataId: string;
  if (isAgentStep) dataId = `ai-agent-${Date.now()}-${Math.random().toString(36).substr(2, 5)}`;
  else dataId = nodeId;

  // Determine node kind
  let nodeKind: string = 'action';
  if (isAgentStep) nodeKind = 'reasoning';
  else if (isCrudStep && crudOperation) {
    const normalizedCrud = crudOperation.replace(/_/g, '-');
    nodeKind = (normalizedCrud === 'find-row' || normalizedCrud === 'find-rows' || normalizedCrud === 'find') ? 'find' : 'crud';
  }

  // Build CRUD data if needed
  const crudDataSourceData = isCrudStep && crudOperation
    ? await buildCrudDataSourceData(step, crudOperation)
    : undefined;

  // Build step params
  const stepParams = step.params || {};

  const hasStepCredentialFields =
    (step as any).credentialSource != null ||
    (step as any).platformCredentialId != null ||
    (step as any).selectedCredentialId != null;

  // Create node
  const stepNode: Node<BuilderNodeData> = {
    id: nodeId,
    type: 'flowNode',
    position: stepPosition,
    positionAbsolute: (useSavedPosition || toolDataResult.toolData || toolDataResult.apiData || isAgentStep || isCrudStep)
      ? stepPosition
      : undefined,
    data: {
      id: dataId,
      label: step.label,
      description: step.description,
      kind: nodeKind,
      // Step-level credential-source fields live on toolData so the inspector
      // can read them alongside selectedCredentialId without threading a separate
      // prop through every form. Preserve them even when tool metadata is absent
      // (failed fetch, CRUD/agent steps), otherwise a platform-pinned step loses
      // its pin on reload. See CredentialSection & stepProcessor.
      toolData: (toolDataResult.toolData || hasStepCredentialFields)
        ? {
            ...(toolDataResult.toolData ?? {}),
            credentialSource: (step as any).credentialSource,
            selectedCredentialId: (step as any).selectedCredentialId ?? null,
            platformCredentialId: (step as any).platformCredentialId ?? null,
          }
        : toolDataResult.toolData,
      apiData: toolDataResult.apiData,
      paramExpressions: inputToParamExpressions(stepParams),
      ...(isCrudStep && crudDataSourceData && { dataSourceData: crudDataSourceData }),
      ...(isAgentStep && {
        provider: stepParams.provider,
        model: stepParams.model,
        temperature: stepParams.temperature,
        maxTokens: stepParams.maxTokens,
        maxIterations: stepParams.maxIterations,
        maxTools: stepParams.maxTools,
        autoDiscoverTools: stepParams.autoDiscoverTools,
        systemPrompt: stepParams.systemPrompt,
      }),
    } as any,
  };

  return {
    node: stepNode,
    mappings: {
      label: step.label,
      normalizedLabel: normalizeLabel(step.label),
    },
    incrementY: !useSavedPosition,
  };
}

/**
 * Create all step nodes from plan
 */
export async function createStepNodes(
  steps: StepFromPlan[],
  loopNodeIds: Set<string>,
  startX: number,
  startY: number,
  nodeCount: number
): Promise<StepCreationResult> {
  const nodes: Node<BuilderNodeData>[] = [];
  const labelToNodeIdMap = new Map<string, string>();
  let currentX = startX;
  let currentY = startY;
  let totalNodes = nodeCount;

  for (const step of steps) {
    // Skip loop children - handled separately
    const parentLoopId = step.parentLoopId;
    if (parentLoopId && loopNodeIds.has(parentLoopId)) {
      continue;
    }

    // Skip gateway/passthrough steps
    if (shouldSkipStep(step)) {
      continue;
    }

    const result = await createStepNode(step, currentX, currentY);

    nodes.push(result.node);

    // Map labels
    labelToNodeIdMap.set(result.mappings.label, result.node.id);
    if (result.mappings.normalizedLabel && result.mappings.normalizedLabel !== result.mappings.label) {
      labelToNodeIdMap.set(result.mappings.normalizedLabel, result.node.id);
    }

    // Update position
    if (result.incrementY) {
      currentY += NODE_SPACING.y;
      totalNodes++;

      // Move to next column if too many nodes
      if (totalNodes % 5 === 0) {
        currentX += NODE_SPACING.x;
        currentY = INITIAL_POSITION.y;
      }
    }
  }

  return {
    nodes,
    labelToNodeIdMap,
    nextY: currentY,
    nextX: currentX,
  };
}
