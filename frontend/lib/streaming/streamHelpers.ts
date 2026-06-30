/**
 * Shared streaming utilities
 * Single source of truth for stream-related operations
 */

import type { ToolActivity, ToolDiffData, GitStatusData } from '@/contexts/StreamingContext';

// ============== TOOL ACTIVITY HELPERS ==============

/**
 * Marks all pending tool activities as success.
 * Used when LLM responds with content or stream completes.
 */
export function markPendingToolsAsSuccess(activities: ToolActivity[]): ToolActivity[] {
  return activities.map(activity =>
    activity.status === 'pending'
      ? { ...activity, status: 'success' as const }
      : activity
  );
}

/**
 * Marks only thinking activities as success.
 * Used when stream is stopped (tools stay pending to show interruption).
 */
export function markThinkingAsSuccess(activities: ToolActivity[]): ToolActivity[] {
  return activities.map(activity =>
    activity.toolName === '_thinking' && activity.status === 'pending'
      ? { ...activity, status: 'success' as const }
      : activity
  );
}

// ============== EVENT MAPPING ==============

export interface StreamEventData {
  type: string;
  content?: string;
  thinking?: string;
  sections?: Array<{ title: string; content: string }>;
  streamId?: string;
  conversationId?: string;
  model?: string;
  toolName?: string;
  toolId?: string;
  arguments?: unknown;
  success?: boolean;
  result?: unknown;
  resultId?: string;
  durationMs?: number;
  error?: string;
  errorCode?: string;
  retryable?: boolean;
  title?: string;
  fullContent?: string;
  totalTokens?: number;
  partialContent?: string;
  interrupted?: boolean;
  reason?: string;
  message?: string;
  visualization?: {
    type: 'datasource' | 'table' | 'interface' | 'workflow' | 'workflow_run' | 'credential' | 'application' | 'web_search' | 'agent_browse';
    id: string;
    title?: string;
    runId?: string;
  };
  iconSlug?: string;
  displayToolName?: string;
  label?: string;
  tasksData?: {
    tasks: Array<{ id: number; description: string; status: string; result?: string }>;
    focusedTaskId?: number;
    completedCount: number;
    totalCount: number;
  };
  serviceApprovalRequired?: {
    services: Array<{
      serviceType: string;
      serviceName: string;
      iconSlug: string;
      toolName?: string;
      toolId?: string;
      description?: string;
    }>;
    reason?: string;
    needsAttention?: boolean;
  };
  toolAuthorization?: {
    rule: string;
    toolName?: string;
    action?: string;
    toolCallId?: string;
    argsSummary?: string;
    /** Publication id - only for application:acquire, used to open the install modal. */
    applicationId?: string;
  };
  credentialRequired?: boolean;
  serviceApproval?: {
    services: Array<{
      serviceType: string;
      serviceName: string;
      iconSlug: string;
      toolName?: string;
      toolId?: string;
      description?: string;
    }>;
    reason?: string;
  };
  // Source-tool render cards: red/green unified diff (repo edit/write/diff + interface
  // patch) and git status badges (repo git_status).
  diff?: ToolDiffData;
  gitStatus?: GitStatusData;
  // Fired once when post-turn COLD compaction persists a new summary. Lightweight
  // real-time cue for the UI to flash a banner; persistent source of truth is
  // Conversation.compactionMarker (the divider survives page reload).
  turnsCoveredCount?: number;
  summarizerModel?: string;
  generatedAt?: string;
}

/**
 * Maps V2 backend events to V1 frontend format.
 * Single source of truth for event transformation.
 */
export function mapV2EventToV1(
  data: Record<string, unknown>,
  eventType: string,
  currentStreamId: string | null
): StreamEventData {
  const streamId = (data.streamId as string) || currentStreamId || undefined;

  switch (eventType) {
    case 'stream_started':
      return {
        type: 'stream_id',
        content: data.streamId as string,
        streamId: data.streamId as string,
        conversationId: data.conversationId as string,
        model: data.model as string,
      };

    case 'content':
      return {
        type: 'content',
        content: data.content as string,
        streamId,
      };

    case 'thinking':
      return {
        type: 'thinking',
        thinking: data.thinking as string,
        streamId,
      };

    case 'thinking_section':
      return {
        type: 'thinking_section',
        title: data.title as string,
        content: data.content as string,
        streamId,
      };

    case 'tool_call':
      return {
        type: 'tool_call',
        toolName: data.toolName as string,
        toolId: data.toolId as string,
        arguments: data.arguments,
        streamId,
      };

    case 'tool_result':
      return {
        type: 'tool_result',
        toolId: data.toolId as string,
        toolName: data.toolName as string,
        success: data.success as boolean,
        result: data.result != null ? String(data.result) : undefined,
        resultId: data.resultId as string,
        durationMs: data.durationMs as number,
        error: data.error as string,
        visualization: data.visualization as StreamEventData['visualization'],
        iconSlug: data.iconSlug as string,
        displayToolName: data.displayToolName as string,
        label: data.label as string,
        tasksData: data.tasksData as StreamEventData['tasksData'],
        serviceApproval: data.serviceApproval as StreamEventData['serviceApproval'],
        diff: data.diff as StreamEventData['diff'],
        gitStatus: data.gitStatus as StreamEventData['gitStatus'],
        streamId,
      };

    case 'completed':
      return {
        type: 'done',
        fullContent: data.fullContent as string,
        totalTokens: data.totalTokens as number,
        streamId,
      };

    case 'stopped':
      return {
        type: 'stopped',
        partialContent: data.partialContent as string,
        streamId,
      };

    case 'error':
      return {
        type: 'error',
        error: data.error as string,
        errorCode: data.errorCode as string,
        retryable: data.retryable as boolean,
        streamId,
      };

    case 'heartbeat':
      return {
        type: 'heartbeat',
        streamId,
      };

    case 'title_updated':
      return {
        type: 'title',
        title: data.title as string,
        conversationId: data.conversationId as string,
        streamId,
      };

    case 'service_approval_required':
      return {
        type: 'service_approval_required',
        serviceApprovalRequired: {
          services: (data.services as StreamEventData['serviceApprovalRequired']['services']) || [],
          reason: data.reason as string,
          needsAttention: data.needsAttention as boolean,
        },
        streamId,
      };

    case 'tool_authorization_required':
      return {
        type: 'tool_authorization_required',
        toolAuthorization: (data.toolAuthorization as StreamEventData['toolAuthorization']) || undefined,
      };

    case 'pending_action_cancelled':
      return {
        type: 'pending_action_cancelled',
        reason: data.reason as string,
        message: data.message as string,
        streamId,
      };

    case 'compaction_done':
      return {
        type: 'compaction_done',
        conversationId: data.conversationId as string,
        turnsCoveredCount: data.turnsCoveredCount as number,
        summarizerModel: data.summarizerModel as string,
        generatedAt: data.generatedAt as string,
        streamId,
      };

    default:
      return { ...(data as unknown as StreamEventData), type: (data.type as string) || eventType };
  }
}

// ============== EVENT TYPE DETECTION (for WebSocket) ==============

/**
 * Detects the event type from a raw JSON payload received via WebSocket.
 * Mirrors backend StreamPubSubService.deserializeEvent() logic.
 * WebSocket events don't have a streaming event: header, so we detect type from JSON structure.
 */
export function detectStreamEventType(data: Record<string, unknown>): string {
  // Sub-agent forwarded events (from parent conversation)
  if (typeof data.type === 'string' && data.type.startsWith('sub_agent_')) return data.type;
  if ('fullContent' in data && 'totalTokens' in data) return 'completed';
  if ('error' in data && 'errorCode' in data) return 'error';
  if ('partialContent' in data) return 'stopped';
  if ('success' in data && 'resultId' in data) return 'tool_result';
  if ('visualizationType' in data && 'visualizationId' in data) return 'visualization_ready';
  if ('screenshotIndex' in data && 'screenshotKey' in data) return 'fetch_screenshot';
  // AgentBrowseStep: live-view bootstrap from BrowserSessionLifecycleService.
  // Discriminator: cdpToken + cdpWsUrl (distinguishes from FetchScreenshot).
  if ('cdpToken' in data && 'cdpWsUrl' in data) return 'agent_browse_step';
  if ('toolName' in data && 'toolId' in data && 'arguments' in data) return 'tool_call';
  if ('credentialType' in data) return 'credential_required';
  if ('toolAuthorization' in data) return 'tool_authorization_required';
  if ('services' in data && 'reason' in data) return 'service_approval_required';
  // CompactionDone carries conversationId but no title - match its own discriminator pair.
  if ('turnsCoveredCount' in data && 'summarizerModel' in data) return 'compaction_done';
  if ('title' in data && 'conversationId' in data) return 'title_updated';
  if ('title' in data && 'content' in data) return 'thinking_section';
  if ('model' in data && 'conversationId' in data) return 'stream_started';
  if ('reason' in data && 'message' in data) return 'pending_action_cancelled';
  if ('thinking' in data) return 'thinking';
  if ('content' in data) return 'content';
  return 'heartbeat';
}

// ============== LOGGING ==============

const LOG_PREFIX = '[Streaming]';

export const streamLogger = {
  info: (message: string, data?: unknown) => {
    console.log(`${LOG_PREFIX} ${message}`, data !== undefined ? data : '');
  },
  warn: (message: string, data?: unknown) => {
    console.warn(`${LOG_PREFIX} ⚠️ ${message}`, data !== undefined ? data : '');
  },
  error: (message: string, data?: unknown) => {
    console.error(`${LOG_PREFIX} ❌ ${message}`, data !== undefined ? data : '');
  },
  debug: (message: string, data?: unknown) => {
    console.log(`${LOG_PREFIX} 🔍 ${message}`, data !== undefined ? data : '');
  },
};
