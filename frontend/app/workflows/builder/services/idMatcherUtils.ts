/**
 * Shared utilities for ID matching and normalization
 * Used by nodeMatcher and edgeMatcher to avoid code duplication
 */

import { normalizeLabel } from '../utils/labelNormalizer';
import { nodeRegistry } from '../registry/nodeRegistry';

/**
 * Normalizes an ID by removing prefixes and runtime suffixes
 * Uses centralized normalizeLabel for consistent handling of accented characters
 */
export function normalizeId(id?: string | null): string {
  if (!id) return '';
  // Remove prefixes and runtime suffixes first
  const withoutPrefixAndSuffix = id.replace(/^(mcp:|table:|trigger:|datasource:|agent:|core:|note:|interface:)/, '').replace(/#.*$/, '');
  // Then apply centralized normalization
  return normalizeLabel(withoutPrefixAndSuffix) || withoutPrefixAndSuffix.toLowerCase();
}

/**
 * Extracts the alias from a node ID (e.g., "step-codekit_decode_qr-123-abc" -> "codekit_decode_qr")
 * Also handles:
 * - Loop child format: "{loopNodeId}::{childAlias}#{position}" -> "childAlias"
 * - Trigger format: "trigger-6" or "trigger:6" or "tables-trigger-6" -> "trigger:6" (for backend matching)
 */
export function extractAliasFromNodeId(nodeId: string): string {
  if (!nodeId) return '';
  
  // Handle loop child format: {loopNodeId}::{childAlias}#{position}
  // Example: "while-1764759070201::actions_list_automations#2" -> "actions_list_automations"
  const loopChildMatch = nodeId.match(/::([^#]+)#/);
  if (loopChildMatch) {
    return normalizeLabel(loopChildMatch[1]) || loopChildMatch[1].toLowerCase();
  }
  
  // Handle trigger formats: trigger-6, trigger:6, tables-trigger-6, trigger:6-1764772417085-klfj5ur95
  // Extract the identifier and return in format "trigger:identifier" for backend matching
  // Remove timestamp and random suffix like we do for steps
  if (nodeId.startsWith('trigger:') || nodeId.startsWith('trigger-') || nodeId.startsWith('tables-trigger-')) {
    // Extract identifier after prefix
    let identifier = nodeId;
    if (nodeId.startsWith('trigger:')) {
      identifier = nodeId.substring('trigger:'.length);
    } else if (nodeId.startsWith('tables-trigger-')) {
      identifier = nodeId.substring('tables-trigger-'.length);
    } else if (nodeId.startsWith('trigger-')) {
      identifier = nodeId.substring('trigger-'.length);
    }
    // Remove timestamp and random suffix (format: -timestamp-random)
    // Match: -{digits}-{alphanumeric} at the end
    identifier = identifier.replace(/-\d+-[a-z0-9]+$/i, '');
    // Return in format "trigger:identifier" for backend matching - use centralized normalizeLabel
    const normalizedIdentifier = normalizeLabel(identifier) || identifier.toLowerCase();
    return `trigger:${normalizedIdentifier}`;
  }
  
  // Handle mcp format: mcp:xxx - use centralized normalizeLabel
  if (nodeId.startsWith('mcp:')) {
    const extracted = nodeId.substring('mcp:'.length);
    return normalizeLabel(extracted) || extracted.toLowerCase();
  }

  // Handle agent format: agent-xxx or agent:xxx - use centralized normalizeLabel
  if (nodeId.startsWith('agent:')) {
    const extracted = nodeId.substring('agent:'.length);
    return normalizeLabel(extracted) || extracted.toLowerCase();
  }
  if (nodeId.startsWith('agent-')) {
    let identifier = nodeId.substring('agent-'.length);
    // Remove timestamp and random suffix
    identifier = identifier.replace(/-\d+-[a-z0-9]+$/i, '');
    return normalizeLabel(identifier) || identifier.toLowerCase();
  }

  // Handle control node format: core:xxx - use centralized normalizeLabel
  if (nodeId.startsWith('core:')) {
    const extracted = nodeId.substring('core:'.length);
    return normalizeLabel(extracted) || extracted.toLowerCase();
  }
  if (nodeId.startsWith('split-')) {
    let identifier = nodeId.replace(/^split-/, '');
    // Remove timestamp and random suffix
    identifier = identifier.replace(/-\d+-[a-z0-9]+$/i, '');
    return normalizeLabel(identifier) || identifier.toLowerCase();
  }

  // Remove "step-" prefix if present
  let alias = nodeId.replace(/^step-/, '');
  // Remove timestamp and random suffix (format: -timestamp-random)
  // Match: -{digits}-{alphanumeric} at the end
  alias = alias.replace(/-\d+-[a-z0-9]+$/i, '');
  return normalizeLabel(alias) || alias.toLowerCase();
}

/**
 * Extracts the alias from a step ID (e.g., "mcp:codekit_decode_qr" -> "codekit_decode_qr")
 * Handles all 7 unified prefixes: trigger:, mcp:, table:, agent:, core:, note:, interface:
 */
export function extractAliasFromStepId(stepId: string): string {
  if (!stepId) return '';
  let alias = stepId.replace(/^(trigger:|mcp:|table:|agent:|core:|note:|interface:)/, '');
  // Use centralized normalizeLabel for consistent handling
  return normalizeLabel(alias) || alias.toLowerCase();
}

/**
 * Checks if a node is a trigger node
 */
export function isTriggerNode(node: { data?: { kind?: string; id?: string; dataSourceData?: any }; id?: string }): boolean {
  // kind === 'entry' is the canonical trigger indicator
  if (node.data?.kind === 'entry') return true;

  // CRUD nodes have dataSourceData but are NOT triggers - exclude them
  const crudOp = (node.data as any)?.dataSourceData?.crudOperation || (node.data as any)?.crudOperation;
  if (crudOp) return false;

  const id = node.data?.id || node.id || '';
  return (
    id.startsWith('tables-trigger-') ||
    id.startsWith('trigger-') ||
    id.startsWith('trigger:')
  );
}

/**
 * Checks if a node is a decision node
 */
export function isDecisionNode(node: { type?: string; data?: { kind?: string } }): boolean {
  return nodeRegistry.isDecisionLikeNode(node as any);
}

/**
 * Matches a trigger node with a trigger ID from batch data
 * Handles various trigger ID formats
 */
export function matchesTriggerId(
  nodeId: string,
  nodeDataId: string,
  nodeLabel: string,
  triggerId: string
): boolean {
  if (!triggerId) return false;

  const nodeIdLower = nodeId.toLowerCase();
  const nodeDataIdLower = nodeDataId.toLowerCase();
  const nodeLabelLower = (nodeLabel || '').toLowerCase();
  const triggerIdLower = triggerId.toLowerCase();

  // Extract trigger ID from node identifiers
  const nodeTriggerId = nodeDataIdLower
    .replace('trigger:', '')
    .replace('trigger-', '')
    .replace('tables-trigger-', '');

  // IMPORTANT: Only match if triggerId starts with "trigger:" prefix or matches label exactly
  // This prevents "mcp:1" from matching a trigger node because "1" appears in the timestamp
  const isActualTriggerStep = triggerId.startsWith('trigger:') ||
                              triggerId === nodeLabelLower ||
                              triggerId === nodeTriggerId;

  if (!isActualTriggerStep) {
    return false;
  }

  return (
    triggerIdLower === nodeTriggerId ||
    nodeLabelLower === triggerIdLower ||
    nodeIdLower.endsWith(`trigger-${triggerIdLower}`) ||
    nodeDataIdLower.endsWith(`trigger-${triggerIdLower}`) ||
    nodeIdLower === `trigger:${triggerIdLower}` ||
    nodeDataIdLower === `trigger:${triggerIdLower}` ||
    nodeIdLower === `tables-trigger-${triggerIdLower}` ||
    nodeDataIdLower === `tables-trigger-${triggerIdLower}`
  );
}

/**
 * Extracts step alias from a node, trying multiple sources in order:
 * For triggers: prioritize label (like steps) because backend saves with normalized label
 * For decision nodes: prioritize label because backend saves with the decision node label (not the ID with timestamp)
 * For steps/loop children: try node.data.id, then node.id, then label
 * 
 * This is useful for finding the step alias to search for in workflow runs,
 * especially for loop child nodes which have format: {loopNodeId}::{childAlias}#{position}
 */
export function extractStepAliasFromNode(node: {
  id: string;
  data?: { id?: string; label?: string; kind?: string };
  type?: string;
}): string | null {
  if (!node) return null;
  
  // Check if it's a trigger node - for triggers, prioritize label (like steps)
  // because backend saves triggers with their normalized label (without "trigger:" prefix)
  const isTrigger = isTriggerNode(node);
  
  if (isTrigger && node.data?.label) {
    // For triggers, use normalized label WITHOUT "trigger:" prefix
    // because backend saves step_alias as just the normalized label (e.g., "test" not "trigger:test")
    const normalizedLabel = normalizeLabel(node.data.label);
    if (normalizedLabel) {
      return normalizedLabel; // Just the label, not "trigger:label"
    }
  }
  
  // Check if it's a decision node - for decision nodes, prioritize label
  // Backend stores step_alias with the NORMALIZED label (via LabelNormalizer.normalizeLabel)
  // So we must normalize here to match what's in the DB
  const isDecision = isDecisionNode(node);

  if (isDecision && node.data?.label) {
    // For decision nodes, use normalized label to match backend behavior
    // Backend (WorkflowPersistenceService.buildDecisionEntity line 1322) does:
    //   String normalizedDecisionLabel = LabelNormalizer.normalizeLabel(evaluation.decisionNodeLabel());
    //   entity.setStepAlias(normalizedDecisionLabel);
    // So "If / else" is stored as "if_else" in the database
    const normalizedLabel = normalizeLabel(node.data.label);
    if (normalizedLabel) {
      return normalizedLabel;
    }
  }

  // For regular steps: prioritize label because backend saves step_alias with the label
  // The node.data.id contains technical IDs (e.g., "1", "2") which don't match what's in the DB
  // Example: node has id="step-1-xxx" and label="C", backend saves step_alias="C"
  if (node.data?.label) {
    const normalized = normalizeLabel(node.data.label);
    if (normalized) {
      return normalized;
    }
  }

  // Fallback to node.data.id (for loop children with format {loopNodeId}::{childAlias}#{position})
  if (node.data?.id) {
    const alias = extractAliasFromNodeId(node.data.id);
    if (alias) return alias;
  }

  // Last resort: try node.id
  if (node.id) {
    const alias = extractAliasFromNodeId(node.id);
    if (alias) return alias;
  }
  
  return null;
}

/**
 * Compares two normalized labels, handling underscore variations
 * "while_a" should match "whilea" and vice versa
 */
export function labelsMatch(label1: string | null | undefined, label2: string | null | undefined): boolean {
  if (!label1 || !label2) return false;

  // Direct match
  if (label1 === label2) return true;

  // Remove underscores and compare
  const l1NoUnderscore = label1.replace(/_/g, '');
  const l2NoUnderscore = label2.replace(/_/g, '');
  if (l1NoUnderscore === l2NoUnderscore) return true;

  return false;
}

/**
 * Creates normalized identifiers for a node (for matching purposes)
 */
export function getNodeIdentifiers(node: {
  id: string;
  data?: { id?: string; label?: string };
}): {
  idAlias: string;
  labelNorm: string;
  nodeId: string;
  idNorm: string;
  dataIdNorm: string;
} {
  const nodeIdAlias = node.data?.id ? extractAliasFromNodeId(node.data.id) : '';
  // Use normalizeLabel for proper normalization (removes parentheses, special chars)
  // "list_bases (copy)" -> "list_bases_copy"
  const nodeLabelNorm = normalizeLabel(node.data?.label || '') || '';
  const nodeId = node.id.toLowerCase();
  const nodeIdNorm = normalizeId(node.data?.id || node.id);
  const nodeDataIdNorm = normalizeId(node.data?.id || '');

  return {
    idAlias: nodeIdAlias,
    labelNorm: nodeLabelNorm,
    nodeId,
    idNorm: nodeIdNorm,
    dataIdNorm: nodeDataIdNorm,
  };
}

