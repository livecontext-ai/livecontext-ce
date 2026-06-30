package com.apimarketplace.agent.client.dto.execution;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Response DTO for the {@code /api/internal/agent/execute/json-completion}
 * endpoint.
 *
 * <p>Carries the raw content string returned by the provider, with any
 * outer Markdown code fence already stripped server-side. Callers feed
 * this directly to a JSON parser.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record JsonCompletionResponseDto(
    String content
) {}
