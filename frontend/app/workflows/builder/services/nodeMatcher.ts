/**
 * Node Matcher Service
 * Single responsibility: Match frontend nodes with backend step data
 */

import type { Node } from 'reactflow';
import type { BuilderNodeData } from '../types';
import {
  normalizeId,
  isTriggerNode,
  matchesTriggerId,
  getNodeIdentifiers,
} from './idMatcherUtils';

/**
 * Batch step data from streaming
 */
export interface BatchStepData {
  normalizedStepId?: string;
  stepAlias?: string;
  originalStepId?: string;
  id?: string;
}

/**
 * Compares two normalized labels, handling underscore variations
 * "while_a" should match "whilea" and vice versa
 *
 * IMPORTANT: Both labels must have the same "copy" suffix status to match.
 * This prevents "step_name" from matching "step_name_copy".
 */
function labelsMatch(label1: string | null | undefined, label2: string | null | undefined): boolean {
  if (!label1 || !label2) return false;

  // Direct match
  if (label1 === label2) return true;

  // Remove underscores and compare
  const l1NoUnderscore = label1.replace(/_/g, '');
  const l2NoUnderscore = label2.replace(/_/g, '');
  if (l1NoUnderscore === l2NoUnderscore) return true;

  // CRITICAL: Prevent "step_name" from matching "step_name_copy"
  // If one ends with "copy" and the other doesn't, they should NOT match
  const l1HasCopy = l1NoUnderscore.endsWith('copy');
  const l2HasCopy = l2NoUnderscore.endsWith('copy');
  if (l1HasCopy !== l2HasCopy) {
    // One has copy suffix, the other doesn't - never match
    return false;
  }

  return false;
}

/**
 * Checks if a node matches a batch step
 * Prioritizes exact matches to avoid ambiguous partial matches
 * Uses labelsMatch to handle underscore variations (e.g., "while_a" vs "whilea")
 */
export function nodeMatchesStep(
  node: Node<BuilderNodeData>,
  step: BatchStepData
): boolean {
  const nodeIds = getNodeIdentifiers(node);
  const stepIdNorm = normalizeId(step.normalizedStepId || step.stepAlias || step.id || '');
  const stepAliasNorm = normalizeId(step.stepAlias || '');
  const originalStepIdNorm = normalizeId(step.originalStepId || '');
  const stepIdRaw = (step.id || '').toLowerCase();

  // Handle trigger nodes
  if (isTriggerNode(node) && stepIdRaw) {
    const triggerIdFromStep = stepIdRaw.replace('trigger:', '').replace('mcp:', '');
    if (matchesTriggerId(node.id, node.data.id || '', node.data.label || '', triggerIdFromStep)) {
      return true;
    }
  }

  // Priority 1: Alias matches with underscore flexibility
  if (
    labelsMatch(nodeIds.idAlias, stepAliasNorm) ||
    labelsMatch(nodeIds.labelNorm, stepAliasNorm) ||
    labelsMatch(nodeIds.labelNorm, originalStepIdNorm)
  ) {
    return true;
  }

  // Priority 2: ID matches with underscore flexibility
  // labelNorm vs stepIdNorm is critical for nodes where data.id is static
  // (agent="ai-agent", guardrail="guardrail", classify="classify", crud="create-row")
  // and streaming doesn't send stepAlias - labelNorm is the only reliable identifier
  if (
    labelsMatch(nodeIds.idNorm, stepIdNorm) ||
    labelsMatch(nodeIds.nodeId, stepIdNorm) ||
    labelsMatch(nodeIds.nodeId, stepAliasNorm) ||
    labelsMatch(nodeIds.idAlias, stepIdNorm) ||
    labelsMatch(nodeIds.dataIdNorm, stepIdNorm) ||
    labelsMatch(nodeIds.labelNorm, stepIdNorm)
  ) {
    return true;
  }

  return false;
}


