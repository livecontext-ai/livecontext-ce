/**
 * Agent Service
 *
 * Handles agent CRUD operations and webhook management.
 * Single Responsibility: Only agent-related operations.
 */

import { apiClient } from '../api-client';
import type { Agent, AgentUpdateInput, AgentWebhook, CreateAgentWebhookRequest, AgentSchedule, CreateAgentScheduleRequest, AgentWidgetConfig, CreateAgentWidgetRequest } from './types';
import type {
  AgentExecutionRecord,
  AgentExecutionMessage,
  AgentExecutionToolCall,
  AgentExecutionIteration,
  ToolCallStats,
  ResourceCallStats,
  DailyStats,
  FleetSummary,
  ChatSummary,
  SubAgentCallStats,
  ModelStats,
  ConversationStats,
  PagedResponse,
} from './agent-metrics.types';

export interface AgentAvatar {
  id: string;
  avatarUrl?: string;
}

/**
 * Fleet batch - all agents' usage stats in one payload, each row keyed by
 * {@code agentId}. Mirrors {@link AgentService.getFleetStats}.
 */
export interface FleetStats {
  toolStats: Array<ToolCallStats & { agentId: string }>;
  resourceStats: Array<ResourceCallStats & { agentId: string }>;
  subAgentStats: Array<SubAgentCallStats & { agentId: string }>;
  modelStats: Array<ModelStats & { agentId: string }>;
}

/** Fleet batch - one row per agent that has an active webhook or enabled schedule. */
export interface FleetTrigger {
  agentId: string;
  hasWebhook: boolean;
  hasSchedule: boolean;
  webhookUrl?: string | null;
  cronExpression?: string | null;
  timezone?: string | null;
}

export class AgentService {
  /**
   * Get all agents for current user
   */
  async getAgents(): Promise<Agent[]> {
    return apiClient.get<Agent[]>('/agents');
  }

  /**
   * Lightweight (id, avatarUrl) list - used by the conversation sidebar to
   * render agent avatars next to chat titles. Backed by a SQL projection that
   * skips the system_prompt LOB and config fields, so the payload is ~80 bytes
   * per agent vs. the full entity {@link #getAgents} returns.
   */
  async getAgentAvatars(): Promise<AgentAvatar[]> {
    return apiClient.get<AgentAvatar[]>('/agents/avatars');
  }

  /**
   * Paged, DB-searchable, server-sorted + server-visibility-filtered list of agents.
   * Returns the envelope { items, totalCount, page, size, publicationStatuses }.
   * `q` is matched server-side against name + description; `sort` = name | lastModified;
   * `visibility` = all | public | private. `publicationStatuses` maps each shared agent id on the
   * page to { status, rejectionReason? } (absent = not shared), batched server-side - so the card
   * needs no separate getAllMyPublications sweep.
   */
  async getAgentsPage(options: {
    page?: number;
    size?: number;
    q?: string;
    sort?: string;
    visibility?: 'all' | 'public' | 'private';
  } = {}): Promise<{
    items: Agent[];
    totalCount: number;
    page: number;
    size: number;
    publicationStatuses: Record<string, { status?: string; rejectionReason?: string | null }>;
  }> {
    const params: Record<string, string> = {};
    if (options.page != null) params.page = String(options.page);
    if (options.size != null) params.size = String(options.size);
    if (options.q && options.q.trim().length > 0) params.q = options.q.trim();
    if (options.sort) params.sort = options.sort;
    if (options.visibility) params.visibility = options.visibility;
    const data = await apiClient.get<any>('/agents/paged', { params });
    return {
      items: data.items ?? [],
      totalCount: data.totalCount ?? 0,
      page: data.page ?? 0,
      size: data.size ?? 25,
      publicationStatuses: data.publicationStatuses ?? {},
    };
  }

  /**
   * Get a single agent by ID
   */
  async getAgent(id: string): Promise<Agent> {
    return apiClient.get<Agent>(`/agents/${id}`);
  }

  /**
   * Get an agent by conversation ID
   */
  async getAgentByConversationId(conversationId: string): Promise<Agent | null> {
    try {
      return await apiClient.get<Agent>(`/agents/by-conversation/${conversationId}`);
    } catch (error: any) {
      if (error?.response?.status === 404) {
        return null;
      }
      throw error;
    }
  }

  /**
   * Create a new agent.
   * `AgentUpdateInput` excludes server-managed budget fields
   * (`creditsConsumed`/`creditsReserved`/`creditsFree`) at compile time - see types.ts.
   */
  async createAgent(agent: AgentUpdateInput): Promise<Agent> {
    return apiClient.post<Agent>('/agents', agent);
  }

  /**
   * Update an agent.
   * Sending `creditsConsumed`, `creditsReserved`, or `creditsFree` is rejected by the
   * backend with 400 `read_only_field` - `AgentUpdateInput` blocks this at compile time.
   * Use `resetCredits()` to zero consumption instead.
   */
  async updateAgent(id: string, agent: AgentUpdateInput): Promise<Agent> {
    return apiClient.put<Agent>(`/agents/${id}`, agent);
  }

  /**
   * Delete an agent
   */
  async deleteAgent(id: string): Promise<void> {
    return apiClient.delete<void>(`/agents/${id}`);
  }

  /**
   * Clone an agent
   */
  async cloneAgent(id: string): Promise<Agent> {
    return apiClient.post<Agent>(`/agents/${id}/clone`, {});
  }

  /**
   * Reset credits consumed for an agent.
   *
   * Throws with `status: 409` and body `{ error: 'reservation_in_flight', ... }` when the
   * agent has a non-zero `creditsReserved` - a sub-agent cascade reservation is in flight.
   * UI callers should surface a "Wait for descendants to finish" hint and offer a retry.
   * See the project docs
   */
  async resetCredits(id: string): Promise<void> {
    return apiClient.post<void>(`/agents/${id}/budget/reset`, {});
  }

  // ============================================
  // Webhook Management
  // ============================================

  /**
   * Create or update webhook for an agent
   */
  async createOrUpdateWebhook(agentId: string, config: CreateAgentWebhookRequest): Promise<AgentWebhook> {
    return apiClient.post<AgentWebhook>(`/agents/${agentId}/webhook`, config);
  }

  /**
   * Get webhook configuration for an agent
   */
  async getWebhook(agentId: string): Promise<AgentWebhook | null> {
    try {
      return await apiClient.get<AgentWebhook>(`/agents/${agentId}/webhook`);
    } catch (error: any) {
      if (error?.response?.status === 404) {
        return null;
      }
      throw error;
    }
  }

  /**
   * Regenerate webhook token for an agent
   */
  async regenerateWebhookToken(agentId: string): Promise<AgentWebhook> {
    return apiClient.post<AgentWebhook>(`/agents/${agentId}/webhook/regenerate`, {});
  }

  /**
   * Enable or disable webhook for an agent
   */
  async setWebhookActive(agentId: string, active: boolean): Promise<AgentWebhook> {
    return apiClient.patch<AgentWebhook>(`/agents/${agentId}/webhook`, { active });
  }

  /**
   * Delete webhook for an agent
   */
  async deleteWebhook(agentId: string): Promise<void> {
    return apiClient.delete<void>(`/agents/${agentId}/webhook`);
  }

  // ============================================
  // Schedule Management
  // ============================================

  async createOrUpdateSchedule(agentId: string, config: CreateAgentScheduleRequest): Promise<AgentSchedule> {
    return apiClient.post<AgentSchedule>(`/agents/${agentId}/schedule`, config);
  }

  async getSchedule(agentId: string): Promise<AgentSchedule | null> {
    try {
      return await apiClient.get<AgentSchedule>(`/agents/${agentId}/schedule`);
    } catch (error: any) {
      if (error?.response?.status === 404 || error?.status === 404) return null;
      throw error;
    }
  }

  async toggleSchedule(agentId: string, enabled: boolean): Promise<AgentSchedule> {
    return apiClient.patch<AgentSchedule>(`/agents/${agentId}/schedule`, { enabled });
  }

  async deleteSchedule(agentId: string): Promise<void> {
    return apiClient.delete<void>(`/agents/${agentId}/schedule`);
  }

  // ============================================
  // Widget Configuration Management
  // ============================================

  /**
   * Create or update widget configuration for an agent
   */
  async createOrUpdateWidgetConfig(agentId: string, config: CreateAgentWidgetRequest): Promise<AgentWidgetConfig> {
    return apiClient.post<AgentWidgetConfig>(`/agents/${agentId}/widget`, config);
  }

  /**
   * Get widget configuration for an agent
   */
  async getWidgetConfig(agentId: string): Promise<AgentWidgetConfig | null> {
    try {
      return await apiClient.get<AgentWidgetConfig>(`/agents/${agentId}/widget`);
    } catch (error: any) {
      if (error?.response?.status === 404) {
        return null;
      }
      throw error;
    }
  }

  /**
   * Enable or disable widget for an agent
   */
  async setWidgetActive(agentId: string, active: boolean): Promise<AgentWidgetConfig> {
    return apiClient.patch<AgentWidgetConfig>(`/agents/${agentId}/widget`, { active });
  }

  /**
   * Delete widget configuration for an agent
   */
  async deleteWidgetConfig(agentId: string): Promise<void> {
    return apiClient.delete<void>(`/agents/${agentId}/widget`);
  }

  // ============================================
  // Agent Metrics & Observability
  // ============================================

  /**
   * Get paginated execution history for an agent
   */
  async getAgentExecutions(
    agentId: string,
    page = 0,
    size = 20
  ): Promise<PagedResponse<AgentExecutionRecord>> {
    return apiClient.get<PagedResponse<AgentExecutionRecord>>(
      `/agents/${agentId}/executions`,
      { params: { page: String(page), size: String(size) } }
    );
  }

  /**
   * Get a single execution with all counters
   */
  async getExecution(executionId: string): Promise<AgentExecutionRecord> {
    return apiClient.get<AgentExecutionRecord>(`/agents/executions/${executionId}`);
  }

  /**
   * Get ordered conversation messages for an execution - paginated DESC by sequenceNumber.
   * Page 0 = newest batch. Callers reverse to ASC for chronological display, or use
   * `getExecutionConversation()` for the convenience first-page array.
   */
  async getExecutionConversationPaged(
    executionId: string,
    page = 0,
    size = 30,
  ): Promise<PagedResponse<AgentExecutionMessage>> {
    return apiClient.get<PagedResponse<AgentExecutionMessage>>(
      `/agents/executions/${executionId}/conversation`,
      { params: { page: String(page), size: String(size) } },
    );
  }

  /**
   * Convenience: first page of the conversation, reversed to ASC for chronological display.
   * Use this when you don't need pagination metadata; otherwise call `getExecutionConversationPaged`
   * and wire lazy-load with `useExecutionPagedResource`.
   */
  async getExecutionConversation(executionId: string, page = 0, size = 30): Promise<AgentExecutionMessage[]> {
    const pageResponse = await this.getExecutionConversationPaged(executionId, page, size);
    return [...pageResponse.content].reverse();
  }

  /**
   * Get tool calls for an execution - paginated DESC by sequenceNumber.
   * Tool-call payloads can run MB-scale; long agent loops fetched unbounded would OOM.
   */
  async getExecutionToolCallsPaged(
    executionId: string,
    page = 0,
    size = 30,
  ): Promise<PagedResponse<AgentExecutionToolCall>> {
    return apiClient.get<PagedResponse<AgentExecutionToolCall>>(
      `/agents/executions/${executionId}/tool-calls`,
      { params: { page: String(page), size: String(size) } },
    );
  }

  /** Convenience: first page of tool calls, reversed to ASC. */
  async getExecutionToolCalls(executionId: string, page = 0, size = 30): Promise<AgentExecutionToolCall[]> {
    const pageResponse = await this.getExecutionToolCallsPaged(executionId, page, size);
    return [...pageResponse.content].reverse();
  }

  /** Get iterations for an execution - paginated DESC by iterationNumber. */
  async getExecutionIterationsPaged(
    executionId: string,
    page = 0,
    size = 30,
  ): Promise<PagedResponse<AgentExecutionIteration>> {
    return apiClient.get<PagedResponse<AgentExecutionIteration>>(
      `/agents/executions/${executionId}/iterations`,
      { params: { page: String(page), size: String(size) } },
    );
  }

  /** Convenience: first page of iterations, reversed to ASC. */
  async getExecutionIterations(executionId: string, page = 0, size = 30): Promise<AgentExecutionIteration[]> {
    const pageResponse = await this.getExecutionIterationsPaged(executionId, page, size);
    return [...pageResponse.content].reverse();
  }

  /**
   * Get per-caller sub-agent call stats for a given agent.
   * Returns how many times this agent called each sub-agent.
   */
  async getSubAgentCallStats(agentId: string): Promise<SubAgentCallStats[]> {
    return apiClient.get<SubAgentCallStats[]>(`/agents/${agentId}/sub-agent-stats`);
  }

  /**
   * Get all caller→callee edges for ancestor detection.
   */
  async getSubAgentEdges(): Promise<Array<{ callerId: string; calleeId: string }>> {
    return apiClient.get<Array<{ callerId: string; calleeId: string }>>('/agents/sub-agent-edges');
  }

  /**
   * Fleet batch - ALL agents' tool/resource/sub-agent/model stats in ONE call
   * (each row carries {@code agentId}). Replaces the per-agent tool-stats /
   * resource-stats / sub-agent-stats / model-stats fan-out (4 requests per agent)
   * the Agent Fleet canvas otherwise makes.
   */
  async getFleetStats(): Promise<FleetStats> {
    return apiClient.get<FleetStats>('/agents/stats');
  }

  /**
   * Fleet batch - webhook + schedule trigger state for every workspace agent in
   * ONE call. Only agents with an active webhook or enabled schedule are returned.
   * Replaces the per-agent getWebhook + getSchedule fan-out (2 requests per agent,
   * nearly all 404).
   */
  async getFleetTriggers(): Promise<FleetTrigger[]> {
    return apiClient.get<FleetTrigger[]>('/agents/triggers');
  }

  /**
   * Get per-tool aggregate stats from materialized view
   */
  async getToolStats(): Promise<ToolCallStats[]> {
    return apiClient.get<ToolCallStats[]>('/agents/metrics/tools');
  }

  /**
   * Get per-model aggregate stats for a specific agent
   */
  async getModelStats(agentId: string): Promise<ModelStats[]> {
    return apiClient.get<ModelStats[]>(`/agents/${agentId}/model-stats`);
  }

  /**
   * Get per-conversation aggregate stats for a specific agent
   */
  async getConversationStats(agentId: string): Promise<ConversationStats[]> {
    return apiClient.get<ConversationStats[]>(`/agents/${agentId}/conversation-stats`);
  }

  /**
   * Get per-tool aggregate stats for a specific agent
   */
  async getAgentToolStats(agentId: string): Promise<ToolCallStats[]> {
    return apiClient.get<ToolCallStats[]>(`/agents/${agentId}/tool-stats`);
  }

  /**
   * Get per-RESOURCE aggregate stats for a specific agent - resource-family tools
   * (table/interface/workflow/application/skill) broken down by the targeted
   * resource id, so each fleet leaf shows its own usage instead of the family total.
   */
  async getAgentResourceStats(agentId: string): Promise<ResourceCallStats[]> {
    return apiClient.get<ResourceCallStats[]>(`/agents/${agentId}/resource-stats`);
  }

  /**
   * Get daily time-series stats from materialized view
   */
  async getDailyStats(days = 30, agentId?: string): Promise<DailyStats[]> {
    const params: Record<string, string> = { days: String(days) };
    if (agentId) params.agentId = agentId;
    return apiClient.get<DailyStats[]>('/agents/metrics/daily', { params });
  }

  /**
   * Get fleet summary from agents table counters
   */
  async getFleetSummary(): Promise<FleetSummary> {
    return apiClient.get<FleetSummary>('/agents/metrics/fleet-summary');
  }

  // ============================================
  // Agent Type Metrics (classify / guardrail)
  // ============================================

  /**
   * Get summary metrics for a specific agent type (classify or guardrail)
   */
  async getAgentTypeSummary(agentType: string): Promise<ChatSummary> {
    return apiClient.get<ChatSummary>(`/agents/metrics/${agentType}-summary`);
  }

  /**
   * Get paginated executions for a specific agent type
   */
  async getAgentTypeExecutions(agentType: string, page = 0, size = 20): Promise<PagedResponse<AgentExecutionRecord>> {
    return apiClient.get<PagedResponse<AgentExecutionRecord>>(
      `/agents/metrics/${agentType}-executions`,
      { params: { page: String(page), size: String(size) } }
    );
  }

  // ============================================
  // General Chat Metrics (no agent selected)
  // ============================================

  /**
   * Get summary metrics for general chat executions
   */
  async getChatSummary(): Promise<ChatSummary> {
    return apiClient.get<ChatSummary>('/agents/metrics/chat-summary');
  }

  /**
   * Get paginated general chat executions
   */
  async getChatExecutions(page = 0, size = 20): Promise<PagedResponse<AgentExecutionRecord>> {
    return apiClient.get<PagedResponse<AgentExecutionRecord>>(
      '/agents/metrics/chat-executions',
      { params: { page: String(page), size: String(size) } }
    );
  }

  /**
   * Get daily time-series stats for general chat
   */
  async getChatDailyStats(days = 30): Promise<DailyStats[]> {
    return apiClient.get<DailyStats[]>('/agents/metrics/chat-daily', {
      params: { days: String(days) },
    });
  }

  /**
   * Get per-tool stats for general chat executions
   */
  async getChatToolStats(): Promise<Array<{ toolName: string; totalCalls: number; successCount: number; failureCount: number; avgDurationMs: number; maxDurationMs: number; repeatCallCount: number; lastUsedAt: string | null }>> {
    return apiClient.get('/agents/metrics/chat-tools');
  }

  /**
   * Get paginated workflow-level agent executions
   */
  async getWorkflowAgentExecutions(
    workflowId: string,
    page = 0,
    size = 20
  ): Promise<PagedResponse<AgentExecutionRecord>> {
    return apiClient.get<PagedResponse<AgentExecutionRecord>>(
      `/workflows/${workflowId}/agent-metrics/executions`,
      { params: { page: String(page), size: String(size) } }
    );
  }
}

export const agentService = new AgentService();
