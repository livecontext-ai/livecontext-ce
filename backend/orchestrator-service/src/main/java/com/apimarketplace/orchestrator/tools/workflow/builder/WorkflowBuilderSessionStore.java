package com.apimarketplace.orchestrator.tools.workflow.builder;

import com.apimarketplace.orchestrator.cache.RedisCacheKeys;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Redis-based session store for workflow builder sessions.
 *
 * This is the SINGLE SOURCE OF TRUTH for workflow builder sessions.
 * No fallback to in-memory - if Redis is down, workflow builder is unavailable.
 *
 * Redis Key Structure:
 * - orchestrator:wb-session:{sessionId} -> WorkflowBuilderSession (JSON)
 * - orchestrator:wb-tenant:{tenantId}   -> Set of session IDs
 *
 * Benefits:
 * - Survives server restart
 * - Shared across cluster nodes
 * - Automatic TTL cleanup
 */
@Slf4j
@Component
public class WorkflowBuilderSessionStore {

    private final RedisTemplate<String, Object> redisTemplate;
    private final ObjectMapper objectMapper;

    @Value("${orchestrator.workflow-builder.session-ttl:PT30M}")
    private Duration sessionTtl;

    public WorkflowBuilderSessionStore(RedisTemplate<String, Object> redisTemplate, ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    /**
     * Save a session to Redis.
     * Sessions are indexed by both tenant (for listing) and conversation (for isolation).
     */
    public void save(WorkflowBuilderSession session) {
        session.touch();
        String sessionKey = RedisCacheKeys.workflowBuilderSession(session.getSessionId());
        String tenantKey = RedisCacheKeys.workflowBuilderTenantIndex(session.getTenantId());

        try {
            // Save session with TTL
            redisTemplate.opsForValue().set(sessionKey, session, sessionTtl);

            // Add to tenant index (Set) - for listing all tenant sessions
            redisTemplate.opsForSet().add(tenantKey, session.getSessionId());
            redisTemplate.expire(tenantKey, sessionTtl);

            // Add to conversation index (String) - for conversation isolation
            if (session.getConversationId() != null) {
                String convKey = RedisCacheKeys.workflowBuilderConversationIndex(
                    session.getTenantId(), session.getConversationId());
                redisTemplate.opsForValue().set(convKey, session.getSessionId(), sessionTtl);
                log.debug("Saved workflow builder session: sessionId={}, tenantId={}, conversationId={}",
                    session.getSessionId(), session.getTenantId(), session.getConversationId());
            } else {
                log.debug("Saved workflow builder session: sessionId={}, tenantId={} (no conversation)",
                    session.getSessionId(), session.getTenantId());
            }
        } catch (Exception e) {
            log.error("Failed to save session to Redis: sessionId={}", session.getSessionId(), e);
            throw new WorkflowBuilderSessionException("Failed to save session", e);
        }
    }

    /**
     * Get a session by ID.
     * Returns empty if session doesn't exist.
     */
    public Optional<WorkflowBuilderSession> get(String sessionId) {
        String key = RedisCacheKeys.workflowBuilderSession(sessionId);
        try {
            Object value = redisTemplate.opsForValue().get(key);
            if (value == null) {
                return Optional.empty();
            }

            // Handle deserialization - value might be a LinkedHashMap from JSON
            WorkflowBuilderSession session = objectMapper.convertValue(value, WorkflowBuilderSession.class);
            log.debug("Loaded workflow builder session from Redis: sessionId={}", sessionId);
            return Optional.of(session);
        } catch (Exception e) {
            log.error("Failed to load session from Redis: sessionId={}", sessionId, e);
            return Optional.empty();
        }
    }

    /**
     * Delete a session.
     */
    public void delete(String sessionId) {
        try {
            // Get session first to know the tenant and conversation
            Optional<WorkflowBuilderSession> session = get(sessionId);

            // Delete session
            String sessionKey = RedisCacheKeys.workflowBuilderSession(sessionId);
            redisTemplate.delete(sessionKey);

            // Remove from indexes
            if (session.isPresent()) {
                WorkflowBuilderSession s = session.get();

                // Remove from tenant index
                String tenantKey = RedisCacheKeys.workflowBuilderTenantIndex(s.getTenantId());
                redisTemplate.opsForSet().remove(tenantKey, sessionId);

                // Remove from conversation index
                if (s.getConversationId() != null) {
                    String convKey = RedisCacheKeys.workflowBuilderConversationIndex(
                        s.getTenantId(), s.getConversationId());
                    redisTemplate.delete(convKey);
                }
            }

            log.debug("Deleted workflow builder session: sessionId={}", sessionId);
        } catch (Exception e) {
            log.error("Failed to delete session from Redis: sessionId={}", sessionId, e);
        }
    }

    /**
     * Get all active sessions for a tenant.
     */
    public List<WorkflowBuilderSession> getSessionsForTenant(String tenantId) {
        String tenantKey = RedisCacheKeys.workflowBuilderTenantIndex(tenantId);
        try {
            Set<Object> sessionIds = redisTemplate.opsForSet().members(tenantKey);
            if (sessionIds == null || sessionIds.isEmpty()) {
                return List.of();
            }

            return sessionIds.stream()
                .map(id -> get((String) id))
                .filter(Optional::isPresent)
                .map(Optional::get)
                .toList();
        } catch (Exception e) {
            log.error("Failed to get sessions for tenant: tenantId={}", tenantId, e);
            return List.of();
        }
    }

    /**
     * Get single active session for a tenant (if only one exists).
     * Returns Optional.empty() if 0 or more than 1 session exists.
     * @deprecated Use getSessionForConversation for proper isolation
     */
    @Deprecated
    public Optional<WorkflowBuilderSession> getSingleSessionForTenant(String tenantId) {
        List<WorkflowBuilderSession> sessions = getSessionsForTenant(tenantId);
        if (sessions.size() == 1) {
            return Optional.of(sessions.get(0));
        }
        return Optional.empty();
    }

    /**
     * Get active session for a specific conversation.
     * This is the primary method for session lookup - provides conversation isolation.
     *
     * @param tenantId The tenant ID
     * @param conversationId The conversation ID
     * @return The session for this conversation, or empty if none exists
     */
    public Optional<WorkflowBuilderSession> getSessionForConversation(String tenantId, String conversationId) {
        if (conversationId == null || conversationId.isBlank()) {
            log.debug("No conversationId provided, falling back to tenant lookup");
            return getSingleSessionForTenant(tenantId);
        }

        String convKey = RedisCacheKeys.workflowBuilderConversationIndex(tenantId, conversationId);
        try {
            Object sessionIdObj = redisTemplate.opsForValue().get(convKey);
            if (sessionIdObj == null) {
                log.debug("No session found for conversation: tenantId={}, conversationId={}", tenantId, conversationId);
                return Optional.empty();
            }

            String sessionId = (String) sessionIdObj;
            return get(sessionId);
        } catch (Exception e) {
            log.error("Failed to get session for conversation: tenantId={}, conversationId={}", tenantId, conversationId, e);
            return Optional.empty();
        }
    }

    /**
     * Check if a conversation has an active session.
     */
    public boolean hasSessionForConversation(String tenantId, String conversationId) {
        if (conversationId == null || conversationId.isBlank()) {
            return hasActiveSession(tenantId);
        }

        String convKey = RedisCacheKeys.workflowBuilderConversationIndex(tenantId, conversationId);
        try {
            return Boolean.TRUE.equals(redisTemplate.hasKey(convKey));
        } catch (Exception e) {
            log.error("Failed to check session for conversation: tenantId={}, conversationId={}", tenantId, conversationId, e);
            return false;
        }
    }

    /**
     * Check if tenant has any active sessions.
     */
    public boolean hasActiveSession(String tenantId) {
        String tenantKey = RedisCacheKeys.workflowBuilderTenantIndex(tenantId);
        try {
            Long size = redisTemplate.opsForSet().size(tenantKey);
            return size != null && size > 0;
        } catch (Exception e) {
            log.error("Failed to check active sessions: tenantId={}", tenantId, e);
            return false;
        }
    }

    /**
     * Discard all sessions for a tenant.
     * @return number of sessions discarded
     */
    public int discardAllForTenant(String tenantId) {
        List<WorkflowBuilderSession> sessions = getSessionsForTenant(tenantId);
        for (WorkflowBuilderSession session : sessions) {
            delete(session.getSessionId());
        }
        log.info("Discarded {} sessions for tenant: {}", sessions.size(), tenantId);
        return sessions.size();
    }

    /**
     * Get active session count (across all tenants).
     */
    public int getActiveSessionCount() {
        try {
            Set<String> keys = redisTemplate.keys(RedisCacheKeys.workflowBuilderSessionPattern());
            return keys != null ? keys.size() : 0;
        } catch (Exception e) {
            log.error("Failed to count active sessions", e);
            return 0;
        }
    }

    /**
     * Exception for Redis operations.
     */
    public static class WorkflowBuilderSessionException extends RuntimeException {
        public WorkflowBuilderSessionException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
