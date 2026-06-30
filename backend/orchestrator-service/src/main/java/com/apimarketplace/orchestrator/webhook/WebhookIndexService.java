package com.apimarketplace.orchestrator.webhook;

import com.apimarketplace.orchestrator.domain.WorkflowEntity;
import com.apimarketplace.orchestrator.repository.WorkflowRepository;
import com.apimarketplace.trigger.client.TriggerClient;
import com.apimarketplace.trigger.client.dto.WebhookTokenDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Service for managing the Redis webhook index.
 * Provides O(1) lookup from webhook token to workflow/trigger.
 *
 * Storage (Multi-DAG):
 * - Source of truth: webhook_tokens table in trigger-service
 * - Index: Redis hash at webhook:{token}
 *
 * Note: This service is only active when Redis is available.
 * Bean creation is handled by WebhookConfiguration.
 */
public class WebhookIndexService {

    private static final Logger logger = LoggerFactory.getLogger(WebhookIndexService.class);

    private final StringRedisTemplate redisTemplate;
    private final WorkflowRepository workflowRepository;
    private final TriggerClient triggerClient;

    public WebhookIndexService(StringRedisTemplate redisTemplate,
                               WorkflowRepository workflowRepository,
                               TriggerClient triggerClient) {
        this.redisTemplate = redisTemplate;
        this.workflowRepository = workflowRepository;
        this.triggerClient = triggerClient;
    }

    /**
     * Index a webhook token in Redis.
     */
    public void index(String token, WebhookTarget target) {
        if (token == null || token.isBlank()) {
            logger.warn("Cannot index webhook with null/blank token");
            return;
        }

        String key = WebhookTarget.redisKey(token);
        redisTemplate.opsForHash().putAll(key, Map.of(
            "workflowId", target.workflowId(),
            "triggerId", target.triggerId() != null ? target.triggerId() : "",
            "tenantId", target.tenantId() != null ? target.tenantId() : ""
        ));

        logger.info("Indexed webhook token {} for workflow {} trigger {}",
                   token.substring(0, Math.min(8, token.length())) + "...",
                   target.workflowId(),
                   target.triggerId());
    }

    /**
     * Lookup a webhook target by token.
     */
    public Optional<WebhookTarget> lookup(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }

        String key = WebhookTarget.redisKey(token);
        Map<Object, Object> data = redisTemplate.opsForHash().entries(key);

        if (data.isEmpty()) {
            logger.debug("Webhook token not found in index: {}",
                        token.substring(0, Math.min(8, token.length())) + "...");
            return Optional.empty();
        }

        return Optional.of(new WebhookTarget(
            (String) data.get("workflowId"),
            (String) data.get("triggerId"),
            (String) data.get("tenantId")
        ));
    }

    /**
     * Remove a webhook token from the index.
     */
    public void remove(String token) {
        if (token == null || token.isBlank()) {
            return;
        }

        String key = WebhookTarget.redisKey(token);
        Boolean deleted = redisTemplate.delete(key);

        if (Boolean.TRUE.equals(deleted)) {
            logger.info("Removed webhook token from index: {}",
                       token.substring(0, Math.min(8, token.length())) + "...");
        }
    }

    /**
     * Remove all webhook tokens for a workflow.
     */
    public void removeAllForWorkflow(String workflowId) {
        try {
            Map<String, String> tokens = triggerClient.getTokensForWorkflow(UUID.fromString(workflowId));
            for (String token : tokens.values()) {
                remove(token);
            }
        } catch (Exception e) {
            logger.warn("Error removing webhook tokens from index for workflow {}: {}",
                       workflowId, e.getMessage());
        }
    }

    /**
     * Sync webhook index for a workflow.
     * Called after workflow save to update Redis index.
     * Uses the webhook_tokens table (via trigger-service) as source of truth.
     */
    public void syncForWorkflow(WorkflowEntity workflow) {
        String workflowId = workflow.getId().toString();
        String tenantId = workflow.getTenantId();

        Map<String, String> tokens = triggerClient.getTokensForWorkflow(workflow.getId());
        for (Map.Entry<String, String> entry : tokens.entrySet()) {
            WebhookTarget target = new WebhookTarget(
                workflowId,
                entry.getKey(),
                tenantId
            );
            index(entry.getValue(), target);
        }
    }

    /**
     * Rebuild the entire webhook index from database.
     * Called on application startup.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void rebuildIndex() {
        logger.info("Rebuilding webhook index from database...");

        int indexed = 0;

        try {
            // Get all workflows and rebuild index for each
            List<WorkflowEntity> allWorkflows = workflowRepository.findAll();
            for (WorkflowEntity workflow : allWorkflows) {
                Map<String, String> tokens = triggerClient.getTokensForWorkflow(workflow.getId());
                for (Map.Entry<String, String> entry : tokens.entrySet()) {
                    WebhookTarget target = new WebhookTarget(
                        workflow.getId().toString(),
                        entry.getKey(),
                        workflow.getTenantId()
                    );
                    index(entry.getValue(), target);
                    indexed++;
                }
            }
        } catch (Exception e) {
            logger.warn("Could not rebuild webhook index: {}", e.getMessage());
        }

        logger.info("Webhook index rebuild complete. Indexed {} webhook(s)", indexed);
    }
}
