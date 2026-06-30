package com.apimarketplace.orchestrator.domain.workflow;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Représente un item issu d'un trigger datasource à traiter individuellement.
 */
public final class TriggerItemContext {

    private final String runId;
    private final String triggerId;
    private final String itemId;
    private final Map<String, Object> payload;
    private final int batchIndex;
    private final int absoluteIndex;
    private final int totalCount;
    private final boolean hasMore;
    private final String tenantId;
    private final Instant receivedAt;

    private TriggerItemContext(Builder builder) {
        this.runId = builder.runId;
        this.triggerId = builder.triggerId;
        this.itemId = builder.itemId != null ? builder.itemId : UUID.randomUUID().toString();
        this.payload = builder.payload;
        this.batchIndex = builder.batchIndex;
        this.absoluteIndex = builder.absoluteIndex;
        this.totalCount = builder.totalCount;
        this.hasMore = builder.hasMore;
        this.tenantId = builder.tenantId;
        this.receivedAt = builder.receivedAt != null ? builder.receivedAt : Instant.now();
    }

    public String getRunId() {
        return runId;
    }

    public String getTriggerId() {
        return triggerId;
    }

    public String getItemId() {
        return itemId;
    }

    public Map<String, Object> getPayload() {
        return payload;
    }

    public int getBatchIndex() {
        return batchIndex;
    }

    public int getAbsoluteIndex() {
        return absoluteIndex;
    }

    public int getTotalCount() {
        return totalCount;
    }

    public boolean isHasMore() {
        return hasMore;
    }

    public String getTenantId() {
        return tenantId;
    }

    public Instant getReceivedAt() {
        return receivedAt;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static TriggerItemContext random(String runId, String triggerId, int absoluteIndex, int totalCount, String tenantId, Map<String, Object> payload) {
        return TriggerItemContext.builder()
            .runId(runId)
            .triggerId(triggerId)
            .absoluteIndex(absoluteIndex)
            .batchIndex(absoluteIndex)
            .totalCount(totalCount)
            .hasMore(absoluteIndex + 1 < totalCount)
            .tenantId(tenantId)
            .payload(payload)
            .build();
    }

    @Override
    public String toString() {
        return "TriggerItemContext{" +
            "runId='" + runId + '\'' +
            ", triggerId='" + triggerId + '\'' +
            ", itemId='" + itemId + '\'' +
            ", batchIndex=" + batchIndex +
            ", absoluteIndex=" + absoluteIndex +
            ", totalCount=" + totalCount +
            ", tenantId='" + tenantId + '\'' +
            '}';
    }

    public static final class Builder {
        private String runId;
        private String triggerId;
        private String itemId;
        private Map<String, Object> payload;
        private int batchIndex;
        private int absoluteIndex;
        private int totalCount;
        private boolean hasMore;
        private String tenantId;
        private Instant receivedAt;

        private Builder() {
        }

        public Builder runId(String runId) {
            this.runId = runId;
            return this;
        }

        public Builder triggerId(String triggerId) {
            this.triggerId = triggerId;
            return this;
        }

        public Builder itemId(String itemId) {
            this.itemId = itemId;
            return this;
        }

        public Builder payload(Map<String, Object> payload) {
            this.payload = payload;
            return this;
        }

        public Builder batchIndex(int batchIndex) {
            this.batchIndex = batchIndex;
            return this;
        }

        public Builder absoluteIndex(int absoluteIndex) {
            this.absoluteIndex = absoluteIndex;
            return this;
        }

        public Builder totalCount(int totalCount) {
            this.totalCount = totalCount;
            return this;
        }

        public Builder hasMore(boolean hasMore) {
            this.hasMore = hasMore;
            return this;
        }

        public Builder tenantId(String tenantId) {
            this.tenantId = tenantId;
            return this;
        }

        public Builder receivedAt(Instant receivedAt) {
            this.receivedAt = receivedAt;
            return this;
        }

        public TriggerItemContext build() {
            Objects.requireNonNull(runId, "runId is required");
            Objects.requireNonNull(triggerId, "triggerId is required");
            Objects.requireNonNull(payload, "payload is required");
            Objects.requireNonNull(tenantId, "tenantId is required");
            return new TriggerItemContext(this);
        }
    }
}
