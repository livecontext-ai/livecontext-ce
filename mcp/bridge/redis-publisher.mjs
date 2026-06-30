/**
 * Redis publisher for conversation streaming events.
 *
 * Mirrors the event format of ConversationRedisStreamingCallback.java
 * so the frontend SSE/WebSocket bridge picks them up identically.
 *
 * Publishes to both:
 * - stream:events:{streamId}           (SSE reconnection)
 * - ws:conversation:{conversationId}   (WebSocket live delivery)
 */

const SSE_PREFIX = 'stream:events:';
const WS_PREFIX = 'ws:conversation:';
const CONV_INDEX_PREFIX = 'stream:conv:';
// Fleet activity channel (mirrors AgentActivityPublisher.java CHANNEL_PREFIX).
const FLEET_ACTIVITY_PREFIX = 'ws:agent:activity:';

export class RedisPublisher {
  /**
   * @param {import('ioredis').Redis} redis           Pub/sub client (used for PUBLISH).
   * @param {string} streamId
   * @param {string} conversationId
   * @param {import('ioredis').Redis|null} [cmdRedis] Optional second client for SET/EXISTS
   *   commands. Pub/sub clients in ioredis cannot run regular commands once they are
   *   subscribed, so callers pass a separate connection. Falls back to the publish
   *   client if not supplied. Constructor-injected (used to be a private monkey-patch
   *   from server.mjs - `publisher._cmdRedis = redis` - which broke encapsulation).
   */
  /**
   * @param {object} [parentContext]       Optional sub-agent parent-forwarding context.
   * @param {string} [parentContext.parentConversationId]
   * @param {string} [parentContext.subAgentName]
   * @param {string} [parentContext.subAgentAvatarUrl]
   * @param {string} [parentContext.subAgentId]
   * @param {string} [parentContext.workflowRunId] P0-E - used by isCancelled() to
   *   honor a workflow-level cancel that arrived on the parent's run.
   */
  /**
   * @param {object} [fleetContext]        Optional fleet-activity context. When
   *   {@code agentEntityId} is present, tool start/finish are ALSO published to
   *   {@code ws:agent:activity:{agentEntityId}} so the agent card / fleet shimmer
   *   reflects each tool call. Bridge/CLI agents otherwise emit only
   *   execution_started/completed (from the Java side), leaving the in-between blind.
   * @param {string} [fleetContext.agentEntityId]
   * @param {string} [fleetContext.executionId]
   * @param {string} [fleetContext.taskId]  Scopes the task-board shimmer to (agent, task).
   */
  constructor(redis, streamId, conversationId, cmdRedis = null, parentContext = null, fleetContext = null) {
    this.redis = redis;
    this._cmdRedis = cmdRedis;
    this.streamId = streamId;
    this.conversationId = conversationId;
    this.sseChannel = SSE_PREFIX + streamId;
    this.wsChannel = conversationId ? WS_PREFIX + conversationId : null;

    // Parent-forwarding (mirrors ConversationRedisStreamingCallback.publishToParent)
    const pc = parentContext || {};
    this.parentConversationId = pc.parentConversationId || null;
    this.parentWsChannel = this.parentConversationId ? WS_PREFIX + this.parentConversationId : null;
    this.subAgentName = pc.subAgentName || null;
    this.subAgentAvatarUrl = pc.subAgentAvatarUrl || null;
    this.subAgentId = pc.subAgentId || null;
    // P0-E - propagate cancel from parent's stream and from workflow run.
    this.workflowRunId = pc.workflowRunId || null;

    // Fleet activity (mirrors AgentActivityPublisher.java). Null agentEntityId ⇒ no-op.
    const fc = fleetContext || {};
    this.agentEntityId = fc.agentEntityId || null;
    this.fleetExecutionId = fc.executionId || null;
    this.fleetTaskId = fc.taskId || null;
    this.fleetActivityChannel = this.agentEntityId ? FLEET_ACTIVITY_PREFIX + this.agentEntityId : null;
  }

  /** Redis client for non-pub/sub commands (SET, EXISTS, GET). */
  get cmdRedis() { return this._cmdRedis || this.redis; }

  /** Set the conv→stream index in Redis (for stop signal propagation) */
  async setConversationIndex() {
    if (this.conversationId && this.streamId) {
      try {
        await this.cmdRedis.set(
          CONV_INDEX_PREFIX + this.conversationId,
          this.streamId,
          'EX',
          3600
        );
      } catch (e) {
        console.warn('[BRIDGE] Failed to set conv index:', e.message);
      }
    }
  }

  /**
   * Append a tool event JSON to {@code stream:{streamId}:tools} so the conversation snapshot
   * replay (getStreamState) can render in-progress/completed tool cards after an open/refresh
   * reconnect - matching publishContent's content buffering and the Java callback's appendToolEvent.
   * Uses the same client as the content rpush. Best-effort: a Redis hiccup must never break the
   * live stream (the live event was already published). The Java finalize (stream complete/error)
   * schedules the TTL cleanup of this key, same as the content key.
   */
  async _bufferToolEvent(event) {
    try {
      await this.redis.rpush(`stream:${this.streamId}:tools`, JSON.stringify(event));
    } catch (_) { /* best-effort */ }
  }

  /** Content chunk - real-time text streaming */
  async publishContent(content) {
    await this._publish({
      streamId: this.streamId,
      content,
      timestamp: new Date().toISOString(),
    });
    await this._publishToParent('sub_agent_content', { content });

    // Accumulate content in Redis List (for stop handler)
    try {
      await this.redis.rpush(`stream:${this.streamId}:content`, content);
    } catch (_) { /* best-effort */ }
  }

  /** Raw thinking chunk */
  async publishThinking(thinking) {
    await this._publish({
      streamId: this.streamId,
      thinking,
      timestamp: new Date().toISOString(),
    });
    await this._publishToParent('sub_agent_thinking', { thinking });
  }

  /** Parsed thinking section (title + content) */
  async publishThinkingSection(title, content) {
    await this._publish({
      streamId: this.streamId,
      title,
      content,
      timestamp: new Date().toISOString(),
    });
  }

  /** Tool call started */
  async publishToolCall(toolName, toolId, args) {
    const event = {
      streamId: this.streamId,
      toolName,
      toolId,
      arguments: args,
      timestamp: new Date().toISOString(),
    };
    await this._publish(event);
    // Buffer for snapshot replay (mirrors publishContent's rpush + the Java callback's
    // appendToolEvent) so a mid-run open/refresh reconnect replays in-progress tool cards,
    // not just the streamed text.
    await this._bufferToolEvent(event);
    await this._publishToParent('sub_agent_tool_call', { toolName, toolId, arguments: args });
    // Fleet activity rides on the canonical tool-call emission point so EVERY adapter
    // path (dispatchToolCall, codex item.started + synthetic, gemini/mistral) emits it
    // exactly once. No-op unless this publisher was given an agentEntityId.
    await this.publishFleetToolCallStarted(toolName, toolId);
  }

  /** Tool call completed */
  async publishToolResult(toolId, toolName, success, durationMs, result, metadata) {
    const event = {
      streamId: this.streamId,
      toolId,
      toolName,
      success,
      resultId: null,
      timestamp: new Date().toISOString(),
    };
    if (durationMs != null) event.durationMs = durationMs;
    if (result != null) event.result = result;
    if (!success && result != null) event.error = result;
    // Forward metadata fields that the frontend expects
    if (metadata) {
      if (metadata.iconSlug) event.iconSlug = metadata.iconSlug;
      if (metadata.toolName) event.displayToolName = metadata.toolName;
      if (metadata.label) event.label = metadata.label;
      if (metadata.visualization) event.visualization = metadata.visualization;
      if (metadata.tasksData) event.tasksData = metadata.tasksData;
      if (metadata.serviceApproval) event.serviceApproval = metadata.serviceApproval;
      // Source-tool render cards: `diff` (red/green unified diff - repo edit/write/diff
      // + interface patch) and `gitStatus` (status badges - repo git_status).
      if (metadata.diff) event.diff = metadata.diff;
      if (metadata.gitStatus) event.gitStatus = metadata.gitStatus;

      // Convert serviceApprovalRequested metadata into serviceApproval on tool result
      // + emit separate service_approval_required event (triggers approval modal)
      if (metadata.serviceApprovalRequested && metadata.services) {
        event.serviceApproval = {
          services: metadata.services,
          reason: metadata.reason || '',
        };
        // Emit the modal-triggering event
        await this._publish({
          streamId: this.streamId,
          services: metadata.services,
          reason: metadata.reason || '',
          needsAttention: metadata.needsAttention || false,
          timestamp: new Date().toISOString(),
        });
      }

      // Tool-authorization gate (bridge path mirror of ConversationRedisStreamingCallback):
      // emit a tool_authorization_required event so the chat paints the authorization card.
      // Frontend detects via 'toolAuthorization' in data (distinct from service approval).
      // Emitted live - the bridge never pauses the agent (async), so several cards can be
      // raised across a single turn just like the Java path.
      if (metadata.toolAuthorizationRequired && metadata.rule) {
        await this._publish({
          streamId: this.streamId,
          toolAuthorization: {
            rule: metadata.rule,
            toolName: metadata.toolName,
            action: metadata.action,
            toolCallId: metadata.toolCallId,
            argsSummary: metadata.argsSummary,
            // application:acquire rides the publication id so the card can open the install modal.
            applicationId: metadata.applicationId,
          },
          timestamp: new Date().toISOString(),
        });
      }
    }
    await this._publish(event);
    // Buffer for snapshot replay (see publishToolCall) so reconnect renders completed tool cards.
    await this._bufferToolEvent(event);
    await this._publishToParent('sub_agent_tool_result', { toolName, toolId, success, durationMs });
    // Fleet activity completion - paired with publishToolCall's started above so every
    // adapter path reports tool finish exactly once. No-op without an agentEntityId.
    await this.publishFleetToolCallCompleted(toolName, toolId, success, durationMs);
  }

  /** Stream completed */
  async publishDone(fullContent, totalTokens = 0) {
    await this._publish({
      streamId: this.streamId,
      fullContent: fullContent ?? '',
      totalTokens,
      timestamp: new Date().toISOString(),
    });
  }

  /** Stream error */
  async publishError(error) {
    await this._publish({
      streamId: this.streamId,
      error,
      errorCode: 'STREAM_ERROR',
      retryable: true,
      timestamp: new Date().toISOString(),
    });
  }

  /**
   * Fleet activity: a tool call started → ws:agent:activity:{agentEntityId}.
   * No-op unless this publisher was given an agentEntityId (non-agent bridge calls).
   */
  async publishFleetToolCallStarted(toolName, toolCallId) {
    await this._publishFleetActivity({ event: 'tool_call_started', toolName, toolCallId });
  }

  /**
   * Fleet activity: a tool call finished → ws:agent:activity:{agentEntityId}.
   * No-op unless this publisher was given an agentEntityId.
   */
  async publishFleetToolCallCompleted(toolName, toolCallId, success, durationMs) {
    const payload = { event: 'tool_call_completed', toolName, toolCallId, success };
    if (durationMs != null) payload.durationMs = durationMs;
    await this._publishFleetActivity(payload);
  }

  /**
   * Publish a fleet-activity event to ws:agent:activity:{agentEntityId}, matching the
   * Java AgentActivityPublisher payload schema (event, executionId, agentEntityId,
   * timestamp, [taskId] + event-specific fields). The bridge only fills the
   * tool_call_* gap; execution_started/completed stay on the Java side, so there are
   * no duplicate lifecycle events. Best-effort - never breaks the conversation stream.
   */
  async _publishFleetActivity(fields) {
    if (!this.fleetActivityChannel) return;
    try {
      const payload = {
        executionId: this.fleetExecutionId,
        agentEntityId: this.agentEntityId,
        timestamp: new Date().toISOString(),
        ...fields,
      };
      if (this.fleetTaskId) payload.taskId = this.fleetTaskId;
      await this.redis.publish(this.fleetActivityChannel, JSON.stringify(payload));
    } catch (e) {
      process.stderr.write(`[BRIDGE:fleet] Failed to publish ${fields.event}: ${e.message}\n`);
    }
  }

  /**
   * Check if a cancel signal exists for THIS stream OR for any of its parents
   * (parent conversation stream, ancestor workflow run). Mirrors the Java
   * {@code ConversationRedisStreamingCallback.shouldStop()} chain so a
   * STOP on the conversation that spawned this sub-agent - or on a workflow
   * that contains it - propagates here.
   *
   * @returns {Promise<boolean>}
   */
  async isCancelled() {
    const status = await this.getCancelStatus();
    return status.cancelled;
  }

  /**
   * Read the cancel signal payload (if any) and return its cause.
   *
   * <p>Walks the cancel keys in this order: own stream, parent stream (resolved
   * via {@code stream:conv:{parentConversationId}}), workflow run. First hit
   * wins. Newer producers store a JSON document so the bridge can distinguish
   * a user-initiated cancel ({@code STOPPED_BY_USER}) from a system-initiated
   * one ({@code CANCELLED}). Legacy producers wrote an empty value - those are
   * treated as user cancellations to keep the historic behaviour.</p>
   *
   * @returns {Promise<{ cancelled: boolean, cause: 'user'|'system'|null }>}
   */
  async getCancelStatus() {
    // 1) Own stream
    const own = await this._readCancelKey(`agent:cancel:${this.streamId}`);
    if (own.cancelled) return own;

    // 2) Parent conversation's stream - resolve indirectly via stream:conv index.
    if (this.parentConversationId) {
      try {
        const parentStreamId = await this.cmdRedis.get(CONV_INDEX_PREFIX + this.parentConversationId);
        if (parentStreamId) {
          const parent = await this._readCancelKey(`agent:cancel:${parentStreamId}`);
          if (parent.cancelled) return parent;
        }
      } catch (_) {
        // ignore - fall through
      }
    }

    // 3) Workflow ancestor (set by orchestrator on workflow cancel).
    if (this.workflowRunId) {
      try {
        const exists = await this.cmdRedis.exists(`workflow:cancel:${this.workflowRunId}`);
        if (exists === 1) {
          // Workflow cancels are always user-driven from this bridge's POV.
          return { cancelled: true, cause: 'user' };
        }
      } catch (_) { /* ignore */ }
    }

    return { cancelled: false, cause: null };
  }

  /** Read a single cancel key and parse the cause payload. */
  async _readCancelKey(key) {
    try {
      const raw = await this.cmdRedis.get(key);
      if (raw == null) return { cancelled: false, cause: null };
      if (!raw || raw.length === 0) return { cancelled: true, cause: 'user' };
      try {
        const parsed = JSON.parse(raw);
        const cause = parsed && typeof parsed.cause === 'string' ? parsed.cause : 'user';
        return { cancelled: true, cause: cause === 'system' ? 'system' : 'user' };
      } catch {
        return { cancelled: true, cause: 'user' };
      }
    } catch (_) {
      return { cancelled: false, cause: null };
    }
  }

  /**
   * Forward an event to the parent conversation's WebSocket channel.
   * Mirrors ConversationRedisStreamingCallback.publishToParent() - includes
   * a nested `subAgent` metadata object so the frontend renders sub-agent activity.
   */
  async _publishToParent(eventType, payload) {
    if (!this.parentWsChannel) return;
    try {
      const event = {
        type: eventType,
        subAgent: {
          name: this.subAgentName,
          ...(this.subAgentAvatarUrl && { avatarUrl: this.subAgentAvatarUrl }),
          ...(this.subAgentId && { agentId: this.subAgentId }),
        },
        ...payload,
        timestamp: new Date().toISOString(),
      };
      const json = JSON.stringify(event);
      await this.redis.publish(this.parentWsChannel, json);
    } catch (e) {
      process.stderr.write(`[BRIDGE:parent] Failed to publish ${eventType} to parent: ${e.message}\n`);
    }
  }

  /** Publish event JSON to both SSE and WS Redis channels */
  async _publish(event) {
    try {
      const json = JSON.stringify(event);
      const sseReceivers = await this.redis.publish(this.sseChannel, json);
      let wsReceivers = 0;
      if (this.wsChannel) {
        wsReceivers = await this.redis.publish(this.wsChannel, json);
      }
      const eventType = event.content ? 'content' : event.fullContent != null ? 'done' : event.thinking ? 'thinking' : event.toolName ? 'tool' : event.error ? 'error' : 'other';
      process.stderr.write(`[BRIDGE:pub] ${eventType} → sse:${sseReceivers} ws:${wsReceivers} ch=${this.wsChannel}\n`);
    } catch (e) {
      console.warn('[BRIDGE] Failed to publish event:', e.message);
    }
  }
}
