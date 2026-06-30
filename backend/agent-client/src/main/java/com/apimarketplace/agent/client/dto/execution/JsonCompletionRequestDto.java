package com.apimarketplace.agent.client.dto.execution;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * DTO for the low-level JSON-completion endpoint exposed by agent-service
 * ({@code POST /api/internal/agent/execute/json-completion}).
 *
 * <p>Used by callers that need a one-shot {@code (system, user) → raw
 * string} model call without agent-loop scaffolding - e.g.
 * conversation-service's COLD-summary pipeline, which feeds the raw
 * content back to a local JSON parser.
 *
 * <p>Fields mirror {@code CompletionRequest} at the field level:
 * {@code provider} + {@code model} select the backend, {@code system}
 * sets the behaviour-pinning prompt, {@code user} is the actual prompt.
 * {@code tenantId} is forwarded so per-tenant rate limiters can attribute
 * traffic; {@code null} is accepted for system-internal calls.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record JsonCompletionRequestDto(
    String provider,
    String model,
    String system,
    String user,
    String tenantId
) {}
