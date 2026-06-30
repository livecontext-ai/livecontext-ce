package com.apimarketplace.orchestrator.execution.v2.nodes;

import com.apimarketplace.agent.client.AgentClient;
import com.apimarketplace.agent.client.dto.AgentObservabilityRequest;
import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionContext;
import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionResult;
import com.apimarketplace.orchestrator.domain.execution.SignalConfig;
import com.apimarketplace.orchestrator.domain.execution.SignalType;
import com.apimarketplace.orchestrator.execution.v2.engine.ExecutionContext;
import com.apimarketplace.orchestrator.execution.v2.engine.ServiceRegistry;
import com.apimarketplace.orchestrator.execution.v2.services.UnifiedSignalService;
import com.apimarketplace.orchestrator.tools.websearch.BrowserAgentModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Browser Agent node - a workflow-level node that runs ONE {@code agent_browse}
 * session against the websearch-service browser-agent runner.
 *
 * <p>Unlike the conversational {@link AgentNode}, BrowserAgentNode does not run
 * an LLM tool-calling loop on the orchestrator side. The runner owns the loop:
 * given a task, it picks browser actions, executes them, and emits a single
 * final-result entry on the per-job Redis LIST. We submit the job, await the
 * final, transform it into the {@link BrowserAgentNodeSpec} output schema, and
 * record an observability row for the run (agent_type='browser_agent').</p>
 *
 * <p>Observability mapping (see design):</p>
 * <ul>
 *   <li>browser-agent steps → {@code agent_execution_iterations} (one row per step)</li>
 *   <li>per-step browser actions (click, type, navigate) → {@code agent_execution_tool_calls}</li>
 *   <li>step's eval / memory / next_goal / screenshot_key → {@code tool_calls.metadata} JSONB</li>
 * </ul>
 *
 * <p>Stop-reason mapping (free-form runner reason → canonical {@link com.apimarketplace.agent.domain.AgentStopReason}):</p>
 * <ul>
 *   <li>COMPLETED → COMPLETED</li>
 *   <li>MAX_STEPS → MAX_ITERATIONS</li>
 *   <li>USER_TAKEOVER → STOPPED_BY_USER</li>
 *   <li>LLM_FAILED, SCHEMA_MISMATCH, DOMAIN_BLOCKED → ERROR</li>
 *   <li>TIMEOUT → TIMEOUT</li>
 *   <li>CANCELLED → CANCELLED</li>
 *   <li>BUDGET_EXHAUSTED → BUDGET_EXHAUSTED</li>
 *   <li>anything else → ERROR (defensive default)</li>
 * </ul>
 * <p>The fine-grained reason is preserved verbatim in the node output's
 * {@code stop_reason} field so the agent / inspector still sees the runner's
 * exact label; only the observability row is normalised.</p>
 */
public class BrowserAgentNode extends BaseNode {

    private static final Logger logger = LoggerFactory.getLogger(BrowserAgentNode.class);

    /** Workflow-plan-supplied node configuration: { task, start_url, llm, … }. */
    private final Map<String, Object> nodeConfig;
    private final List<String> dependencies;

    // Injected services
    private BrowserAgentModule browserAgentModule;
    private AgentClient agentClient;
    /**
     * UnifiedSignalService - used to raise a {@link SignalType#BROWSER_USER_TAKEOVER}
     * signal when the runner reports {@code stop_reason='USER_TAKEOVER'}. Optional:
     * when null (e.g. tests) we fall back to a regular failure result instead of
     * yielding to AWAITING_SIGNAL.
     */
    private UnifiedSignalService signalService;

    public BrowserAgentNode(String nodeId, Map<String, Object> nodeConfig, List<String> dependencies) {
        super(nodeId, NodeType.BROWSER_AGENT);
        this.nodeConfig = nodeConfig != null ? nodeConfig : Map.of();
        this.dependencies = dependencies != null ? dependencies : List.of();
    }

    public BrowserAgentNode(String nodeId, Map<String, Object> nodeConfig) {
        this(nodeId, nodeConfig, List.of());
    }

    public void setBrowserAgentModule(BrowserAgentModule browserAgentModule) {
        this.browserAgentModule = browserAgentModule;
    }

    public void setAgentClient(AgentClient agentClient) {
        this.agentClient = agentClient;
    }

    public void setSignalService(UnifiedSignalService signalService) {
        this.signalService = signalService;
    }

    /**
     * Pulls the BrowserAgentModule + AgentClient from the registry.
     *
     * <p>Both beans are mandatory in production wiring - see
     * {@link com.apimarketplace.orchestrator.execution.v2.engine.ExecutionServiceInjector}.
     * If either is missing here it means the orchestrator was started with
     * {@code websearch.enabled=false} (which gates {@link BrowserAgentModule})
     * or without the agent-client wiring, yet the workflow plan still references
     * a {@code BROWSER_AGENT} node. We fail loudly so the misconfiguration shows
     * up at injection time rather than producing silent observability holes
     * (a null {@code agentClient} would skip {@link #recordObservability}).</p>
     *
     * <p>Tests that need to bypass injection construct the node and use
     * {@link #setBrowserAgentModule}/{@link #setAgentClient} directly without
     * calling this method - same pattern as {@link AgentNode} (see
     * {@code AgentNode.java:121-129}).</p>
     */
    @Override
    public void acceptServices(ServiceRegistry registry) {
        super.acceptServices(registry);
        this.browserAgentModule = registry.getBrowserAgentModule();
        this.agentClient = registry.getAgentClient();
        this.signalService = registry.getSignalService();

        if (this.browserAgentModule == null) {
            throw new IllegalStateException(
                "BrowserAgentNode '" + nodeId + "' cannot be wired: BrowserAgentModule "
                + "missing from ServiceRegistry. Enable 'websearch.enabled=true' or "
                + "remove the BROWSER_AGENT node from the workflow plan.");
        }
        if (this.agentClient == null) {
            throw new IllegalStateException(
                "BrowserAgentNode '" + nodeId + "' cannot be wired: AgentClient "
                + "missing from ServiceRegistry. Observability recording requires "
                + "agent-service connectivity.");
        }
    }

    @Override
    public boolean isAgentNode() {
        return true;
    }

    @Override
    protected List<String> getDependencies(ExecutionContext context) {
        return dependencies;
    }

    @Override
    public NodeExecutionResult execute(ExecutionContext context) {
        long startTime = System.currentTimeMillis();
        logger.info("Browser agent node executing: nodeId={}, itemId={}",
            nodeId, context.itemId());

        // Snapshot the resolved config for the inspector regardless of outcome.
        Map<String, Object> resolvedParams = resolveParams(context);

        if (browserAgentModule == null) {
            long duration = System.currentTimeMillis() - startTime;
            String err = "BrowserAgentModule not injected - set websearch.enabled=true";
            logger.error("Browser agent node misconfigured: nodeId={}, error={}", nodeId, err);
            return NodeExecutionResult.failureWithOutput(nodeId, err,
                buildFailureOutput(context, resolvedParams, err, null), duration);
        }

        // The module's execute() is identical to what the LLM would invoke as
        // web_search(action='agent_browse', …). We bypass WebSearchToolsProvider
        // because we do NOT want the interface-persistence side effect on a
        // workflow node - the run's step row is enough.
        ToolExecutionContext toolCtx = new ToolExecutionContext(
            context.tenantId(),
            buildCallbackCredentials(context),
            Map.of(),
            java.util.Set.of(),
            null, null, null, null
        );

        Optional<ToolExecutionResult> maybe;
        try {
            maybe = browserAgentModule.execute("agent_browse", resolvedParams, context.tenantId(), toolCtx);
        } catch (Exception e) {
            long duration = System.currentTimeMillis() - startTime;
            logger.error("Browser agent execution threw: nodeId={}, error={}", nodeId, e.getMessage(), e);
            return NodeExecutionResult.failureWithOutput(nodeId, e.getMessage(),
                buildFailureOutput(context, resolvedParams, e.getMessage(), null), duration);
        }

        long duration = System.currentTimeMillis() - startTime;

        if (maybe.isEmpty()) {
            String err = "BrowserAgentModule did not handle agent_browse";
            return NodeExecutionResult.failureWithOutput(nodeId, err,
                buildFailureOutput(context, resolvedParams, err, null), duration);
        }

        ToolExecutionResult toolResult = maybe.get();
        @SuppressWarnings("unchecked")
        Map<String, Object> rawOutput = toolResult.data() instanceof Map<?, ?> m
            ? (Map<String, Object>) m
            : new LinkedHashMap<>();

        // Record observability AFTER the session terminates. Best-effort: a
        // recording failure must NOT mask the actual session outcome (mirrors
        // the AgentNode pattern at AgentNode.java:870-878). We pass
        // resolvedParams (templates already evaluated, line 154) - NOT
        // nodeConfig - so the synthesised USER message in the conversation
        // panel shows the literal task, not raw `{{trigger.task}}`
        // placeholders.
        recordObservability(context, rawOutput, toolResult.success(),
            toolResult.error(), duration, resolvedParams);

        if (!toolResult.success()) {
            String err = toolResult.error() != null ? toolResult.error() : "Browser session failed";
            // Preserve any partial output the runner managed to surface - useful
            // for debugging cancelled / timed-out sessions.
            return NodeExecutionResult.failureWithOutput(nodeId, err,
                buildFailureOutput(context, resolvedParams, err, rawOutput), duration);
        }

        Map<String, Object> enrichedOutput = buildSuccessOutput(context, resolvedParams, rawOutput);

        // ── User-takeover signal raise ────────────────────────────────────
        // The runner can surface USER_TAKEOVER as the terminal stop_reason
        // when it detects the user clicked the "take control" button in
        // the live CDP panel. We translate that into a blocking
        // BROWSER_USER_TAKEOVER signal so the workflow stops here until
        // the user resolves it via /api/internal/browser-agent/runs/{r}/nodes/{n}/takeover-resume.
        // Pattern mirrors InterfaceNode raising INTERFACE_SIGNAL.
        String runnerStop = stringField(rawOutput, "stop_reason");
        if ("USER_TAKEOVER".equalsIgnoreCase(runnerStop) && signalService != null) {
            String sessionId = stringField(rawOutput, "session_id");
            String cdpToken = stringField(rawOutput, "cdp_token");
            String runId = context.runId();
            String itemId = context.itemId();
            String dagTriggerId = SignalContextResolver.resolveDagTriggerId(nodeId, null, context);
            int epoch = SignalContextResolver.resolveEpoch(0, context);
            Map<String, Object> signalConfig = SignalConfig.browserTakeover(
                sessionId, runId, nodeId, cdpToken, Duration.ofMinutes(30));
            signalService.registerSignal(
                runId, itemId, nodeId, dagTriggerId, epoch,
                SignalType.BROWSER_USER_TAKEOVER, signalConfig, null);
            logger.info("Browser agent yielded to user takeover: nodeId={}, sessionId={}", nodeId, sessionId);
            return NodeExecutionResult.awaitingSignal(nodeId, SignalType.BROWSER_USER_TAKEOVER, enrichedOutput);
        }

        return NodeExecutionResult.success(nodeId, enrichedOutput, duration);
    }

    /**
     * Resolve {{template}} placeholders inside the node config so both the runner
     * and the inspector see the same materialised values.
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> resolveParams(ExecutionContext context) {
        if (nodeConfig.isEmpty() || templateAdapter == null) {
            return new LinkedHashMap<>(nodeConfig);
        }
        try {
            return new LinkedHashMap<>(templateAdapter.resolveTemplates(nodeConfig, context));
        } catch (Exception e) {
            logger.warn("Template resolution failed for browser agent {}: {}", nodeId, e.getMessage());
            return new LinkedHashMap<>(nodeConfig);
        }
    }

    /**
     * The websearch-service uses these to addresss live trace events back to the
     * orchestrator (streamId / toolCallId / conversationId). For workflow nodes,
     * runId doubles as streamId and the node id doubles as the tool call id -
     * that gives the SSE consumer enough to route step events to the right card.
     */
    // Package-private so BrowserAgentNodeTest can pin the
    // __skipObservability__ flag - see test commentary on the
    // double-fire regression risk.
    Map<String, Object> buildCallbackCredentials(ExecutionContext context) {
        Map<String, Object> creds = new HashMap<>();
        if (context.runId() != null) {
            creds.put("__streamId__", context.runId());
        }
        creds.put("__toolCallId__", nodeId);
        // CRITICAL: tell BrowserAgentModule.recordObservabilityFromResult to
        // SKIP the chat-tool observability call. This node already records
        // its own observability with richer context (workflowId, runId,
        // epoch, spawn, iterations, tool calls) via recordObservability()
        // further down in execute() - letting the module ALSO fire would
        // create TWO agent_executions rows + TWO credit_history debits for
        // the same browser run. Without this mutual-exclusion flag we'd
        // double-bill every workflow browser session.
        //
        // Value MUST be the lowercase literal "true" - the module guard
        // uses .equals("true") (not equalsIgnoreCase) for predictability.
        creds.put("__skipObservability__", "true");
        return creds;
    }

    private Map<String, Object> buildSuccessOutput(ExecutionContext context,
                                                    Map<String, Object> resolvedParams,
                                                    Map<String, Object> rawOutput) {
        Map<String, Object> out = new LinkedHashMap<>(rawOutput);
        // Spec contract - see BrowserAgentNodeSpec. Default node_type so split-context
        // routing works even when the runner forgot the field.
        out.putIfAbsent("node_type", "BROWSER_AGENT");
        out.put("resolved_params", resolvedParams);
        out.put("item_index", context.itemIndex());
        out.put("itemIndex", context.itemIndex());
        out.put("item_id", context.itemId());
        return out;
    }

    private Map<String, Object> buildFailureOutput(ExecutionContext context,
                                                    Map<String, Object> resolvedParams,
                                                    String errorMessage,
                                                    Map<String, Object> partial) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (partial != null) {
            out.putAll(partial);
        }
        out.put("node_type", "BROWSER_AGENT");
        out.put("item_index", context.itemIndex());
        out.put("itemIndex", context.itemIndex());
        out.put("item_id", context.itemId());
        out.put("resolved_params", resolvedParams);
        if (errorMessage != null) {
            out.put("error", errorMessage);
        }
        return out;
    }

    /**
     * Build and post an {@link AgentObservabilityRequest} after the session
     * terminates. Best-effort - caller already handles the result, this is purely
     * for the agent-execution audit trail.
     */
    private void recordObservability(ExecutionContext context,
                                     Map<String, Object> rawOutput,
                                     boolean success,
                                     String errorMessage,
                                     long durationMs,
                                     Map<String, Object> resolvedParams) {
        if (agentClient == null) {
            return;
        }
        try {
            AgentObservabilityRequest req = new AgentObservabilityRequest();
            req.setTenantId(context.tenantId());
            // PR20 - propagate workspace identity onto the browser-agent workflow-node row.
            req.setOrganizationId(context.organizationId());
            req.setAgentType("browser_agent");
            req.setNodeId(nodeId);
            req.setRunId(context.runId());
            req.setEpoch(context.epoch());
            req.setSpawn(context.spawn());
            req.setItemIndex(context.itemIndex());
            req.setDurationMs(durationMs);
            req.setErrorMessage(errorMessage);

            if (context.plan() != null && context.plan().getId() != null) {
                try {
                    req.setWorkflowId(UUID.fromString(context.plan().getId()));
                } catch (IllegalArgumentException ignored) {
                    // Non-UUID plan id (test fixture, etc.) - observability stays anonymous.
                }
            }
            if (context.workflowRunId() != null) {
                try {
                    req.setWorkflowRunId(UUID.fromString(context.workflowRunId()));
                } catch (IllegalArgumentException ignored) { /* same */ }
            }

            String rawStop = stringField(rawOutput, "stop_reason");
            String canonicalStop = mapStopReason(rawStop, success);
            req.setStopReason(canonicalStop);
            req.setStatus(success ? "COMPLETED" : "FAILED");

            // Steps → iterations. One iteration per browser step.
            List<Map<String, Object>> steps = listOfMaps(rawOutput, "steps");
            req.setIterationCount(steps.size());

            List<AgentObservabilityRequest.IterationData> iterations = new java.util.ArrayList<>();
            List<AgentObservabilityRequest.ToolCallData> toolCalls = new java.util.ArrayList<>();
            int toolCallSeq = 0;
            for (int i = 0; i < steps.size(); i++) {
                Map<String, Object> step = steps.get(i);

                AgentObservabilityRequest.IterationData iter = new AgentObservabilityRequest.IterationData();
                iter.setIterationNumber(i + 1);
                iter.setToolCallCount(1); // one browser action per step
                iter.setDurationMs(longField(step, "duration_ms"));
                // Per-step token counters when the runner can supply them
                // (browser-use 0.12.x emits zeros today). Surfacing them on
                // IterationData lets the per-iteration inspector view break
                // down cost without re-summing the header cost block.
                long stepTokensIn = longField(step, "tokens_in");
                long stepTokensOut = longField(step, "tokens_out");
                if (stepTokensIn > 0 || stepTokensOut > 0) {
                    iter.setPromptTokens(stepTokensIn);
                    iter.setCompletionTokens(stepTokensOut);
                }
                iterations.add(iter);

                // Each step's browser action is a tool call. Action / target / eval /
                // memory / next_goal / screenshot_key / url go into the metadata
                // JSONB so the inspector can replay the session. action_args
                // carries the full pydantic dump from browser-use for fidelity.
                AgentObservabilityRequest.ToolCallData tc = new AgentObservabilityRequest.ToolCallData();
                tc.setSequenceNumber(toolCallSeq++);
                tc.setIterationNumber(i + 1);
                tc.setToolName(stringField(step, "action"));
                tc.setSuccess(true); // runner only emits successful steps; failures bubble up via stop_reason
                tc.setDurationMs(longField(step, "duration_ms"));

                Map<String, Object> meta = new LinkedHashMap<>();
                copyIfPresent(step, meta, "action");
                copyIfPresent(step, meta, "action_args");
                copyIfPresent(step, meta, "target");
                copyIfPresent(step, meta, "url");
                copyIfPresent(step, meta, "eval");
                copyIfPresent(step, meta, "memory");
                copyIfPresent(step, meta, "next_goal");
                copyIfPresent(step, meta, "screenshot_key");
                tc.setMetadata(meta);

                Map<String, Object> args = new LinkedHashMap<>();
                copyIfPresent(step, args, "action");
                copyIfPresent(step, args, "action_args");
                copyIfPresent(step, args, "target");
                tc.setArguments(args);

                toolCalls.add(tc);
            }
            req.setTotalToolCalls(toolCalls.size());
            req.setIterations(iterations);
            req.setToolCalls(toolCalls);

            // Cost block - populated by the Python runner from
            // browser-use's TokenCost.usage_history (covers all 7 exit
            // paths: COMPLETED / MAX_STEPS / CANCELLED / DOMAIN_BLOCKED /
            // BUDGET_EXHAUSTED / TIMEOUT / LLM_FAILED). Empty/zero only
            // when no LLM calls happened (e.g. early start_url SSRF reject).
            //
            // Convention: browser-use normalizes provider responses to a
            // SUBSET shape - `tokens_in` ALREADY includes the
            // `cache_read_tokens` portion. `cache_creation_tokens` is
            // Anthropic-only and IS disjoint from `tokens_in`. When the
            // pricing layer applies cache discounts (separate v0.1 work
            // - Anthropic 0.10× reads / 1.25× writes ; OpenAI 0.50× ;
            // Gemini 0.25×), it must NOT sum tokens_in + cache_read; it
            // discounts the cache_read portion of tokens_in. Until then,
            // tokens_in bills at full input rate (bounded over-bill on
            // cache-heavy turns, recoverable via reconciliation).
            Map<String, Object> cost = mapField(rawOutput, "cost");
            if (cost != null) {
                long tokensIn = longField(cost, "tokens_in");
                long tokensOut = longField(cost, "tokens_out");
                long cacheRead = longField(cost, "cache_read_tokens");
                long cacheCreation = longField(cost, "cache_creation_tokens");
                if (tokensIn > 0 || tokensOut > 0) {
                    req.setPromptTokens(tokensIn);
                    req.setCompletionTokens(tokensOut);
                    req.setTotalTokens(tokensIn + tokensOut);
                }
                if (cacheRead > 0) {
                    req.setCacheReadTokens(cacheRead);
                }
                if (cacheCreation > 0) {
                    req.setCacheCreationTokens(cacheCreation);
                }
                // browser_seconds is always populated and worth recording so
                // the per-tenant breakdown can show real wall-clock cost.
                Object browserSecs = cost.get("browser_seconds");
                if (browserSecs instanceof Number n && n.doubleValue() > 0) {
                    req.setDurationMs((long) (n.doubleValue() * 1000.0));
                }
            }

            // Carry the LLM block back into the snapshot so the audit row records
            // which model the agent used.
            Map<String, Object> llmConfig = mapField(nodeConfig, "llm");
            if (llmConfig != null) {
                req.setProvider(stringField(llmConfig, "provider"));
                req.setModel(stringField(llmConfig, "model"));
            }

            // Synthesise a USER/ASSISTANT timeline so the Agent Performance
            // side panel can replay this run like classify/guardrail.
            // task → USER, extracted_data → intermediate ASSISTANT messages,
            // final_result → terminal ASSISTANT. Without this the
            // conversation tab is empty even though the run completed
            // successfully. Pattern matches AgentNode.executeClassify
            // line 1022-1063.
            //
            // CRITICAL: pass resolvedParams (templates evaluated), NOT
            // nodeConfig - otherwise a templated `{{trigger.task}}` would
            // render literally in the conversation panel and the workflow
            // path would diverge visibly from the chat-tool path.
            Map<String, Object> paramsForTimeline = resolvedParams != null ? resolvedParams : nodeConfig;
            java.util.List<AgentObservabilityRequest.MessageData> messages =
                BrowserAgentModule.buildConversationMessages(paramsForTimeline, rawOutput);
            if (!messages.isEmpty()) {
                req.setMessages(messages);
            }

            agentClient.recordObservability(req);
        } catch (Exception e) {
            logger.warn("Failed to record browser agent observability for {}: {}", nodeId, e.getMessage());
        }
    }

    /**
     * Map the runner's free-form {@code stop_reason} to a canonical
     * {@link com.apimarketplace.agent.domain.AgentStopReason}. The free-form value
     * is preserved verbatim in node output for forensics; this is just for the
     * observability row.
     */
    /**
     * Delegated to {@link com.apimarketplace.orchestrator.tools.websearch.BrowserAgentStopReasonMapper}
     * - kept as a pass-through so the existing test harness
     * ({@code BrowserAgentNodeTest$MapStopReason}) keeps working without
     * changes. The shared mapper is also called from
     * {@code BrowserAgentModule.recordObservabilityFromResult} so the two
     * observability paths emit identical {@code stopReason} labels.
     */
    static String mapStopReason(String runnerReason, boolean success) {
        return com.apimarketplace.orchestrator.tools.websearch.BrowserAgentStopReasonMapper
            .map(runnerReason, success);
    }

    // ── tiny helpers - kept package-private for the spec test harness ────────

    private static String stringField(Map<String, Object> m, String key) {
        Object v = m == null ? null : m.get(key);
        return v == null ? null : String.valueOf(v);
    }

    private static long longField(Map<String, Object> m, String key) {
        Object v = m == null ? null : m.get(key);
        if (v instanceof Number n) return n.longValue();
        return 0L;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> mapField(Map<String, Object> m, String key) {
        Object v = m == null ? null : m.get(key);
        return v instanceof Map<?, ?> mm ? (Map<String, Object>) mm : null;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> listOfMaps(Map<String, Object> m, String key) {
        Object v = m == null ? null : m.get(key);
        if (!(v instanceof List<?> list)) return List.of();
        List<Map<String, Object>> out = new java.util.ArrayList<>(list.size());
        for (Object item : list) {
            if (item instanceof Map<?, ?> mm) {
                out.add((Map<String, Object>) mm);
            }
        }
        return out;
    }

    private static void copyIfPresent(Map<String, Object> src, Map<String, Object> dst, String key) {
        if (src != null && src.get(key) != null) {
            dst.put(key, src.get(key));
        }
    }

    public Map<String, Object> getNodeConfig() {
        return nodeConfig;
    }
}
