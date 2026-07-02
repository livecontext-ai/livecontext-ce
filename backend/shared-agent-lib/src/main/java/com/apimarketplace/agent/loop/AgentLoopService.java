package com.apimarketplace.agent.loop;

import com.apimarketplace.agent.bridge.BridgeAccessDeniedException;
import com.apimarketplace.agent.cloud.RuntimeLlmProviderResolver;
import com.apimarketplace.agent.domain.*;
import com.apimarketplace.agent.loop.GuardResult;
import com.apimarketplace.agent.loop.IterationContext;
import com.apimarketplace.agent.loop.PreIterationGuard;
import com.apimarketplace.agent.factory.LLMProviderFactory;
import com.apimarketplace.agent.logging.AgentLogger;
import com.apimarketplace.agent.prompt.DefaultSystemPrompts;
import com.apimarketplace.agent.provider.LLMProvider;
import com.apimarketplace.agent.provider.LLMProviderException;
import com.apimarketplace.agent.retry.RetryPolicy;
import com.apimarketplace.agent.streaming.InactivityWatchdogCallback;
import com.apimarketplace.agent.streaming.StreamingCallback;
import com.apimarketplace.agent.tool.ToolDiscoveryService;
import com.apimarketplace.agent.tool.ToolExecutionService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Main service for executing agent loops.
 * Orchestrates: prompt → LLM → tool calls → execute → repeat.
 *
 * Supports both synchronous execution (for workflows) and
 * streaming execution (for conversations).
 *
 * Refactored to delegate loop execution to AgentLoopExecutor.
 * Follows Single Responsibility Principle - handles service lifecycle and entry points.
 */
@Slf4j
@Service("sharedAgentLoopService")
public class AgentLoopService {

    private final LLMProviderFactory providerFactory;
    private final ToolExecutionService toolExecutionService;
    private final ToolDiscoveryService toolDiscoveryService;
    private final AgentLogger agentLogger;
    private final RetryPolicy retryPolicy;
    private final RuntimeLlmProviderResolver providerResolver;
    private AgentLoopExecutor loopExecutor;

    private final ExecutorService toolExecutor = Executors.newCachedThreadPool(
        r -> {
            Thread t = new Thread(r, "agent-tool-" + System.nanoTime());
            t.setDaemon(true);
            return t;
        }
    );

    @Value("${agent.tool.default-timeout-ms:30000}")
    private long defaultToolTimeoutMs;

    @Value("${agent.tool.parallel-execution:true}")
    private boolean parallelToolExecution;

    /**
     * Backward-compat 4-arg constructor for existing test fixtures and any external
     * caller that doesn't yet wire a {@link RetryPolicy}. Defaults retryPolicy to null -
     * the executor falls back to direct provider.complete() calls without retry.
     */
    public AgentLoopService(
            LLMProviderFactory providerFactory,
            ToolExecutionService toolExecutionService,
            ToolDiscoveryService toolDiscoveryService,
            AgentLogger agentLogger) {
        this(providerFactory, toolExecutionService, toolDiscoveryService, agentLogger, null, null);
    }

    public AgentLoopService(
            LLMProviderFactory providerFactory,
            ToolExecutionService toolExecutionService,
            ToolDiscoveryService toolDiscoveryService,
            AgentLogger agentLogger,
            RetryPolicy retryPolicy) {
        this(providerFactory, toolExecutionService, toolDiscoveryService, agentLogger, retryPolicy, null);
    }

    @Autowired
    public AgentLoopService(
            LLMProviderFactory providerFactory,
            @Autowired(required = false) ToolExecutionService toolExecutionService,
            @Autowired(required = false) ToolDiscoveryService toolDiscoveryService,
            @Autowired(required = false) AgentLogger agentLogger,
            @Autowired(required = false) RetryPolicy retryPolicy,
            @Autowired(required = false) RuntimeLlmProviderResolver providerResolver) {
        this.providerFactory = providerFactory;
        this.toolExecutionService = toolExecutionService;
        this.toolDiscoveryService = toolDiscoveryService;
        this.agentLogger = agentLogger != null ? agentLogger : AgentLogger.NOOP;
        this.retryPolicy = retryPolicy;
        this.providerResolver = providerResolver;

        // Temporary executor with defaults - replaced in @PostConstruct with real @Value config
        this.loopExecutor = new AgentLoopExecutor(
            toolExecutionService, this.agentLogger, toolExecutor, 30000L, true
        );
        if (retryPolicy != null) {
            this.loopExecutor.setRetryPolicy(retryPolicy);
        }
    }

    @PostConstruct
    void init() {
        this.loopExecutor = new AgentLoopExecutor(
            toolExecutionService, agentLogger, toolExecutor, defaultToolTimeoutMs, parallelToolExecution
        );
        if (retryPolicy != null) {
            this.loopExecutor.setRetryPolicy(retryPolicy);
        }
        log.info("AgentLoopExecutor initialized: timeout={}ms, parallelExecution={}, retryPolicy={}",
            defaultToolTimeoutMs, parallelToolExecution, retryPolicy != null ? "enabled" : "disabled");
    }

    @PreDestroy
    public void shutdown() {
        log.info("Shutting down agent loop thread pool...");
        toolExecutor.shutdown();
        try {
            if (!toolExecutor.awaitTermination(30, TimeUnit.SECONDS)) {
                log.warn("Thread pool did not terminate gracefully, forcing shutdown");
                toolExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            toolExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        log.info("Agent loop thread pool shutdown complete");
    }

    /**
     * Execute agent loop synchronously.
     */
    public AgentLoopResult execute(AgentLoopContext context) {
        return execute(context, null);
    }

    /**
     * Execute agent loop synchronously with optional callback for real-time tool call tracking.
     *
     * @param context  The agent loop context
     * @param callback Optional callback for tool call events (can be null)
     * @return The execution result
     */
    public AgentLoopResult execute(AgentLoopContext context, StreamingCallback callback) {
        long startTime = System.currentTimeMillis();
        String providerName = context.provider();
        String runId = context.runId() != null ? context.runId() : UUID.randomUUID().toString();

        try {
            if (providerName == null) {
                providerName = resolveDefaultProviderName(context);
            }
            LLMProvider provider = resolveProvider(providerName, context);
            String model = context.model() != null ? context.model() : provider.getDefaultModel();

            agentLogger.logExecutionStart(runId, context.userPrompt(), providerName, model);

            if (!provider.isConfigured()) {
                agentLogger.logError(runId, "Provider not configured: " + providerName, null);
                return AgentLoopResult.failure("Provider " + providerName + " is not configured",
                    System.currentTimeMillis() - startTime, providerName);
            }

            List<ToolDefinition> tools = discoverTools(context);
            // Every in-process agent gets an inactivity watchdog (default 5 min): a working agent
            // resets it on each streamed event, a stalled one trips it -> INACTIVITY_TIMEOUT.
            InactivityWatchdogCallback watchdog = buildInactivityWatchdog(context, callback);
            AgentLoopResult result = executeLoop(provider, model, context, tools, runId,
                watchdog != null ? watchdog : callback);
            return reclassifyInactivity(result, watchdog);

        } catch (BridgeAccessDeniedException e) {
            // Defensive: bridge providers are normally dispatched by BridgeLoopDispatcher
            // before ever reaching this service, but if a bridge-aware provider gets
            // registered with the factory, the denial must propagate verbatim so
            // GlobalExceptionHandler can map reason → 403 vs 429. Must come BEFORE the
            // LLMProviderException catch since BridgeAccessDeniedException extends it.
            agentLogger.logError(runId, e.getMessage(), e);
            throw e;
        } catch (LLMProviderException e) {
            agentLogger.logError(runId, e.getMessage(), e);
            return AgentLoopResult.failure(e.getMessage(), System.currentTimeMillis() - startTime, providerName);
        } catch (Exception e) {
            agentLogger.logError(runId, "Execution error: " + e.getMessage(), e);
            return AgentLoopResult.failure("Execution error: " + e.getMessage(),
                System.currentTimeMillis() - startTime, providerName);
        } finally {
            // Centralized CE relay billing: settle the execution's accrued usage as ONE ledger
            // line. No-op unless this run used the cloud relay with a correlation executionId.
            if (providerResolver != null) {
                providerResolver.settleCeRelay(context);
            }
        }
    }

    /**
     * Execute agent loop with streaming.
     * Returns the execution result for observability tracking.
     * The callback still receives all streaming events as before.
     */
    public AgentLoopResult executeStreaming(AgentLoopContext context, StreamingCallback callback) {
        String providerName = context.provider();
        String runId = context.runId() != null ? context.runId() : UUID.randomUUID().toString();
        long startTime = System.currentTimeMillis();

        try {
            if (providerName == null) {
                providerName = resolveDefaultProviderName(context);
            }
            LLMProvider provider = resolveProvider(providerName, context);
            String model = context.model() != null ? context.model() : provider.getDefaultModel();

            agentLogger.logExecutionStart(runId, context.userPrompt(), providerName, model);

            if (!provider.isConfigured()) {
                agentLogger.logError(runId, "Provider not configured: " + providerName, null);
                callback.onError("Provider " + providerName + " is not configured");
                return AgentLoopResult.failure("Provider " + providerName + " is not configured",
                    System.currentTimeMillis() - startTime, providerName);
            }

            List<ToolDefinition> tools = discoverTools(context);
            InactivityWatchdogCallback watchdog = buildInactivityWatchdog(context, callback);
            AgentLoopResult result = executeStreamingLoop(provider, model, context, tools,
                watchdog != null ? watchdog : callback, runId, startTime);
            return reclassifyInactivity(result, watchdog);

        } catch (BridgeAccessDeniedException e) {
            // Defensive: see comment in execute() - bridges normally route through
            // BridgeLoopDispatcher. If denial reaches here, SSE consumers get onError
            // and the exception propagates so HTTP layer can map 403/429.
            agentLogger.logError(runId, e.getMessage(), e);
            callback.onError(e.getMessage());
            throw e;
        } catch (LLMProviderException e) {
            agentLogger.logError(runId, e.getMessage(), e);
            callback.onError(e.getMessage());
            return AgentLoopResult.failure(e.getMessage(), System.currentTimeMillis() - startTime, providerName);
        } catch (Exception e) {
            agentLogger.logError(runId, "Execution error: " + e.getMessage(), e);
            callback.onError("Execution error: " + e.getMessage());
            return AgentLoopResult.failure("Execution error: " + e.getMessage(),
                System.currentTimeMillis() - startTime, providerName);
        } finally {
            // Centralized CE relay billing: settle the execution's accrued usage as ONE ledger
            // line. No-op unless this run used the cloud relay with a correlation executionId.
            if (providerResolver != null) {
                providerResolver.settleCeRelay(context);
            }
        }
    }

    /**
     * Loop scaffolding shared by {@link #executeLoop} and {@link #executeStreamingLoop}.
     * Initializes the {@link LoopExecutionState}, the resolved system prompt, the
     * conversation history, and marks the execution start. Returning a record lets both
     * loops avoid 12 lines of duplicated boilerplate that previously drifted.
     */
    private record LoopBootstrap(LoopExecutionState state, String systemPrompt, long timeoutMs) {}

    private LoopBootstrap bootstrapLoop(AgentLoopContext context, String runId) {
        long timeoutMs = context.getExecutionTimeoutMsOrZero();

        // Build LoopDetector with per-agent thresholds when the context carries
        // at least one override. Both sides default to LoopDetector.DEFAULT_* so the
        // detector behavior matches the historical constants when only one override
        // is provided.
        LoopDetector loopDetector;
        if (context.loopIdenticalStop() != null || context.loopConsecutiveStop() != null) {
            int identicalStop = context.loopIdenticalStop() != null
                ? context.loopIdenticalStop() : LoopDetector.DEFAULT_STOP_THRESHOLD;
            int consecutiveStop = context.loopConsecutiveStop() != null
                ? context.loopConsecutiveStop() : LoopDetector.DEFAULT_CONSECUTIVE_STOP;
            loopDetector = new LoopDetector(identicalStop, consecutiveStop);
        } else {
            loopDetector = new LoopDetector();
        }

        LoopExecutionState state = new LoopExecutionState(
            runId, context.getMaxIterationsOrDefault(), timeoutMs, loopDetector);
        // Stage 1a.1 - prefer layered blocks when the caller supplied them, so
        // the string pipeline used for history-init stays in sync with what
        // Claude will later emit as its native system[] array. Non-Claude
        // providers see the same concatenated text either way.
        String systemPrompt = context.hasSystemBlocks()
            ? context.effectiveSystemPrompt()
            : (context.systemPrompt() != null ? context.systemPrompt() : DefaultSystemPrompts.getDefault());
        initializeMessages(state, context, systemPrompt);
        state.markExecutionStart();
        return new LoopBootstrap(state, systemPrompt, timeoutMs);
    }

    /**
     * Apply a denied {@link GuardResult} to the loop state: set the stop reason, populate
     * the {@code budgetScope} / {@code denialReason} metrics, and log a warning. Shared by
     * both loop variants so the metric keys cannot drift between sync and streaming paths.
     */
    private void applyGuardDenial(LoopExecutionState state, GuardResult guardResult) {
        state.setStopReason(guardResult.stopReason());
        if (guardResult.scope() != null) {
            state.getMetrics().put("budgetScope", guardResult.scope());
        }
        if (guardResult.denialReason() != null) {
            state.getMetrics().put("denialReason", guardResult.denialReason());
        }
        log.warn("Agent stopped by guard - reason={} scope={} detail={} (iteration {})",
            guardResult.stopReason(), guardResult.scope(), guardResult.denialReason(), state.getIterations());
    }

    /**
     * Build the inactivity watchdog wrapper for this run, or {@code null} when it does not apply.
     *
     * <p>Returns {@code null} when there is no callback to observe (a pure non-streaming single-shot
     * call emits no incremental events), when the window is disabled ({@code <= 0}), or when the
     * callback is already a watchdog (defensive against double-wrapping). Static + package-private
     * so the wiring decision is unit-testable without constructing the whole service.</p>
     */
    static InactivityWatchdogCallback buildInactivityWatchdog(AgentLoopContext context, StreamingCallback callback) {
        if (callback == null || context == null) {
            return null;
        }
        if (callback instanceof InactivityWatchdogCallback existing) {
            return existing;
        }
        long windowMs = resolveInactivityWindowMs(context);
        if (windowMs <= 0) {
            return null;
        }
        return new InactivityWatchdogCallback(callback, windowMs);
    }

    /**
     * Resolve the inactivity window (ms). A per-agent override carried on the credentials map
     * ({@code __inactivityTimeoutSeconds__}, set by the producers from the agent's inactivity_timeout
     * column) wins, so the value reaches every surface (in-process and bridge) without threading a
     * new field through the positional execution DTO. {@code <= 0} disables the watchdog. Falls back
     * to the context field / platform 5-min default.
     */
    static long resolveInactivityWindowMs(AgentLoopContext context) {
        Object override = context.credentials() != null
            ? context.credentials().get("__inactivityTimeoutSeconds__") : null;
        if (override != null) {
            try {
                long seconds = override instanceof Number n
                    ? n.longValue() : Long.parseLong(override.toString().trim());
                // Contract for the credential channel: 0 = disabled, 10-7200 = custom window.
                // Anything else (negative, 1-9, > 7200) is out-of-contract - producers other
                // than chat forward the raw value unvalidated, so enforcing the range HERE
                // (the single chokepoint every surface funnels through) prevents a stray
                // small value (e.g. 3) from arming a seconds-scale watchdog that false-kills
                // healthy agents. Out-of-contract -> ignore the override, use the default.
                if (seconds == 0) {
                    return 0L;
                }
                if (seconds >= 10 && seconds <= 7200) {
                    return seconds * 1000L;
                }
                log.warn("Ignoring out-of-contract __inactivityTimeoutSeconds__ override {} "
                    + "(expected 0=disabled or 10-7200s) - falling back to the default window", seconds);
            } catch (NumberFormatException ignore) {
                // malformed override -> fall through to the context default
            }
        }
        return context.getInactivityTimeoutMsOrDefault();
    }

    /**
     * The finishReason string to STREAM when a run stops via the cancel channel. The
     * inactivity watchdog trips through the same {@code shouldStop()} channel as a user
     * cancel, so without this check the live stream tells the frontend
     * {@code stopped_by_user} while observability later records
     * {@code INACTIVITY_TIMEOUT} (via {@link #reclassifyInactivity}) - a telemetry/UX
     * mismatch. The persisted {@code AgentLoopResult.stopReason} is still promoted by
     * {@code reclassifyInactivity}; this only aligns the streamed event with it.
     */
    static String cancelFinishReason(StreamingCallback callback) {
        return (callback instanceof InactivityWatchdogCallback w && w.isIdleTripped())
            ? "inactivity_timeout" : "stopped_by_user";
    }

    /**
     * Reclassify a run that the inactivity watchdog stopped. The loop records a watchdog stop as
     * {@code STOPPED_BY_USER} (it trips via the same {@code shouldStop()} channel as a user cancel);
     * here we promote it to {@link AgentStopReason#INACTIVITY_TIMEOUT} so observability shows the
     * real cause. A genuine user/system cancel short-circuits inside the watchdog and never sets
     * {@code idleTripped}, so it is left untouched. Static + package-private for unit-testing.
     */
    static AgentLoopResult reclassifyInactivity(AgentLoopResult result, InactivityWatchdogCallback watchdog) {
        if (result != null && watchdog != null && watchdog.isIdleTripped()
                && result.stopReason() == AgentStopReason.STOPPED_BY_USER) {
            log.warn("⏹️ [AGENT-LOOP] Inactivity watchdog tripped (no activity for {}ms) - "
                + "reclassifying STOPPED_BY_USER -> INACTIVITY_TIMEOUT", watchdog.getInactivityTimeoutMs());
            return result.toBuilder()
                .success(false)
                .stopReason(AgentStopReason.INACTIVITY_TIMEOUT)
                .build();
        }
        return result;
    }

    private AgentLoopResult executeLoop(LLMProvider provider, String model,
                                         AgentLoopContext context,
                                         List<ToolDefinition> tools,
                                         String runId,
                                         StreamingCallback callback) {
        LoopBootstrap boot = bootstrapLoop(context, runId);
        LoopExecutionState state = boot.state();
        String systemPrompt = boot.systemPrompt();
        long timeoutMs = boot.timeoutMs();

        // Captures the verbatim error message when processIteration returns IterationResult.error(...)
        // (e.g. provider misconfigured at streaming time, rate-limit denial, transport failure).
        // Without this propagation the orchestrator's AgentAsyncCompletionService falls back to the
        // generic "Async agent execution failed" string and the user has no way to debug.
        String capturedError = null;

        // Execute loop - pass callback for real-time tool call tracking
        while (state.hasMoreIterations()) {
            // Check for stop request via callback (e.g. Redis cancel key)
            if (callback != null && callback.shouldStop()) {
                log.info("⏹️ [AGENT-LOOP] Hard stop requested - exiting (iteration {})", state.getIterations());
                state.setStopReason(AgentStopReason.STOPPED_BY_USER);
                break;
            }

            // Pre-iteration guards (tenant budget, agent budget, custom): first deny wins.
            GuardResult guardResult = evaluateGuard(context, state);
            if (!guardResult.proceed()) {
                applyGuardDenial(state, guardResult);
                break;
            }

            AgentLoopExecutor.IterationResult result = loopExecutor.processIteration(
                provider, model, context, tools, state, systemPrompt, callback);

            if (!result.shouldContinue()) {
                if (result.isError()) {
                    state.setStopReason(AgentStopReason.ERROR);
                    capturedError = result.errorMessage();
                } else if (result.loopDetected()) {
                    state.setStopReason(AgentStopReason.LOOP_DETECTED);
                } else if (result.isCancelled()) {
                    // F1.2 - explicit cancel from inside processIteration (post-stream
                    // or post-tools STOP). processIteration already set stopReason
                    // to STOPPED_BY_USER, but we set it here too as a guard against
                    // future callers that bypass the inner write.
                    state.setStopReason(AgentStopReason.STOPPED_BY_USER);
                }
                break;
            }

            // Check for stop after tool execution
            if (callback != null && callback.shouldStop()) {
                log.info("⏹️ [AGENT-LOOP] Hard stop after tools - exiting (iteration {})", state.getIterations());
                state.setStopReason(AgentStopReason.STOPPED_BY_USER);
                break;
            }
        }

        // Handle max iterations or timeout - but only if the agent did NOT complete naturally.
        // When the LLM responds without tool calls, processIteration sets both stopReason=COMPLETED
        // and currentState=COMPLETED. We must not override that to MAX_ITERATIONS, even if the
        // agent used exactly maxIterations iterations (e.g. classify with maxIterations=1).
        if (!state.hasMoreIterations()
                && state.getStopReason() == AgentStopReason.COMPLETED
                && state.getCurrentState() != AgentState.COMPLETED) {
            if (state.isTimedOut()) {
                state.setStopReason(AgentStopReason.TIMEOUT);
                log.warn("Agent stopped - execution timeout reached ({}ms elapsed, limit {}ms)",
                    state.getDuration(), timeoutMs);
            } else {
                state.setStopReason(AgentStopReason.MAX_ITERATIONS);
                log.warn("Agent stopped - max iterations reached ({})", state.getMaxIterations());
            }
        }

        // Build final result
        state.buildFinalMetrics();
        boolean isSuccess = state.getStopReason() == AgentStopReason.COMPLETED;
        agentLogger.logExecutionEnd(runId, isSuccess,
            state.getIterations(), state.getAllToolResults().size(), state.getDuration(), state.getStopReason().name());

        String finalContent = state.getLastResponse() != null ? state.getLastResponse().content() : "";
        return AgentLoopResult.builder()
            .success(isSuccess)
            .response(state.getLastResponse())
            .content(finalContent)
            .error(capturedError)
            .toolResults(state.getAllToolResults())
            .iterations(state.getIterations())
            .usage(state.buildUsageInfo())
            .durationMs(state.getDuration())
            .provider(provider.getProviderName())
            .model(model)
            .conversationHistory(synthesiseHistoryFallback(state.getCurrentExecutionMessages(), finalContent))
            .stopReason(state.getStopReason())
            .metrics(state.getMetrics())
            .usagePerIteration(state.getUsagePerIteration())
            .iterationDurations(state.getIterationDurations())
            .finishReasonsPerIteration(state.getFinishReasonsPerIteration())
            .build();
    }

    /**
     * Symmetry fallback with {@code BridgeLoopDispatcher.convertResponse} (line 257-260):
     * if the loop produced visible content but no execution messages were captured
     * (Gemini sometimes streams content without populating the assistant Message in
     * {@link LoopExecutionState#getMessages()}), synthesise a single ASSISTANT message
     * from {@code content} so {@code GuardrailService.parseResponse} /
     * {@code ClassifyService.parseResponse} have at least one row to persist.
     *
     * <p>Without this, ~17% of guardrail/classify runs against Gemini end up with
     * 0 rows in {@code agent.agent_execution_messages}, breaking the side-panel
     * conversation view in Agent Performance.
     */
    private static List<Message> synthesiseHistoryFallback(List<Message> history, String content) {
        if (history != null && !history.isEmpty()) {
            return history;
        }
        if (content == null || content.isBlank()) {
            return history != null ? history : Collections.emptyList();
        }
        return List.of(new Message(Message.Role.ASSISTANT, content, null, null, null, null));
    }

    private AgentLoopResult executeStreamingLoop(LLMProvider provider, String model,
                                       AgentLoopContext context,
                                       List<ToolDefinition> tools,
                                       StreamingCallback callback,
                                       String runId,
                                       long startTime) {
        LoopBootstrap boot = bootstrapLoop(context, runId);
        LoopExecutionState state = boot.state();
        String systemPrompt = boot.systemPrompt();
        long timeoutMs = boot.timeoutMs();

        // Execute loop
        while (state.hasMoreIterations()) {
            // Check for stop request
            if (callback.shouldStop()) {
                log.info("⏹️ [AGENT-LOOP] Hard stop requested - exiting (iteration {})", state.getIterations());
                String cancelReason = cancelFinishReason(callback);
                agentLogger.logExecutionEnd(runId, false, state.getIterations(),
                    state.getAllToolResults().size(), state.getDuration(), cancelReason.toUpperCase(java.util.Locale.ROOT));
                callback.onComplete(CompletionResponse.builder()
                    .content(state.getFullContent().toString())
                    .finishReason(cancelReason)
                    .usage(state.buildUsageInfo())
                    .model(model)
                    .build());
                state.setStopReason(AgentStopReason.STOPPED_BY_USER);
                return buildStreamingResult(state, provider.getProviderName(), model, false);
            }

            // Pre-iteration guards (tenant budget, agent budget, custom): first deny wins.
            GuardResult guardResult = evaluateGuard(context, state);
            if (!guardResult.proceed()) {
                applyGuardDenial(state, guardResult);
                String finishReasonGuard = guardResult.stopReason() == AgentStopReason.BUDGET_EXHAUSTED
                    ? "budget_exhausted" : guardResult.stopReason().name().toLowerCase();
                agentLogger.logExecutionEnd(runId, false, state.getIterations(),
                    state.getAllToolResults().size(), state.getDuration(), guardResult.stopReason().name());
                callback.onComplete(CompletionResponse.builder()
                    .content(state.getFullContent().toString())
                    .finishReason(finishReasonGuard)
                    .usage(state.buildUsageInfo())
                    .model(model)
                    .build());
                return buildStreamingResult(state, provider.getProviderName(), model, false);
            }

            AgentLoopExecutor.IterationResult result = loopExecutor.processIteration(
                provider, model, context, tools, state, systemPrompt, callback);

            if (result.isError()) {
                state.setStopReason(AgentStopReason.ERROR);
                agentLogger.logExecutionEnd(runId, false, state.getIterations(),
                    state.getAllToolResults().size(), state.getDuration(), "ERROR");
                callback.onComplete(CompletionResponse.builder()
                    .content(state.getFullContent().toString())
                    .finishReason("error")
                    .usage(state.buildUsageInfo())
                    .model(model)
                    .build());
                return buildStreamingResult(state, provider.getProviderName(), model, false, result.errorMessage());
            }

            if (result.isComplete()) {
                boolean wasStoppedByUser = callback.shouldStop();
                String cancelReason = cancelFinishReason(callback);
                String status = wasStoppedByUser ? cancelReason.toUpperCase(java.util.Locale.ROOT) : "COMPLETED";
                agentLogger.logExecutionEnd(runId, !wasStoppedByUser, state.getIterations(),
                    state.getAllToolResults().size(), state.getDuration(), status);
                callback.onComplete(CompletionResponse.builder()
                    .content(state.getFullContent().toString())
                    .finishReason(wasStoppedByUser ? cancelReason : "stop")
                    .usage(state.buildUsageInfo())
                    .model(model)
                    .build());
                if (wasStoppedByUser) {
                    state.setStopReason(AgentStopReason.STOPPED_BY_USER);
                }
                return buildStreamingResult(state, provider.getProviderName(), model, !wasStoppedByUser);
            }

            if (result.loopDetected()) {
                String stopReason = state.getMetrics().containsKey("loopType")
                    ? state.getMetrics().get("loopType").toString().toUpperCase() + "_LOOP_DETECTED"
                    : "LOOP_DETECTED";
                agentLogger.logExecutionEnd(runId, false, state.getIterations(),
                    state.getAllToolResults().size(), state.getDuration(), stopReason);
                callback.onComplete(CompletionResponse.builder()
                    .content(state.getFullContent().toString())
                    .finishReason("loop_detected")
                    .usage(state.buildUsageInfo())
                    .model(model)
                    .build());
                state.setStopReason(AgentStopReason.LOOP_DETECTED);
                return buildStreamingResult(state, provider.getProviderName(), model, false);
            }

            // F1.2 - explicit cancel returned by processIteration (post-stream or
            // post-tools STOP). Handled before the post-tools shouldStop fallback
            // so we don't depend on Redis still holding the cancel key when we
            // re-poll (TTL race on long iterations).
            if (result.isCancelled()) {
                log.info("⏹️ [AGENT-LOOP] Cancel result from processIteration - exiting (iteration {})", state.getIterations());
                String cancelReason = cancelFinishReason(callback);
                agentLogger.logExecutionEnd(runId, false, state.getIterations(),
                    state.getAllToolResults().size(), state.getDuration(), cancelReason.toUpperCase(java.util.Locale.ROOT));
                callback.onComplete(CompletionResponse.builder()
                    .content(state.getFullContent().toString())
                    .finishReason(cancelReason)
                    .usage(state.buildUsageInfo())
                    .model(model)
                    .build());
                state.setStopReason(AgentStopReason.STOPPED_BY_USER);
                return buildStreamingResult(state, provider.getProviderName(), model, false);
            }

            // Check for stop after tool execution
            if (callback.shouldStop()) {
                log.info("⏹️ [AGENT-LOOP] Hard stop after tools - exiting (iteration {})", state.getIterations());
                String cancelReason = cancelFinishReason(callback);
                agentLogger.logExecutionEnd(runId, false, state.getIterations(),
                    state.getAllToolResults().size(), state.getDuration(), cancelReason.toUpperCase(java.util.Locale.ROOT));
                callback.onComplete(CompletionResponse.builder()
                    .content(state.getFullContent().toString())
                    .finishReason(cancelReason)
                    .usage(state.buildUsageInfo())
                    .model(model)
                    .build());
                state.setStopReason(AgentStopReason.STOPPED_BY_USER);
                return buildStreamingResult(state, provider.getProviderName(), model, false);
            }
        }

        // Max iterations or timeout reached
        String finishReason;
        if (state.isTimedOut()) {
            log.warn("⚠️ Agent execution timed out ({}ms elapsed, limit {}ms)", state.getDuration(), timeoutMs);
            finishReason = "timeout";
            state.setStopReason(AgentStopReason.TIMEOUT);
        } else {
            log.warn("⚠️ Agent reached max iterations ({}) without completing", state.getMaxIterations());
            finishReason = "max_iterations";
            state.setStopReason(AgentStopReason.MAX_ITERATIONS);
        }
        agentLogger.logExecutionEnd(runId, false, state.getIterations(),
            state.getAllToolResults().size(), state.getDuration(), finishReason.toUpperCase());
        callback.onComplete(CompletionResponse.builder()
            .content(state.getFullContent().toString())
            .finishReason(finishReason)
            .usage(state.buildUsageInfo())
            .model(model)
            .build());
        return buildStreamingResult(state, provider.getProviderName(), model, false);
    }

    private AgentLoopResult buildStreamingResult(LoopExecutionState state, String providerName,
                                                  String model, boolean success) {
        return buildStreamingResult(state, providerName, model, success, null);
    }

    private AgentLoopResult buildStreamingResult(LoopExecutionState state, String providerName,
                                                  String model, boolean success, String errorMessage) {
        state.buildFinalMetrics();
        String streamContent = state.getFullContent().toString();
        return AgentLoopResult.builder()
            .success(success)
            .content(streamContent)
            .error(errorMessage)
            .toolResults(state.getAllToolResults())
            .iterations(state.getIterations())
            .usage(state.buildUsageInfo())
            .durationMs(state.getDuration())
            .provider(providerName)
            .model(model)
            .conversationHistory(synthesiseHistoryFallback(state.getCurrentExecutionMessages(), streamContent))
            .stopReason(state.getStopReason())
            .metrics(state.getMetrics())
            .usagePerIteration(state.getUsagePerIteration())
            .iterationDurations(state.getIterationDurations())
            .finishReasonsPerIteration(state.getFinishReasonsPerIteration())
            .build();
    }

    /**
     * Build an {@link IterationContext} snapshot from the current loop state and run the
     * (possibly composite) {@link PreIterationGuard} from the {@link AgentLoopContext}.
     * Returns {@link GuardResult#allow()} when no guard is configured.
     */
    private GuardResult evaluateGuard(AgentLoopContext context, LoopExecutionState state) {
        PreIterationGuard guard = context.preIterationGuard();
        if (guard == null) {
            return GuardResult.allow();
        }
        IterationContext ctx = new IterationContext(
            context.tenantId(),
            context.agentId(),
            context.provider(),
            context.model(),
            state.getIterations() + 1,
            state.getIterations(),
            state.getTotalPromptTokens(),
            state.getTotalCompletionTokens(),
            // V162: lastDelta tokens drive step-function-aware projection in guards.
            // Zero on iter 1 (no completed iteration yet); guards must handle 0 gracefully
            // by falling back to the avg-only branch.
            state.getLastIterationPromptTokens(),
            state.getLastIterationCompletionTokens(),
            state.getDuration()
        );
        GuardResult result = guard.check(ctx);
        return result != null ? result : GuardResult.allow();
    }

    private String resolveDefaultProviderName(AgentLoopContext context) {
        String providerName = providerResolver != null
                ? providerResolver.resolveDefaultProviderName(context != null ? context.tenantId() : null)
                : providerFactory.getDefaultProviderName();
        if (providerName == null || providerName.isBlank()) {
            throw new LLMProviderException("unknown", "No configured LLM provider available");
        }
        return providerName;
    }

    private LLMProvider resolveProvider(String providerName, AgentLoopContext context) {
        if (providerResolver != null) {
            return providerResolver.resolve(providerName, context);
        }
        return providerFactory.getProvider(providerName);
    }

    private void initializeMessages(LoopExecutionState state, AgentLoopContext context, String systemPrompt) {
        if (context.conversationHistory() != null) {
            state.getMessages().addAll(context.conversationHistory());
        }
        state.getMessages().add(0, Message.system(systemPrompt));

        // Create user message with attachments if present
        if (context.hasCurrentMessageAttachments()) {
            log.info("Creating user message with {} attachments", context.currentMessageAttachments().size());
            state.getMessages().add(Message.userWithAttachments(context.userPrompt(), context.currentMessageAttachments()));
        } else {
            state.getMessages().add(Message.user(context.userPrompt()));
        }
    }

    /**
     * Build the effective tool list for this loop invocation. Tools are accumulated into a
     * name-keyed LinkedHashMap so a later source can never silently overwrite an earlier one
     * and we never ship duplicates to the provider. Prior to BUG-10 we assembled a list and
     * deduplicated post-hoc at the end - that still worked, but produced
     * {@code [TOOL DEDUP] Duplicate 'get_task' removed ×3} warnings on every prompt, and
     * burned prompt-cache bandwidth building-then-discarding ~3KB of repeats per call.
     * Filtering at add-time eliminates both.
     */
    private List<ToolDefinition> discoverTools(AgentLoopContext context) {
        Map<String, ToolDefinition> unique = new LinkedHashMap<>();

        if (context.hasTools()) {
            addUniqueTools(unique, context.tools(), "context");
        }

        if (!context.isAutoDiscoverEnabled()) {
            return new ArrayList<>(unique.values());
        }

        if (toolDiscoveryService != null) {
            List<ToolDefinition> catalogTools = toolDiscoveryService.findRelevantTools(
                context.userPrompt(), context.getMaxToolsOrDefault());
            if (!catalogTools.isEmpty()) {
                log.debug("Auto-discovered {} catalog tools", catalogTools.size());
                addUniqueTools(unique, catalogTools, "catalog-discovery");
            }
        }

        return new ArrayList<>(unique.values());
    }

    /**
     * Insert tools into the name-keyed sink, skipping any whose name already exists. When
     * overlap is observed we log DEBUG per skipped tool (for diagnosis) and WARN once per
     * batch with the source label so the upstream double-registration can be traced.
     */
    private void addUniqueTools(Map<String, ToolDefinition> sink,
                                 List<ToolDefinition> source,
                                 String origin) {
        if (source == null || source.isEmpty()) return;
        int skipped = 0;
        for (ToolDefinition tool : source) {
            if (tool == null || tool.name() == null) continue;
            if (sink.putIfAbsent(tool.name(), tool) != null) {
                skipped++;
                log.debug("[TOOL DEDUP] Skipping duplicate '{}' from {}", tool.name(), origin);
            }
        }
        if (skipped > 0) {
            log.warn("⚠️ [TOOL DEDUP] Source '{}' emitted {} duplicate(s) (of {}) - upstream double-registration",
                origin, skipped, source.size());
        }
    }

}
