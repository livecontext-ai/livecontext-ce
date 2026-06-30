'use client';

/**
 * StreamingContext - Multi-Stream Architecture
 *
 * This context manages multiple parallel streams:
 * - Each conversation can have its own independent stream
 * - Streams are stored in a Map<conversationId, StreamState>
 * - Switching conversations doesn't abort other streams
 *
 * Components should NEVER manage streaming state locally.
 * Use this context for all streaming operations.
 */

import React, { createContext, useContext, useReducer, useRef, useCallback, useMemo, useEffect, useState, ReactNode } from 'react';
import { unifiedApiService } from '@/lib/api';
import { is402Error, is413StorageError } from '@/lib/api/error-utils';

/** Detect LLM API key errors from backend error messages. */
function isApiKeyError(message: string): boolean {
  const lower = message.toLowerCase();
  return lower.includes('api key') || lower.includes('not configured') ||
    lower.includes('provider is not configured') || lower.includes('key is missing') ||
    lower.includes('invalid api key') || lower.includes('incorrect api key') ||
    lower.includes('authentication') && lower.includes('key');
}
import { showInsufficientCreditsModal } from '@/components/billing/InsufficientCreditsModal';
import { showInsufficientStorageModal } from '@/components/billing/InsufficientStorageModal';
import { showMissingApiKeyModal } from '@/components/billing/MissingApiKeyModal';
import { showAgentErrorModal } from '@/components/billing/AgentErrorModal';
import { handleCeRelayError } from '@/lib/billing/ceRelayErrorModals';
import { useAuthGuard } from '@/hooks/useAuthGuard';
import { getModelsCache, getEffectiveDefaultModel, getEffectiveDefaultProvider } from '@/hooks/useModels';
import { wsClient } from '@/lib/websocket';
import { useChannel } from '@/lib/websocket/use-channel';
import {
  markPendingToolsAsSuccess,
  markThinkingAsSuccess,
  detectStreamEventType,
  mapV2EventToV1,
  streamLogger,
} from '@/lib/streaming/streamHelpers';
import {
  AUTO_OPEN_TYPES,
  enqueueAutoOpen,
  flushAutoOpen,
  type AutoOpenVisualization,
} from '@/contexts/sidePanelAutoOpen';

// ============== WORKFLOW PLAN MODIFICATION EVENT ==============

// Debounce timer for workflow plan modified events
let workflowPlanModifiedDebounceTimer: ReturnType<typeof setTimeout> | null = null;
const ACTIVE_STREAMS_BOOT_DELAY_MS = 1500;

// Counter for generating unique IDs (prevents duplicates when Date.now() returns same value)
let uniqueIdCounter = 0;

/**
 * Dispatch a debounced event when the workflow plan is modified by the LLM.
 * This allows the WorkflowBuilder to refresh without multiple rapid re-renders.
 *
 * @param toolName - The real tool name from backend (e.g., 'workflow')
 */
function dispatchWorkflowPlanModified(toolName: string) {
  // Only dispatch for workflow tool - this is the exact name from backend
  if (toolName !== 'workflow') {
    return;
  }

  // Clear previous debounce timer
  if (workflowPlanModifiedDebounceTimer) {
    clearTimeout(workflowPlanModifiedDebounceTimer);
  }

  // Debounce: wait 500ms before dispatching to group rapid changes
  workflowPlanModifiedDebounceTimer = setTimeout(() => {
    window.dispatchEvent(new CustomEvent('workflowPlanModified', {
      detail: { timestamp: Date.now() }
    }));
    workflowPlanModifiedDebounceTimer = null;
  }, 500);
}

// ============== DATA SOURCE MODIFICATION EVENT ==============

let dataSourceModifiedDebounceTimer: ReturnType<typeof setTimeout> | null = null;

function dispatchDataSourceModified(toolName: string) {
  if (toolName !== 'table') return;
  if (dataSourceModifiedDebounceTimer) clearTimeout(dataSourceModifiedDebounceTimer);
  dataSourceModifiedDebounceTimer = setTimeout(() => {
    window.dispatchEvent(new CustomEvent('dataSourceModified', {
      detail: { timestamp: Date.now() }
    }));
    dataSourceModifiedDebounceTimer = null;
  }, 500);
}

// ============== INTERFACE MODIFICATION EVENT ==============

let interfaceModifiedDebounceTimer: ReturnType<typeof setTimeout> | null = null;

function dispatchInterfaceModified(toolName: string) {
  if (toolName !== 'interface') return;
  if (interfaceModifiedDebounceTimer) clearTimeout(interfaceModifiedDebounceTimer);
  interfaceModifiedDebounceTimer = setTimeout(() => {
    window.dispatchEvent(new CustomEvent('interfaceModified', {
      detail: { timestamp: Date.now() }
    }));
    interfaceModifiedDebounceTimer = null;
  }, 500);
}

// ============== WEB SEARCH MODIFICATION EVENT ==============
//
// Historically used to refresh the (now retired) WebSearchPanelContent. Still
// fired for the `web_search` tool because `agent_browse` is a sub-action of
// the same tool and its inline cards (AgentBrowseVisualizeCard /
// AgentBrowsePanelContent) listen for `webSearchModified` to re-fetch their
// Interface row when a step screenshot completes.

let webSearchModifiedDebounceTimer: ReturnType<typeof setTimeout> | null = null;

function dispatchWebSearchModified(toolName: string) {
  if (toolName !== 'web_search') return;
  if (webSearchModifiedDebounceTimer) clearTimeout(webSearchModifiedDebounceTimer);
  webSearchModifiedDebounceTimer = setTimeout(() => {
    window.dispatchEvent(new CustomEvent('webSearchModified', {
      detail: { timestamp: Date.now() }
    }));
    webSearchModifiedDebounceTimer = null;
  }, 500);
}

// ============== SIDE PANEL AUTO-OPEN EVENT ==============

// Visualizations queued during the current debounce window, keyed by resource.
// The previous impl used a single timer that kept ONLY the last visualization -
// so when the agent opened several apps in one burst, every tab but the last
// silently vanished (and a same-resource showcase marker could clobber the
// execute marker, leaving the panel open but empty). We now collect every
// DISTINCT resource and flush them ALL when the window closes - each opens its
// own tab - while `enqueueAutoOpen` keeps the best marker per resource.
// Pure queue logic lives in ./sidePanelAutoOpen (unit-tested).
const sidePanelAutoOpenPending = new Map<string, AutoOpenVisualization>();
let sidePanelAutoOpenDebounceTimer: ReturnType<typeof setTimeout> | null = null;

function dispatchSidePanelAutoOpen(visualization: AutoOpenVisualization) {
  if (!AUTO_OPEN_TYPES.includes(visualization.type)) return;
  enqueueAutoOpen(sidePanelAutoOpenPending, visualization);
  if (sidePanelAutoOpenDebounceTimer) clearTimeout(sidePanelAutoOpenDebounceTimer);
  sidePanelAutoOpenDebounceTimer = setTimeout(() => {
    sidePanelAutoOpenDebounceTimer = null;
    flushAutoOpen(sidePanelAutoOpenPending, (v) => {
      window.dispatchEvent(new CustomEvent('sidePanelAutoOpen', {
        detail: {
          type: v.type,
          id: v.id,
          title: v.title,
          runId: v.runId,
          liveCoords: v.liveCoords,
        },
      }));
    });
  }, 300);
}

// ============== TYPES ==============

// Simplified: 4 statuses
// - 'streaming': active (check content for loading vs content display)
// - 'completed': done, content visible until next message
// - 'stopped': user manually stopped the stream
// - 'error': error occurred
export type StreamingStatus = 'streaming' | 'completed' | 'stopped' | 'error';

export interface StreamError {
  message: string;
  code?: string;
  retryable: boolean;
}

export interface ToolVisualization {
  type: 'datasource' | 'table' | 'interface' | 'workflow' | 'workflow_run' | 'credential' | 'application' | 'web_search' | 'agent' | 'agent_browse';
  id: string;
  title?: string;
  /** For workflow_run: the run ID */
  runId?: string;
  /** For workflow_run: the run index */
  runIndex?: number;
  /** For credential: the service icon slug (e.g., 'gmail', 'slack') */
  iconSlug?: string;
  /** For credential: the service name for display */
  serviceName?: string;
}

// Source-tool render metadata. `diff` (red/green unified-diff card) is emitted by
// repo edit/write/diff and the interface patch tool; `gitStatus` (status badges) by
// repo git_status. Computed backend-side (mcp/repo-tool.mjs) and carried in tool
// metadata - rendered by DiffView / GitStatusView.
export interface ToolDiffFile {
  path: string;
  oldPath?: string;
  status: 'modified' | 'added' | 'deleted' | 'renamed';
  language?: string;
  additions: number;
  deletions: number;
  unifiedDiff: string;
  truncated?: boolean;
}
export interface ToolDiffData {
  files: ToolDiffFile[];
  title?: string;
}
export interface GitStatusFile {
  path: string;
  status: string; // porcelain XY code (M/A/D/R/??/…)
}
export interface GitStatusData {
  branch?: string;
  ahead?: number;
  behind?: number;
  files: GitStatusFile[];
}

export interface ToolActivity {
  id: string;
  toolName: string;
  toolId: string;
  arguments?: string;
  thinkingTitle?: string;
  thinkingMessage?: string;
  // 'interrupted' = a tool_call that was emitted by the LLM but never completed
  // (stop / error / budget exhaustion happened before the tool ran). Distinct
  // from 'error' (the tool ran and failed). Persisted by ConversationAgentService
  // when no tool_result is matched to the tool_call entry.
  status: 'pending' | 'success' | 'error' | 'interrupted';
  result?: string;
  resultId?: string;
  durationMs?: number;
  error?: string;
  visualization?: ToolVisualization;
  timestamp: number;
  // For catalog and workflow: display info from result metadata
  displayToolName?: string; // e.g., "gmail_send_email" instead of "catalog"
  iconSlug?: string; // e.g., "gmail" for /icons/services/gmail.svg
  label?: string; // Step label (for workflow add_node, e.g., "Send Email")
  // For tasks tool: inline task data
  tasksData?: {
    tasks: Array<{ id: number; description: string; status: string; result?: string }>;
    focusedTaskId?: number;
    completedCount: number;
    totalCount: number;
  };
  // For web_search(action='agent_browse'): live-view bootstrap pushed by
  // BrowserSessionLifecycleService as soon as the runner captures the
  // upstream Chromium DevTools URL - BEFORE the (blocking) tool call
  // returns. Lets AgentBrowseVisualizeCard render the card and open the
  // CDP WS panel mid-execution. When the final tool result lands later
  // with the [visualize:agent_browse:{interfaceId}] marker, the card
  // upgrades to the Interface-backed view.
  agentBrowseSession?: {
    sessionId: string;
    cdpToken: string;
    cdpWsUrl: string;
    currentUrl: string;
    runId: string;
    nodeId: string;
  };
  // Flag indicating this tool requires user credentials (frontend checks dynamically)
  credentialRequired?: boolean;
  // For request_credential tool: services that were requested
  // Note: Actual approval status is stored on Conversation.approvedServices (source of truth)
  serviceApproval?: {
    services: ServiceApprovalInfo[];
    reason?: string;
  };
  // For repo edit/write/diff + interface patch: red/green unified-diff card
  diff?: ToolDiffData;
  // For repo git_status: branch + changed-file status badges
  gitStatus?: GitStatusData;
  // For sub-agent calls: nested activity from the sub-agent
  subAgent?: { name: string; avatarUrl?: string; agentId: string };
  subActivities?: ToolActivity[];
  subAgentStatus?: 'running' | 'completed' | 'error';
  subAgentContent?: string;
  subAgentThinking?: string;
}

// Service approval request info (when agent needs access to external services)
export interface ServiceApprovalInfo {
  serviceType: string;   // e.g., "gmail", "slack"
  serviceName: string;   // e.g., "Gmail", "Slack"
  iconSlug: string;      // for icon display
  toolName?: string;     // e.g., "List Messages"
  toolId?: string;       // Tool UUID
  description?: string;  // Why needed
}

export interface PendingServiceApproval {
  services: ServiceApprovalInfo[];
  reason?: string;
  needsAttention?: boolean;
  timestamp: number;
}

// Tool-authorization request info (when the agent calls a sensitive action gated by
// ToolAuthorizationGuard, e.g. application:acquire). Distinct from service/credential approval.
export interface PendingToolAuthorization {
  rule: string;          // canonical "tool:action", e.g. "application:acquire"
  toolName?: string;     // gated facade tool, e.g. "application"
  action?: string;       // gated action, e.g. "acquire"
  toolCallId?: string;   // LLM tool-call id (correlation)
  argsSummary?: string;  // short human-readable summary of the call arguments
  applicationId?: string; // publication id - only for application:acquire (opens install modal)
  timestamp: number;
}

export interface SingleStreamState {
  status: StreamingStatus;
  streamId: string | null;
  content: string;
  error: StreamError | null;
  toolActivities: ToolActivity[];
  // Lists (not single) - the agent raises approval/authorization cards asynchronously
  // without pausing the run, so several can be pending in parallel during one turn.
  // Deduped by their canonical key (see serviceApprovalKey / toolAuthorizationKey).
  pendingServiceApprovals: PendingServiceApproval[];
  pendingToolAuthorizations: PendingToolAuthorization[];
  // Timestamp when pending_action_cancelled was last received (for detecting when to clear conversation.pendingAction)
  lastPendingActionCancelledAt?: number;
}

/**
 * Canonical key for a service-approval card - mirrors the backend
 * {@code PendingActionService.pendingActionKey} so the live
 * and persisted views dedup identically and a per-card DB clear can target the right row.
 */
export function serviceApprovalKey(_services: ServiceApprovalInfo[], needsAttention = false): string {
  return needsAttention ? 'svc:attention' : 'svc:connect';
}

/**
 * Canonical key for a tool-authorization card. Identity is (rule, toolCallId):
 * the agent can raise MULTIPLE cards for the SAME rule in one conversation (e.g.
 * two sequential {@code workflow:execute}), each a distinct LLM tool call. Keying
 * by rule alone collapsed them - approving/dismissing the first suppressed every
 * later same-rule card (the 2nd card never rendered). The toolCallId disambiguates
 * them. Falls back to rule-only when no toolCallId is present (legacy/persisted
 * rows), preserving the previous behaviour for that case.
 */
export function toolAuthorizationKey(rule: string, toolCallId?: string): string {
  return toolCallId ? 'auth:' + rule + '#' + toolCallId : 'auth:' + rule;
}

function serviceInfoKey(service: ServiceApprovalInfo): string {
  return (service.serviceType || service.iconSlug || service.serviceName || '').trim().toLowerCase();
}

function mergeApprovalReason(left?: string, right?: string): string | undefined {
  const parts = [left, right]
    .map(value => value?.trim())
    .filter((value): value is string => !!value);
  return Array.from(new Set(parts)).join('\n') || undefined;
}

export function mergePendingServiceApprovals(
  existing: PendingServiceApproval,
  incoming: PendingServiceApproval,
): PendingServiceApproval {
  const services = new Map<string, ServiceApprovalInfo>();
  for (const service of existing.services) {
    services.set(serviceInfoKey(service), service);
  }
  for (const service of incoming.services) {
    const key = serviceInfoKey(service);
    const current = services.get(key);
    services.set(key, current ? { ...current, ...service } : service);
  }
  return {
    services: Array.from(services.values()),
    reason: mergeApprovalReason(existing.reason, incoming.reason),
    needsAttention: existing.needsAttention || incoming.needsAttention,
    timestamp: Math.min(existing.timestamp, incoming.timestamp),
  };
}

// Multi-stream state: Map of conversationId -> stream state
export interface StreamingState {
  streams: Map<string, SingleStreamState>;
  // Server-reported active streams (fetched from backend on page load)
  serverActiveStreams: Set<string>;
}

// Legacy compatibility - expose single stream state for a conversation
// Uses extended status type to include 'idle' for "no active stream" case
export interface LegacyStreamingState {
  status: StreamingStatus | 'idle';
  conversationId: string | null;
  streamId: string | null;
  content: string;
  error: StreamError | null;
}

export interface SendMessageParams {
  message: string;
  model: string;
  provider?: string;
  conversationId?: string | null;
  history?: Array<{ role: string; content: string }>;
  attachments?: Array<{ storageId: string; type: string; fileName: string; mimeType: string }>;
  agentId?: string;
  defaultSkillIds?: string[];
  chatConfig?: Record<string, unknown>;
  source?: string;
  taskId?: string;
  /** Per-conversation reasoning-effort override for CLI/bridge models. */
  reasoningEffort?: string;
  /**
   * True when this send is a RESUME after the user resolved ONE parallel approval/authorization
   * card - the backend then skips its start-of-turn wipe so the OTHER pending cards survive.
   */
  keepPendingActions?: boolean;
}

export interface StreamingCallbacks {
  onConversationCreated?: (conversationId: string, title?: string) => void;
  onStreamComplete?: (conversationId: string, content: string, model: string) => void;
  onTitleUpdated?: (conversationId: string, title: string) => void;
  // Fires when the backend persists a new cold-zone summary mid-stream. The
  // event is best-effort (may arrive after onStreamComplete on @Async delay);
  // Conversation.compactionMarker is the persistent source of truth.
  onCompactionDone?: (
    conversationId: string,
    turnsCoveredCount: number,
    summarizerModel: string,
    generatedAt: string,
  ) => void;
  onError?: (error: StreamError) => void;
}

interface StreamingContextType {
  // Get state for a specific conversation
  getStreamState: (conversationId: string) => SingleStreamState | null;

  // Legacy state (for backward compatibility - returns first active stream)
  state: LegacyStreamingState;

  // Actions
  sendMessage: (params: SendMessageParams, callbacks?: StreamingCallbacks) => Promise<string | null>;
  stopStream: (conversationId: string) => Promise<void>;
  checkAndReconnect: (conversationId: string, callbacks?: StreamingCallbacks) => Promise<boolean>;
  clearStream: (conversationId: string) => void;
  // key (optional) clears ONE card (canonical key); omit to clear them all.
  clearServiceApproval: (conversationId: string, key?: string) => void;
  clearToolAuthorization: (conversationId: string, key?: string) => void;

  // Derived
  isStreaming: boolean; // Any stream is active
  isStreamingConversation: (conversationId: string) => boolean;
  serverStreamsLoaded: boolean; // True after initial fetch of active streams from server
  getStreamContent: (conversationId: string) => string;
  getToolActivities: (conversationId: string) => ToolActivity[];
  getPendingServiceApprovals: (conversationId: string) => PendingServiceApproval[];
  getPendingToolAuthorizations: (conversationId: string) => PendingToolAuthorization[];
}

// ============== REDUCER ==============

// Simplified actions - merged START_CONNECTING + STREAM_STARTED into START_STREAM
type StreamingAction =
  | { type: 'START_STREAM'; conversationId: string; streamId?: string; keepPendingActions?: boolean }
  | { type: 'CONTENT_RECEIVED'; conversationId: string; content: string }
  | { type: 'APPEND_CONTENT'; conversationId: string; chunk: string }
  | { type: 'THINKING_CHUNK'; conversationId: string; chunk: string }
  | { type: 'THINKING_SECTION'; conversationId: string; title: string; content: string }
  | { type: 'TOOL_CALL'; conversationId: string; toolName: string; toolId: string; arguments?: string; thinkingMessage?: string }
  | { type: 'TOOL_RESULT'; conversationId: string; toolId: string; success: boolean; result?: string; resultId?: string; durationMs?: number; error?: string; visualization?: ToolVisualization; iconSlug?: string; displayToolName?: string; label?: string; tasksData?: ToolActivity['tasksData']; credentialRequired?: boolean; serviceApproval?: ToolActivity['serviceApproval']; diff?: ToolActivity['diff']; gitStatus?: ToolActivity['gitStatus'] }
  | { type: 'AGENT_BROWSE_STEP'; conversationId: string; toolId: string; sessionId: string; cdpToken: string; cdpWsUrl: string; currentUrl: string; runId: string; nodeId: string; stepIndex: number }
  | { type: 'SERVICE_APPROVAL_REQUIRED'; conversationId: string; services: ServiceApprovalInfo[]; reason?: string; needsAttention?: boolean }
  | { type: 'CLEAR_SERVICE_APPROVAL'; conversationId: string; key?: string }
  | { type: 'TOOL_AUTHORIZATION_REQUIRED'; conversationId: string; rule: string; toolName?: string; action?: string; toolCallId?: string; argsSummary?: string; applicationId?: string }
  | { type: 'CLEAR_TOOL_AUTHORIZATION'; conversationId: string; key?: string }
  | { type: 'COMPLETED'; conversationId: string; content?: string; streamId?: string }
  | { type: 'STOPPED'; conversationId: string; streamId?: string }
  | { type: 'PENDING_ACTION_CANCELLED'; conversationId: string }
  | { type: 'ERROR'; conversationId: string; error: StreamError; streamId?: string }
  | { type: 'CLEAR'; conversationId: string }
  | { type: 'CLEAR_ALL' }
  | { type: 'SET_SERVER_ACTIVE_STREAMS'; conversationIds: string[] }
  | { type: 'REMOVE_SERVER_ACTIVE_STREAM'; conversationId: string }
  | { type: 'SUB_AGENT_STARTED'; conversationId: string; subAgent: { name: string; avatarUrl?: string; agentId: string } }
  | { type: 'SUB_AGENT_TOOL_CALL'; conversationId: string; subAgent: { name: string; avatarUrl?: string; agentId: string }; toolName: string; toolId: string }
  | { type: 'SUB_AGENT_TOOL_RESULT'; conversationId: string; subAgent: { name: string; avatarUrl?: string; agentId: string }; toolId: string; toolName?: string; success: boolean; durationMs?: number }
  | { type: 'SUB_AGENT_COMPLETED'; conversationId: string; subAgent: { name: string; avatarUrl?: string; agentId: string }; success: boolean }
  | { type: 'SUB_AGENT_CONTENT'; conversationId: string; subAgent: { name: string; avatarUrl?: string; agentId: string }; content: string }
  | { type: 'SUB_AGENT_THINKING'; conversationId: string; subAgent: { name: string; avatarUrl?: string; agentId: string }; thinking: string };

// No more 'idle' - streams are either in the Map (with a status) or not

const initialState: StreamingState = {
  streams: new Map(),
  serverActiveStreams: new Set(),
};

/**
 * A terminal action (COMPLETED / STOPPED / ERROR) is STALE when it carries the streamId of a
 * stream that is no longer the one tracked for the conversation - e.g. a checkAndReconnect that
 * resolved the PREVIOUS stream's terminal state after a fresh send already put a NEWER stream
 * live, or a late backend terminal replayed by a snapshot. Applying it would flip the live
 * entry to a terminal status while its events keep arriving: the Stop button disappears
 * mid-stream (chunks still render - APPEND_CONTENT never re-raises the status). Both ids must
 * be known to declare staleness; when either side is unknown the action applies (legacy paths).
 * Exported for unit tests only - the reachable interleavings are cut earlier by the refs
 * generation guard; this is the reducer-level safety net for any future dispatch path.
 */
export function isStaleTerminal(
  currentStream: SingleStreamState | undefined,
  actionStreamId: string | undefined
): boolean {
  return Boolean(
    actionStreamId &&
    currentStream?.streamId &&
    actionStreamId !== currentStream.streamId
  );
}

function streamingReducer(state: StreamingState, action: StreamingAction): StreamingState {
  const newStreams = new Map(state.streams);

  if (action.type === 'CLEAR_ALL') {
    return { streams: new Map(), serverActiveStreams: new Set() };
  }

  if (action.type === 'SET_SERVER_ACTIVE_STREAMS') {
    return {
      ...state,
      serverActiveStreams: new Set(action.conversationIds),
    };
  }

  if (action.type === 'REMOVE_SERVER_ACTIVE_STREAM') {
    const newServerStreams = new Set(state.serverActiveStreams);
    newServerStreams.delete(action.conversationId);
    return {
      ...state,
      serverActiveStreams: newServerStreams,
    };
  }

  if (!('conversationId' in action)) {
    return state;
  }

  const conversationId = action.conversationId;
  const currentStream = state.streams.get(conversationId);

  streamLogger.debug(`Reducer: ${action.type}`, {
    conversationId,
    prevStatus: currentStream?.status || 'none',
  });

  switch (action.type) {
    case 'START_STREAM': {
      // Merged START_CONNECTING + STREAM_STARTED. On a RESUME (keepPendingActions), PRESERVE
      // the still-pending sibling cards in the live state - the user resolved one card and the
      // others must stay visible (they would otherwise vanish until a DB refetch). A fresh
      // message resets to [] (the cards were dismissed).
      newStreams.set(conversationId, {
        status: 'streaming',
        streamId: action.streamId || null,
        content: '',
        error: null,
        toolActivities: [],
        pendingServiceApprovals: action.keepPendingActions ? (currentStream?.pendingServiceApprovals ?? []) : [],
        pendingToolAuthorizations: action.keepPendingActions ? (currentStream?.pendingToolAuthorizations ?? []) : [],
      });
      // Remove from server-reported streams since we're now tracking locally
      const newServerStreams = new Set(state.serverActiveStreams);
      newServerStreams.delete(conversationId);
      return { streams: newStreams, serverActiveStreams: newServerStreams };
    }

    case 'CONTENT_RECEIVED': {
      if (!currentStream) return state;
      newStreams.set(conversationId, {
        ...currentStream,
        content: action.content,
        toolActivities: markPendingToolsAsSuccess(currentStream.toolActivities),
      });
      break;
    }

    case 'APPEND_CONTENT': {
      if (!currentStream) return state;
      newStreams.set(conversationId, {
        ...currentStream,
        content: currentStream.content + action.chunk,
        toolActivities: markPendingToolsAsSuccess(currentStream.toolActivities),
      });
      break;
    }

    case 'THINKING_CHUNK': {
      if (!currentStream) return state;

      // Find the LAST thinking activity that is still pending (to accumulate into)
      // This preserves chronological order: thinking → tool → thinking → tool
      const activities = currentStream.toolActivities;
      const lastActivity = activities.length > 0 ? activities[activities.length - 1] : null;

      let updatedActivities: ToolActivity[];

      // If the last activity is a pending thinking, accumulate into it
      if (lastActivity && lastActivity.toolName === '_thinking' && lastActivity.status === 'pending') {
        updatedActivities = [...activities];
        updatedActivities[activities.length - 1] = {
          ...lastActivity,
          thinkingMessage: (lastActivity.thinkingMessage || '') + action.chunk,
        };
      } else {
        // Create a NEW thinking activity at the END (chronological order)
        // Use unique ID to allow multiple thinking sessions
        const uniqueId = `_thinking_${Date.now()}_${++uniqueIdCounter}`;
        const thinkingActivity: ToolActivity = {
          id: uniqueId,
          toolName: '_thinking',
          toolId: uniqueId,
          status: 'pending',
          thinkingMessage: action.chunk,
          timestamp: Date.now(),
        };
        updatedActivities = [...activities, thinkingActivity];
      }

      newStreams.set(conversationId, {
        ...currentStream,
        toolActivities: updatedActivities,
      });
      break;
    }

    case 'THINKING_SECTION': {
      // Add a single thinking section as a new activity (like a tool call)
      // Also remove accumulated raw thinking (without thinkingTitle)
      if (!currentStream) return state;

      // Filter out raw _thinking activities (those without thinkingTitle)
      const filteredActivities = currentStream.toolActivities.filter(
        a => !(a.toolName === '_thinking' && !a.thinkingTitle)
      );

      const sectionUniqueId = `_thinking_section_${Date.now()}_${++uniqueIdCounter}`;
      const sectionActivity: ToolActivity = {
        id: sectionUniqueId,
        toolName: '_thinking',
        toolId: sectionUniqueId,
        status: 'success' as const,
        thinkingTitle: action.title,
        thinkingMessage: action.content,
        timestamp: Date.now(),
      };

      newStreams.set(conversationId, {
        ...currentStream,
        toolActivities: [...filteredActivities, sectionActivity],
      });
      break;
    }

    case 'TOOL_CALL': {
      if (!currentStream) {
        streamLogger.warn('TOOL_CALL ignored - no currentStream', conversationId);
        return state;
      }

      // Check for duplicate tool call (prevent duplicates from network retries)
      const isDuplicate = currentStream.toolActivities.some(
        activity => activity.toolId === action.toolId
      );
      if (isDuplicate) {
        streamLogger.warn('TOOL_CALL ignored - duplicate toolId', action.toolId);
        return state;
      }

      streamLogger.debug('TOOL_CALL processing', { conversationId, toolName: action.toolName, toolId: action.toolId, currentActivities: currentStream.toolActivities.length });
      // Don't mark previous pending tools as success here - with bridge/Claude Code,
      // multiple tool_call events arrive in parallel before any tool_result.
      // Tools are correctly marked success/error only when their TOOL_RESULT arrives.
      const newActivity: ToolActivity = {
        id: action.toolId,
        toolName: action.toolName,
        toolId: action.toolId,
        arguments: action.arguments,
        thinkingMessage: action.thinkingMessage,
        status: 'pending',
        timestamp: Date.now(),
      };
      newStreams.set(conversationId, {
        ...currentStream,
        toolActivities: [...currentStream.toolActivities, newActivity],
      });
      break;
    }

    case 'TOOL_RESULT': {
      if (!currentStream) {
        streamLogger.warn('TOOL_RESULT ignored - no currentStream', conversationId);
        return state;
      }
      const updatedActivities = currentStream.toolActivities.map(activity =>
        activity.toolId === action.toolId
          ? {
              ...activity,
              status: action.success ? 'success' : 'error',
              result: action.result,
              resultId: action.resultId,
              durationMs: action.durationMs,
              error: action.error,
              visualization: action.visualization,
              iconSlug: action.iconSlug,
              displayToolName: action.displayToolName,
              label: action.label,
              tasksData: action.tasksData,
              credentialRequired: action.credentialRequired,
              serviceApproval: action.serviceApproval,
              diff: action.diff,
              gitStatus: action.gitStatus,
            } as ToolActivity
          : activity
      );
      newStreams.set(conversationId, {
        ...currentStream,
        toolActivities: updatedActivities,
      });
      break;
    }

    case 'AGENT_BROWSE_STEP': {
      if (!currentStream) return state;
      const browseActivities = currentStream.toolActivities.map(activity =>
        activity.toolId === action.toolId
          ? {
              ...activity,
              agentBrowseSession: {
                sessionId: action.sessionId,
                cdpToken: action.cdpToken,
                cdpWsUrl: action.cdpWsUrl,
                currentUrl: action.currentUrl,
                runId: action.runId,
                nodeId: action.nodeId,
              },
            } as ToolActivity
          : activity
      );
      newStreams.set(conversationId, {
        ...currentStream,
        toolActivities: browseActivities,
      });
      break;
    }

    // ── Sub-agent forwarded events: attach to the pending "agent" tool call ──

    case 'SUB_AGENT_STARTED': {
      if (!currentStream) return state;
      const activities = currentStream.toolActivities.map(a => {
        if (a.toolName === 'agent' && a.status === 'pending' && !a.subAgent) {
          return { ...a, subAgent: action.subAgent, subActivities: [] as ToolActivity[], subAgentStatus: 'running' as const };
        }
        return a;
      });
      newStreams.set(conversationId, { ...currentStream, toolActivities: activities });
      break;
    }

    case 'SUB_AGENT_TOOL_CALL': {
      if (!currentStream) return state;
      const activities = currentStream.toolActivities.map(a => {
        if (a.subAgent?.agentId === action.subAgent.agentId && a.subAgentStatus === 'running') {
          const subActivity: ToolActivity = {
            id: action.toolId,
            toolName: action.toolName,
            toolId: action.toolId,
            status: 'pending',
            timestamp: Date.now(),
          };
          return { ...a, subActivities: [...(a.subActivities || []), subActivity] };
        }
        return a;
      });
      newStreams.set(conversationId, { ...currentStream, toolActivities: activities });
      break;
    }

    case 'SUB_AGENT_TOOL_RESULT': {
      if (!currentStream) return state;
      const activities = currentStream.toolActivities.map(a => {
        if (a.subAgent?.agentId === action.subAgent.agentId && a.subActivities) {
          const updatedSubs = a.subActivities.map(sub =>
            sub.toolId === action.toolId
              ? { ...sub, status: (action.success ? 'success' : 'error') as ToolActivity['status'], durationMs: action.durationMs }
              : sub
          );
          return { ...a, subActivities: updatedSubs };
        }
        return a;
      });
      newStreams.set(conversationId, { ...currentStream, toolActivities: activities });
      break;
    }

    case 'SUB_AGENT_COMPLETED': {
      if (!currentStream) return state;
      const activities = currentStream.toolActivities.map(a => {
        if (a.subAgent?.agentId === action.subAgent.agentId) {
          return { ...a, subAgentStatus: (action.success ? 'completed' : 'error') as ToolActivity['subAgentStatus'] };
        }
        return a;
      });
      newStreams.set(conversationId, { ...currentStream, toolActivities: activities });
      break;
    }

    case 'SUB_AGENT_CONTENT': {
      if (!currentStream) return state;
      const activities = currentStream.toolActivities.map(a => {
        if (a.subAgent?.agentId === action.subAgent.agentId && a.subAgentStatus === 'running') {
          return { ...a, subAgentContent: (a.subAgentContent || '') + action.content };
        }
        return a;
      });
      newStreams.set(conversationId, { ...currentStream, toolActivities: activities });
      break;
    }

    case 'SUB_AGENT_THINKING': {
      if (!currentStream) return state;
      const activities = currentStream.toolActivities.map(a => {
        if (a.subAgent?.agentId === action.subAgent.agentId && a.subAgentStatus === 'running') {
          return { ...a, subAgentThinking: (a.subAgentThinking || '') + action.thinking };
        }
        return a;
      });
      newStreams.set(conversationId, { ...currentStream, toolActivities: activities });
      break;
    }

    case 'SERVICE_APPROVAL_REQUIRED': {
      if (!currentStream) return state;
      // Merge by card mode so sequential credential requests become one wizard:
      // normal connects in one neutral card, forced reconnects in one warning card.
      const incoming: PendingServiceApproval = {
        services: action.services,
        reason: action.reason,
        needsAttention: action.needsAttention,
        timestamp: Date.now(),
      };
      const incomingKey = serviceApprovalKey(incoming.services, incoming.needsAttention);
      const approvals = [...currentStream.pendingServiceApprovals];
      const existingIndex = approvals.findIndex(
        a => serviceApprovalKey(a.services, a.needsAttention) === incomingKey
      );
      if (existingIndex >= 0) {
        approvals[existingIndex] = mergePendingServiceApprovals(approvals[existingIndex], incoming);
      } else {
        approvals.push(incoming);
      }
      newStreams.set(conversationId, {
        ...currentStream,
        pendingServiceApprovals: approvals,
      });
      break;
    }

    case 'CLEAR_SERVICE_APPROVAL': {
      if (!currentStream) return state;
      // With a key, drop only that card; without, clear them all.
      newStreams.set(conversationId, {
        ...currentStream,
        pendingServiceApprovals: action.key
          ? currentStream.pendingServiceApprovals.filter(
              a => serviceApprovalKey(a.services, a.needsAttention) !== action.key
            )
          : [],
      });
      break;
    }

    case 'TOOL_AUTHORIZATION_REQUIRED': {
      if (!currentStream) return state;
      // Identity is (rule, toolCallId): two distinct tool calls of the SAME rule
      // (e.g. a second workflow:execute in the same conversation) must BOTH be
      // tracked. Dedup only on an exact (rule, toolCallId) match so a genuine
      // retransmit is ignored but a new call is kept.
      const exists = currentStream.pendingToolAuthorizations.some(
        a => a.rule === action.rule && a.toolCallId === action.toolCallId);
      if (exists) return state;
      newStreams.set(conversationId, {
        ...currentStream,
        pendingToolAuthorizations: [
          ...currentStream.pendingToolAuthorizations,
          {
            rule: action.rule,
            toolName: action.toolName,
            action: action.action,
            toolCallId: action.toolCallId,
            argsSummary: action.argsSummary,
            applicationId: action.applicationId,
            timestamp: Date.now(),
          },
        ],
      });
      break;
    }

    case 'CLEAR_TOOL_AUTHORIZATION': {
      if (!currentStream) return state;
      newStreams.set(conversationId, {
        ...currentStream,
        pendingToolAuthorizations: action.key
          ? currentStream.pendingToolAuthorizations.filter(
              a => toolAuthorizationKey(a.rule, a.toolCallId) !== action.key
            )
          : [],
      });
      break;
    }

    case 'COMPLETED': {
      if (!currentStream) return state;
      if (isStaleTerminal(currentStream, action.streamId)) {
        streamLogger.warn('COMPLETED ignored - stale streamId', {
          conversationId, actionStreamId: action.streamId, liveStreamId: currentStream.streamId,
        });
        return state;
      }
      // Keep the stream with 'completed' status instead of deleting
      // This allows the UI to keep showing the content until it's persisted to messages[]
      // The stream will be cleaned up when a new message is sent or on CLEAR action
      //
      // Reconcile the live bubble with the AUTHORITATIVE final content carried by
      // the `done` event (`action.content` = backend `fullContent`). The per-token
      // chunks accumulated during the run can OMIT the trailing `[visualize:...]`
      // marker line: on the bridge path the Claude adapter does not re-publish an
      // assistant *snapshot* text block once deltas were seen earlier in the run
      // (claude-adapter.mjs streamedContentViaDeltas guard), yet that marker IS in
      // `fullContent` and in the persisted message. Without this reconciliation the
      // inline visualize card (application / workflow run / datasource / interface /
      // agent / file / image) only appeared after a manual refresh re-fetched the
      // DB message. Replacing the bubble content with `fullContent` also makes
      // ChatCore's stream-vs-message dedup match exactly (both now carry the marker),
      // preventing a transient double render. Guarded: only override when a non-empty
      // authoritative content is supplied (terminal/reconnect paths omit it).
      const reconciledContent =
        action.content && action.content.length > 0 ? action.content : currentStream.content;
      newStreams.set(conversationId, {
        ...currentStream,
        status: 'completed',
        content: reconciledContent,
        toolActivities: markPendingToolsAsSuccess(currentStream.toolActivities),
      });
      break;
    }

    case 'STOPPED': {
      if (!currentStream) return state;
      if (isStaleTerminal(currentStream, action.streamId)) {
        streamLogger.warn('STOPPED ignored - stale streamId', {
          conversationId, actionStreamId: action.streamId, liveStreamId: currentStream.streamId,
        });
        return state;
      }
      // Keep pending tools as-is (not completed) to show they were interrupted
      // Exception: mark _thinking as success so it displays properly
      const toolsOnStop = markThinkingAsSuccess(currentStream.toolActivities);
      // Add _system_stop marker tool so ActivityFeed shows "Stopped" indicator
      const toolsWithStop: ToolActivity[] = [
        ...toolsOnStop,
        {
          id: `system-stop-${Date.now()}`,
          toolName: '_system_stop',
          toolId: `system-stop-${Date.now()}`,
          status: 'success',
          timestamp: Date.now(),
        },
      ];
      newStreams.set(conversationId, {
        ...currentStream,
        status: 'stopped',
        toolActivities: toolsWithStop,
      });
      break;
    }

    case 'PENDING_ACTION_CANCELLED': {
      // Clear pending approval state when user sends new message
      if (!currentStream) return state;

      // Clear pending service approval and mark when cancelled
      // IMPORTANT: Keep current status (don't force to 'completed')
      // A new stream may already be in progress when this event arrives
      newStreams.set(conversationId, {
        ...currentStream,
        pendingServiceApprovals: [],
        pendingToolAuthorizations: [],
        lastPendingActionCancelledAt: Date.now(), // Track when cancelled
      });

      break;
    }

    case 'ERROR': {
      if (!currentStream) return state;
      if (isStaleTerminal(currentStream, action.streamId)) {
        streamLogger.warn('ERROR ignored - stale streamId', {
          conversationId, actionStreamId: action.streamId, liveStreamId: currentStream.streamId,
        });
        return state;
      }
      // Add _system_error marker tool so ActivityFeed shows "Error" indicator
      const toolsWithError: ToolActivity[] = [
        ...currentStream.toolActivities,
        {
          id: `system-error-${Date.now()}`,
          toolName: '_system_error',
          toolId: `system-error-${Date.now()}`,
          status: 'error',
          error: action.error?.message,
          timestamp: Date.now(),
        },
      ];
      newStreams.set(conversationId, {
        ...currentStream,
        status: 'error',
        error: action.error,
        toolActivities: toolsWithError,
      });
      break;
    }

    case 'CLEAR':
      newStreams.delete(conversationId);
      break;

    default:
      return state;
  }

  return { ...state, streams: newStreams };
}

// ============== CONTEXT ==============

const StreamingContext = createContext<StreamingContextType | null>(null);

// ============== PROVIDER ==============

// Per-stream data stored in refs (not in React state for performance)
interface StreamRefs {
  wsUnsubscribe: (() => void) | null;
  callbacks: StreamingCallbacks;
  content: string;
  model: string;
  streamId: string | null;
  // Monotonic ownership token for this conversation's refs. sendMessage bumps it on
  // every send (sends are authoritative - they CREATE streams); checkAndReconnect
  // bumps it on entry and re-validates it after each await, aborting if a newer
  // operation took over. Prevents a slow reconnect from mutating the refs of - or
  // dispatching a stale terminal onto - a stream that started while it was in flight.
  generation: number;
}

export function StreamingProvider({ children }: { children: ReactNode }) {
  const [state, dispatch] = useReducer(streamingReducer, initialState);
  const { isReady, isAuthenticated } = useAuthGuard();
  const [serverStreamsLoaded, setServerStreamsLoaded] = useState(false);

  // Map of conversationId -> stream refs
  const streamRefsMap = useRef<Map<string, StreamRefs>>(new Map());
  const isMountedRef = useRef(true);

  // Track when streams were completed (for cleanup)
  const streamCompletionTimestamps = useRef<Map<string, number>>(new Map());

  // Get or create refs for a conversation
  const getStreamRefs = useCallback((conversationId: string): StreamRefs => {
    let refs = streamRefsMap.current.get(conversationId);
    if (!refs) {
      refs = {
        wsUnsubscribe: null,
        callbacks: {},
        content: '',
        model: getEffectiveDefaultModel() ?? '',
        streamId: null,
        generation: 0,
      };
      streamRefsMap.current.set(conversationId, refs);
    }
    return refs;
  }, []);

  // Cleanup on unmount
  useEffect(() => {
    isMountedRef.current = true;
    return () => {
      isMountedRef.current = false;
      // Unsubscribe all WebSocket channels
      streamRefsMap.current.forEach((refs) => {
        if (refs.wsUnsubscribe) {
          refs.wsUnsubscribe();
        }
      });
    };
  }, []);

  // Periodic cleanup of completed streams (DRY: prevent memory leaks)
  useEffect(() => {
    const CLEANUP_INTERVAL = 60000; // Check every 60 seconds
    const RETENTION_TIME = 120000;  // Keep completed streams for 2 minutes

    const cleanupInterval = setInterval(() => {
      const now = Date.now();
      const conversationsToCleanup: string[] = [];

      // Find streams completed more than RETENTION_TIME ago
      streamCompletionTimestamps.current.forEach((completionTime, conversationId) => {
        if (now - completionTime > RETENTION_TIME) {
          conversationsToCleanup.push(conversationId);
        }
      });

      // Cleanup old completed streams
      if (conversationsToCleanup.length > 0) {
        conversationsToCleanup.forEach((convId) => {
          streamRefsMap.current.delete(convId);
          streamCompletionTimestamps.current.delete(convId);
        });
      }
    }, CLEANUP_INTERVAL);

    return () => clearInterval(cleanupInterval);
  }, []);

  // Track if we've already fetched server streams (prevent duplicate fetches)
  const hasFetchedServerStreamsRef = useRef(false);

  // Fetch active streams from server when auth is ready
  useEffect(() => {
    // Wait for auth to be ready
    if (!isReady || !isAuthenticated) {
      return;
    }

    // Only fetch once per session
    if (hasFetchedServerStreamsRef.current) {
      return;
    }

    hasFetchedServerStreamsRef.current = true;
    let didStart = false;

    const fetchActiveStreams = async () => {
      didStart = true;
      try {
        const activeConversationIds = await unifiedApiService.getActiveStreamingConversations();

        if (activeConversationIds.length > 0 && isMountedRef.current) {
          dispatch({ type: 'SET_SERVER_ACTIVE_STREAMS', conversationIds: activeConversationIds });
        }
      } catch {
        // Silently fail - active streams will be detected on reconnection attempts
      } finally {
        if (isMountedRef.current) {
          setServerStreamsLoaded(true);
        }
      }
    };

    const timer = setTimeout(fetchActiveStreams, ACTIVE_STREAMS_BOOT_DELAY_MS);
    return () => {
      clearTimeout(timer);
      if (!didStart) {
        hasFetchedServerStreamsRef.current = false;
      }
    };
  }, [isReady, isAuthenticated]);

  // ============== GET STREAM STATE ==============
  const getStreamState = useCallback((conversationId: string): SingleStreamState | null => {
    return state.streams.get(conversationId) || null;
  }, [state.streams]);

  // ============== WS EVENT HANDLER (shared by sendMessage and checkAndReconnect) ==============
  /**
   * Creates a WebSocket channel handler that dispatches events to the reducer.
   * Used for both new streams and reconnection.
   */
  const createWsEventHandler = useCallback((
    conversationId: string,
    callbacks: StreamingCallbacks | undefined,
    refs: StreamRefs
  ) => {
    // Pin this handler to the stream it was created for. The WebSocket channel is
    // keyed by CONVERSATION (`conversation:{id}`), so EVERY stream of a
    // conversation shares it. After a stop + resend in the SAME conversation, the
    // backend's late terminal event (stopped / done / error) for the PREVIOUS
    // stream is still delivered on this shared channel - and without this guard
    // the NEW stream's handler would consume it: it would dispatch STOPPED/
    // COMPLETED (flipping the live UI), fire onStreamComplete prematurely
    // (loadMessages then clobbers the in-flight response, which only reappears on
    // refresh), and tear down the new stream's own WebSocket. Every backend event
    // carries `streamId`; binding the handler to its stream's id lets us discard
    // cross-stream leakage. Events with NO streamId (sub-agent forwarded events)
    // and conversation-scoped events (title / compaction) are never stream-bound
    // and must always pass through.
    const boundStreamId = refs.streamId;

    return (rawPayload: unknown) => {
      if (!isMountedRef.current) return;

      const payload = rawPayload as Record<string, unknown>;
      const eventType = detectStreamEventType(payload);
      const mapped = mapV2EventToV1(payload, eventType, null);

      // Drop events that belong to a DIFFERENT stream of this same conversation
      // (the shared-channel cross-talk described above). `title` and
      // `compaction_done` are conversation-scoped (keyed by conversationId, not
      // stream-bound and non-destructive), so they bypass the filter; sub-agent
      // forwarded events carry no streamId and fall through naturally.
      const eventStreamId = mapped.streamId;
      if (
        boundStreamId &&
        eventStreamId &&
        eventStreamId !== boundStreamId &&
        mapped.type !== 'title' &&
        mapped.type !== 'compaction_done'
      ) {
        console.log(`[WS:StreamCtx] dropped cross-stream event type=${mapped.type} eventStream=${eventStreamId} bound=${boundStreamId} conv=${conversationId}`);
        return;
      }

      // Debug: trace all WS events for bridge streaming diagnosis
      console.log(`[WS:StreamCtx] event=${eventType} mapped=${mapped.type} conv=${conversationId}`, payload);

      switch (mapped.type) {
        case 'stream_id':
          // stream_started: we already have the conversationId from POST, just update streamId
          break;

        case 'content':
          if (mapped.content) {
            if (payload.replay === true) {
              // A snapshot replay (requestSnapshot on (re)subscribe) carries the FULL accumulated
              // content, not an incremental chunk - replace, don't append, so reconnect/snapshot
              // replays never duplicate the streamed text.
              refs.content = mapped.content;
              dispatch({ type: 'CONTENT_RECEIVED', conversationId, content: mapped.content });
            } else {
              refs.content += mapped.content;
              dispatch({ type: 'APPEND_CONTENT', conversationId, chunk: mapped.content });
            }
          }
          break;

        case 'thinking':
          if (mapped.thinking) {
            dispatch({ type: 'THINKING_CHUNK', conversationId, chunk: mapped.thinking });
          }
          break;

        case 'thinking_section':
          if (mapped.title !== undefined || mapped.content) {
            dispatch({
              type: 'THINKING_SECTION',
              conversationId,
              title: mapped.title || '',
              content: mapped.content || '',
            });
          }
          break;

        case 'tool_call': {
          let thinkingMessage: string | undefined;
          let rawArgs: string | undefined;
          if (mapped.arguments) {
            try {
              const args = typeof mapped.arguments === 'string'
                ? JSON.parse(mapped.arguments)
                : mapped.arguments;
              thinkingMessage = args.thinking;
              rawArgs = args.raw;
            } catch { /* ignore */ }
          }
          dispatch({
            type: 'TOOL_CALL',
            conversationId,
            toolName: mapped.toolName || 'unknown',
            toolId: mapped.toolId || `tool-${crypto.randomUUID()}`,
            arguments: rawArgs || (typeof mapped.arguments === 'string' ? mapped.arguments : JSON.stringify(mapped.arguments)),
            thinkingMessage,
          });
          break;
        }

        case 'tool_result': {
          dispatch({
            type: 'TOOL_RESULT',
            conversationId,
            toolId: mapped.toolId || '',
            success: mapped.success ?? true,
            result: typeof mapped.result === 'string' ? mapped.result : undefined,
            resultId: mapped.resultId,
            durationMs: mapped.durationMs,
            error: mapped.error,
            visualization: mapped.visualization,
            iconSlug: mapped.iconSlug,
            displayToolName: mapped.displayToolName,
            label: mapped.label,
            tasksData: mapped.tasksData,
            credentialRequired: mapped.credentialRequired,
            serviceApproval: mapped.serviceApproval,
            diff: mapped.diff,
            gitStatus: mapped.gitStatus,
          });

          if (mapped.success && mapped.toolName) {
            dispatchWorkflowPlanModified(mapped.toolName);
            dispatchDataSourceModified(mapped.toolName);
            dispatchInterfaceModified(mapped.toolName);
            dispatchWebSearchModified(mapped.toolName);
          }
          if (mapped.success && mapped.visualization) {
            // Skip post-completion auto-open for agent_browse: the live
            // tab opened mid-execution (via AGENT_BROWSE_STEP) is the
            // user's existing focus. Opening a SECOND tab here keyed by
            // interfaceId steals focus AND leaves the user with two
            // panels (live frozen + post-completion static). The chat
            // card already provides the entry-point if the user closed
            // the live tab and wants to re-open the post-completion view.
            if (mapped.visualization.type !== 'agent_browse') {
              dispatchSidePanelAutoOpen(mapped.visualization);
            }
          }
          break;
        }

        case 'agent_browse_step': {
          // Live-view bootstrap from BrowserSessionLifecycleService:
          // attaches CDP coords to the in-flight tool activity so
          // AgentBrowseVisualizeCard can render the live card and open
          // the WS panel BEFORE the blocking agent_browse call returns.
          dispatch({
            type: 'AGENT_BROWSE_STEP',
            conversationId,
            toolId: (payload.toolId as string) || '',
            sessionId: (payload.sessionId as string) || '',
            cdpToken: (payload.cdpToken as string) || '',
            cdpWsUrl: (payload.cdpWsUrl as string) || '',
            currentUrl: (payload.currentUrl as string) || '',
            runId: (payload.runId as string) || '',
            nodeId: (payload.nodeId as string) || '',
            stepIndex: (payload.stepIndex as number) || 0,
          });
          // Auto-open the right-side panel - same UX as workflow/web_search.
          // The panel's keepMounted flag (set in AppHeader) keeps the CDP
          // WS alive across tab switches.
          const sessionId = (payload.sessionId as string) || '';
          const cdpToken = (payload.cdpToken as string) || '';
          const cdpWsUrl = (payload.cdpWsUrl as string) || '';
          const runId = (payload.runId as string) || '';
          const nodeId = (payload.nodeId as string) || '';
          const currentUrl = (payload.currentUrl as string) || '';
          if (sessionId && cdpToken && cdpWsUrl && runId && nodeId) {
            // Tab id is keyed by toolId. NOTE: the post-completion
            // [visualize:agent_browse:{interfaceId}] marker (rendered
            // by AgentBrowseVisualizeCard) opens a DIFFERENT tab keyed
            // by interfaceId. The live tab is auto-closed when the WS
            // dies (BrowserLiveCdpPanel dispatches
            // `agentBrowseLiveTabEnded` on reconnect-exhausted; AppHeader
            // removes the tab) - avoiding two stale tabs side-by-side.
            // If the user clicks the post-completion card BEFORE the
            // live session has ended, both tabs briefly coexist; this
            // is an acceptable edge in normal flow.
            const stableId = (payload.toolId as string) || sessionId;
            dispatchSidePanelAutoOpen({
              type: 'agent_browse',
              id: stableId,
              title: currentUrl || 'Browser Agent',
              liveCoords: { sessionId, cdpToken, cdpWsUrl, currentUrl, runId, nodeId },
            });
          }
          break;
        }

        case 'visualization_ready': {
          // Early panel opening: search results are ready, fetches still in progress
          const viz = {
            type: payload.visualizationType as string,
            id: payload.visualizationId as string,
            title: (payload.visualizationTitle as string) || undefined,
            runId: (payload.runId as string) || undefined,
          };
          dispatchSidePanelAutoOpen(viz);
          if (viz.type === 'web_search') {
            dispatchWebSearchModified('web_search');
          }
          break;
        }

        case 'title':
          if (mapped.title && mapped.conversationId && callbacks?.onTitleUpdated) {
            callbacks.onTitleUpdated(mapped.conversationId, mapped.title);
          }
          break;

        case 'service_approval_required': {
          if (mapped.serviceApprovalRequired?.services?.length > 0) {
            dispatch({
              type: 'SERVICE_APPROVAL_REQUIRED',
              conversationId,
              services: mapped.serviceApprovalRequired.services,
              reason: mapped.serviceApprovalRequired.reason,
              needsAttention: mapped.serviceApprovalRequired.needsAttention,
            });
          }
          break;
        }

        case 'tool_authorization_required': {
          if (mapped.toolAuthorization?.rule) {
            dispatch({
              type: 'TOOL_AUTHORIZATION_REQUIRED',
              conversationId,
              rule: mapped.toolAuthorization.rule,
              toolName: mapped.toolAuthorization.toolName,
              action: mapped.toolAuthorization.action,
              toolCallId: mapped.toolAuthorization.toolCallId,
              argsSummary: mapped.toolAuthorization.argsSummary,
              applicationId: mapped.toolAuthorization.applicationId,
            });
          }
          break;
        }

        case 'pending_action_cancelled': {
          dispatch({ type: 'PENDING_ACTION_CANCELLED', conversationId });
          break;
        }

        case 'compaction_done': {
          // Notify upstream (ChatPage) so it can toast the user + refresh the
          // compactionMarker on the current conversation. No dispatch here -
          // the marker is a per-conversation attribute, not streaming state.
          if (
            mapped.conversationId &&
            typeof mapped.turnsCoveredCount === 'number' &&
            callbacks?.onCompactionDone
          ) {
            callbacks.onCompactionDone(
              mapped.conversationId,
              mapped.turnsCoveredCount,
              mapped.summarizerModel || '',
              mapped.generatedAt || '',
            );
          }
          break;
        }

        case 'done': {
          // Compute the authoritative final content FIRST, then hand it to the
          // COMPLETED reducer so the live bubble is reconciled with `fullContent`
          // (which carries the `[visualize:...]` markers the streamed chunks may
          // have omitted - see the COMPLETED reducer comment). This renders the
          // inline card live instead of only after a manual refresh.
          const finalContent = mapped.fullContent || refs.content;
          dispatch({
            type: 'COMPLETED',
            conversationId,
            content: finalContent,
            streamId: eventStreamId || boundStreamId || undefined,
          });

          if (conversationId && finalContent && callbacks?.onStreamComplete) {
            callbacks.onStreamComplete(conversationId, finalContent, refs.model);
          }

          // Mark completion time for later cleanup
          streamCompletionTimestamps.current.set(conversationId, Date.now());

          // Unsubscribe from WS channel on terminal event
          if (refs.wsUnsubscribe) {
            refs.wsUnsubscribe();
            refs.wsUnsubscribe = null;
          }
          break;
        }

        case 'stopped': {
          dispatch({ type: 'STOPPED', conversationId, streamId: eventStreamId || boundStreamId || undefined });

          const partialContent = mapped.partialContent || refs.content;
          if (conversationId && partialContent && callbacks?.onStreamComplete) {
            callbacks.onStreamComplete(conversationId, partialContent, refs.model);
          }

          if (refs.wsUnsubscribe) {
            refs.wsUnsubscribe();
            refs.wsUnsubscribe = null;
          }
          break;
        }

        case 'error': {
          const errorMsg = mapped.error || 'Stream error';
          const error: StreamError = {
            message: errorMsg,
            code: mapped.errorCode,
            retryable: mapped.retryable ?? false,
          };

          // CE cloud-relay errors (insufficient cloud credit / unmanaged model) get their own
          // actionable modal and are terminal (top up or refresh the bundle), not retryable.
          // Checked before the API-key heuristic; no-op in the Cloud edition.
          if (handleCeRelayError(errorMsg)) {
            error.retryable = false;
          } else if (isApiKeyError(errorMsg)) {
            showMissingApiKeyModal();
            error.retryable = false;
          } else {
            // Generic unexpected agent/relay error (e.g. "Provider not configured", a
            // transient provider/relay hiccup): surface a friendly "try again" modal
            // instead of failing silently. Edition-agnostic (Cloud and CE).
            showAgentErrorModal();
          }

          dispatch({ type: 'ERROR', conversationId, error, streamId: eventStreamId || boundStreamId || undefined });
          if (callbacks?.onError) {
            callbacks.onError(error);
          }

          if (refs.wsUnsubscribe) {
            refs.wsUnsubscribe();
            refs.wsUnsubscribe = null;
          }
          break;
        }

        case 'heartbeat':
          // Ignore heartbeat events
          break;

        default: {
          // Sub-agent forwarded events (detected by detectStreamEventType as sub_agent_*)
          if (eventType === 'sub_agent_started') {
            const subAgent = payload.subAgent as { name: string; avatarUrl?: string; agentId: string } | undefined;
            if (subAgent) {
              dispatch({ type: 'SUB_AGENT_STARTED', conversationId, subAgent });
            }
          } else if (eventType === 'sub_agent_tool_call') {
            const subAgent = payload.subAgent as { name: string; avatarUrl?: string; agentId: string } | undefined;
            if (subAgent) {
              dispatch({
                type: 'SUB_AGENT_TOOL_CALL',
                conversationId,
                subAgent,
                toolName: (payload.toolName as string) || 'unknown',
                toolId: (payload.toolId as string) || `sub-${Date.now()}`,
              });
            }
          } else if (eventType === 'sub_agent_tool_result') {
            const subAgent = payload.subAgent as { name: string; avatarUrl?: string; agentId: string } | undefined;
            if (subAgent) {
              dispatch({
                type: 'SUB_AGENT_TOOL_RESULT',
                conversationId,
                subAgent,
                toolId: (payload.toolId as string) || '',
                toolName: payload.toolName as string | undefined,
                success: (payload.success as boolean) ?? true,
                durationMs: payload.durationMs as number | undefined,
              });
            }
          } else if (eventType === 'sub_agent_completed') {
            const subAgent = payload.subAgent as { name: string; avatarUrl?: string; agentId: string } | undefined;
            if (subAgent) {
              dispatch({
                type: 'SUB_AGENT_COMPLETED',
                conversationId,
                subAgent,
                success: (payload.success as boolean) ?? true,
              });
            }
          } else if (eventType === 'sub_agent_content') {
            const subAgent = payload.subAgent as { name: string; avatarUrl?: string; agentId: string } | undefined;
            if (subAgent && payload.content) {
              dispatch({
                type: 'SUB_AGENT_CONTENT',
                conversationId,
                subAgent,
                content: payload.content as string,
              });
            }
          } else if (eventType === 'sub_agent_thinking') {
            const subAgent = payload.subAgent as { name: string; avatarUrl?: string; agentId: string } | undefined;
            if (subAgent && payload.thinking) {
              dispatch({
                type: 'SUB_AGENT_THINKING',
                conversationId,
                subAgent,
                thinking: payload.thinking as string,
              });
            }
          }
          break;
        }
      }
    };
  }, []);

  // ============== SEND MESSAGE ==============
  const sendMessage = useCallback(async (
    params: SendMessageParams,
    callbacks?: StreamingCallbacks
  ): Promise<string | null> => {
    const { message, model, provider = getEffectiveDefaultProvider() ?? '', conversationId, history, agentId, defaultSkillIds, chatConfig, source, taskId, reasoningEffort, keepPendingActions } = params;

    // Start streaming (status = 'streaming', content = '' means loading)
    const tempId = conversationId || `temp-${Date.now()}`;
    const refs = getStreamRefs(tempId);

    // Take ownership of this conversation's refs: any in-flight checkAndReconnect
    // for the same conversation re-validates the generation after its awaits and
    // aborts instead of clobbering this send's stream.
    refs.generation += 1;

    // Store callbacks and model
    refs.callbacks = callbacks || {};
    refs.model = model;
    refs.content = '';
    refs.streamId = null;

    // Unsubscribe any existing WS channel for this conversation
    if (refs.wsUnsubscribe) {
      refs.wsUnsubscribe();
      refs.wsUnsubscribe = null;
    }

    // Clear any pending service approvals (user is sending a FRESH message, abandoning approval).
    // SKIP on a resume - the sibling cards must stay live.
    const existingStream = state.streams.get(tempId);
    if (!keepPendingActions && existingStream?.pendingServiceApprovals?.length) {
      dispatch({ type: 'CLEAR_SERVICE_APPROVAL', conversationId: tempId });
    }

    dispatch({ type: 'START_STREAM', conversationId: tempId, keepPendingActions });

    try {
      // Step 1: POST to /v3/chat with JSON response
      const result = await unifiedApiService.sendChatMessageWs(
        message, model, provider,
        conversationId || undefined,
        history,
        params.attachments,
        agentId,
        defaultSkillIds,
        chatConfig,
        source,
        taskId,
        reasoningEffort,
        keepPendingActions
      );

      const resolvedConversationId = result.conversationId;

      // If this is a new conversation, migrate refs from temp to real ID
      if (tempId !== resolvedConversationId) {
        const tempRefs = streamRefsMap.current.get(tempId);
        if (tempRefs) {
          streamRefsMap.current.set(resolvedConversationId, tempRefs);
          streamRefsMap.current.delete(tempId);
        }
        dispatch({ type: 'CLEAR', conversationId: tempId });
      }

      // Update refs reference to the resolved one
      const resolvedRefs = getStreamRefs(resolvedConversationId);
      // Re-take ownership now that the stream exists: a checkAndReconnect that
      // entered DURING the POST round-trip holds the previous generation and
      // must abort rather than overwrite this stream with the previous one's
      // terminal state (mirror direction of the entry-time bump).
      resolvedRefs.generation += 1;
      resolvedRefs.callbacks = callbacks || {};
      resolvedRefs.model = model;
      resolvedRefs.content = '';
      resolvedRefs.streamId = result.streamId;

      // Start stream with real conversationId
      dispatch({
        type: 'START_STREAM',
        conversationId: resolvedConversationId,
        streamId: result.streamId,
        keepPendingActions,
      });

      if (!conversationId && callbacks?.onConversationCreated) {
        callbacks.onConversationCreated(resolvedConversationId);
      }

      // Live WS subscription is now DECLARATIVE (see ConversationStreamSubscriber): the resolved
      // START_STREAM above put this conversation into state.streams (status 'streaming'), so the
      // provider mounts a subscriber for `conversation:{id}` keyed on the active-stream set. Unlike
      // the previous imperative wsClient.subscribe here - which was silently dropped when an
      // org-switch reconnect churned the socket between POST and subscribe - the declarative one
      // survives reconnects via wsClient.resubscribeAll(requestSnapshot) on each `hello`.
      return resolvedConversationId;
    } catch (error: any) {
      if (!isMountedRef.current) return null;

      // CE cloud-relay errors (insufficient cloud credit / unmanaged model) come back via the
      // relay with their own token; route them to the CE modal before the generic 402 path
      // (whose Cloud Stripe modal is a no-op in CE). No-op in the Cloud edition.
      if (handleCeRelayError(error)) {
        const errorConvId = conversationId || tempId;
        dispatch({ type: 'ERROR', conversationId: errorConvId, error: { message: error?.message || 'Cloud relay error', retryable: false } });
        return null;
      }

      if (is402Error(error)) {
        showInsufficientCreditsModal();
        const errorConvId = conversationId || tempId;
        dispatch({ type: 'ERROR', conversationId: errorConvId, error: { message: 'Insufficient credits', retryable: false } });
        return null;
      }

      if (is413StorageError(error)) {
        showInsufficientStorageModal();
        const errorConvId = conversationId || tempId;
        dispatch({ type: 'ERROR', conversationId: errorConvId, error: { message: 'Storage quota exceeded', retryable: false } });
        return null;
      }

      const errorMsg = error?.message || 'Failed to send message';
      if (isApiKeyError(errorMsg)) {
        showMissingApiKeyModal();
        const errorConvId = conversationId || tempId;
        dispatch({ type: 'ERROR', conversationId: errorConvId, error: { message: errorMsg, retryable: false } });
        return null;
      }

      const errorConvId = conversationId || tempId;
      const streamError: StreamError = {
        message: errorMsg,
        retryable: true,
      };
      dispatch({ type: 'ERROR', conversationId: errorConvId, error: streamError });
      if (callbacks?.onError) {
        callbacks.onError(streamError);
      }
      return null;
    }
  }, [getStreamRefs, createWsEventHandler]);

  // ============== STOP STREAM ==============
  const stopStream = useCallback(async (conversationId: string) => {
    const refs = streamRefsMap.current.get(conversationId);

    // Get stream ID from state before any cleanup
    const streamState = state.streams.get(conversationId);
    const streamId = streamState?.streamId ?? refs?.streamId;

    // CRITICAL: capture THIS stream's identity (callbacks/content/model) BEFORE
    // the await below. `refs` is a per-conversation singleton (streamRefsMap):
    // if the user sends a NEW message in this SAME conversation while we await
    // the backend stop, sendMessage repurposes that same `refs` object
    // (callbacks/content/model/wsUnsubscribe) for the new stream. Reading
    // `refs.*` AFTER the await would then operate on the NEW stream - tearing
    // down its WebSocket (its response freezes/"disappears" after the thinking)
    // and firing its onStreamComplete prematurely (loadMessages overwrites the
    // in-flight response with backend state that lacks it yet - it only
    // reappears on refresh). Capturing here pins us to the stream we were
    // actually asked to stop. The cross-conversation variant is guarded
    // separately in useMessageHandlersV2.onStreamComplete via conversationIdRef.
    const stoppedCallbacks = refs?.callbacks;
    const stoppedContent = refs?.content ?? '';
    const stoppedModel = refs?.model ?? '';

    // A stop invoked from a STALE render can target a stream that is no longer the
    // one the live refs track (a newer send took the conversation over BEFORE this
    // call landed, so `streamState` - read from an old closure - still names the
    // previous stream). The backend stop must still go to the captured streamId,
    // but every LOCAL side effect (completion callback, refs cleanup) belongs to
    // the newer stream and is skipped; the STOPPED dispatch below carries the
    // captured streamId so the reducer drops it for the same reason.
    const staleClosure =
      streamId != null && refs?.streamId != null && refs.streamId !== streamId;

    // Detach + unsubscribe THIS stream's WS channel synchronously, then null the
    // shared slot. Cutting it up-front (rather than after the backend stop) means
    // a concurrent sendMessage for the same conversation can neither
    // double-unsubscribe it nor have its own freshly-subscribed channel torn down
    // by the post-await path - and a late chunk for the stopped stream can't
    // interleave into the new one.
    if (refs?.wsUnsubscribe) {
      refs.wsUnsubscribe();
      refs.wsUnsubscribe = null;
    }

    // Mark as stopped immediately (updates UI). Carries the stopped stream's id so
    // the reducer ignores it if a newer stream took the conversation over between
    // the click and this dispatch.
    dispatch({ type: 'STOPPED', conversationId, streamId: streamId ?? undefined });

    // Stop on the backend (HTTP by streamId - independent of the WS channel).
    if (streamId) {
      try {
        await unifiedApiService.stopStream(streamId);
      } catch {
        // Silently fail - stream may already be stopped
      }
    }

    // Refresh the message list for the stopped stream - ALWAYS (even with no text
    // content), but ONLY if a newer stream has not taken over this conversation
    // during the await. "Taken over" happens two ways: (1) a resend in the SAME
    // conversation reassigned refs.callbacks on the shared object, or (2) the map
    // entry was replaced/cleared (clearStream, or a new-conversation tempId
    // migration) so it no longer points to the object we captured. In either case
    // the newer stream fires its OWN onStreamComplete on completion - firing the
    // stale stopped callback here would clobber it. (We deliberately use the
    // locally-captured stoppedContent, not the backend 'stopped' event's
    // partialContent: the WS was cut up-front, and the two differ by at most a
    // few tokens generated between the click and the backend halting.)
    const liveRefs = streamRefsMap.current.get(conversationId);
    const takenOver = staleClosure || liveRefs !== refs || refs?.callbacks !== stoppedCallbacks;
    if (!takenOver && conversationId && stoppedCallbacks?.onStreamComplete) {
      stoppedCallbacks.onStreamComplete(conversationId, stoppedContent, stoppedModel);
    }

    if (!takenOver && refs) {
      refs.streamId = null;
    }

    // Always remove from server active streams (cleanup)
    dispatch({ type: 'REMOVE_SERVER_ACTIVE_STREAM', conversationId });
  }, [state.streams]);

  // ============== CHECK AND RECONNECT ==============
  const checkAndReconnect = useCallback(async (
    conversationId: string,
    callbacks?: StreamingCallbacks
  ): Promise<boolean> => {
    // Check if already streaming this conversation
    const existingStream = state.streams.get(conversationId);
    if (existingStream && existingStream.status === 'streaming') {
      return true;
    }

    const refs = getStreamRefs(conversationId);
    refs.callbacks = callbacks || {};
    refs.content = '';

    // Take the ownership token for this conversation's refs. If a send (or a newer
    // reconnect) starts while the status fetches below are in flight, it bumps the
    // generation; re-validating after each await lets this reconnect abort WITHOUT
    // touching the newer stream - no refs mutation, no stale terminal dispatch.
    // Previously a slow reconnect that resolved the PREVIOUS stream's terminal state
    // flipped the freshly-started stream's entry to 'completed': the Stop button
    // disappeared while the new response was still visibly streaming (bridge runs,
    // being long and multi-turn, hit this the most).
    refs.generation += 1;
    const myGeneration = refs.generation;
    const takenOver = () => refs.generation !== myGeneration;

    try {
      // Step 1: Check if there's an active stream
      const status = await unifiedApiService.getStreamStatus(conversationId);
      // A newer operation owns the conversation now - report "active" and stand down.
      if (takenOver()) return true;

      if (!status?.hasActiveStream) {
        dispatch({ type: 'REMOVE_SERVER_ACTIVE_STREAM', conversationId });
        return false;
      }

      // Step 2: Get reconnection state (buffered content + tool events)
      const reconnState = await unifiedApiService.getStreamReconnectionState(conversationId);
      if (takenOver()) return true;

      if (!reconnState?.hasActiveStream) {
        dispatch({ type: 'REMOVE_SERVER_ACTIVE_STREAM', conversationId });
        return false;
      }

      // Step 3: Apply buffered state
      dispatch({
        type: 'START_STREAM',
        conversationId,
        streamId: reconnState.streamId || '',
      });

      refs.model = reconnState.model || getEffectiveDefaultModel() || '';
      refs.streamId = reconnState.streamId || null;

      // Apply buffered content
      if (reconnState.content) {
        refs.content = reconnState.content;
        dispatch({ type: 'APPEND_CONTENT', conversationId, chunk: reconnState.content });
      }

      // Apply buffered tool events
      if (reconnState.toolEvents?.length > 0) {
        for (const toolJson of reconnState.toolEvents) {
          try {
            const parsed = JSON.parse(toolJson);
            const eventType = detectStreamEventType(parsed as Record<string, unknown>);
            const mapped = mapV2EventToV1(parsed as Record<string, unknown>, eventType, null);

            if (mapped.type === 'tool_call') {
              let thinkingMessage: string | undefined;
              let rawArgs: string | undefined;
              if (mapped.arguments) {
                try {
                  const args = typeof mapped.arguments === 'string'
                    ? JSON.parse(mapped.arguments)
                    : mapped.arguments;
                  thinkingMessage = args.thinking;
                  rawArgs = args.raw;
                } catch { /* ignore */ }
              }
              dispatch({
                type: 'TOOL_CALL',
                conversationId,
                toolName: mapped.toolName || 'unknown',
                toolId: mapped.toolId || `tool-${crypto.randomUUID()}`,
                arguments: rawArgs || (typeof mapped.arguments === 'string' ? mapped.arguments : JSON.stringify(mapped.arguments)),
                thinkingMessage,
              });
            } else if (mapped.type === 'tool_result') {
              dispatch({
                type: 'TOOL_RESULT',
                conversationId,
                toolId: mapped.toolId || '',
                success: mapped.success ?? true,
                result: typeof mapped.result === 'string' ? mapped.result : undefined,
                resultId: mapped.resultId,
                durationMs: mapped.durationMs,
                error: mapped.error,
                visualization: mapped.visualization,
                iconSlug: mapped.iconSlug,
                displayToolName: mapped.displayToolName,
                tasksData: mapped.tasksData,
                credentialRequired: mapped.credentialRequired,
                serviceApproval: mapped.serviceApproval,
                diff: mapped.diff,
                gitStatus: mapped.gitStatus,
              });

              if ((mapped.success ?? true) && mapped.toolName) {
                dispatchWorkflowPlanModified(mapped.toolName);
                dispatchDataSourceModified(mapped.toolName);
                dispatchInterfaceModified(mapped.toolName);
                dispatchWebSearchModified(mapped.toolName);
              }
              if ((mapped.success ?? true) && mapped.visualization
                  && mapped.visualization.type !== 'agent_browse') {
                dispatchSidePanelAutoOpen(mapped.visualization);
              }
            }
          } catch {
            // Ignore parse errors
          }
        }
      }

      // Handle terminal states from reconnection. Each terminal carries the stream id
      // it belongs to, so the reducer drops it if the conversation's live entry is a
      // different (newer) stream by the time it lands.
      const reconnStreamId = reconnState.streamId || undefined;
      if (reconnState.state === 'COMPLETED') {
        dispatch({ type: 'COMPLETED', conversationId, streamId: reconnStreamId });
        if (refs.content && callbacks?.onStreamComplete) {
          callbacks.onStreamComplete(conversationId, refs.content, refs.model);
        }
        return true;
      }
      if (reconnState.state === 'STOPPED_BY_USER') {
        dispatch({ type: 'STOPPED', conversationId, streamId: reconnStreamId });
        return true;
      }
      if (reconnState.state === 'ERROR') {
        dispatch({ type: 'COMPLETED', conversationId, streamId: reconnStreamId });
        return false;
      }

      // Step 4: Subscribe to WebSocket channel for live events
      // Use requestSnapshot=true so the backend re-publishes the current stream
      // state via Redis. This closes the gap between the REST fetch (step 2) and
      // the WS subscription: any terminal event (COMPLETED, STOPPED, ERROR)
      // published during that window is replayed by the snapshot trigger.
      // Live subscription is declarative now (ConversationStreamSubscriber): the START_STREAM
      // dispatched above put this conversation into the active-stream set, so the provider mounts a
      // subscriber for `conversation:{id}` with requestSnapshot - no imperative subscribe needed here.
      return true;
    } catch (error: any) {
      // A failed reconnect must not clobber a stream that a newer operation started
      // while the fetches above were in flight.
      if (takenOver()) return true;

      dispatch({ type: 'COMPLETED', conversationId });

      if (error?.message?.includes('404') || error?.status === 404) {
        dispatch({ type: 'REMOVE_SERVER_ACTIVE_STREAM', conversationId });
        return false;
      }

      return false;
    }
  }, [state.streams, getStreamRefs, createWsEventHandler]);

  // ============== CLEAR STREAM ==============
  const clearStream = useCallback((conversationId: string) => {
    // First dispatch to update React state
    dispatch({ type: 'CLEAR', conversationId });
    dispatch({ type: 'REMOVE_SERVER_ACTIVE_STREAM', conversationId });

    // Then cleanup refs (after dispatch to avoid race conditions)
    const refs = streamRefsMap.current.get(conversationId);
    if (refs?.wsUnsubscribe) {
      refs.wsUnsubscribe();
    }
    streamRefsMap.current.delete(conversationId);
    streamCompletionTimestamps.current.delete(conversationId);
  }, []);

  // ============== GET STREAM CONTENT ==============
  const getStreamContent = useCallback((conversationId: string): string => {
    const streamState = state.streams.get(conversationId);
    return streamState?.content || '';
  }, [state.streams]);

  // ============== GET TOOL ACTIVITIES ==============
  const getToolActivities = useCallback((conversationId: string): ToolActivity[] => {
    const streamState = state.streams.get(conversationId);
    return streamState?.toolActivities || [];
  }, [state.streams]);

  // ============== SERVICE APPROVAL ==============
  const getPendingServiceApprovals = useCallback((conversationId: string): PendingServiceApproval[] => {
    const streamState = state.streams.get(conversationId);
    return streamState?.pendingServiceApprovals || [];
  }, [state.streams]);

  const clearServiceApproval = useCallback((conversationId: string, key?: string) => {
    dispatch({ type: 'CLEAR_SERVICE_APPROVAL', conversationId, key });
  }, []);

  const getPendingToolAuthorizations = useCallback((conversationId: string): PendingToolAuthorization[] => {
    const streamState = state.streams.get(conversationId);
    return streamState?.pendingToolAuthorizations || [];
  }, [state.streams]);

  const clearToolAuthorization = useCallback((conversationId: string, key?: string) => {
    dispatch({ type: 'CLEAR_TOOL_AUTHORIZATION', conversationId, key });
  }, []);

  // ============== DERIVED VALUES ==============

  // Check if any stream is actively streaming (not completed)
  const isStreaming = useMemo(() =>
    Array.from(state.streams.values()).some(s => s.status === 'streaming') ||
    state.serverActiveStreams.size > 0,
    [state.streams, state.serverActiveStreams]
  );

  // Check if a specific conversation is ACTIVELY streaming (for stop button, pulse indicator)
  // Does NOT include 'completed' - use getStreamState for content visibility
  const isStreamingConversation = useCallback((conversationId: string) => {
    const stream = state.streams.get(conversationId);
    if (stream?.status === 'streaming') {
      return true;
    }
    // Check server-reported active streams
    return state.serverActiveStreams.has(conversationId);
  }, [state.streams, state.serverActiveStreams]);

  // Legacy state for backward compatibility
  // Returns first stream found, or idle state if no streams
  const legacyState = useMemo<LegacyStreamingState>(() => {
    // Return first stream from the Map
    const firstEntry = state.streams.entries().next();
    if (!firstEntry.done) {
      const [convId, stream] = firstEntry.value;
      return {
        status: stream.status,
        conversationId: convId,
        streamId: stream.streamId,
        content: stream.content,
        error: stream.error,
      };
    }
    // No streams - return idle state
    return {
      status: 'idle',
      conversationId: null,
      streamId: null,
      content: '',
      error: null,
    };
  }, [state.streams]);

  // ============== CONTEXT VALUE ==============
  const value = useMemo<StreamingContextType>(() => ({
    getStreamState,
    state: legacyState,
    sendMessage,
    stopStream,
    checkAndReconnect,
    clearStream,
    clearServiceApproval,
    clearToolAuthorization,
    isStreaming,
    isStreamingConversation,
    serverStreamsLoaded,
    getStreamContent,
    getToolActivities,
    getPendingServiceApprovals,
    getPendingToolAuthorizations,
  }), [
    getStreamState, legacyState, sendMessage, stopStream,
    checkAndReconnect, clearStream, clearServiceApproval, clearToolAuthorization,
    isStreaming, isStreamingConversation, serverStreamsLoaded,
    getStreamContent,
    getToolActivities, getPendingServiceApprovals, getPendingToolAuthorizations,
  ]);

  // One declarative WS subscription per conversation that currently needs a live channel:
  // streaming (or just-completed, so a terminal snapshot still lands) plus any server-reported
  // active streams (reload-while-streaming). Sorted + stable so the array identity only changes
  // when the SET of ids changes - never on per-token content mutations - avoiding subscribe churn.
  const activeChannelIds = useMemo(() => {
    const ids = new Set<string>();
    state.streams.forEach((s, id) => {
      if (s.status === 'streaming' || s.status === 'completed') ids.add(id);
    });
    (state.serverActiveStreams as Set<string> | string[] | undefined)?.forEach((id: string) => ids.add(id));
    return Array.from(ids).sort();
  }, [state.streams, state.serverActiveStreams]);

  return (
    <StreamingContext.Provider value={value}>
      {activeChannelIds.map((id) => (
        <ConversationStreamSubscriber
          key={id}
          conversationId={id}
          getRefs={getStreamRefs}
          makeHandler={createWsEventHandler}
        />
      ))}
      {children}
    </StreamingContext.Provider>
  );
}

// ============== HOOKS ==============

export function useStreaming() {
  const context = useContext(StreamingContext);
  if (!context) {
    throw new Error('useStreaming must be used within StreamingProvider');
  }
  return context;
}

export function useStreamingSafe(): StreamingContextType | null {
  return useContext(StreamingContext);
}

/**
 * Declarative live subscription for ONE active conversation's stream. Mounted once per active
 * conversation id by the provider. Unlike the old imperative `wsClient.subscribe` in sendMessage,
 * this subscription is owned by React's lifecycle, so it survives WS reconnects (the org-switch
 * reconnect churn that silently dropped the imperative subscribe) - `wsClient.resubscribeAll`
 * re-sends every tracked channel WITH requestSnapshot on each `hello`. It routes events through the
 * unchanged `createWsEventHandler` (raw-payload contract); `requestSnapshot=true` makes the backend
 * conversation-snapshot replay any content published before this subscribe lands. This aligns the
 * chat with the workflow/agent/notification surfaces, which already use this declarative pattern.
 */
function ConversationStreamSubscriber({
  conversationId,
  getRefs,
  makeHandler,
}: {
  conversationId: string;
  getRefs: (id: string) => StreamRefs;
  makeHandler: (id: string, cb: StreamingCallbacks | undefined, refs: StreamRefs) => (raw: unknown) => void;
}) {
  const refs = getRefs(conversationId);
  useChannel<unknown>(
    `conversation:${conversationId}`,
    (raw) => makeHandler(conversationId, refs.callbacks, refs)(raw),
    { requestSnapshot: true },
  );
  return null;
}

