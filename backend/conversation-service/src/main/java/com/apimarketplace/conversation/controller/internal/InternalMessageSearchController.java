package com.apimarketplace.conversation.controller.internal;

import com.apimarketplace.conversation.dto.MessageSearchRequest;
import com.apimarketplace.conversation.dto.MessageSearchResponse;
import com.apimarketplace.conversation.service.MessageSearchService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;

/**
 * Internal (service-to-service) full-text search.
 *
 * <p>Used by the orchestrator/agent-service when an MCP {@code search_messages}
 * action runs (PR 3). The caller is responsible for resolving the visible
 * conversation set according to the agent's permission scope (allowlist,
 * children, reviewer-of-task, …) before calling here.
 *
 * <p>This controller intentionally performs NO permission check beyond what
 * the service layer already validates (query length, list size). The
 * {@code /api/internal} prefix is gated at the network boundary.
 */
@RestController
@RequestMapping("/api/internal/messages")
public class InternalMessageSearchController {

    private static final Logger logger = LoggerFactory.getLogger(InternalMessageSearchController.class);

    private final MessageSearchService messageSearchService;

    public InternalMessageSearchController(MessageSearchService messageSearchService) {
        this.messageSearchService = messageSearchService;
    }

    @PostMapping("/search")
    public ResponseEntity<?> search(@RequestBody InternalSearchPayload payload) {
        try {
            MessageSearchRequest request = new MessageSearchRequest(
                    payload.conversationIds(),
                    payload.query(),
                    payload.since(),
                    payload.until(),
                    payload.roles(),
                    payload.toolName(),
                    payload.limit() == 0 ? MessageSearchService.DEFAULT_LIMIT : payload.limit(),
                    payload.cursor()
            );
            MessageSearchResponse response = messageSearchService.search(
                    request, payload.includeInactive());
            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException e) {
            logger.debug("Invalid internal search request: {}", e.getMessage());
            return ResponseEntity.badRequest().body(java.util.Map.of("error", e.getMessage()));
        }
    }

    /**
     * Wire format for {@code POST /api/internal/messages/search}.
     */
    public record InternalSearchPayload(
            List<String> conversationIds,
            String query,
            Instant since,
            Instant until,
            List<String> roles,
            String toolName,
            boolean includeInactive,
            int limit,
            String cursor
    ) {}
}
