import { describe, it, expect } from 'vitest';
import type { Node } from 'reactflow';
import type { BuilderNodeData, BuilderNodeKind } from '../../types';
import { nodeRegistry } from '../nodeRegistry';

// ==================== Helper to create mock nodes ====================

function mockNode(
  type: string,
  data: Partial<BuilderNodeData> & Record<string, any> = {},
  id = 'test-node-1',
): Node<BuilderNodeData> {
  return {
    id,
    type,
    position: { x: 0, y: 0 },
    data: {
      id: data.id ?? id,
      label: data.label ?? 'Test',
      kind: data.kind ?? 'action',
      ...data,
    } as BuilderNodeData,
  };
}

// ==================== Tests ====================

describe('nodeRegistry', () => {
  // ==================== getDefinition ====================
  describe('getDefinition', () => {
    it('returns definition for known node types', () => {
      const def = nodeRegistry.getDefinition('decisionNode');
      expect(def).toBeDefined();
      expect(def!.type).toBe('decisionNode');
      expect(def!.prefix).toBe('core');
      expect(def!.kind).toBe('decision');
    });

    it('returns undefined for unknown node types', () => {
      expect(nodeRegistry.getDefinition('unknownNode')).toBeUndefined();
      expect(nodeRegistry.getDefinition('')).toBeUndefined();
    });

    it('returns definition for all registered node types', () => {
      const knownTypes = [
        'triggerNode', 'decisionNode', 'switchNode', 'optionNode', 'userApprovalNode',
        'whileGroupNode', 'splitNode', 'forkNode', 'mergeNode',
        'flowNode', 'agentNode', 'guardrailNode', 'classifyNode',
        'crudNode', 'interfaceNode', 'noteNode', 'exitNode',
        'responseNode', 'aggregateNode', 'workflowNode',
      ];
      for (const t of knownTypes) {
        expect(nodeRegistry.getDefinition(t)).toBeDefined();
      }
    });
  });

  // ==================== getAllDefinitions ====================
  describe('getAllDefinitions', () => {
    it('returns an object with all definitions', () => {
      const all = nodeRegistry.getAllDefinitions();
      expect(Object.keys(all).length).toBeGreaterThan(10);
      // Top-level keys are spread-copied
      expect(all).toHaveProperty('triggerNode');
      expect(all).toHaveProperty('decisionNode');
      expect(all).toHaveProperty('flowNode');
    });

    it('top-level spread does not share identity with internal map', () => {
      const all = nodeRegistry.getAllDefinitions();
      // Deleting a key from the copy should not affect the original
      delete all['triggerNode'];
      expect(nodeRegistry.getDefinition('triggerNode')).toBeDefined();
    });
  });

  // ==================== getPrefix ====================
  describe('getPrefix', () => {
    it('returns correct prefix for each node category', () => {
      expect(nodeRegistry.getPrefix('triggerNode')).toBe('trigger');
      expect(nodeRegistry.getPrefix('decisionNode')).toBe('core');
      expect(nodeRegistry.getPrefix('userApprovalNode')).toBe('core');
      expect(nodeRegistry.getPrefix('flowNode')).toBe('mcp');
      expect(nodeRegistry.getPrefix('agentNode')).toBe('agent');
      expect(nodeRegistry.getPrefix('crudNode')).toBe('table');
      expect(nodeRegistry.getPrefix('interfaceNode')).toBe('interface');
      expect(nodeRegistry.getPrefix('noteNode')).toBe('note');
    });

    it('returns null for unknown node types', () => {
      expect(nodeRegistry.getPrefix('unknownNode')).toBeNull();
      expect(nodeRegistry.getPrefix('')).toBeNull();
    });
  });

  // ==================== getPrefixesForNode ====================
  describe('getPrefixesForNode', () => {
    it('returns ["core"] for flowNode with transform kind', () => {
      const node = mockNode('flowNode', { kind: 'transform' });
      expect(nodeRegistry.getPrefixesForNode(node)).toEqual(['core']);
    });

    it('returns ["core"] for flowNode with download_file kind', () => {
      const node = mockNode('flowNode', { kind: 'download_file' });
      expect(nodeRegistry.getPrefixesForNode(node)).toEqual(['core']);
    });

    it('returns ["core"] for flowNode with public_link kind', () => {
      const node = mockNode('flowNode', { kind: 'public_link' });
      expect(nodeRegistry.getPrefixesForNode(node)).toEqual(['core']);
    });

    it('returns ["core"] for flowNode with media kind', () => {
      const node = mockNode('flowNode', { kind: 'media' });
      expect(nodeRegistry.getPrefixesForNode(node)).toEqual(['core']);
    });

    it('returns ["core"] for flowNode with wait kind', () => {
      const node = mockNode('flowNode', { kind: 'wait' });
      expect(nodeRegistry.getPrefixesForNode(node)).toEqual(['core']);
    });

    it('returns ["core"] for flowNode with merge kind', () => {
      const node = mockNode('flowNode', { kind: 'merge' });
      expect(nodeRegistry.getPrefixesForNode(node)).toEqual(['core']);
    });

    it('returns ["core"] for flowNode with http_request kind', () => {
      const node = mockNode('flowNode', { kind: 'http_request' });
      expect(nodeRegistry.getPrefixesForNode(node)).toEqual(['core']);
    });

    it('returns ["mcp"] for flowNode with action kind', () => {
      const node = mockNode('flowNode', { kind: 'action' });
      expect(nodeRegistry.getPrefixesForNode(node)).toEqual(['mcp']);
    });

    it('returns ["agent", "mcp"] for agent node types', () => {
      const node = mockNode('agentNode');
      expect(nodeRegistry.getPrefixesForNode(node)).toEqual(['agent', 'mcp']);
    });

    it('returns ["trigger"] for triggerNode', () => {
      const node = mockNode('triggerNode', { kind: 'entry' });
      expect(nodeRegistry.getPrefixesForNode(node)).toEqual(['trigger']);
    });

    it('returns ["core"] for decisionNode', () => {
      const node = mockNode('decisionNode', { kind: 'decision' });
      expect(nodeRegistry.getPrefixesForNode(node)).toEqual(['core']);
    });

    it('returns default fallback for unknown node types', () => {
      const node = mockNode('unknownType', {});
      expect(nodeRegistry.getPrefixesForNode(node)).toEqual(['mcp', 'agent']);
    });
  });

  // ==================== isTrigger ====================
  describe('isTrigger', () => {
    it('returns true for triggerNode type', () => {
      expect(nodeRegistry.isTrigger(mockNode('triggerNode'))).toBe(true);
    });

    it('returns true for node with kind=entry', () => {
      expect(nodeRegistry.isTrigger(mockNode('flowNode', { kind: 'entry' }))).toBe(true);
    });

    it('returns false for non-trigger nodes', () => {
      expect(nodeRegistry.isTrigger(mockNode('flowNode', { kind: 'action' }))).toBe(false);
      expect(nodeRegistry.isTrigger(mockNode('decisionNode'))).toBe(false);
    });
  });

  // ==================== isControlNode ====================
  describe('isControlNode', () => {
    it('returns true for all control flow types', () => {
      const controlTypes = [
        'decisionNode', 'switchNode', 'optionNode', 'userApprovalNode',
        'whileGroupNode', 'splitNode', 'forkNode', 'mergeNode',
        'exitNode', 'responseNode', 'aggregateNode',
      ];
      for (const t of controlTypes) {
        expect(nodeRegistry.isControlNode(mockNode(t))).toBe(true);
      }
    });

    it('returns true for flowNode-based control nodes (by kind)', () => {
      const controlKinds = ['transform', 'wait', 'download_file', 'public_link', 'media', 'http_request', 'data_input'];
      for (const k of controlKinds) {
        expect(nodeRegistry.isControlNode(mockNode('flowNode', { kind: k as BuilderNodeKind }))).toBe(true);
      }
    });

    it('returns false for regular flowNode', () => {
      expect(nodeRegistry.isControlNode(mockNode('flowNode', { kind: 'action' }))).toBe(false);
    });

    it('returns false for agent nodes', () => {
      expect(nodeRegistry.isControlNode(mockNode('agentNode'))).toBe(false);
    });

    it('returns false for trigger nodes', () => {
      expect(nodeRegistry.isControlNode(mockNode('triggerNode'))).toBe(false);
    });
  });

  // ==================== isBranchingNode ====================
  describe('isBranchingNode', () => {
    it('returns true for nodes with ports', () => {
      const branchingTypes = ['decisionNode', 'switchNode', 'optionNode', 'userApprovalNode', 'forkNode', 'guardrailNode', 'classifyNode'];
      for (const t of branchingTypes) {
        expect(nodeRegistry.isBranchingNode(mockNode(t))).toBe(true);
      }
    });

    it('returns false for nodes without ports', () => {
      const nonBranchingTypes = ['flowNode', 'triggerNode', 'mergeNode', 'splitNode', 'noteNode', 'agentNode'];
      for (const t of nonBranchingTypes) {
        expect(nodeRegistry.isBranchingNode(mockNode(t))).toBe(false);
      }
    });

    it('returns false for unknown node type', () => {
      expect(nodeRegistry.isBranchingNode(mockNode('unknownType'))).toBe(false);
    });
  });

  // ==================== isDecisionLikeNode ====================
  describe('isDecisionLikeNode', () => {
    it('returns true for decision node', () => {
      expect(nodeRegistry.isDecisionLikeNode(mockNode('decisionNode'))).toBe(true);
    });

    it('returns true for switch node', () => {
      expect(nodeRegistry.isDecisionLikeNode(mockNode('switchNode'))).toBe(true);
    });

    it('returns true for option node', () => {
      expect(nodeRegistry.isDecisionLikeNode(mockNode('optionNode'))).toBe(true);
    });

    it('returns true for user approval node', () => {
      expect(nodeRegistry.isDecisionLikeNode(mockNode('userApprovalNode', { kind: 'approval' }))).toBe(true);
    });

    it('returns true for node with kind=condition (backward compat)', () => {
      expect(nodeRegistry.isDecisionLikeNode(mockNode('flowNode', { kind: 'condition' }))).toBe(true);
    });

    it('returns true for node with kind=decision', () => {
      expect(nodeRegistry.isDecisionLikeNode(mockNode('flowNode', { kind: 'decision' }))).toBe(true);
    });

    it('returns false for whileGroupNode', () => {
      expect(nodeRegistry.isDecisionLikeNode(mockNode('whileGroupNode'))).toBe(false);
    });

    it('returns false for fork node', () => {
      expect(nodeRegistry.isDecisionLikeNode(mockNode('forkNode'))).toBe(false);
    });
  });

  // ==================== isDecisionNode ====================
  describe('isDecisionNode', () => {
    it('returns true for decisionNode type', () => {
      expect(nodeRegistry.isDecisionNode(mockNode('decisionNode'))).toBe(true);
    });

    it('returns false for switchNode', () => {
      expect(nodeRegistry.isDecisionNode(mockNode('switchNode'))).toBe(false);
    });
  });

  // ==================== isSwitchNode ====================
  describe('isSwitchNode', () => {
    it('returns true for switchNode type', () => {
      expect(nodeRegistry.isSwitchNode(mockNode('switchNode'))).toBe(true);
    });

    it('returns true for node with data.id="switch"', () => {
      expect(nodeRegistry.isSwitchNode(mockNode('flowNode', { id: 'switch' }))).toBe(true);
    });

    it('returns true for node with data.id starting with "switch-"', () => {
      expect(nodeRegistry.isSwitchNode(mockNode('flowNode', { id: 'switch-123' }))).toBe(true);
    });

    it('returns false for decisionNode', () => {
      expect(nodeRegistry.isSwitchNode(mockNode('decisionNode'))).toBe(false);
    });
  });

  // ==================== isOptionNode ====================
  describe('isOptionNode', () => {
    it('returns true for optionNode type', () => {
      expect(nodeRegistry.isOptionNode(mockNode('optionNode'))).toBe(true);
    });

    it('returns true for node with data.id="option"', () => {
      expect(nodeRegistry.isOptionNode(mockNode('flowNode', { id: 'option' }))).toBe(true);
    });

    it('returns true for node with data.id starting with "option-"', () => {
      expect(nodeRegistry.isOptionNode(mockNode('flowNode', { id: 'option-abc' }))).toBe(true);
    });

    it('returns false for unrelated node', () => {
      expect(nodeRegistry.isOptionNode(mockNode('flowNode'))).toBe(false);
    });
  });

  // ==================== isUserApprovalNode ====================
  describe('isUserApprovalNode', () => {
    it('returns true for userApprovalNode type', () => {
      expect(nodeRegistry.isUserApprovalNode(mockNode('userApprovalNode'))).toBe(true);
    });

    it('returns true for data.id="user-approval"', () => {
      expect(nodeRegistry.isUserApprovalNode(mockNode('flowNode', { id: 'user-approval' }))).toBe(true);
    });

    it('returns true for data.id starting with "user-approval-"', () => {
      expect(nodeRegistry.isUserApprovalNode(mockNode('flowNode', { id: 'user-approval-123' }))).toBe(true);
    });

    it('returns false for optionNode', () => {
      expect(nodeRegistry.isUserApprovalNode(mockNode('optionNode'))).toBe(false);
    });

    it('returns false for unrelated node', () => {
      expect(nodeRegistry.isUserApprovalNode(mockNode('flowNode'))).toBe(false);
    });
  });

  // ==================== isLoopNode ====================
  describe('isLoopNode', () => {
    it('returns true for whileGroupNode type', () => {
      expect(nodeRegistry.isLoopNode(mockNode('whileGroupNode'))).toBe(true);
    });

    it('returns false for splitNode', () => {
      expect(nodeRegistry.isLoopNode(mockNode('splitNode'))).toBe(false);
    });
  });

  // ==================== isSplitNode ====================
  describe('isSplitNode', () => {
    it('returns true for splitNode', () => {
      expect(nodeRegistry.isSplitNode(mockNode('splitNode'))).toBe(true);
    });

    it('returns false for whileGroupNode', () => {
      expect(nodeRegistry.isSplitNode(mockNode('whileGroupNode'))).toBe(false);
    });
  });

  // ==================== isForkNode ====================
  describe('isForkNode', () => {
    it('returns true for forkNode type', () => {
      expect(nodeRegistry.isForkNode(mockNode('forkNode'))).toBe(true);
    });

    it('returns true for node with data.id="fork"', () => {
      expect(nodeRegistry.isForkNode(mockNode('flowNode', { id: 'fork' }))).toBe(true);
    });

    it('returns true for node with data.id starting with "fork-"', () => {
      expect(nodeRegistry.isForkNode(mockNode('flowNode', { id: 'fork-1' }))).toBe(true);
    });

    it('returns false for mergeNode', () => {
      expect(nodeRegistry.isForkNode(mockNode('mergeNode'))).toBe(false);
    });
  });

  // ==================== isMergeNode ====================
  describe('isMergeNode', () => {
    it('returns true for mergeNode type', () => {
      expect(nodeRegistry.isMergeNode(mockNode('mergeNode'))).toBe(true);
    });

    it('returns true for node with kind=merge', () => {
      expect(nodeRegistry.isMergeNode(mockNode('flowNode', { kind: 'merge' }))).toBe(true);
    });

    it('returns true for node with data.id="merge"', () => {
      expect(nodeRegistry.isMergeNode(mockNode('flowNode', { id: 'merge' }))).toBe(true);
    });

    it('returns true for node with data.id starting with "merge-"', () => {
      expect(nodeRegistry.isMergeNode(mockNode('flowNode', { id: 'merge-abc' }))).toBe(true);
    });

    it('returns false for forkNode', () => {
      expect(nodeRegistry.isMergeNode(mockNode('forkNode'))).toBe(false);
    });
  });

  // ==================== isTransformNode ====================
  describe('isTransformNode', () => {
    it('returns true for kind=transform', () => {
      expect(nodeRegistry.isTransformNode(mockNode('flowNode', { kind: 'transform' }))).toBe(true);
    });

    it('returns true for data.id="transform"', () => {
      expect(nodeRegistry.isTransformNode(mockNode('flowNode', { id: 'transform' }))).toBe(true);
    });

    it('returns true for data.id starting with "transform-"', () => {
      expect(nodeRegistry.isTransformNode(mockNode('flowNode', { id: 'transform-1' }))).toBe(true);
    });

    it('returns false for unrelated node', () => {
      expect(nodeRegistry.isTransformNode(mockNode('flowNode', { kind: 'action' }))).toBe(false);
    });
  });

  // ==================== isWaitNode ====================
  describe('isWaitNode', () => {
    it('returns true for kind=wait', () => {
      expect(nodeRegistry.isWaitNode(mockNode('flowNode', { kind: 'wait' }))).toBe(true);
    });

    it('returns true for data.id="wait"', () => {
      expect(nodeRegistry.isWaitNode(mockNode('flowNode', { id: 'wait' }))).toBe(true);
    });

    it('returns true for data.id starting with "wait-"', () => {
      expect(nodeRegistry.isWaitNode(mockNode('flowNode', { id: 'wait-5s' }))).toBe(true);
    });

    it('returns false for unrelated node', () => {
      expect(nodeRegistry.isWaitNode(mockNode('flowNode', { kind: 'action' }))).toBe(false);
    });
  });

  // ==================== isDownloadFileNode ====================
  describe('isDownloadFileNode', () => {
    it('returns true for kind=download_file', () => {
      expect(nodeRegistry.isDownloadFileNode(mockNode('flowNode', { kind: 'download_file' }))).toBe(true);
    });

    it('returns true for data.id="download_file"', () => {
      expect(nodeRegistry.isDownloadFileNode(mockNode('flowNode', { id: 'download_file' }))).toBe(true);
    });

    it('returns true for data.id starting with "download-file-"', () => {
      expect(nodeRegistry.isDownloadFileNode(mockNode('flowNode', { id: 'download-file-1' }))).toBe(true);
    });

    it('returns true for data.id starting with "download_file-"', () => {
      expect(nodeRegistry.isDownloadFileNode(mockNode('flowNode', { id: 'download_file-1' }))).toBe(true);
    });

    it('returns false for unrelated node', () => {
      expect(nodeRegistry.isDownloadFileNode(mockNode('flowNode', { kind: 'action' }))).toBe(false);
    });
  });

  // ==================== isPublicLinkNode ====================
  describe('isPublicLinkNode', () => {
    it('returns true for kind=public_link', () => {
      expect(nodeRegistry.isPublicLinkNode(mockNode('flowNode', { kind: 'public_link' }))).toBe(true);
    });

    it('returns true for data.id="public_link"', () => {
      expect(nodeRegistry.isPublicLinkNode(mockNode('flowNode', { id: 'public_link' }))).toBe(true);
    });

    it('returns true for data.id starting with "public-link-"', () => {
      expect(nodeRegistry.isPublicLinkNode(mockNode('flowNode', { id: 'public-link-1' }))).toBe(true);
    });

    it('returns true for data.id starting with "public_link-"', () => {
      expect(nodeRegistry.isPublicLinkNode(mockNode('flowNode', { id: 'public_link-1' }))).toBe(true);
    });

    it('returns false for unrelated node', () => {
      expect(nodeRegistry.isPublicLinkNode(mockNode('flowNode', { kind: 'action' }))).toBe(false);
    });

    it('does NOT match a download_file node (sibling file node)', () => {
      expect(nodeRegistry.isPublicLinkNode(mockNode('flowNode', { kind: 'download_file', id: 'download_file-1' }))).toBe(false);
    });
  });

  // ==================== isMediaNode ====================
  describe('isMediaNode', () => {
    it('returns true for kind=media', () => {
      expect(nodeRegistry.isMediaNode(mockNode('flowNode', { kind: 'media' }))).toBe(true);
    });

    it('returns true for data.id="media"', () => {
      expect(nodeRegistry.isMediaNode(mockNode('flowNode', { id: 'media' }))).toBe(true);
    });

    it('returns true for data.id starting with "media-"', () => {
      expect(nodeRegistry.isMediaNode(mockNode('flowNode', { id: 'media-1' }))).toBe(true);
    });

    it('returns false for unrelated node', () => {
      expect(nodeRegistry.isMediaNode(mockNode('flowNode', { kind: 'action' }))).toBe(false);
    });

    it('does NOT match an mcp tool node whose id merely CONTAINS "media"', () => {
      expect(nodeRegistry.isMediaNode(mockNode('flowNode', { id: 'create_media_container-1' }))).toBe(false);
    });

    it('does NOT match a download_file node (sibling file node)', () => {
      expect(nodeRegistry.isMediaNode(mockNode('flowNode', { kind: 'download_file', id: 'download_file-1' }))).toBe(false);
    });
  });

  // ==================== isHttpRequestNode ====================
  describe('isHttpRequestNode', () => {
    it('returns true for kind=http_request', () => {
      expect(nodeRegistry.isHttpRequestNode(mockNode('flowNode', { kind: 'http_request' }))).toBe(true);
    });

    it('returns true for data.id="http-request"', () => {
      expect(nodeRegistry.isHttpRequestNode(mockNode('flowNode', { id: 'http-request' }))).toBe(true);
    });

    it('returns true for data.id starting with "http-request-"', () => {
      expect(nodeRegistry.isHttpRequestNode(mockNode('flowNode', { id: 'http-request-1' }))).toBe(true);
    });

    it('returns true for data.id starting with "http_request-"', () => {
      expect(nodeRegistry.isHttpRequestNode(mockNode('flowNode', { id: 'http_request-abc' }))).toBe(true);
    });

    it('returns false for unrelated node', () => {
      expect(nodeRegistry.isHttpRequestNode(mockNode('flowNode', { kind: 'action' }))).toBe(false);
    });
  });

  // ==================== isExitNode ====================
  describe('isExitNode', () => {
    it('returns true for exitNode type', () => {
      expect(nodeRegistry.isExitNode(mockNode('exitNode'))).toBe(true);
    });

    it('returns true for data.id="exit"', () => {
      expect(nodeRegistry.isExitNode(mockNode('flowNode', { id: 'exit' }))).toBe(true);
    });

    it('returns true for data.id starting with "exit-"', () => {
      expect(nodeRegistry.isExitNode(mockNode('flowNode', { id: 'exit-1' }))).toBe(true);
    });

    it('returns false for unrelated node', () => {
      expect(nodeRegistry.isExitNode(mockNode('flowNode'))).toBe(false);
    });
  });

  // ==================== isResponseNode ====================
  describe('isResponseNode', () => {
    it('returns true for responseNode type', () => {
      expect(nodeRegistry.isResponseNode(mockNode('responseNode'))).toBe(true);
    });

    it('returns true for data.id="response"', () => {
      expect(nodeRegistry.isResponseNode(mockNode('flowNode', { id: 'response' }))).toBe(true);
    });

    it('returns true for data.id starting with "response-"', () => {
      expect(nodeRegistry.isResponseNode(mockNode('flowNode', { id: 'response-1' }))).toBe(true);
    });

    it('returns false for unrelated node', () => {
      expect(nodeRegistry.isResponseNode(mockNode('flowNode'))).toBe(false);
    });
  });

  // ==================== isAggregateNode ====================
  describe('isAggregateNode', () => {
    it('returns true for aggregateNode type', () => {
      expect(nodeRegistry.isAggregateNode(mockNode('aggregateNode'))).toBe(true);
    });

    it('returns true for data.id="aggregate"', () => {
      expect(nodeRegistry.isAggregateNode(mockNode('flowNode', { id: 'aggregate' }))).toBe(true);
    });

    it('returns true for data.id starting with "aggregate-"', () => {
      expect(nodeRegistry.isAggregateNode(mockNode('flowNode', { id: 'aggregate-2' }))).toBe(true);
    });

    it('returns false for unrelated node', () => {
      expect(nodeRegistry.isAggregateNode(mockNode('flowNode'))).toBe(false);
    });
  });

  // ==================== isAgentNode ====================
  describe('isAgentNode', () => {
    it('returns true for agentNode type', () => {
      expect(nodeRegistry.isAgentNode(mockNode('agentNode'))).toBe(true);
    });

    it('returns true for guardrailNode type', () => {
      expect(nodeRegistry.isAgentNode(mockNode('guardrailNode'))).toBe(true);
    });

    it('returns true for classifyNode type', () => {
      expect(nodeRegistry.isAgentNode(mockNode('classifyNode'))).toBe(true);
    });

    it('returns true for node with agentData', () => {
      const node = mockNode('flowNode', { agentData: { model: 'gpt-4' } } as any);
      expect(nodeRegistry.isAgentNode(node)).toBe(true);
    });

    it('returns true for flowNode with kind=reasoning (regular AI agent)', () => {
      const node = mockNode('flowNode', { id: 'ai-agent', kind: 'reasoning', label: 'My Agent' });
      expect(nodeRegistry.isAgentNode(node)).toBe(true);
    });

    it('returns false for regular flowNode', () => {
      expect(nodeRegistry.isAgentNode(mockNode('flowNode'))).toBe(false);
    });
  });

  // ==================== isAiAgentNode (strict, exact-name) ====================
  describe('isAiAgentNode', () => {
    it('returns true for canonical AI Agent (data.id="ai-agent")', () => {
      const node = mockNode('flowNode', { id: 'ai-agent', kind: 'reasoning', label: 'My Agent' });
      expect(nodeRegistry.isAiAgentNode(node)).toBe(true);
    });

    it('returns true for data.id="agent" (legacy canonical)', () => {
      expect(nodeRegistry.isAiAgentNode(mockNode('flowNode', { id: 'agent' }))).toBe(true);
    });

    it('returns true for entity-bound id (data.id="agent-<uuid>")', () => {
      expect(nodeRegistry.isAiAgentNode(mockNode('flowNode', { id: 'agent-abc-123' }))).toBe(true);
    });

    it('returns true for type="agentNode"', () => {
      expect(nodeRegistry.isAiAgentNode(mockNode('agentNode'))).toBe(true);
    });

    it('returns true for kind="reasoning"', () => {
      expect(nodeRegistry.isAiAgentNode(mockNode('flowNode', { kind: 'reasoning' } as any))).toBe(true);
    });

    it('returns true for agentType="agent"', () => {
      expect(nodeRegistry.isAiAgentNode(mockNode('flowNode', { agentType: 'agent' } as any))).toBe(true);
    });

    it('returns false for classify node - must not steal classify panel', () => {
      expect(nodeRegistry.isAiAgentNode(mockNode('classifyNode'))).toBe(false);
      expect(nodeRegistry.isAiAgentNode(mockNode('flowNode', { id: 'classify' }))).toBe(false);
    });

    it('returns false for guardrail node - must not steal guardrail panel', () => {
      expect(nodeRegistry.isAiAgentNode(mockNode('guardrailNode'))).toBe(false);
      expect(nodeRegistry.isAiAgentNode(mockNode('flowNode', { id: 'guardrail' }))).toBe(false);
    });

    it('returns false for browser_agent node - must not steal browser-agent panel', () => {
      expect(nodeRegistry.isAiAgentNode(mockNode('browserAgentNode'))).toBe(false);
      expect(nodeRegistry.isAiAgentNode(mockNode('flowNode', { id: 'browser_agent' } as any))).toBe(false);
      expect(nodeRegistry.isAiAgentNode(mockNode('flowNode', { kind: 'browser_agent' } as any))).toBe(false);
    });

    it('returns false for arbitrary node whose label contains "agent" - exact-name only', () => {
      // Regression: previously data.label.toLowerCase().includes("agent") matched
      // any node labeled "Agent something" and incorrectly routed it to the agent panel.
      const node = mockNode('flowNode', { id: 'mcp:gmail', label: 'Agent Webhook' } as any);
      expect(nodeRegistry.isAiAgentNode(node)).toBe(false);
    });

    it('returns false for ids starting with "ai-agent-" - only exact "ai-agent" qualifies', () => {
      // Regression: previously data.id.startsWith("ai-agent") matched ai-agent-summarize, etc.
      const node = mockNode('flowNode', { id: 'ai-agent-summarize' });
      expect(nodeRegistry.isAiAgentNode(node)).toBe(false);
    });

    it('returns false for plain flowNode with no agent markers', () => {
      expect(nodeRegistry.isAiAgentNode(mockNode('flowNode'))).toBe(false);
    });
  });

  // ==================== isClassifyNode ====================
  describe('isClassifyNode', () => {
    it('returns true for classifyNode type', () => {
      expect(nodeRegistry.isClassifyNode(mockNode('classifyNode'))).toBe(true);
    });

    it('returns true for data.id="classify"', () => {
      expect(nodeRegistry.isClassifyNode(mockNode('flowNode', { id: 'classify' }))).toBe(true);
    });

    it('returns true for data.id starting with "classify-"', () => {
      expect(nodeRegistry.isClassifyNode(mockNode('flowNode', { id: 'classify-1' }))).toBe(true);
    });

    it('returns false for agentNode', () => {
      expect(nodeRegistry.isClassifyNode(mockNode('agentNode'))).toBe(false);
    });
  });

  // ==================== isGuardrailNode ====================
  describe('isGuardrailNode', () => {
    it('returns true for guardrailNode type', () => {
      expect(nodeRegistry.isGuardrailNode(mockNode('guardrailNode'))).toBe(true);
    });

    it('returns true for data.id="guardrail"', () => {
      expect(nodeRegistry.isGuardrailNode(mockNode('flowNode', { id: 'guardrail' }))).toBe(true);
    });

    it('returns true for data.id starting with "guardrail-"', () => {
      expect(nodeRegistry.isGuardrailNode(mockNode('flowNode', { id: 'guardrail-abc' }))).toBe(true);
    });

    it('returns false for agentNode', () => {
      expect(nodeRegistry.isGuardrailNode(mockNode('agentNode'))).toBe(false);
    });
  });

  // ==================== isCrudNode ====================
  describe('isCrudNode', () => {
    it('returns true for crudNode type', () => {
      expect(nodeRegistry.isCrudNode(mockNode('crudNode'))).toBe(true);
    });

    it('returns true for valid crud operations', () => {
      const ops = ['create-row', 'read-row', 'update-row', 'delete-row', 'create-column', 'find-row'];
      for (const op of ops) {
        const node = mockNode('flowNode', { dataSourceData: { crudOperation: op } } as any);
        expect(nodeRegistry.isCrudNode(node)).toBe(true);
      }
    });

    it('returns false for invalid crud operation', () => {
      const node = mockNode('flowNode', { dataSourceData: { crudOperation: 'triggers' } } as any);
      expect(nodeRegistry.isCrudNode(node)).toBe(false);
    });

    it('returns false for node without dataSourceData', () => {
      expect(nodeRegistry.isCrudNode(mockNode('flowNode'))).toBe(false);
    });

    it('returns false for node with empty crudOperation', () => {
      const node = mockNode('flowNode', { dataSourceData: {} } as any);
      expect(nodeRegistry.isCrudNode(node)).toBe(false);
    });
  });

  // ==================== isFindNode ====================
  describe('isFindNode', () => {
    it('returns true for find-row crudOperation', () => {
      const node = mockNode('flowNode', { dataSourceData: { crudOperation: 'find-row' } } as any);
      expect(nodeRegistry.isFindNode(node)).toBe(true);
    });

    it('returns true for data.id find-row', () => {
      const node = mockNode('flowNode', { id: 'find-row' });
      expect(nodeRegistry.isFindNode(node)).toBe(true);
    });

    it('returns true for data.id starting with find-row-', () => {
      const node = mockNode('flowNode', { id: 'find-row-ds123' });
      expect(nodeRegistry.isFindNode(node)).toBe(true);
    });

    it('returns false for read-row crudOperation (read-row is Get Rows, not Find)', () => {
      const node = mockNode('flowNode', { dataSourceData: { crudOperation: 'read-row' } } as any);
      expect(nodeRegistry.isFindNode(node)).toBe(false);
    });

    it('returns false for plain flowNode', () => {
      expect(nodeRegistry.isFindNode(mockNode('flowNode'))).toBe(false);
    });
  });

  // ==================== isSplitLikeNode ====================
  describe('isSplitLikeNode', () => {
    it('returns true for splitNode', () => {
      expect(nodeRegistry.isSplitLikeNode(mockNode('splitNode'))).toBe(true);
    });

    it('returns true for find-row node', () => {
      const node = mockNode('flowNode', { dataSourceData: { crudOperation: 'find-row' } } as any);
      expect(nodeRegistry.isSplitLikeNode(node)).toBe(true);
    });

    it('returns true for read-row node', () => {
      const node = mockNode('flowNode', { dataSourceData: { crudOperation: 'read-row' } } as any);
      expect(nodeRegistry.isSplitLikeNode(node)).toBe(true);
    });

    it('returns false for regular flowNode', () => {
      expect(nodeRegistry.isSplitLikeNode(mockNode('flowNode'))).toBe(false);
    });
  });

  // ==================== isNoteNode ====================
  describe('isNoteNode', () => {
    it('returns true for noteNode type', () => {
      expect(nodeRegistry.isNoteNode(mockNode('noteNode'))).toBe(true);
    });

    it('returns false for other types', () => {
      expect(nodeRegistry.isNoteNode(mockNode('flowNode'))).toBe(false);
    });
  });

  // ==================== isInterfaceNode ====================
  describe('isInterfaceNode', () => {
    it('returns true for interfaceNode type', () => {
      expect(nodeRegistry.isInterfaceNode(mockNode('interfaceNode'))).toBe(true);
    });

    it('returns true for node with interfaceData.interfaceId', () => {
      const node = mockNode('flowNode', { interfaceData: { interfaceId: '123' } } as any);
      expect(nodeRegistry.isInterfaceNode(node)).toBe(true);
    });

    it('returns true for node whose id starts with "interface-"', () => {
      const node = mockNode('flowNode', {}, 'interface-abc');
      expect(nodeRegistry.isInterfaceNode(node)).toBe(true);
    });

    it('returns true for node with data.id starting with "interface-"', () => {
      const node = mockNode('flowNode', { id: 'interface-xyz' });
      expect(nodeRegistry.isInterfaceNode(node)).toBe(true);
    });

    it('returns false for unrelated node', () => {
      expect(nodeRegistry.isInterfaceNode(mockNode('flowNode'))).toBe(false);
    });
  });

  // ==================== isWorkflowNode ====================
  describe('isWorkflowNode', () => {
    it('returns true for workflowNode', () => {
      expect(nodeRegistry.isWorkflowNode(mockNode('workflowNode'))).toBe(true);
    });

    it('returns false for flowNode', () => {
      expect(nodeRegistry.isWorkflowNode(mockNode('flowNode'))).toBe(false);
    });
  });

  // ==================== isFlowNode ====================
  describe('isFlowNode', () => {
    it('returns true for flowNode', () => {
      expect(nodeRegistry.isFlowNode(mockNode('flowNode'))).toBe(true);
    });

    it('returns false for other types', () => {
      expect(nodeRegistry.isFlowNode(mockNode('agentNode'))).toBe(false);
    });
  });

  // ==================== isCoreNode ====================
  describe('isCoreNode', () => {
    it('returns true for all core node types', () => {
      const coreTypes = [
        'decisionNode', 'switchNode', 'optionNode', 'userApprovalNode',
        'whileGroupNode', 'splitNode', 'forkNode', 'mergeNode',
        'exitNode', 'responseNode', 'aggregateNode',
      ];
      for (const t of coreTypes) {
        expect(nodeRegistry.isCoreNode(mockNode(t))).toBe(true);
      }
    });

    it('returns true for flowNode-based core nodes by kind', () => {
      const coreKinds = ['transform', 'wait', 'download_file', 'public_link', 'media', 'merge', 'http_request', 'data_input'];
      for (const k of coreKinds) {
        expect(nodeRegistry.isCoreNode(mockNode('flowNode', { kind: k as BuilderNodeKind }))).toBe(true);
      }
    });

    it('returns false for regular flowNode', () => {
      expect(nodeRegistry.isCoreNode(mockNode('flowNode', { kind: 'action' }))).toBe(false);
    });

    it('returns false for trigger node', () => {
      expect(nodeRegistry.isCoreNode(mockNode('triggerNode'))).toBe(false);
    });

    it('returns false for agent node', () => {
      expect(nodeRegistry.isCoreNode(mockNode('agentNode'))).toBe(false);
    });
  });

  // ==================== hasPorts ====================
  describe('hasPorts', () => {
    it('returns true for branching node types', () => {
      expect(nodeRegistry.hasPorts('decisionNode')).toBe(true);
      expect(nodeRegistry.hasPorts('switchNode')).toBe(true);
      expect(nodeRegistry.hasPorts('optionNode')).toBe(true);
      expect(nodeRegistry.hasPorts('userApprovalNode')).toBe(true);
      expect(nodeRegistry.hasPorts('forkNode')).toBe(true);
      expect(nodeRegistry.hasPorts('guardrailNode')).toBe(true);
      expect(nodeRegistry.hasPorts('classifyNode')).toBe(true);
    });

    it('returns false for non-branching node types', () => {
      expect(nodeRegistry.hasPorts('flowNode')).toBe(false);
      expect(nodeRegistry.hasPorts('triggerNode')).toBe(false);
      expect(nodeRegistry.hasPorts('mergeNode')).toBe(false);
      expect(nodeRegistry.hasPorts('splitNode')).toBe(false);
      expect(nodeRegistry.hasPorts('noteNode')).toBe(false);
    });

    it('returns false for unknown node types', () => {
      expect(nodeRegistry.hasPorts('unknownType')).toBe(false);
    });
  });

  // ==================== requiresSingleEntry ====================
  describe('requiresSingleEntry', () => {
    it('returns true for nodes requiring single input', () => {
      const singleEntryTypes = [
        'decisionNode', 'switchNode', 'optionNode', 'userApprovalNode',
        'splitNode', 'forkNode', 'guardrailNode',
      ];
      for (const t of singleEntryTypes) {
        expect(nodeRegistry.requiresSingleEntry(mockNode(t))).toBe(true);
      }
    });

    it('returns false for nodes accepting multiple inputs', () => {
      expect(nodeRegistry.requiresSingleEntry(mockNode('mergeNode'))).toBe(false);
      expect(nodeRegistry.requiresSingleEntry(mockNode('flowNode'))).toBe(false);
      expect(nodeRegistry.requiresSingleEntry(mockNode('triggerNode'))).toBe(false);
    });
  });

  // ==================== isTerminal ====================
  describe('isTerminal', () => {
    it('returns true for exitNode', () => {
      expect(nodeRegistry.isTerminal(mockNode('exitNode'))).toBe(true);
    });

    it('returns false for non-terminal nodes', () => {
      expect(nodeRegistry.isTerminal(mockNode('flowNode'))).toBe(false);
      expect(nodeRegistry.isTerminal(mockNode('decisionNode'))).toBe(false);
      expect(nodeRegistry.isTerminal(mockNode('responseNode'))).toBe(false);
    });

    it('returns false for unknown types', () => {
      expect(nodeRegistry.isTerminal(mockNode('unknownType'))).toBe(false);
    });
  });

  // ==================== getPortPattern ====================
  describe('getPortPattern', () => {
    it('returns correct patterns for branching nodes', () => {
      expect(nodeRegistry.getPortPattern('decisionNode')).toBe('if|else|elseif_');
      expect(nodeRegistry.getPortPattern('switchNode')).toBe('case_|default');
      expect(nodeRegistry.getPortPattern('optionNode')).toBe('choice_');
      expect(nodeRegistry.getPortPattern('userApprovalNode')).toBe('approved|rejected|timeout');
      expect(nodeRegistry.getPortPattern('forkNode')).toBe('branch_');
      expect(nodeRegistry.getPortPattern('guardrailNode')).toBe('pass|fail');
      expect(nodeRegistry.getPortPattern('classifyNode')).toBe('category_');
    });

    it('returns undefined for non-branching nodes', () => {
      expect(nodeRegistry.getPortPattern('flowNode')).toBeUndefined();
      expect(nodeRegistry.getPortPattern('triggerNode')).toBeUndefined();
      expect(nodeRegistry.getPortPattern('unknownType')).toBeUndefined();
    });
  });

  // ==================== isBranchHandle ====================
  describe('isBranchHandle', () => {
    it('matches decision node handle patterns', () => {
      expect(nodeRegistry.isBranchHandle('source-if', 'decisionNode')).toBe(true);
      expect(nodeRegistry.isBranchHandle('source-else', 'decisionNode')).toBe(true);
      expect(nodeRegistry.isBranchHandle('source-elseif_0', 'decisionNode')).toBe(true);
      expect(nodeRegistry.isBranchHandle('if', 'decisionNode')).toBe(true);
      expect(nodeRegistry.isBranchHandle('else', 'decisionNode')).toBe(true);
    });

    it('matches switch node handle patterns', () => {
      expect(nodeRegistry.isBranchHandle('switch-case_0', 'switchNode')).toBe(true);
      expect(nodeRegistry.isBranchHandle('switch-case-0', 'switchNode')).toBe(true);
      expect(nodeRegistry.isBranchHandle('default', 'switchNode')).toBe(true);
      expect(nodeRegistry.isBranchHandle('source-default', 'switchNode')).toBe(true);
    });

    it('matches option node handle patterns', () => {
      expect(nodeRegistry.isBranchHandle('choice_0', 'optionNode')).toBe(true);
      expect(nodeRegistry.isBranchHandle('choice-0', 'optionNode')).toBe(true);
    });

    it('matches fork node handle patterns', () => {
      expect(nodeRegistry.isBranchHandle('branch_0', 'forkNode')).toBe(true);
      expect(nodeRegistry.isBranchHandle('branch-0', 'forkNode')).toBe(true);
    });

    it('matches guardrail node handle patterns', () => {
      expect(nodeRegistry.isBranchHandle('source-pass', 'guardrailNode')).toBe(true);
      expect(nodeRegistry.isBranchHandle('source-fail', 'guardrailNode')).toBe(true);
      expect(nodeRegistry.isBranchHandle('pass', 'guardrailNode')).toBe(true);
      expect(nodeRegistry.isBranchHandle('fail', 'guardrailNode')).toBe(true);
    });

    it('matches classify node handle patterns', () => {
      expect(nodeRegistry.isBranchHandle('category_0', 'classifyNode')).toBe(true);
      expect(nodeRegistry.isBranchHandle('category-0', 'classifyNode')).toBe(true);
    });

    it('returns false for non-branching node types', () => {
      expect(nodeRegistry.isBranchHandle('any-handle', 'flowNode')).toBe(false);
      expect(nodeRegistry.isBranchHandle('any-handle', 'triggerNode')).toBe(false);
    });

    it('returns false for unknown node type', () => {
      expect(nodeRegistry.isBranchHandle('if', 'unknownType')).toBe(false);
    });
  });

  // ==================== computeBackendKey ====================
  describe('computeBackendKey', () => {
    it('returns trigger key for trigger nodes', () => {
      const node = mockNode('triggerNode', { kind: 'entry' });
      expect(nodeRegistry.computeBackendKey(node, 'my_webhook')).toBe('trigger:my_webhook');
    });

    it('returns agent key for agent nodes', () => {
      const node = mockNode('agentNode');
      expect(nodeRegistry.computeBackendKey(node, 'my_agent')).toBe('agent:my_agent');
    });

    it('returns core key for core nodes', () => {
      const node = mockNode('decisionNode');
      expect(nodeRegistry.computeBackendKey(node, 'check_condition')).toBe('core:check_condition');
    });

    it('returns core key for flowNode with core kind', () => {
      const node = mockNode('flowNode', { kind: 'transform' });
      expect(nodeRegistry.computeBackendKey(node, 'transform_data')).toBe('core:transform_data');
    });

    it('returns table key for crud nodes', () => {
      const node = mockNode('crudNode');
      expect(nodeRegistry.computeBackendKey(node, 'create_user')).toBe('table:create_user');
    });

    it('returns mcp key for regular flowNode', () => {
      const node = mockNode('flowNode', { kind: 'action' });
      expect(nodeRegistry.computeBackendKey(node, 'api_call')).toBe('mcp:api_call');
    });

    it('returns mcp key for node with empty type', () => {
      const node = mockNode('', { kind: 'action' });
      expect(nodeRegistry.computeBackendKey(node, 'step_1')).toBe('mcp:step_1');
    });

    it('returns note key for noteNode', () => {
      const node = mockNode('noteNode');
      expect(nodeRegistry.computeBackendKey(node, 'my_note')).toBe('note:my_note');
    });

    it('returns agent key for guardrailNode', () => {
      const node = mockNode('guardrailNode');
      expect(nodeRegistry.computeBackendKey(node, 'safety_check')).toBe('agent:safety_check');
    });

    it('returns agent key for classifyNode', () => {
      const node = mockNode('classifyNode');
      expect(nodeRegistry.computeBackendKey(node, 'categorize')).toBe('agent:categorize');
    });

    it('returns agent key for flowNode with kind=reasoning (regular AI agent)', () => {
      const node = mockNode('flowNode', { id: 'ai-agent', kind: 'reasoning', label: 'My Agent' });
      expect(nodeRegistry.computeBackendKey(node, 'my_agent')).toBe('agent:my_agent');
    });

    it('returns core key for exitNode', () => {
      const node = mockNode('exitNode');
      expect(nodeRegistry.computeBackendKey(node, 'stop_workflow')).toBe('core:stop_workflow');
    });

    it('returns core key for userApprovalNode', () => {
      const node = mockNode('userApprovalNode', { kind: 'approval' });
      expect(nodeRegistry.computeBackendKey(node, 'review_step')).toBe('core:review_step');
    });
  });

  // ==================== extractBranchType ====================
  describe('extractBranchType', () => {
    const decisionNode = mockNode('decisionNode', { kind: 'decision' });
    const switchNode = mockNode('switchNode', { kind: 'switch' });
    const optionNode = mockNode('optionNode', { kind: 'option' });
    const flowNode = mockNode('flowNode', { kind: 'action' });

    it('returns null for null/undefined handle', () => {
      expect(nodeRegistry.extractBranchType(null, decisionNode)).toBeNull();
      expect(nodeRegistry.extractBranchType(undefined, decisionNode)).toBeNull();
    });

    it('returns null for non-decision-like nodes', () => {
      expect(nodeRegistry.extractBranchType('then', flowNode)).toBeNull();
      expect(nodeRegistry.extractBranchType('if', mockNode('forkNode'))).toBeNull();
    });

    // Decision node patterns
    it('returns "then" for decision "then" handle', () => {
      expect(nodeRegistry.extractBranchType('then', decisionNode)).toBe('then');
    });

    it('returns "then" for decision "source-if" handle', () => {
      expect(nodeRegistry.extractBranchType('source-if', decisionNode)).toBe('then');
    });

    it('returns "elsif" for decision handle containing "-elseif"', () => {
      expect(nodeRegistry.extractBranchType('source-elseif_1', decisionNode)).toBe('elsif');
      expect(nodeRegistry.extractBranchType('source-elseif_0', decisionNode)).toBe('elsif');
    });

    it('returns null for "elseif_0" without hyphen prefix (no pattern match)', () => {
      // "elseif_0" does not match startsWith("elsif") because "elsif" != "elsei"
      // and does not contain "-elseif"
      expect(nodeRegistry.extractBranchType('elseif_0', decisionNode)).toBeNull();
    });

    it('returns "else" for decision "else" handle', () => {
      expect(nodeRegistry.extractBranchType('else', decisionNode)).toBe('else');
      expect(nodeRegistry.extractBranchType('source-else', decisionNode)).toBe('else');
    });

    // Switch node patterns
    it('returns "then" for switch case-0', () => {
      expect(nodeRegistry.extractBranchType('switch-case-0', switchNode)).toBe('then');
    });

    it('returns "then" for switch case-1 (matches case-1 pattern)', () => {
      expect(nodeRegistry.extractBranchType('case-1', switchNode)).toBe('then');
    });

    it('returns "elsif" for switch case-N (N > 1)', () => {
      expect(nodeRegistry.extractBranchType('switch-case-2', switchNode)).toBe('elsif');
    });

    it('returns "else" for switch default', () => {
      expect(nodeRegistry.extractBranchType('switch-default', switchNode)).toBe('else');
      expect(nodeRegistry.extractBranchType('default', switchNode)).toBe('else');
    });

    // Option node patterns
    it('returns "then" for option choice_0', () => {
      expect(nodeRegistry.extractBranchType('choice_0', optionNode)).toBe('then');
      expect(nodeRegistry.extractBranchType('choice-0', optionNode)).toBe('then');
    });

    it('returns "elsif" for option choice_N (N > 0)', () => {
      expect(nodeRegistry.extractBranchType('choice_1', optionNode)).toBe('elsif');
      expect(nodeRegistry.extractBranchType('choice-2', optionNode)).toBe('elsif');
    });

    it('returns null for unrecognized handle pattern on decision node', () => {
      expect(nodeRegistry.extractBranchType('source-output', decisionNode)).toBeNull();
      expect(nodeRegistry.extractBranchType('random', decisionNode)).toBeNull();
    });
  });

  // ==================== Edge cases ====================
  describe('edge cases', () => {
    it('handles node with undefined type gracefully', () => {
      const node = { id: 'n1', position: { x: 0, y: 0 }, data: { id: 'n1', label: 'Test', kind: 'action' } } as any;
      expect(nodeRegistry.isTrigger(node)).toBe(false);
      expect(nodeRegistry.isControlNode(node)).toBe(false);
      expect(nodeRegistry.isBranchingNode(node)).toBe(false);
      expect(nodeRegistry.isCoreNode(node)).toBe(false);
    });

    it('handles node with empty string type', () => {
      const node = mockNode('');
      expect(nodeRegistry.isBranchingNode(node)).toBe(false);
      expect(nodeRegistry.isTerminal(node)).toBe(false);
      expect(nodeRegistry.requiresSingleEntry(node)).toBe(false);
    });

    it('nodeRegistry singleton methods work with bound calls', () => {
      // Verify that calling methods on the singleton produces expected results
      expect(nodeRegistry.getPrefix('triggerNode')).toBe('trigger');
      expect(nodeRegistry.hasPorts('decisionNode')).toBe(true);
      expect(nodeRegistry.isTrigger(mockNode('triggerNode', { kind: 'entry' }))).toBe(true);
      expect(nodeRegistry.isControlNode(mockNode('decisionNode'))).toBe(true);
    });
  });
});
