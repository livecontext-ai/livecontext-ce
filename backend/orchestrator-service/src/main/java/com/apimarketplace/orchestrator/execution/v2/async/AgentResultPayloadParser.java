package com.apimarketplace.orchestrator.execution.v2.async;

import com.apimarketplace.orchestrator.domain.execution.AgentResultMessage;

import java.time.Instant;
import java.util.Map;

/**
 * Shared decoder for the raw result payload that agent-service workers publish.
 *
 * <h2>Why this exists</h2>
 * <p>Two code paths deliver worker results into the orchestrator:
 * <ol>
 *   <li>{@code AgentResultSubscriber} - pub/sub happy path, receives the payload via
 *       {@code agent:result:channel:{correlationId}}</li>
 *   <li>{@link AgentRecoveryService} - recovery path, polls the durable result key
 *       {@code agent:result:{correlationId}} on startup and during periodic scans</li>
 * </ol>
 *
 * <p>Both paths decode the same JSON format, but before this class existed they
 * implemented the boolean-success logic slightly differently - one of them wrongly
 * reported {@code {success: false}} as success=true. Consolidating the decode here
 * closes that gap and guarantees they agree on every payload.</p>
 *
 * <h2>Contract</h2>
 * <p>A payload is considered successful iff <b>both</b>:
 * <ul>
 *   <li>No {@code error} field is present, AND</li>
 *   <li>{@code success} is either absent (null) or explicitly {@code Boolean.TRUE}</li>
 * </ul>
 * Explicit {@code success=false} is a failure even when no {@code error} field is
 * present.</p>
 */
public final class AgentResultPayloadParser {

    private AgentResultPayloadParser() {}

    /**
     * Decode a raw worker payload into an {@link AgentResultMessage}.
     *
     * @param correlationId the ID from the channel name or pending entry
     * @param rawResult     the deserialized worker payload
     * @param runId         optional - populated from {@link PendingAgent} on recovery;
     *                      {@code null} on the pub/sub path where the subscriber leaves
     *                      it to be resolved via the registry
     * @param nodeId        optional - same as runId
     * @param agentType     optional - same as runId
     */
    public static AgentResultMessage decode(
            String correlationId,
            Map<String, Object> rawResult,
            String runId,
            String nodeId,
            String agentType) {
        boolean success = isSuccess(rawResult);
        String errorMessage = extractError(rawResult);
        return new AgentResultMessage(
            correlationId,
            runId,
            nodeId,
            rawResult,
            success,
            errorMessage,
            agentType,
            Instant.now()
        );
    }

    /**
     * True iff the payload represents a successful execution.
     * See class Javadoc for the exact rule.
     */
    static boolean isSuccess(Map<String, Object> rawResult) {
        if (rawResult == null) {
            return false;
        }
        if (rawResult.containsKey("error")) {
            return false;
        }
        Object successField = rawResult.get("success");
        // Absent → treat as success (legacy worker format); present → must be Boolean.TRUE
        return successField == null || Boolean.TRUE.equals(successField);
    }

    static String extractError(Map<String, Object> rawResult) {
        if (rawResult == null || !rawResult.containsKey("error")) {
            return null;
        }
        Object err = rawResult.get("error");
        return err == null ? null : String.valueOf(err);
    }
}
