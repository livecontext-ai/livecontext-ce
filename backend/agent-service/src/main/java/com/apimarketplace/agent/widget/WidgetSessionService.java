package com.apimarketplace.agent.widget;

import com.apimarketplace.agent.domain.AgentEntity;
import com.apimarketplace.conversation.client.ConversationClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Service for managing anonymous widget chat sessions.
 *
 * <p>Sessions are stored in Redis with a 24h TTL. A session is an opaque token
 * (IP-bound) pointing at the agent's single conversation - it is NOT a conversation
 * of its own.
 *
 * <p><b>One agent = one conversation.</b> Widget sessions must never create a
 * second conversation for an agent. All widget visitors (now and forever) share
 * the same underlying {@code conversationId}, resolved via
 * {@link ConversationClient#findOrCreateAgentConversation}. See
 * {@link #createSession} for details.
 */
@Service
public class WidgetSessionService {

    private static final Logger logger = LoggerFactory.getLogger(WidgetSessionService.class);

    private static final String SESSION_KEY_PREFIX = "widget:session:";
    private static final Duration SESSION_TTL = Duration.ofHours(24);

    private final RedisTemplate<String, Object> redisTemplate;
    private final ConversationClient conversationClient;

    public WidgetSessionService(
            RedisTemplate<String, Object> redisTemplate,
            ConversationClient conversationClient) {
        this.redisTemplate = redisTemplate;
        this.conversationClient = conversationClient;
    }

    /**
     * Create a new widget session for an agent.
     *
     * <p><b>Contract: one agent = one conversation.</b> Widget sessions never create
     * a second conversation for an agent. All visitors of a given widget share the
     * same underlying conversation (identified by {@code agentId}); only the opaque
     * session token (IP-bound, 24h TTL) differs between visitors.
     *
     * <p>Implementation uses {@link ConversationClient#findOrCreateAgentConversation}
     * which first looks up the existing agent conversation via
     * {@code GET /api/conversations/agent/{agentId}} and only falls through to
     * creation on the first call ever made for this agent. Subsequent widget session
     * creations - whether from the same browser, a different browser, or a different
     * visitor entirely - reuse the exact same {@code conversationId}.
     */
    public WidgetSession createSession(AgentEntity agent, String ipAddress, String userAgent) {
        String tenantId = agent.getTenantId();
        String organizationId = agent.getOrganizationId();
        UUID agentId = agent.getId();

        // One agent = one conversation: find-or-create, never a blind create.
        String conversationId = conversationClient.findOrCreateAgentConversation(
                agentId.toString(), tenantId, agent.getName(), organizationId);

        if (conversationId == null) {
            throw new RuntimeException("Failed to resolve conversation for widget session (agent=" + agentId + ")");
        }

        String sessionId = UUID.randomUUID().toString();
        WidgetSession session = new WidgetSession(
            sessionId,
            conversationId,
            agentId,
            tenantId,
            organizationId,
            ipAddress,
            userAgent,
            Instant.now()
        );

        // Store in Redis with TTL
        String key = SESSION_KEY_PREFIX + sessionId;
        Map<String, String> sessionData = new HashMap<>();
        sessionData.put("sessionId", session.sessionId());
        sessionData.put("conversationId", session.conversationId());
        sessionData.put("agentId", session.agentId().toString());
        sessionData.put("tenantId", session.tenantId());
        if (session.organizationId() != null) {
            sessionData.put("organizationId", session.organizationId());
        }
        sessionData.put("ipAddress", session.ipAddress() != null ? session.ipAddress() : "");
        sessionData.put("userAgent", session.userAgent() != null ? session.userAgent() : "");
        sessionData.put("createdAt", session.createdAt().toString());

        redisTemplate.opsForHash().putAll(key, sessionData);
        redisTemplate.expire(key, SESSION_TTL);

        logger.info("Created widget session {} for agent {} with conversation {}", sessionId, agentId, conversationId);
        return session;
    }

    /**
     * Get a session from Redis.
     */
    public WidgetSession getSession(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return null;
        }

        String key = SESSION_KEY_PREFIX + sessionId;
        Map<Object, Object> data = redisTemplate.opsForHash().entries(key);
        if (data == null || data.isEmpty()) {
            return null;
        }

        return new WidgetSession(
            (String) data.get("sessionId"),
            (String) data.get("conversationId"),
            UUID.fromString((String) data.get("agentId")),
            (String) data.get("tenantId"),
            (String) data.get("organizationId"),
            (String) data.get("ipAddress"),
            (String) data.get("userAgent"),
            Instant.parse((String) data.get("createdAt"))
        );
    }

    /**
     * Validate session: exists and IP is consistent.
     */
    public boolean validateSession(String sessionId, String ipAddress) {
        WidgetSession session = getSession(sessionId);
        if (session == null) {
            return false;
        }

        // Refresh TTL on valid access
        refreshSession(sessionId);

        // IP validation (lenient: skip if session has no IP or request has no IP)
        if (session.ipAddress() != null && !session.ipAddress().isEmpty()
                && ipAddress != null && !ipAddress.isEmpty()) {
            return session.ipAddress().equals(ipAddress);
        }

        return true;
    }

    /**
     * Refresh session TTL.
     */
    public void refreshSession(String sessionId) {
        String key = SESSION_KEY_PREFIX + sessionId;
        redisTemplate.expire(key, SESSION_TTL);
    }

    /**
     * Send a message synchronously via ConversationClient.
     * Returns {success, content, error, conversationId}.
     */
    public Map<String, Object> sendMessage(String tenantId, String conversationId, String message, AgentEntity agent) {
        String model = agent.getModelName();
        String provider = agent.getModelProvider();
        String organizationId = agent.getOrganizationId();

        return conversationClient.sendChatSync(
                tenantId, conversationId, message,
                agent.getId().toString(), model, provider, "WIDGET", null, organizationId);
    }

    /**
     * Get conversation history via ConversationClient.
     */
    public List<Map<String, Object>> getHistory(String tenantId, String conversationId) {
        return getHistory(tenantId, conversationId, null);
    }

    public List<Map<String, Object>> getHistory(String tenantId, String conversationId, String organizationId) {
        return conversationClient.getConversationMessages(conversationId, 100, tenantId, organizationId);
    }

    /**
     * Widget session record.
     */
    public record WidgetSession(
        String sessionId,
        String conversationId,
        UUID agentId,
        String tenantId,
        String organizationId,
        String ipAddress,
        String userAgent,
        Instant createdAt
    ) {}
}
