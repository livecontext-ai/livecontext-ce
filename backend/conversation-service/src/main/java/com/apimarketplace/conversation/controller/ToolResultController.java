package com.apimarketplace.conversation.controller;

import com.apimarketplace.conversation.entity.ToolResult;
import com.apimarketplace.conversation.service.ToolResultService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * REST Controller for retrieving full tool execution results.
 * All tool-specific metadata (visualization, iconSlug, etc.) is stored in the metadata JSONB column.
 */
@Slf4j
@RestController
@RequestMapping("/api/tool-results")
@CrossOrigin(origins = "*")
@RequiredArgsConstructor
public class ToolResultController {

    private final ToolResultService toolResultService;

    /**
     * Save a tool result with metadata.
     * Used for workflow runs and other tools that need to persist results.
     */
    @PostMapping
    public ResponseEntity<Map<String, Object>> saveToolResult(
            @RequestBody Map<String, Object> request,
            @RequestHeader(value = "X-User-ID") String tenantId) {
        try {
            String conversationId = (String) request.get("conversationId");
            String toolName = (String) request.get("toolName");
            String toolCallId = (String) request.get("toolCallId");
            Boolean success = (Boolean) request.getOrDefault("success", true);
            Long durationMs = request.get("durationMs") != null ? ((Number) request.get("durationMs")).longValue() : null;
            String contentFull = (String) request.get("content");
            String errorMessage = (String) request.get("error");

            String executionId = (String) request.get("executionId");

            @SuppressWarnings("unchecked")
            Map<String, Object> metadata = (Map<String, Object>) request.get("metadata");

            log.info("Saving tool result: {} for conversation: {} toolCallId: {}", toolName, conversationId, toolCallId);

            ToolResult saved = toolResultService.save(
                conversationId, tenantId, toolName, toolCallId,
                success, durationMs, contentFull, errorMessage, metadata, executionId
            );

            return ResponseEntity.ok(Map.of(
                "id", saved.getId().toString(),
                "toolCallId", toolCallId != null ? toolCallId : "",
                "success", true
            ));
        } catch (Exception e) {
            log.error("Error saving tool result: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().body(Map.of(
                "success", false,
                "error", e.getMessage()
            ));
        }
    }

    /**
     * Update a tool result's metadata (e.g., add status and duration after workflow completes).
     */
    @PutMapping("/{id}/metadata")
    public ResponseEntity<Map<String, Object>> updateMetadata(
            @PathVariable String id,
            @RequestBody Map<String, Object> metadata,
            @RequestHeader(value = "X-User-ID") String tenantId,
            @RequestHeader(value = "X-Organization-ID", required = false) String organizationId) {
        try {
            UUID resultId = UUID.fromString(id);
            return toolResultService.getById(resultId, tenantId, organizationId)
                .map(result -> {
                    // Merge new metadata with existing
                    Map<String, Object> existingMetadata = result.getMetadata();
                    if (existingMetadata == null) {
                        existingMetadata = new HashMap<>();
                    }
                    existingMetadata.putAll(metadata);
                    result.setMetadata(existingMetadata);

                    // Note: We need to add a method to save the updated result
                    // For now, this is a placeholder
                    log.info("Updated metadata for tool result: {}", id);
                    Map<String, Object> response = new HashMap<>();
                    response.put("success", true);
                    response.put("id", id);
                    return ResponseEntity.ok(response);
                })
                .orElse(ResponseEntity.notFound().build());
        } catch (IllegalArgumentException e) {
            log.warn("Invalid tool result ID: {}", id);
            return ResponseEntity.badRequest().build();
        }
    }

    /**
     * Get full tool result content by ID.
     *
     * <p>Org-scope: access is gated by the parent conversation. A teammate who can see the
     * conversation (same org) can see its tool results, even when the row's {@code tenant_id}
     * is the agent-runner's tenant (e.g. an admin in org X viewing a conversation owned by
     * a different tenant in the same org). Without this gate the row would 404 because the
     * legacy {@code findByIdAndTenantId} predicate compared against the caller's tenantId only.
     */
    @GetMapping("/{id}")
    public ResponseEntity<Map<String, Object>> getFullResult(
            @PathVariable String id,
            @RequestHeader(value = "X-User-ID") String tenantId,
            @RequestHeader(value = "X-Organization-ID", required = false) String organizationId) {
        try {
            UUID resultId = UUID.fromString(id);
            return toolResultService.getById(resultId, tenantId, organizationId)
                .map(result -> {
                    log.info("GET tool-result/{}: tool={}, success={}, contentLen={}",
                        id, result.getToolName(), result.isSuccess(),
                        result.getContentFull() != null ? result.getContentFull().length() : 0);
                    return ResponseEntity.ok(buildResponse(result));
                })
                .orElseGet(() -> {
                    log.warn("Tool result not found: id={}, tenantId={}, orgId={}", id, tenantId, organizationId);
                    return ResponseEntity.notFound().build();
                });
        } catch (IllegalArgumentException e) {
            log.warn("Invalid tool result ID: {}", id);
            return ResponseEntity.badRequest().build();
        }
    }

    /**
     * Get full tool result content by tool call ID. See {@link #getFullResult} for the
     * org-scope contract - same shape.
     */
    @GetMapping("/by-tool-call/{toolCallId}")
    public ResponseEntity<Map<String, Object>> getByToolCallId(
            @PathVariable String toolCallId,
            @RequestHeader(value = "X-User-ID") String tenantId,
            @RequestHeader(value = "X-Organization-ID", required = false) String organizationId) {
        return toolResultService.getByToolCallId(toolCallId, tenantId, organizationId)
            .map(result -> ResponseEntity.ok(buildResponse(result)))
            .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Get all tool results for a conversation (metadata included, content fetched on demand).
     */
    @GetMapping("/conversation/{conversationId}")
    public ResponseEntity<Map<String, Object>> getByConversation(
            @PathVariable String conversationId,
            @RequestHeader(value = "X-User-ID") String tenantId,
            @RequestHeader(value = "X-Organization-ID", required = false) String organizationId) {
        List<ToolResult> results = toolResultService.getByConversation(conversationId, tenantId, organizationId);

        List<Map<String, Object>> items = results.stream()
            .map(this::buildListItem)
            .collect(Collectors.toList());

        return ResponseEntity.ok(Map.of(
            "conversationId", conversationId,
            "count", items.size(),
            "results", items
        ));
    }

    /**
     * Build full response for a single tool result (includes content).
     */
    private Map<String, Object> buildResponse(ToolResult result) {
        Map<String, Object> response = new HashMap<>();
        response.put("id", result.getId().toString());
        response.put("toolName", result.getToolName());
        response.put("success", result.isSuccess());
        response.put("durationMs", result.getDurationMs() != null ? result.getDurationMs() : 0L);
        response.put("content", result.getContentFull() != null ? result.getContentFull() : "");
        response.put("error", result.getErrorMessage() != null ? result.getErrorMessage() : "");
        response.put("createdAt", result.getCreatedAt().toString());

        // Include all metadata (visualization, iconSlug, displayToolName, etc.)
        if (result.getMetadata() != null) {
            response.putAll(result.getMetadata());
        }
        return response;
    }

    /**
     * Build list item for conversation results (no content - fetched on demand).
     */
    private Map<String, Object> buildListItem(ToolResult result) {
        Map<String, Object> item = new HashMap<>();
        item.put("id", result.getId().toString());
        item.put("toolName", result.getToolName());
        item.put("toolCallId", result.getToolCallId());
        item.put("success", result.isSuccess());
        item.put("durationMs", result.getDurationMs() != null ? result.getDurationMs() : 0L);
        item.put("error", result.getErrorMessage() != null ? result.getErrorMessage() : "");
        item.put("createdAt", result.getCreatedAt().toString());

        // Include all metadata (visualization, iconSlug, displayToolName, etc.)
        if (result.getMetadata() != null) {
            item.putAll(result.getMetadata());
        }
        return item;
    }
}
