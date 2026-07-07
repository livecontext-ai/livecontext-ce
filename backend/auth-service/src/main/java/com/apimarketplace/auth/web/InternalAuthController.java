package com.apimarketplace.auth.web;

import com.apimarketplace.auth.domain.UserOnboarding;
import com.apimarketplace.auth.repository.OrganizationMemberRepository;
import com.apimarketplace.auth.repository.UserOnboardingRepository;
import com.apimarketplace.common.auth.UserSummaryDto;
import com.apimarketplace.auth.dto.CeLinkEntitlements;
import com.apimarketplace.auth.service.CreditConsumptionDeadLetterService;
import com.apimarketplace.auth.service.CeLinkEntitlementsService;
import com.apimarketplace.auth.service.CeLinkService;
import com.apimarketplace.auth.service.ModelPricingService;
import com.apimarketplace.auth.service.OnboardingService;
import com.apimarketplace.auth.service.OrgRestrictionQueryService;
import com.apimarketplace.auth.service.PlanLimitService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Internal API controller for inter-service communication.
 * Called by other microservices via AuthClient. Not exposed through the public gateway.
 *
 * <p>Endpoint prefix: /api/internal/auth
 */
@RestController
@RequestMapping("/api/internal/auth")
public class InternalAuthController {

    private static final Logger log = LoggerFactory.getLogger(InternalAuthController.class);

    private final OrgRestrictionQueryService restrictionService;
    private final CreditConsumptionDeadLetterService deadLetterService;
    private final UserOnboardingRepository onboardingRepository;
    private final OnboardingService onboardingService;
    private final ModelPricingService modelPricingService;
    private final PlanLimitService planLimitService;
    private final OrganizationMemberRepository memberRepository;
    // CeLinkService is a Cloud-only bean (gated @ConditionalOnProperty auth.mode=keycloak).
    // This controller is mixed-mode (also serves CE-required /plans/limits,
    // /org-restrictions), so the dependency is OPTIONAL: in CE (auth.mode=embedded) the bean
    // is absent and the /ce-link active probe returns active=false (CE has no cloud link).
    private final ObjectProvider<CeLinkService> ceLinkServiceProvider;
    // Same Cloud-only optionality as CeLinkService: in CE (auth.mode=embedded) the
    // bean is absent and the /ce-link entitlements probe returns the no-subscription
    // shape instead of failing context startup.
    private final ObjectProvider<CeLinkEntitlementsService> ceLinkEntitlementsServiceProvider;

    public InternalAuthController(OrgRestrictionQueryService restrictionService,
                                  CreditConsumptionDeadLetterService deadLetterService,
                                  UserOnboardingRepository onboardingRepository,
                                  OnboardingService onboardingService,
                                  ModelPricingService modelPricingService,
                                  PlanLimitService planLimitService,
                                  OrganizationMemberRepository memberRepository,
                                  ObjectProvider<CeLinkService> ceLinkServiceProvider,
                                  ObjectProvider<CeLinkEntitlementsService> ceLinkEntitlementsServiceProvider) {
        this.restrictionService = restrictionService;
        this.deadLetterService = deadLetterService;
        this.onboardingRepository = onboardingRepository;
        this.onboardingService = onboardingService;
        this.modelPricingService = modelPricingService;
        this.planLimitService = planLimitService;
        this.memberRepository = memberRepository;
        this.ceLinkServiceProvider = ceLinkServiceProvider;
        this.ceLinkEntitlementsServiceProvider = ceLinkEntitlementsServiceProvider;
    }

    /**
     * Returns the per-plan creation limit for a given resource type.
     * Header: X-User-ID (Keycloak provider sub)
     * Path: /plans/limits/{resourceType}
     * Returns: { "planCode": "FREE", "limit": 5 } where limit is null if unlimited.
     */
    @GetMapping("/plans/limits/{resourceType}")
    public ResponseEntity<Map<String, Object>> getPlanLimit(
            @RequestHeader("X-User-ID") String providerId,
            @PathVariable String resourceType) {
        if (resourceType == null || resourceType.isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        Integer limit = planLimitService.getLimit(providerId, resourceType);
        String planCode = planLimitService.getPlanCode(providerId);
        Map<String, Object> body = new java.util.HashMap<>();
        body.put("planCode", planCode);
        body.put("limit", limit); // null = unlimited
        return ResponseEntity.ok(body);
    }

    @GetMapping("/ce-link/{installId}/active")
    public ResponseEntity<Map<String, Object>> hasActiveCeLink(
            @RequestHeader("X-User-ID") Long userId,
            @PathVariable UUID installId) {
        // Cloud-only: in CE (auth.mode=embedded) CeLinkService is absent → no cloud link exists.
        CeLinkService ceLinkService = ceLinkServiceProvider.getIfAvailable();
        boolean active = ceLinkService != null && ceLinkService.userOwnsActiveLink(userId, installId);
        return ResponseEntity.ok(Map.of(
                "active", active,
                "installId", installId.toString()));
    }

    /**
     * Subscription entitlements of the cloud account owning a CE link. Consumed by
     * the cloud-side CE catalog relay ({@code AuthClient.ceLinkEntitlements}) to
     * gate relayed tool executions on an ACTIVE PAID subscription.
     *
     * <p>Always 200. Fail-closed shape ({@code planCode="__NONE__"},
     * {@code hasSubscription=false}) for: absent {@code CeLinkEntitlementsService}
     * (CE, {@code auth.mode=embedded}), unknown/foreign/revoked install, malformed
     * user id, or malformed install id. Never 500 on bad input.
     */
    @GetMapping("/ce-link/{installId}/entitlements")
    public ResponseEntity<Map<String, Object>> ceLinkEntitlements(
            @RequestHeader("X-User-ID") String userId,
            @PathVariable String installId) {
        CeLinkEntitlements entitlements = resolveEntitlements(userId, installId);
        String planCode = entitlements.planCode();
        boolean hasSubscription = planCode != null
                && !PlanLimitService.NO_SUBSCRIPTION.equals(planCode)
                && !"FREE".equals(planCode);
        Map<String, Object> body = new HashMap<>();
        body.put("planCode", planCode != null ? planCode : PlanLimitService.NO_SUBSCRIPTION);
        body.put("hasSubscription", hasSubscription);
        return ResponseEntity.ok(body);
    }

    private CeLinkEntitlements resolveEntitlements(String userId, String installId) {
        CeLinkEntitlementsService entitlementsService = ceLinkEntitlementsServiceProvider.getIfAvailable();
        if (entitlementsService == null) {
            return CeLinkEntitlements.none();
        }
        Long callerUserId;
        UUID installUuid;
        try {
            callerUserId = Long.parseLong(userId);
            installUuid = UUID.fromString(installId);
        } catch (RuntimeException malformedInput) {
            // Malformed caller id or install id → same fail-closed shape, never 500.
            return CeLinkEntitlements.none();
        }
        return entitlementsService.entitlementsForCaller(callerUserId, installUuid);
    }

    /**
     * Returns the set of resource IDs restricted for a given org member.
     */
    @GetMapping("/org-restrictions")
    public ResponseEntity<Set<String>> getRestrictedResourceIds(
            @RequestParam String orgId,
            @RequestParam String userId,
            @RequestParam String resourceType) {
        Set<String> restricted = restrictionService.getRestrictedResourceIds(orgId, userId, resourceType);
        return ResponseEntity.ok(restricted);
    }

    /**
     * Returns the resource IDs the member may not WRITE (delete/assign/modify) -
     * any restriction (DENY or READ-only) blocks writes.
     */
    @GetMapping("/org-restrictions/write")
    public ResponseEntity<Set<String>> getWriteRestrictedResourceIds(
            @RequestParam String orgId,
            @RequestParam String userId,
            @RequestParam String resourceType) {
        Set<String> restricted = restrictionService.getWriteRestrictedResourceIds(orgId, userId, resourceType);
        return ResponseEntity.ok(restricted);
    }

    /**
     * Add a single restriction. Optional {@code permission} (DENY|READ, default DENY).
     */
    @PostMapping("/org-restrictions")
    public ResponseEntity<Void> restrictAccess(@RequestBody Map<String, String> body) {
        restrictionService.restrictAccess(
                body.get("orgId"), body.get("memberUserId"),
                body.get("resourceType"), body.get("resourceId"),
                body.get("restrictedBy"), body.get("permission"));
        return ResponseEntity.ok().build();
    }

    /**
     * Remove a single restriction.
     */
    @DeleteMapping("/org-restrictions")
    public ResponseEntity<Void> grantAccess(
            @RequestParam String orgId, @RequestParam String memberUserId,
            @RequestParam String resourceType, @RequestParam String resourceId) {
        restrictionService.grantAccess(orgId, memberUserId, resourceType, resourceId);
        return ResponseEntity.ok().build();
    }

    /**
     * Bulk set restrictions for a member + resource type.
     */
    @SuppressWarnings("unchecked")
    @PutMapping("/org-restrictions/bulk")
    public ResponseEntity<Void> setRestrictions(@RequestBody Map<String, Object> body) {
        String orgId = (String) body.get("orgId");
        String memberUserId = (String) body.get("memberUserId");
        String resourceType = (String) body.get("resourceType");
        List<String> ids = (List<String>) body.getOrDefault("restrictedIds", List.of());
        String restrictedBy = (String) body.get("restrictedBy");
        // Optional per-resource permission map (resourceId -> DENY|READ). Ids missing
        // from the map default to DENY, keeping legacy callers (ids-only) intact.
        Map<String, String> permissions = (Map<String, String>) body.getOrDefault("permissions", Map.of());
        Map<String, String> permissionsById = new HashMap<>();
        for (String id : ids) {
            permissionsById.put(id, permissions.getOrDefault(id, "DENY"));
        }
        // Also honour ids present only in the permissions map.
        permissions.forEach(permissionsById::putIfAbsent);
        restrictionService.setRestrictions(orgId, memberUserId, resourceType, permissionsById, restrictedBy);
        return ResponseEntity.ok().build();
    }

    /**
     * Get all restrictions for a member.
     */
    @GetMapping("/org-restrictions/member")
    public ResponseEntity<List<Map<String, Object>>> getMemberRestrictions(
            @RequestParam String orgId, @RequestParam String memberUserId) {
        return ResponseEntity.ok(restrictionService.getMemberRestrictions(orgId, memberUserId));
    }

    /**
     * Receives dead-letter credit consumption events from other services.
     * Body: { tenantId, sourceType, sourceId, provider, model, promptTokens, completionTokens, errorReason, organizationId? }
     *
     * <p>Phase 6 MIGRATION_ORG_ID_NOT_NULL (CC-2, 2026-05-19): reads
     * {@code X-Organization-ID} header first (set by daemon-side
     * {@link com.apimarketplace.common.credit.HttpCreditDeadLetterHandler}
     * when the producer captured the orgId at dispatch time). Falls back
     * to {@code body.organizationId} for legacy callers that didn't set
     * the header. The receiver then forwards to the 9-arg
     * {@link com.apimarketplace.common.credit.CreditDeadLetterHandler#persistFailedConsumption}
     * so the V261-NOT-NULL column gets stamped.
     */
    @PostMapping("/credit/dead-letter")
    public ResponseEntity<Void> receiveDeadLetter(
            @RequestBody Map<String, Object> body,
            @RequestHeader(value = "X-Organization-ID", required = false) String orgIdHeader) {
        try {
            String organizationId = orgIdHeader;
            if (organizationId == null || organizationId.isBlank()) {
                organizationId = (String) body.get("organizationId");
            }
            deadLetterService.persistFailedConsumption(
                    (String) body.get("tenantId"),
                    (String) body.get("sourceType"),
                    (String) body.get("sourceId"),
                    (String) body.get("provider"),
                    (String) body.get("model"),
                    body.get("promptTokens") instanceof Number n ? n.intValue() : null,
                    body.get("completionTokens") instanceof Number n ? n.intValue() : null,
                    (String) body.get("errorReason"),
                    organizationId
            );
            return ResponseEntity.ok().build();
        } catch (Exception e) {
            log.error("Failed to persist dead-letter entry: {}", e.getMessage());
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * Returns the display name for a user identified either by their Keycloak
     * provider ID (UUID sub) or by their numeric internal user id (bigint,
     * as stored in {@code orchestrator.workflow_runs.tenant_id} for legacy
     * single-user tenants).
     *
     * <p>Detects which form was passed: pure digits → {@code findByUserId},
     * otherwise → {@code findByUserProviderId}. This dual lookup lets
     * orchestrator pass {@code run.tenantId} directly without pre-resolving
     * the providerId, and lets gateway pass the JWT {@code sub} (UUID).
     *
     * <p>Used by the orchestrator to populate {@code triggered_by} in manual
     * trigger outputs and {@code _display_name} in workflow step outputs.
     */
    /**
     * Resolve the user's default-personal organization id. Used by cross-server
     * paths (CE→cloud) where the entry-point only knows the user id and needs
     * to stamp organization_id on persisted rows post-V261.
     */
    @GetMapping("/users/{userId}/default-organization")
    public ResponseEntity<Map<String, String>> getDefaultOrganization(@PathVariable String userId) {
        Long internalId;
        if (userId != null && userId.matches("\\d+")) {
            try { internalId = Long.parseLong(userId); }
            catch (NumberFormatException e) { return ResponseEntity.ok(Map.of("userId", userId)); }
        } else {
            // Provider id (UUID sub) - resolve to internal id via onboarding.
            Optional<UserOnboarding> ob = onboardingRepository.findByUserProviderId(userId);
            if (ob.isEmpty() || ob.get().getUser() == null) {
                return ResponseEntity.ok(Map.of("userId", userId));
            }
            internalId = ob.get().getUser().getId();
        }
        return memberRepository.findDefaultPersonalByUserId(internalId)
                .map(m -> ResponseEntity.ok(Map.of(
                        "userId", userId,
                        "organizationId", m.getOrganization().getId().toString())))
                .orElse(ResponseEntity.ok(Map.of("userId", userId)));
    }

    /**
     * Resolve the user's platform roles (e.g. {@code ["USER","ADMIN"]}) from the
     * persisted store ({@code auth.user_roles}) - the same source the JWT role
     * claims are built from.
     *
     * <p><b>Why server-side resolution:</b> the agent-cli MCP stdio bridge
     * (mcp/agent-cli-server.mjs) talks directly to agent-service without going
     * through the gateway, so there is no JWT validator to inject an
     * {@code X-User-Roles} header. Tools that gate on admin (e.g. modifying a
     * global skill) therefore never saw the caller's roles on the bridge path.
     * agent-service resolves them here at CLI session start and stamps them into
     * the session credentials. Mirrors the role-less precedent in
     * {@code InternalBridgeAccessController.persistedAdmin}.
     *
     * <p>Body shape: {@code {userId, roles:[...]}}. {@code roles} is empty when
     * the user is unknown (never null) so callers can treat "no roles" and
     * "unknown user" identically - both mean "not admin".
     *
     * <p>Dual-form dispatch (numeric internal id vs Keycloak provider UUID)
     * mirrors {@link #getPublisherProfile}. Uses the FETCH-user query variants so
     * the EAGER {@code User.roles} collection loads in the same round-trip.
     */
    @GetMapping("/users/{userId}/roles")
    public ResponseEntity<Map<String, Object>> getUserRoles(@PathVariable String userId) {
        Optional<UserOnboarding> onboarding;
        if (userId != null && userId.matches("\\d+")) {
            try {
                onboarding = onboardingRepository.findByUserIdFetchUser(Long.parseLong(userId));
            } catch (NumberFormatException e) {
                onboarding = Optional.empty();
            }
        } else if (userId != null) {
            onboarding = onboardingRepository.findByUserProviderIdFetchUser(userId);
        } else {
            return ResponseEntity.badRequest().build();
        }

        Set<String> roles = onboarding
                .map(UserOnboarding::getUser)
                .map(u -> u.getRoles())
                .orElse(Set.of());
        Map<String, Object> body = new HashMap<>();
        body.put("userId", userId);
        body.put("roles", new ArrayList<>(roles));
        return ResponseEntity.ok(body);
    }

    /**
     * Returns the auth user ids of every member of an organization, as a JSON
     * array of strings under {@code userIds}. Consumed by agent-service's
     * {@code AuthClient.getOrganizationMemberIds} to validate that a human task
     * assignee / reviewer actually belongs to the task's workspace. A malformed
     * org id yields an empty list (caller fails closed → rejects the assignment).
     */
    @GetMapping("/organizations/{orgId}/member-ids")
    public ResponseEntity<Map<String, Object>> getOrganizationMemberIds(@PathVariable String orgId) {
        java.util.UUID orgUuid;
        try {
            orgUuid = java.util.UUID.fromString(orgId);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.ok(Map.of("userIds", List.of()));
        }
        List<String> ids = memberRepository.findMemberUserIdsByOrganizationId(orgUuid).stream()
                .filter(java.util.Objects::nonNull)
                .map(String::valueOf)
                .toList();
        return ResponseEntity.ok(Map.of("userIds", ids));
    }

    @GetMapping("/users/{userId}/display-name")
    public ResponseEntity<Map<String, String>> getDisplayName(@PathVariable String userId) {
        if (userId == null || userId.isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        // Delegate to the shared resolver: onboarding display name first, then a
        // users-row identity fallback (full name → username → email). The
        // fallback is what makes CE embedded actors - who have no
        // user_onboarding row - resolve to their real name instead of nothing.
        UserSummaryDto summary = onboardingService.resolveUserSummaries(List.of(userId)).get(userId);
        if (summary != null && summary.displayName() != null) {
            return ResponseEntity.ok(Map.of(
                    "displayName", summary.displayName(),
                    "userId", userId
            ));
        }
        return ResponseEntity.ok(Map.of("userId", userId));
    }

    /**
     * Returns the public-facing publisher identity used by publication-service
     * to freeze {@code workflow_publications.publisher_*} columns at publish
     * time. Single source of truth for "who is this publisher right now" -
     * the publish path is the only place this should be called server-side,
     * never by the frontend.
     *
     * <p>Body shape (any field may be absent / null):
     * <pre>{@code {userId, displayName, email, avatarUrl}}</pre>
     *
     * <p>Dual-form dispatch (numeric internal id vs Keycloak provider UUID)
     * mirrors {@link #getDisplayName} so callers can pass whichever form they
     * have in hand.
     */
    @GetMapping("/users/{userId}/publisher-profile")
    public ResponseEntity<Map<String, String>> getPublisherProfile(@PathVariable String userId) {
        // JOIN FETCH the user association so the controller can read
        // uo.user.email / .avatarUrl without depending on Spring's
        // open-in-view to lazy-load - explicit > implicit.
        Optional<UserOnboarding> onboarding;
        if (userId != null && userId.matches("\\d+")) {
            try {
                onboarding = onboardingRepository.findByUserIdFetchUser(Long.parseLong(userId));
            } catch (NumberFormatException e) {
                onboarding = Optional.empty();
            }
        } else if (userId != null) {
            onboarding = onboardingRepository.findByUserProviderIdFetchUser(userId);
        } else {
            return ResponseEntity.badRequest().build();
        }

        Map<String, String> body = new HashMap<>();
        body.put("userId", userId);
        if (onboarding.isPresent()) {
            UserOnboarding ob = onboarding.get();
            if (ob.getDisplayName() != null) body.put("displayName", ob.getDisplayName());
            // user_onboarding has a LAZY association to the User; reach through
            // to grab email + avatarUrl in the same round-trip.
            if (ob.getUser() != null) {
                if (ob.getUser().getEmail() != null) body.put("email", ob.getUser().getEmail());
                if (ob.getUser().getAvatarUrl() != null) body.put("avatarUrl", ob.getUser().getAvatarUrl());
            }
        }
        return ResponseEntity.ok(body);
    }

    /**
     * Batch resolve displayName for up to {@value #BATCH_RESOLVE_MAX} user ids.
     * Used by the recent-activity aggregator
     * ({@code RecentActivityAggregatorService} in orchestrator-service) to
     * resolve {@code created_by → displayName} for ~50 rows in a single RPC
     * - avoids the per-row N+1 that calling
     * {@link #getDisplayName(String)} would produce.
     *
     * <p><b>Input form:</b> mixed numeric ({@code "42"}) and UUID
     * ({@code "uuid-…"}) ids accepted, matching {@link #getDisplayName}'s
     * dual-form dispatch. Numeric ids that don't parse and unknown ids are
     * returned in the response map with {@code displayName=null} so the
     * caller can cache them as negative entries (matches the single-user
     * endpoint's behavior at line 210 returning {@code Map.of("userId", userId)}
     * without a displayName key - the absence-of-key is the negative signal).
     *
     * <p><b>Avatar:</b> {@code avatarUrl} is always {@code null} until
     * {@code user_onboarding.avatar_url} backfill lands (see
     * {@link UserSummaryDto} javadoc).
     *
     * <p><b>Cap:</b> 200 ids per request - the aggregator batches at most 50,
     * other callers shouldn't need more either. Inputs above the cap return
     * {@code 400 Bad Request} (defensive: a runaway caller could DoS the
     * JOIN FETCH otherwise).
     */
    private static final int BATCH_RESOLVE_MAX = 200;

    @PostMapping("/users/resolve-batch")
    public ResponseEntity<Map<String, UserSummaryDto>> resolveBatch(@RequestBody List<String> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return ResponseEntity.ok(Map.of());
        }
        if (userIds.size() > BATCH_RESOLVE_MAX) {
            return ResponseEntity.badRequest().build();
        }

        // Shared resolver: partitions ids by form (numeric user.id vs provider
        // sub), resolves the onboarding display name, then falls back to the
        // users-row identity (full name → username → email) for ids with no
        // onboarding name. The fallback is what makes CE embedded actors - who
        // never get a user_onboarding row - resolve to a real name instead of
        // "unknown" in the recent-activity feed.
        Map<String, UserSummaryDto> result = new HashMap<>(onboardingService.resolveUserSummaries(userIds));

        // Defensive: emit NULL entries for ids the caller asked about but DB
        // didn't return - lets the client cache them as negative hits and
        // skip repeated lookups for known-missing users (deleted accounts, etc.)
        // for the cache TTL window. Mirrors getDisplayName's silent-null behavior.
        for (String raw : userIds) {
            if (raw == null || raw.isBlank()) continue;
            result.putIfAbsent(raw, new UserSummaryDto(raw, null, null));
        }

        return ResponseEntity.ok(result);
    }

    /**
     * Upserts a model pricing entry. Idempotent: updates rates if an active entry
     * for the provider+model exists, otherwise creates a new one.
     * Used by other services to synchronize pricing when an admin changes prices.
     */
    /** Whitelist mirrors the V117 CHECK constraint on auth.model_pricing.provider_kind. */
    private static final java.util.Set<String> VALID_PROVIDER_KINDS =
            java.util.Set.of("byok", "bridge", "cloud");

    @PostMapping("/model-pricing/sync")
    public ResponseEntity<Void> syncModelPricing(@RequestBody Map<String, Object> body) {
        String provider = (String) body.get("provider");
        String model = (String) body.get("model");
        BigDecimal inputRate = body.get("inputRate") instanceof Number n
                ? new BigDecimal(n.toString()) : null;
        BigDecimal outputRate = body.get("outputRate") instanceof Number n
                ? new BigDecimal(n.toString()) : null;
        String providerKind = body.get("providerKind") instanceof String s && !s.isBlank() ? s : null;

        if (provider == null || model == null || inputRate == null || outputRate == null) {
            return ResponseEntity.badRequest().build();
        }
        // Fail fast on invalid providerKind instead of bubbling DB CHECK violation as 500.
        if (providerKind != null && !VALID_PROVIDER_KINDS.contains(providerKind)) {
            return ResponseEntity.badRequest().build();
        }

        modelPricingService.upsertPricing(provider, model, inputRate, outputRate, providerKind);
        return ResponseEntity.ok().build();
    }
}
