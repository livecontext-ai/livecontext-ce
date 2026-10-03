package com.apimarketplace.conversation.controller.internal;

import com.apimarketplace.conversation.dto.ConversationDto;
import com.apimarketplace.conversation.service.ConversationCommandService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * CASA LC-066: the per-task conversation of a RESTRICTED delegated task, for agent-service's task
 * dispatch ({@code ConversationClient.findOrCreateTaskConversation} / {@code findTaskConversation}).
 *
 * <p>A RESTRICTED task's assignee and reviewer turns run here instead of the agent's main
 * conversation, which would otherwise become restricted for good (see
 * {@link ConversationCommandService#findOrCreateTaskConversation}). Keyed by (agent, task), owned by
 * the caller's user ({@code X-User-ID}) and workspace ({@code X-Organization-ID}).
 *
 * <p>Internal-only like every {@code /api/internal/**} path. An agent-service that calls a
 * conversation-service without this endpoint (rolling update) gets a 404 and refuses the
 * restricted task instead of falling back to the agent's main conversation.
 */
@RestController
@RequestMapping("/api/internal/conversations/agent/{agentId}/task/{taskId}")
public class InternalTaskConversationController {

    private static final Logger log = LoggerFactory.getLogger(InternalTaskConversationController.class);

    private final ConversationCommandService conversationCommandService;

    public InternalTaskConversationController(ConversationCommandService conversationCommandService) {
        this.conversationCommandService = conversationCommandService;
    }

    /** The task's conversation for this agent, or 404 when there is none yet. */
    @GetMapping
    public ResponseEntity<ConversationDto> find(
            @PathVariable("agentId") String agentId,
            @PathVariable("taskId") String taskId,
            @RequestHeader(value = "X-Organization-ID", required = false) String organizationId) {
        try {
            return conversationCommandService.findTaskConversation(organizationId, agentId, taskId)
                    .map(ResponseEntity::ok)
                    .orElse(ResponseEntity.notFound().build());
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ResponseEntity.badRequest().build();
        }
    }

    /** Find or create the task's conversation for this agent. Body: optional {@code {"title": ...}}. */
    @PostMapping
    public ResponseEntity<ConversationDto> findOrCreate(
            @PathVariable("agentId") String agentId,
            @PathVariable("taskId") String taskId,
            @RequestHeader(value = "X-User-ID") String userId,
            @RequestHeader(value = "X-Organization-ID", required = false) String organizationId,
            @RequestBody(required = false) Map<String, String> body) {
        try {
            String title = body != null ? body.get("title") : null;
            return ResponseEntity.ok(conversationCommandService.findOrCreateTaskConversation(
                    userId, organizationId, agentId, taskId, title));
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ResponseEntity.badRequest().build();
        } catch (Exception e) {
            log.error("Could not open the conversation of task {} for agent {}: {}", taskId, agentId, e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }
}
