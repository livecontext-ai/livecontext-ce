// Monotonic counter for synthetic tool ids - `Date.now()` alone collides
// when two tool calls land in the same millisecond. Module-scope so the
// counter survives across calls and adapters. Exported so adapters with
// their own dispatch sites (e.g. codex command_execution / web_search) use
// the same counter as the shared helpers.
let _synthIdCounter = 0;
export function synthIdFor(providerKey) {
  _synthIdCounter = (_synthIdCounter + 1) >>> 0;
  return `${providerKey}_${Date.now()}_${_synthIdCounter}`;
}

/**
 * Shared helpers used by every CLI adapter (claude, codex, gemini, mistral).
 *
 * Before this module each adapter inlined its own copy of:
 *   - per-call usage push + cumulative recompute + updateState
 *   - tool_call dispatch (pendingToolCalls.set + orderedEntries.push + publish)
 *   - tool_result dispatch (pending lookup + toolResults.push + publish + delete)
 *
 * Result: ~400 duplicated lines and one prod regression where the Claude
 * adapter's variant of the usage loop diverged silently from the others
 * (closure trap + double counting). This module makes the canonical shape the
 * only shape - adapters now describe data, not control flow.
 */

/**
 * Push one API call's usage onto ctx.state.perCallUsages and recompute the
 * cumulative `usage` field that the budget guard reads mid-run.
 *
 * The cumulative is a SUM across perCallUsages - that's the correct billing
 * semantics for every provider where each push is a distinct billable call.
 * Claude is the only provider where the same logical "user turn" can produce
 * two billable calls (extended thinking pause/resume); both are intentionally
 * counted because Anthropic charges them separately.
 *
 * @param {object} ctx - adapter execution context (from server.mjs)
 * @param {object} call - { promptTokens, completionTokens, cacheCreationInputTokens?, cacheReadInputTokens?, cachedTokens?, reasoningTokens? }
 */
export function recordCallUsage(ctx, call) {
  const entry = {
    promptTokens: call.promptTokens || 0,
    completionTokens: call.completionTokens || 0,
    cacheCreationInputTokens: call.cacheCreationInputTokens || 0,
    cacheReadInputTokens: call.cacheReadInputTokens || 0,
    // OpenAI-style cached prompt subset (codex cached_input_tokens) - billed
    // at the provider's cached discount by auth-service.
    cachedTokens: call.cachedTokens || 0,
    reasoningTokens: call.reasoningTokens || 0,
  };
  ctx.state.perCallUsages.push(entry);
  ctx.state.iterationTimestamps.push(Date.now());

  let totalInput = 0;
  let totalOutput = 0;
  for (const c of ctx.state.perCallUsages) {
    totalInput += c.promptTokens;
    totalOutput += c.completionTokens;
  }
  ctx.updateState({ usage: { promptTokens: totalInput, completionTokens: totalOutput } });
  return { totalInput, totalOutput, callIndex: ctx.state.perCallUsages.length };
}

/**
 * Dispatch a tool call: register it in pendingToolCalls, append to orderedEntries
 * (for the canonical replay log), and publish over Redis.
 *
 * Adapters call this *after* any provider-specific dedup (e.g. Claude's
 * seenToolUseIds + pause_turn skip). Helpers do not see provider-specific
 * dedup state - that's intentional, see ctx.adapterState in server.mjs.
 *
 * @param {object} ctx
 * @param {object} call - { toolId, toolName, argsStr, extras? }
 *   `extras` is shallow-merged into the pendingToolCalls entry (used by
 *   claude-adapter to remember `attachmentFileName`).
 */
export async function dispatchToolCall(ctx, { toolId, toolName, argsStr, extras = {} }) {
  ctx.pendingToolCalls.set(toolId, {
    toolName,
    arguments: argsStr,
    startTime: Date.now(),
    ...extras,
  });
  ctx.orderedEntries.push({
    type: 'tool_call',
    id: toolId,
    toolName,
    arguments: argsStr,
    timestamp: Date.now(),
  });
  await ctx.publisher.publishToolCall(toolName, toolId, argsStr);
}

/**
 * Dispatch a tool result: look up the pending entry, build the canonical
 * toolResults record, publish over Redis, then drop the pending entry.
 *
 * @param {object} ctx
 * @param {object} result - { toolId, isError, content, errorMsg?, metadata?, fallbackToolName? }
 *   `errorMsg` is used as the error string when `isError` is true.
 *   `fallbackToolName` is used if the pending entry is missing (race / unknown id).
 *   `metadata` is shallow-merged into the published metadata; helpers will also
 *   set `label`/`toolName` from the pending entry's `attachmentFileName` if
 *   present (Claude `view_attachment` flow).
 */
export async function dispatchToolResult(ctx, { toolId, isError, content, errorMsg, metadata = {}, fallbackToolName = 'unknown' }) {
  const pending = ctx.pendingToolCalls.get(toolId);
  if (!pending) {
    // Loud warning so a malformed/out-of-order CLI stream surfaces in logs
    // instead of silently producing an "unknown" tool entry that masks bridge
    // bugs. Tests rely on this exact prefix.
    console.warn(`[BRIDGE] dispatchToolResult: no pending tool for id=${toolId} (using fallback "${fallbackToolName}")`);
  }
  const toolName = pending?.toolName || fallbackToolName;
  const durationMs = pending ? Date.now() - pending.startTime : null;

  const enrichedMetadata = { ...metadata };
  if (pending?.attachmentFileName) {
    enrichedMetadata.label = pending.attachmentFileName;
    enrichedMetadata.toolName = 'view_attachment';
  }

  ctx.toolResults.push({
    toolCall: { id: toolId, toolName, arguments: pending?.arguments || '{}' },
    success: !isError,
    content,
    error: isError ? (errorMsg || content) : null,
    durationMs,
    metadata: enrichedMetadata,
  });

  await ctx.publisher.publishToolResult(toolId, toolName, !isError, durationMs, content, enrichedMetadata);
  ctx.pendingToolCalls.delete(toolId);
}

/**
 * Increment the per-iteration turn counter. Used by every adapter (claude
 * via the assistant case, codex on turn.completed, gemini/mistral on the
 * shared assistant + turn_complete paths). Centralised so a future bug in
 * one adapter can't silently double-count or skip a turn.
 *
 * Note: distinct from `ctx.updateState({ numTurns: msg.num_turns })` which
 * adapters use at end-of-run to OVERRIDE with the canonical CLI total.
 */
export function incrementTurn(ctx) {
  ctx.updateState({ numTurns: (ctx.state?.numTurns || 0) + 1 });
}

/**
 * Handle a "flat" CLI message - a single typed envelope per event rather
 * than the nested item.* / assistant.content[] formats. Used by Gemini and
 * Mistral CLIs (any CLI that emits one of these top-level types):
 *
 *   content / text                          - direct text output
 *   content_block_delta / stream_event       - streaming text/thinking deltas
 *   tool_use / function_call / tool_call    - single tool invocation
 *   tool_result / function_response / tool_call_result - single tool result
 *   thinking / reasoning                     - single reasoning block
 *
 * Returns `true` if the message was handled, `false` otherwise so the caller
 * can fall through to its provider-specific branches (turn_complete, system,
 * result, error, ...).
 *
 * @param {object} msg
 * @param {object} ctx
 * @param {object} opts
 * @param {string} opts.providerKey - for synthetic ids
 * @returns {Promise<boolean>}
 */
export async function handleFlatCliMessage(msg, ctx, opts) {
  const { publisher, pendingToolCalls, thinkingSections, orderedEntries } = ctx;
  const providerKey = opts.providerKey;
  const synthId = () => synthIdFor(providerKey);

  // Direct text content
  if (msg.type === 'content' || msg.type === 'text') {
    const text = msg.text || msg.content || '';
    if (text) {
      await publisher.publishContent(text);
      ctx.updateState({ fullContent: ctx.getContent() + text });
    }
    return true;
  }

  // Streaming deltas
  if (msg.type === 'content_block_delta' || msg.type === 'stream_event') {
    const delta = msg.delta || msg.event?.delta;
    if (delta?.type === 'text_delta' && delta.text) {
      await publisher.publishContent(delta.text);
      ctx.updateState({ fullContent: ctx.getContent() + delta.text });
    }
    if (delta?.type === 'thinking_delta' && delta.thinking) {
      await publisher.publishThinking(delta.thinking);
      thinkingSections.push({ title: '', content: delta.thinking });
    }
    return true;
  }

  // Tool invocation
  if (msg.type === 'tool_use' || msg.type === 'function_call' || msg.type === 'tool_call') {
    const toolId = msg.id || msg.call_id || synthId();
    let toolName = msg.name || msg.tool || msg.function?.name || 'unknown';
    toolName = ctx.stripMcpPrefix(toolName);
    const argsStr = typeof msg.arguments === 'string'
      ? msg.arguments
      : JSON.stringify(msg.arguments || msg.args || msg.input || msg.function?.arguments || {});
    await dispatchToolCall(ctx, { toolId, toolName, argsStr });
    return true;
  }

  // Tool result
  if (msg.type === 'tool_result' || msg.type === 'function_response' || msg.type === 'tool_call_result') {
    const toolId = msg.call_id || msg.tool_use_id || msg.id;
    const isError = msg.is_error === true || msg.status === 'error';
    const rawContent = msg.output || msg.content || msg.result || msg.text || '';
    const { content: cleanContent, metadata } = ctx.extractToolResultAndMetadata(
      typeof rawContent === 'string' ? rawContent : JSON.stringify(rawContent)
    );
    await dispatchToolResult(ctx, {
      toolId,
      isError,
      content: cleanContent,
      metadata,
      fallbackToolName: msg.name || 'unknown',
    });
    return true;
  }

  // Thinking
  if (msg.type === 'thinking' || msg.type === 'reasoning') {
    const thinking = msg.text || msg.content || msg.thinking || '';
    if (thinking) {
      await publisher.publishThinking(thinking);
      thinkingSections.push({ title: '', content: thinking });
      orderedEntries.push({ type: 'thinking', title: '', content: thinking, timestamp: Date.now() });
    }
    return true;
  }

  return false;
}

/**
 * Build the stdin payload for CLIs that read the prompt from stdin (codex,
 * gemini, mistral). Three adapters had identical "prepend system prompt with
 * '\n\n---\n\n' separator" logic - centralised here so the separator can be
 * changed in one place if a CLI ever objects to it.
 */
export const PROMPT_SEPARATOR = '\n\n---\n\n';
export function buildStdinPayload(systemPrompt, prompt) {
  return systemPrompt ? `${systemPrompt}${PROMPT_SEPARATOR}${prompt}` : prompt;
}

/**
 * Handle a Claude-shaped `assistant` event for adapters that accept the
 * Anthropic block format (Gemini and Mistral CLIs proxy it). Iterates
 * `msg.message.content[]` and publishes text / tool_use / thinking blocks.
 *
 * Does NOT include Claude's own snapshot/pause_turn dedup machinery - those
 * are protocol quirks specific to the official `claude` CLI's
 * `--output-format stream-json --verbose`. Other CLIs that just emit a
 * single assistant block per turn don't need them.
 *
 * @param {object} msg - parsed NDJSON event with `msg.message.content[]`
 * @param {object} ctx - adapter context
 * @param {object} opts
 * @param {string} opts.providerKey - for synthetic ids ('gemini', 'mistral', ...)
 * @param {(usage: object, ctx: object) => void} [opts.recordUsage]
 */
export async function handleClaudeStyleAssistantMessage(msg, ctx, opts) {
  const { publisher, thinkingSections, orderedEntries } = ctx;
  const contentBlocks = msg.message?.content || [];
  const providerKey = opts.providerKey;
  const recordUsage = opts.recordUsage || (() => {});
  const synthId = () => synthIdFor(providerKey);

  if (msg.message?.model) ctx.updateState({ cliModel: msg.message.model });
  if (msg.message?.usage) recordUsage(msg.message.usage, ctx);

  for (const block of contentBlocks) {
    if (block.type === 'text' && block.text) {
      await publisher.publishContent(block.text);
      ctx.updateState({ fullContent: ctx.getContent() + block.text });
    }

    if (block.type === 'tool_use' || block.type === 'function_call') {
      const toolId = block.id || synthId();
      let toolName = block.name || block.tool || 'unknown';
      toolName = ctx.stripMcpPrefix(toolName);
      const argsStr = JSON.stringify(block.input || block.arguments || {});
      await dispatchToolCall(ctx, { toolId, toolName, argsStr });
    }

    if ((block.type === 'thinking' || block.type === 'reasoning') && (block.thinking || block.text)) {
      const thinking = block.thinking || block.text;
      await publisher.publishThinking(thinking);
      thinkingSections.push({ title: '', content: thinking });
      orderedEntries.push({ type: 'thinking', title: '', content: thinking, timestamp: Date.now() });
    }
  }

  incrementTurn(ctx);
}

/**
 * Handle the OpenAI-style `item.started` / `item.completed` event family.
 *
 * Used by Codex, Gemini, and Mistral CLIs (and any other CLI that emits the
 * same envelope). The three providers all had a near-byte-for-byte copy of
 * this 100-line switch - extracted here so a fix to e.g. tool-result
 * handling lands in one place. Provider-specific differences are exposed
 * via the `opts` parameter; codex layers its own `command_execution` /
 * `web_search` handlers on top of this helper rather than inside it.
 *
 * @param {object} msg - parsed NDJSON event with `type` in {'item.started','item.completed'}
 * @param {object} ctx - adapter context (publisher, pendingToolCalls, …)
 * @param {object} opts
 * @param {string} opts.providerKey - log prefix and synthetic id namespace ('codex' | 'gemini' | 'mistral')
 * @param {(usage: object, ctx: object) => void} [opts.recordUsage] - provider-specific usage recorder
 *   (called for `agent_message`/`message` items that carry usage). Defaults to no-op.
 */
export async function handleCodexStyleItemEvent(msg, ctx, opts) {
  const { publisher, pendingToolCalls, thinkingSections, orderedEntries } = ctx;
  const item = msg.item;
  if (!item) return;
  const providerKey = opts.providerKey;
  const recordUsage = opts.recordUsage || (() => {});
  const synthId = () => synthIdFor(providerKey);

  // `item.started`: register pending + publish, but DO NOT append to
  // `orderedEntries` - the canonical replay log entry is appended once on
  // `item.completed` to avoid double-counting (started+completed for the
  // same tool would otherwise show up twice). Hence the manual `set` +
  // `publish` here instead of `dispatchToolCall` (which always appends).
  if (msg.type === 'item.started') {
    if ((item.type === 'mcp_tool_call' || item.type === 'function_call') && (item.tool || item.name)) {
      const toolId = item.id || item.call_id || synthId();
      let toolName = item.tool || item.name || item.function?.name || 'unknown';
      toolName = ctx.stripMcpPrefix(toolName);
      const argsStr = typeof item.arguments === 'string'
        ? item.arguments
        : JSON.stringify(item.arguments || item.function?.arguments || {});
      pendingToolCalls.set(toolId, { toolName, arguments: argsStr, startTime: Date.now() });
      await publisher.publishToolCall(toolName, toolId, argsStr);
    }
    return;
  }

  // item.completed
  switch (item.type) {
    case 'agent_message':
    case 'message': {
      const text = item.content?.[0]?.text || item.text || '';
      if (text) {
        await publisher.publishContent(text);
        ctx.updateState({ fullContent: ctx.getContent() + text });
      }
      if (item.model) ctx.updateState({ cliModel: item.model });
      if (item.usage) recordUsage(item.usage, ctx);
      break;
    }

    case 'mcp_tool_call':
    case 'function_call': {
      const toolId = item.id || item.call_id || synthId();
      let toolName = item.tool || item.name || item.function?.name || 'unknown';
      toolName = ctx.stripMcpPrefix(toolName);
      const argsStr = typeof item.arguments === 'string'
        ? item.arguments
        : JSON.stringify(item.arguments || item.function?.arguments || {});
      const isError = item.status === 'failed' || item.error != null;
      const errorMsg = item.error?.message || null;
      const resultContent = item.result || '';

      if (item.status === 'failed' || item.result != null) {
        const { content: cleanContent, metadata } = ctx.extractToolResultAndMetadata(
          typeof resultContent === 'string' ? resultContent : JSON.stringify(resultContent)
        );
        const alreadyStarted = pendingToolCalls.has(toolId);
        if (!alreadyStarted) {
          // Record the call so the replay log + UI see it before the result.
          pendingToolCalls.set(toolId, { toolName, arguments: argsStr, startTime: Date.now() });
          orderedEntries.push({ type: 'tool_call', id: toolId, toolName, arguments: argsStr, timestamp: Date.now() });
          await publisher.publishToolCall(toolName, toolId, argsStr);
        }
        await dispatchToolResult(ctx, {
          toolId,
          isError,
          content: isError ? errorMsg : cleanContent,
          errorMsg: isError ? errorMsg : null,
          metadata,
          fallbackToolName: toolName,
        });
      } else {
        // Started without a result yet - register pending and broadcast.
        pendingToolCalls.set(toolId, { toolName, arguments: argsStr, startTime: Date.now() });
        orderedEntries.push({ type: 'tool_call', id: toolId, toolName, arguments: argsStr, timestamp: Date.now() });
        await publisher.publishToolCall(toolName, toolId, argsStr);
      }
      break;
    }

    case 'mcp_tool_call_result':
    case 'function_call_output': {
      const toolId = item.call_id || item.tool_use_id || item.id;
      const isError = item.is_error === true || item.status === 'error';
      const rawContent = item.output || item.content || item.text || '';
      const { content: cleanContent, metadata } = ctx.extractToolResultAndMetadata(rawContent);
      await dispatchToolResult(ctx, {
        toolId,
        isError,
        content: cleanContent,
        metadata,
        fallbackToolName: item.name || 'unknown',
      });
      break;
    }

    case 'reasoning': {
      const thinking = item.content?.[0]?.text || item.text || '';
      if (thinking) {
        await publisher.publishThinking(thinking);
        thinkingSections.push({ title: '', content: thinking });
        orderedEntries.push({ type: 'thinking', title: '', content: thinking, timestamp: Date.now() });
      }
      break;
    }

    default:
      console.log(`[BRIDGE:${providerKey}:item] unknown item.type=${item.type}`);
      break;
  }
}
