package com.apimarketplace.orchestrator.domain.execution;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;

/**
 * JPA entity for workflow_signal_waits table.
 *
 * Represents a signal wait registered by a node (WaitNode, UserApprovalNode, WebhookWaitNode).
 * Lifecycle: PENDING -> CLAIMED -> RESOLVED (or CANCELLED at any point).
 *
 * Uses claim-before-process pattern for idempotent multi-instance processing.
 * Split context is persisted in splitItemData JSONB for restart recovery.
 */
@Entity
@Table(name = "workflow_signal_waits", schema = "orchestrator",
    uniqueConstraints = @UniqueConstraint(
        columnNames = {"run_id", "node_id", "item_id", "epoch"}
    )
)
public class SignalWaitEntity {

    /**
     * Signal wait lifecycle status.
     */
    public enum SignalWaitStatus {
        PENDING,
        CLAIMED,
        RESOLVED,
        CANCELLED
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "run_id", nullable = false, length = 100)
    private String runId;

    @Column(name = "item_id", nullable = false, length = 50)
    private String itemId;

    @Column(name = "node_id", nullable = false, length = 200)
    private String nodeId;

    @Column(name = "dag_trigger_id", length = 200)
    private String dagTriggerId;

    @Enumerated(EnumType.STRING)
    @Column(name = "signal_type", nullable = false, length = 30)
    private SignalType signalType;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "signal_config", columnDefinition = "jsonb")
    private Map<String, Object> signalConfig;

    @Column(name = "epoch", nullable = false)
    private int epoch;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private SignalWaitStatus status = SignalWaitStatus.PENDING;

    @Enumerated(EnumType.STRING)
    @Column(name = "resolution", length = 30)
    private SignalResolution resolution;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "resolution_data", columnDefinition = "jsonb")
    private Map<String, Object> resolutionData;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "split_item_data", columnDefinition = "jsonb")
    private Map<String, Object> splitItemData;

    /**
     * Resolved approval context (USER_APPROVAL only): the node's contextTemplate rendered against
     * the execution context at yield time, exposed verbatim to the human approver as
     * {@code approvalContext} in the signals payload. Plain display text (no restoration keys),
     * hence a {@code text} column rather than JSONB. Null for non-approval signals / no template.
     */
    @Column(name = "approval_context", columnDefinition = "text")
    private String approvalContext;

    @Column(name = "expires_at")
    private Instant expiresAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "claimed_at")
    private Instant claimedAt;

    @Column(name = "claimed_by", length = 100)
    private String claimedBy;

    /**
     * Plan v4 §10 fencing token (V182) - generation of {@code instance_lease}
     * at the time the steal CTE claimed this signal. Resume validates equality
     * against the live generation; mismatch → STALE_OWNERSHIP rejection.
     * Default 0 (unclaimed-shape).
     */
    @Column(name = "claimed_generation", nullable = false)
    private long claimedGeneration = 0L;

    /**
     * Plan v4 §10 cooldown (V182) - when a STALE_OWNERSHIP reject NULLs
     * {@link #claimedBy}, this stamp is set to {@code NOW() + 5s} so the
     * rejecting instance does not immediately re-pick the row in an
     * infinite loop. The CTE steal filters on
     * {@code COALESCE(retry_after, '1970-01-01') <= NOW()}.
     */
    @Column(name = "retry_after")
    private Instant retryAfter;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    @Column(name = "resolved_by", length = 255)
    private String resolvedBy;

    @Column(name = "blocking", nullable = false)
    private boolean blocking = true;

    @Column(name = "correlation_id", length = 100)
    private String correlationId;

    // ========================================================================
    // CONSTRUCTORS
    // ========================================================================

    public SignalWaitEntity() {
    }

    // ========================================================================
    // FACTORY METHODS
    // ========================================================================

    /**
     * Create a timer signal wait.
     */
    public static SignalWaitEntity forTimer(
            String runId, String itemId, String nodeId,
            String dagTriggerId, int epoch,
            long durationMs, Clock clock) {
        SignalWaitEntity entity = new SignalWaitEntity();
        entity.runId = runId;
        entity.itemId = itemId;
        entity.nodeId = nodeId;
        entity.dagTriggerId = dagTriggerId;
        entity.epoch = epoch;
        entity.signalType = SignalType.WAIT_TIMER;
        entity.signalConfig = SignalConfig.timer(durationMs);
        entity.status = SignalWaitStatus.PENDING;
        entity.createdAt = clock.instant();
        entity.expiresAt = clock.instant().plusMillis(durationMs);
        return entity;
    }

    /**
     * Create a user approval signal wait.
     */
    public static SignalWaitEntity forUserApproval(
            String runId, String itemId, String nodeId,
            String dagTriggerId, int epoch,
            java.util.List<String> approverRoles,
            int requiredApprovals, Duration timeout, Clock clock) {
        SignalWaitEntity entity = new SignalWaitEntity();
        entity.runId = runId;
        entity.itemId = itemId;
        entity.nodeId = nodeId;
        entity.dagTriggerId = dagTriggerId;
        entity.epoch = epoch;
        entity.signalType = SignalType.USER_APPROVAL;
        entity.signalConfig = SignalConfig.userApproval(approverRoles, requiredApprovals, timeout);
        entity.status = SignalWaitStatus.PENDING;
        entity.createdAt = clock.instant();
        entity.expiresAt = timeout != null ? clock.instant().plus(timeout) : null;
        return entity;
    }

    /**
     * Create a webhook wait signal.
     */
    public static SignalWaitEntity forWebhookWait(
            String runId, String itemId, String nodeId,
            String dagTriggerId, int epoch,
            String webhookToken, Duration timeout, Clock clock) {
        SignalWaitEntity entity = new SignalWaitEntity();
        entity.runId = runId;
        entity.itemId = itemId;
        entity.nodeId = nodeId;
        entity.dagTriggerId = dagTriggerId;
        entity.epoch = epoch;
        entity.signalType = SignalType.WEBHOOK_WAIT;
        entity.signalConfig = SignalConfig.webhookWait(webhookToken, timeout);
        entity.status = SignalWaitStatus.PENDING;
        entity.createdAt = clock.instant();
        entity.expiresAt = timeout != null ? clock.instant().plus(timeout) : null;
        return entity;
    }

    /**
     * Create an agent execution signal wait (async queue mode).
     */
    public static SignalWaitEntity forAgentExecution(
            String runId, String itemId, String nodeId,
            String dagTriggerId, int epoch,
            String correlationId, String agentType,
            String provider, String model,
            Duration timeout, Clock clock) {
        SignalWaitEntity entity = new SignalWaitEntity();
        entity.runId = runId;
        entity.itemId = itemId;
        entity.nodeId = nodeId;
        entity.dagTriggerId = dagTriggerId;
        entity.epoch = epoch;
        entity.signalType = SignalType.AGENT_EXECUTION;
        entity.signalConfig = SignalConfig.agentExecution(correlationId, agentType, provider, model, timeout);
        entity.status = SignalWaitStatus.PENDING;
        entity.createdAt = clock.instant();
        Duration effectiveTimeout = timeout != null ? timeout : Duration.ofMinutes(70);
        entity.expiresAt = clock.instant().plus(effectiveTimeout);
        entity.correlationId = correlationId;
        entity.blocking = true;
        return entity;
    }

    // ========================================================================
    // BUSINESS METHODS
    // ========================================================================

    public boolean isPending() {
        return status == SignalWaitStatus.PENDING;
    }

    public boolean isClaimed() {
        return status == SignalWaitStatus.CLAIMED;
    }

    public boolean isResolved() {
        return status == SignalWaitStatus.RESOLVED;
    }

    public boolean isCancelled() {
        return status == SignalWaitStatus.CANCELLED;
    }

    public boolean isExpired(Instant now) {
        return expiresAt != null && now.isAfter(expiresAt);
    }

    public boolean isActive() {
        return status == SignalWaitStatus.PENDING || status == SignalWaitStatus.CLAIMED;
    }

    // ========================================================================
    // GETTERS AND SETTERS
    // ========================================================================

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getRunId() {
        return runId;
    }

    public void setRunId(String runId) {
        this.runId = runId;
    }

    public String getItemId() {
        return itemId;
    }

    public void setItemId(String itemId) {
        this.itemId = itemId;
    }

    public String getNodeId() {
        return nodeId;
    }

    public void setNodeId(String nodeId) {
        this.nodeId = nodeId;
    }

    public String getDagTriggerId() {
        return dagTriggerId;
    }

    public void setDagTriggerId(String dagTriggerId) {
        this.dagTriggerId = dagTriggerId;
    }

    public SignalType getSignalType() {
        return signalType;
    }

    public void setSignalType(SignalType signalType) {
        this.signalType = signalType;
    }

    public Map<String, Object> getSignalConfig() {
        return signalConfig;
    }

    public void setSignalConfig(Map<String, Object> signalConfig) {
        this.signalConfig = signalConfig;
    }

    public int getEpoch() {
        return epoch;
    }

    public void setEpoch(int epoch) {
        this.epoch = epoch;
    }

    public SignalWaitStatus getStatus() {
        return status;
    }

    public void setStatus(SignalWaitStatus status) {
        this.status = status;
    }

    public SignalResolution getResolution() {
        return resolution;
    }

    public void setResolution(SignalResolution resolution) {
        this.resolution = resolution;
    }

    public Map<String, Object> getResolutionData() {
        return resolutionData;
    }

    public void setResolutionData(Map<String, Object> resolutionData) {
        this.resolutionData = resolutionData;
    }

    public Map<String, Object> getSplitItemData() {
        return splitItemData;
    }

    public void setSplitItemData(Map<String, Object> splitItemData) {
        this.splitItemData = splitItemData;
    }

    public String getApprovalContext() {
        return approvalContext;
    }

    public void setApprovalContext(String approvalContext) {
        this.approvalContext = approvalContext;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(Instant expiresAt) {
        this.expiresAt = expiresAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getClaimedAt() {
        return claimedAt;
    }

    public void setClaimedAt(Instant claimedAt) {
        this.claimedAt = claimedAt;
    }

    public String getClaimedBy() {
        return claimedBy;
    }

    public void setClaimedBy(String claimedBy) {
        this.claimedBy = claimedBy;
    }

    public long getClaimedGeneration() {
        return claimedGeneration;
    }

    public void setClaimedGeneration(long claimedGeneration) {
        this.claimedGeneration = claimedGeneration;
    }

    public Instant getRetryAfter() {
        return retryAfter;
    }

    public void setRetryAfter(Instant retryAfter) {
        this.retryAfter = retryAfter;
    }

    public Instant getResolvedAt() {
        return resolvedAt;
    }

    public void setResolvedAt(Instant resolvedAt) {
        this.resolvedAt = resolvedAt;
    }

    public String getResolvedBy() {
        return resolvedBy;
    }

    public void setResolvedBy(String resolvedBy) {
        // F9 defense-in-depth: V188 widened the column to VARCHAR(255), but
        // resolvedBy comes from upstream-controlled sources (X-User-ID header,
        // request body) - bound at the setter so a future migration narrowing
        // the column or a Keycloak federation refactor cannot re-introduce
        // the "signal stuck forever" overflow path.
        this.resolvedBy = resolvedBy == null || resolvedBy.length() <= 255
                ? resolvedBy
                : resolvedBy.substring(0, 255);
    }

    public boolean isBlocking() {
        return blocking;
    }

    public void setBlocking(boolean blocking) {
        this.blocking = blocking;
    }

    public String getCorrelationId() {
        return correlationId;
    }

    public void setCorrelationId(String correlationId) {
        this.correlationId = correlationId;
    }

    @Override
    public String toString() {
        return "SignalWaitEntity{" +
            "id=" + id +
            ", runId='" + runId + '\'' +
            ", nodeId='" + nodeId + '\'' +
            ", signalType=" + signalType +
            ", status=" + status +
            ", resolution=" + resolution +
            ", epoch=" + epoch +
            ", expiresAt=" + expiresAt +
            '}';
    }
}
