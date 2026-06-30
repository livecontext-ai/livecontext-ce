package com.apimarketplace.orchestrator.trigger.queue;

import com.apimarketplace.orchestrator.domain.WorkflowRunEntity;
import com.apimarketplace.orchestrator.trigger.TriggerType;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Serializable execution request stored in the distributed queue.
 *
 * <p>Do not put JPA entities or futures in this message. A worker can run on a
 * different pod, so it must rehydrate the workflow run from {@code runIdPublic}.
 */
public record QueuedExecutionMessage(
        @JsonProperty("schemaVersion") int schemaVersion,
        @JsonProperty("requestId") String requestId,
        @JsonProperty("runIdPublic") String runIdPublic,
        @JsonProperty("triggerId") String triggerId,
        @JsonProperty("triggerType") TriggerType triggerType,
        @JsonProperty("payload") Map<String, Object> payload,
        @JsonProperty("userPlan") String userPlan,
        @JsonProperty("planPriority") int planPriority,
        @JsonProperty("tenantId") String tenantId,
        @JsonProperty("organizationId") String organizationId,
        @JsonProperty("organizationRole") String organizationRole,
        @JsonProperty("fireAndForget") boolean fireAndForget,
        @JsonProperty("enqueuedAt") Instant enqueuedAt,
        @JsonProperty("expiresAt") Instant expiresAt
) {

    public static final int SCHEMA_VERSION = 1;

    @JsonCreator
    public QueuedExecutionMessage {
        if (schemaVersion != SCHEMA_VERSION) {
            throw new IllegalArgumentException("Unsupported execution queue message schema: " + schemaVersion);
        }
        payload = payload != null
                ? Collections.unmodifiableMap(new LinkedHashMap<>(payload))
                : Map.of();
        enqueuedAt = enqueuedAt != null ? enqueuedAt : Instant.now();
    }

    public static QueuedExecutionMessage fromRun(
            WorkflowRunEntity run,
            String triggerId,
            TriggerType triggerType,
            Map<String, Object> payload,
            String userPlan,
            String requestId,
            boolean fireAndForget,
            Duration queueTimeout,
            Clock clock) {

        Instant now = Instant.now(clock);
        int planPriority = PlanPriorityMapper.getPriority(userPlan);
        return new QueuedExecutionMessage(
                SCHEMA_VERSION,
                requestId,
                run != null ? run.getRunIdPublic() : null,
                triggerId,
                triggerType,
                payload,
                userPlan,
                planPriority,
                run != null ? run.getTenantId() : null,
                run != null ? run.getOrganizationId() : null,
                run != null ? run.getOrganizationRole() : null,
                fireAndForget,
                now,
                now.plus(queueTimeout));
    }

    public boolean isExpired(Clock clock) {
        return expiresAt != null && !Instant.now(clock).isBefore(expiresAt);
    }

    public void validateForExecution() {
        if (requestId == null || requestId.isBlank()) {
            throw new IllegalArgumentException("requestId is required");
        }
        if (runIdPublic == null || runIdPublic.isBlank()) {
            throw new IllegalArgumentException("runIdPublic is required");
        }
        if (triggerId == null || triggerId.isBlank()) {
            throw new IllegalArgumentException("triggerId is required");
        }
        if (triggerType == null) {
            throw new IllegalArgumentException("triggerType is required");
        }
    }
}
