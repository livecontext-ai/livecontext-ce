package com.apimarketplace.agent.loop;

import com.apimarketplace.agent.domain.*;
import com.apimarketplace.agent.logging.AgentLogger;
import com.apimarketplace.agent.logging.LlmTurnInstrumentation;
import com.apimarketplace.agent.provider.LLMProvider;
import com.apimarketplace.agent.provider.LLMProviderException;
import com.apimarketplace.agent.retry.RetryPolicy;
import com.apimarketplace.agent.streaming.StreamingCallback;
import com.apimarketplace.agent.tool.ToolExecutionService;
import com.apimarketplace.agent.tools.common.ToolMediaMetadata;
import com.apimarketplace.common.web.TenantResolver;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

/**
 * Executes the agent loop with shared logic for both sync and streaming modes.
 * Follows Single Responsibility Principle - handles loop execution only.
 */
@Slf4j
@RequiredArgsConstructor
public class AgentLoopExecutor {

    // Loop detection - tracked tools that can cause infinite loops
    private static final Set<String> LOOP_TRACKED_TOOLS = Set.of(
        "catalog", "interface", "table", "workflow"
    );

    // Tools that must execute sequentially (stateful, order-dependent)
    private static final Set<String> SEQUENTIAL_ONLY_TOOLS = Set.of("workflow", "application");

    // Context monitoring thresholds
    private static final int CONTEXT_WARNING_TOKENS = 20000;
    private static final int CONTEXT_MAX_TOKENS = 50000;
    private static final int CHARS_PER_TOKEN = 4;

    // Tool result truncation
    private static final int MAX_TOOL_RESULT_LENGTH = 1000;
    private static final int TRUNCATED_RESULT_LENGTH = 300;
    private static final String TASK_ID_CREDENTIAL = "__taskId__";
    private static final String STOP_AGENT_LOOP_METADATA = "stopAgentLoop";

    private final ToolExecutionService toolExecutionService;
    private final AgentLogger agentLogger;
    private final ExecutorService toolExecutor;
    private final long defaultToolTimeoutMs;
    private final boolean parallelToolExecution;

    /**
     * Optional retry policy for transient LLM provider failures (429/5xx). Wraps the
     * sync {@code provider.complete(request)} call. Streaming retry is deferred - the
     * callback-driven flow makes restart-from-scratch costly. Tests can leave this
     * null to skip retry behavior.
     */
    @Setter
    private RetryPolicy retryPolicy;

    /**
     * Process a single iteration of the loop (common logic for sync/streaming).
     * Returns true if loop should continue, false if complete.
     */
    public IterationResult processIteration(
            LLMProvider provider,
            String model,
            AgentLoopContext context,
            List<ToolDefinition> tools,
            LoopExecutionState state,
            String systemPrompt,
            StreamingCallback callback) {

        long iterationStart = System.currentTimeMillis();
        state.incrementIterations();
        state.setCurrentState(AgentState.CALLING_LLM);

        // Last iteration warning. WARN only for multi-iteration agents that have used
        // their full budget - those are the ones where seeing "last iteration" in logs
        // is a real signal (e.g. an agent ran out of tool-call budget mid-task). For
        // single-iteration agents (Classify by contract, maxIterations=1) every call
        // hits this branch by design, so emitting WARN every time would drown out the
        // real ones.
        if (state.isLastIteration()) {
            state.getMessages().add(Message.system(
                "[SYSTEM WARNING] This is your LAST iteration. You will not be able to call more tools after this. " +
                "If you haven't completed the user's request, inform them now about what's done and what remains."
            ));
            if (state.getMaxIterations() > 1) {
                log.warn("⚠️ [ITERATION {}] LAST ITERATION - warning added", state.getIterations());
            } else {
                log.debug("[ITERATION {}] Single-iteration agent reached its only iteration (maxIterations=1) - by contract", state.getIterations());
            }
        }

        // Build and send completion request
        CompletionRequest request = buildCompletionRequest(context, model, systemPrompt, state.getMessages(), tools, callback != null);

        log.info("🔄 [ITERATION {}] Starting with {} messages, {} tools (model: {})",
            state.getIterations(), state.getMessages().size(), tools != null ? tools.size() : 0, model);

        // Execute based on mode
        CompletionResponse response;
        List<ToolCall> pendingToolCalls;

        if (callback != null) {
            // Streaming mode
            StreamingCollector collector = new StreamingCollector(callback, state);
            provider.completeStreaming(request, collector);

            if (collector.hasError()) {
                // STOP wins over stream error: when the user cancelled while we
                // were streaming, the underlying socket may be torn down by the
                // hosting layer, surfacing here as a stream IOException. The
                // run is functionally STOPPED_BY_USER (the user asked us to
                // stop), not ERROR - observability would otherwise paint
                // user-stops as failures in the agent-fleet dashboard.
                if (callback.shouldStop()) {
                    log.info("⏹️ [CANCEL] Stream error after STOP signal - classifying as STOPPED_BY_USER, not ERROR");
                    state.setCurrentState(AgentState.COMPLETED);
                    state.setStopReason(AgentStopReason.STOPPED_BY_USER);
                    return IterationResult.cancelled();
                }
                return IterationResult.error(collector.getLastError());
            }

            response = collector.getResponse();
            pendingToolCalls = collector.getPendingToolCalls();
        } else {
            // Sync mode - wrap with RetryPolicy when available for transient 429/5xx recovery.
            // The policy honors LLMProviderException.isRetryable() and retryAfter() hints
            // (Retry-After header / Google body retryDelay / message regex). Streaming path
            // (above) is intentionally not wrapped - restart-from-scratch on a partial
            // SSE stream is costly; deferred.
            CompletionRequest finalRequest = request;
            if (retryPolicy != null) {
                response = retryPolicy.execute(
                    "llm.complete." + provider.getProviderName(),
                    () -> provider.complete(finalRequest),
                    t -> t instanceof LLMProviderException ex && ex.isRetryable(),
                    t -> t instanceof LLMProviderException ex ? ex.retryAfter() : Optional.empty()
                );
            } else {
                response = provider.complete(finalRequest);
            }
            pendingToolCalls = response.hasToolCalls() ? response.toolCalls() : List.of();
        }

        state.setLastResponse(response);
        state.trackUsage(response.usage());
        state.recordFinishReason(response.finishReason());

        // Stage 0.2 instrumentation - structured [LLM_TURN] log line for telemetry
        LlmTurnInstrumentation.logTurn(context, request, response.usage(), state.getIterations());

        // F1.2 - bail-out if STOP arrived during streaming. Without this, an
        // incomplete LLM response with no tool_calls would be classified as
        // COMPLETED (wrong) or proceed to tool execution (waste of credits).
        // Partial content is preserved in lastResponse for observability.
        if (callback != null && callback.shouldStop()) {
            log.info("⏹️ [CANCEL] Stop signal detected after stream at iteration {} (content={} chars, pending tools={})",
                state.getIterations(),
                response.content() != null ? response.content().length() : 0,
                pendingToolCalls != null ? pendingToolCalls.size() : 0);
            if (response.content() != null && !response.content().isBlank()) {
                state.getMessages().add(new Message(Message.Role.ASSISTANT, response.content(), null, null, null, null));
            }
            state.setCurrentState(AgentState.COMPLETED);
            state.setStopReason(AgentStopReason.STOPPED_BY_USER);
            return IterationResult.cancelled();
        }

        // Delta 3 - propagate streaming-level budget cap to the loop's stop reason.
        // AbstractLLMProvider sets finishReason="budget_exhausted" when the local completion-
        // token cap trips mid-stream. Without this mapping, the loop would fall through to
        // COMPLETED and observability would mis-classify a capped run as successful.
        if ("budget_exhausted".equals(response.finishReason())) {
            state.setCurrentState(AgentState.COMPLETED);
            state.setStopReason(AgentStopReason.BUDGET_EXHAUSTED);
            if (response.content() != null && !response.content().isBlank()) {
                state.getMessages().add(new Message(Message.Role.ASSISTANT, response.content(), null, null, null, null));
            }
            log.warn("Agent stopped by streaming budget cap - partial content preserved (iterations={}, content={} chars)",
                state.getIterations(), response.content() != null ? response.content().length() : 0);
            return IterationResult.complete();
        }

        // No tool calls = done
        if (pendingToolCalls.isEmpty()) {
            state.setCurrentState(AgentState.COMPLETED);
            state.setStopReason(AgentStopReason.COMPLETED);
            // Add final assistant response to conversation history for observability
            if (response.content() != null && !response.content().isBlank()) {
                state.getMessages().add(new Message(Message.Role.ASSISTANT, response.content(), null, null, null, null));
            }
            int contentLength = response.content() != null ? response.content().length() : 0;
            log.info("Agent completed - iterations={}, contentLength={}, promptTokens={}, completionTokens={}",
                state.getIterations(), contentLength, state.getTotalPromptTokens(), state.getTotalCompletionTokens());
            if (contentLength == 0) {
                log.warn("⚠️ Agent completed with NO visible content! finishReason={}", response.finishReason());
            }
            return IterationResult.complete();
        }

        // Check loop detection
        LoopCheckResult loopCheck = checkForLoops(pendingToolCalls, state);

        if (loopCheck.shouldStop()) {
            return handleLoopStop(provider, model, context, systemPrompt, state, pendingToolCalls, loopCheck, callback);
        }

        // Execute tool calls
        state.setCurrentState(AgentState.EXECUTING_TOOLS);
        state.recordToolCallCount(pendingToolCalls.size());
        agentLogger.logIteration(state.getRunId(), state.getIterations(), pendingToolCalls.size());

        List<ToolResult> toolResults = executeToolCalls(pendingToolCalls, tools, context, state, callback);
        state.addToolResults(toolResults);

        // Notify callback of tool results
        if (callback != null) {
            for (ToolResult result : toolResults) {
                callback.onToolResult(result);
            }
        }

        // Add messages for tool calls and results - even on cancel, we persist
        // what executed so the conversation history stays coherent (no orphan
        // tool_use without tool_result, which would break the next message
        // against Anthropic's API validation).
        state.getMessages().add(Message.assistantWithToolCalls(
            response.content() != null ? response.content() : "", pendingToolCalls));
        addToolResultMessages(state, toolResults, provider.supportsImageAttachments());

        // F1.2 - STOP during tool execution: persist results we got, bail out.
        // Sequential loop already breaks early; parallel branch lets in-flight
        // futures finish (no force-cancel here - that's Phase 4 with disposable
        // registry). What matters is we don't loop into another LLM call.
        if (callback != null && callback.shouldStop()) {
            log.info("⏹️ [CANCEL] Stop signal detected after tools at iteration {} (got {} of {} tool results)",
                state.getIterations(), toolResults.size(), pendingToolCalls.size());
            state.setCurrentState(AgentState.COMPLETED);
            state.setStopReason(AgentStopReason.STOPPED_BY_USER);
            return IterationResult.cancelled();
        }

        if (shouldStopAfterTaskTurnDecision(context, toolResults)) {
            log.info("[TASK TURN] Task decision tool completed; stopping loop after iteration {}",
                state.getIterations());
            state.setCurrentState(AgentState.COMPLETED);
            state.setStopReason(AgentStopReason.COMPLETED);
            return IterationResult.complete();
        }

        // Add loop warnings
        for (String warning : loopCheck.warnings()) {
            state.getMessages().add(Message.system(warning));
        }

        long duration = System.currentTimeMillis() - iterationStart;
        state.recordIterationDuration(duration);
        monitorContextSize(state.getMessages(), state.getIterations());

        return IterationResult.continueLoop();
    }

    private boolean shouldStopAfterTaskTurnDecision(AgentLoopContext context, List<ToolResult> toolResults) {
        if (!hasTaskExecutionContext(context) || toolResults == null || toolResults.isEmpty()) {
            return false;
        }
        for (ToolResult result : toolResults) {
            if (result != null
                    && result.success()
                    && result.metadata() != null
                    && Boolean.TRUE.equals(result.metadata().get(STOP_AGENT_LOOP_METADATA))) {
                return true;
            }
        }
        return false;
    }

    private boolean hasTaskExecutionContext(AgentLoopContext context) {
        if (context == null || context.credentials() == null) {
            return false;
        }
        Object taskId = context.credentials().get(TASK_ID_CREDENTIAL);
        return taskId instanceof String s && !s.isBlank();
    }

    // Package-private (visible for testing): AgentLoopExecutorRequestWiringTest
    // pins that context fields reach the CompletionRequest unchanged.
    CompletionRequest buildCompletionRequest(
            AgentLoopContext context, String model, String systemPrompt,
            List<Message> messages, List<ToolDefinition> tools, boolean stream) {
        // Stage 1a.1 - pass both the legacy string and the layered block list.
        // ClaudeProvider honors systemBlocks (native cache_control breakpoints);
        // other providers fall back to the concatenated systemPrompt string.
        return CompletionRequest.builder()
            .tenantId(context.tenantId())
            .model(model)
            .systemPrompt(systemPrompt)
            .systemBlocks(context.systemBlocks())
            .userPrompt(context.userPrompt())
            .conversationHistory(messages)
            .temperature(context.temperature())
            .maxTokens(context.maxTokens())
            .topP(context.topP())
            .tools(tools)
            .stream(stream)
            .purpose(context.purpose())
            .thinkingLevel(resolveAdaptiveThinkingLevel(context, tools))
            // Resolved effort rides on the DIRECT-API request too: ClaudeProvider
            // maps it to Anthropic output_config.effort on supporting models.
            // Bridge providers get it via their own dispatch DTO; other direct
            // providers ignore the field.
            .reasoningEffort(context.reasoningEffort())
            .build();
    }

    /**
     * Stage 1b.1 - resolve the adaptive {@link ThinkingLevel} at this single
     * chokepoint. Every LLM call in the platform flows through
     * {@link #buildCompletionRequest}, so wiring the AUTO mapping here guarantees
     * that CLASSIFY/GUARDRAIL routes always get {@link ThinkingLevel#MEDIUM}
     * (never HIGH - they would burn reasoning tokens on fast-path extraction),
     * and MAIN routes adapt to turn shape (tool count + user-message length).
     *
     * <p><b>Caller-pinned override.</b> When {@link AgentLoopContext#thinkingLevel()}
     * is non-null, it wins unconditionally - this is how conversation-scoped
     * callers (e.g. {@code AgentContextBuilder}) keep the Anthropic prompt cache
     * warm on Claude by pinning the first MAIN turn's tier for the rest of the
     * conversation (the {@code thinking} parameter invalidates system+messages
     * cache when it flips). Providers whose thinking knob does NOT invalidate
     * cache (Gemini's {@code generationConfig.thinkingConfig}, OpenAI which
     * ignores the field) leave it null and let this method auto-resolve per
     * iteration.
     *
     * <p>Turn-shape inputs (used only when no caller override is provided):
     * <ul>
     *   <li>{@code toolCount} - size of the {@code tools} list on the current
     *   iteration (0 when the caller attaches none; CLASSIFY/GUARDRAIL always
     *   pass {@code null}). The loop-stop "final call without tools" (see
     *   {@link #processIteration} stop branch) legitimately passes
     *   {@code List.of()}, which downgrades MAIN short-prompt turns to LOW -
     *   correct: no tools, short prompt, no reasoning headroom needed.</li>
     *   <li>{@code userMsgChars} - character length of the <em>initial</em>
     *   {@code context.userPrompt()}. It does not grow with conversation
     *   history: the heuristic is "was the user's ask shallow?", measured
     *   once at loop entry. Treated as 0 when unset so unseeded turns do not
     *   accidentally trip the {@code ≥50 chars} threshold.</li>
     * </ul>
     *
     * <p>Never returns {@code null} - either the override is honored, or
     * {@link ThinkingLevel#auto(CallPurpose, int, int)} always resolves to
     * a tier.
     *
     * <p>Package-private for direct unit-testing in
     * {@code AgentLoopExecutorAdaptiveThinkingLevelTest}.
     */
    static ThinkingLevel resolveAdaptiveThinkingLevel(
            AgentLoopContext context, List<ToolDefinition> tools) {
        // Caller-pinned override wins - used by conversation-service for Claude
        // to keep Anthropic prompt cache warm across turns. Auto-resolution only
        // kicks in when the caller hasn't pinned a tier.
        if (context.thinkingLevel() != null) {
            return context.thinkingLevel();
        }
        int toolCount = tools != null ? tools.size() : 0;
        int userMsgChars = context.userPrompt() != null ? context.userPrompt().length() : 0;
        return ThinkingLevel.auto(context.purpose(), toolCount, userMsgChars);
    }

    private LoopCheckResult checkForLoops(List<ToolCall> toolCalls, LoopExecutionState state) {
        List<String> warnings = new ArrayList<>();
        boolean shouldStopIdentical = false;
        boolean shouldStopConsecutive = false;
        String stoppingToolName = null;

        List<ToolCall> trackedCalls = toolCalls.stream()
            .filter(tc -> LOOP_TRACKED_TOOLS.contains(tc.toolName()))
            .collect(Collectors.toList());

        // Check identical calls
        for (ToolCall toolCall : trackedCalls) {
            LoopDetector.DetectionResult detection = state.getLoopDetector().recordToolCall(toolCall);
            int callCount = state.getLoopDetector().getCallCount(toolCall);

            if (detection == LoopDetector.DetectionResult.STOP) {
                shouldStopIdentical = true;
                stoppingToolName = toolCall.toolName();
                warnings.add(state.getLoopDetector().generateStopMessage(toolCall, callCount));
                log.error("🛑 [IDENTICAL LOOP] Tool: {}, count: {}", toolCall.toolName(), callCount);
            } else if (detection == LoopDetector.DetectionResult.WARN) {
                warnings.add(state.getLoopDetector().generateWarningMessage(toolCall, callCount));
            }
        }

        // Check consecutive calls
        for (int i = 0; i < trackedCalls.size(); i++) {
            LoopDetector.ConsecutiveResult consecutive = state.getLoopDetector().recordConsecutiveCall();

            if (consecutive == LoopDetector.ConsecutiveResult.STOP) {
                shouldStopConsecutive = true;
                String msg = state.getLoopDetector().generateConsecutiveMessage(consecutive);
                if (msg != null && !warnings.contains(msg)) {
                    warnings.add(msg);
                }
                break;
            } else if (consecutive != LoopDetector.ConsecutiveResult.OK) {
                String msg = state.getLoopDetector().generateConsecutiveMessage(consecutive);
                if (msg != null && !warnings.contains(msg)) {
                    warnings.add(msg);
                }
            }
        }

        return new LoopCheckResult(shouldStopIdentical, shouldStopConsecutive, stoppingToolName, warnings);
    }

    private IterationResult handleLoopStop(
            LLMProvider provider, String model, AgentLoopContext context,
            String systemPrompt, LoopExecutionState state,
            List<ToolCall> pendingToolCalls, LoopCheckResult loopCheck,
            StreamingCallback callback) {

        log.warn("🛑 [HARD STOP] Giving LLM final chance to respond");

        // Add context for cancelled tool calls
        state.getMessages().add(Message.assistantWithToolCalls("", pendingToolCalls));
        for (ToolCall tc : pendingToolCalls) {
            state.getMessages().add(Message.toolResult(tc.id(), tc.toolName(),
                "[CANCELLED] Tool execution stopped due to loop detection."));
        }

        String stopInstruction = String.format(
            "[STOP] %s (%d calls). Tools NOT executed. RESPOND NOW: summarize results.",
            loopCheck.isIdentical() ? "Identical calls" : "Call limit",
            state.getLoopDetector().getTotalConsecutiveCalls()
        );
        state.getMessages().add(Message.system(stopInstruction));

        // Final LLM call without tools
        CompletionRequest finalRequest = buildCompletionRequest(
            context, model, systemPrompt, state.getMessages(), List.of(), callback != null);

        CompletionResponse loopFinalResponse;
        if (callback != null) {
            StreamingCollector collector = new StreamingCollector(callback, state);
            provider.completeStreaming(finalRequest, collector);
            loopFinalResponse = collector.getResponse();
        } else {
            loopFinalResponse = provider.complete(finalRequest);
            state.trackUsage(loopFinalResponse.usage());
        }
        state.setLastResponse(loopFinalResponse);

        // Add final assistant response to conversation history for observability
        if (loopFinalResponse.content() != null && !loopFinalResponse.content().isBlank()) {
            state.getMessages().add(new Message(Message.Role.ASSISTANT, loopFinalResponse.content(), null, null, null, null));
        }

        // F1.2 (audit P0.3) - if the user clicked STOP during this final
        // wrap-up call, honor STOPPED_BY_USER over MAX_ITERATIONS. Loop
        // detection still fires the final call, but the user signal wins
        // when classifying the run for observability/billing.
        if (callback != null && callback.shouldStop()) {
            log.info("⏹️ [CANCEL] Stop signal during loop-detection wrap-up - STOPPED_BY_USER overrides LOOP_DETECTED");
            state.setStopReason(AgentStopReason.STOPPED_BY_USER);
            state.setCurrentState(AgentState.COMPLETED);
            return IterationResult.cancelled();
        }

        state.setStopReason(AgentStopReason.MAX_ITERATIONS);
        state.setCurrentState(AgentState.COMPLETED);
        state.markLoopDetected(loopCheck.isIdentical(), loopCheck.stoppingToolName());

        return IterationResult.withLoopDetected();
    }

    private List<ToolResult> executeToolCalls(
            List<ToolCall> toolCalls, List<ToolDefinition> tools,
            AgentLoopContext context, LoopExecutionState state,
            StreamingCallback callback) {

        if (parallelToolExecution && toolCalls.size() >= 2) {
            return executeToolCallsParallel(toolCalls, tools, context, state, callback);
        }
        return executeToolCallsSequential(toolCalls, tools, context, state, callback);
    }

    private List<ToolResult> executeToolCallsSequential(
            List<ToolCall> toolCalls, List<ToolDefinition> tools,
            AgentLoopContext context, LoopExecutionState state,
            StreamingCallback callback) {

        List<ToolResult> results = new ArrayList<>();

        for (ToolCall toolCall : toolCalls) {
            // F1.2 - check between tools so a STOP arriving mid-batch stops further
            // tool executions. We intentionally do NOT interrupt the in-flight tool
            // (that's Phase 4 with the disposable registry); we only refuse to
            // start the next one. The caller (processIteration) sees shouldStop()
            // after this returns and bails the loop.
            if (callback != null && callback.shouldStop()) {
                log.info("⏹️ [CANCEL] Stop signal in sequential tool loop after {}/{} tools",
                    results.size(), toolCalls.size());
                break;
            }

            agentLogger.logToolCallStart(state.getRunId(), toolCall);
            long start = System.currentTimeMillis();

            ToolResult result = executeSingleToolCall(toolCall, tools, context);

            agentLogger.logToolCallEnd(state.getRunId(), toolCall, result, System.currentTimeMillis() - start);
            results.add(result);
            // The tool finished: the agent is alive and working. Reset the inactivity clock so a
            // sequence of legitimately long tool calls is never mistaken for a hung agent.
            if (callback != null) callback.onKeepAlive();
        }

        return results;
    }

    private List<ToolResult> executeToolCallsParallel(
            List<ToolCall> toolCalls, List<ToolDefinition> tools,
            AgentLoopContext context, LoopExecutionState state,
            StreamingCallback callback) {

        // Separate sequential-only calls (workflow, application) from parallelizable calls
        List<Integer> sequentialIndices = new ArrayList<>();
        List<Integer> regularIndices = new ArrayList<>();
        for (int i = 0; i < toolCalls.size(); i++) {
            ToolCall tc = toolCalls.get(i);
            if (SEQUENTIAL_ONLY_TOOLS.contains(tc.toolName())) {
                sequentialIndices.add(i);
            } else {
                regularIndices.add(i);
            }
        }

        // Pre-allocate results array to preserve original order
        ToolResult[] results = new ToolResult[toolCalls.size()];

        // F1.2 - sequential-only batch (workflow/application) honors STOP between
        // entries. Force-cancelling an in-flight tool requires Phase 4 (disposable
        // registry); here we only refuse to *start* the next one.
        for (int idx : sequentialIndices) {
            if (callback != null && callback.shouldStop()) {
                log.info("⏹️ [CANCEL] Stop signal in parallel-sequential pre-pass at idx {}/{}",
                    idx, toolCalls.size());
                break;
            }
            ToolCall toolCall = toolCalls.get(idx);
            agentLogger.logToolCallStart(state.getRunId(), toolCall);
            long start = System.currentTimeMillis();

            ToolResult result = executeSingleToolCall(toolCall, tools, context);

            agentLogger.logToolCallEnd(state.getRunId(), toolCall, result, System.currentTimeMillis() - start);
            results[idx] = result;
            // Tool finished - reset the inactivity clock (the agent is working, not hung).
            if (callback != null) callback.onKeepAlive();
        }

        // If only one regular call (or none), no parallelism needed
        if (regularIndices.size() <= 1) {
            for (int idx : regularIndices) {
                if (callback != null && callback.shouldStop()) {
                    log.info("⏹️ [CANCEL] Stop signal in parallel-fallback singletons at idx {}/{}",
                        idx, toolCalls.size());
                    break;
                }
                ToolCall toolCall = toolCalls.get(idx);
                agentLogger.logToolCallStart(state.getRunId(), toolCall);
                long start = System.currentTimeMillis();
                ToolResult result = executeSingleToolCall(toolCall, tools, context);
                agentLogger.logToolCallEnd(state.getRunId(), toolCall, result, System.currentTimeMillis() - start);
                results[idx] = result;
            }
        } else {
            // F1.2 - pre-flight cancel: if STOP arrived BEFORE we submit, skip the
            // whole parallel batch. Once submitted, futures all run to completion
            // (in-flight cancellation is a Phase 4 concern with disposables); this
            // is the strongest guarantee we can offer without that registry.
            if (callback != null && callback.shouldStop()) {
                log.info("⏹️ [CANCEL] Stop signal before parallel submit ({} regular tools skipped)",
                    regularIndices.size());
                // Fall through to the assembly step which will collect nulls in
                // the regularIndices slots - unfilled slots are treated as if the
                // tool never ran (no result row added below).
            } else {
            // Log start for all regular tool calls
            for (int idx : regularIndices) {
                agentLogger.logToolCallStart(state.getRunId(), toolCalls.get(idx));
            }

            log.info("⚡ [PARALLEL] Executing {} tool calls in parallel", regularIndices.size());

            // Submit all regular tool calls to thread pool
            @SuppressWarnings("unchecked")
            CompletableFuture<ToolResult>[] futures = new CompletableFuture[regularIndices.size()];
            long[] startTimes = new long[regularIndices.size()];

            for (int i = 0; i < regularIndices.size(); i++) {
                int idx = regularIndices.get(i);
                ToolCall toolCall = toolCalls.get(idx);
                long toolStart = System.currentTimeMillis();
                startTimes[i] = toolStart;

                ToolDefinition toolDef = tools != null ? tools.stream()
                    .filter(t -> t.name().equals(toolCall.toolName()))
                    .findFirst().orElse(null) : null;
                long timeout = toolDef != null ? toolDef.getEffectiveTimeoutMs(defaultToolTimeoutMs) : defaultToolTimeoutMs;

                futures[i] = CompletableFuture.supplyAsync(
                    () -> executeSingleToolCall(toolCall, tools, context), toolExecutor
                ).orTimeout(timeout, TimeUnit.MILLISECONDS).exceptionally(ex -> {
                    String errorMsg = ex instanceof TimeoutException
                        ? "Tool '" + toolCall.toolName() + "' timed out after " + timeout + "ms"
                        : "Parallel execution error: " + ex.getMessage();
                    return ToolResult.builder()
                        .toolCall(toolCall)
                        .success(false)
                        .error(errorMsg)
                        .durationMs(System.currentTimeMillis() - toolStart)
                        .build();
                });
            }

            // Wait for all to complete
            try {
                CompletableFuture.allOf(futures).join();
            } catch (Exception e) {
                log.warn("⚠️ [PARALLEL] Some tool calls failed: {}", e.getMessage());
            }

            // Collect results in original order
            for (int i = 0; i < regularIndices.size(); i++) {
                int idx = regularIndices.get(i);
                ToolCall toolCall = toolCalls.get(idx);
                long duration = System.currentTimeMillis() - startTimes[i];

                ToolResult result;
                try {
                    result = futures[i].get();
                    // Fill in duration if missing
                    if (result.durationMs() == null || result.durationMs() == 0) {
                        result = ToolResult.builder()
                            .toolCall(result.toolCall())
                            .success(result.success())
                            .content(result.content())
                            .error(result.error())
                            .durationMs(duration)
                            .metadata(result.metadata())
                            .build();
                    }
                } catch (Exception e) {
                    result = ToolResult.builder()
                        .toolCall(toolCall)
                        .success(false)
                        .error("Failed to get result: " + e.getMessage())
                        .durationMs(duration)
                        .build();
                }

                agentLogger.logToolCallEnd(state.getRunId(), toolCall, result, duration);
                results[idx] = result;
            }

            log.info("⚡ [PARALLEL] All {} tool calls completed", regularIndices.size());
            } // close: F1.2 cancel-pre-flight else
        }

        // F1.2 - drop unfilled slots (cancelled pre-submit) so the caller
        // doesn't see null entries. Sequential-only batch may also have
        // skipped some slots when STOP arrived mid-pre-pass.
        List<ToolResult> filtered = new ArrayList<>(results.length);
        for (ToolResult r : results) {
            if (r != null) filtered.add(r);
        }
        return filtered;
    }

    private ToolResult executeSingleToolCall(ToolCall toolCall, List<ToolDefinition> tools, AgentLoopContext context) {
        if (toolExecutionService == null) {
            return ToolResult.failure(toolCall, "Tool execution service not configured");
        }

        long startTime = System.currentTimeMillis();
        String toolName = toolCall.toolName();

        ToolDefinition toolDef = tools.stream()
            .filter(t -> t.name().equals(toolName))
            .findFirst()
            .orElse(null);

        if (toolDef == null) {
            List<String> available = tools.stream().map(ToolDefinition::name).collect(Collectors.toList());
            return ToolResult.failure(toolCall, "Tool not found: '" + toolName + "'. Available: " + available);
        }

        long timeout = toolDef.getEffectiveTimeoutMs(defaultToolTimeoutMs);
        Map<String, Object> credentials = enrichCredentials(context);

        try {
            CompletableFuture<ToolResult> future = CompletableFuture.supplyAsync(() -> {
                try {
                    return executeToolWithOrgScope(toolCall, toolDef, context.tenantId(), credentials);
                } catch (Exception e) {
                    return ToolResult.failure(toolCall, "Execution error: " + e.getMessage());
                }
            }, toolExecutor);

            ToolResult result = future.get(timeout, TimeUnit.MILLISECONDS);
            long duration = System.currentTimeMillis() - startTime;

            if (result.durationMs() == null) {
                return ToolResult.builder()
                    .toolCall(result.toolCall())
                    .success(result.success())
                    .content(result.content())
                    .error(result.error())
                    .durationMs(duration)
                    .metadata(result.metadata())
                    .build();
            }
            return result;

        } catch (TimeoutException e) {
            return ToolResult.builder()
                .toolCall(toolCall)
                .success(false)
                .error("Tool '" + toolName + "' timed out after " + timeout + "ms")
                .durationMs(System.currentTimeMillis() - startTime)
                .build();
        } catch (Exception e) {
            return ToolResult.builder()
                .toolCall(toolCall)
                .success(false)
                .error("Execution error: " + e.getMessage())
                .durationMs(System.currentTimeMillis() - startTime)
                .build();
        }
    }

    private ToolResult executeToolWithOrgScope(
            ToolCall toolCall,
            ToolDefinition toolDefinition,
            String tenantId,
            Map<String, Object> credentials) {
        ToolResult[] result = new ToolResult[1];
        TenantResolver.runWithOrgScope(organizationIdFromCredentials(credentials),
            () -> result[0] = toolExecutionService.executeTool(toolCall, toolDefinition, tenantId, credentials));
        return result[0];
    }

    private String organizationIdFromCredentials(Map<String, Object> credentials) {
        if (credentials == null) {
            return null;
        }
        Object orgId = credentials.get("__orgId__");
        return orgId instanceof String s && !s.isBlank() ? s : null;
    }

    private Map<String, Object> enrichCredentials(AgentLoopContext context) {
        Map<String, Object> enriched = new HashMap<>();
        if (context.credentials() != null) {
            enriched.putAll(context.credentials());
        }
        if (context.approvedServices() != null && !context.approvedServices().isEmpty()) {
            enriched.put("__approvedServices__", new ArrayList<>(context.approvedServices()));
        }
        return enriched;
    }

    private void addToolResultMessages(LoopExecutionState state, List<ToolResult> toolResults,
                                       boolean providerSupportsImages) {
        int total = toolResults.size();
        int index = 0;

        // Collect any vision media (images a tool produced for the model to SEE) to attach
        // AFTER all tool_result messages - never interleaved - so the OpenAI invariant
        // "assistant(tool_calls) → tool results, no other role between" still holds.
        List<MessageAttachment> visionImages = new ArrayList<>();

        for (ToolResult result : toolResults) {
            String content = result.success() ? result.content() : "Error: " + result.error();
            boolean isRecent = (total - index) <= 2;
            content = truncateIfNeeded(content, isRecent);

            state.getMessages().add(Message.toolResult(
                result.toolCall().id(),
                result.toolCall().toolName(),
                content
            ));

            if (result.success()) {
                visionImages.addAll(ToolMediaMetadata.toImageAttachments(result.metadata()));
            }
            index++;
        }

        // Direct-API vision: the tool_result text role cannot carry image bytes on most
        // providers (OpenAI/Gemini reject images in the tool role), so surface the images
        // on a synthetic USER message that the provider serialises to a native image
        // block - the API-mode equivalent of the bridge's __media__ → image block.
        //
        // Gated on the provider's attachment capability: a provider whose serialiser
        // DROPS attachments (DeepSeek, Mistral, OpenAI-compatible, cloud relay) must not
        // have the model told "shown below for you to see" about pixels it will never
        // receive - that misleading label makes the model hallucinate about an image it
        // cannot inspect. For those providers the images are skipped and logged.
        if (!visionImages.isEmpty()) {
            if (providerSupportsImages) {
                String label = visionImages.size() == 1
                    ? "[Image returned by the preceding tool call - shown below for you to see.]"
                    : "[" + visionImages.size() + " images returned by the preceding tool calls - shown below for you to see.]";
                state.getMessages().add(Message.userWithAttachments(label, visionImages));
                log.debug("[VISION] Attached {} tool-result image(s) as a synthetic user message", visionImages.size());
            } else {
                log.info("[VISION] Dropping {} tool-result image(s): the active provider does not serialise "
                    + "user-message image attachments (no native vision block wired)", visionImages.size());
            }
        }
    }

    private String truncateIfNeeded(String content, boolean isRecent) {
        if (content == null || isRecent || content.length() <= MAX_TOOL_RESULT_LENGTH) {
            return content;
        }
        return content.substring(0, TRUNCATED_RESULT_LENGTH) +
            "\n... [truncated " + (content.length() - TRUNCATED_RESULT_LENGTH) + " chars]";
    }

    private void monitorContextSize(List<Message> messages, int iteration) {
        int tokens = estimateTokens(messages);
        if (tokens >= CONTEXT_MAX_TOKENS) {
            log.error("🚨 [CONTEXT CRITICAL] {} tokens at iteration {}", tokens, iteration);
        } else if (tokens >= CONTEXT_WARNING_TOKENS) {
            log.warn("⚠️ [CONTEXT WARNING] {} tokens at iteration {}", tokens, iteration);
        }
    }

    private int estimateTokens(List<Message> messages) {
        int chars = 0;
        for (Message msg : messages) {
            if (msg.content() != null) chars += msg.content().length();
            if (msg.toolCalls() != null) {
                for (ToolCall tc : msg.toolCalls()) {
                    chars += tc.toolName().length();
                    if (tc.arguments() != null) chars += tc.arguments().toString().length();
                }
            }
        }
        return chars / CHARS_PER_TOKEN;
    }

    // Helper classes
    public record IterationResult(boolean shouldContinue, boolean isComplete, boolean isError, boolean loopDetected,
                                  boolean isCancelled, String errorMessage) {
        public static IterationResult continueLoop() { return new IterationResult(true, false, false, false, false, null); }
        public static IterationResult complete() { return new IterationResult(false, true, false, false, false, null); }
        public static IterationResult error() { return new IterationResult(false, false, true, false, false, null); }
        // Streaming-side errors land here with the verbatim provider/transport message.
        // Without this, processIteration → IterationResult.error() lost the error string and
        // AgentLoopService.executeLoop built an AgentLoopResult with success=false but error=null,
        // so the orchestrator's wrapper fell back to the generic "Async agent execution failed".
        public static IterationResult error(String errorMessage) { return new IterationResult(false, false, true, false, false, errorMessage); }
        public static IterationResult withLoopDetected() { return new IterationResult(false, false, false, true, false, null); }
        // F1.2 - explicit cancel variant. Distinct from error()/complete() so the
        // outer AgentLoopService classifies the run with stopReason=STOPPED_BY_USER
        // (not ERROR / not COMPLETED) and observability bills the partial work
        // correctly. Returned when callback.shouldStop() trips between the LLM
        // stream and tool exec, or between sequential tools.
        public static IterationResult cancelled() { return new IterationResult(false, false, false, false, true, null); }
    }

    public record LoopCheckResult(boolean isIdentical, boolean isConsecutive, String stoppingToolName, List<String> warnings) {
        boolean shouldStop() { return isIdentical || isConsecutive; }
    }

    /**
     * Collects streaming responses into usable data.
     * Package-private for direct unit-testing of callback delegation
     * (notably {@link #shouldStop()} which routes the cancel signal from
     * the conversation Redis cancel key down to the LLM provider).
     */
    static class StreamingCollector implements StreamingCallback {
        private final StreamingCallback delegate;
        private final LoopExecutionState state;
        private final StringBuilder content = new StringBuilder();
        private final List<ToolCall> pendingToolCalls = new ArrayList<>();
        private boolean hasError = false;
        // Captures the verbatim error string passed to onError() so IterationResult.error
        // can carry it up to AgentLoopService.executeLoop and into AgentLoopResult.error.
        // Without this, streaming errors (provider misconfigured, rate limited, transport
        // failure) were lost between the callback and the orchestrator's response payload.
        private String lastError;
        private CompletionResponse response;

        StreamingCollector(StreamingCallback delegate, LoopExecutionState state) {
            this.delegate = delegate;
            this.state = state;
        }

        @Override
        public void onChunk(String chunk) {
            if (shouldStop()) {
                return;
            }
            content.append(chunk);
            state.appendContent(chunk);
            delegate.onChunk(chunk);
        }

        @Override
        public void onThinking(String thinking) {
            delegate.onThinking(thinking);
        }

        @Override
        public void onToolCall(ToolCall toolCall) {
            pendingToolCalls.add(toolCall);
            delegate.onToolCall(toolCall);
        }

        @Override
        public void onToolResult(ToolResult result) {
            delegate.onToolResult(result);
        }

        @Override
        public void onComplete(CompletionResponse resp) {
            this.response = resp;
            if (resp.hasToolCalls() && pendingToolCalls.isEmpty()) {
                pendingToolCalls.addAll(resp.toolCalls());
            }
        }

        @Override
        public void onError(String error) {
            hasError = true;
            lastError = error;
            delegate.onError(error);
        }

        @Override
        public void onError(String error, Throwable exception) {
            hasError = true;
            // Fall back to exception.getMessage() when caller passed null for the
            // string - keeps the orchestrator's user-facing failure message
            // populated when a future provider routes a typed exception only.
            lastError = error != null ? error : (exception != null ? exception.getMessage() : null);
            delegate.onError(error, exception);
        }

        // F1.1 - without this delegation, providers calling callback.shouldStop()
        // during streaming always see false (the interface default), so the
        // Redis cancel key set by the conversation STOP is silently ignored
        // and the LLM keeps streaming until natural completion.
        @Override
        public boolean shouldStop() {
            try {
                return delegate.shouldStop();
            } catch (Exception e) {
                return false;
            }
        }

        @Override
        public long getCompletionTokenBudget() {
            return delegate.getCompletionTokenBudget();
        }

        // Must delegate so the inactivity window set by AgentLoopService.execute reaches the
        // provider through this collector; without it the provider sees the interface default (-1)
        // and never tightens the streaming socket timeout, so a fully-silent stream is not caught.
        @Override
        public long getInactivityTimeoutMs() {
            return delegate.getInactivityTimeoutMs();
        }

        @Override
        public void onKeepAlive() {
            delegate.onKeepAlive();
        }

        boolean hasError() { return hasError; }
        String getLastError() { return lastError; }
        List<ToolCall> getPendingToolCalls() { return pendingToolCalls; }
        CompletionResponse getResponse() {
            return response != null ? response : CompletionResponse.builder().content(content.toString()).build();
        }
    }
}
