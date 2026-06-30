package com.apimarketplace.agent.dto.cli;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.Map;

/**
 * Response DTO for a single tool execution within a CLI session.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CliToolResponse(
    boolean success,
    String result,
    String error,
    long durationMs,
    Map<String, Object> metadata
) {}
