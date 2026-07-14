/**
 * Shared test helpers for validation rule tests.
 *
 * Provides factory functions to create mock nodes, edges, and validation contexts
 * so that each test file can build workflows declaratively.
 */

import type { Node, Edge } from 'reactflow';
import type { BuilderNodeData, ConditionRow, OptionChoice, ApprovalOutput } from '../../../types';
import type { ValidationContext, ValidationCache } from '../core/types';
import type { Credential } from '@/lib/api/orchestrator';
import { buildValidationCache } from '../core/ValidationCache';

// =========================================================================
// Node factory helpers
// =========================================================================

let nodeCounter = 0;

/** Reset the counter between test suites if needed. */
export function resetNodeCounter(): void {
  nodeCounter = 0;
}

function nextId(prefix: string): string {
  return `${prefix}-${++nodeCounter}`;
}

/** Creates a minimal React Flow node with the given overrides. */
function makeNode(
  overrides: Partial<Node<BuilderNodeData>> & { data: BuilderNodeData }
): Node<BuilderNodeData> {
  return {
    id: overrides.id || nextId('node'),
    type: overrides.type || 'flowNode',
    position: overrides.position || { x: 0, y: 0 },
    data: overrides.data,
    ...overrides,
  };
}

/** Creates a trigger node. */
export function makeTriggerNode(
  label: string,
  id?: string
): Node<BuilderNodeData> {
  return makeNode({
    id: id || nextId('trigger'),
    type: 'triggerNode',
    data: {
      id: id || `trigger-data`,
      label,
      kind: 'entry',
      badge: 'Trigger',
    },
  });
}

/** Creates an MCP step node (flowNode). */
export function makeStepNode(
  label: string,
  opts?: {
    id?: string;
    toolId?: string;
    toolSlug?: string;
    parameters?: Record<string, unknown>;
  }
): Node<BuilderNodeData> {
  const id = opts?.id || nextId('step');
  return makeNode({
    id,
    type: 'flowNode',
    data: {
      id,
      label,
      kind: 'action',
      badge: 'Step',
      toolData: {
        toolId: opts?.toolId || 'tool-1',
        toolSlug: opts?.toolSlug,
        apiName: 'TestAPI',
        method: 'GET',
        parameters: opts?.parameters
          ? Object.entries(opts.parameters).map(([name, val]) => ({
              name,
              defaultValue: String(val),
            }))
          : undefined,
      },
    },
  });
}

/** Creates an MCP step node with no tool data (incomplete step). */
export function makeIncompleteStepNode(
  label: string,
  id?: string
): Node<BuilderNodeData> {
  const nodeId = id || nextId('step');
  return makeNode({
    id: nodeId,
    type: 'flowNode',
    data: {
      id: nodeId,
      label,
      kind: 'action',
      badge: 'Step',
      toolData: undefined,
    },
  });
}

/** Creates an agent node. */
export function makeAgentNode(
  label: string,
  id?: string
): Node<BuilderNodeData> {
  const nodeId = id || nextId('agent');
  return makeNode({
    id: nodeId,
    type: 'agentNode',
    data: {
      id: nodeId,
      label,
      kind: 'reasoning',
      badge: 'Agent',
      agentType: 'agent',
    },
  });
}

/** Creates a decision node. */
export function makeDecisionNode(
  label: string,
  conditions: ConditionRow[],
  id?: string
): Node<BuilderNodeData> {
  const nodeId = id || nextId('decision');
  return makeNode({
    id: nodeId,
    type: 'decisionNode',
    data: {
      id: nodeId,
      label,
      kind: 'decision',
      badge: 'Decision',
      decisionConditions: conditions,
    },
  });
}

/** Creates a loop node. */
export function makeLoopNode(
  label: string,
  loopCondition: string,
  id?: string
): Node<BuilderNodeData> {
  const nodeId = id || nextId('loop');
  return makeNode({
    id: nodeId,
    type: 'whileGroupNode',
    data: {
      id: nodeId,
      label,
      kind: 'loop',
      badge: 'Loop',
      loopCondition,
    },
  });
}

/** Creates a split node. */
export function makeSplitNode(
  label: string,
  id?: string
): Node<BuilderNodeData> {
  const nodeId = id || nextId('split');
  return makeNode({
    id: nodeId,
    type: 'splitNode',
    data: {
      id: nodeId,
      label,
      kind: 'split',
      badge: 'Split',
    },
  });
}

/** Creates a fork node. */
export function makeForkNode(
  label: string,
  id?: string
): Node<BuilderNodeData> {
  const nodeId = id || nextId('fork');
  return makeNode({
    id: nodeId,
    type: 'forkNode',
    data: {
      id: `fork-${nodeId}`,
      label,
      kind: 'fork',
      badge: 'Fork',
    },
  });
}

/** Creates a merge node. */
export function makeMergeNode(
  label: string,
  id?: string
): Node<BuilderNodeData> {
  const nodeId = id || nextId('merge');
  return makeNode({
    id: nodeId,
    type: 'mergeNode',
    data: {
      id: `merge-${nodeId}`,
      label,
      kind: 'merge',
      badge: 'Merge',
    },
  });
}

/** Creates an option node. */
export function makeOptionNode(
  label: string,
  choices: OptionChoice[],
  id?: string
): Node<BuilderNodeData> {
  const nodeId = id || nextId('option');
  return makeNode({
    id: nodeId,
    type: 'optionNode',
    data: {
      id: `option-${nodeId}`,
      label,
      kind: 'option',
      badge: 'Option',
      optionChoices: choices,
    } as any,
  });
}

/** Creates a response node. */
export function makeResponseNode(
  label: string,
  responseMessage?: string,
  id?: string
): Node<BuilderNodeData> {
  const nodeId = id || nextId('response');
  return makeNode({
    id: nodeId,
    type: 'responseNode',
    data: {
      id: `response-${nodeId}`,
      label,
      kind: 'output',
      badge: 'Response',
      responseMessage,
    } as any,
  });
}

/** Creates a user approval node. */
export function makeApprovalNode(
  label: string,
  outputs: ApprovalOutput[],
  id?: string
): Node<BuilderNodeData> {
  const nodeId = id || nextId('approval');
  return makeNode({
    id: nodeId,
    type: 'userApprovalNode',
    data: {
      id: `user-approval-${nodeId}`,
      label,
      kind: 'approval',
      badge: 'APPROVAL',
      approvalOutputs: outputs,
    } as any,
  });
}

/** Creates a note node (non-executable, should be skipped). */
export function makeNoteNode(
  label: string,
  id?: string
): Node<BuilderNodeData> {
  const nodeId = id || nextId('note');
  return makeNode({
    id: nodeId,
    type: 'noteNode',
    data: {
      id: nodeId,
      label,
      kind: 'output' as any,
      badge: 'Note',
      noteText: 'A note',
    },
  });
}

/** Creates an interface node. */
export function makeInterfaceNode(
  label: string,
  interfaceData?: Record<string, unknown>,
  id?: string
): Node<BuilderNodeData> {
  const nodeId = id || nextId('interface');
  return makeNode({
    id: nodeId,
    type: 'flowNode',
    data: {
      id: nodeId,
      label,
      kind: 'interface',
      badge: 'Interface',
      interfaceData: interfaceData || {
        interfaceId: 'iface-1',
        interfaceName: 'Test Interface',
      },
    } as any,
  });
}

/** Creates a CRUD/table node. */
export function makeCrudNode(
  label: string,
  opts?: {
    id?: string;
    dataSourceId?: string;
    crudOperation?: string;
    columnExpressions?: Record<string, string>;
    columnLabels?: Record<string, string>;
  }
): Node<BuilderNodeData> {
  const nodeId = opts?.id || nextId('crud');
  return makeNode({
    id: nodeId,
    type: 'crudNode',
    data: {
      id: nodeId,
      label,
      kind: 'action',
      badge: 'CRUD',
      dataSourceData: {
        dataSourceId: opts?.dataSourceId,
        dataSourceName: 'TestDS',
        crudOperation: opts?.crudOperation || 'read-row',
        ...(opts?.columnExpressions ? { columnExpressions: opts.columnExpressions } : {}),
        ...(opts?.columnLabels ? { columnLabels: opts.columnLabels } : {}),
      },
    } as any,
  });
}

/** Creates a step node with credential requirements. */
export function makeStepWithCredentials(
  label: string,
  opts?: {
    id?: string;
    toolId?: string;
    selectedCredentialId?: number | null;
    credentials?: Array<{ name: string; isRequired?: boolean }>;
    integration?: string;
    credentialSource?: 'user' | 'platform';
    platformCredentialId?: number | null;
  }
): Node<BuilderNodeData> {
  const nodeId = opts?.id || nextId('step');
  return makeNode({
    id: nodeId,
    type: 'flowNode',
    data: {
      id: nodeId,
      label,
      kind: 'action',
      badge: 'Step',
      toolData: {
        toolId: opts?.toolId || 'tool-1',
        apiName: 'TestAPI',
        method: 'GET',
        integration: opts?.integration || 'test-service',
        selectedCredentialId: opts?.selectedCredentialId ?? null,
        credentials: opts?.credentials || [{ name: 'api_key', isRequired: true }],
        ...(opts?.credentialSource ? { credentialSource: opts.credentialSource } : {}),
        ...(opts?.platformCredentialId !== undefined
          ? { platformCredentialId: opts.platformCredentialId }
          : {}),
      },
    } as any,
  });
}

/** Creates a send-email node with optional SMTP credential id. */
export function makeSendEmailNode(
  label: string,
  opts?: { id?: string; smtpCredentialId?: number | null }
): Node<BuilderNodeData> {
  const nodeId = opts?.id || nextId('send-email');
  return makeNode({
    id: nodeId,
    type: 'flowNode',
    data: {
      id: 'send_email',
      label,
      kind: 'send_email' as any,
      badge: 'Step',
      smtpCredentialId: opts?.smtpCredentialId ?? null,
    } as any,
  });
}

/** Creates an email-inbox node with optional IMAP credential id. */
export function makeEmailInboxNode(
  label: string,
  opts?: { id?: string; imapCredentialId?: number | null }
): Node<BuilderNodeData> {
  const nodeId = opts?.id || nextId('email-inbox');
  return makeNode({
    id: nodeId,
    type: 'flowNode',
    data: {
      id: 'email_inbox',
      label,
      kind: 'email_inbox' as any,
      badge: 'Step',
      imapCredentialId: opts?.imapCredentialId ?? null,
    } as any,
  });
}

/**
 * Creates an ssh / sftp / database node with its dedicated credential id
 * field (sshCredentialId / sftpCredentialId / dbCredentialId). These nodes
 * are detected purely by `data.kind` (see nodeRegistry.isSshNode etc.).
 */
export function makeCoreCredentialNode(
  kind: 'ssh' | 'sftp' | 'database',
  label: string,
  opts?: { id?: string; credentialId?: number | null }
): Node<BuilderNodeData> {
  const nodeId = opts?.id || nextId(kind);
  const credField =
    kind === 'ssh' ? 'sshCredentialId' : kind === 'sftp' ? 'sftpCredentialId' : 'dbCredentialId';
  return makeNode({
    id: nodeId,
    type: 'flowNode',
    data: {
      id: kind,
      label,
      kind: kind as any,
      badge: 'Step',
      [credField]: opts?.credentialId ?? null,
    } as any,
  });
}

/** Creates a transform node. */
export function makeTransformNode(
  label: string,
  id?: string
): Node<BuilderNodeData> {
  const nodeId = id || nextId('transform');
  return makeNode({
    id: nodeId,
    type: 'flowNode',
    data: {
      id: nodeId,
      label,
      kind: 'transform',
      badge: 'Transform',
    },
  });
}

// =========================================================================
// Edge factory helpers
// =========================================================================

let edgeCounter = 0;

export function resetEdgeCounter(): void {
  edgeCounter = 0;
}

/** Creates an edge between two node IDs. */
export function makeEdge(
  source: string,
  target: string,
  opts?: { id?: string; sourceHandle?: string; targetHandle?: string }
): Edge {
  return {
    id: opts?.id || `edge-${++edgeCounter}`,
    source,
    target,
    sourceHandle: opts?.sourceHandle,
    targetHandle: opts?.targetHandle,
  };
}

// =========================================================================
// Context builder
// =========================================================================

/** Builds a full ValidationContext (including cache) from nodes and edges. */
export function buildContext(
  nodes: Node<BuilderNodeData>[],
  edges: Edge[],
  backendErrors?: { type?: string; message: string; path?: string; context?: Record<string, unknown> }[],
  userCredentials?: Credential[]
): ValidationContext {
  const cache = buildValidationCache(nodes, edges);
  return {
    nodes,
    edges,
    backendErrors,
    cache,
    userCredentials,
  };
}

// =========================================================================
// Assertion helpers
// =========================================================================

/** Checks that a result has no errors. */
export function expectNoErrors(result: { issues: { severity: string }[] }): void {
  const errors = result.issues.filter((i) => i.severity === 'error');
  if (errors.length > 0) {
    throw new Error(
      `Expected no errors but found ${errors.length}: ${errors.map((e: any) => e.message).join(', ')}`
    );
  }
}

/** Checks that a result has no issues at all. */
export function expectNoIssues(result: { issues: unknown[] }): void {
  if (result.issues.length > 0) {
    throw new Error(
      `Expected no issues but found ${result.issues.length}: ${(result.issues as any[]).map((e) => e.message).join(', ')}`
    );
  }
}
