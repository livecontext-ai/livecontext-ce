package com.apimarketplace.orchestrator.domain;

import jakarta.persistence.*;
import java.time.Instant;

/**
 * Entity for pending and pre-approved signals in step-by-step workflow execution.
 * Replaces Redis cache for step-by-step mode persistence.
 *
 * Signal types:
 * - PENDING: Node is waiting for user to click "Execute"
 * - PRE_APPROVED: User clicked ahead before node was ready
 */
@Entity
@Table(name = "workflow_pending_signals", schema = "orchestrator")
public class PendingSignalEntity {

    public enum SignalType {
        PENDING,
        PRE_APPROVED
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

    @Enumerated(EnumType.STRING)
    @Column(name = "signal_type", nullable = false, length = 20)
    private SignalType signalType;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "expires_at")
    private Instant expiresAt;

    public PendingSignalEntity() {
    }

    public PendingSignalEntity(String runId, String itemId, String nodeId, SignalType signalType) {
        this.runId = runId;
        this.itemId = itemId;
        this.nodeId = nodeId;
        this.signalType = signalType;
        this.createdAt = Instant.now();
    }

    public PendingSignalEntity(String runId, String itemId, String nodeId, SignalType signalType, Instant expiresAt) {
        this(runId, itemId, nodeId, signalType);
        this.expiresAt = expiresAt;
    }

    // Static factory methods
    public static PendingSignalEntity pending(String runId, String itemId, String nodeId) {
        return new PendingSignalEntity(runId, itemId, nodeId, SignalType.PENDING);
    }

    public static PendingSignalEntity preApproved(String runId, String itemId, String nodeId) {
        return new PendingSignalEntity(runId, itemId, nodeId, SignalType.PRE_APPROVED);
    }

    public static PendingSignalEntity preApprovedWithExpiry(String runId, String itemId, String nodeId, Instant expiresAt) {
        return new PendingSignalEntity(runId, itemId, nodeId, SignalType.PRE_APPROVED, expiresAt);
    }

    // Getters and setters
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

    public SignalType getSignalType() {
        return signalType;
    }

    public void setSignalType(SignalType signalType) {
        this.signalType = signalType;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(Instant expiresAt) {
        this.expiresAt = expiresAt;
    }

    public boolean isPending() {
        return signalType == SignalType.PENDING;
    }

    public boolean isPreApproved() {
        return signalType == SignalType.PRE_APPROVED;
    }

    public boolean isExpired() {
        return expiresAt != null && Instant.now().isAfter(expiresAt);
    }
}
