package com.apimarketplace.agent.service;

import com.apimarketplace.agent.domain.AgentTaskEntity;
import com.apimarketplace.agent.dto.TaskResponse;
import com.apimarketplace.common.event.EventBus;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * Publishes task board events to Redis for real-time WebSocket updates.
 * <p>
 * Channel: {@code ws:task:board:{tenantId}}
 * <p>
 * Events are picked up by the gateway WebSocket bridge and forwarded
 * to subscribed clients (the task board UI).
 * <p>
 * Event types:
 * <ul>
 *   <li>{@code task_created} - a new task was created</li>
 *   <li>{@code task_updated} - task fields changed (status, assignee, priority, etc.)</li>
 *   <li>{@code task_deleted} - task was hard-deleted</li>
 * </ul>
 * Each event carries the full {@link TaskResponse} (except delete, which carries only the ID),
 * so the frontend can patch its local state without re-fetching.
 */
@Service
public class TaskBoardPublisher {

    private static final Logger log = LoggerFactory.getLogger(TaskBoardPublisher.class);
    private static final String CHANNEL_PREFIX = "ws:task:board:";

    private final EventBus eventBus;
    private final ObjectMapper objectMapper;

    public TaskBoardPublisher(EventBus eventBus, ObjectMapper objectMapper) {
        this.eventBus = eventBus;
        this.objectMapper = objectMapper;
    }

    public void publishTaskCreated(String tenantId, AgentTaskEntity task) {
        Map<String, Object> payload = basePayload("task_created", tenantId, task.getOrganizationId());
        payload.put("task", TaskResponse.from(task));
        publish(tenantId, payload);
    }

    public void publishTaskUpdated(String tenantId, AgentTaskEntity task) {
        Map<String, Object> payload = basePayload("task_updated", tenantId, task.getOrganizationId());
        payload.put("task", TaskResponse.from(task));
        publish(tenantId, payload);
    }

    public void publishTaskDeleted(String tenantId, String taskId) {
        // Delete events have no entity to read organizationId from. Frontend
        // filters by tenantId only for deletes - acceptable since task IDs
        // are UUID-unique and deleting a non-visible row is a no-op client-side.
        Map<String, Object> payload = basePayload("task_deleted", tenantId, null);
        payload.put("taskId", taskId);
        publish(tenantId, payload);
    }

    /**
     * 2026-05-18 - adds {@code organizationId} to the WS payload so frontend
     * subscribers can filter cross-workspace events at the consumer level.
     * The channel itself stays keyed by tenantId (one socket per user) to
     * avoid re-keying the channel namespace which would force gateway
     * authorizer + frontend useChannel + Redis subscription rewiring.
     */
    private Map<String, Object> basePayload(String event, String tenantId, String organizationId) {
        Map<String, Object> payload = new HashMap<>(8);
        payload.put("event", event);
        payload.put("tenantId", tenantId);
        payload.put("organizationId", organizationId);
        payload.put("timestamp", Instant.now().toString());
        return payload;
    }

    private void publish(String tenantId, Map<String, Object> payload) {
        try {
            String channel = CHANNEL_PREFIX + tenantId;
            String json = objectMapper.writeValueAsString(payload);
            eventBus.publish(channel, json);
            log.debug("[TASK_BOARD] Published {} to {}", payload.get("event"), channel);
        } catch (Exception e) {
            log.warn("[TASK_BOARD] Failed to publish event (non-critical): {}", e.getMessage());
        }
    }
}
