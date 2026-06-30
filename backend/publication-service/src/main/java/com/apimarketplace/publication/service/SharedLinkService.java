package com.apimarketplace.publication.service;

import com.apimarketplace.publication.config.SharedLinkPlanLimits;
import com.apimarketplace.publication.domain.SharedLinkEntity;
import com.apimarketplace.publication.domain.SharedLinkEntity.ResourceType;
import com.apimarketplace.publication.dto.SharedLinkCheckResponse;
import com.apimarketplace.publication.dto.SharedLinkConfigResponse;
import com.apimarketplace.publication.dto.SharedLinkResponse;
import com.apimarketplace.publication.repository.SharedLinkRepository;
import com.apimarketplace.common.scope.ScopeGuard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
@Transactional
public class SharedLinkService {

    private static final Logger logger = LoggerFactory.getLogger(SharedLinkService.class);

    private final SharedLinkRepository repository;

    public SharedLinkService(SharedLinkRepository repository) {
        this.repository = repository;
    }

    /**
     * Register a shared link with quota enforcement (atomic, single transaction).
     * Idempotent: returns existing active link if resourceToken already registered
     * for the same organization. Handles race condition via catch on unique
     * constraint violation.
     *
     * @param organizationId nullable for backward-compat callers - when null the
     *                       idempotency + quota checks fall back to tenantId.
     *                       New code MUST pass a non-null orgId.
     */
    public SharedLinkEntity register(String tenantId, String organizationId, String userPlan,
                                     String resourceType, String resourceToken,
                                     UUID resourceId, String title, String description) {
        // Idempotency check - scoped to org (USER_SCOPED) when present, falling
        // back to tenant for legacy callers. Token IS globally unique on insert
        // path; this guards the cross-scope "token already in use" 1-row replay.
        Optional<SharedLinkEntity> existing = repository.findByResourceTokenAndIsActiveTrue(resourceToken);
        if (existing.isPresent()) {
            boolean sameScope = ScopeGuard.isInStrictScope(
                    tenantId, organizationId,
                    existing.get().getTenantId(), existing.get().getOrganizationId());
            if (!sameScope) {
                throw new IllegalArgumentException("Resource token already in use");
            }
            logger.debug("Shared link already exists for resourceToken={}", resourceToken);
            return existing.get();
        }

        // Quota check - in the same transaction as the insert to prevent TOCTOU race
        long currentCount = organizationId != null
                ? repository.countByOrganizationIdStrict(organizationId)
                : repository.countByTenantId(tenantId);
        int maxPerUser = SharedLinkPlanLimits.getMaxSharedLinks(userPlan);
        if (currentCount >= maxPerUser) {
            throw new SharedLinkLimitException(currentCount, maxPerUser);
        }

        ResourceType type = ResourceType.valueOf(resourceType.toUpperCase());

        SharedLinkEntity entity = new SharedLinkEntity();
        entity.setToken(generateToken());
        entity.setResourceType(type);
        entity.setResourceToken(resourceToken);
        entity.setResourceId(resourceId);
        entity.setTenantId(tenantId);
        entity.setOrganizationId(organizationId);
        entity.setTitle(title);
        entity.setDescription(description);

        try {
            SharedLinkEntity saved = repository.save(entity);
            repository.flush();
            logger.info("Registered shared link token={} for resourceToken={} type={}", saved.getToken(), resourceToken, type);
            return saved;
        } catch (DataIntegrityViolationException e) {
            // Race condition: another thread created the link between our check and save
            logger.debug("Concurrent registration for resourceToken={}, returning existing", resourceToken);
            return repository.findByResourceTokenAndIsActiveTrue(resourceToken)
                    .filter(link -> ScopeGuard.isInStrictScope(
                            tenantId, organizationId,
                            link.getTenantId(), link.getOrganizationId()))
                    .orElseThrow(() -> new IllegalArgumentException("Resource token already in use"));
        }
    }

    /**
     * Resolve a token to its resource info. Increments access counter.
     */
    @Transactional
    public Optional<SharedLinkResolution> resolve(String token) {
        Optional<SharedLinkEntity> opt = repository.findByToken(token);
        if (opt.isEmpty()) return Optional.empty();

        SharedLinkEntity entity = opt.get();
        if (!entity.isActive()) return Optional.empty();

        // Check expiration if configured
        if (entity.getAccessConfig() != null) {
            Object expiresAt = entity.getAccessConfig().get("expiresAt");
            if (expiresAt != null) {
                try {
                    long expiresMs = Long.parseLong(expiresAt.toString());
                    if (System.currentTimeMillis() > expiresMs) {
                        return Optional.empty();
                    }
                } catch (NumberFormatException ignored) {
                    // Invalid expiration format, ignore
                }
            }
        }

        repository.incrementAccessCount(token);

        boolean hasPassword = entity.getAccessConfig() != null
                && entity.getAccessConfig().containsKey("passwordHash");

        return Optional.of(new SharedLinkResolution(
                entity.getToken(),
                entity.getResourceType().name(),
                entity.getResourceToken(),
                entity.getTitle(),
                entity.getDescription(),
                entity.isActive(),
                hasPassword,
                null // Never expose raw metadata to public endpoint
        ));
    }

    /**
     * Update shared link settings. Ownership check via organizationId when
     * provided (canonical USER_SCOPED path), else falls back to tenantId.
     */
    public SharedLinkEntity update(String tenantId, String organizationId, UUID linkId, String title, String description,
                                   Map<String, Object> accessConfig, Boolean isActive) {
        SharedLinkEntity entity = repository.findById(linkId)
                .orElseThrow(() -> new IllegalArgumentException("Shared link not found"));

        boolean authorized = ScopeGuard.isInStrictScope(
                tenantId, organizationId,
                entity.getTenantId(), entity.getOrganizationId());
        if (!authorized) {
            throw new IllegalArgumentException("Not authorized to update this shared link");
        }

        if (title != null) entity.setTitle(title);
        if (description != null) entity.setDescription(description);
        if (accessConfig != null) entity.setAccessConfig(accessConfig);
        if (isActive != null) entity.setActive(isActive);

        return repository.save(entity);
    }

    /**
     * Deactivate shared link by resource token. Called when the resource is deleted.
     */
    public void unregister(String resourceToken) {
        repository.deactivateByResourceToken(resourceToken);
        logger.info("Deactivated shared link for resourceToken={}", resourceToken);
    }

    /**
     * Get a specific shared link by ID, scoped to org when provided
     * (USER_SCOPED canonical path), else falls back to tenant.
     */
    @Transactional(readOnly = true)
    public Optional<SharedLinkEntity> getByIdAndScope(UUID linkId, String tenantId, String organizationId) {
        if (organizationId != null) {
            return repository.findByIdAndOrganizationIdStrict(linkId, organizationId);
        }
        return repository.findByIdAndTenantId(linkId, tenantId);
    }

    /**
     * List shared links scoped to org when provided, else falls back to tenant.
     */
    @Transactional(readOnly = true)
    public List<SharedLinkEntity> getByScope(String tenantId, String organizationId) {
        if (organizationId != null) {
            return repository.findByOrganizationIdStrictOrderByCreatedAtDesc(organizationId);
        }
        return repository.findByTenantIdOrderByCreatedAtDesc(tenantId);
    }

    /**
     * List shared links filtered by type, scoped to org when provided, else tenant.
     */
    @Transactional(readOnly = true)
    public List<SharedLinkEntity> getByScopeAndType(String tenantId, String organizationId, ResourceType type) {
        if (organizationId != null) {
            return repository.findByOrganizationIdStrictAndResourceTypeOrderByCreatedAtDesc(organizationId, type);
        }
        return repository.findByTenantIdAndResourceTypeOrderByCreatedAtDesc(tenantId, type);
    }

    /**
     * Get shared link by token (for internal lookups that need tenantId).
     */
    @Transactional(readOnly = true)
    public Optional<SharedLinkEntity> getByToken(String token) {
        return repository.findByToken(token).filter(SharedLinkEntity::isActive);
    }

    /**
     * Get shared link by resource token (for internal lookups).
     */
    @Transactional(readOnly = true)
    public Optional<SharedLinkEntity> getByResourceToken(String resourceToken) {
        return repository.findByResourceTokenAndIsActiveTrue(resourceToken);
    }

    @Transactional(readOnly = true)
    public Optional<SharedLinkEntity> getByResourceId(UUID resourceId) {
        return repository.findByResourceIdAndIsActiveTrue(resourceId);
    }

    /**
     * Regenerate the share token for a link. Ownership check via org when
     * provided (canonical USER_SCOPED path), else tenant.
     */
    public SharedLinkEntity regenerateToken(String tenantId, String organizationId, UUID linkId) {
        SharedLinkEntity entity = repository.findById(linkId)
                .orElseThrow(() -> new IllegalArgumentException("Shared link not found"));

        boolean authorized = ScopeGuard.isInStrictScope(
                tenantId, organizationId,
                entity.getTenantId(), entity.getOrganizationId());
        if (!authorized) {
            throw new IllegalArgumentException("Not authorized to regenerate this token");
        }

        String oldToken = entity.getToken();
        entity.setToken(generateToken());
        SharedLinkEntity saved = repository.save(entity);

        logger.info("Regenerated shared link token: old={}... new={}...",
                oldToken.substring(0, Math.min(8, oldToken.length())),
                saved.getToken().substring(0, Math.min(8, saved.getToken().length())));
        return saved;
    }

    /**
     * Delete (hard) a shared link. Ownership check via org when provided
     * (canonical USER_SCOPED path), else tenant.
     */
    public void delete(String tenantId, String organizationId, UUID linkId) {
        SharedLinkEntity entity = repository.findById(linkId)
                .orElseThrow(() -> new IllegalArgumentException("Shared link not found"));

        boolean authorized = ScopeGuard.isInStrictScope(
                tenantId, organizationId,
                entity.getTenantId(), entity.getOrganizationId());
        if (!authorized) {
            throw new IllegalArgumentException("Not authorized to delete this shared link");
        }

        repository.delete(entity);
        logger.info("Deleted shared link id={} token={}", linkId, entity.getToken());
    }

    /**
     * Get shared link quota configuration, scoped to org when provided.
     */
    @Transactional(readOnly = true)
    public SharedLinkConfigResponse getConfig(String tenantId, String organizationId, String userPlan) {
        return getConfig(tenantId, organizationId, userPlan, null);
    }

    /**
     * Quota config scoped to org when provided (else tenant), and optionally to
     * a single resourceType, so per-tab gauges (Applications, Conversations, ...)
     * reflect only that tab's links. The plan limit (maxPerUser) is global; the
     * count is filtered when type is provided.
     */
    @Transactional(readOnly = true)
    public SharedLinkConfigResponse getConfig(String tenantId, String organizationId, String userPlan, String resourceType) {
        long currentCount;
        if (resourceType != null && !resourceType.isBlank()) {
            try {
                SharedLinkEntity.ResourceType type = SharedLinkEntity.ResourceType.valueOf(resourceType.toUpperCase());
                currentCount = organizationId != null
                        ? repository.countByOrganizationIdStrictAndResourceType(organizationId, type)
                        : repository.countByTenantIdAndResourceType(tenantId, type);
            } catch (IllegalArgumentException e) {
                logger.warn("Unknown resourceType '{}' in getConfig, falling back to global count", resourceType);
                currentCount = organizationId != null
                        ? repository.countByOrganizationIdStrict(organizationId)
                        : repository.countByTenantId(tenantId);
            }
        } else {
            currentCount = organizationId != null
                    ? repository.countByOrganizationIdStrict(organizationId)
                    : repository.countByTenantId(tenantId);
        }
        int maxPerUser = SharedLinkPlanLimits.getMaxSharedLinks(userPlan);
        return new SharedLinkConfigResponse(maxPerUser, currentCount);
    }

    /**
     * Optimized check: find existing link by resourceToken (or resourceId fallback) + quota in one call.
     * Replaces getAll(type) + getConfig() with a single-row lookup + count.
     * Scoped to org when provided (canonical USER_SCOPED path), else tenant.
     */
    @Transactional(readOnly = true)
    public SharedLinkCheckResponse checkLink(String tenantId, String organizationId,
                                             String resourceToken, UUID resourceId, String userPlan) {
        // Primary lookup by resourceToken (works for CHAT, FORM triggers)
        Optional<SharedLinkEntity> found = organizationId != null
                ? repository.findByOrganizationIdStrictAndResourceTokenAndIsActiveTrue(organizationId, resourceToken)
                : repository.findByTenantIdAndResourceTokenAndIsActiveTrue(tenantId, resourceToken);

        // Fallback: lookup by resourceId (for CONVERSATION where stored resourceToken is cs_ token)
        if (found.isEmpty() && resourceId != null) {
            found = organizationId != null
                    ? repository.findByOrganizationIdStrictAndResourceIdAndIsActiveTrue(organizationId, resourceId)
                    : repository.findByTenantIdAndResourceIdAndIsActiveTrue(tenantId, resourceId);
        }

        SharedLinkResponse linkDto = found.map(SharedLinkResponse::from).orElse(null);

        long currentCount = organizationId != null
                ? repository.countByOrganizationIdStrict(organizationId)
                : repository.countByTenantId(tenantId);
        int maxPerUser = SharedLinkPlanLimits.getMaxSharedLinks(userPlan);
        SharedLinkConfigResponse config = new SharedLinkConfigResponse(maxPerUser, currentCount);

        return new SharedLinkCheckResponse(linkDto, config);
    }

    /**
     * Exception for shared link quota exceeded. Contains count/max for structured handling.
     */
    public static class SharedLinkLimitException extends RuntimeException {
        private final long currentCount;
        private final int maxPerUser;

        public SharedLinkLimitException(long currentCount, int maxPerUser) {
            super("Shared link limit reached");
            this.currentCount = currentCount;
            this.maxPerUser = maxPerUser;
        }

        public long getCurrentCount() { return currentCount; }
        public int getMaxPerUser() { return maxPerUser; }
    }

    private static String generateToken() {
        return "sl_" + UUID.randomUUID().toString().replace("-", "");
    }

    public record SharedLinkResolution(
            String token,
            String resourceType,
            String resourceToken,
            String title,
            String description,
            boolean isActive,
            boolean hasPassword,
            Map<String, Object> metadata
    ) {}
}
