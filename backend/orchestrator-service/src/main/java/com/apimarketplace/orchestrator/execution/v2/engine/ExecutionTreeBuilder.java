package com.apimarketplace.orchestrator.execution.v2.engine;

import com.apimarketplace.orchestrator.domain.workflow.*;
import com.apimarketplace.orchestrator.execution.v2.nodes.*;
import com.apimarketplace.orchestrator.validation.DAGIndependenceValidator;
import com.apimarketplace.orchestrator.validation.PlanAliasValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Builds an ExecutionTree from a WorkflowPlan.
 * Acts as a FACADE that delegates to specialized components:
 * - ExecutionNodeFactory: Basic node creation (trigger, step, agent, split)
 * - CoreNodeBuilder: Control flow nodes (decision, loop, fork, merge)
 * - EdgeWiringOrchestrator: Edge wiring (two-pass algorithm)
 * - ExecutionServiceInjector: Service injection into nodes
 *
 * <p>Supports multiple independent workflows (multiple triggers) in the same plan.
 * Orphan nodes (not connected to any trigger) are automatically ignored.</p>
 *
 * Note: Event emission, persistence, and metrics are handled by
 * V2ExecutionEventService in UnifiedExecutionEngine, not by nodes.
 */
@Component
public class ExecutionTreeBuilder {

    private static final Logger logger = LoggerFactory.getLogger(ExecutionTreeBuilder.class);

    private final ExecutionNodeFactory nodeFactory;
    private final EdgeWiringOrchestrator edgeWirer;
    private final ExecutionServiceInjector serviceInjector;
    private final DAGIndependenceValidator dagValidator;
    private final PlanAliasValidator aliasValidator;

    public ExecutionTreeBuilder(
            ExecutionNodeFactory nodeFactory,
            EdgeWiringOrchestrator edgeWirer,
            ExecutionServiceInjector serviceInjector,
            DAGIndependenceValidator dagValidator,
            PlanAliasValidator aliasValidator) {
        this.nodeFactory = nodeFactory;
        this.edgeWirer = edgeWirer;
        this.serviceInjector = serviceInjector;
        this.dagValidator = dagValidator;
        this.aliasValidator = aliasValidator;
    }

    /**
     * Builds an execution tree from a workflow plan.
     * Supports multiple triggers (multi-workflow mode).
     *
     * <p>Legacy entry point - defaults {@code organizationId} and
     * {@code organizationRole} to {@code null} (personal scope). New callers
     * should use the 6-arg overload which carries the workspace identity.</p>
     */
    public ExecutionTree build(
            String runId,
            String workflowRunId,
            String tenantId,
            WorkflowPlan plan) {
        return build(runId, workflowRunId, tenantId, plan, null, null);
    }

    /**
     * PR15 - org-aware overload. Threads the workspace identity from
     * {@code WorkflowRunEntity.organization_id / .organization_role} onto the
     * ExecutionTree so node executors read the active org from the context
     * rather than re-reading the legacy metadata['__orgId__'] stash.
     *
     * @param runId             The execution run ID
     * @param workflowRunId     The workflow run ID
     * @param tenantId          The tenant ID
     * @param plan              The workflow plan to build from
     * @param organizationId    Active workspace org id (null = personal scope)
     * @param organizationRole  Caller's role in the active org (null when personal)
     * @return The built execution tree
     */
    public ExecutionTree build(
            String runId,
            String workflowRunId,
            String tenantId,
            WorkflowPlan plan,
            String organizationId,
            String organizationRole) {

        logger.info("🌳 Building execution tree: runId={} (org={})", runId, organizationId);

        try {
            // Create node map to hold all nodes
            Map<String, ExecutionNode> nodeMap = new HashMap<>();

            // Step 0: Validate DAG independence for multi-trigger workflows
            if (plan.getTriggers() != null && plan.getTriggers().size() > 1) {
                dagValidator.validateIndependence(plan);
            }

            // Step 0b: Validate alias uniqueness (fails or warns per config flag)
            aliasValidator.validate(plan);

            // Step 1: Create basic nodes (trigger, step, agent, split, end)
            nodeFactory.createBasicNodes(nodeMap, plan, tenantId, organizationId);

            // Step 2: Wire successors from edges (includes core node creation)
            edgeWirer.wireSuccessorsFromEdges(nodeMap, plan);

            // Step 3: Inject execution services (toolsGateway, templateAdapter)
            serviceInjector.injectServices(nodeMap);

            // Step 4: Find ALL root nodes (all triggers)
            List<ExecutionNode> rootNodes = findAllRootNodes(nodeMap);

            // Build the execution tree
            ExecutionTree tree = ExecutionTree.builder()
                .runId(runId)
                .workflowRunId(workflowRunId)
                .tenantId(tenantId)
                .plan(plan)
                .rootNodes(rootNodes)
                .organizationId(organizationId)
                .organizationRole(organizationRole)
                .build();

            logger.info("✅ Execution tree built: runId={}, nodeCount={}, triggerCount={}",
                runId, nodeMap.size(), rootNodes.size());

            if (rootNodes.size() > 1) {
                logger.info("🔀 Multi-workflow mode enabled with {} independent triggers: {}",
                    rootNodes.size(),
                    rootNodes.stream().map(ExecutionNode::getNodeId).collect(Collectors.joining(", ")));
            }

            return tree;

        } catch (Exception e) {
            logger.error("❌ Failed to build execution tree: runId={}, error={}", runId, e.getMessage(), e);
            throw new RuntimeException("Failed to build execution tree", e);
        }
    }

    /**
     * Finds ALL root nodes (triggers) in the node map.
     * Each trigger represents an independent workflow entry point.
     *
     * @param nodeMap The map of all nodes
     * @return List of trigger nodes (may be empty if no triggers found)
     */
    private List<ExecutionNode> findAllRootNodes(Map<String, ExecutionNode> nodeMap) {
        List<ExecutionNode> triggers = nodeMap.values().stream()
            .filter(ExecutionNode::isTriggerNode)
            .collect(Collectors.toList());

        if (triggers.isEmpty()) {
            logger.warn("⚠️ No trigger nodes found in workflow plan");
        }

        return triggers;
    }
}
