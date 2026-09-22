package com.apimarketplace.conversation.controller.v3;

import com.apimarketplace.common.credit.ChatCreditRefusal;
import com.apimarketplace.agent.client.AgentClient;
import com.apimarketplace.common.web.AdminRoleGuard;
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
                    .body(Map.of("error", ChatCreditRefusal.MESSAGE)));
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
     *
     * @param category which catalogue slice to answer with. Absent means the chat slice,
     *                 which is what this endpoint has always returned and what every
     *                 existing caller still gets. A surface that runs a DIFFERENT kind of
     *                 model names its category: the classify node asks for
     *                 {@code classification} to reach the decision models, which the chat
     *                 slice deliberately excludes (they cannot hold a conversation, so
     *                 they must never appear in a chat picker).
     *
     *                 <p>Restricted to a known set HERE, and not left to the catalogue.
     *                 This endpoint is reachable without a token, and the eligibility rule
     *                 downstream is permissive for a category it does not recognise: an
     *                 arbitrary value would therefore have answered with the whole
     *                 catalogue, to an anonymous caller, the moment this parameter became
     *                 caller-controlled. An unknown value is ignored and the chat slice
     *                 answered, which is what this endpoint has always returned.
     */
    @GetMapping("/models")
    public ResponseEntity<Map<String, Object>> getAvailableModels(
            @RequestParam(value = "category", required = false) String category,
            @RequestHeader(value = "X-User-ID", required = false) String userId,
            @RequestHeader(value = "X-Organization-ID", required = false) String organizationId,
            @RequestHeader(value = "X-User-Roles", defaultValue = "USER") String roles) {
        try {
            // THIS is the public boundary: the endpoint is reachable without a token, and an
            // absent X-User-ID here really does mean an anonymous browser, because the request
            // reached a controller. Said explicitly so agent-service never has to guess.
            boolean publicRead = userId == null || userId.isBlank();
            // And THIS is the only browser-facing catalogue read, which is what makes it the
            // place to enforce "a CLI bridge is never named to an end user". It used to be
            // enforced only by the React hook that renders the picker, so the payload carried
            // the bridges to every signed-in user and one forgetful surface would have shown
            // them. The internal endpoint stays whole for the enricher, the schedulers and the
            // admin panels that point execution links AT a bridge.
            boolean hideBridges = !publicRead && !AdminRoleGuard.isAdmin(roles);
            Map<String, Object> models =
                    agentClient.getModelsInfo(allowedCategory(category), userId, organizationId, publicRead, hideBridges);
            return ResponseEntity.ok(models);
        } catch (Exception e) {
            log.error("Error retrieving available models: {}", e.getMessage(), e);
            return ResponseEntity.ok(Map.of("error", "Error retrieving models"));
        }
    }

    /**
     * The categories this public endpoint will answer for. Anything else, including null,
     * resolves to the chat slice.
     *
     * <p>An allow-list rather than validation, because the downstream check is on the
     * SHAPE of the string and the eligibility rule is permissive for a category it does
     * not know: those two together would have turned an unrecognised value into "return
     * everything". Ignoring it instead means the worst a crafted value can do is get the
     * answer this endpoint gave before the parameter existed.
     */
    private static String allowedCategory(String requested) {
        // The null check is not redundant: Set.of(...) throws on contains(null), and null
        // is the COMMON case here (every caller that wants the chat slice omits it).
        return requested != null && PUBLIC_CATEGORIES.contains(requested) ? requested : null;
    }

    /** Chat is the default and is expressed as null, so it is not listed here. */
    private static final java.util.Set<String> PUBLIC_CATEGORIES = java.util.Set.of("classification");
}
