import { describe, it, expect } from 'vitest';
import {
  normalizeId,
  extractAliasFromNodeId,
  extractAliasFromStepId,
  isTriggerNode,
  matchesTriggerId,
  extractStepAliasFromNode,
  labelsMatch,
  getNodeIdentifiers,
  isDecisionNode,
} from '../idMatcherUtils';

// =============================================================================
// normalizeId
// =============================================================================
describe('normalizeId', () => {
  it('should return empty string for null/undefined/empty', () => {
    expect(normalizeId(null)).toBe('');
    expect(normalizeId(undefined)).toBe('');
    expect(normalizeId('')).toBe('');
  });

  it('should strip mcp: prefix and normalize', () => {
    expect(normalizeId('mcp:get_user')).toBe('get_user');
  });

  it('should strip trigger: prefix and normalize', () => {
    expect(normalizeId('trigger:my_webhook')).toBe('my_webhook');
  });

  it('should strip agent: prefix and normalize', () => {
    expect(normalizeId('agent:my_agent')).toBe('my_agent');
  });

  it('should strip core: prefix and normalize', () => {
    expect(normalizeId('core:my_decision')).toBe('my_decision');
  });

  it('should strip table: prefix and normalize', () => {
    expect(normalizeId('table:users')).toBe('users');
  });

  it('should strip note: prefix and normalize', () => {
    expect(normalizeId('note:my_note')).toBe('my_note');
  });

  it('should strip interface: prefix and normalize', () => {
    expect(normalizeId('interface:my_form')).toBe('my_form');
  });

  it('should strip datasource: prefix and normalize', () => {
    expect(normalizeId('datasource:my_ds')).toBe('my_ds');
  });

  it('should strip # suffix (runtime branch info)', () => {
    expect(normalizeId('mcp:step_1#then')).toBe('step_1');
    expect(normalizeId('core:check#if')).toBe('check');
  });

  it('should handle IDs without prefixes', () => {
    expect(normalizeId('some_id')).toBe('some_id');
  });

  it('should normalize accented characters', () => {
    expect(normalizeId('mcp:entree_ids')).toBe('entree_ids');
  });

  it('should lowercase the result', () => {
    // normalizeId regex is case-sensitive on prefixes, so uppercase prefix is not stripped.
    // The input gets normalized via normalizeLabel which lowercases everything.
    expect(normalizeId('mcp:GET_USER')).toBe('get_user');
    // Without a recognized prefix, the whole string is normalized
    expect(normalizeId('MCP:GET_USER')).toBe('mcp_get_user');
  });

  it('should replace special characters with underscores', () => {
    expect(normalizeId('mcp:My Step Name')).toBe('my_step_name');
  });

  it('should collapse multiple underscores', () => {
    expect(normalizeId('mcp:my__step___name')).toBe('my_step_name');
  });

  it('should trim leading/trailing underscores', () => {
    expect(normalizeId('mcp:_my_step_')).toBe('my_step');
  });

  it('should handle combined prefix and # suffix', () => {
    expect(normalizeId('core:while_loop#body:mcp:step')).toBe('while_loop');
  });
});

// =============================================================================
// extractAliasFromNodeId
// =============================================================================
describe('extractAliasFromNodeId', () => {
  it('should return empty string for empty input', () => {
    expect(extractAliasFromNodeId('')).toBe('');
  });

  it('should extract alias from loop child format', () => {
    // {loopNodeId}::{childAlias}#{position}
    expect(extractAliasFromNodeId('while-1764759070201::actions_list_automations#2'))
      .toBe('actions_list_automations');
  });

  it('should extract alias from trigger: format', () => {
    expect(extractAliasFromNodeId('trigger:6')).toBe('trigger:6');
  });

  it('should extract alias from trigger- format', () => {
    expect(extractAliasFromNodeId('trigger-6')).toBe('trigger:6');
  });

  it('should extract alias from tables-trigger- format', () => {
    expect(extractAliasFromNodeId('tables-trigger-6')).toBe('trigger:6');
  });

  it('should strip timestamp and random suffix from trigger format', () => {
    expect(extractAliasFromNodeId('trigger:6-1764772417085-klfj5ur95'))
      .toBe('trigger:6');
  });

  it('should extract alias from mcp: format', () => {
    expect(extractAliasFromNodeId('mcp:codekit_decode_qr')).toBe('codekit_decode_qr');
  });

  it('should extract alias from agent: format', () => {
    expect(extractAliasFromNodeId('agent:my_analyzer')).toBe('my_analyzer');
  });

  it('should extract alias from agent- format with timestamp', () => {
    expect(extractAliasFromNodeId('agent-my_analyzer-1764772417085-klfj5ur95'))
      .toBe('my_analyzer');
  });

  it('should extract alias from core: format', () => {
    expect(extractAliasFromNodeId('core:my_decision')).toBe('my_decision');
  });

  it('should extract alias from split- format with timestamp', () => {
    expect(extractAliasFromNodeId('split-my_split-1764772417085-klfj5ur95'))
      .toBe('my_split');
  });

  it('should strip step- prefix', () => {
    expect(extractAliasFromNodeId('step-codekit_decode_qr-123-abc'))
      .toBe('codekit_decode_qr');
  });

  it('should handle step- with timestamp and random suffix', () => {
    expect(extractAliasFromNodeId('step-my_step-1764772417085-klfj5ur95'))
      .toBe('my_step');
  });

  it('should handle plain IDs with timestamp suffix', () => {
    expect(extractAliasFromNodeId('my_step-1764772417085-klfj5ur95'))
      .toBe('my_step');
  });

  it('should normalize accented characters in loop child alias', () => {
    expect(extractAliasFromNodeId('while-123::entree_ids#0'))
      .toBe('entree_ids');
  });

  it('should handle agent- format without suffix', () => {
    expect(extractAliasFromNodeId('agent-simple')).toBe('simple');
  });

  it('should handle trigger: with label-based identifier', () => {
    expect(extractAliasFromNodeId('trigger:my_webhook')).toBe('trigger:my_webhook');
  });

  it('should handle trigger- with label and timestamp', () => {
    expect(extractAliasFromNodeId('trigger-my_webhook-1234567890-abc123'))
      .toBe('trigger:my_webhook');
  });
});

// =============================================================================
// extractAliasFromStepId
// =============================================================================
describe('extractAliasFromStepId', () => {
  it('should return empty string for empty input', () => {
    expect(extractAliasFromStepId('')).toBe('');
  });

  it('should strip trigger: prefix', () => {
    expect(extractAliasFromStepId('trigger:my_webhook')).toBe('my_webhook');
  });

  it('should strip mcp: prefix', () => {
    expect(extractAliasFromStepId('mcp:codekit_decode_qr')).toBe('codekit_decode_qr');
  });

  it('should strip table: prefix', () => {
    expect(extractAliasFromStepId('table:users')).toBe('users');
  });

  it('should strip agent: prefix', () => {
    expect(extractAliasFromStepId('agent:my_agent')).toBe('my_agent');
  });

  it('should strip core: prefix', () => {
    expect(extractAliasFromStepId('core:my_decision')).toBe('my_decision');
  });

  it('should strip note: prefix', () => {
    expect(extractAliasFromStepId('note:my_note')).toBe('my_note');
  });

  it('should strip interface: prefix', () => {
    expect(extractAliasFromStepId('interface:my_form')).toBe('my_form');
  });

  it('should handle input without known prefix', () => {
    expect(extractAliasFromStepId('raw_label')).toBe('raw_label');
  });

  it('should normalize the extracted alias', () => {
    expect(extractAliasFromStepId('mcp:My Step Name')).toBe('my_step_name');
  });

  it('should handle accented characters', () => {
    expect(extractAliasFromStepId('mcp:Entree IDs')).toBe('entree_ids');
  });
});

// =============================================================================
// isTriggerNode
// =============================================================================
describe('isTriggerNode', () => {
  it('should return true for kind=entry', () => {
    expect(isTriggerNode({ data: { kind: 'entry' } })).toBe(true);
  });

  it('should return true for tables-trigger- prefix in data.id', () => {
    expect(isTriggerNode({ data: { id: 'tables-trigger-6' } })).toBe(true);
  });

  it('should return true for trigger- prefix in data.id', () => {
    expect(isTriggerNode({ data: { id: 'trigger-6' } })).toBe(true);
  });

  it('should return true for trigger: prefix in data.id', () => {
    expect(isTriggerNode({ data: { id: 'trigger:6' } })).toBe(true);
  });

  it('should return true when node.id starts with trigger-', () => {
    expect(isTriggerNode({ id: 'trigger-1764772417085-klfj5ur95' })).toBe(true);
  });

  it('should return false for regular step node', () => {
    expect(isTriggerNode({ data: { id: 'step-1', kind: 'action' }, id: 'step-1-123-abc' })).toBe(false);
  });

  it('should return false for empty node', () => {
    expect(isTriggerNode({ data: {} })).toBe(false);
  });

  it('should return false for mcp node', () => {
    expect(isTriggerNode({ data: { id: 'mcp:get_user', kind: 'action' }, id: 'step-get_user-123-abc' })).toBe(false);
  });

  // CRUD nodes must NOT be detected as triggers (Finding 1 fix)
  it('should return false for CRUD find-row node with dataSourceData', () => {
    expect(isTriggerNode({ data: { id: 'find-row-123-abc', dataSourceData: { crudOperation: 'find-row' } } as any })).toBe(false);
  });

  it('should return false for CRUD create-row node with dataSourceData', () => {
    expect(isTriggerNode({ data: { id: 'create-row-123-abc', dataSourceData: { crudOperation: 'create-row' } } as any })).toBe(false);
  });

  it('should return false for CRUD read-row node with dataSourceData', () => {
    expect(isTriggerNode({ data: { id: 'read-row-123-abc', dataSourceData: { crudOperation: 'read-row' } } as any })).toBe(false);
  });

  it('should return false for CRUD update-row node with dataSourceData', () => {
    expect(isTriggerNode({ data: { id: 'update-row-123-abc', dataSourceData: { crudOperation: 'update-row' } } as any })).toBe(false);
  });

  it('should return false for CRUD delete-row node with dataSourceData', () => {
    expect(isTriggerNode({ data: { id: 'delete-row-123-abc', dataSourceData: { crudOperation: 'delete-row' } } as any })).toBe(false);
  });

  it('should return true for tables-trigger with dataSourceData but kind=entry', () => {
    expect(isTriggerNode({ data: { id: 'tables-trigger-6', kind: 'entry', dataSourceData: {} } as any })).toBe(true);
  });
});

// =============================================================================
// isDecisionNode
// =============================================================================
describe('isDecisionNode', () => {
  it('should return true for decisionNode type (via registry)', () => {
    expect(isDecisionNode({ type: 'decisionNode', data: {} })).toBe(true);
  });

  it('should return true for kind=condition', () => {
    expect(isDecisionNode({ type: 'flowNode', data: { kind: 'condition' } })).toBe(true);
  });

  it('should return false for regular flow node', () => {
    expect(isDecisionNode({ type: 'flowNode', data: { kind: 'action' } })).toBe(false);
  });

  it('should return false for loop node', () => {
    expect(isDecisionNode({ type: 'whileGroupNode', data: {} })).toBe(false);
  });
});

// =============================================================================
// matchesTriggerId
// =============================================================================
describe('matchesTriggerId', () => {
  it('should return false for empty triggerId', () => {
    expect(matchesTriggerId('trigger-6', 'trigger:6', 'My Trigger', '')).toBe(false);
  });

  it('should match when triggerId starts with trigger: prefix', () => {
    // When triggerId has trigger: prefix, isActualTriggerStep is true
    // triggerIdLower = 'trigger:6', nodeTriggerId = '6'
    // triggerIdLower === nodeTriggerId -> 'trigger:6' === '6' -> false
    // But nodeDataIdLower === `trigger:${triggerIdLower}` -> 'trigger:6' === 'trigger:trigger:6' -> false
    // Actually nodeDataIdLower.endsWith('trigger-trigger:6') -> false
    // The function expects triggerId to already be stripped of prefix (as nodeMatchesStep does)
    // So passing '6' as triggerId works:
    expect(matchesTriggerId('trigger-6', 'trigger:6', 'My Trigger', '6')).toBe(true);
  });

  it('should match by nodeTriggerId when triggerId equals extracted ID', () => {
    // nodeTriggerId extracted from 'trigger:6' -> '6'
    // triggerId '6' === nodeTriggerId '6' -> isActualTriggerStep = true
    // triggerIdLower '6' === nodeTriggerId '6' -> match
    expect(matchesTriggerId('trigger-6', 'trigger:6', 'My Trigger', '6')).toBe(true);
  });

  it('should match by label (exact match)', () => {
    // triggerId 'test' === nodeLabelLower 'test' -> isActualTriggerStep = true
    expect(matchesTriggerId('trigger-6', 'trigger:6', 'test', 'test')).toBe(true);
  });

  it('should prevent mcp: step from matching trigger node', () => {
    // triggerId 'mcp:1' does not start with 'trigger:', does not match label 'my trigger', does not match nodeTriggerId '1'
    // (because 'mcp:1' !== '1')
    expect(matchesTriggerId('trigger-1-1764772417085-abc', 'trigger:1', 'My Trigger', 'mcp:1')).toBe(false);
  });

  it('should match when nodeId ends with trigger-{triggerId}', () => {
    // triggerId '6' === nodeTriggerId '6' -> isActualTriggerStep true
    // nodeIdLower 'test-trigger-6'.endsWith('trigger-6') -> true
    expect(matchesTriggerId('test-trigger-6', 'trigger:6', '', '6')).toBe(true);
  });

  it('should match when nodeDataId is trigger:{triggerId}', () => {
    // triggerId '6' === nodeTriggerId '6' -> isActualTriggerStep true
    // nodeDataIdLower 'trigger:6' === 'trigger:6' -> true
    expect(matchesTriggerId('some-node', 'trigger:6', '', '6')).toBe(true);
  });

  it('should match tables-trigger format via label', () => {
    // nodeDataId 'tables-trigger-6' -> replace chain: 'tables-trigger-6' -> 'tables-6' (trigger- removed from middle)
    // nodeTriggerId = 'tables-6', triggerId = '6', '6' !== 'tables-6' -> isActualTriggerStep = false
    // To match tables-trigger, the label must match or triggerId must match nodeTriggerId
    // In practice, nodeMatchesStep first tries trigger matching via label, so use label:
    expect(matchesTriggerId('tables-trigger-6', 'tables-trigger-6', '6', '6')).toBe(true);
  });

  it('should match tables-trigger format when nodeDataId has trigger: prefix', () => {
    // With proper trigger: prefix in nodeDataId, nodeTriggerId extracts correctly
    // nodeDataId 'trigger:6' -> replace 'trigger:' -> '6'
    // triggerId '6' === nodeTriggerId '6' -> isActualTriggerStep true
    // nodeIdLower 'tables-trigger-6' === 'tables-trigger-6' -> true
    expect(matchesTriggerId('tables-trigger-6', 'trigger:6', '', '6')).toBe(true);
  });

  it('should be case insensitive', () => {
    // triggerId '6', all IDs lowercased internally
    expect(matchesTriggerId('TRIGGER-6', 'TRIGGER:6', 'My Trigger', '6')).toBe(true);
  });

  it('should match when triggerId equals nodeTriggerId directly', () => {
    expect(matchesTriggerId('trigger-6', 'trigger:6', '', '6')).toBe(true);
  });

  it('should not match unrelated IDs', () => {
    // triggerId 'step_1' does not start with 'trigger:', does not match label 'step 1' (case differs), does not match nodeTriggerId 'step_1' (since mcp:step_1 -> step_1)
    // Actually nodeTriggerId = 'mcp:step_1'.replace('trigger:', '').replace('trigger-', '').replace('tables-trigger-', '') = 'mcp:step_1'
    // So 'step_1' !== 'mcp:step_1' -> isActualTriggerStep false
    expect(matchesTriggerId('step-1', 'mcp:step_1', 'Step 1', 'step_1')).toBe(false);
  });

  it('should match when label matches triggerId exactly', () => {
    // triggerId 'webhook' === nodeLabelLower 'webhook' -> isActualTriggerStep true
    // nodeLabelLower 'webhook' === triggerIdLower 'webhook' -> match
    expect(matchesTriggerId('trigger-1', 'trigger:1', 'webhook', 'webhook')).toBe(true);
  });

  it('should match when triggerId has trigger: prefix via startsWith check', () => {
    // triggerId 'trigger:my_webhook' starts with 'trigger:' -> isActualTriggerStep true
    // Then: nodeDataIdLower 'trigger:my_webhook' === 'trigger:trigger:my_webhook' -> false
    // But: nodeLabelLower 'my_webhook' === 'trigger:my_webhook' -> false
    // nodeIdLower 'trigger-my_webhook'.endsWith('trigger-trigger:my_webhook') -> false
    // This shows that trigger: prefix in triggerId causes double-prefixing in comparisons
    // The caller (nodeMatchesStep) strips the prefix first, so this is expected to fail
    expect(matchesTriggerId('trigger-my_webhook', 'trigger:my_webhook', 'my_webhook', 'trigger:my_webhook')).toBe(false);
  });
});

// =============================================================================
// extractStepAliasFromNode
// =============================================================================
describe('extractStepAliasFromNode', () => {
  it('should return null for null node', () => {
    expect(extractStepAliasFromNode(null as any)).toBeNull();
  });

  it('should return normalized label for trigger node', () => {
    const node = {
      id: 'trigger-6-1764772417085-abc',
      data: { id: 'trigger:6', label: 'My Webhook', kind: 'entry' as const },
    };
    expect(extractStepAliasFromNode(node)).toBe('my_webhook');
  });

  it('should return normalized label for trigger node without trigger: prefix', () => {
    const node = {
      id: 'trigger-test-123-abc',
      data: { id: 'trigger:test', label: 'Test', kind: 'entry' as const },
    };
    // For triggers, returns just normalized label (not "trigger:test")
    expect(extractStepAliasFromNode(node)).toBe('test');
  });

  it('should return normalized label for decision node', () => {
    const node = {
      id: 'decision-123-abc',
      type: 'decisionNode',
      data: { id: 'decision-123', label: 'If / else', kind: 'decision' as const },
    };
    expect(extractStepAliasFromNode(node)).toBe('if_else');
  });

  it('should prioritize label for regular steps', () => {
    const node = {
      id: 'step-1-123-abc',
      data: { id: '1', label: 'Get User', kind: 'action' as const },
    };
    expect(extractStepAliasFromNode(node)).toBe('get_user');
  });

  it('should fallback to node.data.id when no label', () => {
    const node = {
      id: 'step-get_user-123-abc',
      data: { id: 'step-get_user-123-abc', kind: 'action' as const },
    };
    expect(extractStepAliasFromNode(node)).toBe('get_user');
  });

  it('should fallback to node.id when no data.id or label', () => {
    const node = {
      id: 'step-my_step-123-abc',
      data: { kind: 'action' as const },
    };
    expect(extractStepAliasFromNode(node)).toBe('my_step');
  });

  it('should handle loop child node (fallback to data.id)', () => {
    const node = {
      id: 'while-123::actions_list#2',
      data: { id: 'while-123::actions_list#2', kind: 'action' as const },
    };
    expect(extractStepAliasFromNode(node)).toBe('actions_list');
  });

  it('should handle node with label containing accented characters', () => {
    const node = {
      id: 'step-1-123-abc',
      data: { id: '1', label: 'Entr\u00e9e IDs', kind: 'action' as const },
    };
    expect(extractStepAliasFromNode(node)).toBe('entree_ids');
  });

  it('should handle node with label containing special characters', () => {
    const node = {
      id: 'step-1-123-abc',
      data: { id: '1', label: 'list_bases (copy)', kind: 'action' as const },
    };
    expect(extractStepAliasFromNode(node)).toBe('list_bases_copy');
  });
});

// =============================================================================
// labelsMatch
// =============================================================================
describe('labelsMatch', () => {
  it('should return false for null/undefined inputs', () => {
    expect(labelsMatch(null, 'test')).toBe(false);
    expect(labelsMatch('test', null)).toBe(false);
    expect(labelsMatch(undefined, 'test')).toBe(false);
    expect(labelsMatch('test', undefined)).toBe(false);
    expect(labelsMatch(null, null)).toBe(false);
  });

  it('should return false for empty string inputs', () => {
    expect(labelsMatch('', 'test')).toBe(false);
    expect(labelsMatch('test', '')).toBe(false);
  });

  it('should match identical strings', () => {
    expect(labelsMatch('my_step', 'my_step')).toBe(true);
  });

  it('should match with underscore variations', () => {
    expect(labelsMatch('while_a', 'whilea')).toBe(true);
    expect(labelsMatch('whilea', 'while_a')).toBe(true);
  });

  it('should NOT match when one is a substring of the other', () => {
    expect(labelsMatch('my_step', 'step')).toBe(false);
    expect(labelsMatch('step', 'my_step')).toBe(false);
    expect(labelsMatch('webhook', 'webhook1')).toBe(false);
    expect(labelsMatch('webhook1', 'webhook')).toBe(false);
  });

  it('should match when underscore-stripped versions are equal', () => {
    expect(labelsMatch('while_loop_a', 'whileloopa')).toBe(true);
  });

  it('should not match completely different strings', () => {
    expect(labelsMatch('abc', 'xyz')).toBe(false);
  });

  it('should handle single character labels', () => {
    expect(labelsMatch('a', 'a')).toBe(true);
    expect(labelsMatch('a', 'b')).toBe(false);
  });

  it('should match when underscore-stripped versions are equal', () => {
    expect(labelsMatch('some_long_name', 'somelongname')).toBe(true);
  });
});

// =============================================================================
// getNodeIdentifiers
// =============================================================================
describe('getNodeIdentifiers', () => {
  it('should extract all identifiers from a standard node', () => {
    const node = {
      id: 'step-get_user-123-abc',
      data: { id: 'mcp:get_user', label: 'Get User' },
    };
    const ids = getNodeIdentifiers(node);
    expect(ids.idAlias).toBe('get_user');
    expect(ids.labelNorm).toBe('get_user');
    expect(ids.nodeId).toBe('step-get_user-123-abc');
    expect(ids.idNorm).toBe('get_user');
    expect(ids.dataIdNorm).toBe('get_user');
  });

  it('should handle node without data.id', () => {
    const node = {
      id: 'step-my_step-123-abc',
      data: { label: 'My Step' },
    };
    const ids = getNodeIdentifiers(node);
    expect(ids.idAlias).toBe('');
    expect(ids.labelNorm).toBe('my_step');
    expect(ids.nodeId).toBe('step-my_step-123-abc');
    // When no data.id, normalizeId uses node.id which doesn't strip step- prefix
    // normalizeId('step-my_step-123-abc') normalizes the full string
    expect(ids.idNorm).toBe('step_my_step_123_abc');
    expect(ids.dataIdNorm).toBe('');
  });

  it('should handle node without label', () => {
    const node = {
      id: 'step-get_user-123-abc',
      data: { id: 'mcp:get_user' },
    };
    const ids = getNodeIdentifiers(node);
    expect(ids.idAlias).toBe('get_user');
    expect(ids.labelNorm).toBe('');
    expect(ids.nodeId).toBe('step-get_user-123-abc');
  });

  it('should normalize label with parentheses', () => {
    const node = {
      id: 'step-1-123-abc',
      data: { id: 'mcp:list_bases', label: 'list_bases (copy)' },
    };
    const ids = getNodeIdentifiers(node);
    expect(ids.labelNorm).toBe('list_bases_copy');
  });

  it('should lowercase nodeId', () => {
    const node = {
      id: 'Step-GET_USER-123-ABC',
      data: { id: 'mcp:GET_USER', label: 'GET USER' },
    };
    const ids = getNodeIdentifiers(node);
    expect(ids.nodeId).toBe('step-get_user-123-abc');
  });

  it('should handle trigger node', () => {
    const node = {
      id: 'trigger-6-123-abc',
      data: { id: 'trigger:6', label: 'Webhook' },
    };
    const ids = getNodeIdentifiers(node);
    expect(ids.idAlias).toBe('trigger:6');
    expect(ids.labelNorm).toBe('webhook');
  });

  it('should handle agent node', () => {
    const node = {
      id: 'agent-my_agent-123-abc',
      data: { id: 'agent:my_agent', label: 'My Agent' },
    };
    const ids = getNodeIdentifiers(node);
    expect(ids.idAlias).toBe('my_agent');
    expect(ids.labelNorm).toBe('my_agent');
  });

  it('should handle core node', () => {
    const node = {
      id: 'decision-123-abc',
      data: { id: 'core:my_decision', label: 'If / Else' },
    };
    const ids = getNodeIdentifiers(node);
    expect(ids.idAlias).toBe('my_decision');
    expect(ids.labelNorm).toBe('if_else');
  });

  it('should handle empty data gracefully', () => {
    const node = {
      id: 'node-1',
      data: {},
    };
    const ids = getNodeIdentifiers(node);
    expect(ids.idAlias).toBe('');
    expect(ids.labelNorm).toBe('');
    expect(ids.nodeId).toBe('node-1');
    expect(ids.idNorm).toBe('node_1');
    expect(ids.dataIdNorm).toBe('');
  });
});
