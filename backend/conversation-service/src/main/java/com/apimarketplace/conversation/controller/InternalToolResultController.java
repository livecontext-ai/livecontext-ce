package com.apimarketplace.conversation.controller;

import com.apimarketplace.conversation.service.ToolResultService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Service-to-service save of a tool result (ConversationClient#saveToolResult, called by the
 * agent loop in agent-service and orchestrator-service for the conversation it is running).
 *
 * <p>Split from the user-facing {@code POST /api/tool-results}, which is routed from outside and
 * therefore scope-checks the conversation against the caller's active workspace. This path is
 * never routed from outside (the cloud gateway does not expose {@code /api/internal}, the CE
 * MonolithSecurityFilter 404s non-loopback callers), and its callers can run from an async
 * context with no workspace header to forward, so it keeps the unchecked save. Kept in the
 * {@code controller} package, not {@code controller.internal}, because the CE monolith excludes
 * that package and CE runs the same agent loop.
 */
@RestController
@RequestMapping("/api/internal/tool-results")
@RequiredArgsConstructor
public class InternalToolResultController {

    private final ToolResultService toolResultService;

    @PostMapping
    public ResponseEntity<Map<String, Object>> saveToolResult(
            @RequestBody Map<String, Object> request,
            @RequestHeader(value = "X-User-ID") String tenantId) {
        return ToolResultController.save(toolResultService, request, tenantId);
    }
}
