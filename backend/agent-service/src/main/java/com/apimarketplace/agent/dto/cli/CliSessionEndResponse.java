package com.apimarketplace.agent.dto.cli;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * Response DTO for ending a CLI session - returns summary for observability.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CliSessionEndResponse(
    String sessionId,
    int totalToolCalls,
    long totalDurationMs,
    List<String> toolsUsed
) {}
