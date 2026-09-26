package com.apimarketplace.publication.service;

import com.apimarketplace.common.web.LogSafePath;
import org.springframework.beans.factory.annotation.Autowired;
import com.apimarketplace.common.security.token.TokenAtRest;
import com.apimarketplace.publication.security.PublicationTokenAtRestBackfill;

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

    /**
     * Read-only plaintext fallback (reads) and heal (writes) for a link row still stored in clear (pre-2026-09-17).
     * Optional so a unit test can build the service without a database; in a Spring context
     * the component is always present (same package tree).
     */
    @Autowired(required = false)
    private PublicationTokenAtRestBackfill tokenBackfill;

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
        Optional<SharedLinkEntity> existing = findActiveByResourceToken(resourceToken);
        if (existing.isPresent()) {
            boolean sameScope = ScopeGuard.isInStrictScope(
                    tenantId, organizationId,
                    existing.get().getTenantId(), existing.get().getOrganizationId());
            if (!sameScope) {
                throw new IllegalArgumentException("Resource token already in use");
            }
            logger.debug("Shared link already exists for resourceToken={}", LogSafePath.tokenPreview(resourceToken));
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
            logger.info("Registered shared link id={} for resource type={}", saved.getId(), type);
            return saved;
        } catch (DataIntegrityViolationException e) {
            // Race condition: another thread created the link between our check and save
            logger.debug("Concurrent registration for resourceToken={}, returning existing", LogSafePath.tokenPreview(resourceToken));
            return findActiveByResourceToken(resourceToken)
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
        Optional<SharedLinkEntity> opt = findByPlainToken(token);
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

        // By id, not by hash: a row the delayed backfill has not reached yet has a NULL hash, and
        // keying the counter on it would silently freeze access_count for every legacy link.
        repository.incrementAccessCountById(entity.getId());

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
        // THE ONLY WRITE IN THIS CLASS WITH NO SCOPE PREDICATE, and deliberately so. Written
        // down because it looks exactly like the bug the rest of this class was just hardened
        // against, and the next reader will otherwise either "fix" it or worry about it.
        //
        // What authorises it is not a predicate here but two properties of the path. First,
        // `/api/internal/shared-links/unregister` is unroutable from the edge: the gateway
        // never exposes the `/api/internal` prefix (pinned by
        // SimpleGatewayConfigTest.mustNotPubliclyRouteApiInternal) and CE restricts it to
        // loopback in MonolithSecurityFilter. Second, all three callers have already been
        // authorised on the owning resource: AgentConversationModule reads the link by
        // resourceId WITH scope headers and unregisters the token it got back, while the
        // chat- and form-endpoint services unregister the token of an endpoint whose delete or
        // rotate they just performed. The resourceToken is itself the unguessable capability.
        //
        // Why not scope it anyway, as defence in depth: every caller swallows the failure
        // (AgentConversationModule logs "non-blocking" and moves on). A scope predicate that
        // does not match would therefore leave the link ACTIVE and tell nobody, which is worse
        // than the risk it removes: a user who unshared something would still be sharing it.
        // Doing this properly means threading scope headers through publication-client and
        // three services AND making the callers surface a refusal, which is its own change
        // with its own e2e, not a line added here.
        //
        // A row written before the hash column exists carries no hash yet: this is a WRITE path,
        // so heal (rewrite) it first so the deactivation by hash actually reaches it. The heal is
        // a no-op once the startup pass has drained the table.
        if (tokenBackfill != null) {
            tokenBackfill.heal(PublicationTokenAtRestBackfill.SHARED_LINK_RESOURCE_TOKENS, resourceToken);
        }
        int deactivated = repository.deactivateByResourceTokenHash(TokenAtRest.hash(resourceToken));
        if (deactivated == 0) {
            logger.info("No active shared link to deactivate for this resource token");
        } else {
            logger.info("Deactivated {} shared link(s) for a resource token", deactivated);
        }
    }

    /**
     * Whether a row is in the caller's active workspace.
     *
     * <p>The three readers below choose their finder the same way they always did: by org when
     * the caller has one, by tenant otherwise. That choice is a QUERY optimisation, not the
     * isolation rule, and the tenant branch is looser than the rule the rest of this class
     * enforces: it returns a row the caller created and later tagged to an organization, which
     * belongs to that workspace and not to whoever made it.
     *
     * <p>Filtering here rather than rewriting the queries keeps the change to what it is. For a
     * caller WITH an org this is provably a no-op: the query already selected on
     * {@code organization_id = :organizationId}, which is exactly what the predicate re-checks.
     * It only bites in personal scope, and there it makes these readers agree with
     * {@code checkLink} and with every write path, instead of the state this replaces, where
     * {@code /check} answered "no link" for a row that {@code GET /shared-links} still listed.
     */
    private boolean inCallerScope(SharedLinkEntity link, String tenantId, String organizationId) {
        return ScopeGuard.isInStrictScope(
                tenantId, organizationId, link.getTenantId(), link.getOrganizationId());
    }

    /**
     * Get a specific shared link by ID, in the caller's active workspace.
     *
     * <p>The finder is chosen by org when provided (USER_SCOPED canonical path) and by tenant
     * otherwise; {@link #inCallerScope} is what decides, see its note.
     */
    @Transactional(readOnly = true)
    public Optional<SharedLinkEntity> getByIdAndScope(UUID linkId, String tenantId, String organizationId) {
        Optional<SharedLinkEntity> found = organizationId != null
                ? repository.findByIdAndOrganizationIdStrict(linkId, organizationId)
                : repository.findByIdAndTenantId(linkId, tenantId);
        return found.filter(link -> inCallerScope(link, tenantId, organizationId));
    }

    /**
     * List shared links in the caller's active workspace.
     */
    @Transactional(readOnly = true)
    public List<SharedLinkEntity> getByScope(String tenantId, String organizationId) {
        List<SharedLinkEntity> found = organizationId != null
                ? repository.findByOrganizationIdStrictOrderByCreatedAtDesc(organizationId)
                : repository.findByTenantIdOrderByCreatedAtDesc(tenantId);
        return found.stream().filter(link -> inCallerScope(link, tenantId, organizationId)).toList();
    }

    /**
     * List shared links of one type, in the caller's active workspace.
     */
    @Transactional(readOnly = true)
    public List<SharedLinkEntity> getByScopeAndType(String tenantId, String organizationId, ResourceType type) {
        List<SharedLinkEntity> found = organizationId != null
                ? repository.findByOrganizationIdStrictAndResourceTypeOrderByCreatedAtDesc(organizationId, type)
                : repository.findByTenantIdAndResourceTypeOrderByCreatedAtDesc(tenantId, type);
        return found.stream().filter(link -> inCallerScope(link, tenantId, organizationId)).toList();
    }

    /**
     * Get shared link by token (for internal lookups that need tenantId).
     */
    @Transactional(readOnly = true)
    public Optional<SharedLinkEntity> getByToken(String token) {
        return findByPlainToken(token).filter(SharedLinkEntity::isActive);
    }

    /**
     * Get shared link by resource token (for internal lookups).
     */
    @Transactional(readOnly = true)
    public Optional<SharedLinkEntity> getByResourceToken(String resourceToken) {
        return findActiveByResourceToken(resourceToken);
    }

    /** Hash lookup on the public link token, with the read-only plaintext fallback for a pre-hash row. */
    private Optional<SharedLinkEntity> findByPlainToken(String token) {
        return TokenAtRest.lookup(token, repository::findByTokenHash,
                t -> tokenBackfill == null ? Optional.empty() : tokenBackfill.findLegacy(PublicationTokenAtRestBackfill.SHARED_LINK_TOKENS, t, repository::findLegacyPlaintext));
    }

    /** Hash lookup on the underlying resource token, with the read-only plaintext fallback for a pre-hash row. */
    private Optional<SharedLinkEntity> findActiveByResourceToken(String resourceToken) {
        return TokenAtRest.lookup(resourceToken, repository::findByResourceTokenHashAndIsActiveTrue,
                t -> tokenBackfill == null ? Optional.empty() : tokenBackfill.findLegacy(PublicationTokenAtRestBackfill.SHARED_LINK_RESOURCE_TOKENS, t, t2 -> repository.findLegacyPlaintextResourceToken(t2).filter(SharedLinkEntity::isActive)));
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
        logger.info("Deleted shared link id={}", linkId);
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
     *
     * <p>The lookups narrow by org when the caller has one and by tenant otherwise, but they do
     * NOT decide isolation: ScopeGuard does, once, on whatever they return. See the comment at
     * that filter for why this method follows the write side of the class and what it costs.
     */
    @Transactional(readOnly = true)
    public SharedLinkCheckResponse checkLink(String tenantId, String organizationId,
                                             String resourceToken, UUID resourceId, String userPlan) {
        // Primary lookup by resourceToken (works for CHAT, FORM triggers)
        Optional<SharedLinkEntity> found = TokenAtRest.lookup(resourceToken,
                h -> organizationId != null
                        ? repository.findByOrganizationIdStrictAndResourceTokenHashAndIsActiveTrue(organizationId, h)
                        : repository.findByTenantIdAndResourceTokenHashAndIsActiveTrue(tenantId, h),
                t -> tokenBackfill == null ? Optional.empty() : tokenBackfill.findLegacy(
                        PublicationTokenAtRestBackfill.SHARED_LINK_RESOURCE_TOKENS, t,
                        // Mirrors the primary hash query exactly, so the fallback finds the same rows
                        // it stands in for. Isolation is NOT decided here: the ScopeGuard filter below
                        // is at least as strict for every row that can exist, so only isActive() is
                        // still load-bearing. (Not literal subsumption: this branches on != null and
                        // ScopeGuard on !isBlank(), which differ for a present-but-empty header, a
                        // case no row can satisfy post-V263 either way.) Kept as the mirror of the
                        // primary rather than trimmed, so the two lookups read as one pair.
                        t2 -> repository.findLegacyPlaintextResourceToken(t2).filter(l -> l.isActive()
                                && (organizationId != null
                                        ? organizationId.equals(l.getOrganizationId())
                                        : tenantId != null && tenantId.equals(l.getTenantId())))));

        // Fallback: lookup by resourceId (for CONVERSATION where stored resourceToken is cs_ token)
        if (found.isEmpty() && resourceId != null) {
            found = organizationId != null
                    ? repository.findByOrganizationIdStrictAndResourceIdAndIsActiveTrue(organizationId, resourceId)
                    : repository.findByTenantIdAndResourceIdAndIsActiveTrue(tenantId, resourceId);
        }

        // THE LOOKUPS ABOVE ARE NOT THE ISOLATION RULE. They narrow by org when the caller
        // has one and by tenant otherwise, and that second branch is looser: in personal scope
        // it returns a row the caller created and later tagged to an organization, which
        // belongs to that workspace and not to whoever made it.
        //
        // Every path in this class now decides the same way: `register`, `update`, `delete`
        // and `regenerateToken` on the write side, `getByScope`, `getByIdAndScope` and
        // `getByScopeAndType` on the read side, and this method between them. That matters
        // here more than anywhere else, because this answer is the INPUT to a register call:
        // the caller asks "is this resource already shared?" and acts on the reply. Answering
        // "yes, here it is" with a row `register` would refuse by this same predicate is what
        // made the two disagree, and it handed an org workspace's link to a personal one.
        //
        // What that means in practice, stated plainly because it is the whole trade: since
        // V263 made organization_id NOT NULL, no row can satisfy the personal branch of
        // ScopeGuard, so an org-less caller now always reads "no link" here and a follow-up
        // register throws "Resource token already in use" instead of reusing the row. That is
        // the intended answer, not an oversight: an org-less caller is not in the workspace
        // the row belongs to. An org-less caller is rare but real, not impossible: the gateway
        // and the CE context filter both ship the request WITHOUT org headers when the user
        // resolves to no membership and has no default org, and the controller declares the
        // header optional. Such a caller owns no rows either: register never gets as far as
        // the NOT NULL column, because OrgScopedEntityListener throws at @PrePersist when the
        // org is null and TenantResolver has none (the caller sees a 500, not a constraint
        // error, which is worth knowing before hunting for one in the DB logs). So what an
        // org-less caller loses here is the sight of a workspace they are not in, not a link
        // of their own.
        //
        // The quota below stays deliberately loose, like `register`'s own count: it answers
        // "how many links does this account have", which spans the caller's workspaces, and is
        // a different question from "is this particular row in scope".
        SharedLinkResponse linkDto = found
                .filter(link -> ScopeGuard.isInStrictScope(
                        tenantId, organizationId,
                        link.getTenantId(), link.getOrganizationId()))
                .map(SharedLinkResponse::from)
                .orElse(null);

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
