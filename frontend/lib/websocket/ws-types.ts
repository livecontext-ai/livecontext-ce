/**
 * WebSocket protocol types - shared between client and server.
 */

// ── Protocol Envelope ──

export interface WsEnvelope<T = unknown> {
  v: 1;
  type: string;
  id: string;
  ref?: string;
  channel?: string;
  channelSeq?: number;
  ts: number;
  payload?: T;
}

// ── Client → Server message types ──

export type ClientMessageType =
  | 'subscribe'
  | 'unsubscribe'
  | 'action'
  | 'pong'
  | 'auth.refresh';

// ── Server → Client message types ──

export type ServerMessageType =
  | 'hello'
  | 'ping'
  | 'subscribed'
  | 'unsubscribed'
  | 'event'
  | 'action.ack'
  | 'action.error'
  | 'auth.refreshed'
  | 'goaway'
  | 'error';

// ── System events ──

export interface HelloPayload {
  sessionId: string;
  userId: string;
  heartbeatMs: number;
  maxSubscriptions: number;
}

export interface GoawayPayload {
  reason: string;
  limit?: number;
}

export interface ErrorPayload {
  message: string;
}

// ── Channel event payloads ──

export interface ChannelEventPayload {
  v: number;
  type: string;
  id: string;
  ts: number;
  payload: unknown;
}

// ── Workflow events ──

export interface WorkflowBatchUpdate {
  runId: string;
  timestamp: number;
  steps: WorkflowStepState[];
  edges: WorkflowEdgeState[];
  pendingSignals?: PendingSignal[];
  loops?: unknown[];
  merges?: unknown[];
  interfaces?: unknown[];
}

export interface WorkflowStepState {
  id: string;
  label: string;
  status: string;
  statusCounts?: {
    running: number;
    completed: number;
    failed: number;
    skipped: number;
    total: number;
  };
  output?: { selectedBranch?: string };
}

export interface WorkflowEdgeState {
  id: string;
  from: string;
  to: string;
  running: number;
  completed: number;
  skipped: number;
}

export interface PendingSignal {
  id: number;
  nodeId: string;
  signalType: string;
  status: string;
  epoch?: number;
  itemId?: string;
  expiresAt?: string;
  config?: unknown;
  /**
   * Split-item context for this signal (e.g. `current_item`), sent by the
   * backend when the signal was registered inside a Split iteration. Used by
   * the run-mode UI to preview WHICH item a pending approval refers to.
   */
  itemContext?: Record<string, unknown>;
  /**
   * Resolved approval context (USER_APPROVAL only): the node's `contextTemplate`
   * rendered against the execution context at pause time, shown to the approver
   * as a human sentence. Independent of `itemContext` (which is the auto split item).
   */
  approvalContext?: string;
}

export interface WorkflowStatusEvent {
  status: string;
  message: string;
  terminal?: boolean;
}

// ── Conversation events ──

export interface ConversationContentEvent {
  streamId: string;
  content?: string;
  thinking?: string;
}

export interface ConversationToolCallEvent {
  streamId: string;
  toolName: string;
  toolId: string;
  arguments: string;
}

export interface ConversationToolResultEvent {
  streamId: string;
  toolName: string;
  toolId: string;
  resultId: string;
  success: boolean;
}

export interface ConversationCompletedEvent {
  streamId: string;
  fullContent: string;
  totalTokens: number;
}

// ── Notification events ──

export interface NotificationEvent {
  kind: string;
  data: unknown;
}

export interface ApprovalRequestedNotification {
  runId: string;
  nodeId: string;
  signalId: number;
  workflowName?: string;
}

export interface WorkflowCompletedNotification {
  runId: string;
  status: string;
  workflowId: string;
}

// ── Action payloads ──

export interface SignalResolveAction {
  action: 'signal.resolve';
  data: {
    signalId: number;
    resolution: string;
    payload?: Record<string, unknown>;
  };
}

// ── Connection status ──

export type WsConnectionStatus =
  | 'disconnected'
  | 'connecting'
  | 'connected'
  | 'reconnecting';

// ── Channel handler type ──

export type ChannelHandler<T = unknown> = (data: T) => void;
