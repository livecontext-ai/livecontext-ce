package com.apimarketplace.orchestrator.execution.v2.nodes;

import com.apimarketplace.orchestrator.domain.execution.SignalConfig;
import com.apimarketplace.orchestrator.domain.execution.SignalType;
import com.apimarketplace.orchestrator.domain.workflow.Core;
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
    /**
     * Optional external-channel delegation (v1: Telegram inline buttons). When configured,
     * the resolved block is embedded in the signal_config at yield so the channel notifier
     * (AFTER_COMMIT listener) can send the message statelessly. Null = in-app approval only.
     */
    private final Core.ApprovalDelegation delegation;
    /**
     * Split-context continuation mode: "all_items" (default) = successors run once after
     * every per-item approval resolves; "per_item" = each resolved item continues its own
     * downstream chain immediately (SignalResumeService reads it back from signal_config).
     * Ignored outside a split.
     */
    private final String continuationMode;

    // Port targets: "approved" -> [nodes], "rejected" -> [nodes], "timeout" -> [nodes]
    private final Map<String, List<ExecutionNode>> portTargets;

    // Injected via ExecutionServiceInjector
    private UnifiedSignalService signalService;
    private Clock clock;
    private String dagTriggerId;
    private int epoch;

    public UserApprovalNode(String nodeId, List<String> approverRoles,
                            int requiredApprovals, long timeoutMs, String contextTemplate) {
        this(nodeId, approverRoles, requiredApprovals, timeoutMs, contextTemplate, null, null);
    }

    public UserApprovalNode(String nodeId, List<String> approverRoles,
                            int requiredApprovals, long timeoutMs, String contextTemplate,
                            Core.ApprovalDelegation delegation) {
        this(nodeId, approverRoles, requiredApprovals, timeoutMs, contextTemplate, delegation, null);
    }

    public UserApprovalNode(String nodeId, List<String> approverRoles,
                            int requiredApprovals, long timeoutMs, String contextTemplate,
                            Core.ApprovalDelegation delegation, String continuationMode) {
        super(nodeId, NodeType.APPROVAL);
        this.approverRoles = approverRoles != null ? List.copyOf(approverRoles) : List.of();
        this.requiredApprovals = Math.max(1, requiredApprovals);
        this.timeoutMs = timeoutMs;
        this.contextTemplate = contextTemplate != null ? contextTemplate : "";
        this.delegation = delegation != null && delegation.isConfigured() ? delegation : null;
        this.continuationMode = Core.ApprovalConfig.normalizeContinuationMode(continuationMode);
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

            // External-channel delegation: resolve chatId/message templates against the
            // execution context FROZEN at the pause (same soft semantics as contextTemplate:
            // a malformed template never fails the node) and embed the resolved block in the
            // signal_config. The channel notifier reads it back from the signal row.
            Map<String, Object> resolvedDelegation = buildResolvedDelegation(context);

            Map<String, Object> signalConfig = SignalConfig.userApprovalWithDelegation(
                approverRoles, requiredApprovals,
                timeoutMs > 0 ? Duration.ofMillis(timeoutMs) : Duration.ofHours(24),
                resolvedDelegation);
            // Persisted with the signal so SignalResumeService can branch per-resolution
            // without reloading the plan (stateless + restart-safe, like the delegation block).
            signalConfig.put("continuationMode", continuationMode);

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
            // Carry the resolved approval context (contextTemplate rendered at yield) on the
            // awaiting-signal result too, so the yield-time output is consistent with the COMPLETED
            // output written at resolution (SignalResumeService.buildSignalResolutionOutput) - the
            // resolved value is authoritatively persisted from the signal at resolution.
            // Omitted when the template is blank or did not resolve (matches resolveApprovalContext).
            if (approvalContext != null && !approvalContext.isBlank()) {
                output.put("approval_context", approvalContext);
            }
            // Advertise the delegated channel so downstream steps (and the resolved output,
            // mirrored by SignalResumeService.buildSignalResolutionOutput) can branch on it.
            if (resolvedDelegation != null) {
                output.put("delegated_channel", resolvedDelegation.get("channel"));
            }

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

    /**
     * Build the resolved delegation block for signal_config, or null when the node
     * has no configured delegation. chatId, messageTemplate and imageTemplate resolve
     * with the same soft semantics as contextTemplate
     * ({@link SignalContextResolver#resolveApprovalContext}): a malformed template
     * falls back (chatId: raw configured string; message and image: omitted, the
     * notifier then uses the resolved approval context / a plain text message) and
     * NEVER fails the node.
     */
    private Map<String, Object> buildResolvedDelegation(ExecutionContext context) {
        if (delegation == null) {
            return null;
        }
        Map<String, Object> block = new LinkedHashMap<>();
        block.put("channel", delegation.channel());
        if (delegation.credentialId() != null) {
            block.put("credentialId", delegation.credentialId());
        }
        String resolvedChatId = SignalContextResolver.resolveApprovalContext(
            delegation.chatId(), context, templateAdapter);
        block.put("chatId", resolvedChatId != null ? resolvedChatId : delegation.chatId());
        String resolvedMessage = SignalContextResolver.resolveApprovalContext(
            delegation.messageTemplate(), context, templateAdapter);
        if (resolvedMessage != null && !resolvedMessage.isBlank()) {
            block.put("message", resolvedMessage);
        }
        // Optional image: resolved WITHOUT stringifying so a whole-string template
        // ({{interface:card.output.screenshot}}) keeps its FileRef Map shape - the
        // exact shape the catalog's telegram send_photo accepts (a String HTTP URL /
        // file_id works too). Omitted when blank or unresolvable: the notifier then
        // falls back to the plain text message (soft semantics, never fails the node).
        Object resolvedImage = SignalContextResolver.resolveApprovalValue(
            delegation.imageTemplate(), context, templateAdapter);
        if (resolvedImage != null) {
            block.put("image", resolvedImage);
        }
        // Optional custom inline-button labels (template-capable): resolved like the
        // message. Omitted when blank so the notifier keeps its channel defaults
        // ("✅ Approve" / "❌ Reject"). Only the displayed text changes; approve/reject
        // callback semantics are untouched.
        String resolvedApproveLabel = SignalContextResolver.resolveApprovalContext(
            delegation.approveLabel(), context, templateAdapter);
        if (resolvedApproveLabel != null && !resolvedApproveLabel.isBlank()) {
            block.put("approveLabel", resolvedApproveLabel);
        }
        String resolvedRejectLabel = SignalContextResolver.resolveApprovalContext(
            delegation.rejectLabel(), context, templateAdapter);
        if (resolvedRejectLabel != null && !resolvedRejectLabel.isBlank()) {
            block.put("rejectLabel", resolvedRejectLabel);
        }
        if (!delegation.allowedUserIds().isEmpty()) {
            block.put("allowedUserIds", delegation.allowedUserIds());
        }
        return block;
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
    public Core.ApprovalDelegation getDelegation() { return delegation; }
    public String getContinuationMode() { return continuationMode; }

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
        private Core.ApprovalDelegation delegation;
        private String continuationMode;

        public Builder nodeId(String nodeId) { this.nodeId = nodeId; return this; }
        public Builder approverRoles(List<String> approverRoles) { this.approverRoles = approverRoles; return this; }
        public Builder requiredApprovals(int requiredApprovals) { this.requiredApprovals = requiredApprovals; return this; }
        public Builder timeoutMs(long timeoutMs) { this.timeoutMs = timeoutMs; return this; }
        public Builder contextTemplate(String contextTemplate) { this.contextTemplate = contextTemplate; return this; }
        public Builder delegation(Core.ApprovalDelegation delegation) { this.delegation = delegation; return this; }
        public Builder continuationMode(String continuationMode) { this.continuationMode = continuationMode; return this; }

        public UserApprovalNode build() {
            return new UserApprovalNode(nodeId, approverRoles, requiredApprovals, timeoutMs, contextTemplate, delegation,
                continuationMode);
        }
    }

    public static Builder builder() {
        return new Builder();
    }
}
