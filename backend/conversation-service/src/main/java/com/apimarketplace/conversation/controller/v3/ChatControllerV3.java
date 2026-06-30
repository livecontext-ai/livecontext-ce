package com.apimarketplace.conversation.controller.v3;

import com.apimarketplace.agent.client.AgentClient;
import com.apimarketplace.conversation.controller.v3.chat.ChatBudgetEstimator;
import com.apimarketplace.conversation.controller.v3.chat.ChatStreamInitializer;
import com.apimarketplace.conversation.controller.v3.chat.StreamStopHandler;
import com.apimarketplace.conversation.dto.ChatRequest;
import com.apimarketplace.common.credit.CreditConsumptionClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.Map;

/**
 * V3 Chat Controller - Main entry point for chat conversations.
 * Uses WebSocket-based streaming via Redis Pub/Sub.
 *
 * This controller follows SOLID principles by delegating to specialized components:
 * - ChatStreamInitializer: Creates and starts new streams
 * - StreamStopHandler: Handles stream stopping
 */
@Slf4j
@RestController
@RequestMapping("/api/v3/chat")
@RequiredArgsConstructor
public class ChatControllerV3 {

    private final ChatStreamInitializer streamInitializer;
    private final StreamStopHandler stopHandler;
    private final AgentClient agentClient;
    private final CreditConsumptionClient creditClient;
    private final ChatBudgetEstimator budgetEstimator;

    /**
     * Chat endpoint returning JSON response for WebSocket-based streaming.
     * Returns {conversationId, streamId, model} immediately, starts agent loop async.
     * Events flow via WebSocket channel: conversation:{conversationId}
     */
    @PostMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<Map<String, String>>> chatJson(
            @RequestBody ChatRequest request,
            @RequestHeader(value = "X-User-ID") String userId,
            @RequestHeader(value = "X-Organization-ID", required = false) String orgId,
            @RequestHeader(value = "X-Organization-Role", required = false) String orgRole,
            @RequestHeader(value = "X-User-Roles", required = false) String userRoles) {

        request.setUserId(userId);
        request.setOrgId(orgId);
        request.setOrgRole(orgRole);
        request.setUserRoles(userRoles);
        log.info("WS chat request - User: {}, Message length: {}, Conversation: {}",
                userId,
                request.getMessage() != null ? request.getMessage().length() : 0,
                request.getConversationId());

        ChatBudgetEstimator.PayloadValidation payloadValidation = budgetEstimator.validatePayload(request);
        if (payloadValidation != null && !payloadValidation.valid()) {
            log.warn("Rejected oversized chat payload for user {}: {}", userId, payloadValidation.error());
            return Mono.just(ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                    .body(Map.of("error", payloadValidation.error())));
        }

        // Cost-aware pre-flight: estimate the projected cost of this turn and refuse
        // if the user's balance can't cover it. The generic checkCredits() only gates
        // on balance >= 1; without this estimate a user with (e.g.) 2 credits would
        // pass the gate, the LLM would run, and the post-flight consumeForChat would
        // reject with 402 - inference delivered, ledger un-debited.
        ChatBudgetEstimator.Estimate estimate = budgetEstimator.estimate(request);
        if (!creditClient.checkChatBudget(
                userId, estimate.provider(), estimate.model(),
                estimate.estimatedPromptTokens(), estimate.estimatedCompletionTokens())) {
            log.warn("Insufficient credits for user {} (provider={}, model={}, estPrompt={}, estCompletion={}), blocking chat request",
                    userId, estimate.provider(), estimate.model(),
                    estimate.estimatedPromptTokens(), estimate.estimatedCompletionTokens());
            return Mono.just(ResponseEntity.status(HttpStatus.PAYMENT_REQUIRED)
                    .body(Map.of("error", "Insufficient credits")));
        }

        return streamInitializer.initializeStreamAsync(request, userId);
    }

    /**
     * Stop an active stream for a conversation.
     */
    @PostMapping("/stop")
    @Transactional
    public ResponseEntity<Map<String, Object>> stopStream(
            @RequestHeader(value = "X-User-ID") String userId,
            @RequestHeader(value = "X-Organization-ID", required = false) String organizationId,
            @RequestBody Map<String, String> requestBody) {

        String conversationId = requestBody.get("conversationId");
        StreamStopHandler.StopResult result = stopHandler.stopStream(userId, conversationId, organizationId);

        if (!result.success() && "Conversation ID is required".equals(result.message())) {
            return ResponseEntity.badRequest().body(stopHandler.toResponseMap(result));
        }

        if (!result.success()) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(stopHandler.toResponseMap(result));
        }

        return ResponseEntity.ok(stopHandler.toResponseMap(result));
    }

    /**
     * Get available AI models.
     */
    @GetMapping("/models")
    public ResponseEntity<Map<String, Object>> getAvailableModels(
            @RequestHeader(value = "X-User-ID", required = false) String userId,
            @RequestHeader(value = "X-Organization-ID", required = false) String organizationId) {
        try {
            Map<String, Object> models = agentClient.getModelsInfo(null, userId, organizationId);
            return ResponseEntity.ok(models);
        } catch (Exception e) {
            log.error("Error retrieving available models: {}", e.getMessage(), e);
            return ResponseEntity.ok(Map.of("error", "Error retrieving models"));
        }
    }
}
