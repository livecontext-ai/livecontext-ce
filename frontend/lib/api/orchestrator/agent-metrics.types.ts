/**
 * Types for Agent Observability Metrics (Phase 2)
 */

import type { AgentStopReason } from '@/types/agentStopReason';

export interface AgentMetricsSummary {
  totalExecutions: number;
  successCount: number;
  failureCount: number;
  totalTokensUsed: number;
  totalToolCalls: number;
  totalDurationMs: number;
  lastExecutionAt?: string;
}

export interface AgentExecutionRecord {
  id: string;
  agentEntityId?: string;
  workflowId?: string;
  workflowRunId?: string;
  runId: string;
  nodeId: string;
  agentType: string;
  epoch?: number;
  spawn?: number;
  itemIndex?: number;
  loopIteration?: number;
  provider?: string;
  model?: string;
  temperature?: number;
  maxTokensConfig?: number;
  maxIterationsConfig?: number;
  status: string;
  stopReason?: AgentStopReason;
  /**
   * Scope reported by the budget guard chain when stopReason === 'BUDGET_EXHAUSTED'.
   * 'tenant' = tenant balance ran out; 'agent' = per-agent quota exhausted.
   * Sourced from the run metrics map (key: budgetScope) populated by AgentLoopService.
   */
  budgetScope?: 'tenant' | 'agent';
  errorMessage?: string;
  iterationCount: number;
  totalToolCalls: number;
  successfulToolCalls: number;
  failedToolCalls: number;
  messageCount: number;
  initialHistorySize: number;
  totalPromptTokens: number;
  totalCompletionTokens: number;
  totalTokens: number;
  totalCacheCreationTokens: number;
  totalCacheReadTokens: number;
  totalCachedTokens: number;
  totalReasoningTokens: number;
  durationMs?: number;
  loopDetected: boolean;
  loopType?: string;
  loopToolName?: string;
  toolSequence?: string;
  distinctTools?: string[];
  startedAt: string;
  endedAt?: string;
  source?: string;
  callerAgentEntityId?: string;
  depth: number;
  systemPrompt?: string;
  creditsConsumed?: number;
}

export interface AgentExecutionMessage {
  id: number;
  sequenceNumber: number;
  iterationNumber?: number;
  role: 'SYSTEM' | 'USER' | 'ASSISTANT' | 'TOOL';
  content?: string;
  contentStorageId?: string;
  contentLength?: number;
  toolCallId?: string;
  toolName?: string;
  toolCallsRequested?: Array<{
    id: string;
    toolName: string;
    arguments: Record<string, unknown>;
  }>;
}

export interface AgentExecutionToolCall {
  id: number;
  sequenceNumber: number;
  iterationNumber: number;
  toolCallId?: string;
  toolName: string;
  parallelIndex?: number;
  arguments?: Record<string, unknown>;
  success: boolean;
  content?: string;
  contentStorageId?: string;
  contentLength?: number;
  errorMessage?: string;
  durationMs?: number;
  metadata?: Record<string, unknown>;
  isRepeat: boolean;
  consecutiveCount: number;
}

export interface ToolCallStats {
  toolName: string;
  totalCalls: number;
  successCount: number;
  failureCount: number;
  successRatePct: number;
  avgDurationMs: number;
  maxDurationMs?: number;
  p95DurationMs?: number;
  executionCount: number;
  repeatCallCount: number;
  lastUsedAt?: string;
}

/**
 * Per-RESOURCE breakdown of a resource-family tool's calls (table/interface/
 * workflow/application/skill). Unlike {@link ToolCallStats} - which groups by the
 * family tool name only - this attributes each call to the specific resource id
 * the agent targeted, so a fleet leaf shows ITS own usage instead of the whole
 * family's. `resourceId` matches the leaf id (numeric datasource id, or a UUID).
 * See AgentMetricsQueryService.getResourceStatsByAgent.
 */
export interface ResourceCallStats {
  toolName: string;
  resourceId: string;
  totalCalls: number;
  successCount: number;
  failureCount: number;
}

export interface DailyStats {
  executionDate: string;
  provider?: string;
  model?: string;
  totalExecutions: number;
  completedCount: number;
  failedCount: number;
  cancelledCount: number;
  loopDetectedCount: number;
  totalToolCalls: number;
  totalTokens: number;
  avgDurationMs: number;
  avgIterations?: number;
  /** Cache-read subset of totalTokens for this day/provider/model bucket. */
  cachedTokens?: number;
}

export interface FleetSummary {
  totalAgents: number;
  totalExecutions: number;
  totalTokensUsed: number;
  totalToolCalls: number;
  totalDurationMs: number;
  totalCreditsConsumed: number;
  successCount: number;
  failureCount: number;
  cancelledCount: number;
  loopDetectedCount: number;
  avgDurationMs: number;
  successRate: number;
  /** Cache-read subset of totalTokensUsed (sourced from agent_executions). */
  totalCachedTokens?: number;
}

export interface ChatSummary {
  totalExecutions: number;
  successCount: number;
  failureCount: number;
  cancelledCount: number;
  loopDetectedCount: number;
  totalTokensUsed: number;
  totalToolCalls: number;
  totalDurationMs: number;
  totalCreditsConsumed: number;
  avgDurationMs: number;
  successRate: number;
  lastExecutionAt?: string;
  /** Cache-read subset of totalTokensUsed (sourced from agent_executions). */
  totalCachedTokens?: number;
}

export interface SubAgentCallStats {
  calleeAgentId: string;
  totalCalls: number;
  successCount: number;
  failureCount: number;
}

export interface ModelStats {
  model: string;
  totalExecutions: number;
  successCount: number;
  failureCount: number;
  /**
   * Subset of failureCount where the agent was throttled at the credit gate
   * (status='FAILED' AND stop_reason='BUDGET_EXHAUSTED'). Surfaced separately
   * so the Fleet model chip can render an amber Coins indicator distinct from
   * the red FAILED chip. Frontend subtracts this from rawFailed when drawing
   * the red chip so a single throttled execution renders as ONE amber chip,
   * not red+amber double-count. See AgentMetricsQueryService.getModelStatsByAgent.
   */
  budgetExhaustedCount?: number;
}

export interface ConversationStats {
  conversationId: string;
  totalExecutions: number;
  successCount: number;
  failureCount: number;
}

export interface AgentExecutionIteration {
  id: number;
  executionId: string;
  iterationNumber: number;
  toolCallCount: number;
  promptTokens?: number;
  completionTokens?: number;
  cacheCreationTokens?: number;
  cacheReadTokens?: number;
  cachedTokens?: number;
  reasoningTokens?: number;
  durationMs?: number;
  finishReason?: string;
  isFinal: boolean;
  createdAt: string;
}

export interface PagedResponse<T> {
  content: T[];
  totalElements: number;
  totalPages: number;
  number: number;
  size: number;
  first: boolean;
  last: boolean;
}
