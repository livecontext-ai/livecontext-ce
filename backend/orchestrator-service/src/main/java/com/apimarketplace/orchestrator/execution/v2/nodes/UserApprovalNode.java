package com.apimarketplace.orchestrator.execution.v2.nodes;

import com.apimarketplace.orchestrator.domain.execution.SignalConfig;
import com.apimarketplace.orchestrator.domain.execution.SignalType;
import com.apimarketplace.orchestrator.execution.v2.engine.ExecutionContext;
import com.apimarketplace.orchestrator.execution.v2.engine.ServiceRegistry;
import com.apimarketplace.orchestrator.execution.v2.services.UnifiedSignalService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * User approval node - Branching node with ports: approved, rejected, timeout.
 *
 * Registers a USER_APPROVAL signal and YIELDS. Execution resumes when:
 * - A user approves (-> "approved" port)
 * - A user rejects (-> "rejected" port)
 * - The timeout expires (-> "timeout" port)
 *
 * Similar to DecisionNode but the branch is selected by human action,
 * not by expression evaluation.
 *
 * Supports multi-level approval (requiredApprovals > 1).
 */
public class UserApprovalNode extends BaseNode {

    private static final Logger logger = LoggerFactory.getLogger(UserApprovalNode.class);

    private final List<String> approverRoles;
    private final int requiredApprovals;
    private final long timeoutMs;
    /** Template (literal + {{...}}) resolved at yield and shown to the human approver. May be blank. */
    private final String contextTemplate;

    // Port targets: "approved" -> [nodes], "rejected" -> [nodes], "timeout" -> [nodes]
    private final Map<String, List<ExecutionNode>> portTargets;

    // Injected via ExecutionServiceInjector
    private UnifiedSignalService signalService;
    private Clock clock;
    private String dagTriggerId;
    private int epoch;

    public UserApprovalNode(String nodeId, List<String> approverRoles,
                            int requiredApprovals, long timeoutMs, String contextTemplate) {
        super(nodeId, NodeType.APPROVAL);
        this.approverRoles = approverRoles != null ? List.copyOf(approverRoles) : List.of();
        this.requiredApprovals = Math.max(1, requiredApprovals);
        this.timeoutMs = timeoutMs;
        this.contextTemplate = contextTemplate != null ? contextTemplate : "";
        this.portTargets = new HashMap<>();
    }

    @Override
    public NodeExecutionResult execute(ExecutionContext context) {
        logger.info("Approval node executing: nodeId={}, approverRoles={}, requiredApprovals={}",
            nodeId, approverRoles, requiredApprovals);

        Map<String, Object> resolvedParams = new LinkedHashMap<>();
        resolvedParams.put("approverRoles", approverRoles);
        resolvedParams.put("requiredApprovals", requiredApprovals);
        resolvedParams.put("timeoutMs", timeoutMs);
        resolvedParams.put("contextTemplate", contextTemplate);

        try {
            if (signalService == null) {
                logger.warn("Approval node has no signal service, failing: nodeId={}", nodeId);
                return NodeExecutionResult.failureWithOutput(
                    nodeId,
                    "Signal service not available for approval node",
                    buildFailureOutput(resolvedParams, "Signal service not available for approval node"),
                    0L);
            }

            String runId = context.runId();
            String itemId = context.itemId();

            // Resolve runtime signal context (dagTriggerId from plan, epoch from context/default)
            String effectiveDagTriggerId = SignalContextResolver.resolveDagTriggerId(nodeId, dagTriggerId, context);
            int effectiveEpoch = SignalContextResolver.resolveEpoch(epoch, context);

            Map<String, Object> signalConfig = SignalConfig.userApproval(
                approverRoles, requiredApprovals,
                timeoutMs > 0 ? Duration.ofMillis(timeoutMs) : Duration.ofHours(24));

            // Split context: persist the current item alongside the signal so the
            // approver can SEE what each per-item approval refers to (the signals
            // payload exposes it as `itemContext`). Outside a split this stays null.
            Map<String, Object> splitItemData = SignalContextResolver.buildSplitItemData(context);

            // Configured approval context: resolve the author's template against the
            // execution context FROZEN at the moment of the pause, exposed verbatim as
            // `approvalContext` in the signals payload so the approver reads a human
            // sentence (e.g. "Approve refund of 120 EUR for x@y?"). Works outside a
            // split too. A blank template or a malformed expression yields null (the
            // node never fails on it - see resolveApprovalContext).
            String approvalContext = SignalContextResolver.resolveApprovalContext(
                contextTemplate, context, templateAdapter);

            signalService.registerSignal(
                runId, itemId, nodeId, effectiveDagTriggerId, effectiveEpoch,
                SignalType.USER_APPROVAL, signalConfig, splitItemData, approvalContext);

            Clock clk = clock != null ? clock : Clock.systemUTC();
            String expiresAt = clk.instant().plusMillis(timeoutMs > 0 ? timeoutMs : 86400000L).toString();

            logger.info("Approval signal registered (yield): nodeId={}, expiresAt={}", nodeId, expiresAt);

            Map<String, Object> output = new HashMap<>();
            output.put("resolved_params", resolvedParams);
            output.put("approver_roles", approverRoles);
            output.put("required_approvals", requiredApprovals);
            output.put("expires_at", expiresAt);

            return NodeExecutionResult.awaitingSignal(nodeId, SignalType.USER_APPROVAL, output);

        } catch (Exception e) {
            logger.error("Approval node failed: nodeId={}, error={}", nodeId, e.getMessage(), e);
            return NodeExecutionResult.failureWithOutput(
                nodeId,
                e.getMessage(),
                buildFailureOutput(resolvedParams, e.getMessage()),
                0L);
        }
    }

    private Map<String, Object> buildFailureOutput(Map<String, Object> resolvedParams, String error) {
        Map<String, Object> failOutput = new HashMap<>();
        failOutput.put("resolved_params", resolvedParams);
        failOutput.put("approver_roles", approverRoles);
        failOutput.put("required_approvals", requiredApprovals);
        failOutput.put("timeout_ms", timeoutMs);
        failOutput.put("error", error);
        return failOutput;
    }

    /**
     * Returns the successor nodes for the selected port.
     * The port is determined by the signal resolution (stored as "selected_port" in result output).
     */
    @Override
    public List<ExecutionNode> getNextNodes(NodeExecutionResult result) {
        if (result == null || result.isFailure()) {
            return List.of();
        }

        String selectedPort = null;
        if (result.output() != null) {
            Object portVal = result.output().get("selected_port");
            if (portVal != null) {
                selectedPort = portVal.toString();
            }
        }

        if (selectedPort != null && portTargets.containsKey(selectedPort)) {
            logger.info("Approval node selecting port: nodeId={}, port={}", nodeId, selectedPort);
            return portTargets.get(selectedPort);
        }

        if (result.output() != null
                && "CANCELLED".equalsIgnoreCase(String.valueOf(result.output().get("resolution")))) {
            logger.info("Approval node cancelled, no successor selected: nodeId={}", nodeId);
            return List.of();
        }

        // Fallback: if timeout port exists and no explicit port, use timeout
        if (portTargets.containsKey("timeout")) {
            logger.warn("Approval node no selected_port, falling back to timeout: nodeId={}", nodeId);
            return portTargets.get("timeout");
        }

        logger.warn("Approval node no matching port targets: nodeId={}, selectedPort={}", nodeId, selectedPort);
        return List.of();
    }

    /**
     * Returns all port target nodes (for engine traversal, skip propagation).
     * Used by buildNodeMapRecursive() and findNodeById().
     */
    @Override
    public List<ExecutionNode> getSuccessors() {
        List<ExecutionNode> all = new ArrayList<>();
        for (List<ExecutionNode> targets : portTargets.values()) {
            all.addAll(targets);
        }
        return all;
    }

    /**
     * Returns the nodes on non-selected ports (for skip propagation).
     * When "approved" is selected, "rejected" and "timeout" targets are skipped.
     */
    @Override
    public List<ExecutionNode> getSkippedChildNodes(NodeExecutionResult result) {
        String selectedPort = null;
        if (result != null && result.output() != null) {
            Object portVal = result.output().get("selected_port");
            if (portVal != null) {
                selectedPort = portVal.toString();
            }
        }

        List<ExecutionNode> skipped = new ArrayList<>();
        for (Map.Entry<String, List<ExecutionNode>> entry : portTargets.entrySet()) {
            if (!entry.getKey().equals(selectedPort)) {
                skipped.addAll(entry.getValue());
            }
        }
        return skipped;
    }

    /**
     * Returns all port target nodes (all ports).
     */
    public List<ExecutionNode> getAllPortTargetNodes() {
        return getSuccessors();
    }

    /**
     * Returns all child nodes for tree traversal.
     * For UserApprovalNode, this includes all port target nodes (approved, rejected, timeout).
     */
    @Override
    public List<ExecutionNode> getAllChildNodes() {
        return getAllPortTargetNodes();
    }

    /**
     * UserApprovalNode is a branching node - it selects one port based on user action.
     */
    @Override
    public boolean isBranchingNode() {
        return true;
    }

    /**
     * Returns all port targets mapped by port for port-qualified edge emission.
     * Maps: "approved" -> [nodes], "rejected" -> [nodes], "timeout" -> [nodes]
     */
    @Override
    public Map<String, java.util.List<ExecutionNode>> getBranchTargetsByPort() {
        return new HashMap<>(portTargets);
    }

    /**
     * Returns the selected port based on execution result.
     * Returns "approved", "rejected", or "timeout".
     */
    @Override
    public String getSelectedPort(NodeExecutionResult result) {
        if (result == null || result.output() == null) {
            return null;
        }
        Object portVal = result.output().get("selected_port");
        return portVal != null ? portVal.toString() : null;
    }

    /**
     * UserApprovalNode is an approval node.
     */
    @Override
    public boolean isApprovalNode() {
        return true;
    }

    // ========================================================================
    // PORT TARGET MANAGEMENT (used by ApprovalNodeWirer)
    // ========================================================================

    /**
     * Adds a target node for a specific port.
     * Polymorphic wiring method from ExecutionNode interface.
     */
    @Override
    public void addPortTarget(String port, ExecutionNode target) {
        portTargets.computeIfAbsent(port, k -> new ArrayList<>()).add(target);
    }

    /**
     * Gets the port targets map.
     */
    public Map<String, List<ExecutionNode>> getPortTargets() {
        return portTargets;
    }

    // ========================================================================
    // ACCESSORS
    // ========================================================================

    public List<String> getApproverRoles() { return approverRoles; }
    public int getRequiredApprovals() { return requiredApprovals; }
    public long getTimeoutMs() { return timeoutMs; }
    public String getContextTemplate() { return contextTemplate; }

    // ========================================================================
    // SERVICE INJECTION (via ExecutionServiceInjector)
    // ========================================================================

    public void setSignalService(UnifiedSignalService signalService) {
        this.signalService = signalService;
    }

    public void setClock(Clock clock) {
        this.clock = clock;
    }

    public void setDagTriggerId(String dagTriggerId) {
        this.dagTriggerId = dagTriggerId;
    }

    public void setEpoch(int epoch) {
        this.epoch = epoch;
    }

    /**
     * Accepts services from the registry.
     * UserApprovalNode needs signal services for approval flow.
     */
    @Override
    public void acceptServices(ServiceRegistry registry) {
        super.acceptServices(registry);
        this.signalService = registry.getSignalService();
        this.clock = registry.getClock();
        // templateAdapter is injected by BaseNode.acceptServices (used to resolve contextTemplate).
    }

    // ========================================================================
    // BUILDER
    // ========================================================================

    public static class Builder {
        private String nodeId;
        private List<String> approverRoles;
        private int requiredApprovals = 1;
        private long timeoutMs = 86400000L; // 24h default
        private String contextTemplate = "";

        public Builder nodeId(String nodeId) { this.nodeId = nodeId; return this; }
        public Builder approverRoles(List<String> approverRoles) { this.approverRoles = approverRoles; return this; }
        public Builder requiredApprovals(int requiredApprovals) { this.requiredApprovals = requiredApprovals; return this; }
        public Builder timeoutMs(long timeoutMs) { this.timeoutMs = timeoutMs; return this; }
        public Builder contextTemplate(String contextTemplate) { this.contextTemplate = contextTemplate; return this; }

        public UserApprovalNode build() {
            return new UserApprovalNode(nodeId, approverRoles, requiredApprovals, timeoutMs, contextTemplate);
        }
    }

    public static Builder builder() {
        return new Builder();
    }
}
