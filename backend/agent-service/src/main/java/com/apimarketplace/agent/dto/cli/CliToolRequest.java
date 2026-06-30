package com.apimarketplace.agent.dto.cli;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.Map;

/**
 * Request DTO to execute a single tool within a CLI session.
 * Claude Code decides which tool to call and with what params.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CliToolRequest(
    @NotBlank String sessionId,
    @NotBlank String tool,
    @NotNull Map<String, Object> params
) {}
