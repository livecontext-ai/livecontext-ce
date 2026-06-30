/**
 * E2E-style tests for real-time statusCount updates via streaming.
 *
 * These tests verify that ALL node types correctly receive statusCounts
 * from streaming batch-update events. The key issue being tested:
 *   - SnapshotService sends {id, label, status, statusCounts} but NOT stepAlias/normalizedStepId
 *   - Agent/guardrail/classify/CRUD nodes have static data.id (e.g. "ai-agent")
 *   - nodeMatchesStep must still match them via labelNorm vs stepIdNorm
 *   - Loop children must be updated alongside the loop node itself
 */

import { describe, it, expect } from 'vitest';
import { nodeMatchesStep } from '../nodeMatcher';
import { updateNodesFromBatchSteps, type BatchStepData } from '../statusUpdater';
import type { Node } from 'reactflow';
import type { BuilderNodeData } from '../../types';

// ============================================================================
// Helpers
// ============================================================================

/**
 * Creates a minimal Node<BuilderNodeData> matching the real node structures
 * produced by the workflow builder (drag-drop and plan import).
 */
function makeNode(overrides: {
  id: string;
  type?: string;
  dataId?: string;
  label?: string;
  kind?: string;
  dataSourceData?: any;
  loopChildren?: Array<{ id: string; label?: string; status?: any; statusCounts?: any }>;
}): Node<BuilderNodeData> {
  return {
    id: overrides.id,
    type: overrides.type || 'flowNode',
    position: { x: 0, y: 0 },
    data: {
      id: overrides.dataId ?? overrides.id,
      label: overrides.label || '',
      kind: (overrides.kind || 'action') as any,
      dataSourceData: overrides.dataSourceData,
      loopChildren: overrides.loopChildren,
    } as BuilderNodeData,
  };
}

/**
 * Creates a streaming batch step exactly like SnapshotService sends them.
 * The backend sends {id, label, status, statusCounts} - NO stepAlias, NO normalizedStepId.
 */
function makeSseStep(overrides: {
  id: string;
  label?: string;
  status?: string;
  statusCounts?: Record<string, number>;
}): BatchStepData {
  const step: any = {
    id: overrides.id,
    label: overrides.label ?? overrides.id.replace(/^(mcp:|agent:|core:|table:|trigger:)/, ''),
    status: overrides.status ?? 'completed',
    statusCounts: overrides.statusCounts ?? { running: 0, completed: 1, failed: 0, skipped: 0, total: 1 },
  };
  // Intentionally NOT setting stepAlias or normalizedStepId - mimics real streaming data
  return step as BatchStepData;
}

/**
 * Creates a REST API batch step (has stepAlias populated).
 * Used as baseline comparison.
 */
function makeRestStep(overrides: {
  id: string;
  stepAlias: string;
  status?: string;
  statusCounts?: Record<string, number>;
}): BatchStepData {
  return {
    id: overrides.id,
    normalizedStepId: overrides.id,
    stepAlias: overrides.stepAlias,
    status: overrides.status ?? 'completed',
    statusCounts: overrides.statusCounts ?? { running: 0, completed: 1, failed: 0, skipped: 0, total: 1 },
  };
}

// ============================================================================
// SECTION 1: nodeMatchesStep - streaming format (no stepAlias)
// Tests that node matching works when only step.id is available
// ============================================================================

describe('nodeMatchesStep - streaming format (no stepAlias)', () => {
  // ---- Agent nodes (static data.id = "ai-agent") ----
  describe('Agent nodes (plan import - data.id = "ai-agent")', () => {
    it('should match agent node by label when streaming sends agent:label', () => {
      const node = makeNode({
        id: 'agent-My Agent-1707123456789-abc123',
        type: 'flowNode',
        dataId: 'ai-agent',
        label: 'My Agent',
        kind: 'agent',
      });
      const step: BatchStepData = { id: 'agent:my_agent' };
      expect(nodeMatchesStep(node, step)).toBe(true);
    });

    it('should match agent node with complex label', () => {
      const node = makeNode({
        id: 'agent-Data Analyzer Pro-1707123456789-xyz',
        type: 'flowNode',
        dataId: 'ai-agent',
        label: 'Data Analyzer Pro',
        kind: 'agent',
      });
      const step: BatchStepData = { id: 'agent:data_analyzer_pro' };
      expect(nodeMatchesStep(node, step)).toBe(true);
    });

    it('should match agent node with accented label', () => {
      const node = makeNode({
        id: 'agent-Analyseur-1707123456789-abc',
        type: 'flowNode',
        dataId: 'ai-agent',
        label: 'Analyseur',
        kind: 'agent',
      });
      const step: BatchStepData = { id: 'agent:analyseur' };
      expect(nodeMatchesStep(node, step)).toBe(true);
    });

    it('should NOT match different agent nodes', () => {
      const node = makeNode({
        id: 'agent-Agent Alpha-1707123456789-abc',
        type: 'flowNode',
        dataId: 'ai-agent',
        label: 'Agent Alpha',
        kind: 'agent',
      });
      const step: BatchStepData = { id: 'agent:agent_beta' };
      expect(nodeMatchesStep(node, step)).toBe(false);
    });
  });

  // ---- Agent nodes (drag-drop - data.id = "ai-agent-{timestamp}") ----
  describe('Agent nodes (drag-drop - data.id = "ai-agent-{ts}")', () => {
    it('should match drag-drop agent node by label', () => {
      const node = makeNode({
        id: 'ai-agent-1707123456789',
        type: 'flowNode',
        dataId: 'ai-agent-1707123456789',
        label: 'Summarizer',
        kind: 'agent',
      });
      const step: BatchStepData = { id: 'agent:summarizer' };
      expect(nodeMatchesStep(node, step)).toBe(true);
    });
  });

  // ---- Guardrail nodes (static data.id = "guardrail") ----
  describe('Guardrail nodes (plan import - data.id = "guardrail")', () => {
    it('should match guardrail node by label', () => {
      const node = makeNode({
        id: 'guardrail-PII Check-1707123456789-abc',
        type: 'guardrailNode',
        dataId: 'guardrail',
        label: 'PII Check',
        kind: 'action',
      });
      const step: BatchStepData = { id: 'agent:pii_check' };
      expect(nodeMatchesStep(node, step)).toBe(true);
    });

    it('should match guardrail node with complex label', () => {
      const node = makeNode({
        id: 'guardrail-Content Safety-1707123456789-xyz',
        type: 'guardrailNode',
        dataId: 'guardrail',
        label: 'Content Safety',
        kind: 'action',
      });
      const step: BatchStepData = { id: 'agent:content_safety' };
      expect(nodeMatchesStep(node, step)).toBe(true);
    });
  });

  // ---- Classify nodes (static data.id = "classify") ----
  describe('Classify nodes (plan import - data.id = "classify")', () => {
    it('should match classify node by label', () => {
      const node = makeNode({
        id: 'classify-Intent Router-1707123456789-abc',
        type: 'classifyNode',
        dataId: 'classify',
        label: 'Intent Router',
        kind: 'action',
      });
      const step: BatchStepData = { id: 'agent:intent_router' };
      expect(nodeMatchesStep(node, step)).toBe(true);
    });
  });

  // ---- CRUD/Table nodes (static data.id = "create-row" etc.) ----
  describe('CRUD/Table nodes (plan import - data.id = operation name)', () => {
    it('should match crud create-row node by label', () => {
      const node = makeNode({
        id: 'crud-create-row-1707123456789-abc',
        type: 'crudNode',
        dataId: 'create-row',
        label: 'Insert User',
        kind: 'action',
      });
      const step: BatchStepData = { id: 'table:insert_user' };
      expect(nodeMatchesStep(node, step)).toBe(true);
    });

    it('should match crud read-row node by label', () => {
      const node = makeNode({
        id: 'crud-read-row-1707123456789-abc',
        type: 'crudNode',
        dataId: 'read-row',
        label: 'Fetch Records',
        kind: 'action',
      });
      const step: BatchStepData = { id: 'table:fetch_records' };
      expect(nodeMatchesStep(node, step)).toBe(true);
    });

    it('should NOT match different crud nodes', () => {
      const node = makeNode({
        id: 'crud-create-row-1707123456789-abc',
        type: 'crudNode',
        dataId: 'create-row',
        label: 'Insert User',
        kind: 'action',
      });
      const step: BatchStepData = { id: 'table:delete_user' };
      expect(nodeMatchesStep(node, step)).toBe(false);
    });
  });

  // ---- MCP nodes (unique data.id - should still work) ----
  describe('MCP nodes (plan import - data.id = unique step id)', () => {
    it('should match MCP node by data.id extraction', () => {
      const node = makeNode({
        id: 'step-Fetch Data-1707123456789-abc',
        type: 'flowNode',
        dataId: 'step-Fetch Data-1707123456789-abc',
        label: 'Fetch Data',
        kind: 'action',
      });
      const step: BatchStepData = { id: 'mcp:fetch_data' };
      expect(nodeMatchesStep(node, step)).toBe(true);
    });

    it('should match MCP node by label fallback', () => {
      const node = makeNode({
        id: 'step-1-1707123456789-abc',
        type: 'flowNode',
        dataId: '1',
        label: 'Send Email',
        kind: 'action',
      });
      const step: BatchStepData = { id: 'mcp:send_email' };
      expect(nodeMatchesStep(node, step)).toBe(true);
    });
  });

  // ---- Trigger nodes ----
  describe('Trigger nodes (streaming format)', () => {
    it('should match trigger node by trigger: prefix', () => {
      const node = makeNode({
        id: 'trigger-6-1764772417085-abc',
        type: 'triggerNode',
        dataId: 'trigger:6',
        label: 'My Webhook',
        kind: 'entry',
      });
      const step: BatchStepData = { id: 'trigger:my_webhook' };
      expect(nodeMatchesStep(node, step)).toBe(true);
    });
  });
});

// ============================================================================
// SECTION 2: updateNodesFromBatchSteps - Full pipeline test
// Simulates exactly what happens when streaming batch-update arrives
// ============================================================================

describe('updateNodesFromBatchSteps - streaming statusCount pipeline', () => {
  // ---- Agent node statusCounts ----
  describe('Agent nodes receive statusCounts from streaming', () => {
    it('should update agent node (plan import, static data.id) with statusCounts', () => {
      const nodes = [
        makeNode({
          id: 'agent-My Agent-1707123456789-abc',
          type: 'flowNode',
          dataId: 'ai-agent',
          label: 'My Agent',
          kind: 'agent',
        }),
      ];
      const sseSteps = [
        makeSseStep({
          id: 'agent:my_agent',
          status: 'completed',
          statusCounts: { running: 0, completed: 3, failed: 1, skipped: 0, total: 4 },
        }),
      ];

      const result = updateNodesFromBatchSteps(nodes, sseSteps);
      expect(result[0].data.statusCounts).toBeDefined();
      expect(result[0].data.statusCounts?.COMPLETED).toBe(3);
      expect(result[0].data.statusCounts?.FAILED).toBe(1);
      expect(result[0].data.status).not.toBe('pending');
    });

    it('should update agent node (drag-drop) with statusCounts', () => {
      const nodes = [
        makeNode({
          id: 'ai-agent-1707123456789',
          type: 'flowNode',
          dataId: 'ai-agent-1707123456789',
          label: 'My Analyzer',
          kind: 'agent',
        }),
      ];
      const sseSteps = [
        makeSseStep({
          id: 'agent:my_analyzer',
          status: 'running',
          statusCounts: { running: 2, completed: 0, failed: 0, skipped: 0, total: 2 },
        }),
      ];

      const result = updateNodesFromBatchSteps(nodes, sseSteps);
      expect(result[0].data.statusCounts).toBeDefined();
      expect(result[0].data.statusCounts?.RUNNING).toBe(2);
      expect(result[0].data.status).toBe('running');
    });

    it('should correctly match among multiple agent nodes', () => {
      const nodes = [
        makeNode({
          id: 'agent-Alpha-1707123456789-abc',
          type: 'flowNode',
          dataId: 'ai-agent',
          label: 'Alpha',
          kind: 'agent',
        }),
        makeNode({
          id: 'agent-Beta-1707123456790-def',
          type: 'flowNode',
          dataId: 'ai-agent',
          label: 'Beta',
          kind: 'agent',
        }),
      ];
      const sseSteps = [
        makeSseStep({
          id: 'agent:alpha',
          status: 'completed',
          statusCounts: { running: 0, completed: 5, failed: 0, skipped: 0, total: 5 },
        }),
        makeSseStep({
          id: 'agent:beta',
          status: 'running',
          statusCounts: { running: 1, completed: 2, failed: 0, skipped: 0, total: 3 },
        }),
      ];

      const result = updateNodesFromBatchSteps(nodes, sseSteps);

      // Alpha should be completed with 5 success
      expect(result[0].data.statusCounts?.COMPLETED).toBe(5);
      expect(result[0].data.status).toBe('completed');

      // Beta should be running with 1 running, 2 success
      expect(result[1].data.statusCounts?.RUNNING).toBe(1);
      expect(result[1].data.statusCounts?.COMPLETED).toBe(2);
      expect(result[1].data.status).toBe('running');
    });
  });

  // ---- Guardrail node statusCounts ----
  describe('Guardrail nodes receive statusCounts from streaming', () => {
    it('should update guardrail node with statusCounts', () => {
      const nodes = [
        makeNode({
          id: 'guardrail-PII Check-1707123456789-abc',
          type: 'guardrailNode',
          dataId: 'guardrail',
          label: 'PII Check',
        }),
      ];
      const sseSteps = [
        makeSseStep({
          id: 'agent:pii_check',
          status: 'completed',
          statusCounts: { running: 0, completed: 10, failed: 0, skipped: 0, total: 10 },
        }),
      ];

      const result = updateNodesFromBatchSteps(nodes, sseSteps);
      expect(result[0].data.statusCounts).toBeDefined();
      expect(result[0].data.statusCounts?.COMPLETED).toBe(10);
    });
  });

  // ---- Classify node statusCounts ----
  describe('Classify nodes receive statusCounts from streaming', () => {
    it('should update classify node with statusCounts', () => {
      const nodes = [
        makeNode({
          id: 'classify-Intent Router-1707123456789-abc',
          type: 'classifyNode',
          dataId: 'classify',
          label: 'Intent Router',
        }),
      ];
      const sseSteps = [
        makeSseStep({
          id: 'agent:intent_router',
          status: 'completed',
          statusCounts: { running: 0, completed: 7, failed: 0, skipped: 3, total: 10 },
        }),
      ];

      const result = updateNodesFromBatchSteps(nodes, sseSteps);
      expect(result[0].data.statusCounts).toBeDefined();
      expect(result[0].data.statusCounts?.COMPLETED).toBe(7);
      expect(result[0].data.statusCounts?.SKIPPED).toBe(3);
    });
  });

  // ---- CRUD/Table node statusCounts ----
  describe('CRUD/Table nodes receive statusCounts from streaming', () => {
    it('should update CRUD node with statusCounts', () => {
      const nodes = [
        makeNode({
          id: 'crud-create-row-1707123456789-abc',
          type: 'crudNode',
          dataId: 'create-row',
          label: 'Insert User',
        }),
      ];
      const sseSteps = [
        makeSseStep({
          id: 'table:insert_user',
          status: 'completed',
          statusCounts: { running: 0, completed: 50, failed: 2, skipped: 0, total: 52 },
        }),
      ];

      const result = updateNodesFromBatchSteps(nodes, sseSteps);
      expect(result[0].data.statusCounts).toBeDefined();
      expect(result[0].data.statusCounts?.COMPLETED).toBe(50);
      expect(result[0].data.statusCounts?.FAILED).toBe(2);
    });
  });

  // ---- MCP/flowNode statusCounts ----
  describe('MCP nodes receive statusCounts from streaming', () => {
    it('should update MCP node (unique data.id) with statusCounts', () => {
      const nodes = [
        makeNode({
          id: 'step-Fetch Data-1707123456789-abc',
          type: 'flowNode',
          dataId: 'step-Fetch Data-1707123456789-abc',
          label: 'Fetch Data',
        }),
      ];
      const sseSteps = [
        makeSseStep({
          id: 'mcp:fetch_data',
          status: 'completed',
          statusCounts: { running: 0, completed: 1, failed: 0, skipped: 0, total: 1 },
        }),
      ];

      const result = updateNodesFromBatchSteps(nodes, sseSteps);
      expect(result[0].data.statusCounts).toBeDefined();
      expect(result[0].data.statusCounts?.COMPLETED).toBe(1);
    });

    it('should update MCP node (numeric data.id) with statusCounts via label', () => {
      const nodes = [
        makeNode({
          id: 'step-1-1707123456789-abc',
          type: 'flowNode',
          dataId: '1',
          label: 'Send Email',
        }),
      ];
      const sseSteps = [
        makeSseStep({
          id: 'mcp:send_email',
          status: 'completed',
          statusCounts: { running: 0, completed: 1, failed: 0, skipped: 0, total: 1 },
        }),
      ];

      const result = updateNodesFromBatchSteps(nodes, sseSteps);
      expect(result[0].data.statusCounts).toBeDefined();
      expect(result[0].data.statusCounts?.COMPLETED).toBe(1);
    });
  });

  // ---- Core nodes (decision, switch, etc.) statusCounts ----
  describe('Core nodes receive statusCounts from streaming', () => {
    it('should update decision node with statusCounts', () => {
      const nodes = [
        makeNode({
          id: 'decision-Check Status-1707123456789-abc',
          type: 'decisionNode',
          dataId: 'core:check_status',
          label: 'Check Status',
          kind: 'decision',
        }),
      ];
      const sseSteps = [
        makeSseStep({
          id: 'core:check_status',
          status: 'completed',
          statusCounts: { running: 0, completed: 5, failed: 0, skipped: 0, total: 5 },
        }),
      ];

      const result = updateNodesFromBatchSteps(nodes, sseSteps);
      expect(result[0].data.statusCounts).toBeDefined();
      expect(result[0].data.statusCounts?.COMPLETED).toBe(5);
    });

    it('should update switch node with statusCounts', () => {
      const nodes = [
        makeNode({
          id: 'switch-Route-1707123456789-abc',
          type: 'switchNode',
          dataId: 'core:route',
          label: 'Route',
          kind: 'switch',
        }),
      ];
      const sseSteps = [
        makeSseStep({
          id: 'core:route',
          status: 'completed',
          statusCounts: { running: 0, completed: 3, failed: 0, skipped: 2, total: 5 },
        }),
      ];

      const result = updateNodesFromBatchSteps(nodes, sseSteps);
      expect(result[0].data.statusCounts?.COMPLETED).toBe(3);
      expect(result[0].data.statusCounts?.SKIPPED).toBe(2);
    });

    it('should update split node with statusCounts', () => {
      const nodes = [
        makeNode({
          id: 'split-Process Items-1707123456789-abc',
          type: 'splitNode',
          dataId: 'core:process_items',
          label: 'Process Items',
          kind: 'split',
        }),
      ];
      const sseSteps = [
        makeSseStep({
          id: 'core:process_items',
          status: 'running',
          statusCounts: { running: 3, completed: 7, failed: 0, skipped: 0, total: 10 },
        }),
      ];

      const result = updateNodesFromBatchSteps(nodes, sseSteps);
      expect(result[0].data.statusCounts?.RUNNING).toBe(3);
      expect(result[0].data.statusCounts?.COMPLETED).toBe(7);
    });

    it('should update fork node with statusCounts', () => {
      const nodes = [
        makeNode({
          id: 'fork-Parallel-1707123456789-abc',
          type: 'forkNode',
          dataId: 'core:parallel',
          label: 'Parallel',
          kind: 'fork',
        }),
      ];
      const sseSteps = [
        makeSseStep({
          id: 'core:parallel',
          status: 'completed',
          statusCounts: { running: 0, completed: 2, failed: 0, skipped: 0, total: 2 },
        }),
      ];

      const result = updateNodesFromBatchSteps(nodes, sseSteps);
      expect(result[0].data.statusCounts?.COMPLETED).toBe(2);
    });

    it('should update merge node with statusCounts', () => {
      const nodes = [
        makeNode({
          id: 'merge-Wait All-1707123456789-abc',
          type: 'mergeNode',
          dataId: 'core:wait_all',
          label: 'Wait All',
          kind: 'merge',
        }),
      ];
      const sseSteps = [
        makeSseStep({
          id: 'core:wait_all',
          status: 'completed',
          statusCounts: { running: 0, completed: 1, failed: 0, skipped: 0, total: 1 },
        }),
      ];

      const result = updateNodesFromBatchSteps(nodes, sseSteps);
      expect(result[0].data.statusCounts?.COMPLETED).toBe(1);
    });

    it('should update transform node (flowNode kind) with statusCounts', () => {
      const nodes = [
        makeNode({
          id: 'transform-Convert-1707123456789-abc',
          type: 'flowNode',
          dataId: 'core:convert',
          label: 'Convert',
          kind: 'transform',
        }),
      ];
      const sseSteps = [
        makeSseStep({
          id: 'core:convert',
          status: 'completed',
          statusCounts: { running: 0, completed: 1, failed: 0, skipped: 0, total: 1 },
        }),
      ];

      const result = updateNodesFromBatchSteps(nodes, sseSteps);
      expect(result[0].data.statusCounts?.COMPLETED).toBe(1);
    });

    it('should update wait node (flowNode kind) with statusCounts', () => {
      const nodes = [
        makeNode({
          id: 'wait-Delay-1707123456789-abc',
          type: 'flowNode',
          dataId: 'core:delay',
          label: 'Delay',
          kind: 'wait',
        }),
      ];
      const sseSteps = [
        makeSseStep({
          id: 'core:delay',
          status: 'completed',
          statusCounts: { running: 0, completed: 1, failed: 0, skipped: 0, total: 1 },
        }),
      ];

      const result = updateNodesFromBatchSteps(nodes, sseSteps);
      expect(result[0].data.statusCounts?.COMPLETED).toBe(1);
    });

    it('should update exit node with statusCounts', () => {
      const nodes = [
        makeNode({
          id: 'exit-End-1707123456789-abc',
          type: 'exitNode',
          dataId: 'core:end',
          label: 'End',
          kind: 'exit',
        }),
      ];
      const sseSteps = [
        makeSseStep({
          id: 'core:end',
          status: 'completed',
          statusCounts: { running: 0, completed: 1, failed: 0, skipped: 0, total: 1 },
        }),
      ];

      const result = updateNodesFromBatchSteps(nodes, sseSteps);
      expect(result[0].data.statusCounts?.COMPLETED).toBe(1);
    });

    it('should update response node with statusCounts', () => {
      const nodes = [
        makeNode({
          id: 'response-Reply-1707123456789-abc',
          type: 'responseNode',
          dataId: 'core:reply',
          label: 'Reply',
          kind: 'response',
        }),
      ];
      const sseSteps = [
        makeSseStep({
          id: 'core:reply',
          status: 'completed',
          statusCounts: { running: 0, completed: 1, failed: 0, skipped: 0, total: 1 },
        }),
      ];

      const result = updateNodesFromBatchSteps(nodes, sseSteps);
      expect(result[0].data.statusCounts?.COMPLETED).toBe(1);
    });

    it('should update aggregate node with statusCounts', () => {
      const nodes = [
        makeNode({
          id: 'aggregate-Collect-1707123456789-abc',
          type: 'aggregateNode',
          dataId: 'core:collect',
          label: 'Collect',
          kind: 'aggregate',
        }),
      ];
      const sseSteps = [
        makeSseStep({
          id: 'core:collect',
          status: 'completed',
          statusCounts: { running: 0, completed: 1, failed: 0, skipped: 0, total: 1 },
        }),
      ];

      const result = updateNodesFromBatchSteps(nodes, sseSteps);
      expect(result[0].data.statusCounts?.COMPLETED).toBe(1);
    });

    it('should update download_file node with statusCounts', () => {
      const nodes = [
        makeNode({
          id: 'download-Get File-1707123456789-abc',
          type: 'flowNode',
          dataId: 'core:get_file',
          label: 'Get File',
          kind: 'download_file',
        }),
      ];
      const sseSteps = [
        makeSseStep({
          id: 'core:get_file',
          status: 'completed',
          statusCounts: { running: 0, completed: 1, failed: 0, skipped: 0, total: 1 },
        }),
      ];

      const result = updateNodesFromBatchSteps(nodes, sseSteps);
      expect(result[0].data.statusCounts?.COMPLETED).toBe(1);
    });

    it('should update http_request node with statusCounts', () => {
      const nodes = [
        makeNode({
          id: 'http-Call API-1707123456789-abc',
          type: 'flowNode',
          dataId: 'core:call_api',
          label: 'Call API',
          kind: 'http_request',
        }),
      ];
      const sseSteps = [
        makeSseStep({
          id: 'core:call_api',
          status: 'completed',
          statusCounts: { running: 0, completed: 1, failed: 0, skipped: 0, total: 1 },
        }),
      ];

      const result = updateNodesFromBatchSteps(nodes, sseSteps);
      expect(result[0].data.statusCounts?.COMPLETED).toBe(1);
    });
  });

  // ---- While group node statusCounts ----
  describe('WhileGroupNode receives statusCounts from streaming', () => {
    it('should update while group node with statusCounts via core: matching', () => {
      const nodes = [
        makeNode({
          id: 'while-Process Items-1707123456789-abc',
          type: 'whileGroupNode',
          dataId: 'core:process_items',
          label: 'Process Items',
          kind: 'loop',
        }),
      ];
      const sseSteps = [
        makeSseStep({
          id: 'core:process_items',
          status: 'running',
          statusCounts: { running: 1, completed: 4, failed: 0, skipped: 0, total: 5 },
        }),
      ];

      const result = updateNodesFromBatchSteps(nodes, sseSteps);

      // While group node should have statusCounts
      expect(result[0].data.statusCounts).toBeDefined();
      expect(result[0].data.statusCounts?.RUNNING).toBe(1);
      expect(result[0].data.statusCounts?.COMPLETED).toBe(4);
    });

    it('should update child body nodes independently as separate React Flow nodes', () => {
      const nodes = [
        makeNode({
          id: 'while-My Loop-1707123456789-abc',
          type: 'whileGroupNode',
          dataId: 'core:my_loop',
          label: 'My Loop',
          kind: 'loop',
        }),
        makeNode({
          id: 'step-process-123',
          type: 'flowNode',
          dataId: 'mcp:process',
          label: 'Process',
          kind: 'action',
        }),
      ];
      const sseSteps = [
        makeSseStep({
          id: 'core:my_loop',
          status: 'running',
          statusCounts: { running: 1, completed: 2, failed: 0, skipped: 0, total: 3 },
        }),
        makeSseStep({
          id: 'mcp:process',
          status: 'completed',
          statusCounts: { running: 0, completed: 3, failed: 0, skipped: 0, total: 3 },
        }),
      ];

      const result = updateNodesFromBatchSteps(nodes, sseSteps);

      // While group node updated
      expect(result[0].data.statusCounts).toBeDefined();
      expect(result[0].data.statusCounts?.RUNNING).toBe(1);

      // Body child updated independently
      expect(result[1].data.statusCounts).toBeDefined();
      expect(result[1].data.statusCounts?.COMPLETED).toBe(3);
    });
  });

  // ---- Trigger node statusCounts ----
  describe('Trigger nodes receive statusCounts from streaming', () => {
    it('should update trigger node with statusCounts', () => {
      const nodes = [
        makeNode({
          id: 'trigger-6-1764772417085-abc',
          type: 'triggerNode',
          dataId: 'trigger:6',
          label: 'My Webhook',
          kind: 'entry',
        }),
      ];
      const sseSteps = [
        makeSseStep({
          id: 'trigger:my_webhook',
          status: 'completed',
          statusCounts: { running: 0, completed: 1, failed: 0, skipped: 0, total: 1 },
        }),
      ];

      const result = updateNodesFromBatchSteps(nodes, sseSteps);
      expect(result[0].data.statusCounts).toBeDefined();
      expect(result[0].data.statusCounts?.COMPLETED).toBe(1);
    });
  });

  // ---- Mixed workflow with all node types ----
  describe('Mixed workflow - all node types in one workflow', () => {
    it('should update ALL node types simultaneously from streaming batch', () => {
      const nodes = [
        // Trigger
        makeNode({
          id: 'trigger-start-1707123456789-abc',
          type: 'triggerNode',
          dataId: 'trigger:start',
          label: 'Start',
          kind: 'entry',
        }),
        // Agent (plan import - static data.id)
        makeNode({
          id: 'agent-Summarizer-1707123456790-def',
          type: 'flowNode',
          dataId: 'ai-agent',
          label: 'Summarizer',
          kind: 'agent',
        }),
        // MCP
        makeNode({
          id: 'step-Send Email-1707123456791-ghi',
          type: 'flowNode',
          dataId: 'step-Send Email-1707123456791-ghi',
          label: 'Send Email',
          kind: 'action',
        }),
        // Decision (core)
        makeNode({
          id: 'decision-Check-1707123456792-jkl',
          type: 'decisionNode',
          dataId: 'core:check',
          label: 'Check',
          kind: 'decision',
        }),
        // Guardrail
        makeNode({
          id: 'guardrail-Safety-1707123456793-mno',
          type: 'guardrailNode',
          dataId: 'guardrail',
          label: 'Safety',
        }),
        // CRUD
        makeNode({
          id: 'crud-create-row-1707123456794-pqr',
          type: 'crudNode',
          dataId: 'create-row',
          label: 'Save Record',
        }),
      ];

      const sseSteps = [
        makeSseStep({ id: 'trigger:start', status: 'completed', statusCounts: { running: 0, completed: 1, failed: 0, skipped: 0, total: 1 } }),
        makeSseStep({ id: 'agent:summarizer', status: 'completed', statusCounts: { running: 0, completed: 1, failed: 0, skipped: 0, total: 1 } }),
        makeSseStep({ id: 'mcp:send_email', status: 'completed', statusCounts: { running: 0, completed: 1, failed: 0, skipped: 0, total: 1 } }),
        makeSseStep({ id: 'core:check', status: 'completed', statusCounts: { running: 0, completed: 1, failed: 0, skipped: 0, total: 1 } }),
        makeSseStep({ id: 'agent:safety', status: 'completed', statusCounts: { running: 0, completed: 1, failed: 0, skipped: 0, total: 1 } }),
        makeSseStep({ id: 'table:save_record', status: 'completed', statusCounts: { running: 0, completed: 1, failed: 0, skipped: 0, total: 1 } }),
      ];

      const result = updateNodesFromBatchSteps(nodes, sseSteps);

      // ALL nodes should have statusCounts
      result.forEach((node, i) => {
        expect(result[i].data.statusCounts).toBeDefined();
        expect(result[i].data.statusCounts?.COMPLETED).toBe(1);
        expect(result[i].data.status).not.toBe('pending');
      });
    });
  });

  // ---- Status derivation from statusCounts ----
  describe('Status derivation from statusCounts', () => {
    it('should derive running status when running > 0', () => {
      const nodes = [
        makeNode({ id: 'agent-X-123-abc', type: 'flowNode', dataId: 'ai-agent', label: 'X', kind: 'agent' }),
      ];
      const sseSteps = [
        makeSseStep({ id: 'agent:x', status: 'running', statusCounts: { running: 2, completed: 3, failed: 0, skipped: 0, total: 5 } }),
      ];

      const result = updateNodesFromBatchSteps(nodes, sseSteps);
      expect(result[0].data.status).toBe('running');
    });

    it('should derive error status when only failures', () => {
      const nodes = [
        makeNode({ id: 'agent-X-123-abc', type: 'flowNode', dataId: 'ai-agent', label: 'X', kind: 'agent' }),
      ];
      const sseSteps = [
        makeSseStep({ id: 'agent:x', status: 'failed', statusCounts: { running: 0, completed: 0, failed: 3, skipped: 0, total: 3 } }),
      ];

      const result = updateNodesFromBatchSteps(nodes, sseSteps);
      expect(result[0].data.status).toBe('failed');
    });

    it('should derive partial_success status when mix of success and failure', () => {
      const nodes = [
        makeNode({ id: 'agent-X-123-abc', type: 'flowNode', dataId: 'ai-agent', label: 'X', kind: 'agent' }),
      ];
      // Backend sends partial_success as the raw status when there's a mix of success and failure.
      // The source prioritizes raw status over deriveStatusFromCounts, so status must match.
      const sseSteps = [
        makeSseStep({ id: 'agent:x', status: 'partial_success', statusCounts: { running: 0, completed: 5, failed: 2, skipped: 0, total: 7 } }),
      ];

      const result = updateNodesFromBatchSteps(nodes, sseSteps);
      expect(result[0].data.status).toBe('partial_success');
    });

    it('should derive skipped status when only skipped', () => {
      const nodes = [
        makeNode({ id: 'agent-X-123-abc', type: 'flowNode', dataId: 'ai-agent', label: 'X', kind: 'agent' }),
      ];
      const sseSteps = [
        makeSseStep({ id: 'agent:x', status: 'skipped', statusCounts: { running: 0, completed: 0, failed: 0, skipped: 3, total: 3 } }),
      ];

      const result = updateNodesFromBatchSteps(nodes, sseSteps);
      expect(result[0].data.status).toBe('skipped');
    });
  });

  // ---- Interface node statusCounts ----
  describe('Interface nodes receive statusCounts from streaming', () => {
    it('should update interface node with awaiting_signal status', () => {
      const nodes = [
        makeNode({
          id: 'interface-My Form-1707123456789-abc',
          type: 'interfacePreviewNode',
          dataId: 'interface:my_form',
          label: 'My Form',
          kind: 'interface',
        }),
      ];
      const sseSteps = [
        makeSseStep({
          id: 'interface:my_form',
          status: 'awaiting_signal',
          statusCounts: { AWAITING_SIGNAL: 1 },
        }),
      ];

      const result = updateNodesFromBatchSteps(nodes, sseSteps);
      expect(result[0].data.status).toBe('awaiting_signal');
    });

    it('should update interface node with completed status', () => {
      const nodes = [
        makeNode({
          id: 'interface-Dashboard-1707123456789-abc',
          type: 'interfacePreviewNode',
          dataId: 'interface:dashboard',
          label: 'Dashboard',
          kind: 'interface',
        }),
      ];
      const sseSteps = [
        makeSseStep({
          id: 'interface:dashboard',
          status: 'completed',
          statusCounts: { running: 0, completed: 1, failed: 0, skipped: 0, total: 1 },
        }),
      ];

      const result = updateNodesFromBatchSteps(nodes, sseSteps);
      expect(result[0].data.statusCounts).toBeDefined();
      expect(result[0].data.statusCounts?.COMPLETED).toBe(1);
      expect(result[0].data.status).toBe('completed');
    });
  });

  // ---- No false matches ----
  describe('No false matches', () => {
    it('should not update node when no streaming step matches', () => {
      const nodes = [
        makeNode({ id: 'agent-Alpha-123-abc', type: 'flowNode', dataId: 'ai-agent', label: 'Alpha', kind: 'agent' }),
      ];
      const sseSteps = [
        makeSseStep({ id: 'agent:completely_different', status: 'completed' }),
      ];

      const result = updateNodesFromBatchSteps(nodes, sseSteps);
      expect(result[0].data.statusCounts).toBeUndefined();
      expect(result[0].data.status).toBeUndefined();
    });

    it('should not cross-match agent steps with CRUD nodes', () => {
      const nodes = [
        makeNode({ id: 'crud-create-row-123-abc', type: 'crudNode', dataId: 'create-row', label: 'Insert User' }),
      ];
      const sseSteps = [
        makeSseStep({ id: 'agent:insert_something_else', status: 'completed' }),
      ];

      const result = updateNodesFromBatchSteps(nodes, sseSteps);
      expect(result[0].data.statusCounts).toBeUndefined();
    });
  });
});
