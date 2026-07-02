package com.apimarketplace.monolith.chat;

import com.apimarketplace.agent.cloud.RuntimeLlmProviderResolver;
import com.apimarketplace.agent.factory.LLMProviderFactory;
import com.apimarketplace.agent.loop.AgentLoopContext;
import com.apimarketplace.agent.provider.LLMProvider;
import com.apimarketplace.common.credit.CreditConsumptionClient;
import com.apimarketplace.conversation.controller.v3.chat.ChatRequestConfigMapper;
import com.apimarketplace.conversation.dto.ChatRequest;
import com.apimarketplace.conversation.service.ConversationHistoryService;
import com.apimarketplace.conversation.service.ai.ChatStreamingService;
import com.apimarketplace.conversation.streaming.StreamingOutput;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.*;

/**
 * Chat controller for CE monolith mode.
 * Replaces the reactive ChatControllerV3 (excluded from monolith).
 *
 * Uses the SAME ChatStreamingService as cloud mode - the only difference is:
 * - StreamStateService -> Redis-backed stream state
 * - StreamingOutput -> MonolithStreamingOutput (Redis Pub/Sub to WS)
 *
 * This ensures identical agent execution, tool calling, and message persistence.
 */
@Slf4j
@RestController
@RequestMapping("/api/v3/chat")
@RequiredArgsConstructor
@ConditionalOnProperty(name = "deployment.mode", havingValue = "monolith")
public class MonolithChatController {

    private final ConversationHistoryService conversationHistoryService;
    private final ChatStreamingService chatStreamingService;
    private final CreditConsumptionClient creditClient;
    private final LLMProviderFactory llmProviderFactory;
    private final org.springframework.data.redis.core.StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    @Autowired(required = false)
    private RuntimeLlmProviderResolver providerResolver;

    @PostMapping
    public ResponseEntity<Map<String, String>> chat(
            @RequestBody ChatRequest request,
            @RequestHeader(value = "X-User-ID") String userId,
            @RequestHeader(value = "X-Organization-ID", required = false) String orgId,
            @RequestHeader(value = "X-Organization-Role", required = false) String orgRole,
            @RequestHeader(value = "X-User-Roles", required = false) String userRoles) {

        request.setUserId(userId);
        request.setOrgId(orgId);
        request.setOrgRole(orgRole);
        // Platform role set - threaded the same way as cloud ChatControllerV3 so
        // service-layer tool modules (e.g. AgentHelpModule's admin-only CLI-bridge
        // gating) resolve admin status. Without it, CE would hide bridge models
        // from the admin operator too.
        request.setUserRoles(userRoles);

        DefaultModel defaults = resolveDefaultModel(userId);
        String model = request.getModel() != null ? request.getModel() : defaults.model();
        String provider = request.getProvider() != null ? request.getProvider() : defaults.provider();
        request.setModel(model);
        request.setProvider(provider);

        log.info("[MonolithChat] User: {}, Message: {}chars, Conv: {}, Model: {}/{}",
                userId,
                request.getMessage() != null ? request.getMessage().length() : 0,
                request.getConversationId(), provider, model);

        // Source-type-scoped gate (cloud ChatControllerV3 parity): chat draws the
        // PAYG bucket alone on the FREE plan. No-op in CE unlimited mode.
        if (!creditClient.checkCredits(userId,
                com.apimarketplace.common.credit.CreditConsumptionClient.SOURCE_TYPE_CHAT_CONVERSATION)) {
            return ResponseEntity.status(HttpStatus.PAYMENT_REQUIRED)
                    .body(Map.of("error", "Insufficient credits"));
        }

        // Get or create conversation (same as cloud ChatStreamInitializer)
        String conversationId = request.getConversationId();
        if (conversationId == null || conversationId.isEmpty()) {
            // 2026-06-11 - thread the request's chatConfig into the create, exactly
            // like the cloud path. Pre-fix the CE create dropped it, so the
            // per-(user, workspace) defaults (V312) and the composer's initial
            // skill selection never reached conversation.chat_config on CE.
            conversationId = conversationHistoryService.createConversation(
                    userId, orgId, "Generating Title...", model, provider, request.getAgentId(),
                    ChatRequestConfigMapper.initialChatConfig(request));
            if (conversationId == null || conversationId.isBlank()) {
                log.error("[MonolithChat] Failed to create conversation for user: {} (org: {})", userId, orgId);
                return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                        .body(Map.of("error", "Failed to create conversation"));
            }
            request.setConversationId(conversationId);
            log.info("[MonolithChat] Created conversation: {}", conversationId);
        } else if (request.isDefaultSkillIdsProvided()) {
            // Existing conversation + explicit composer skill selection - persist it
            // into chatConfig.defaultSkillIds (cloud parity: ChatStreamInitializer
            // does the same so a reload keeps the per-conversation selection).
            conversationHistoryService.persistDefaultSkillIds(
                    conversationId, userId, orgId,
                    request.getDefaultSkillIds() != null ? request.getDefaultSkillIds() : List.of());
        }

        String streamId = "stream_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);

        // Create streaming output that publishes via Redis Pub/Sub to WS.
        StreamingOutput streamOutput = new MonolithStreamingOutput(
                streamId, conversationId, model, provider, redisTemplate, objectMapper);

        // Publish stream_started
        publishEvent(conversationId, Map.of(
                "streamId", streamId, "conversationId", conversationId,
                "model", model, "timestamp", Instant.now().toString()
        ), "stream_started");

        // Start processing in virtual thread (same as cloud ChatStreamInitializer.startAsyncProcessing)
        final String convId = conversationId;
        Thread.ofVirtual().name("chat-" + streamId).start(() -> {
            try {
                chatStreamingService.processStreamingRequest(request, streamOutput, streamId);
            } catch (Exception e) {
                log.error("[MonolithChat] Streaming error: {}", e.getMessage(), e);
                streamOutput.sendError(e.getMessage());
            }
        });

        return ResponseEntity.ok(Map.of(
                "conversationId", conversationId,
                "streamId", streamId,
                "model", model
        ));
    }

    @PostMapping("/stop")
    public ResponseEntity<Map<String, Object>> stopStream(
            @RequestHeader(value = "X-User-ID") String userId,
            @RequestBody Map<String, String> body) {
        String conversationId = body.get("conversationId");
        if (conversationId == null) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", "Conversation ID required"));
        }
        log.info("[MonolithChat] Stop requested for conversation: {}", conversationId);
        return ResponseEntity.ok(Map.of("success", true, "message", "Stop signal sent"));
    }

    private void publishEvent(String conversationId, Map<String, Object> event, String type) {
        try {
            var fullEvent = new LinkedHashMap<>(event);
            fullEvent.putIfAbsent("type", type);
            String json = objectMapper.writeValueAsString(fullEvent);
            redisTemplate.convertAndSend("ws:conversation:" + conversationId, json);
        } catch (Exception e) {
            log.warn("[MonolithChat] Failed to publish {}: {}", type, e.getMessage());
        }
    }

    private DefaultModel resolveDefaultModel(String userId) {
        if (providerResolver != null) {
            String providerName = providerResolver.resolveDefaultProviderName(userId);
            if (providerName != null && !providerName.isBlank()) {
                LLMProvider provider = providerResolver.resolve(providerName,
                        AgentLoopContext.builder()
                                .tenantId(userId)
                                .provider(providerName)
                                .build());
                return new DefaultModel(provider.getProviderName(), provider.getDefaultModel());
            }
        }
        Map<String, Object> modelsInfo = llmProviderFactory.getAllModelsInfo();
        return new DefaultModel(
                (String) modelsInfo.get("defaultProvider"),
                (String) modelsInfo.get("defaultModel"));
    }

    private record DefaultModel(String provider, String model) {
    }
}
