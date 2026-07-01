package com.apimarketplace.orchestrator.execution.v2.engine;

import com.apimarketplace.orchestrator.domain.workflow.*;
import com.apimarketplace.orchestrator.execution.v2.nodes.*;
import com.apimarketplace.orchestrator.services.TemplateEngine;
import com.apimarketplace.orchestrator.services.agent.AgentConfigResolver;
import com.apimarketplace.orchestrator.utils.LabelNormalizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Factory for creating ExecutionNodes from WorkflowPlan elements.
 * Handles creation of basic nodes: trigger, step, agent, split, and end nodes.
 *
 * Control flow nodes (decision, loop, fork, merge) are created by CoreNodeBuilder.
 */
@Component
public class ExecutionNodeFactory {

    private static final Logger logger = LoggerFactory.getLogger(ExecutionNodeFactory.class);

    private final TemplateEngine templateEngine;
    private final AgentConfigResolver agentConfigResolver;

    public ExecutionNodeFactory(TemplateEngine templateEngine, AgentConfigResolver agentConfigResolver) {
        this.templateEngine = templateEngine;
        this.agentConfigResolver = agentConfigResolver;
    }

    /**
     * Creates all basic nodes from a WorkflowPlan and adds them to the nodeMap.
     * This includes triggers, steps, agents, tables, split loops, and the end node.
     *
     * @param nodeMap The map to populate with created nodes
     * @param plan The workflow plan containing node definitions
     */
    public void createBasicNodes(Map<String, ExecutionNode> nodeMap, WorkflowPlan plan) {
        createTriggerNodes(nodeMap, plan);
        createStepNodes(nodeMap, plan);
        createAgentNodes(nodeMap, plan);
        createTableNodes(nodeMap, plan);
        createSplitNodes(nodeMap, plan);
        createInterfaceNodes(nodeMap, plan);
        createEndNode(nodeMap);
    }

    /**
     * Creates all basic nodes with tenant context for agent entity resolution.
     *
     * @param nodeMap  The map to populate with created nodes
     * @param plan     The workflow plan containing node definitions
     * @param tenantId The tenant ID for agent entity resolution
     */
    public void createBasicNodes(Map<String, ExecutionNode> nodeMap, WorkflowPlan plan, String tenantId) {
        createBasicNodes(nodeMap, plan, tenantId, null);
    }

    /**
     * Creates all basic nodes with tenant and workspace context for agent entity resolution.
     *
     * @param nodeMap        The map to populate with created nodes
     * @param plan           The workflow plan containing node definitions
     * @param tenantId       The tenant ID for agent entity resolution
     * @param organizationId The active workspace organization ID, or null for personal scope
     */
    public void createBasicNodes(
            Map<String, ExecutionNode> nodeMap,
            WorkflowPlan plan,
            String tenantId,
            String organizationId) {
        createTriggerNodes(nodeMap, plan);
        createStepNodes(nodeMap, plan);
        createAgentNodes(nodeMap, plan, tenantId, organizationId);
        createTableNodes(nodeMap, plan);
        createSplitNodes(nodeMap, plan);
        createInterfaceNodes(nodeMap, plan);
        createEndNode(nodeMap);
    }

    /**
     * Creates ALL trigger nodes from the plan.
     * Each trigger represents an independent workflow entry point.
     * Passes full Trigger to enable trigger.input template resolution.
     */
    public void createTriggerNodes(Map<String, ExecutionNode> nodeMap, WorkflowPlan plan) {
        if (plan.getTriggers() == null || plan.getTriggers().isEmpty()) {
            return;
        }

        for (Trigger trigger : plan.getTriggers()) {
            String triggerKey = trigger.getNormalizedKey();
            TriggerNode triggerNode = new TriggerNode(triggerKey, trigger);
            nodeMap.put(triggerKey, triggerNode);
            // Phase H (archi-refoundation 2026-05-04) - INFO → DEBUG: plan-parse trace,
            // not an operational state transition. ORCH_OPS #1b: ~1500+ lines / 10min in prod.
            logger.debug("📍 Added trigger: key={}", triggerKey);
        }

        if (plan.getTriggers().size() > 1) {
            logger.debug("🔀 Multi-workflow mode: {} independent triggers detected", plan.getTriggers().size());
        }
    }

    /**
     * Creates step nodes from the plan.
     * Nodes are added under both the normalized key and raw ID-based key for edge matching.
     * Edge 'from' and 'to' fields may use raw IDs like "mcp:__gateway_xxx"
     * but our canonical key is "mcp:normalized_label".
     */
    public void createStepNodes(Map<String, ExecutionNode> nodeMap, WorkflowPlan plan) {
        if (plan.getMcps() == null) {
            return;
        }

        for (Step step : plan.getMcps()) {
            String stepKey = step.getNormalizedKey();
            StepNode stepNode = new StepNode(stepKey, step);
            nodeMap.put(stepKey, stepNode);
            // Phase H - INFO → DEBUG: plan-parse trace.
            logger.debug("📍 Added step: key={}, id={}, label={}", stepKey, step.id(), step.label());

            // Also add by raw ID if different from normalized key
            if (step.id() != null) {
                String idKey = "mcp:" + step.id().toLowerCase();
                if (!idKey.equals(stepKey)) {
                    nodeMap.put(idKey, stepNode);
                    logger.debug("📍 Added step alias: {} -> {}", idKey, stepKey);
                }
            }
        }
    }

    /**
     * Creates agent nodes from the plan.
     * Nodes are added under both the normalized key and raw label for edge matching.
     */
    public void createAgentNodes(Map<String, ExecutionNode> nodeMap, WorkflowPlan plan) {
        createAgentNodes(nodeMap, plan, null);
    }

    /**
     * Creates agent nodes from the plan with optional tenant context for entity resolution.
     * When tenantId is provided, agents with agentConfigId are resolved from the entity DB.
     */
    public void createAgentNodes(Map<String, ExecutionNode> nodeMap, WorkflowPlan plan, String tenantId) {
        createAgentNodes(nodeMap, plan, tenantId, null);
    }

    /**
     * Creates agent nodes from the plan with optional tenant and workspace context for entity resolution.
     * When tenantId is provided, agents with agentConfigId are resolved from the entity DB.
     */
    public void createAgentNodes(
            Map<String, ExecutionNode> nodeMap,
            WorkflowPlan plan,
            String tenantId,
            String organizationId) {
        if (plan.getAgents() == null) {
            return;
        }

        for (Agent agent : plan.getAgents()) {
            // Resolve entity reference if present. Single agent-service fetch
            // produces both the merged Agent record and the runtime overrides
            // (executionTimeout / loop thresholds) - AgentNode reads the
            // overrides without re-resolving.
            Agent resolvedAgent = agent;
            com.apimarketplace.orchestrator.services.agent.AgentRuntimeOverrides overrides =
                com.apimarketplace.orchestrator.services.agent.AgentRuntimeOverrides.EMPTY;
            if (tenantId != null && agent.agentConfigId() != null && agentConfigResolver != null) {
                com.apimarketplace.orchestrator.services.agent.AgentConfigResolver.ResolveResult result =
                    agentConfigResolver.resolve(agent, tenantId, organizationId);
                resolvedAgent = result.agent();
                overrides = result.overrides();
            }

            String agentKey = resolvedAgent.getNormalizedKey(); // "agent:my_assistant"

            // Browser-agent gets its own node - distinct execution shape (no
            // conversational LLM loop, delegates to websearch via
            // BrowserAgentModule, raises BROWSER_USER_TAKEOVER on pause). All
            // other types (agent / classify / guardrail) share AgentNode.
            ExecutionNode agentNode;
            if ("browser_agent".equalsIgnoreCase(resolvedAgent.type())) {
                java.util.Map<String, Object> nodeConfig = new java.util.HashMap<>();
                nodeConfig.put("agent", resolvedAgent);
                if (resolvedAgent.params() != null) {
                    nodeConfig.putAll(resolvedAgent.params());
                }
                agentNode = new com.apimarketplace.orchestrator.execution.v2.nodes.BrowserAgentNode(
                    agentKey, nodeConfig);
                logger.info("🌐 Added browser-agent: key={}, label={}",
                    agentKey, resolvedAgent.label());
            } else {
                AgentNode node = new AgentNode(agentKey, resolvedAgent);
                node.setRuntimeOverrides(overrides);
                agentNode = node;
                logger.info("🤖 Added agent: key={}, label={}, entityRef={}",
                    agentKey, resolvedAgent.label(), resolvedAgent.agentConfigId() != null);
            }
            nodeMap.put(agentKey, agentNode);

            // Also add by raw label if different from normalized key
            String rawLabelKey = "agent:" + resolvedAgent.label().toLowerCase();
            if (!rawLabelKey.equals(agentKey)) {
                nodeMap.put(rawLabelKey, agentNode);
                logger.info("🤖 Added agent alias: {} -> {}", rawLabelKey, agentKey);
            }
        }
    }

    /**
     * Creates table nodes (insert_row, update_row, delete_row, read_rows, find) from the plan.
     * Tables are stored separately from mcps in plan.getTables() but execute as StepNodes.
     * They use "table:" prefix (e.g., "table:save_to_db") instead of "mcp:" prefix.
     *
     * <p>Special case: "crud-find" type creates a FindNode instead of StepNode.
     * FindNode combines CRUD read with split-like parallel execution per row.
     */
    public void createTableNodes(Map<String, ExecutionNode> nodeMap, WorkflowPlan plan) {
        if (plan.getTables() == null || plan.getTables().isEmpty()) {
            return;
        }

        for (Step table : plan.getTables()) {
            // Tables use "table:" prefix instead of "mcp:" (Step.getNormalizedKey() hardcodes "mcp:")
            String normalizedLabel = LabelNormalizer.normalizeLabel(table.label());
            if (normalizedLabel == null || normalizedLabel.isEmpty()) {
                logger.warn("Table step has no label, skipping: id={}", table.id());
                continue;
            }
            String tableKey = "table:" + normalizedLabel;

            ExecutionNode tableNode;
            if (table.isFindStep()) {
                // FindNode: queries table and returns items[] array (no split)
                String listExpression = table.params() != null
                    ? (String) table.params().get("list") : null;
                int maxItems = table.crud() != null && table.crud().limit() != null
                    ? table.crud().limit() : 100;
                tableNode = new FindNode(
                    tableKey, table, listExpression, maxItems, templateEngine);
                logger.info("🔍 Added find: key={}, id={}, label={}, maxItems={}, hasList={}",
                    tableKey, table.id(), table.label(), maxItems, listExpression != null);
            } else {
                tableNode = new StepNode(tableKey, table);
                logger.info("📊 Added table: key={}, id={}, label={}", tableKey, table.id(), table.label());
            }

            nodeMap.put(tableKey, tableNode);

            // Also add by raw ID if different from normalized key
            if (table.id() != null) {
                String idKey = "table:" + table.id().toLowerCase();
                if (!idKey.equals(tableKey)) {
                    nodeMap.put(idKey, tableNode);
                    logger.info("📊 Added table alias: {} -> {}", idKey, tableKey);
                }
            }
        }
    }

    /**
     * Creates split nodes from cores with type="split".
     * Body nodes are resolved from the nodeMap.
     */
    public void createSplitNodes(Map<String, ExecutionNode> nodeMap, WorkflowPlan plan) {
        logger.info("[ExecutionNodeFactory] Checking for split loops...");
        Map<String, SplitLoop> splitLoops = plan.getSplitLoops();
        logger.info("[ExecutionNodeFactory] getSplitLoops() returned: {} (size: {})",
            splitLoops != null ? "NOT NULL" : "NULL",
            splitLoops != null ? splitLoops.size() : 0);

        if (splitLoops == null || splitLoops.isEmpty()) {
            return;
        }

        logger.info("[ExecutionNodeFactory] Found {} split loops to process", splitLoops.size());
        for (Map.Entry<String, SplitLoop> entry : splitLoops.entrySet()) {
            SplitLoop splitLoop = entry.getValue();
            String loopId = entry.getKey();
            String splitKey = "core:" + loopId;

            // Find body nodes (steps inside split)
            List<ExecutionNode> bodyNodes = new ArrayList<>();
            if (splitLoop.steps() != null) {
                for (SplitStep splitStep : splitLoop.steps()) {
                    if (splitStep != null && splitStep.stepId() != null) {
                        ExecutionNode stepNode = nodeMap.get(splitStep.stepId());
                        if (stepNode != null) {
                            bodyNodes.add(stepNode);
                        } else {
                            logger.warn("Split body step not found: {}", splitStep.stepId());
                        }
                    }
                }
            }

            // Create SplitNode
            SplitNode splitNode = new SplitNode(
                splitKey,
                splitLoop.list(),
                splitLoop.maxItems(),
                splitLoop.splitStrategy(),
                bodyNodes,
                templateEngine
            );

            nodeMap.put(splitKey, splitNode);
            logger.info("Added split: key={}, loopId={}, items={}, bodyNodes={}",
                splitKey, loopId, splitLoop.maxItems(), bodyNodes.size());

            // Add alias for core reference (edges may use "core:xxx")
            String coreKey = "core:" + loopId;
            nodeMap.put(coreKey, splitNode);
            logger.info("Added split alias: {} -> {}", coreKey, splitKey);
        }
    }

    /**
     * Creates interface nodes from the plan.
     * Interfaces participate in DAG execution (blocking with __continue, auto-advance otherwise).
     */
    public void createInterfaceNodes(Map<String, ExecutionNode> nodeMap, WorkflowPlan plan) {
        if (plan.getInterfaces() == null || plan.getInterfaces().isEmpty()) {
            return;
        }

        for (InterfaceDef iface : plan.getInterfaces()) {
            String interfaceKey = iface.getNormalizedKey();
            InterfaceNode interfaceNode = new InterfaceNode(
                interfaceKey, iface.id(), iface.actionMapping(),
                Boolean.TRUE.equals(iface.isEntryInterface()),
                Boolean.TRUE.equals(iface.generateScreenshot()),
                Boolean.TRUE.equals(iface.exposeRenderedSource()),
                Boolean.TRUE.equals(iface.generatePdf()),
                iface.pdfFormat(),
                Boolean.TRUE.equals(iface.pdfLandscape()));
            nodeMap.put(interfaceKey, interfaceNode);
            logger.info("Added interface: key={}, id={}, label={}",
                interfaceKey, iface.id(), iface.label());

            // Also add by raw ID if different from normalized key
            if (iface.id() != null) {
                String idKey = "interface:" + iface.id().toLowerCase();
                if (!idKey.equals(interfaceKey)) {
                    nodeMap.put(idKey, interfaceNode);
                    logger.info("Added interface alias: {} -> {}", idKey, interfaceKey);
                }
            }
        }
    }

    /**
     * Creates the end node for the workflow.
     */
    public void createEndNode(Map<String, ExecutionNode> nodeMap) {
        EndNode endNode = new EndNode("__end__");
        nodeMap.put("__end__", endNode);
        logger.info("🏁 Added end node");
    }
}
