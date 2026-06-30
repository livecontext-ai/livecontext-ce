package com.apimarketplace.orchestrator.execution.v2.nodes;

import com.apimarketplace.orchestrator.execution.v2.engine.ExecutionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * Fork node - Parallel branching (ALL branches execute).
 *
 * Unlike Decision nodes which select ONE branch based on conditions,
 * Fork nodes activate ALL their successors in parallel.
 *
 * Flow:
 * 1. Execute (simple passthrough, no condition evaluation)
 * 2. Return ALL successors for parallel execution
 *
 * Output contains:
 * - branch_count: number of branches activated
 * - branches: list of branch identifiers
 *
 * Edge Format:
 * - "core:fork_label:branch_0" -> first branch
 * - "core:fork_label:branch_1" -> second branch
 * - etc.
 */
public class ForkNode extends BaseNode {

    private static final Logger logger = LoggerFactory.getLogger(ForkNode.class);

    private final List<ForkBranch> branches;

    public ForkNode(String nodeId, List<ForkBranch> branches) {
        super(nodeId, NodeType.FORK);
        this.branches = branches != null ? branches : new ArrayList<>();
    }

    public ForkNode(String nodeId) {
        this(nodeId, new ArrayList<>());
    }

    @Override
    public NodeExecutionResult execute(ExecutionContext context) {
        logger.debug("Fork node executing: nodeId={}, branches={}, itemId={}",
            nodeId, branches.size(), context.itemId());

        // Build resolved_params snapshot for inspector visibility
        Map<String, Object> resolvedParams = new LinkedHashMap<>();
        resolvedParams.put("branches", branches.size());

        // Build output with branch information
        Map<String, Object> output = new HashMap<>();
        output.put("resolved_params", resolvedParams);
        output.put("node_type", "FORK");
        output.put("fork_node", nodeId);
        output.put("branch_count", branches.size());

        // List all branches that will be activated
        List<Map<String, Object>> branchList = new ArrayList<>();
        for (int i = 0; i < branches.size(); i++) {
            ForkBranch branch = branches.get(i);
            branchList.add(Map.of(
                "index", i,
                "id", branch.id() != null ? branch.id() : "branch_" + i,
                "label", branch.label() != null ? branch.label() : "Branch " + i,
                "target_count", branch.nodes().size()
            ));
        }
        output.put("branches", branchList);

        // Add item context for persistence
        output.put("item_index", context.itemIndex());
        output.put("itemIndex", context.itemIndex());
        output.put("item_id", context.itemId());

        logger.info("Fork activated: nodeId={}, branches={}",
            nodeId, branches.size());

        return NodeExecutionResult.success(nodeId, output);
    }

    /**
     * Fork returns ALL successors - all branches execute in parallel.
     * Unlike Decision which returns only the selected branch.
     */
    @Override
    public List<ExecutionNode> getNextNodes(NodeExecutionResult result) {
        Map<String, ExecutionNode> allNodes = new LinkedHashMap<>();

        // Add nodes from all branches
        for (ForkBranch branch : branches) {
            for (ExecutionNode node : branch.nodes()) {
                if (node != null) {
                    allNodes.putIfAbsent(node.getNodeId(), node);
                }
            }
        }

        // Also add direct successors (if any edges bypass branches)
        for (ExecutionNode node : successors) {
            if (node != null) {
                allNodes.putIfAbsent(node.getNodeId(), node);
            }
        }

        logger.debug("Fork '{}' returning ALL branches: {} nodes", nodeId, allNodes.size());
        return new ArrayList<>(allNodes.values());
    }

    /**
     * Fork has no skipped branches - all branches execute.
     */
    @Override
    public List<ExecutionNode> getSkippedChildNodes(NodeExecutionResult result) {
        return List.of(); // No branches are skipped in a fork
    }

    /**
     * Returns ALL child nodes across all branches (for skip propagation).
     */
    @Override
    public List<ExecutionNode> getAllChildNodes() {
        List<ExecutionNode> allNodes = new ArrayList<>();
        for (ForkBranch branch : branches) {
            allNodes.addAll(branch.nodes());
        }
        allNodes.addAll(successors);
        return allNodes;
    }

    public List<ForkBranch> getBranches() {
        return branches;
    }

    public void addBranch(ForkBranch branch) {
        branches.add(branch);
    }

    /**
     * Polymorphic fork branch wiring from ExecutionNode interface.
     * Creates a new branch with the given ID, label, and target node.
     */
    @Override
    public void addForkBranch(String branchId, String branchLabel, ExecutionNode target) {
        ForkBranch branch = new ForkBranch(branchId, branchLabel, new ArrayList<>());
        branch.addNode(target);
        branches.add(branch);
    }

    /**
     * ForkNode is a fork node.
     */
    @Override
    public boolean isForkNode() {
        return true;
    }

    /**
     * ForkNode skips split handling - it manages its own parallel branches.
     */
    @Override
    public boolean skipsSplitHandling() {
        return true;
    }

    /**
     * Represents a fork branch with its target nodes.
     */
    public record ForkBranch(
        String id,
        String label,
        List<ExecutionNode> nodes
    ) {
        public ForkBranch(String id, String label) {
            this(id, label, new ArrayList<>());
        }

        public void addNode(ExecutionNode node) {
            nodes.add(node);
        }
    }

    // Builder pattern
    public static class Builder {
        private String nodeId;
        private List<ForkBranch> branches = new ArrayList<>();

        public Builder nodeId(String nodeId) {
            this.nodeId = nodeId;
            return this;
        }

        public Builder addBranch(String id, String label, List<ExecutionNode> nodes) {
            branches.add(new ForkBranch(id, label, nodes != null ? nodes : new ArrayList<>()));
            return this;
        }

        public Builder addBranch(String id, String label) {
            return addBranch(id, label, new ArrayList<>());
        }

        public ForkNode build() {
            return new ForkNode(nodeId, branches);
        }
    }

    public static Builder builder() {
        return new Builder();
    }
}
