/**
 * Edge Matcher Service
 * Single responsibility: Match frontend edges with backend edge data
 */

import type { Edge, Node } from 'reactflow';
import type { BuilderNodeData } from '../types';
import { normalizeId, labelsMatch } from './idMatcherUtils';
import { normalizeLabel, extractCoreLabelWithoutPort, extractAgentLabelWithoutPort, extractPortFromRef } from '../utils/labelNormalizer';

/**
 * Batch edge data from streaming
 */
export interface BatchEdgeData {
  id?: string;
  from?: string;
  to?: string;
  running?: number;
  completed?: number;
  skipped?: number;
  statusCounts?: Record<string, number>;
}

/**
 * Checks if an edge matches a batch edge
 */
export function edgeMatchesBatchEdge(
    edge: Edge,
    batchEdge: BatchEdgeData,
    sourceNode: Node<BuilderNodeData>,
    targetNode: Node<BuilderNodeData>,
    allNodes?: Node<BuilderNodeData>[]
): boolean {
  // Normalize node labels using normalizeLabel (consistent with backend)
  const sourceLabelNormalized = normalizeLabel(sourceNode.data.label);
  const targetLabelNormalized = normalizeLabel(targetNode.data.label);

  // Also keep normalizeId for ID-based matching (fallback)
  const sourceIdNorm = normalizeId(sourceNode.data.id);
  const sourceNodeId = sourceNode.id.toLowerCase();
  const targetIdNorm = normalizeId(targetNode.data.id);
  const targetNodeId = targetNode.id.toLowerCase();

  // Extract and normalize labels from batch edge
  // Uses all unified prefixes: trigger:, mcp:, agent:, core:, table:, interface:
  // Also strips the # suffix (branch info) e.g., "core:whilea#then:..." -> "whilea"
  // For core: also strips port suffixes like ":if", ":branch_0", etc.
  const extractAndNormalizeBatchLabel = (batchRef: string | null | undefined): string | null => {
    if (!batchRef) return null;
    // Strip the # suffix (branch/condition info) before extracting label
    const cleanRef = batchRef.split('#')[0];
    if (cleanRef.startsWith('trigger:')) {
      return normalizeLabel(cleanRef.substring('trigger:'.length));
    }
    if (cleanRef.startsWith('mcp:')) {
      return normalizeLabel(cleanRef.substring('mcp:'.length));
    }
    if (cleanRef.startsWith('agent:')) {
      // Use extractAgentLabelWithoutPort to handle ports like ":category_0", ":pass", etc.
      return extractAgentLabelWithoutPort(cleanRef);
    }
    if (cleanRef.startsWith('core:')) {
      // Use extractCoreLabelWithoutPort to handle ports like ":if", ":branch_0", etc.
      return extractCoreLabelWithoutPort(cleanRef);
    }
    if (cleanRef.startsWith('table:')) {
      return normalizeLabel(cleanRef.substring('table:'.length));
    }
    if (cleanRef.startsWith('interface:')) {
      return normalizeLabel(cleanRef.substring('interface:'.length));
    }
    return null;
  };

  const batchFromLabel = extractAndNormalizeBatchLabel(batchEdge.from);
  const batchToLabel = extractAndNormalizeBatchLabel(batchEdge.to);

  // Also keep normalizeId for backward compatibility
  const batchFromNorm = normalizeId(batchEdge.from);
  const batchToNorm = normalizeId(batchEdge.to);

  // Strategy 1: Direct from->to match using normalized labels
  // Priority: match by normalized labels (for cores, triggers, steps)
  // Use labelsMatch to handle underscore variations (e.g., "while_a" vs "whilea")
  const fromMatches =
      // Match by normalized labels with underscore flexibility
      labelsMatch(batchFromLabel, sourceLabelNormalized) ||
      // Fallback: match by ID (for backward compatibility)
      labelsMatch(sourceIdNorm, batchFromNorm) ||
      labelsMatch(sourceNodeId, batchFromNorm);

  const toMatches =
      // Match by normalized labels with underscore flexibility
      labelsMatch(batchToLabel, targetLabelNormalized) ||
      // Fallback: match by ID (for backward compatibility)
      labelsMatch(targetIdNorm, batchToNorm) ||
      labelsMatch(targetNodeId, batchToNorm);

  if (fromMatches && toMatches) {
    // Port-aware disambiguation: when batch edge has a port (e.g., core:option:choice_0),
    // verify it matches the React Flow edge's sourceHandle to distinguish between
    // multiple branches going to the same target.
    const port = extractPortFromRef(batchEdge.from);
    if (port && edge.sourceHandle) {
      const portMatch = sourceHandleMatchesPort(port, edge.sourceHandle, sourceNode);
      if (portMatch === false) {
        // Definite mismatch - this batch edge belongs to a different branch
        return false;
      }
    }
    return true;
  }

  // Strategy 2: Match by edge ID pattern (e.g., "trigger:test->mcp:get_user_profile_by_user_id")
  if (batchEdge.id && batchEdge.id.includes('->')) {
    const [batchIdFrom, batchIdTo] = batchEdge.id.split('->');

    // Extract and normalize labels from edge ID
    const batchIdFromLabel = extractAndNormalizeBatchLabel(batchIdFrom);
    const batchIdToLabel = extractAndNormalizeBatchLabel(batchIdTo);

    // Also keep normalizeId for backward compatibility
    const batchIdFromNorm = normalizeId(batchIdFrom);
    const batchIdToNorm = normalizeId(batchIdTo);

    const fromMatchesId =
        // Match by normalized labels with underscore flexibility
        labelsMatch(batchIdFromLabel, sourceLabelNormalized) ||
        // Fallback: match by ID
        labelsMatch(sourceIdNorm, batchIdFromNorm) ||
        labelsMatch(sourceNodeId, batchIdFromNorm);

    const toMatchesId =
        // Match by normalized labels with underscore flexibility
        labelsMatch(batchIdToLabel, targetLabelNormalized) ||
        // Fallback: match by ID
        labelsMatch(targetIdNorm, batchIdToNorm) ||
        labelsMatch(targetNodeId, batchIdToNorm);

    if (fromMatchesId && toMatchesId) {
      return true;
    }
  }

  return false;
}

/**
 * Checks if a React Flow edge's sourceHandle corresponds to a backend port.
 * Used to disambiguate when multiple branches share the same target node.
 *
 * Returns true if matches, false if definitely doesn't match, undefined if can't determine.
 */
function sourceHandleMatchesPort(
  port: string,
  sourceHandle: string,
  sourceNode: Node<BuilderNodeData>
): boolean | undefined {
  const data = sourceNode.data as unknown as Record<string, unknown>;

  // Index-based ports: choice_N, case_N, branch_N, category_N, elseif_N, path_N
  const indexMatch = port.match(/^(choice|case|branch|category|elseif|path)_(\d+)$/);
  if (indexMatch) {
    const [, type, indexStr] = indexMatch;
    const index = parseInt(indexStr);

    let items: Array<{ id?: string }>;
    switch (type) {
      case 'choice': items = (data.optionChoices as any[]) || []; break;
      case 'case': items = (data.switchCases as any[]) || []; break;
      case 'branch': items = (data.forkOutputs as any[]) || []; break;
      case 'category': items = (data.classifyCategories as any[]) || []; break;
      case 'path': items = (data.approvalOutputs as any[]) || []; break;
      case 'elseif': {
        // elseif_0 maps to the 2nd condition (index 1 in conditions array since 0 is 'if')
        const conditions = (data.decisionConditions as any[]) || [];
        const condIndex = index + 1; // elseif_0 → conditions[1]
        if (condIndex >= 0 && condIndex < conditions.length) {
          const condId = conditions[condIndex]?.id;
          if (condId) return sourceHandle === condId || sourceHandle.includes(condId);
        }
        return undefined;
      }
      default: return undefined;
    }

    if (index >= 0 && index < items.length) {
      const itemId = items[index]?.id;
      if (itemId) return sourceHandle === itemId || sourceHandle.includes(itemId);
    }
    return undefined;
  }

  // Named ports: if, else, default
  if (port === 'if' || port === 'else') {
    const conditions = (data.decisionConditions as any[]) || [];
    for (const cond of conditions) {
      if (cond.type === port && cond.id) {
        return sourceHandle === cond.id || sourceHandle.includes(cond.id);
      }
    }
    return undefined;
  }

  if (port === 'default') {
    const cases = (data.switchCases as any[]) || [];
    const defaultCase = cases.find((c: { type?: string }) => c.type === 'default');
    if (defaultCase?.id) return sourceHandle === defaultCase.id || sourceHandle.includes(defaultCase.id);
    return undefined;
  }

  // Approval semantic ports: approved, rejected, timeout
  // Backend emits "core:user_approval:approved" but React Flow edges use approvalOutput IDs as sourceHandle.
  // Match by comparing port name to approvalOutput label (case-insensitive).
  if (port === 'approved' || port === 'rejected' || port === 'timeout') {
    const items = (data.approvalOutputs as Array<{ id?: string; label?: string }>) || [];
    for (const item of items) {
      if (item.label?.toLowerCase() === port && item.id) {
        return sourceHandle === item.id || sourceHandle.includes(item.id);
      }
    }
    return undefined;
  }

  // Guardrail semantic ports: pass, fail
  // GuardrailNode renders hardcoded source handles id="pass" / id="fail" (and the legacy
  // "<id>-pass" / "<id>-fail" form). Without this case sourceHandleMatchesPort returned
  // undefined for guardrail branches, so edgeMatchesBatchEdge could not exclude the sibling
  // branch: both the pass and fail edges (same source, same merge target) bound to whichever
  // per-epoch key matched first, painting them with one branch's status.
  if (port === 'pass' || port === 'fail') {
    return sourceHandle === port || sourceHandle.endsWith('-' + port);
  }

  return undefined;
}
