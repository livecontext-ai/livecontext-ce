import { describe, it, expect } from 'vitest';
import { nodeMatchesStep, type BatchStepData } from '../nodeMatcher';
import type { Node } from 'reactflow';
import type { BuilderNodeData } from '../../types';

/**
 * Helper to create a minimal Node<BuilderNodeData> for testing.
 * Only the fields used by nodeMatchesStep are required.
 */
function makeNode(overrides: {
  id: string;
  type?: string;
  dataId?: string;
  label?: string;
  kind?: string;
  dataSourceData?: any;
}): Node<BuilderNodeData> {
  return {
    id: overrides.id,
    type: overrides.type || 'flowNode',
    position: { x: 0, y: 0 },
    data: {
      id: overrides.dataId || overrides.id,
      label: overrides.label || '',
      kind: (overrides.kind || 'action') as any,
      dataSourceData: overrides.dataSourceData,
    } as BuilderNodeData,
  };
}

// =============================================================================
// Trigger node matching
// =============================================================================
describe('nodeMatchesStep - trigger nodes', () => {
  it('should match trigger node with trigger: step id', () => {
    const node = makeNode({
      id: 'trigger-6-1764772417085-abc',
      type: 'triggerNode',
      dataId: 'trigger:6',
      label: 'My Webhook',
      kind: 'entry',
    });
    const step: BatchStepData = { id: 'trigger:6', stepAlias: 'trigger:6' };
    expect(nodeMatchesStep(node, step)).toBe(true);
  });

  it('should match trigger node by label matching step alias', () => {
    const node = makeNode({
      id: 'trigger-test-123-abc',
      type: 'triggerNode',
      dataId: 'trigger:test',
      label: 'Test',
      kind: 'entry',
    });
    const step: BatchStepData = { id: 'trigger:test', stepAlias: 'test' };
    expect(nodeMatchesStep(node, step)).toBe(true);
  });

  it('should match trigger node with datasource data by label', () => {
    const node = makeNode({
      id: 'tables-trigger-6-123-abc',
      type: 'triggerNode',
      dataId: 'tables-trigger-6',
      label: 'Datasource Trigger',
      kind: 'entry',
      dataSourceData: { tableId: '123' },
    });
    // Backend stores step alias as the normalized label
    const step: BatchStepData = { id: 'trigger:datasource_trigger', stepAlias: 'datasource_trigger' };
    expect(nodeMatchesStep(node, step)).toBe(true);
  });

  it('should NOT match trigger node with unrelated mcp step', () => {
    const node = makeNode({
      id: 'trigger-1-1764772417085-abc',
      type: 'triggerNode',
      dataId: 'trigger:1',
      label: 'My Trigger',
      kind: 'entry',
    });
    // Use a clearly different ID that won't collide with trigger's numeric ID
    const step: BatchStepData = { id: 'mcp:fetch_data', stepAlias: 'fetch_data' };
    expect(nodeMatchesStep(node, step)).toBe(false);
  });
});

// =============================================================================
// Alias matching (exact, normalized)
// =============================================================================
describe('nodeMatchesStep - alias matching', () => {
  it('should match by exact alias (idAlias == stepAlias)', () => {
    const node = makeNode({
      id: 'step-get_user-123-abc',
      dataId: 'mcp:get_user',
      label: 'Get User',
    });
    const step: BatchStepData = { stepAlias: 'get_user' };
    expect(nodeMatchesStep(node, step)).toBe(true);
  });

  it('should match by label normalized to step alias', () => {
    const node = makeNode({
      id: 'step-1-123-abc',
      dataId: '1',
      label: 'Get User Profile',
    });
    const step: BatchStepData = { stepAlias: 'get_user_profile' };
    expect(nodeMatchesStep(node, step)).toBe(true);
  });

  it('should match by label to originalStepId', () => {
    const node = makeNode({
      id: 'step-1-123-abc',
      dataId: '1',
      label: 'Send Email',
    });
    const step: BatchStepData = { originalStepId: 'mcp:send_email', stepAlias: '' };
    expect(nodeMatchesStep(node, step)).toBe(true);
  });

  it('should match with underscore variations (while_a vs whilea)', () => {
    const node = makeNode({
      id: 'step-while_a-123-abc',
      dataId: 'mcp:while_a',
      label: 'While A',
    });
    const step: BatchStepData = { stepAlias: 'whilea' };
    expect(nodeMatchesStep(node, step)).toBe(true);
  });
});

// =============================================================================
// Label matching with copy suffix prevention
// =============================================================================
describe('nodeMatchesStep - copy suffix prevention', () => {
  it('should NOT match node_copy with step node (without copy)', () => {
    const node = makeNode({
      id: 'step-my_step_copy-123-abc',
      dataId: 'mcp:my_step_copy',
      label: 'My Step (copy)',
    });
    const step: BatchStepData = { stepAlias: 'my_step', normalizedStepId: 'mcp:my_step' };
    expect(nodeMatchesStep(node, step)).toBe(false);
  });

  it('should NOT match node without copy with step that has copy', () => {
    const node = makeNode({
      id: 'step-my_step-123-abc',
      dataId: 'mcp:my_step',
      label: 'My Step',
    });
    const step: BatchStepData = { stepAlias: 'my_step_copy', normalizedStepId: 'mcp:my_step_copy' };
    expect(nodeMatchesStep(node, step)).toBe(false);
  });

  it('should match node_copy with step_copy (both have copy)', () => {
    const node = makeNode({
      id: 'step-my_step_copy-123-abc',
      dataId: 'mcp:my_step_copy',
      label: 'My Step Copy',
    });
    const step: BatchStepData = { stepAlias: 'my_step_copy' };
    expect(nodeMatchesStep(node, step)).toBe(true);
  });
});

// =============================================================================
// MCP nodes
// =============================================================================
describe('nodeMatchesStep - MCP nodes', () => {
  it('should match MCP node by normalizedStepId', () => {
    const node = makeNode({
      id: 'step-codekit_decode_qr-123-abc',
      dataId: 'mcp:codekit_decode_qr',
      label: 'Codekit Decode QR',
    });
    const step: BatchStepData = { normalizedStepId: 'mcp:codekit_decode_qr', stepAlias: 'codekit_decode_qr' };
    expect(nodeMatchesStep(node, step)).toBe(true);
  });

  it('should match MCP node by step id', () => {
    const node = makeNode({
      id: 'step-my_api_call-123-abc',
      dataId: 'mcp:my_api_call',
      label: 'My API Call',
    });
    const step: BatchStepData = { id: 'mcp:my_api_call' };
    expect(nodeMatchesStep(node, step)).toBe(true);
  });

  it('should match MCP node when labels normalize identically', () => {
    const node = makeNode({
      id: 'step-1-123-abc',
      dataId: '1',
      label: 'Fetch Data',
    });
    const step: BatchStepData = { stepAlias: 'fetch_data' };
    expect(nodeMatchesStep(node, step)).toBe(true);
  });
});

// =============================================================================
// Agent nodes
// =============================================================================
describe('nodeMatchesStep - agent nodes', () => {
  it('should match agent node by alias', () => {
    const node = makeNode({
      id: 'agent-my_agent-123-abc',
      type: 'agentNode',
      dataId: 'agent:my_agent',
      label: 'My Agent',
      kind: 'agent',
    });
    const step: BatchStepData = { stepAlias: 'my_agent' };
    expect(nodeMatchesStep(node, step)).toBe(true);
  });

  it('should match agent node by normalized step ID', () => {
    const node = makeNode({
      id: 'agent-analyzer-123-abc',
      type: 'agentNode',
      dataId: 'agent:analyzer',
      label: 'Analyzer',
      kind: 'agent',
    });
    const step: BatchStepData = { normalizedStepId: 'agent:analyzer', stepAlias: 'analyzer' };
    expect(nodeMatchesStep(node, step)).toBe(true);
  });
});

// =============================================================================
// Core nodes (decision, loop, split, etc.)
// =============================================================================
describe('nodeMatchesStep - core nodes', () => {
  it('should match decision node by label normalization', () => {
    const node = makeNode({
      id: 'decision-123-abc',
      type: 'decisionNode',
      dataId: 'core:if_else',
      label: 'If / Else',
      kind: 'decision',
    });
    const step: BatchStepData = { stepAlias: 'if_else' };
    expect(nodeMatchesStep(node, step)).toBe(true);
  });

  it('should match loop node by alias', () => {
    const node = makeNode({
      id: 'loop-while-123-abc',
      type: 'whileGroupNode',
      dataId: 'core:while_loop',
      label: 'While Loop',
      kind: 'loop',
    });
    const step: BatchStepData = { stepAlias: 'while_loop' };
    expect(nodeMatchesStep(node, step)).toBe(true);
  });

  it('should match split node by alias', () => {
    const node = makeNode({
      id: 'split-my_split-123-abc',
      type: 'splitNode',
      dataId: 'core:my_split',
      label: 'My Split',
      kind: 'split',
    });
    const step: BatchStepData = { stepAlias: 'my_split' };
    expect(nodeMatchesStep(node, step)).toBe(true);
  });
});

// =============================================================================
// ID-based matching (Priority 2)
// =============================================================================
describe('nodeMatchesStep - ID-based matching', () => {
  it('should match by idNorm == stepIdNorm', () => {
    const node = makeNode({
      id: 'step-fetch_data-123-abc',
      dataId: 'mcp:fetch_data',
      label: '',
    });
    const step: BatchStepData = { normalizedStepId: 'mcp:fetch_data' };
    expect(nodeMatchesStep(node, step)).toBe(true);
  });

  it('should match by nodeId == stepIdNorm', () => {
    const node = makeNode({
      id: 'mcp:fetch_data',
      dataId: 'mcp:fetch_data',
      label: '',
    });
    const step: BatchStepData = { normalizedStepId: 'mcp:fetch_data' };
    expect(nodeMatchesStep(node, step)).toBe(true);
  });

  it('should match by dataIdNorm == stepIdNorm', () => {
    const node = makeNode({
      id: 'step-1-123-abc',
      dataId: 'mcp:fetch_data',
      label: '',
    });
    const step: BatchStepData = { normalizedStepId: 'mcp:fetch_data' };
    expect(nodeMatchesStep(node, step)).toBe(true);
  });
});

// =============================================================================
// Edge cases
// =============================================================================
describe('nodeMatchesStep - edge cases', () => {
  it('should handle step with all empty fields', () => {
    const node = makeNode({
      id: 'step-1-123-abc',
      dataId: 'mcp:step_1',
      label: 'Step 1',
    });
    const step: BatchStepData = {};
    expect(nodeMatchesStep(node, step)).toBe(false);
  });

  it('should handle node with no label and no data.id match', () => {
    const node = makeNode({
      id: 'step-xyz-123-abc',
      dataId: 'mcp:xyz',
      label: '',
    });
    const step: BatchStepData = { stepAlias: 'completely_different' };
    expect(nodeMatchesStep(node, step)).toBe(false);
  });

  it('should handle step with only id field', () => {
    const node = makeNode({
      id: 'step-send_email-123-abc',
      dataId: 'mcp:send_email',
      label: 'Send Email',
    });
    const step: BatchStepData = { id: 'mcp:send_email' };
    expect(nodeMatchesStep(node, step)).toBe(true);
  });

  it('should not match completely unrelated node and step', () => {
    const node = makeNode({
      id: 'step-alpha-123-abc',
      dataId: 'mcp:alpha',
      label: 'Alpha',
    });
    const step: BatchStepData = { stepAlias: 'beta', normalizedStepId: 'mcp:beta', id: 'mcp:beta' };
    expect(nodeMatchesStep(node, step)).toBe(false);
  });

  it('should match when idAlias matches stepIdNorm', () => {
    const node = makeNode({
      id: 'step-process_data-123-abc',
      dataId: 'mcp:process_data',
      label: '',
    });
    const step: BatchStepData = { normalizedStepId: 'mcp:process_data', stepAlias: '' };
    expect(nodeMatchesStep(node, step)).toBe(true);
  });

  it('should handle case with accented labels', () => {
    const node = makeNode({
      id: 'step-1-123-abc',
      dataId: '1',
      label: 'Entree des donnees',
    });
    const step: BatchStepData = { stepAlias: 'entree_des_donnees' };
    expect(nodeMatchesStep(node, step)).toBe(true);
  });

  it('should handle node with special characters in label', () => {
    const node = makeNode({
      id: 'step-1-123-abc',
      dataId: '1',
      label: 'Step #1 (test)',
    });
    const step: BatchStepData = { stepAlias: 'step_1_test' };
    expect(nodeMatchesStep(node, step)).toBe(true);
  });
});
