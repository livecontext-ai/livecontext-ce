package com.apimarketplace.agent.client.dto.execution;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Lightweight DTO for carrying a single conversation message (SYSTEM / USER / ASSISTANT / TOOL)
 * from agent-service back to the orchestrator so it can be forwarded to the observability pipeline.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ConversationMessageDto(
    String role,
    String content,
    String toolCallId,
    String toolName
) {}
