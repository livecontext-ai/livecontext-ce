package com.apimarketplace.orchestrator.execution.v2.nodes;

import com.apimarketplace.agent.config.AgentModuleResolver;
import com.apimarketplace.agent.domain.Message;
import com.apimarketplace.agent.prompt.DefaultSystemPrompts;
import com.apimarketplace.orchestrator.domain.workflow.Agent;
import com.apimarketplace.orchestrator.execution.v2.async.PendingAgent;
import com.apimarketplace.orchestrator.execution.v2.async.PendingAgentRegistry;
import com.apimarketplace.orchestrator.execution.v2.engine.ExecutionContext;
import com.apimarketplace.orchestrator.execution.v2.engine.ServiceRegistry;
import com.apimarketplace.orchestrator.execution.v2.split.SplitContextManager;
import com.apimarketplace.orchestrator.services.agent.AgentConfigResolver;
import com.apimarketplace.orchestrator.domain.WorkflowRunEntity;
import com.apimarketplace.orchestrator.repository.WorkflowRunRepository;
import com.apimarketplace.orchestrator.services.agent.AgentConversationManager;
import com.apimarketplace.orchestrator.services.agent.AgentConversationManager.StreamSession;
import com.apimarketplace.orchestrator.services.agent.AgentExecutionResult;
import com.apimarketplace.orchestrator.services.agent.AgentRequest;
import com.apimarketplace.orchestrator.services.agent.ClassifyCategory;
import com.apimarketplace.orchestrator.services.agent.ClassifyRequest;
import com.apimarketplace.orchestrator.services.agent.ClassifyResult;
import com.apimarketplace.orchestrator.services.agent.GuardrailRequest;
import com.apimarketplace.orchestrator.services.agent.GuardrailResult;
import com.apimarketplace.orchestrator.services.agent.GuardrailRule;
import com.apimarketplace.orchestrator.services.streaming.bus.WorkflowEventPublisher;
import com.apimarketplace.agent.client.dto.execution.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.apimarketplace.agent.client.queue.AgentExecutionRequestMessage;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Agent node - Executes an AI/LLM agent with tool calling capabilities.
 *
 * Supports three agent types:
 * - "agent": Full agent with tool calling (default)
 * - "classify": Content classification into categories
 * - "guardrail": Content validation against safety rules
 *
 * Flow:
 * 1. Check if dependencies are completed
 * 2. Prepare input data and resolve templates
 * 3. Route to appropriate service based on agent type
 * 4. Return result with structured output
 * 5. Successors are executed next
 */
public class AgentNode extends BaseNode {

    private static final Logger logger = LoggerFactory.getLogger(AgentNode.class);

    private final Agent agentConfig;
    private final List<String> dependencies;

    // Category targets for classify nodes: Map<port (category_0, category_1), targetNode>
    private final Map<String, ExecutionNode> categoryTargets = new HashMap<>();

    // Guardrail targets: Map<port (pass, fail), targetNode>
    private final Map<String, ExecutionNode> guardrailTargets = new HashMap<>();

    // Event publisher for real-time tool call tracking (injected)
    private WorkflowEventPublisher eventPublisher;

    // Config resolution (injected)
    private AgentConfigResolver agentConfigResolver;

    // Agent client for remote execution via agent-service (injected)
    private com.apimarketplace.agent.client.AgentClient agentClient;

    // Centralized conversation manager (injected)
    private AgentConversationManager conversationManager;

    // Run repository for org context lookup (injected)
    private WorkflowRunRepository workflowRunRepository;

    // Async queue mode support (scaling).
    // When async mode is on, this node yields with NodeExecutionResult.asyncRunning,
    // registers a PendingAgent entry, and the worker eventually delivers the result via
    // AgentAsyncCompletionService - which then calls the same sync persistence pipeline
    // used by inline execution.
    private boolean asyncQueueEnabled = false;
    private PendingAgentRegistry pendingAgentRegistry;
    private com.apimarketplace.orchestrator.services.credit.CreditBudgetService creditBudgetService;

    // Per-agent runtime overrides (executionTimeout, loop thresholds) populated by
    // ExecutionNodeFactory from the same agent-service fetch that produced agentConfig.
    // Defaults to EMPTY so legacy paths that don't set overrides keep working - the
    // downstream loop falls back to platform defaults when fields are null.
    private com.apimarketplace.orchestrator.services.agent.AgentRuntimeOverrides runtimeOverrides =
        com.apimarketplace.orchestrator.services.agent.AgentRuntimeOverrides.EMPTY;

    public AgentNode(String nodeId, Agent agentConfig, List<String> dependencies) {
        super(nodeId, NodeType.AGENT);
        this.agentConfig = agentConfig;
        this.dependencies = dependencies != null ? dependencies : List.of();
    }

    public AgentNode(String nodeId, Agent agentConfig) {
        this(nodeId, agentConfig, List.of());
    }

    /**
     * Sets the WorkflowEventPublisher for real-time tool call tracking via streaming.
     */
    public void setEventPublisher(WorkflowEventPublisher eventPublisher) {
        this.eventPublisher = eventPublisher;
    }

    /**
     * Sets the per-agent runtime overrides resolved by {@link AgentConfigResolver}.
     * Called by {@code ExecutionNodeFactory.createAgentNodes} immediately after
     * construction so the node has the fields without re-fetching the AgentDto.
     * Pass {@code null} or {@link AgentRuntimeOverrides#EMPTY} for inline-config
     * workflows (no entity reference) - fields will fall back to platform defaults.
     */
    public void setRuntimeOverrides(com.apimarketplace.orchestrator.services.agent.AgentRuntimeOverrides overrides) {
        this.runtimeOverrides = overrides != null
            ? overrides
            : com.apimarketplace.orchestrator.services.agent.AgentRuntimeOverrides.EMPTY;
    }

    /**
     * Accepts services from the registry.
     * AgentNode needs agent execution services in addition to base services.
     */
    @Override
    public void acceptServices(ServiceRegistry registry) {
        super.acceptServices(registry);
        this.eventPublisher = registry.getEventPublisher();
        this.agentConfigResolver = registry.getAgentConfigResolver();
        this.agentClient = registry.getAgentClient();
        this.conversationManager = registry.getAgentConversationManager();
        this.workflowRunRepository = registry.getWorkflowRunRepository();
        this.pendingAgentRegistry = registry.getPendingAgentRegistry();
        this.creditBudgetService = registry.getCreditBudgetService();
    }

    /**
     * Enable async queue execution mode (scaling).
     * When enabled, agent execution is offloaded to a queue instead of synchronous HTTP.
     */
    public void setAsyncQueueEnabled(boolean asyncQueueEnabled) {
        this.asyncQueueEnabled = asyncQueueEnabled;
    }

    /**
     * Check if async queue mode is enabled.
     */
    public boolean isAsyncQueueEnabled() {
        return asyncQueueEnabled;
    }

    /**
     * Adds a category target for classify nodes.
     * @param port The port name (e.g., "category_0", "category_1")
     * @param target The target node for this category
     */
    @Override
    public void addCategoryTarget(String port, ExecutionNode target) {
        categoryTargets.put(port, target);
        logger.debug("Added category target: {} -> {}", port, target.getNodeId());
    }

    /**
     * Adds a guardrail target for guardrail nodes.
     * @param port The port name ("pass" or "fail")
     * @param target The target node for this branch
     */
    @Override
    public void addGuardrailTarget(String port, ExecutionNode target) {
        guardrailTargets.put(port, target);
        logger.debug("Added guardrail target: {} -> {}", port, target.getNodeId());
    }

    /**
     * Gets all category target nodes (for classify nodes).
     * Used by findNodeById and ReadyNodeCalculator to traverse the execution tree.
     * @return Collection of all category target nodes
     */
    @Override
    public Collection<ExecutionNode> getAllCategoryTargetNodes() {
        return categoryTargets.values();
    }

    /**
     * Gets all guardrail target nodes.
     * Used by findNodeById to traverse the execution tree.
     * @return Collection of all guardrail target nodes
     */
    public Collection<ExecutionNode> getAllGuardrailTargetNodes() {
        return guardrailTargets.values();
    }

    /**
     * Returns all child nodes for tree traversal.
     * For AgentNode, this includes category targets (for classify) and guardrail targets.
     */
    @Override
    public List<ExecutionNode> getAllChildNodes() {
        List<ExecutionNode> allChildren = new ArrayList<>();
        allChildren.addAll(categoryTargets.values());
        allChildren.addAll(guardrailTargets.values());
        return allChildren;
    }

    /**
     * AgentNode is a branching node only for classify and guardrail types.
     * These types select one path based on classification result or guardrail check.
     */
    @Override
    public boolean isBranchingNode() {
        String agentType = agentConfig.type();
        if (agentType == null) {
            return false;
        }
        return "classify".equalsIgnoreCase(agentType) || "guardrail".equalsIgnoreCase(agentType);
    }

    /**
     * AgentNode is an agent node.
     */
    @Override
    public boolean isAgentNode() {
        return true;
    }

    /**
     * Classify nodes should NOT propagate skip to downstream nodes.
     * Different items may select different categories, so skip is per-item.
     * Guardrail nodes DO propagate skip (pass/fail is deterministic per item).
     */
    @Override
    public boolean shouldPropagateSkipOnBranching() {
        String agentType = agentConfig.type();
        if (agentType == null) {
            return true;
        }
        // Classify: skip is per-item, don't propagate
        // Guardrail: skip is deterministic, do propagate
        return !"classify".equalsIgnoreCase(agentType);
    }

    /**
     * Get the next nodes based on agent execution result.
     *
     * For classify nodes: returns the node for the selected category
     * For guardrail nodes: returns the pass or fail branch based on validation result
     * For regular agents: returns all successors (default behavior)
     */
    @Override
    public List<ExecutionNode> getNextNodes(NodeExecutionResult result) {
        if (result == null || result.isFailure()) {
            return List.of();
        }

        Map<String, Object> output = result.output();
        if (output == null) {
            return super.getNextNodes(result);
        }

        // Prefer in-memory agentConfig.type() - it is always set from the workflow plan and
        // is never stripped by OutputSchemaMapper. Reading node_type from the stored output
        // was unreliable because ClassifyNodeSpec / GuardrailNodeSpec did not declare it,
        // so GenericOutputSchemaMapper stripped it on every DB round-trip, causing
        // getNextNodes to fall through to the "no selection info" fallback every time.
        String configType = agentConfig.type();

        // Handle classify node branching
        if ("classify".equalsIgnoreCase(configType)) {
            return getClassifyNextNodes(output);
        }

        // Handle guardrail node branching
        if ("guardrail".equalsIgnoreCase(configType)) {
            return getGuardrailNextNodes(output);
        }

        // Default: return all successors for regular agents
        return super.getNextNodes(result);
    }

    /**
     * Get next nodes for classify execution based on selected_category.
     *
     * <p>Delegates label→index resolution to {@link #findCategoryIndex} so the runtime
     * routing path uses the same {@link com.apimarketplace.orchestrator.utils.LabelNormalizer}
     * normalization as the storage path ({@code createClassifySuccessResult}). Without
     * this delegation, an LLM returning "Réseaux" would store
     * {@code selected_category_index = -1} (post-normalization-fix) but routing would
     * still fall through with no port match - sync and async would diverge again.
     */
    private List<ExecutionNode> getClassifyNextNodes(Map<String, Object> output) {
        String selectedCategory = (String) output.get("selected_category");
        logger.debug("[getClassifyNextNodes] nodeId={}, selectedCategory={}, categoryTargets.size={}, categoryTargets={}",
            nodeId, selectedCategory, categoryTargets.size(), categoryTargets.keySet());

        if (categoryTargets.isEmpty()) {
            if (!successors.isEmpty()) {
                logger.debug("Classify node {} has no category targets configured - using linear successors", nodeId);
                return List.copyOf(successors);
            }
            logger.warn("Classify node {} has no category targets configured and no linear successors configured - no branch will be followed", nodeId);
            return List.of();
        }

        if (selectedCategory == null) {
            // Distinguish reconstruction-time speculative traversal (output empty:
            // ReadyNodeCalculator probes getNextNodes BEFORE the agent has executed in
            // this slice; success path reruns ~1s later with full output) from a real
            // classify failure (output populated but selected_category absent - LLM
            // didn't return one or normalization stripped it).
            if (output.isEmpty()) {
                logger.debug("Classify node {} probed during reconstruction (no output yet) - no branch returned", nodeId);
            } else {
                logger.warn("Classify node {} produced no selected_category despite populated output (LLM contract broken). " +
                    "outputKeys={} - no branch will be followed", nodeId, output.keySet());
            }
            return List.of();
        }

        int idx = findCategoryIndex(selectedCategory);
        if (idx < 0) {
            logger.warn("Classify node {} could not find matching category for '{}'", nodeId, selectedCategory);
            return List.of();
        }

        String port = "category_" + idx;
        ExecutionNode targetNode = categoryTargets.get(port);
        if (targetNode == null) {
            logger.warn("Classify node {} has no target for port {}", nodeId, port);
            return List.of();
        }
        logger.info("🏷️ Classify {} selected category '{}' (index {}), routing to: {}",
            nodeId, selectedCategory, idx, targetNode.getNodeId());
        return List.of(targetNode);
    }

    /**
     * Get next nodes for guardrail execution based on passed/failed result.
     */
    private List<ExecutionNode> getGuardrailNextNodes(Map<String, Object> output) {
        Object passedObj = output.get("passed");
        if (guardrailTargets.isEmpty()) {
            if (!successors.isEmpty()) {
                logger.debug("Guardrail node {} has no guardrail targets configured - using linear successors", nodeId);
                return List.copyOf(successors);
            }
            logger.warn("Guardrail node {} has no guardrail targets or linear successors configured - no branch will be followed", nodeId);
            return List.of();
        }

        if (passedObj == null) {
            logger.warn("Guardrail node {} has no passed result - no branch will be followed", nodeId);
            return List.of();
        }

        boolean passed = Boolean.TRUE.equals(passedObj);
        String port = passed ? "pass" : "fail";
        ExecutionNode targetNode = guardrailTargets.get(port);

        if (targetNode != null) {
            logger.info("🛡️ Guardrail {} result: passed={}, routing to: {}", nodeId, passed, targetNode.getNodeId());
            return List.of(targetNode);
        }

        logger.warn("Guardrail node {} has no target for port {}", nodeId, port);
        return List.of();
    }

    /**
     * Get the skipped branch nodes for classify/guardrail agents.
     * Used for skip propagation to mark unselected branches as skipped.
     */
    @Override
    public List<ExecutionNode> getSkippedChildNodes(NodeExecutionResult result) {
        if (result == null || result.output() == null) {
            return List.of();
        }

        Map<String, Object> output = result.output();
        // Use in-memory agentConfig.type() - node_type in the stored output is stripped by
        // GenericOutputSchemaMapper when ClassifyNodeSpec / GuardrailNodeSpec don't declare it.
        String configType = agentConfig.type();

        if ("classify".equalsIgnoreCase(configType)) {
            return getSkippedClassifyNodes(output);
        }

        if ("guardrail".equalsIgnoreCase(configType)) {
            return getSkippedGuardrailNodes(output);
        }

        return List.of();
    }

    private List<ExecutionNode> getSkippedClassifyNodes(Map<String, Object> output) {
        String selectedCategory = (String) output.get("selected_category");
        List<Map<String, Object>> categories = agentConfig.classifyCategories();

        if (selectedCategory == null || categories == null) {
            return new ArrayList<>(categoryTargets.values());
        }

        // Use findCategoryIndex (LabelNormalizer-backed) for the "selected" check so
        // skip-propagation lines up with the routing decision in getClassifyNextNodes.
        // If the LLM label doesn't normalize to any configured category, idx=-1 and
        // ALL category targets are correctly returned as skipped.
        int selectedIndex = findCategoryIndex(selectedCategory);
        List<ExecutionNode> skipped = new ArrayList<>();
        for (int i = 0; i < categories.size(); i++) {
            if (i == selectedIndex) {
                continue;
            }
            String port = "category_" + i;
            ExecutionNode targetNode = categoryTargets.get(port);
            if (targetNode != null) {
                skipped.add(targetNode);
            }
        }
        return skipped;
    }

    private List<ExecutionNode> getSkippedGuardrailNodes(Map<String, Object> output) {
        Object passedObj = output.get("passed");
        if (passedObj == null) {
            return new ArrayList<>(guardrailTargets.values());
        }

        boolean passed = Boolean.TRUE.equals(passedObj);
        String skippedPort = passed ? "fail" : "pass";
        ExecutionNode skippedNode = guardrailTargets.get(skippedPort);

        return skippedNode != null ? List.of(skippedNode) : List.of();
    }

    /**
     * Returns all branch targets mapped by port for port-qualified edge emission.
     * For classify: { "category_0": [node], "category_1": [node], ... }
     * For guardrail: { "pass": [node], "fail": [node] }
     */
    @Override
    public Map<String, List<ExecutionNode>> getBranchTargetsByPort() {
        Map<String, List<ExecutionNode>> result = new HashMap<>();
        for (Map.Entry<String, ExecutionNode> entry : categoryTargets.entrySet()) {
            result.computeIfAbsent(entry.getKey(), k -> new ArrayList<>()).add(entry.getValue());
        }
        for (Map.Entry<String, ExecutionNode> entry : guardrailTargets.entrySet()) {
            result.computeIfAbsent(entry.getKey(), k -> new ArrayList<>()).add(entry.getValue());
        }
        return result;
    }

    /**
     * Returns the selected port based on classify/guardrail execution result.
     * For classify: "category_N" based on selected_category_index
     * For guardrail: "pass" or "fail" based on passed boolean
     */
    @Override
    public String getSelectedPort(NodeExecutionResult result) {
        if (result == null || result.output() == null) {
            return null;
        }

        Map<String, Object> output = result.output();
        // Use in-memory agentConfig.type() - node_type in the stored output is stripped by
        // GenericOutputSchemaMapper when ClassifyNodeSpec / GuardrailNodeSpec don't declare it.
        String configType = agentConfig.type();

        if ("classify".equalsIgnoreCase(configType)) {
            Object indexObj = output.get("selected_category_index");
            if (indexObj instanceof Integer idx && idx >= 0) {
                return "category_" + idx;
            }
            // Fallback: resolve index from selected_category label
            String selectedCategory = (String) output.get("selected_category");
            if (selectedCategory != null) {
                int idx = findCategoryIndex(selectedCategory);
                if (idx >= 0) {
                    return "category_" + idx;
                }
            }
            return null;
        }

        if ("guardrail".equalsIgnoreCase(configType)) {
            Object passedObj = output.get("passed");
            if (passedObj != null) {
                return Boolean.TRUE.equals(passedObj) ? "pass" : "fail";
            }
            return null;
        }

        return null;
    }

    @Override
    protected List<String> getDependencies(ExecutionContext context) {
        return dependencies;
    }

    @Override
    public NodeExecutionResult execute(ExecutionContext context) {
        long startTime = System.currentTimeMillis();
        String agentType = agentConfig.type() != null ? agentConfig.type().toLowerCase() : "agent";
        // Captured outside the try so failure paths (budget exhausted, thrown exceptions)
        // can still attach resolved_params to the step record - without this, the
        // inspector "Resolved parameters" panel is blank whenever an agent fails.
        Map<String, Object> inputData = Map.of();

        logger.info("🤖 Agent node executing: nodeId={}, label={}, type={}, itemId={}",
            nodeId, agentConfig.label(), agentType, context.itemId());

        // Check credit budget before execution
        if (agentClient != null && agentConfig.agentConfigId() != null) {
            try {
                UUID agentEntityId = UUID.fromString(agentConfig.agentConfigId());
                com.apimarketplace.agent.client.dto.AgentDto agentDto =
                    agentClient.resolveAgentConfig(agentEntityId, context.tenantId(), context.organizationId());
                if (agentDto != null && agentDto.getCreditBudget() != null) {
                    // Trigger periodic reset if needed
                    agentClient.checkAndResetBudget(agentEntityId);
                    // Re-fetch after potential reset
                    agentDto = agentClient.resolveAgentConfig(agentEntityId, context.tenantId(), context.organizationId());
                    if (agentDto != null && agentDto.getCreditBudget() != null
                            && agentDto.getCreditsConsumed() != null
                            && agentDto.getCreditsConsumed().compareTo(agentDto.getCreditBudget()) >= 0) {
                        long duration = System.currentTimeMillis() - startTime;
                        String budgetError = "BUDGET_EXHAUSTED: Agent '" + agentConfig.label()
                                + "' has reached its credit budget of " + agentDto.getCreditBudget() + " credits"
                                + " (consumed: " + agentDto.getCreditsConsumed() + ")";
                        Map<String, Object> budgetOutput = new HashMap<>();
                        budgetOutput.put("resolved_params", buildResolvedInputForInspector(context, Map.of()));
                        budgetOutput.put("error", budgetError);
                        return NodeExecutionResult.failureWithOutput(nodeId, budgetError, budgetOutput, duration);
                    }
                }
            } catch (Exception e) {
                logger.warn("Budget check failed for agent {}, proceeding with execution: {}",
                    agentConfig.agentConfigId(), e.getMessage());
            }
        }

        try {
            // Prepare input data
            inputData = prepareInput(context);

            logger.debug("Agent input prepared: nodeId={}, inputKeys={}",
                nodeId, inputData.keySet());

            // Async queue path: offload to worker pool and yield with asyncRunning
            // (visible status stays RUNNING). The completion is delivered later via
            // AgentAsyncCompletionService, which calls back into the same sync
            // persistence pipeline as inline execution.
            if (asyncQueueEnabled && pendingAgentRegistry != null) {
                return executeAgentAsyncQueue(context, inputData, agentType);
            }

            // Route to appropriate service based on agent type
            NodeExecutionResult result = switch (agentType) {
                case "classify" -> executeClassify(context, inputData, startTime);
                case "guardrail" -> executeGuardrail(context, inputData, startTime);
                default -> executeAgent(context, inputData, startTime);
            };

            // Persist resolved input params so they are visible in the inspector panel
            Map<String, Object> enrichedOutput = new HashMap<>(result.output() != null ? result.output() : Map.of());
            enrichedOutput.put("resolved_params", buildResolvedInputForInspector(context, inputData));

            if (result.isSuccess()) {
                return NodeExecutionResult.success(nodeId, enrichedOutput, result.durationMs());
            } else {
                return NodeExecutionResult.failureWithOutput(nodeId, result.errorMessage().orElse(null), enrichedOutput, result.durationMs());
            }

        } catch (Exception e) {
            long duration = System.currentTimeMillis() - startTime;
            logger.error("❌ {} execution error: nodeId={}, error={}",
                agentType, nodeId, e.getMessage(), e);

            // Preserve resolved inputs so the inspector can show the prompt/model/etc.
            // that the agent tried to run with when it blew up.
            Map<String, Object> failOutput = new HashMap<>();
            failOutput.put("resolved_params", buildResolvedInputForInspector(context, inputData));
            failOutput.put("error", e.getMessage() != null ? e.getMessage() : "");
            return NodeExecutionResult.failureWithOutput(nodeId, e.getMessage(), failOutput, duration);
        }
    }

    /**
     * Async queue execution path: registers a {@link PendingAgent} entry and yields with
     * {@link NodeExecutionResult#asyncRunning} so the engine stops traversal without
     * marking the node complete. The visible status stays RUNNING - the implementation
     * mechanism (worker pool) is hidden from the frontend.
     *
     * <p>The actual queue push is performed by {@code V2ExecutionEventService.emitNodeAsyncRunning},
     * which reads the {@code queueMessage} we attach to the result output.</p>
     *
     * <p>When the worker delivers a result, {@code AgentAsyncCompletionService.onAgentResult}
     * looks up the {@link PendingAgent} entry by correlationId, restores split context, and
     * calls the same {@code StepCompletionOrchestrator.completeStep} pipeline that inline
     * execution uses - keeping {@code selectedBranch} derivation and other agent-result
     * enrichment in one place rather than duplicating it on the async path.</p>
     */
    private NodeExecutionResult executeAgentAsyncQueue(ExecutionContext context, Map<String, Object> inputData, String agentType) {
        String correlationId = UUID.randomUUID().toString();
        String runId = context.runId();
        String itemId = context.itemId();
        String provider = agentConfig.provider();
        String model = agentConfig.model();

        logger.info("Async agent execution: nodeId={}, agentType={}, correlationId={}, provider={}, model={}",
            nodeId, agentType, correlationId, provider, model);

        // Delta 2 - pre-flight budget gate BEFORE enqueueing the work. Without this, the
        // scaling path enqueues a job that a worker will pick up and spend tokens on before
        // the tenant's balance is even consulted. The gate uses inputData-based estimation
        // since AgentRequest isn't built here (workers resolve it themselves).
        NodeExecutionResult gateDenial = preflightDenialOrNull(context, provider, model,
            estimateNonAgentPromptTokens(inputData), System.currentTimeMillis());
        if (gateDenial != null) return gateDenial;

        // Resolve dag/epoch context. These are needed so the completion service can
        // call completeStep with the same epoch/trigger the inline path would have used.
        String dagTriggerId = SignalContextResolver.resolveDagTriggerId(nodeId, null, context);
        int epoch = SignalContextResolver.resolveEpoch(0, context);

        // If running inside a split, snapshot the per-item context so the completion
        // service can restore it before persistence. The in-memory SplitContext on the
        // engine side is gone by the time the worker delivers the result.
        //
        // IMPORTANT: current_split_id holds the SCOPED key (e.g., "core:split_messages:0"),
        // but SplitContextManager.findActiveContext looks up contexts using
        // buildContextKey(BASE_NODE_ID, workflowItemIndex). We extract the BASE node id
        // before persisting; otherwise the restored context lives under a key the executor
        // will never look up.
        Map<String, Object> splitItemData = null;
        if (context.getGlobalData("current_split_id").isPresent()) {
            String scopedSplitId = context.getGlobalData("current_split_id")
                .map(Object::toString).orElse(null);
            String splitNodeId = scopedSplitId != null
                ? SplitContextManager.extractBaseSplitNodeId(scopedSplitId)
                : null;
            Object items = context.getGlobalData("items").orElse(null);
            int splitItemIndex = context.getGlobalData("index")
                .map(v -> v instanceof Number n ? n.intValue() : 0).orElse(0);

            // The scoped split key has the form "<splitNodeId>:<workflowItemIndex>" (top-level)
            // or "<splitNodeId>:<workflowItemIndex>/sN" (nested). The completion service uses
            // workflowItemIndex to look up the SplitContext for storeResults - without it the
            // sealed batch can't be re-injected and downstream templates won't resolve.
            int workflowItemIndex = parseWorkflowItemIndexFromScopedKey(scopedSplitId);

            splitItemData = new HashMap<>();
            splitItemData.put("splitNodeId", splitNodeId);
            splitItemData.put("items", items);
            splitItemData.put("itemIndex", splitItemIndex);
            splitItemData.put("workflowItemIndex", workflowItemIndex);
        }

        // Register the pending entry BEFORE returning. The engine event service will
        // enqueue the worker task immediately after we yield - if the worker happens to
        // be very fast, the result could arrive before we register, so the registry is
        // populated first. The queue push happens after we return from this method, so
        // there is no race window where the worker runs without an entry to consume.
        // Snapshot the resolved input BEFORE yielding - the async worker result only
        // contains the agent output (selected_category, passed, etc.), not the input.
        // Without this, StepDataPersistenceService.extractInputData() finds nothing and
        // the inspector "Resolved parameters" panel stays empty for async agents.
        Map<String, Object> resolvedInputData = buildResolvedInputForInspector(context, inputData);

        // Conversation persistence parity with the inline executeAgent path (lines ~860-901).
        // Resolve the agent's conversation, save the user prompt, and start a stream BEFORE
        // we yield - so the conversation panel shows the prompt immediately and the
        // assistant message can be appended at delivery time using the same conversationId.
        // Scope: only the regular "agent" type writes to a conversation. classify/guardrail
        // are routing nodes - they don't have a user-facing conversation, so we skip the
        // resolution to avoid creating empty agent-entity conversations for them.
        String conversationId = null;
        String streamId = null;
        String executionId = "agent".equals(agentType) ? UUID.randomUUID().toString() : null;
        if ("agent".equals(agentType) && conversationManager != null) {
            Object rawConvId = context.triggerData() != null
                ? context.triggerData().get("conversationId") : null;
            String triggerConversationId = rawConvId != null ? rawConvId.toString() : null;
            boolean skipUserPrompt;
            if (triggerConversationId != null && !triggerConversationId.isBlank()) {
                conversationId = triggerConversationId;
                skipUserPrompt = true;
            } else {
                conversationId = conversationManager.ensureConversation(
                    agentConfig.agentConfigId(), context.tenantId(), agentConfig.label(),
                    resolveOrgId(context));
                skipUserPrompt = false;
            }
            if (conversationId != null) {
                StreamSession session = conversationManager.startExecution(
                    conversationId, resolveEffectiveUserPrompt(context, inputData),
                    context.tenantId(), executionId, model, skipUserPrompt);
                if (session != null) {
                    streamId = session.streamId();
                }
            }
        }

        // Snapshot the FINAL system + user prompts (with template substitution and
        // modular-prefix augmentation) onto the PendingAgent so the async completion
        // service can populate the Agent Performance metric view with the same data
        // the inline / sub-agent / chat paths persist. The async worker DTO doesn't
        // echo these back, and the raw agentConfig.systemPrompt() template lacks
        // both the modular prefix and variable substitution - so without this snapshot
        // the metric "system prompt" cell shows a stale value and "user message" is
        // empty for async agents.
        //
        // Scoped to agentType="agent" - classify/guardrail branches in
        // AgentAsyncCompletionService read systemPrompt from the worker's flat
        // result DTO instead, so a snapshot here would just be wasted Redis bytes
        // (two strings round-tripped per enqueue and never consumed).
        String snapshotSystemPrompt = null;
        String snapshotUserPrompt = null;
        if ("agent".equals(agentType)) {
            snapshotSystemPrompt = resolveEffectiveSystemPromptForObservability(context);
            snapshotUserPrompt = resolveEffectiveUserPrompt(context, inputData);
        }

        PendingAgent pending = new PendingAgent(
            correlationId,
            runId,
            nodeId,
            agentConfig.label(),
            dagTriggerId,
            epoch,
            context.itemIndex(),
            itemId,
            agentType,
            context.tenantId(),
            splitItemData,
            resolvedInputData,
            conversationId,
            streamId,
            executionId,
            model,
            snapshotSystemPrompt,
            snapshotUserPrompt,
            Instant.now(),
            context.organizationId(),
            // Loop iteration this body execution runs at (null outside a loop / on the first body
            // entry) so the async completion records each iteration's step at a distinct iteration.
            extractCurrentIteration(context));
        pendingAgentRegistry.register(pending);

        // Build the queue message payload. This must mirror what the inline path
        // (executeClassify / executeGuardrail / buildAgentRequest) would have sent,
        // so the worker deserializes into a fully-populated ClassifyRequestDto /
        // GuardrailRequestDto / AgentExecutionRequestDto. Before this was fixed,
        // classify/guardrail in async mode ran with categories=0 / rules=0 because
        // classifyCategories and guardrailRules were never injected here - the LLM
        // then hallucinated off-list labels ("Uncategorized", "N/A") and split
        // branch routing failed silently.
        Map<String, Object> requestPayload = new HashMap<>(inputData);
        requestPayload.put("provider", provider);
        requestPayload.put("model", model);
        requestPayload.put("tenantId", context.tenantId());
        requestPayload.put("agentType", agentType);
        if (agentConfig.temperature() != null) {
            requestPayload.put("temperature", agentConfig.temperature());
        }
        if (agentConfig.maxTokens() != null) {
            requestPayload.put("maxTokens", agentConfig.maxTokens());
        }
        if (agentConfig.maxIterations() != null) {
            requestPayload.put("maxIterations", agentConfig.maxIterations());
        }
        if (agentConfig.agentConfigId() != null) {
            requestPayload.put("agentEntityId", agentConfig.agentConfigId());
        }
        Map<String, Object> credentials = "agent".equals(agentType)
            ? buildBaseAgentCredentials(context, inputData)
            : copyStringKeyedMap(requestPayload.get("credentials"));
        if ("agent".equals(agentType)) {
            Map<String, Object> asyncToolsConfig = loadToolsConfig(context);
            applyToolsConfigCredentials(credentials, asyncToolsConfig);
            if (executionId != null && !executionId.isBlank()) {
                credentials.put("__executionId__", executionId);
                requestPayload.put("executionId", executionId);
            }
            requestPayload.put("variables", buildAgentVariables(context, inputData));
            requestPayload.put("autoDiscoverTools", agentConfig.tools() == null || agentConfig.tools().isEmpty());
            requestPayload.put("maxTools", agentConfig.maxTools());
            // Scope the remote core tool SCHEMAS to the agent's modules - parity with the inline
            // executeAgentRemotely path (which sets request.enabledModules via buildAgentRequest)
            // and with chat. Without this the async worker deserialized enabledModules=null and
            // fell back to the UNFILTERED full core tool set, billing every schema on every
            // iteration regardless of toolsConfig.mode. null toolsConfig ⇒ omit ⇒ unrestricted.
            if (asyncToolsConfig != null) {
                requestPayload.put("enabledModules", new ArrayList<>(resolveEnabledModules(asyncToolsConfig)));
            }
            requestPayload.put("executionTimeout", runtimeOverrides.executionTimeout());
            requestPayload.put("loopIdenticalStop", runtimeOverrides.loopIdenticalStop());
            requestPayload.put("loopConsecutiveStop", runtimeOverrides.loopConsecutiveStop());
            // Per-agent inactivity watchdog window rides on the credentials map (not a new positional
            // DTO field) so agent-service's AgentLoopService.resolveInactivityWindowMs honors it.
            if (runtimeOverrides.inactivityTimeout() != null) {
                credentials.put("__inactivityTimeoutSeconds__", runtimeOverrides.inactivityTimeout());
            }
        }
        applyOrgContext(credentials, context);
        if (!credentials.isEmpty()) {
            requestPayload.put("credentials", credentials);
        }
        Object resolvedOrgId = credentials.get("__orgId__");
        if (resolvedOrgId instanceof String orgId && !orgId.isBlank()) {
            requestPayload.put("organizationId", orgId);
        }
        Object resolvedOrgRole = credentials.get("__orgRole__");
        if (resolvedOrgRole instanceof String orgRole && !orgRole.isBlank()) {
            requestPayload.put("organizationRole", orgRole);
        }
        requestPayload.put("source", "WORKFLOW");

        // Streaming metadata - MUST mirror the inline path in executeAgentRemotely
        // (AgentExecutionRequestDto construction around lines 2042-2051). Without these
        // fields, AgentRemoteExecutionService.executeAgent on the worker side sees
        // request.streamChannelId() == null, falls into the fallback `else if
        // (agentEntityId != null)` branch, and calls wrapWithActivityPublishing(null,
        // ...). With a null delegate, onChunk/onThinking become no-ops - fleet
        // execution_started/completed + tool_call events still publish via
        // agentActivityPublisher (they're outside the callback), but the regular
        // workflow streaming channel receives nothing and tool-call events lose their
        // (runId, nodeId, itemIndex, iteration) routing keys used by the frontend SSE
        // subscriber, so the live view in fleet and workflow goes silent during the
        // agent's execution. The async queue only carries workflow-mode agents
        // (conversation-mode agents are dispatched inline through executeAgentRemotely),
        // so streamingFormat is always "workflow" here.
        requestPayload.put("streamChannelId", runId);
        requestPayload.put("streamingFormat", "workflow");
        requestPayload.put("nodeId", nodeId);
        requestPayload.put("itemIndex", context.itemIndex());
        requestPayload.put("workflowRunId", runId);
        // Conversation channel for the bridge/CLI path. The bridge (RedisPublisher) ignores
        // streamingFormat and publishes tool_call/tool_result/content/done to
        // ws:conversation:{conversationId} ONLY when conversationId is present. The inline
        // path already sends it (executeAgentRemotely), but the async queue payload dropped
        // it, so a bridge agent's conversation panel (the node's bottom button) sat in
        // "thinking" with no live tool cards until the final DB reload. Forward the same
        // conversationId we resolved above so async bridge runs stream live like inline runs.
        // Null for classify/guardrail (no user-facing conversation) and when no conversation
        // manager is wired. Inert on the direct-API worker, which keeps the workflow-format
        // callback (streamingFormat stays "workflow") and never reads conversationId.
        if (conversationId != null && !conversationId.isBlank()) {
            requestPayload.put("conversationId", conversationId);
        }
        Integer loopIteration = extractCurrentIteration(context);
        if (loopIteration != null) {
            requestPayload.put("loopIteration", loopIteration);
        }

        // Resolve prompt templates so {{vars}} get substituted. Regular agents use
        // the same final modular system prompt and user prompt as buildAgentRequest();
        // classify/guardrail keep their compact DTO prompts.
        if ("agent".equals(agentType)) {
            if (snapshotUserPrompt != null) {
                requestPayload.put("prompt", snapshotUserPrompt);
            }
            if (snapshotSystemPrompt != null) {
                requestPayload.put("systemPrompt", snapshotSystemPrompt);
            }
        } else {
            String resolvedPrompt = resolvePromptTemplate(agentConfig.prompt(), context);
            if (resolvedPrompt != null) {
                requestPayload.put("prompt", resolvedPrompt);
            }
            if (agentConfig.systemPrompt() != null) {
                requestPayload.put("systemPrompt", resolvePromptTemplate(agentConfig.systemPrompt(), context));
            }
        }

        // Type-specific fields - match the DTO the worker will deserialize into.
        // classify  → ClassifyRequestDto(content, categories, ...)
        // guardrail → GuardrailRequestDto(content, rules, action, ...)
        // agent     → AgentExecutionRequestDto with the same credentials/variables
        //             envelope as the inline workflow path.
        switch (agentType) {
            case "classify" -> {
                requestPayload.put("content", resolveContent(inputData));
                List<Map<String, Object>> categories = agentConfig.classifyCategories();
                if (categories != null && !categories.isEmpty()) {
                    List<Map<String, Object>> categoryDtos = new ArrayList<>(categories.size());
                    for (Map<String, Object> cat : categories) {
                        String label = (String) cat.get("label");
                        if (label == null) continue;
                        Map<String, Object> dto = new HashMap<>();
                        dto.put("label", label);
                        Object description = cat.get("description");
                        if (description != null) dto.put("description", description);
                        categoryDtos.add(dto);
                    }
                    requestPayload.put("categories", categoryDtos);
                }
            }
            case "guardrail" -> {
                requestPayload.put("content", resolveContent(inputData));
                // Mirror the inline normalization in executeGuardrail (lines 942-962):
                // use `type` as id when the id is generic "rule-N", and look up
                // description in the nested `config` map if absent at top level.
                List<Map<String, Object>> rules = agentConfig.guardrailRules();
                if (rules != null && !rules.isEmpty()) {
                    List<Map<String, Object>> ruleDtos = new ArrayList<>(rules.size());
                    for (Map<String, Object> rule : rules) {
                        String id = (String) rule.get("id");
                        String type = (String) rule.get("type");
                        if (type != null && !type.isBlank() && id != null && id.matches("rule-\\d+")) {
                            id = type;
                        }
                        String description = (String) rule.get("description");
                        if (description == null) {
                            Object cfg = rule.get("config");
                            if (cfg instanceof Map<?, ?> cfgMap) {
                                description = (String) cfgMap.get("description");
                            }
                        }
                        if (id == null) continue;
                        Map<String, Object> dto = new HashMap<>();
                        dto.put("id", id);
                        if (description != null) dto.put("description", description);
                        ruleDtos.add(dto);
                    }
                    requestPayload.put("rules", ruleDtos);
                }
                // Action: from params, default "flag" - same as inline (line 936).
                Map<String, Object> params = agentConfig.params();
                String action = params != null ? (String) params.get("action") : null;
                requestPayload.put("action", action != null && !action.isBlank() ? action : "flag");
            }
            default -> {
                // Regular agent path - existing generic payload is left as-is.
            }
        }

        AgentExecutionRequestMessage queueMessage = AgentExecutionRequestMessage.create(
            correlationId, runId, nodeId, context.tenantId(),
            agentType, provider, model, requestPayload);

        // Output payload - V2ExecutionEventService.emitNodeAsyncRunning reads queueMessage
        // from here and pushes it to the worker queue. The other fields are informational
        // (visible in the running snapshot, useful for debugging).
        Map<String, Object> output = new HashMap<>();
        output.put("correlationId", correlationId);
        output.put("agentType", agentType);
        output.put("provider", provider);
        output.put("model", model);
        output.put("async", true);
        output.put("queueMessage", queueMessage);

        logger.info("Agent node yielding (async running): nodeId={}, correlationId={}", nodeId, correlationId);

        return NodeExecutionResult.asyncRunning(nodeId, correlationId, agentType, output);
    }

    /**
     * Parse the workflow item index from a scoped split context key.
     *
     * <p>Scoped key formats:
     * <ul>
     *   <li>top-level: {@code "<splitNodeId>:<workflowItemIndex>"} → returns {@code workflowItemIndex}</li>
     *   <li>nested:    {@code "<splitNodeId>:<workflowItemIndex>/sN"} → returns {@code workflowItemIndex}</li>
     * </ul>
     *
     * <p>Returns 0 if the key is null/malformed - top-level splits without an explicit
     * workflow item index default to 0, matching {@link SplitContextManager#buildContextKey}.
     */
    private static int parseWorkflowItemIndexFromScopedKey(String scopedKey) {
        if (scopedKey == null || scopedKey.isEmpty()) {
            return 0;
        }
        // Strip nested suffix if present.
        String stripped = scopedKey;
        int slashIdx = stripped.indexOf('/');
        if (slashIdx > 0) {
            stripped = stripped.substring(0, slashIdx);
        }
        int lastColon = stripped.lastIndexOf(':');
        if (lastColon < 0 || lastColon == stripped.length() - 1) {
            return 0;
        }
        try {
            return Integer.parseInt(stripped.substring(lastColon + 1));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * Execute standard agent with tool calling.
     */
    private NodeExecutionResult executeAgent(ExecutionContext context, Map<String, Object> inputData, long startTime) {
        // Check if AgentClient is available for remote execution
        if (agentClient == null) {
            logger.error("❌ AgentClient not injected for agent: {}", nodeId);
            return NodeExecutionResult.failure(nodeId,
                "AgentClient not available",
                System.currentTimeMillis() - startTime);
        }

        // Generate executionId upfront so ALL messages (user, assistant, tools) share the same ID
        String executionId = UUID.randomUUID().toString();

        // Build AgentRequest from agent config
        AgentRequest request = buildAgentRequest(context, inputData);

        // Delta 2 - pre-flight budget gate (symmetry with ChatControllerV3). Uses the shared
        // helper so classify/guardrail/async paths gate on the same contract.
        NodeExecutionResult gateDenial = preflightDenialOrNull(context, request.provider(), request.model(),
            estimatePromptTokens(request), startTime);
        if (gateDenial != null) return gateDenial;

        logger.info("🚀 Executing agent: provider={}, model={}, nodeId={}, executionId={}",
            request.provider(), request.model(), nodeId, executionId);

        // Resolve conversation for streaming.
        // Chat trigger → use trigger's conversationId (workflow conversation, separate from agent entity)
        // No trigger conv → use agent entity conversation (webhook/schedule/manual triggers)
        // Agent entity conversation is NEVER written to from workflow context - it's reserved for direct AI chat.
        Object rawConvId = context.triggerData() != null
            ? context.triggerData().get("conversationId") : null;
        String triggerConversationId = rawConvId != null ? rawConvId.toString() : null;

        String conversationId;
        boolean skipUserPrompt;
        if (triggerConversationId != null && !triggerConversationId.isBlank()) {
            // Chat trigger: use workflow conversation, user prompt already saved by frontend
            conversationId = triggerConversationId;
            skipUserPrompt = true;
        } else {
            // Non-chat trigger: use agent entity conversation as fallback for streaming
            conversationId = conversationManager != null
                ? conversationManager.ensureConversation(agentConfig.agentConfigId(), context.tenantId(), agentConfig.label(), resolveOrgId(context))
                : null;
            skipUserPrompt = false;
        }

        StreamSession streamSession = conversationManager != null
            ? conversationManager.startExecution(conversationId, request.prompt(), context.tenantId(), executionId, request.model(), skipUserPrompt)
            : null;

        // Dispatch to agent-service via HTTP
        Integer iteration = extractCurrentIteration(context);
        AgentExecutionResult agentResult = executeAgentRemotely(request, context, iteration, streamSession, executionId);

        // Record observability data via agent-service
        if (agentClient != null) {
            try {
                var obsRequest = buildObservabilityRequest(agentResult, agentConfig, context, nodeId, "agent",
                    request.systemPrompt(), request.prompt(), Boolean.TRUE.equals(agentConfig.withMemory()), conversationId);
                agentClient.recordObservability(obsRequest);
            } catch (Exception e) {
                logger.warn("Failed to record agent observability data: {}", e.getMessage());
            }
        }

        long duration = System.currentTimeMillis() - startTime;

        // Save assistant response + complete stream
        if (conversationManager != null) {
            conversationManager.saveAssistantResponse(conversationId, context.tenantId(), agentResult, executionId);
            conversationManager.completeStream(streamSession, agentResult);
        }

        // Convert to NodeExecutionResult
        if (agentResult.isSuccess()) {
            logger.info("✅ Agent executed successfully: nodeId={}, iterations={}, duration={}ms",
                nodeId, agentResult.getIterations(), duration);

            return createSuccessResult(agentResult, duration, context);
        } else if (agentResult.getStopReason() == com.apimarketplace.agent.domain.AgentStopReason.STOPPED_BY_USER) {
            // Stopped by user: treat as partial success to preserve content, tokens, and tool results
            logger.info("⏹️ Agent stopped by user: nodeId={}, iterations={}, duration={}ms",
                nodeId, agentResult.getIterations(), duration);

            return createSuccessResult(agentResult, duration, context);
        } else {
            String errorMsg = agentResult.getError() != null
                ? agentResult.getError()
                : "Agent execution failed";
            logger.error("❌ Agent execution failed: nodeId={}, error={}",
                nodeId, errorMsg);
            return NodeExecutionResult.failure(nodeId, errorMsg, duration);
        }
    }

    /**
     * Extract current iteration from context for tool call events.
     */
    private Integer extractCurrentIteration(ExecutionContext context) {
        if (context == null) {
            return null;
        }
        // Check for loop iteration in context (set by BackEdgeHandler when a loop advances)
        return context.getGlobalData("current_loop_iteration")
            .filter(obj -> obj instanceof Number)
            .map(obj -> ((Number) obj).intValue())
            .orElse(null);
    }

    /**
     * Execute classify agent for content classification.
     */
    @SuppressWarnings("unchecked")
    private NodeExecutionResult executeClassify(ExecutionContext context, Map<String, Object> inputData, long startTime) {
        if (agentClient == null) {
            logger.error("❌ AgentClient not injected for classify: {}", nodeId);
            return NodeExecutionResult.failure(nodeId,
                "AgentClient not available",
                System.currentTimeMillis() - startTime);
        }

        // Delta 2 - pre-flight budget gate (shared helper, same contract as executeAgent).
        NodeExecutionResult gateDenial = preflightDenialOrNull(context,
            agentConfig.provider(), agentConfig.model(),
            estimateNonAgentPromptTokens(inputData), startTime);
        if (gateDenial != null) return gateDenial;

        // Build ClassifyRequest from agent config
        ClassifyRequest.Builder requestBuilder = ClassifyRequest.builder()
            .provider(agentConfig.provider())
            .model(agentConfig.model())
            .temperature(agentConfig.temperature())
            .maxTokens(agentConfig.maxTokens())
            .tenantId(context.tenantId())
            .agentEntityId(agentConfig.agentConfigId());

        // Get content from params or input
        String content = resolveContent(inputData);
        requestBuilder.content(content);

        // Log resolved content for debugging (truncate if too long)
        String contentPreview = content != null && content.length() > 200
            ? content.substring(0, 200) + "..."
            : content;
        logger.info("🏷️ Classify content resolved: nodeId={}, contentLength={}, preview='{}'",
            nodeId, content != null ? content.length() : 0, contentPreview);

        // Get prompt
        String prompt = agentConfig.prompt();
        if (prompt != null) {
            prompt = resolvePromptTemplate(prompt, context);
        }
        requestBuilder.prompt(prompt);

        // Get categories from agent config
        List<Map<String, Object>> categories = agentConfig.classifyCategories();
        if (categories != null) {
            for (Map<String, Object> cat : categories) {
                String label = (String) cat.get("label");
                String description = (String) cat.get("description");
                if (label != null) {
                    requestBuilder.addCategory(label, description);
                }
            }
        }

        ClassifyRequest request = requestBuilder.build();

        logger.info("🏷️ Executing classify: provider={}, model={}, categories={}",
            request.provider(), request.model(), request.categories().size());

        // Execute classification via agent-service
        ClassifyResult result = executeClassifyRemotely(request);

        // Record observability data for classify via agent-service
        if (agentClient != null) {
            try {
                var obsRequest = buildMinimalObservabilityRequest(agentConfig, context, nodeId, "classify",
                    result.success() ? "COMPLETED" : "FAILED", result.error());
                obsRequest.setDurationMs(result.durationMs());
                obsRequest.setTotalTokens(result.tokensUsed());
                obsRequest.setPromptTokens(result.promptTokens());
                obsRequest.setCompletionTokens(result.completionTokens());
                obsRequest.setIterationCount(1);
                obsRequest.setTemperature(agentConfig.temperature());
                obsRequest.setMaxTokensConfig(agentConfig.maxTokens());

                // Conversation messages: prepend SYSTEM + USER, then append execution messages
                if (result.systemPrompt() != null) {
                    obsRequest.setSystemPrompt(result.systemPrompt());
                }
                {
                    var messages = new java.util.ArrayList<com.apimarketplace.agent.client.dto.AgentObservabilityRequest.MessageData>();
                    int seq = 0;

                    // Prepend SYSTEM prompt
                    if (result.systemPrompt() != null && !result.systemPrompt().isBlank()) {
                        var sys = new com.apimarketplace.agent.client.dto.AgentObservabilityRequest.MessageData();
                        sys.setSequenceNumber(seq++);
                        sys.setRole("SYSTEM");
                        sys.setContent(result.systemPrompt());
                        messages.add(sys);
                    }

                    // Prepend USER prompt
                    if (result.userPrompt() != null && !result.userPrompt().isBlank()) {
                        var usr = new com.apimarketplace.agent.client.dto.AgentObservabilityRequest.MessageData();
                        usr.setSequenceNumber(seq++);
                        usr.setRole("USER");
                        usr.setContent(result.userPrompt());
                        usr.setIterationNumber(1);
                        messages.add(usr);
                    }

                    // Append execution messages (ASSISTANT response from LLM)
                    if (result.conversationMessages() != null && !result.conversationMessages().isEmpty()) {
                        for (var src : result.conversationMessages()) {
                            var msg = new com.apimarketplace.agent.client.dto.AgentObservabilityRequest.MessageData();
                            msg.setSequenceNumber(seq++);
                            msg.setRole(src.role());
                            msg.setContent(src.content());
                            msg.setToolCallId(src.toolCallId());
                            msg.setToolName(src.toolName());
                            if (!"SYSTEM".equalsIgnoreCase(src.role())) {
                                msg.setIterationNumber(1);
                            }
                            messages.add(msg);
                        }
                    }

                    if (!messages.isEmpty()) {
                        obsRequest.setMessages(messages);
                    }
                }
                agentClient.recordObservability(obsRequest);
            } catch (Exception e) {
                logger.warn("Failed to record classify observability data: {}", e.getMessage());
            }
        }

        long duration = System.currentTimeMillis() - startTime;

        if (result.success()) {
            return createClassifySuccessResult(result, duration, context);
        } else {
            return NodeExecutionResult.failure(nodeId, result.error(), duration);
        }
    }

    /**
     * Execute guardrail agent for content validation.
     */
    @SuppressWarnings("unchecked")
    private NodeExecutionResult executeGuardrail(ExecutionContext context, Map<String, Object> inputData, long startTime) {
        if (agentClient == null) {
            logger.error("❌ AgentClient not injected for guardrail: {}", nodeId);
            return NodeExecutionResult.failure(nodeId,
                "AgentClient not available",
                System.currentTimeMillis() - startTime);
        }

        // Delta 2 - pre-flight budget gate (shared helper, same contract as executeAgent).
        NodeExecutionResult gateDenial = preflightDenialOrNull(context,
            agentConfig.provider(), agentConfig.model(),
            estimateNonAgentPromptTokens(inputData), startTime);
        if (gateDenial != null) return gateDenial;

        // Build GuardrailRequest from agent config
        GuardrailRequest.Builder requestBuilder = GuardrailRequest.builder()
            .provider(agentConfig.provider())
            .model(agentConfig.model())
            .temperature(agentConfig.temperature())
            .maxTokens(agentConfig.maxTokens())
            .tenantId(context.tenantId())
            .agentEntityId(agentConfig.agentConfigId());

        // Get content from params or input
        String content = resolveContent(inputData);
        requestBuilder.content(content);

        // Get prompt
        String prompt = agentConfig.prompt();
        if (prompt != null) {
            prompt = resolvePromptTemplate(prompt, context);
        }
        requestBuilder.prompt(prompt);

        // Get action from params
        Map<String, Object> params = agentConfig.params();
        String action = params != null ? (String) params.get("action") : "flag";
        requestBuilder.action(action != null ? action : "flag");

        // Get rules from agent config
        // Frontend format: [{id, type, action, config: {description, ...}}]
        // Backend/agent format: [{id, description}]
        List<Map<String, Object>> rules = agentConfig.guardrailRules();
        if (rules != null) {
            for (Map<String, Object> rule : rules) {
                String id = (String) rule.get("id");
                // Use type as rule id when id is generic (e.g. "rule-0") - more meaningful for the LLM
                String type = (String) rule.get("type");
                if (type != null && !type.isBlank() && id != null && id.matches("rule-\\d+")) {
                    id = type;
                }
                // Description can be top-level (backend format) or nested under config (frontend format)
                String description = (String) rule.get("description");
                if (description == null) {
                    Object config = rule.get("config");
                    if (config instanceof Map<?, ?> configMap) {
                        description = (String) configMap.get("description");
                    }
                }
                if (id != null) {
                    requestBuilder.addRule(id, description);
                }
            }
        } else if (params != null && params.get("rules") instanceof Map) {
            // Also support rules as Map<ruleId, description>
            Map<String, String> rulesMap = (Map<String, String>) params.get("rules");
            for (Map.Entry<String, String> entry : rulesMap.entrySet()) {
                requestBuilder.addRule(entry.getKey(), entry.getValue());
            }
        }

        GuardrailRequest request = requestBuilder.build();

        logger.info("🛡️ Executing guardrail: provider={}, model={}, rules={}, action={}",
            request.provider(), request.model(), request.rules().size(), request.action());

        // Execute guardrail validation via agent-service
        GuardrailResult result = executeGuardrailRemotely(request);

        // Record observability data for guardrail via agent-service
        if (agentClient != null) {
            try {
                var obsRequest = buildMinimalObservabilityRequest(agentConfig, context, nodeId, "guardrail",
                    result.success() ? "COMPLETED" : "FAILED", result.error());
                obsRequest.setDurationMs(result.durationMs());
                obsRequest.setTotalTokens(result.tokensUsed());
                obsRequest.setPromptTokens(result.promptTokens());
                obsRequest.setCompletionTokens(result.completionTokens());
                obsRequest.setIterationCount(1);
                obsRequest.setTemperature(agentConfig.temperature());
                obsRequest.setMaxTokensConfig(agentConfig.maxTokens());

                // Conversation messages: prepend SYSTEM + USER, then append execution messages
                if (result.systemPrompt() != null) {
                    obsRequest.setSystemPrompt(result.systemPrompt());
                }
                {
                    var messages = new java.util.ArrayList<com.apimarketplace.agent.client.dto.AgentObservabilityRequest.MessageData>();
                    int seq = 0;

                    // Prepend SYSTEM prompt
                    if (result.systemPrompt() != null && !result.systemPrompt().isBlank()) {
                        var sys = new com.apimarketplace.agent.client.dto.AgentObservabilityRequest.MessageData();
                        sys.setSequenceNumber(seq++);
                        sys.setRole("SYSTEM");
                        sys.setContent(result.systemPrompt());
                        messages.add(sys);
                    }

                    // Prepend USER prompt
                    if (result.userPrompt() != null && !result.userPrompt().isBlank()) {
                        var usr = new com.apimarketplace.agent.client.dto.AgentObservabilityRequest.MessageData();
                        usr.setSequenceNumber(seq++);
                        usr.setRole("USER");
                        usr.setContent(result.userPrompt());
                        usr.setIterationNumber(1);
                        messages.add(usr);
                    }

                    // Append execution messages (ASSISTANT response from LLM)
                    if (result.conversationMessages() != null && !result.conversationMessages().isEmpty()) {
                        for (var src : result.conversationMessages()) {
                            var msg = new com.apimarketplace.agent.client.dto.AgentObservabilityRequest.MessageData();
                            msg.setSequenceNumber(seq++);
                            msg.setRole(src.role());
                            msg.setContent(src.content());
                            msg.setToolCallId(src.toolCallId());
                            msg.setToolName(src.toolName());
                            if (!"SYSTEM".equalsIgnoreCase(src.role())) {
                                msg.setIterationNumber(1);
                            }
                            messages.add(msg);
                        }
                    }

                    if (!messages.isEmpty()) {
                        obsRequest.setMessages(messages);
                    }
                }
                agentClient.recordObservability(obsRequest);
            } catch (Exception e) {
                logger.warn("Failed to record guardrail observability data: {}", e.getMessage());
            }
        }

        long duration = System.currentTimeMillis() - startTime;

        if (result.success()) {
            return createGuardrailSuccessResult(result, duration, context);
        } else {
            return NodeExecutionResult.failure(nodeId, result.error(), duration);
        }
    }

    /**
     * Resolve content from input data or params.
     */
    private String resolveContent(Map<String, Object> inputData) {
        // Try 'content' key first, then 'input' alias
        Object content = inputData.get("content");
        if (content == null) {
            content = inputData.get("input");
        }
        if (content != null) {
            return content.toString();
        }

        // Try from agent params (both keys)
        Map<String, Object> params = agentConfig.params();
        if (params != null) {
            content = params.get("content");
            if (content == null) {
                content = params.get("input");
            }
            if (content != null) {
                return content.toString();
            }
        }

        // Fallback to prompt
        return agentConfig.prompt() != null ? agentConfig.prompt() : "";
    }

    /**
     * Create success result for classify execution.
     */
    private NodeExecutionResult createClassifySuccessResult(ClassifyResult result, long duration, ExecutionContext context) {
        Map<String, Object> output = new HashMap<>();
        output.put("selected_category", result.selectedCategory());
        output.put("confidence", result.confidence());
        output.put("reasoning", result.reasoning());
        output.put("response", "Classified as: " + result.selectedCategory());
        output.put("model", result.model());
        output.put("provider", result.provider());
        output.put("tokens_used", result.tokensUsed());
        output.put("durationMs", result.durationMs());

        // Find the selected category index for branch routing
        int selectedCategoryIndex = findCategoryIndex(result.selectedCategory());
        output.put("selected_category_index", selectedCategoryIndex);

        // Agent config snapshot (frozen at execution time for audit)
        output.put("agent_config_snapshot", buildAgentConfigSnapshot(context));

        // Include item context
        output.put("node_type", "CLASSIFY");
        output.put("item_index", context.itemIndex());
        output.put("itemIndex", context.itemIndex());
        output.put("item_id", context.itemId());

        logger.info("✅ Classify complete: category={}, categoryIndex={}, confidence={}",
            result.selectedCategory(), selectedCategoryIndex, result.confidence());

        return NodeExecutionResult.success(nodeId, output, duration);
    }

    /**
     * Find the index of a category by its label.
     * Returns -1 if not found.
     *
     * <p>Matching uses {@link com.apimarketplace.orchestrator.utils.LabelNormalizer#normalizeLabel}
     * on both sides - so the LLM's free-form output ("Réseaux", "promo's", "Work Items",
     * "  finance ") matches a configured category that normalizes to the same slug
     * ("reseaux", "promo_s", "work_items", "finance"). Without this, the agent runtime's
     * creative casing/whitespace/accent variants land in the {@code -1} bucket and the
     * item is silently lost. Mirror of {@code AgentAsyncCompletionService.resolveCategoryIndex}
     * - keep the two implementations behavior-identical.
     */
    private int findCategoryIndex(String categoryLabel) {
        String normalizedSelected =
            com.apimarketplace.orchestrator.utils.LabelNormalizer.normalizeLabel(categoryLabel);
        if (normalizedSelected == null) {
            return -1;
        }

        List<Map<String, Object>> categories = agentConfig.classifyCategories();
        if (categories == null || categories.isEmpty()) {
            return -1;
        }

        for (int i = 0; i < categories.size(); i++) {
            Map<String, Object> category = categories.get(i);
            String label = (String) category.get("label");
            String normalizedConfig =
                com.apimarketplace.orchestrator.utils.LabelNormalizer.normalizeLabel(label);
            if (normalizedConfig != null && normalizedConfig.equals(normalizedSelected)) {
                return i;
            }
        }

        return -1;
    }

    /**
     * Create success result for guardrail execution.
     */
    private NodeExecutionResult createGuardrailSuccessResult(GuardrailResult result, long duration, ExecutionContext context) {
        Map<String, Object> output = new HashMap<>();
        output.put("passed", result.passed());
        output.put("violations", result.violations());
        output.put("details", result.details());
        output.put("sanitized", result.sanitized());
        output.put("response", result.passed() ? "Content passed all checks" : "Content violated " + result.violations().size() + " rule(s)");
        output.put("model", result.model());
        output.put("provider", result.provider());
        output.put("tokens_used", result.tokensUsed());
        output.put("durationMs", result.durationMs());

        // Agent config snapshot (frozen at execution time for audit)
        output.put("agent_config_snapshot", buildAgentConfigSnapshot(context));

        // Include item context
        output.put("node_type", "GUARDRAIL");
        output.put("item_index", context.itemIndex());
        output.put("itemIndex", context.itemIndex());
        output.put("item_id", context.itemId());

        logger.info("✅ Guardrail complete: passed={}, violations={}", result.passed(), result.violations().size());

        return NodeExecutionResult.success(nodeId, output, duration);
    }

    /**
     * Builds AgentRequest from agent config and context.
     * Uses modular system prompt and core tools based on entity's toolsConfig.
     */
    private AgentRequest buildAgentRequest(ExecutionContext context, Map<String, Object> inputData) {
        String tenantId = context.tenantId();
        String runId = context.runId();

        Map<String, Object> credentials = buildBaseAgentCredentials(context, inputData);
        // Per-agent inactivity watchdog window (inline path): carry it on the credentials map so
        // agent-service's AgentLoopService.resolveInactivityWindowMs honors the agent's setting.
        if (runtimeOverrides.inactivityTimeout() != null) {
            credentials.put("__inactivityTimeoutSeconds__", runtimeOverrides.inactivityTimeout());
        }
        Map<String, Object> variables = buildAgentVariables(context, inputData);

        // Resolve prompt template if needed
        String resolvedPrompt = resolvePromptTemplate(agentConfig.prompt(), context);
        String resolvedSystemPrompt = resolvePromptTemplate(agentConfig.systemPrompt(), context);

        // Ensure prompt is never null - use input prompt or default
        if (resolvedPrompt == null || resolvedPrompt.isBlank()) {
            Object inputPrompt = inputData.get("prompt");
            if (inputPrompt != null && !inputPrompt.toString().isBlank()) {
                resolvedPrompt = inputPrompt.toString();
            } else {
                resolvedPrompt = "Please assist with this request.";
                logger.warn("Agent {} has no prompt configured, using default", nodeId);
            }
        }

        // ══════════════════════════════════════════════════════════════
        // Modular system prompt + core tools based on toolsConfig
        // ══════════════════════════════════════════════════════════════

        // Load toolsConfig from entity
        Map<String, Object> toolsConfig = loadToolsConfig(context);

        // Determine which resource modules are enabled
        Set<String> enabledModules = resolveEnabledModules(toolsConfig);

        // Always build modular prompt (tool instructions), then append custom prompt if present
        // This matches conversation behavior: custom prompt EXTENDS, never replaces
        boolean withMemory = Boolean.TRUE.equals(agentConfig.withMemory());
        String customSystemPrompt = resolvedSystemPrompt;

        DefaultSystemPrompts.ModularPromptResult promptResult =
            DefaultSystemPrompts.build(enabledModules, withMemory);
        resolvedSystemPrompt = promptResult.systemPrompt();
        logger.info("Agent {}: built modular prompt with modules={}, conversationMode={}",
            nodeId, enabledModules, withMemory);

        if (customSystemPrompt != null && !customSystemPrompt.isBlank()) {
            // Append custom system prompt after modular prompt (same as conversation AgentContextBuilder)
            resolvedSystemPrompt = resolvedSystemPrompt + "\n\n" + customSystemPrompt;
            logger.info("Agent {}: appended custom system prompt ({} chars)",
                nodeId, customSystemPrompt.length());
        }

        // Pass resource restriction IDs in credentials for runtime enforcement
        applyToolsConfigCredentials(credentials, toolsConfig);

        // PR15 - read org context directly from the ExecutionContext field
        // (populated at tree-build time from WorkflowRunEntity.organization_id).
        // Falls back to a DB lookup only if the context is empty, e.g. for
        // pre-PR15 in-flight runs where the tree was built before the field
        // existed. The legacy credentials.__orgId__ stash is preserved so
        // downstream tool-module deny-list filtering keeps working until
        // PR20 migrates those consumers to read from ExecutionContext.
        applyOrgContext(credentials, context);

        // Auto-discover catalog tools in addition to core tools
        boolean autoDiscover = agentConfig.tools() == null || agentConfig.tools().isEmpty();

        // Load conversation history for agent memory if enabled
        List<Message> conversationHistory = loadAgentMemory(tenantId, withMemory);

        // Execution timeout comes from runtimeOverrides - populated by ExecutionNodeFactory
        // out of the same agent-service fetch that produced agentConfig. No HTTP here.
        Integer executionTimeout = runtimeOverrides.executionTimeout();

        return AgentRequest.builder()
            .prompt(resolvedPrompt)
            .systemPrompt(resolvedSystemPrompt)
            .provider(agentConfig.provider())
            .model(agentConfig.model())
            .temperature(agentConfig.temperature())
            .maxTokens(agentConfig.maxTokens())
            .maxIterations(agentConfig.maxIterations())
            .executionTimeout(executionTimeout)
            .maxTools(agentConfig.maxTools())
            .autoDiscoverTools(autoDiscover)
            .tools(null) // agent-service auto-discovers tools
            // Forward the canonical enabled-module set so agent-service scopes the
            // auto-discovered core tool SCHEMAS exactly like the chat path (parity with
            // AgentContextBuilder). toolsConfig == null ⇒ unrestricted: leave null so
            // agent-service keeps the legacy "all core tools" fallback (matches chat's
            // buildAgentDefault for an agent with no toolsConfig). Without this the remote
            // loop ignored toolsConfig.mode and billed every core schema on every iteration.
            .enabledModules(toolsConfig != null ? List.copyOf(enabledModules) : null)
            .conversationHistory(conversationHistory)
            .tenantId(tenantId)
            .runId(runId)
            .nodeId(nodeId)
            .variables(variables)
            .credentials(credentials)
            .build();
    }

    /**
     * Load toolsConfig from the agent entity via AgentConfigResolver.
     */
    private Map<String, Object> loadToolsConfig(ExecutionContext context) {
        if (agentConfigResolver == null || agentConfig.agentConfigId() == null) {
            return null; // No entity → unrestricted
        }
        return agentConfigResolver.getToolsConfig(
            agentConfig.agentConfigId(),
            context.tenantId(),
            context.organizationId());
    }

    /**
     * Resolve the user prompt with the same fallback chain {@link #buildAgentRequest}
     * uses (line ~1463-1472): the configured template (variable-substituted), then
     * {@code inputData["prompt"]}, then a default placeholder. Extracted so the
     * inline path, the conversation-bootstrap path, and the async observability
     * snapshot all show the SAME user message in the metric view.
     */
    private String resolveEffectiveUserPrompt(ExecutionContext context, Map<String, Object> inputData) {
        String resolved = resolvePromptTemplate(agentConfig.prompt(), context);
        if (resolved != null && !resolved.isBlank()) return resolved;
        Object inputPrompt = inputData != null ? inputData.get("prompt") : null;
        if (inputPrompt != null && !inputPrompt.toString().isBlank()) {
            return inputPrompt.toString();
        }
        return "Please assist with this request.";
    }

    /**
     * Mirror the system-prompt construction inside {@link #buildAgentRequest} so the
     * async path can snapshot the FINAL system prompt (modular prefix + custom
     * template, with variables substituted) onto {@link PendingAgent} at enqueue time.
     *
     * <p>The inline {@code AgentNode.executeAgent} path passes the same composite
     * value into {@link #buildObservabilityRequest} (lines ~2089, 2110-2117), which
     * persists it to {@code agent_executions.system_prompt} and prepends it as a
     * SYSTEM message in {@code agent_execution_messages}. Without this helper, the
     * async path used to fall back to {@code agentConfig.systemPrompt()} (the raw
     * unresolved template, missing the modular prefix), so the Agent Performance
     * metric view rendered a stale value.</p>
     */
    private String resolveEffectiveSystemPromptForObservability(ExecutionContext context) {
        Map<String, Object> toolsConfig = loadToolsConfig(context);
        Set<String> enabledModules = resolveEnabledModules(toolsConfig);
        boolean withMemory = Boolean.TRUE.equals(agentConfig.withMemory());
        DefaultSystemPrompts.ModularPromptResult promptResult =
            DefaultSystemPrompts.build(enabledModules, withMemory);
        String effective = promptResult.systemPrompt();
        String customResolved = resolvePromptTemplate(agentConfig.systemPrompt(), context);
        if (customResolved != null && !customResolved.isBlank()) {
            effective = effective + "\n\n" + customResolved;
        }
        return effective;
    }

    /**
     * Determine which prompt modules are enabled based on toolsConfig.
     * Delegates to shared AgentModuleResolver for consistent behavior across
     * workflow execution and conversation chat.
     */
    private Set<String> resolveEnabledModules(Map<String, Object> toolsConfig) {
        return AgentModuleResolver.resolveEnabledModules(toolsConfig);
    }

    /**
     * Builds a flat snapshot of the agent configuration at execution time.
     * Captures identity, LLM config, tools config, and enabled modules
     * so that past runs can be audited even after config changes.
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> buildAgentConfigSnapshot(ExecutionContext context) {
        String tenantId = context.tenantId();
        Map<String, Object> snapshot = new LinkedHashMap<>();

        // Identity
        snapshot.put("agentConfigId", agentConfig.agentConfigId());
        snapshot.put("agentLabel", agentConfig.label());
        snapshot.put("agentType", agentConfig.type() != null ? agentConfig.type() : "agent");

        // LLM config
        snapshot.put("provider", agentConfig.provider());
        snapshot.put("model", agentConfig.model());
        snapshot.put("temperature", agentConfig.temperature());
        snapshot.put("maxTokens", agentConfig.maxTokens());
        snapshot.put("maxIterations", agentConfig.maxIterations());
        snapshot.put("withMemory", agentConfig.withMemory());

        // System prompt hash (not the full prompt - too large)
        if (agentConfig.systemPrompt() != null) {
            snapshot.put("systemPromptHash", hashString(agentConfig.systemPrompt()));
        }

        // Tools config from entity
        Map<String, Object> toolsConfig = loadToolsConfig(context);
        if (toolsConfig != null) {
            snapshot.put("toolsMode", toolsConfig.get("mode"));
            snapshot.put("tools", toolsConfig.get("tools"));
            snapshot.put("allowedTables", toolsConfig.get("tables"));
            snapshot.put("allowedInterfaces", toolsConfig.get("interfaces"));
            snapshot.put("allowedAgents", toolsConfig.get("agents"));
            snapshot.put("allowedWorkflows", toolsConfig.get("workflows"));
            snapshot.put("allowedApplications", toolsConfig.get("applications"));
        }

        // Enabled modules (computed)
        Set<String> enabledModules = resolveEnabledModules(toolsConfig);
        snapshot.put("enabledModules", new ArrayList<>(enabledModules));

        // Entity summary (avatar, name, description)
        if (agentConfigResolver != null && agentConfig.agentConfigId() != null) {
            Map<String, Object> entitySummary = agentConfigResolver.getAgentEntitySummary(
                agentConfig.agentConfigId(), tenantId, context.organizationId());
            if (entitySummary != null) {
                snapshot.put("avatarUrl", entitySummary.get("avatarUrl"));
                snapshot.put("agentName", entitySummary.get("name"));
                snapshot.put("agentDescription", entitySummary.get("description"));
            }
        }

        return snapshot;
    }

    /**
     * Computes a truncated SHA-256 hash of the input string for change detection.
     * Returns the first 16 hex characters (64 bits) for readability.
     */
    private static String hashString(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash).substring(0, 16);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is always available in Java
            return null;
        }
    }

    /**
     * Pass allowed resource IDs in credentials for runtime enforcement by CRUD modules.
     */
    @SuppressWarnings("unchecked")
    private void applyToolsConfigCredentials(Map<String, Object> credentials, Map<String, Object> toolsConfig) {
        // A null toolsConfig (legacy agent row, pre-V163 migration) MUST behave the
        // same as a fully-empty config: no internal-resource access. Falling through
        // with `return` would leave allowed*Ids unset → tool modules' null checks
        // would treat as "no restriction" and grant full tenant access. Use an empty
        // map so each passAllowedIds path reaches the absent-key branch and writes [].
        Map<String, Object> tc = toolsConfig != null ? toolsConfig : Map.of();

        // For each resource type: if the field exists in toolsConfig, pass it as allowedXxxIds
        passAllowedIds(credentials, tc, "tables", "allowedTableIds");
        passAllowedIds(credentials, tc, "interfaces", "allowedInterfaceIds");
        passAllowedIds(credentials, tc, "agents", "allowedAgentIds");
        passAllowedIds(credentials, tc, "workflows", "allowedWorkflowIds");
        passAllowedIds(credentials, tc, "applications", "allowedApplicationIds");
        // Files are opt-in: an absent/empty list means full org-scoped file access
        // (FilesToolsProvider treats an empty allow-list as unrestricted), so existing
        // agents keep their access; a non-empty list scopes the agent to those files only.
        passAllowedIds(credentials, tc, "files", "allowedFileIds");
        passAccessMode(credentials, tc, "tableAccessMode");
        passAccessMode(credentials, tc, "workflowAccessMode");
        passAccessMode(credentials, tc, "interfaceAccessMode");
        passAccessMode(credentials, tc, "agentAccessMode");
        passAccessMode(credentials, tc, "applicationAccessMode");
        passAccessMode(credentials, tc, "skillAccessMode");
        passAccessMode(credentials, tc, "fileAccessMode");
    }

    private void passAllowedIds(Map<String, Object> credentials, Map<String, Object> toolsConfig,
                                 String configKey, String credentialKey) {
        // GRANT sentinel - authoritative, DENY-BY-DEFAULT (mirrors AgentConfigProvider.isXNone
        // and the conversation path AgentContextBuilder.applyToolsConfigCredentials). Convention:
        // ABSENT __allowed<Family>Ids__ = unrestricted, [] = deny-all, [ids] = those ids. When a
        // <family>Grant is present it DRIVES the result: 'all' → OMIT (unrestricted) so a
        // grant:'all'+empty-list agent is NOT blocked as a workflow node; 'custom' → emit the id
        // list; 'none' OR any UNRECOGNISED value → emit [] (deny). A none/unknown grant must never
        // trust a stale id list behind it - that is the fail-OPEN this guards against.
        Object grantObj = toolsConfig.get(configKey + "Grant");
        if (grantObj instanceof String grant) {
            if ("all".equals(grant)) {
                return;
            }
            if ("custom".equals(grant)) {
                // Stringify every id: allowlists created via MCP keep their native JSON type, so a
                // numeric-ID resource (tables:[209]) arrives as a List<Integer>; tool modules compare
                // with `.contains(String.valueOf(id))`, so a raw List<Integer> never matches → silent
                // "not in your approved list". UUID-based resources are already strings (no-op).
                Object value = toolsConfig.get(configKey);
                credentials.put(credentialKey, value instanceof List<?> list
                        ? list.stream().map(String::valueOf).toList() : List.of());
            } else {
                credentials.put(credentialKey, List.of());
            }
            return;
        }
        // No grant axis on this key (e.g. files / a legacy config-less node) - emit the list
        // as-is (stringified like the custom branch); an absent key becomes [] so a missing list
        // never bypasses the allowlist.
        Object value = toolsConfig.get(configKey);
        credentials.put(credentialKey, value instanceof List<?> list
                ? list.stream().map(String::valueOf).toList() : List.of());
    }

    private void passAccessMode(Map<String, Object> credentials, Map<String, Object> toolsConfig,
                                String accessModeKey) {
        Object value = toolsConfig.get(accessModeKey);
        if (value != null) {
            credentials.put(accessModeKey, value);
        }
    }

    /**
     * Builds the workflow-agent credential envelope shared by the inline and
     * async queue paths.
     */
    private Map<String, Object> buildBaseAgentCredentials(
            com.apimarketplace.orchestrator.execution.v2.engine.ExecutionContext context,
            Map<String, Object> inputData) {
        Map<String, Object> credentials = new HashMap<>();
        Map<String, Object> agentParams = agentConfig.params();
        if (agentParams != null) {
            copyStringKeyedMapInto(credentials, agentParams.get("credentials"));
        }
        if (inputData != null) {
            copyStringKeyedMapInto(credentials, inputData.get("credentials"));
        }
        applyWorkflowAgentIdentityCredentials(credentials, context);
        return credentials;
    }

    private Map<String, Object> copyStringKeyedMap(Object rawMap) {
        Map<String, Object> copy = new HashMap<>();
        copyStringKeyedMapInto(copy, rawMap);
        return copy;
    }

    private void copyStringKeyedMapInto(Map<String, Object> target, Object rawMap) {
        if (target == null || !(rawMap instanceof Map<?, ?> source)) {
            return;
        }
        source.forEach((key, value) -> {
            if (key instanceof String stringKey) {
                target.put(stringKey, value);
            }
        });
    }

    private void applyWorkflowAgentIdentityCredentials(
            Map<String, Object> credentials,
            com.apimarketplace.orchestrator.execution.v2.engine.ExecutionContext context) {
        if (agentConfig.agentConfigId() != null) {
            credentials.put("__agentId__", agentConfig.agentConfigId());
        }
        if (context != null && context.runId() != null) {
            credentials.put("__workflowRunId__", context.runId());
        }
    }

    private Map<String, Object> buildAgentVariables(
            com.apimarketplace.orchestrator.execution.v2.engine.ExecutionContext context,
            Map<String, Object> inputData) {
        Map<String, Object> variables = new HashMap<>(inputData != null ? inputData : Map.of());
        Map<String, Object> stepOutputs = context != null ? context.getAllStepOutputs() : null;
        if (stepOutputs != null) {
            variables.putAll(stepOutputs);
        }
        if (agentConfig.tools() != null && !agentConfig.tools().isEmpty()) {
            variables.put("__requestedTools", agentConfig.tools());
        }
        return variables;
    }

    /**
     * Pass org context (orgId, orgRole) in credentials for deny-list filtering
     * in tool modules.
     *
     * <p>PR15 - prefers the {@link com.apimarketplace.orchestrator.execution.v2.engine.ExecutionContext}
     * fields ({@code organizationId} / {@code organizationRole}) populated at
     * tree-build time from {@code WorkflowRunEntity.organization_id /
     * .organization_role}. Falls back to the legacy DB-read path when the
     * context fields are empty (transitional safety for in-flight pre-PR15
     * runs whose tree was built before this propagation existed).</p>
     *
     * <p>The {@code credentials.__orgId__} / {@code credentials.__orgRole__}
     * keys are preserved so downstream tool-module consumers (which still
     * read from credentials) keep working. PR20 will migrate those consumers
     * to read from {@code ExecutionContext} directly, after which this
     * stash can be removed.</p>
     */
    private void applyOrgContext(Map<String, Object> credentials,
                                  com.apimarketplace.orchestrator.execution.v2.engine.ExecutionContext context) {
        if (context == null) return;
        String orgId = context.organizationId();
        String orgRole = context.organizationRole();
        if (orgId == null || orgId.isBlank()) {
            // Fallback: read from the WorkflowRunEntity for in-flight pre-PR15
            // runs (tree built before the propagation existed).
            if (workflowRunRepository != null && context.workflowRunId() != null) {
                try {
                    java.util.UUID runUuid = java.util.UUID.fromString(context.workflowRunId());
                    var runOpt = workflowRunRepository.findById(runUuid);
                    if (runOpt.isPresent()) {
                        orgId = runOpt.get().getOrgId();
                        orgRole = runOpt.get().getOrgRole();
                    }
                } catch (IllegalArgumentException e) {
                    // workflowRunId is not a valid UUID - skip
                }
            }
        }
        if (orgId != null && !orgId.isBlank()) {
            credentials.put("__orgId__", orgId);
            if (orgRole != null) credentials.put("__orgRole__", orgRole);
        }
    }

    /**
     * Resolve the authoritative OWNER org of this run: the {@link
     * com.apimarketplace.orchestrator.execution.v2.engine.ExecutionContext}
     * field (populated at tree-build time from
     * {@code WorkflowRunEntity.organization_id}), falling back to a
     * {@code WorkflowRunEntity} lookup for pre-PR15 in-flight runs whose tree was
     * built before that propagation existed.
     *
     * <p>This is the org that MUST be passed explicitly when creating the agent
     * conversation row: workflow agents run on async / non-servlet threads, so
     * leaving the org to be inferred from the ambient thread context would let a
     * stale org (another tenant's) get stamped onto the conversation - the
     * cross-tenant bleed this guards against. Mirrors {@link #applyOrgContext}'s
     * resolution so the conversation org and the credentials {@code __orgId__}
     * always agree. Returns null only when neither source carries the org.
     */
    private String resolveOrgId(com.apimarketplace.orchestrator.execution.v2.engine.ExecutionContext context) {
        if (context == null) return null;
        String orgId = context.organizationId();
        if (orgId != null && !orgId.isBlank()) return orgId;
        if (workflowRunRepository != null && context.workflowRunId() != null) {
            try {
                java.util.UUID runUuid = java.util.UUID.fromString(context.workflowRunId());
                var runOpt = workflowRunRepository.findById(runUuid);
                if (runOpt.isPresent()) {
                    return runOpt.get().getOrgId();
                }
            } catch (IllegalArgumentException e) {
                // workflowRunId is not a valid UUID - skip
            }
        }
        return orgId;
    }

    /**
     * Load agent memory (conversation history) if withMemory is enabled.
     *
     * In workflow context, we do NOT load the full accumulated conversation history.
     * Each workflow execution is self-contained - the agent receives only the current
     * execution's messages (prompt + tool calls + responses), matching the behavior
     * of conversation-service where only the current request's messages are sent.
     *
     * The conversationManager still SAVES messages (for inspector/audit visibility),
     * but loading historical context is disabled to avoid polluting the LLM context
     * with messages from previous workflow runs or epochs.
     */
    private List<Message> loadAgentMemory(String tenantId, boolean withMemory) {
        // Workflow agents are stateless per execution - no history loading.
        // Memory saving is still handled by conversationManager.saveAssistantResponse().
        return null;
    }

    /**
     * Resolves template expressions in prompt using context data.
     */
    private String resolvePromptTemplate(String prompt, ExecutionContext context) {
        if (prompt == null || prompt.isBlank()) {
            return prompt;
        }

        // If template adapter is available, use it for SpEL resolution
        if (templateAdapter != null) {
            try {
                Map<String, Object> resolved = templateAdapter.resolveTemplates(
                    Map.of("prompt", prompt), context);
                Object resolvedPrompt = resolved.get("prompt");
                if (resolvedPrompt instanceof String) {
                    return (String) resolvedPrompt;
                }
            } catch (Exception e) {
                logger.warn("Failed to resolve prompt template for {}: {}", nodeId, e.getMessage());
            }
        }

        return prompt;
    }

    /**
     * Creates success result from agent execution.
     */
    private NodeExecutionResult createSuccessResult(AgentExecutionResult agentResult, long duration, ExecutionContext context) {
        Map<String, Object> output = new HashMap<>();
        output.put("response", agentResult.getContent());
        output.put("content", agentResult.getContent()); // Alias for compatibility
        output.put("model", agentResult.getModel());
        output.put("provider", agentResult.getProvider());
        output.put("iterations", agentResult.getIterations());
        output.put("iterations_used", agentResult.getIterations()); // Alias for schema mapper
        output.put("durationMs", agentResult.getDurationMs());

        if (agentResult.getTotalUsage() != null) {
            output.put("tokens_used", agentResult.getTotalUsage().getTotal());
            output.put("promptTokens", agentResult.getTotalUsage().promptTokens());
            output.put("completionTokens", agentResult.getTotalUsage().completionTokens());
        }

        if (agentResult.getToolResults() != null && !agentResult.getToolResults().isEmpty()) {
            // Tool calls detail (per-tool breakdown for UI display), stored as tool_calls via alias
            List<Map<String, Object>> toolCallsDetail = agentResult.getToolResults().stream()
                .map(tr -> {
                    Map<String, Object> detail = new LinkedHashMap<>();
                    if (tr.toolCall() != null) {
                        detail.put("toolName", tr.toolCall().toolName());
                        detail.put("toolCallId", tr.toolCall().id());
                    }
                    detail.put("success", tr.success());
                    detail.put("durationMs", tr.durationMs());
                    return detail;
                })
                .toList();
            output.put("tool_calls", toolCallsDetail.size());
            output.put("tool_calls_detail", toolCallsDetail);
        }

        // Agent config snapshot (frozen at execution time for audit)
        output.put("agent_config_snapshot", buildAgentConfigSnapshot(context));

        // Include item context for proper persistence (like DecisionNode does)
        // Use correct node_type based on agent type (agent, classify, guardrail)
        output.put("node_type", getNodeTypeForAgent());
        output.put("item_index", context.itemIndex());
        output.put("itemIndex", context.itemIndex());
        output.put("item_id", context.itemId());

        return NodeExecutionResult.success(nodeId, output, duration);
    }

    /**
     * Prepares input data from agent config and context.
     * Uses the template adapter to resolve SpEL expressions.
     */
    private Map<String, Object> prepareInput(ExecutionContext context) {
        Map<String, Object> rawInput = new HashMap<>();

        // Get params from agent config
        if (agentConfig.params() != null) {
            rawInput.putAll(agentConfig.params());
        }

        // Inject classify/guardrail-specific input as "content" so resolveContent() finds it.
        // These fields are stored separately from params in the Agent record.
        if (agentConfig.classifyParams() != null && !agentConfig.classifyParams().isEmpty()) {
            rawInput.putIfAbsent("content", agentConfig.classifyParams());
        }
        if (agentConfig.guardrailParams() != null && !agentConfig.guardrailParams().isEmpty()) {
            rawInput.putIfAbsent("content", agentConfig.guardrailParams());
        }

        // If template adapter is available, resolve templates
        if (templateAdapter != null && !rawInput.isEmpty()) {
            try {
                Map<String, Object> resolved = templateAdapter.resolveTemplates(rawInput, context);
                logger.info("🤖 Agent template resolution: nodeId={}, rawKeys={}, resolvedKeys={}, contentBefore='{}', contentAfter='{}'",
                    nodeId, rawInput.keySet(), resolved.keySet(),
                    truncate(String.valueOf(rawInput.get("content")), 100),
                    truncate(String.valueOf(resolved.get("content")), 100));
                return resolved;
            } catch (Exception e) {
                logger.error("Template resolution failed for agent {}: {}", nodeId, e.getMessage());
                // Fall back to raw input + context
            }
        }

        // Fallback: add trigger data (lightweight). Step outputs are NOT included
        // to prevent persisting the entire workflow context (100+ KB) into each
        // step's input_data row - the data is available on-demand via storage.
        rawInput.put("trigger", context.triggerData());

        return rawInput;
    }

    /**
     * Builds a clean resolved input map for the inspector panel.
     * Instead of dumping the entire context (trigger + all steps), this extracts
     * only the specific resolved parameters relevant to each agent type.
     */
    private Map<String, Object> buildResolvedInputForInspector(ExecutionContext context, Map<String, Object> inputData) {
        Map<String, Object> resolved = new LinkedHashMap<>();

        // Common params for all agent types
        if (agentConfig.prompt() != null) {
            resolved.put("prompt", resolvePromptTemplate(agentConfig.prompt(), context));
        }
        resolved.put("model", agentConfig.model());
        resolved.put("provider", agentConfig.provider());
        if (agentConfig.temperature() != null) {
            resolved.put("temperature", agentConfig.temperature());
        }
        if (agentConfig.maxTokens() != null) {
            resolved.put("maxTokens", agentConfig.maxTokens());
        }
        if (agentConfig.maxIterations() != null) {
            resolved.put("maxIterations", agentConfig.maxIterations());
        }

        String type = agentConfig.type() != null ? agentConfig.type().toLowerCase() : "agent";
        switch (type) {
            case "classify" -> {
                resolved.put("content", resolveContent(inputData));
                if (agentConfig.classifyCategories() != null && !agentConfig.classifyCategories().isEmpty()) {
                    resolved.put("categories", agentConfig.classifyCategories());
                }
            }
            case "guardrail" -> {
                resolved.put("content", resolveContent(inputData));
                Map<String, Object> params = agentConfig.params();
                resolved.put("action", params != null ? params.getOrDefault("action", "flag") : "flag");
                if (agentConfig.guardrailRules() != null && !agentConfig.guardrailRules().isEmpty()) {
                    resolved.put("rules", agentConfig.guardrailRules());
                }
            }
            default -> {
                // Agent type: include systemPrompt and user-configured content if present
                if (agentConfig.systemPrompt() != null) {
                    resolved.put("systemPrompt", resolvePromptTemplate(agentConfig.systemPrompt(), context));
                }
                // Include resolved params from inputData (user-configured template params)
                // but exclude raw context dumps (trigger/steps)
                for (Map.Entry<String, Object> entry : inputData.entrySet()) {
                    String key = entry.getKey();
                    if (!"trigger".equals(key) && !"steps".equals(key) && !resolved.containsKey(key)) {
                        resolved.put(key, entry.getValue());
                    }
                }
            }
        }

        return resolved;
    }

    @Override
    public void onComplete(ExecutionContext context, NodeExecutionResult result) {
        logger.debug("Agent node completed: nodeId={}, status={}",
            nodeId, result.status());
    }

    public Agent getAgentConfig() {
        return agentConfig;
    }

    /**
     * Returns the correct node_type based on agent type.
     * - "agent" -> "AGENT"
     * - "classify" -> "CLASSIFY"
     * - "guardrail" -> "GUARDRAIL"
     */
    private String getNodeTypeForAgent() {
        String agentType = agentConfig.type();
        if (agentType == null) {
            return "AGENT";
        }
        return switch (agentType.toLowerCase()) {
            case "classify" -> "CLASSIFY";
            case "guardrail" -> "GUARDRAIL";
            default -> "AGENT";
        };
    }

    /**
     * Truncate a string for logging purposes.
     */
    private String truncate(String s, int maxLength) {
        if (s == null) return "null";
        return s.length() > maxLength ? s.substring(0, maxLength) + "..." : s;
    }

    /**
     * Delta 2 - shared pre-flight budget gate invoked by all four agent-dispatch paths
     * ({@code executeAgent}, {@code executeClassify}, {@code executeGuardrail},
     * {@code executeAgentAsyncQueue}). Asks auth-service whether the tenant's balance covers
     * the projected cost for this turn before we pay for the LLM call. Without this gate,
     * the workflow burns tokens, post-flight debit 402s, and the audit row is the only
     * trace - that was the prod bug class this addresses.
     *
     * <p>Fail-closed on unknown model / auth-service unreachable (see
     * {@link com.apimarketplace.common.credit.CreditConsumptionClient#checkChatBudget}).
     * Skipped when {@code creditBudgetService} is unavailable (CE without billing) or when
     * either {@code provider}/{@code model} is null - the latter lets malformed configs
     * surface as normal execution errors downstream instead of being masked as
     * "insufficient credits".
     *
     * @return a failure {@link NodeExecutionResult} when the gate denies, or {@code null}
     *         when dispatch should proceed.
     */
    private NodeExecutionResult preflightDenialOrNull(ExecutionContext context,
                                                        String provider, String model,
                                                        int estPromptTokens, long startTime) {
        if (creditBudgetService == null || provider == null || model == null) {
            return null;
        }
        int estCompletionTokens = agentConfig.maxTokens() != null ? agentConfig.maxTokens() : 4096;
        boolean allowed = creditBudgetService.preflightAgentBudget(
            context.tenantId(), provider, model, estPromptTokens, estCompletionTokens);
        if (allowed) {
            return null;
        }
        logger.warn("💸 Agent dispatch blocked by pre-flight budget: tenant={}, nodeId={}, provider={}, model={}, estPrompt={}, estCompletion={}",
            context.tenantId(), nodeId, provider, model, estPromptTokens, estCompletionTokens);
        return NodeExecutionResult.failure(nodeId,
            "Insufficient credits (pre-flight tenant budget)",
            System.currentTimeMillis() - startTime);
    }

    /**
     * Prompt-token estimate for classify/guardrail pre-flight. These paths don't build an
     * {@link AgentRequest} upfront - they compose prompts inline - so we approximate from
     * the agent-config system prompt plus the character length of the serialized input
     * payload. Same ~4 chars/token heuristic as {@link #estimatePromptTokens(AgentRequest)}.
     */
    private int estimateNonAgentPromptTokens(Map<String, Object> inputData) {
        long chars = 0;
        if (agentConfig.systemPrompt() != null) chars += agentConfig.systemPrompt().length();
        if (inputData != null) {
            for (Object v : inputData.values()) {
                if (v != null) chars += v.toString().length();
            }
        }
        int estimated = (int) Math.min(Integer.MAX_VALUE, (chars + 3) / 4);
        return Math.max(estimated, 1);
    }

    /**
     * Conservative character-based prompt-token estimate for pre-flight budget checks.
     * Uses the widely-used "1 token ≈ 4 characters" heuristic and sums system prompt,
     * user prompt, and conversation history. Deliberately over-estimates (no tool schema
     * reduction, no tokenizer-specific compression) so the gate fails safe: if we pass the
     * gate, there's real slack for the actual request.
     *
     * <p>Not the same tokenizer the LLM uses, so not for billing - only for the
     * go/no-go decision. The post-flight debit still consumes from the REAL token count
     * reported by the provider.
     */
    private int estimatePromptTokens(AgentRequest request) {
        long chars = 0;
        if (request.systemPrompt() != null) chars += request.systemPrompt().length();
        if (request.prompt() != null) chars += request.prompt().length();
        if (request.conversationHistory() != null) {
            for (var msg : request.conversationHistory()) {
                if (msg.content() != null) chars += msg.content().length();
            }
        }
        int estimated = (int) Math.min(Integer.MAX_VALUE, (chars + 3) / 4);
        return Math.max(estimated, 1);
    }

    // ========== Observability request builders ==========

    /**
     * Build an AgentObservabilityRequest from a full agent execution result.
     */
    private com.apimarketplace.agent.client.dto.AgentObservabilityRequest buildObservabilityRequest(
            AgentExecutionResult agentResult, Agent agentConfig, ExecutionContext context,
            String nodeId, String agentType,
            String systemPrompt, String userPrompt, boolean memoryEnabled, String conversationId) {
        var req = new com.apimarketplace.agent.client.dto.AgentObservabilityRequest();
        req.setTenantId(context.tenantId());
        // PR20 - propagate workspace identity onto the observability row so the
        // execution-history panel can scope its listing by strict isolation.
        req.setOrganizationId(context.organizationId());
        req.setAgentType(agentType);
        req.setNodeId(nodeId);

        // Agent entity reference
        if (agentConfig.agentConfigId() != null) {
            try {
                req.setAgentEntityId(java.util.UUID.fromString(agentConfig.agentConfigId()));
            } catch (IllegalArgumentException ignored) {}
        }

        // Workflow context
        if (context.plan() != null && context.plan().getId() != null) {
            try {
                req.setWorkflowId(java.util.UUID.fromString(context.plan().getId()));
            } catch (IllegalArgumentException ignored) {}
        }
        if (context.workflowRunId() != null) {
            try {
                req.setWorkflowRunId(java.util.UUID.fromString(context.workflowRunId()));
            } catch (IllegalArgumentException ignored) {}
        }
        req.setRunId(context.runId());
        req.setEpoch(context.epoch());
        req.setSpawn(context.spawn());
        req.setItemIndex(context.itemIndex());

        // Execution result
        req.setStatus(agentResult.isSuccess() ? "COMPLETED" : "FAILED");
        if (agentResult.getStopReason() != null) {
            req.setStopReason(agentResult.getStopReason().name());
        }
        // Surface budgetScope (tenant|agent) when AgentLoopService recorded a budget denial.
        if (agentResult.getMetrics() != null) {
            Object scope = agentResult.getMetrics().get("budgetScope");
            if (scope instanceof String s && !s.isBlank()) {
                req.setBudgetScope(s);
            }
        }
        req.setErrorMessage(agentResult.getError());

        // LLM config - runtime values from the result are authoritative (real provider/model
        // selected by the routing layer), but fall back to the agent's configured provider/model
        // when the result didn't propagate them. Without this fallback the row lands with
        // provider=NULL,model=NULL → CreditConsumptionClient writes "unknown"/"unknown" into
        // the ledger and ModelPricingService applies the DEFAULT_INPUT_RATE/OUTPUT_RATE fallback,
        // producing a spurious cost (the "unknown/unknown 793/150 → -1.39" prod incident).
        String resolvedProvider = agentResult.getProvider() != null && !agentResult.getProvider().isBlank()
                ? agentResult.getProvider()
                : agentConfig.provider();
        String resolvedModel = agentResult.getModel() != null && !agentResult.getModel().isBlank()
                ? agentResult.getModel()
                : agentConfig.model();
        req.setProvider(resolvedProvider);
        req.setModel(resolvedModel);
        req.setTemperature(agentConfig.temperature());
        req.setMaxTokensConfig(agentConfig.maxTokens());
        req.setMaxIterationsConfig(agentConfig.maxIterations());

        // Counters
        req.setIterationCount(agentResult.getIterations());
        req.setTotalToolCalls(agentResult.getToolResults() != null ? agentResult.getToolResults().size() : 0);
        req.setDurationMs(agentResult.getDurationMs());

        // Token usage
        if (agentResult.getTotalUsage() != null) {
            var usage = agentResult.getTotalUsage();
            req.setTotalTokens(usage.getTotal());
            req.setPromptTokens(usage.promptTokens() != null ? usage.promptTokens() : 0);
            req.setCompletionTokens(usage.completionTokens() != null ? usage.completionTokens() : 0);
            if (usage.cacheCreationInputTokens() != null) req.setCacheCreationTokens(usage.cacheCreationInputTokens());
            if (usage.cacheReadInputTokens() != null) req.setCacheReadTokens(usage.cacheReadInputTokens());
            if (usage.cachedTokens() != null) req.setCachedTokens(usage.cachedTokens());
            if (usage.reasoningTokens() != null) req.setReasoningTokens(usage.reasoningTokens());
        }

        // Loop detection
        if (agentResult.getMetrics() != null) {
            Object loopDetected = agentResult.getMetrics().get("loopDetected");
            req.setLoopDetected(Boolean.TRUE.equals(loopDetected));
            Object loopType = agentResult.getMetrics().get("loopType");
            if (loopType != null) req.setLoopType(loopType.toString());
            Object loopToolName = agentResult.getMetrics().get("loopToolName");
            if (loopToolName != null) req.setLoopToolName(loopToolName.toString());
        }

        // System prompt, memory, and conversation context
        req.setSystemPrompt(systemPrompt);
        req.setMemoryEnabled(memoryEnabled);
        req.setConversationId(conversationId);

        // Unique tool count
        if (agentResult.getToolResults() != null && !agentResult.getToolResults().isEmpty()) {
            java.util.Set<String> uniqueTools = new java.util.HashSet<>();
            for (var tr : agentResult.getToolResults()) {
                if (tr.toolCall() != null && tr.toolCall().toolName() != null) {
                    uniqueTools.add(tr.toolCall().toolName());
                }
            }
            req.setUniqueToolCount(uniqueTools.size());
        }

        // Conversation history → messages (prepend SYSTEM + USER, then append execution messages)
        {
            var messages = new java.util.ArrayList<com.apimarketplace.agent.client.dto.AgentObservabilityRequest.MessageData>();
            int seq = 0;
            int iterationCounter = 0;

            // Prepend SYSTEM prompt so metrics show the full conversation
            if (systemPrompt != null && !systemPrompt.isBlank()) {
                var sys = new com.apimarketplace.agent.client.dto.AgentObservabilityRequest.MessageData();
                sys.setSequenceNumber(seq++);
                sys.setRole("SYSTEM");
                sys.setContent(systemPrompt);
                messages.add(sys);
            }

            // Prepend USER prompt (the resolved prompt sent to the LLM)
            if (userPrompt != null && !userPrompt.isBlank()) {
                var usr = new com.apimarketplace.agent.client.dto.AgentObservabilityRequest.MessageData();
                usr.setSequenceNumber(seq++);
                usr.setRole("USER");
                usr.setContent(userPrompt);
                usr.setIterationNumber(iterationCounter);
                messages.add(usr);
            }

            // Append execution messages (ASSISTANT, TOOL, etc.)
            if (agentResult.getConversationHistory() != null && !agentResult.getConversationHistory().isEmpty()) {
                for (var msg : agentResult.getConversationHistory()) {
                    if (msg.role() != null && msg.role() == com.apimarketplace.agent.domain.Message.Role.ASSISTANT) {
                        iterationCounter++;
                    }
                    var md = new com.apimarketplace.agent.client.dto.AgentObservabilityRequest.MessageData();
                    md.setSequenceNumber(seq++);
                    md.setRole(msg.role() != null ? msg.role().name() : "UNKNOWN");
                    md.setContent(msg.content());
                    md.setToolCallId(msg.toolCallId());
                    md.setToolName(msg.toolName());
                    if (msg.role() != null && msg.role() != com.apimarketplace.agent.domain.Message.Role.SYSTEM) {
                        md.setIterationNumber(iterationCounter);
                    }
                    messages.add(md);
                }
            }

            if (!messages.isEmpty()) {
                req.setMessages(messages);
            }
        }

        // Tool results → tool calls (with parallelIndex)
        if (agentResult.getToolResults() != null && !agentResult.getToolResults().isEmpty()) {
            var toolCalls = new java.util.ArrayList<com.apimarketplace.agent.client.dto.AgentObservabilityRequest.ToolCallData>();
            int seq = 0;
            for (var tr : agentResult.getToolResults()) {
                var tc = new com.apimarketplace.agent.client.dto.AgentObservabilityRequest.ToolCallData();
                tc.setSequenceNumber(seq++);
                if (tr.toolCall() != null) {
                    tc.setToolCallId(tr.toolCall().id());
                    tc.setToolName(tr.toolCall().toolName());
                    tc.setArguments(tr.toolCall().arguments());
                    if (tr.toolCall().index() != null) {
                        tc.setParallelIndex(tr.toolCall().index());
                    }
                }
                tc.setSuccess(tr.success());
                tc.setResult(tr.content());
                tc.setDurationMs(tr.durationMs() != null ? tr.durationMs() : 0L);
                toolCalls.add(tc);
            }
            req.setToolCalls(toolCalls);
        }

        // Per-iteration data (with toolCallCount and finishReason)
        if (agentResult.getUsagePerIteration() != null && !agentResult.getUsagePerIteration().isEmpty()) {
            var iterations = new java.util.ArrayList<com.apimarketplace.agent.client.dto.AgentObservabilityRequest.IterationData>();
            // Compute per-iteration tool call counts from metrics
            java.util.List<?> toolCallsPerIter = null;
            if (agentResult.getMetrics() != null) {
                Object tcpi = agentResult.getMetrics().get("toolCallsPerIteration");
                if (tcpi instanceof java.util.List<?>) toolCallsPerIter = (java.util.List<?>) tcpi;
            }
            for (int i = 0; i < agentResult.getUsagePerIteration().size(); i++) {
                var usage = agentResult.getUsagePerIteration().get(i);
                var iter = new com.apimarketplace.agent.client.dto.AgentObservabilityRequest.IterationData();
                iter.setIterationNumber(i);
                iter.setPromptTokens(usage.promptTokens() != null ? usage.promptTokens() : 0);
                iter.setCompletionTokens(usage.completionTokens() != null ? usage.completionTokens() : 0);
                if (usage.cacheCreationInputTokens() != null) iter.setCacheCreationTokens(usage.cacheCreationInputTokens());
                if (usage.cacheReadInputTokens() != null) iter.setCacheReadTokens(usage.cacheReadInputTokens());
                if (usage.cachedTokens() != null) iter.setCachedTokens(usage.cachedTokens());
                if (usage.reasoningTokens() != null) iter.setReasoningTokens(usage.reasoningTokens());
                if (agentResult.getIterationDurations() != null && i < agentResult.getIterationDurations().size()) {
                    iter.setDurationMs(agentResult.getIterationDurations().get(i));
                }
                if (agentResult.getFinishReasonsPerIteration() != null && i < agentResult.getFinishReasonsPerIteration().size()) {
                    iter.setFinishReason(agentResult.getFinishReasonsPerIteration().get(i));
                }
                if (toolCallsPerIter != null && i < toolCallsPerIter.size()) {
                    Object count = toolCallsPerIter.get(i);
                    if (count instanceof Number n) iter.setToolCallCount(n.intValue());
                }
                iterations.add(iter);
            }
            req.setIterations(iterations);
        }

        return req;
    }

    /**
     * Build a minimal AgentObservabilityRequest for classify/guardrail executions.
     */
    private com.apimarketplace.agent.client.dto.AgentObservabilityRequest buildMinimalObservabilityRequest(
            Agent agentConfig, ExecutionContext context, String nodeId,
            String agentType, String status, String errorMessage) {
        var req = new com.apimarketplace.agent.client.dto.AgentObservabilityRequest();
        req.setTenantId(context.tenantId());
        // PR20 - failure-path / classify-guardrail mirror of the success-path stamp.
        req.setOrganizationId(context.organizationId());
        req.setAgentType(agentType);
        req.setNodeId(nodeId);
        req.setStatus(status);
        req.setErrorMessage(errorMessage);

        if (agentConfig.agentConfigId() != null) {
            try {
                req.setAgentEntityId(java.util.UUID.fromString(agentConfig.agentConfigId()));
            } catch (IllegalArgumentException ignored) {}
        }

        if (context.plan() != null && context.plan().getId() != null) {
            try {
                req.setWorkflowId(java.util.UUID.fromString(context.plan().getId()));
            } catch (IllegalArgumentException ignored) {}
        }
        if (context.workflowRunId() != null) {
            try {
                req.setWorkflowRunId(java.util.UUID.fromString(context.workflowRunId()));
            } catch (IllegalArgumentException ignored) {}
        }
        req.setRunId(context.runId());
        req.setEpoch(context.epoch());
        req.setSpawn(context.spawn());
        req.setItemIndex(context.itemIndex());

        // LLM config from agent plan
        req.setProvider(agentConfig.provider());
        req.setModel(agentConfig.model());

        return req;
    }

    /**
     * Map conversation messages from agent-service DTO to observability MessageData format.
     */
    private java.util.List<com.apimarketplace.agent.client.dto.AgentObservabilityRequest.MessageData> mapConversationMessages(
            java.util.List<com.apimarketplace.agent.client.dto.execution.ConversationMessageDto> conversationMessages) {
        var messages = new java.util.ArrayList<com.apimarketplace.agent.client.dto.AgentObservabilityRequest.MessageData>();
        for (int i = 0; i < conversationMessages.size(); i++) {
            var src = conversationMessages.get(i);
            var msg = new com.apimarketplace.agent.client.dto.AgentObservabilityRequest.MessageData();
            msg.setSequenceNumber(i);
            msg.setRole(src.role());
            msg.setContent(src.content());
            msg.setToolCallId(src.toolCallId());
            msg.setToolName(src.toolName());
            if (!"SYSTEM".equalsIgnoreCase(src.role())) {
                msg.setIterationNumber(1);
            }
            messages.add(msg);
        }
        return messages;
    }

    // Builder pattern
    public static class Builder {
        private String nodeId;
        private Agent agentConfig;
        private List<String> dependencies;
        private WorkflowEventPublisher eventPublisher;

        public Builder nodeId(String nodeId) {
            this.nodeId = nodeId;
            return this;
        }

        public Builder agentConfig(Agent agentConfig) {
            this.agentConfig = agentConfig;
            return this;
        }

        public Builder dependencies(List<String> dependencies) {
            this.dependencies = dependencies;
            return this;
        }

        public Builder eventPublisher(WorkflowEventPublisher eventPublisher) {
            this.eventPublisher = eventPublisher;
            return this;
        }

        public AgentNode build() {
            AgentNode node = new AgentNode(nodeId, agentConfig, dependencies);
            node.setEventPublisher(eventPublisher);
            return node;
        }
    }

    public static Builder builder() {
        return new Builder();
    }

    // ════════════════════════════════════════════════════════════════
    // Remote execution dispatch helpers (via agent-service)
    // ════════════════════════════════════════════════════════════════

    /**
     * Execute a full agent remotely via agent-service HTTP endpoint.
     * Converts AgentRequest → DTO, calls AgentClient, converts response back.
     */
    private AgentExecutionResult executeAgentRemotely(
            AgentRequest request,
            ExecutionContext context,
            Integer iteration,
            StreamSession streamSession,
            String executionId) {
        long remoteStart = System.currentTimeMillis();

        try {
            // Convert tools to serializable maps
            List<Map<String, Object>> toolMaps = null;
            if (request.tools() != null) {
                toolMaps = request.tools().stream()
                    .map(tool -> {
                        Map<String, Object> map = new LinkedHashMap<>();
                        map.put("id", tool.id());
                        map.put("name", tool.name());
                        map.put("description", tool.description());
                        map.put("apiSlug", tool.apiSlug());
                        map.put("toolSlug", tool.toolSlug());
                        if (tool.parameters() != null) map.put("parameters", tool.parameters());
                        if (tool.requiredParameters() != null) map.put("requiredParameters", tool.requiredParameters());
                        if (tool.metadata() != null) map.put("metadata", tool.metadata());
                        if (tool.timeoutMs() != null) map.put("timeoutMs", tool.timeoutMs());
                        return map;
                    })
                    .toList();
            }

            // Convert conversation history to serializable maps
            List<Map<String, Object>> historyMaps = null;
            if (request.conversationHistory() != null) {
                historyMaps = request.conversationHistory().stream()
                    .map(msg -> {
                        Map<String, Object> map = new LinkedHashMap<>();
                        if (msg.role() != null) map.put("role", msg.role().name());
                        if (msg.content() != null) map.put("content", msg.content());
                        if (msg.toolCallId() != null) map.put("toolCallId", msg.toolCallId());
                        if (msg.toolName() != null) map.put("toolName", msg.toolName());
                        return map;
                    })
                    .toList();
            }

            // Per-agent loop thresholds (V100 columns) come from runtimeOverrides -
            // populated by ExecutionNodeFactory from the same fetch that produced
            // agentConfig. Null fields fall back to platform defaults in the remote
            // LoopDetector. No HTTP here.
            Integer loopIdenticalStop = runtimeOverrides.loopIdenticalStop();
            Integer loopConsecutiveStop = runtimeOverrides.loopConsecutiveStop();

            AgentExecutionRequestDto dto = new AgentExecutionRequestDto(
                request.prompt(),
                request.systemPrompt(),
                request.provider(),
                request.model(),
                request.temperature(),
                request.maxTokens(),
                toolMaps,
                request.autoDiscoverTools(),
                request.maxTools(),
                request.maxIterations(),
                request.executionTimeout(),
                historyMaps,
                request.tenantId(),
                request.runId(),
                request.nodeId(),
                request.variables(),
                request.credentials(),
                null, // maxCreditBudget - agent-service loads it server-side via AgentRepository + BudgetResolver from agentEntityId below
                context.runId(), // streamChannelId = runId for Redis pub/sub
                context.itemIndex(),
                iteration,
                streamSession != null ? streamSession.conversationId() : null,
                streamSession != null ? "conversation" : null, // stream to conversation channel when conversation exists
                null, // parentConversationId (not applicable for workflow)
                null, // subAgentName
                null, // subAgentAvatarUrl
                null, // subAgentId
                context.runId(), // workflowRunId - for sub-agent cancel propagation
                null, // attachments (not applicable for workflow)
                agentConfig.agentConfigId(), // agentEntityId - for fleet real-time activity
                // Bridge-only fields below (tenantBalance/pricingRates) - orchestrator
                // dispatches to agent-service which uses Java guards reading
                // CreditConsumptionClient directly. Bridge isn't in this path, so null is fine.
                null, // tenantBalance
                null, // pricingRates
                null, // creditsConsumedSoFar - Java guards resolve via BudgetResolver in agent-service
                loopIdenticalStop,
                loopConsecutiveStop,
                executionId, // same workflow AgentNode execution id used for conversation persistence
                "WORKFLOW",
                // Per-agent reasoning effort (bridge/CLI providers). Agent-level value only;
                // agent-service applies the per-model default as a lower-precedence fallback.
                runtimeOverrides.reasoningEffort(),
                // Canonical enabled-module set (toolsConfig-derived) - scopes core tool
                // schemas in the remote loop / bridge (parity with chat). Null ⇒ unrestricted.
                request.enabledModules()
            );

            AgentExecutionResponseDto response = agentClient.executeAgent(dto);

            if (response == null) {
                return AgentExecutionResult.failure("Remote agent execution returned null",
                    System.currentTimeMillis() - remoteStart, request.provider());
            }

            return convertRemoteAgentResponse(response);

        } catch (Exception e) {
            logger.error("Remote agent execution failed: {}", e.getMessage(), e);
            return AgentExecutionResult.failure("Remote execution error: " + e.getMessage(),
                System.currentTimeMillis() - remoteStart, request.provider());
        }
    }

    /**
     * Convert AgentExecutionResponseDto to AgentExecutionResult.
     */
    private AgentExecutionResult convertRemoteAgentResponse(AgentExecutionResponseDto response) {
        if (!response.success()) {
            com.apimarketplace.agent.domain.AgentStopReason stopReason = com.apimarketplace.agent.domain.AgentStopReason.ERROR;
            if (response.stopReason() != null) {
                try {
                    stopReason = com.apimarketplace.agent.domain.AgentStopReason.valueOf(response.stopReason());
                } catch (IllegalArgumentException e) {
                    // Keep default ERROR
                }
            }
            return AgentExecutionResult.failure(response.error(), response.durationMs(),
                response.provider(), stopReason);
        }

        // Build CompletionResponse from DTO
        String content = response.content() != null ? response.content() : response.finalResponse();
        com.apimarketplace.agent.domain.CompletionResponse finalResponse =
            com.apimarketplace.agent.domain.CompletionResponse.text(content);

        // Parse stop reason
        com.apimarketplace.agent.domain.AgentStopReason stopReason = com.apimarketplace.agent.domain.AgentStopReason.COMPLETED;
        if (response.stopReason() != null) {
            try {
                stopReason = com.apimarketplace.agent.domain.AgentStopReason.valueOf(response.stopReason());
            } catch (IllegalArgumentException e) {
                // Keep default
            }
        }

        // Convert tool results from Maps back to domain objects
        List<com.apimarketplace.agent.domain.ToolResult> toolResults = new java.util.ArrayList<>();
        if (response.toolResults() != null) {
            for (Map<String, Object> trMap : response.toolResults()) {
                com.apimarketplace.agent.domain.ToolCall toolCall = null;
                Object tcObj = trMap.get("toolCall");
                if (tcObj instanceof Map<?,?> tcRaw) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> tcMap = (Map<String, Object>) tcRaw;
                    @SuppressWarnings("unchecked")
                    Map<String, Object> tcArgs = tcMap.get("arguments") instanceof Map
                        ? (Map<String, Object>) tcMap.get("arguments") : Map.of();
                    toolCall = new com.apimarketplace.agent.domain.ToolCall(
                        (String) tcMap.get("id"),
                        (String) tcMap.get("toolName"),
                        tcArgs,
                        tcMap.get("index") instanceof Number n ? n.intValue() : null
                    );
                }
                toolResults.add(com.apimarketplace.agent.domain.ToolResult.builder()
                    .toolCall(toolCall)
                    .success(Boolean.TRUE.equals(trMap.get("success")))
                    .content((String) trMap.get("content"))
                    .error((String) trMap.get("error"))
                    .durationMs(trMap.get("durationMs") instanceof Number n ? n.longValue() : null)
                    .build());
            }
        }

        // Convert conversation history from Maps back to domain objects
        List<com.apimarketplace.agent.domain.Message> conversationHistory = new java.util.ArrayList<>();
        if (response.conversationHistory() != null) {
            for (Map<String, Object> msgMap : response.conversationHistory()) {
                com.apimarketplace.agent.domain.Message.Role role = com.apimarketplace.agent.domain.Message.Role.USER;
                if (msgMap.get("role") != null) {
                    try {
                        role = com.apimarketplace.agent.domain.Message.Role.valueOf(String.valueOf(msgMap.get("role")));
                    } catch (IllegalArgumentException ignored) {}
                }
                conversationHistory.add(com.apimarketplace.agent.domain.Message.builder()
                    .role(role)
                    .content((String) msgMap.get("content"))
                    .toolCallId((String) msgMap.get("toolCallId"))
                    .toolName((String) msgMap.get("toolName"))
                    .build());
            }
        }

        // Convert total usage
        com.apimarketplace.agent.domain.UsageInfo totalUsage = convertUsageInfo(response.totalUsage());

        // Convert per-iteration usage
        List<com.apimarketplace.agent.domain.UsageInfo> usagePerIteration = new java.util.ArrayList<>();
        if (response.usagePerIteration() != null) {
            for (Map<String, Object> uMap : response.usagePerIteration()) {
                usagePerIteration.add(convertUsageInfo(uMap));
            }
        }

        return AgentExecutionResult.builder()
            .success(true)
            .finalResponse(finalResponse)
            .iterations(response.iterations())
            .durationMs(response.durationMs())
            .provider(response.provider())
            .model(response.model())
            .stopReason(stopReason)
            .metrics(response.metrics() != null ? response.metrics() : Map.of())
            .toolResults(toolResults)
            .conversationHistory(conversationHistory)
            .totalUsage(totalUsage)
            .usagePerIteration(usagePerIteration)
            .iterationDurations(response.iterationDurations() != null ? response.iterationDurations() : List.of())
            .finishReasonsPerIteration(response.finishReasonsPerIteration() != null ? response.finishReasonsPerIteration() : List.of())
            .build();
    }

    /**
     * Convert a Map (from remote DTO) to UsageInfo domain object.
     */
    private com.apimarketplace.agent.domain.UsageInfo convertUsageInfo(Map<String, Object> map) {
        if (map == null) return null;
        return com.apimarketplace.agent.domain.UsageInfo.builder()
            .promptTokens(map.get("promptTokens") instanceof Number n ? n.intValue() : null)
            .completionTokens(map.get("completionTokens") instanceof Number n ? n.intValue() : null)
            .totalTokens(map.get("totalTokens") instanceof Number n ? n.intValue() : null)
            .cacheCreationInputTokens(map.get("cacheCreationInputTokens") instanceof Number n ? n.intValue() : null)
            .cacheReadInputTokens(map.get("cacheReadInputTokens") instanceof Number n ? n.intValue() : null)
            .cachedTokens(map.get("cachedTokens") instanceof Number n ? n.intValue() : null)
            .reasoningTokens(map.get("reasoningTokens") instanceof Number n ? n.intValue() : null)
            .build();
    }

    /**
     * Execute classify remotely via agent-service.
     */
    private ClassifyResult executeClassifyRemotely(ClassifyRequest request) {
        try {
            List<ClassifyRequestDto.CategoryDto> categoryDtos = request.categories() != null
                ? request.categories().stream()
                    .map(c -> new ClassifyRequestDto.CategoryDto(c.label(), c.description()))
                    .toList()
                : List.of();

            ClassifyRequestDto dto = new ClassifyRequestDto(
                request.content(),
                request.prompt(),
                categoryDtos,
                request.provider(),
                request.model(),
                request.temperature(),
                request.maxTokens(),
                request.tenantId(),
                request.agentEntityId()
            );

            ClassifyResponseDto response = agentClient.executeClassify(dto);

            if (response == null) {
                return ClassifyResult.failure("Remote classify execution returned null", 0, request.provider());
            }

            if (response.success()) {
                return ClassifyResult.success(
                    response.selectedCategory(), response.confidence(), response.reasoning(),
                    response.durationMs(), response.provider(), response.model(),
                    response.tokensUsed(), response.promptTokens(), response.completionTokens(),
                    response.systemPrompt(), response.conversationMessages(),
                    response.userPrompt());
            } else {
                return ClassifyResult.failure(response.error(), response.durationMs(), response.provider());
            }

        } catch (Exception e) {
            logger.error("Remote classify execution failed: {}", e.getMessage(), e);
            return ClassifyResult.failure("Remote classify error: " + e.getMessage(), 0, request.provider());
        }
    }

    /**
     * Execute guardrail remotely via agent-service.
     */
    private GuardrailResult executeGuardrailRemotely(GuardrailRequest request) {
        try {
            List<GuardrailRequestDto.RuleDto> ruleDtos = request.rules() != null
                ? request.rules().stream()
                    .map(r -> new GuardrailRequestDto.RuleDto(r.id(), r.description()))
                    .toList()
                : List.of();

            GuardrailRequestDto dto = new GuardrailRequestDto(
                request.content(),
                request.prompt(),
                ruleDtos,
                request.action(),
                request.provider(),
                request.model(),
                request.temperature(),
                request.maxTokens(),
                request.tenantId(),
                request.agentEntityId()
            );

            GuardrailResponseDto response = agentClient.executeGuardrail(dto);

            if (response == null) {
                return GuardrailResult.failure("Remote guardrail execution returned null", 0, request.provider());
            }

            if (response.success()) {
                return GuardrailResult.success(
                    response.passed(), response.violations(), response.details(),
                    response.sanitized(), response.durationMs(), response.provider(),
                    response.model(), response.tokensUsed(),
                    response.promptTokens(), response.completionTokens(),
                    response.systemPrompt(), response.conversationMessages(),
                    response.userPrompt());
            } else {
                return GuardrailResult.failure(response.error(), response.durationMs(), response.provider());
            }

        } catch (Exception e) {
            logger.error("Remote guardrail execution failed: {}", e.getMessage(), e);
            return GuardrailResult.failure("Remote guardrail error: " + e.getMessage(), 0, request.provider());
        }
    }
}
