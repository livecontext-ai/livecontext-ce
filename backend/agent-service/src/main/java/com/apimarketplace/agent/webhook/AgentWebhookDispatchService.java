package com.apimarketplace.agent.webhook;

import com.apimarketplace.agent.domain.AgentEntity;
import com.apimarketplace.agent.domain.AgentWebhookTokenEntity;
import com.apimarketplace.agent.repository.AgentRepository;
import com.apimarketplace.conversation.client.ConversationClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Service for dispatching incoming agent webhook calls.
 * Uses ConversationClient (shared) for all conversation operations.
 */
@Service
public class AgentWebhookDispatchService {

    private static final Logger logger = LoggerFactory.getLogger(AgentWebhookDispatchService.class);

    private final AgentWebhookTokenService tokenService;
    private final AgentRepository agentRepository;
    private final ConversationClient conversationClient;
    private final ObjectMapper objectMapper;

    public AgentWebhookDispatchService(
            AgentWebhookTokenService tokenService,
            AgentRepository agentRepository,
            ConversationClient conversationClient,
            ObjectMapper objectMapper) {
        this.tokenService = tokenService;
        this.agentRepository = agentRepository;
        this.conversationClient = conversationClient;
        this.objectMapper = objectMapper;
    }

    /**
     * Get webhook configuration by token.
     */
    public AgentWebhookConfig getWebhookConfigByToken(String token) {
        Optional<AgentWebhookTokenEntity> tokenEntityOpt = tokenService.findByToken(token);
        if (tokenEntityOpt.isEmpty()) {
            return null;
        }

        AgentWebhookTokenEntity entity = tokenEntityOpt.get();
        return AgentWebhookConfig.fromAuthConfig(
            entity.getHttpMethod(),
            entity.getAuthType(),
            entity.getAuthConfig()
        );
    }

    /**
     * Dispatch a webhook call to the agent.
     */
    public AgentWebhookResponse dispatch(String token, Map<String, Object> payload, boolean sync) {
        String tokenPreview = token != null ? token.substring(0, Math.min(12, token.length())) + "..." : "null";

        // 1. Find active token
        Optional<AgentWebhookTokenEntity> tokenEntityOpt = tokenService.findActiveByToken(token);
        if (tokenEntityOpt.isEmpty()) {
            Optional<AgentWebhookTokenEntity> inactiveToken = tokenService.findByToken(token);
            if (inactiveToken.isPresent() && !Boolean.TRUE.equals(inactiveToken.get().getIsActive())) {
                logger.info("Agent webhook token is inactive: {}", tokenPreview);
                return AgentWebhookResponse.inactive();
            }
            logger.warn("Agent webhook token not found: {}", tokenPreview);
            return AgentWebhookResponse.notFound();
        }

        AgentWebhookTokenEntity tokenEntity = tokenEntityOpt.get();
        UUID agentId = tokenEntity.getAgentId();

        // 2. Get the agent
        Optional<AgentEntity> agentOpt = agentRepository.findById(agentId);
        if (agentOpt.isEmpty()) {
            logger.warn("Agent not found for webhook token: {}, agentId: {}", tokenPreview, agentId);
            return AgentWebhookResponse.error("Agent not found");
        }

        AgentEntity agent = agentOpt.get();
        if (!Boolean.TRUE.equals(agent.getIsActive())) {
            logger.info("Agent is inactive: {}", agentId);
            return AgentWebhookResponse.error("Agent is inactive");
        }

        String agentName = agent.getName();
        String tenantId = agent.getTenantId();
        String organizationId = agent.getOrganizationId();
        String model = agent.getModelName();
        String provider = agent.getModelProvider();

        logger.info("Dispatching webhook to agent: {} ({}), token: {}", agentName, agentId, tokenPreview);

        try {
            // 3. Resolve conversation
            boolean memoryEnabled = Boolean.TRUE.equals(tokenEntity.getMemoryEnabled());
            String conversationId;
            if (memoryEnabled) {
                // Same pattern as AgentNode.ensureConversation - find or create the agent's linked conversation
                conversationId = conversationClient.findOrCreateAgentConversation(
                        agentId.toString(), tenantId, agentName, organizationId);
            } else {
                // Fresh isolated conversation per webhook call
                conversationId = conversationClient.createConversation(
                        tenantId,
                        "Webhook: " + agentName + " - " + Instant.now(),
                        model, provider, agentId.toString(), false, organizationId);
            }

            if (conversationId == null) {
                logger.error("Failed to create conversation for agent webhook");
                return AgentWebhookResponse.error("Failed to create conversation");
            }

            // 4. Execute synchronously - blocks until agent completes
            String message = formatPayloadAsMessage(payload);
            Map<String, Object> result = conversationClient.sendChatSync(
                    tenantId, conversationId, message,
                    agentId.toString(), model, provider, "WEBHOOK", null, organizationId);

            boolean success = Boolean.TRUE.equals(result.get("success"));
            String content = (String) result.getOrDefault("content", "");
            String error = (String) result.get("error");

            if (success) {
                logger.info("Webhook agent {} responded, length: {}", agentId, content.length());
                return AgentWebhookResponse.success(conversationId, agentId.toString(), agentName, content);
            } else {
                logger.error("Webhook agent {} failed: {}", agentId, error);
                return AgentWebhookResponse.error(error != null ? error : "Agent execution failed");
            }

        } catch (Exception e) {
            logger.error("Error dispatching agent webhook: {}", e.getMessage(), e);
            return AgentWebhookResponse.error("Error processing webhook: " + e.getMessage());
        }
    }

    /**
     * Format the webhook payload as a user message.
     */
    private String formatPayloadAsMessage(Map<String, Object> payload) {
        if (payload == null || payload.isEmpty()) {
            return "[Webhook triggered with no payload]";
        }

        Map<String, Object> cleanPayload = new HashMap<>(payload);
        cleanPayload.remove("_webhookMethod");
        cleanPayload.remove("_webhookTimestamp");

        if (cleanPayload.containsKey("message")) {
            Object msg = cleanPayload.get("message");
            if (msg instanceof String) {
                return (String) msg;
            }
        }

        try {
            String json = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(cleanPayload);
            return "Webhook payload received:\n```json\n" + json + "\n```";
        } catch (Exception e) {
            return "Webhook payload: " + cleanPayload.toString();
        }
    }
}
