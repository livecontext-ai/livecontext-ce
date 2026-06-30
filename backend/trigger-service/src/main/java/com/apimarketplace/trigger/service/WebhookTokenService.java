package com.apimarketplace.trigger.service;

import com.apimarketplace.common.scope.ScopeGuard;
import com.apimarketplace.common.web.TenantResolver;
import com.apimarketplace.trigger.domain.WebhookTokenEntity;
import com.apimarketplace.trigger.repository.WebhookTokenRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Service for generating and managing webhook tokens.
 * Supports multi-DAG webhooks where each trigger has its own token.
 *
 * <p>Post-V261: every token row carries a non-null {@code organization_id}
 * (and non-null {@code tenant_id} since V253). The back-compat overloads
 * that defaulted org/tenant to null were removed in the V261 sweep.
 */
@Service
public class WebhookTokenService {

    private static final Logger logger = LoggerFactory.getLogger(WebhookTokenService.class);

    private static final String TOKEN_PREFIX = "wh_";
    private static final int TOKEN_LENGTH = 32;
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final WebhookTokenRepository webhookTokenRepository;

    public WebhookTokenService(WebhookTokenRepository webhookTokenRepository) {
        this.webhookTokenRepository = webhookTokenRepository;
    }

    public String generateToken() {
        byte[] bytes = new byte[TOKEN_LENGTH / 2];
        SECURE_RANDOM.nextBytes(bytes);
        StringBuilder hex = new StringBuilder(TOKEN_PREFIX);
        for (byte b : bytes) {
            hex.append(String.format("%02x", b));
        }
        return hex.toString();
    }

    @Transactional
    public WebhookTokenEntity ensureTokenForTrigger(UUID workflowId, String triggerId,
                                                     String organizationId, String tenantId) {
        TenantResolver.requireOrgId(organizationId);
        // Use list query to gracefully handle pre-migration duplicates
        List<WebhookTokenEntity> existing = webhookTokenRepository
                .findAllByWorkflowIdAndTriggerId(workflowId, triggerId);
        if (!existing.isEmpty()) {
            if (existing.size() > 1) {
                logger.warn("[WebhookToken] Cleaning up {} duplicate(s) for workflow={} trigger={}",
                        existing.size() - 1, workflowId, triggerId);
                webhookTokenRepository.deleteAll(existing.subList(1, existing.size()));
            }
            return existing.get(0);
        }
        String token = generateToken();
        WebhookTokenEntity entity = new WebhookTokenEntity(workflowId, triggerId, token);
        entity.setOrganizationId(organizationId);
        // V253 - owner tenant captured at create time for the strict-isolation
        // check in deleteTokensForWorkflowScoped.
        entity.setTenantId(
                (tenantId != null && !tenantId.isBlank()) ? tenantId : null);
        webhookTokenRepository.save(entity);
        logger.info("Generated webhook token for workflow {} trigger {} org={} tenant={}: {}...",
                workflowId, triggerId, entity.getOrganizationId(), entity.getTenantId(),
                token.substring(0, Math.min(12, token.length())));
        return entity;
    }

    public Map<String, String> getTokensForWorkflow(UUID workflowId) {
        return webhookTokenRepository.findByWorkflowId(workflowId).stream()
            .collect(Collectors.toMap(
                WebhookTokenEntity::getTriggerId,
                WebhookTokenEntity::getToken
            ));
    }

    @Transactional
    public WebhookTokenEntity regenerateTokenForTrigger(UUID workflowId, String triggerId,
                                                        String organizationId, String tenantId) {
        TenantResolver.requireOrgId(organizationId);
        // Use list query to gracefully handle pre-migration duplicates
        List<WebhookTokenEntity> existing = webhookTokenRepository
                .findAllByWorkflowIdAndTriggerId(workflowId, triggerId);
        WebhookTokenEntity entity;
        if (existing.isEmpty()) {
            entity = new WebhookTokenEntity();
            entity.setWorkflowId(workflowId);
            entity.setTriggerId(triggerId);
            entity.setCreatedAt(Instant.now());
            entity.setOrganizationId(organizationId);
            // V253 - stamp tenant on brand-new row so strict-isolation
            // delete can match it later.
            if (tenantId != null && !tenantId.isBlank()) {
                entity.setTenantId(tenantId);
            }
        } else {
            entity = existing.get(0);
            if (existing.size() > 1) {
                logger.warn("[WebhookToken] Cleaning up {} duplicate(s) for workflow={} trigger={}",
                        existing.size() - 1, workflowId, triggerId);
                webhookTokenRepository.deleteAll(existing.subList(1, existing.size()));
            }
        }

        String newToken = generateToken();
        entity.setToken(newToken);
        entity.setUpdatedAt(Instant.now());
        webhookTokenRepository.save(entity);

        logger.info("Regenerated webhook token for workflow {} trigger {} org={}",
                workflowId, triggerId, entity.getOrganizationId());
        return entity;
    }

    public Optional<WebhookTokenEntity> findByToken(String token) {
        if (token == null || token.isBlank()) return Optional.empty();
        return webhookTokenRepository.findByToken(token);
    }

    @Transactional
    public void deleteTokensForWorkflow(UUID workflowId) {
        webhookTokenRepository.deleteByWorkflowId(workflowId);
        logger.info("Deleted all webhook tokens for workflow {}", workflowId);
    }

    /**
     * Strict-isolation tenant+org scope check on each token row before
     * delete. V253 (2026-05-18) added {@code tenant_id} to
     * {@code webhook_tokens} (backfilled from parent workflow), closing the
     * prior {@code @TolerantScope} schema-gap exception. A caller in OrgA
     * cannot delete their OrgB webhook tokens via this path.
     *
     * <p>Legacy rows with NULL tenant_id (orphaned when their parent
     * workflow was deleted before V253) remain untouched by any caller.
     */
    @Transactional
    public int deleteTokensForWorkflowScoped(UUID workflowId, String tenantId, String orgId) {
        List<WebhookTokenEntity> all = webhookTokenRepository.findByWorkflowId(workflowId);
        if (all.isEmpty()) return 0;
        int deleted = 0;
        for (WebhookTokenEntity t : all) {
            if (ScopeGuard.isInStrictScope(tenantId, orgId,
                    t.getTenantId(), t.getOrganizationId())) {
                webhookTokenRepository.delete(t);
                deleted++;
            }
        }
        if (deleted < all.size()) {
            logger.warn("[SCOPE] Token cleanup for workflow {} matched only {}/{} rows for caller tenantId={}, orgId={}",
                    workflowId, deleted, all.size(), tenantId, orgId);
        } else if (deleted > 0) {
            logger.info("[SCOPE] Deleted {} webhook tokens for workflow {} (tenantId={}, orgId={})",
                    workflowId, deleted, tenantId, orgId);
        }
        return deleted;
    }

    @Transactional
    public void cleanupOrphanTokens(UUID workflowId, List<String> currentTriggerIds) {
        if (currentTriggerIds == null || currentTriggerIds.isEmpty()) {
            webhookTokenRepository.deleteByWorkflowId(workflowId);
        } else {
            webhookTokenRepository.deleteByWorkflowIdAndTriggerIdNotIn(workflowId, currentTriggerIds);
        }
    }

    public boolean isValidTokenFormat(String token) {
        if (token == null || token.isBlank()) return false;
        if (!token.startsWith(TOKEN_PREFIX)) return false;
        String hexPart = token.substring(TOKEN_PREFIX.length());
        if (hexPart.length() != TOKEN_LENGTH) return false;
        return hexPart.chars().allMatch(c ->
            (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F'));
    }

    public String getWebhookUrl(String baseUrl, String token) {
        if (token == null || token.isBlank()) return null;
        if (baseUrl.endsWith("/")) baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        return baseUrl + "/webhook/" + token;
    }

    /**
     * Find which workflow IDs (from the given set) have at least one webhook token.
     */
    public Set<UUID> findWorkflowIdsWithTokens(Collection<UUID> workflowIds) {
        if (workflowIds == null || workflowIds.isEmpty()) return Collections.emptySet();
        return webhookTokenRepository.findWorkflowIdsWithTokens(workflowIds);
    }
}
