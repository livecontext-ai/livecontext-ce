package com.apimarketplace.auth.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import com.apimarketplace.common.web.OrgContextHeaderForwarder;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

import com.apimarketplace.auth.client.dto.BadgeProfileDto;
import com.apimarketplace.auth.client.dto.CeLinkEntitlementsResult;
import com.apimarketplace.auth.client.dto.OrgRestrictionDto;
import com.apimarketplace.auth.client.dto.PublisherProfileDto;
import com.apimarketplace.common.auth.UserSummaryDto;
import com.apimarketplace.common.plan.CeLinkAccess;
import com.apimarketplace.common.plan.CeLinkAccessResult;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * HTTP client for communicating with auth-service internal API.
 * Provides resource index claiming and org access restriction queries.
 */
public class AuthClient {

    private static final Logger log = LoggerFactory.getLogger(AuthClient.class);

    private final RestTemplate restTemplate;
    // Dedicated bounded-timeout template used ONLY by
    // {@link #batchResolveUsers} - 2s connect / 3s read. The aggregator
    // ({@code RecentActivityAggregatorService}) calls batchResolveUsers
    // synchronously AFTER the 4-way fan-out, so it is NOT wrapped in a
    // CompletableFuture timeout. Without a bounded read timeout here, an
    // auth-service hang would park the aggregator's calling thread
    // indefinitely. Mirrors the {@code recentActivityRestTemplate}
    // pattern in interface/agent/datasource clients (auditor v3.3
    // chunks 2+3 must-fix).
    private final RestTemplate boundedRestTemplate;
    // Used ONLY by {@link #sendNotificationMail}: long enough for an SMTP send.
    private final RestTemplate mailRestTemplate = createMailRestTemplate();
    private final String baseUrl;

    // Simple in-memory cache for user summaries (userId → CachedUserSummary).
    // Shared by {@link #getDisplayName} (single-user, name-only consumer) and
    // {@link #batchResolveUsers} (batch consumer that also reads avatarUrl).
    // ONE cache, both writers respect the same TTL - avoids the divergence
    // trap auditor A flagged on the v3.3 plan when two separate caches could
    // drift between single and batch paths.
    // NOTE: {@link #getPublisherProfile} intentionally does NOT touch this
    // cache - the publish path requires a fresh read from auth.users every
    // time so the publication snapshot is current. Don't wire it in here.
    private final ConcurrentHashMap<String, CachedUserSummary> displayNameCache = new ConcurrentHashMap<>();
    private static final long DISPLAY_NAME_CACHE_TTL_MS = 5 * 60 * 1000; // 5 minutes

    // Bumped from displayName-only to (displayName + avatarUrl) so the batch
    // path can read avatar from cache hits without re-fetching. The single-user
    // {@link #getDisplayName} path writes avatarUrl=null because the single
    // endpoint doesn't return it yet - acceptable: a subsequent batch call for
    // the same id will replace the entry with the avatar populated (once the
    // user_onboarding.avatar_url backfill lands).
    private record CachedUserSummary(String displayName, String avatarUrl, long cachedAt) {
        boolean isExpired() {
            return System.currentTimeMillis() - cachedAt > DISPLAY_NAME_CACHE_TTL_MS;
        }
    }

    public AuthClient(String authServiceUrl) {
        this.restTemplate = new RestTemplate();
        this.boundedRestTemplate = createBoundedRestTemplate();
        this.baseUrl = authServiceUrl;
    }

    public AuthClient(RestTemplate restTemplate, String authServiceUrl) {
        this.restTemplate = restTemplate;
        this.boundedRestTemplate = createBoundedRestTemplate();
        this.baseUrl = authServiceUrl;
    }

    private static RestTemplate createBoundedRestTemplate() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(2));
        factory.setReadTimeout(Duration.ofSeconds(3));
        return new RestTemplate(factory);
    }

    // ========== Plan Resource Limits ==========

    /**
     * Fetches the per-plan creation limit for a resource type from auth-service.
     *
     * @param providerId   the user's provider ID (Keycloak sub) - passed in X-User-ID header
     * @param resourceType WORKFLOW | AGENT | DATASOURCE | INTERFACE | APPLICATION
     * @return {planCode, limit} - limit is null if unlimited; null result on error (fail-open)
     */
    public PlanLimitResponse getResourceLimit(String providerId, String resourceType) {
        if (providerId == null || providerId.isBlank() || resourceType == null) {
            return null;
        }
        String url = baseUrl + "/api/internal/auth/plans/limits/" + resourceType;
        HttpEntity<Void> entity = new HttpEntity<>(buildHeaders(providerId));
        try {
            ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                    url, HttpMethod.GET, entity,
                    new ParameterizedTypeReference<>() {});
            Map<String, Object> body = response.getBody();
            if (body == null) return null;
            String planCode = (String) body.get("planCode");
            Object limitObj = body.get("limit");
            Integer limit = (limitObj instanceof Number n) ? n.intValue() : null;
            return new PlanLimitResponse(planCode, limit);
        } catch (Exception e) {
            log.warn("Failed to fetch plan limit for user={}, type={}: {} - failing OPEN",
                    providerId, resourceType, e.getMessage());
            return null;
        }
    }

    /**
     * Plan limit lookup response. {@code limit} is null when unlimited.
     */
    public record PlanLimitResponse(String planCode, Integer limit) {}

    /**
     * Fetches the plan gate: every feature key that requires more than FREE, plus
     * the caller's own plan code when {@code providerId} is given.
     *
     * <p>Returns {@code null} on any failure so callers can fail OPEN. A node must
     * never be blocked because auth-service was briefly unreachable: the cost of
     * that mistake is a run that fails for a reason the user cannot act on.
     */
    public PlanFeatureResponse getPlanFeatures(String providerId) {
        String url = baseUrl + "/api/internal/auth/plan-features";
        HttpEntity<Void> entity = new HttpEntity<>(buildHeaders(providerId));
        try {
            ResponseEntity<Map<String, Object>> response = boundedRestTemplate.exchange(
                    url, HttpMethod.GET, entity,
                    new ParameterizedTypeReference<>() {});
            Map<String, Object> body = response.getBody();
            if (body == null) return null;
            Map<String, String> requirements = new HashMap<>();
            Object raw = body.get("requirements");
            if (raw instanceof Map<?, ?> map) {
                for (Map.Entry<?, ?> e : map.entrySet()) {
                    if (e.getKey() != null && e.getValue() != null) {
                        requirements.put(e.getKey().toString(), e.getValue().toString());
                    }
                }
            }
            Object planCode = body.get("planCode");
            return new PlanFeatureResponse(
                    Collections.unmodifiableMap(requirements),
                    planCode != null ? planCode.toString() : null);
        } catch (Exception e) {
            log.warn("Failed to fetch plan features for user={}: {} - failing OPEN", providerId, e.getMessage());
            return null;
        }
    }

    /**
     * The plan gate as one payload: {@code featureKey -> minimum plan}, and the
     * plan of the user the request was made for ({@code null} when none was given).
     */
    public record PlanFeatureResponse(Map<String, String> requirements, String planCode) {}

    /**
     * Execution-log retention windows for the WORKSPACES {@code organizationIds},
     * in days.
     *
     * <p>Keyed by organization id, not by tenant: a workspace's window is its
     * owner's plan, and the sweepers group journal rows by the workspace they
     * live in before asking. Passing a tenant id here yields no entry.
     *
     * <p><b>A workspace absent from the returned map retains indefinitely</b>,
     * and so does every workspace when this call fails: the empty map an error
     * produces is indistinguishable from "nobody has a finite window", which is
     * exactly the behaviour a deleting caller needs. Note the direction, opposite
     * to every other method on this client: the others fail OPEN because they
     * gate access and the cost of being wrong is a refused feature. This one
     * feeds a purge, so it fails LONG, and returning an empty map on error is the
     * whole mechanism. A caller must never read absence as "no retention".
     *
     * @param organizationIds at most 500 per call; the endpoint refuses a longer
     *                        list rather than truncating it
     */
    public Map<String, Integer> getLogRetentionDays(Collection<String> organizationIds) {
        if (organizationIds == null || organizationIds.isEmpty()) {
            return Map.of();
        }
        // Paged here rather than clamped at each caller. The endpoint REFUSES an
        // oversized list (it must not truncate), so a sweeper configured with a
        // larger page would otherwise get a 400, read the resulting empty map as
        // "nobody has a window", and silently retain everything while reporting the
        // workspaces as considered. Safe, but invisible; chunking removes the trap.
        List<String> all = new ArrayList<>(organizationIds);
        Map<String, Integer> merged = new HashMap<>();
        for (int start = 0; start < all.size(); start += LOG_RETENTION_MAX_BATCH) {
            List<String> chunk = all.subList(start, Math.min(start + LOG_RETENTION_MAX_BATCH, all.size()));
            merged.putAll(fetchLogRetentionChunk(chunk));
        }
        return Collections.unmodifiableMap(merged);
    }

    /** Matches the endpoint's own cap; a longer list is refused, never truncated. */
    private static final int LOG_RETENTION_MAX_BATCH = 500;

    /**
     * The purge outbox after {@code afterSeq}, oldest first, at most {@code limit} rows
     * (the endpoint caps at 200). Consumed by {@code PurgeFollower} in every service.
     *
     * <p><b>Fails EMPTY.</b> A transport failure returns an empty list, which the follower
     * reads as "nothing new": it keeps its cursor and asks again next pass. Nothing is ever
     * skipped by an error here, and nothing is ever deleted on the strength of one.
     */
    public java.util.List<com.apimarketplace.auth.client.purge.PurgeRecord> getPurges(long afterSeq, int limit) {
        String url = baseUrl + "/api/internal/auth/purges?after=" + afterSeq + "&limit=" + limit;
        HttpEntity<Void> entity = new HttpEntity<>(buildHeaders(null));
        try {
            ResponseEntity<java.util.List<com.apimarketplace.auth.client.purge.PurgeRecord>> response =
                    boundedRestTemplate.exchange(url, HttpMethod.GET, entity,
                            new ParameterizedTypeReference<>() {});
            java.util.List<com.apimarketplace.auth.client.purge.PurgeRecord> body = response.getBody();
            return body != null ? body : java.util.List.of();
        } catch (Exception e) {
            log.warn("Failed to read the purge log after #{}: {} - nothing applied this pass", afterSeq, e.getMessage());
            return java.util.List.of();
        }
    }

    /**
     * Tells auth-service this user just created a workflow, for the lifecycle emails
     * ({@code user.activated}). Idempotent on the auth side: only the first call per account
     * does anything. Bounded (2 s / 3 s) and <b>never throws</b>: callers fire it after a save
     * and a lifecycle signal must never fail one.
     *
     * @return true when auth-service acknowledged the call
     */
    public boolean reportActivation(String userId) {
        if (userId == null || userId.isBlank()) return false;
        String url = baseUrl + "/api/internal/auth/lifecycle/activation";
        HttpEntity<Void> entity = new HttpEntity<>(buildHeaders(userId));
        try {
            boundedRestTemplate.exchange(url, HttpMethod.POST, entity, Void.class);
            return true;
        } catch (Exception e) {
            log.debug("Lifecycle activation not reported for user {}: {}", userId, e.getMessage());
            return false;
        }
    }

    /** What auth-service did with a {@link #emitLifecycleEvent} call. */
    public enum LifecycleEventResult {
        /** Taken: queued for Resend. */
        ACCEPTED,
        /**
         * Not sent because lifecycle emails are off in auth-service (no key, kill switch; 204).
         * Nothing to retry now, but a sender that records "sent" must not record it.
         */
        INACTIVE,
        /** Refused for good (400 / 404 only): unknown user, or an event / payload off auth-service's allow-list. Do not retry. */
        REFUSED,
        /** Not taken right now (busy, unreachable, or any other 4xx / 5xx): the caller may retry later. */
        RETRY_LATER
    }

    /**
     * Sends one lifecycle email event for this user ({@code badge.unlocked},
     * {@code recap.monthly}). {@code payload} carries facts only (codes, counts, a month):
     * auth-service turns them into the user's language. Bounded (2 s / 3 s) and <b>never
     * throws</b>: a lifecycle email must never fail the work that triggered it.
     */
    public LifecycleEventResult emitLifecycleEvent(String userId, String event, Map<String, Object> payload) {
        if (userId == null || userId.isBlank() || event == null || event.isBlank()) {
            return LifecycleEventResult.REFUSED;
        }
        String url = baseUrl + "/api/internal/auth/lifecycle/events";
        Map<String, Object> body = new HashMap<>();
        body.put("event", event);
        body.put("payload", payload != null ? payload : Map.of());
        try {
            ResponseEntity<Void> response = boundedRestTemplate.exchange(url, HttpMethod.POST,
                    new HttpEntity<>(body, buildHeaders(userId)), Void.class);
            return response != null && response.getStatusCode().value() == 204
                    ? LifecycleEventResult.INACTIVE
                    : LifecycleEventResult.ACCEPTED;
        } catch (org.springframework.web.client.HttpClientErrorException e) {
            int status = e.getStatusCode().value();
            // Only what auth-service answers about THIS event is final: 400 (event or payload off
            // its allow-list) and 404 (unknown user). Any other 4xx (401/403 wiring, 409, 422, 429)
            // says nothing about the event, so it is retried later rather than dropped for good.
            if (status == 400 || status == 404) {
                log.debug("Lifecycle event {} refused for user {}: {}", event, userId, e.getStatusCode());
                return LifecycleEventResult.REFUSED;
            }
            if (status != 429) {
                log.warn("Lifecycle event {} for user {} answered {}: will retry later", event, userId,
                        e.getStatusCode());
            }
            return LifecycleEventResult.RETRY_LATER;
        } catch (Exception e) {
            log.debug("Lifecycle event {} not sent for user {}: {}", event, userId, e.getMessage());
            return LifecycleEventResult.RETRY_LATER;
        }
    }

    private Map<String, Integer> fetchLogRetentionChunk(List<String> organizationIds) {
        String url = baseUrl + "/api/internal/auth/log-retention";
        HttpEntity<List<String>> entity =
                new HttpEntity<>(new ArrayList<>(organizationIds), buildHeaders(null));
        try {
            ResponseEntity<Map<String, Integer>> response = boundedRestTemplate.exchange(
                    url, HttpMethod.POST, entity,
                    new ParameterizedTypeReference<>() {});
            Map<String, Integer> body = response.getBody();
            return body != null ? body : Map.of();
        } catch (Exception e) {
            log.warn("Failed to fetch log-retention windows for {} workspace(s): {} - retaining all",
                    organizationIds.size(), e.getMessage());
            return Map.of();
        }
    }

    /**
     * Cloud-side "linked AND paid" check for every CE-link-gated relay: true only when
     * {@link #ceLinkAccess} answers {@link CeLinkAccess#ACTIVE}. A suspended link (the
     * account fell back to a non-paid plan) answers false. Callers that must tell the CE
     * WHY (plan required vs not linked) use {@link #ceLinkAccess} and
     * {@link com.apimarketplace.common.plan.CeLinkRefusal}.
     */
    public boolean userOwnsActiveCeLink(String userId, String installId) {
        return ceLinkAccess(userId, installId).isActive();
    }

    /**
     * Resolve what {@code userId} may do through the CE link of {@code installId}: ACTIVE
     * (linked and paid), PLAN_REQUIRED (linked, suspended until the account pays) or
     * NOT_LINKED. Fail-closed: a malformed id, a transport failure or a non-2xx answer is
     * NOT_LINKED. An auth-service that predates the {@code reason} field is read through its
     * {@code active} flag alone.
     */
    public CeLinkAccessResult ceLinkAccess(String userId, String installId) {
        if (userId == null || userId.isBlank() || installId == null || installId.isBlank()) {
            return CeLinkAccessResult.notLinked();
        }
        try {
            UUID.fromString(installId);
        } catch (IllegalArgumentException invalidInstallId) {
            return CeLinkAccessResult.notLinked();
        }
        String url = baseUrl + "/api/internal/auth/ce-link/" + installId + "/active";
        HttpEntity<Void> entity = new HttpEntity<>(buildHeaders(userId));
        try {
            ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                    url, HttpMethod.GET, entity,
                    new ParameterizedTypeReference<>() {});
            Map<String, Object> body = response.getBody();
            if (!response.getStatusCode().is2xxSuccessful() || body == null) {
                return CeLinkAccessResult.notLinked();
            }
            String planCode = body.get("planCode") instanceof String s && !s.isBlank() ? s : null;
            if (CeLinkAccess.PLAN_REQUIRED.name().equals(body.get("reason"))) {
                return CeLinkAccessResult.planRequired(planCode);
            }
            return Boolean.TRUE.equals(body.get("active")) && body.get("reason") == null
                    ? CeLinkAccessResult.active(planCode)
                    : CeLinkAccessResult.notLinked();
        } catch (Exception e) {
            log.warn("Failed to validate CE link installId={} userId={}: {}",
                    installId, userId, e.getMessage());
            return CeLinkAccessResult.notLinked();
        }
    }

    /**
     * Cloud-side subscription check for the CE catalog relay. Resolves the plan
     * entitlements of the cloud account owning the given install's link. The
     * caller is the authenticated cloud user, mirroring
     * {@link #userOwnsActiveCeLink}. Fail-closed: any malformed id, transport
     * failure, or non-2xx response yields {@link CeLinkEntitlementsResult#none()}
     * ({@code planCode="__NONE__", hasSubscription=false}).
     */
    public CeLinkEntitlementsResult ceLinkEntitlements(String userId, String installId) {
        if (userId == null || userId.isBlank() || installId == null || installId.isBlank()) {
            return CeLinkEntitlementsResult.none();
        }
        try {
            UUID.fromString(installId);
        } catch (IllegalArgumentException invalidInstallId) {
            return CeLinkEntitlementsResult.none();
        }
        String url = baseUrl + "/api/internal/auth/ce-link/" + installId + "/entitlements";
        HttpEntity<Void> entity = new HttpEntity<>(buildHeaders(userId));
        try {
            ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                    url, HttpMethod.GET, entity,
                    new ParameterizedTypeReference<>() {});
            Map<String, Object> body = response.getBody();
            if (!response.getStatusCode().is2xxSuccessful() || body == null) {
                return CeLinkEntitlementsResult.none();
            }
            String planCode = body.get("planCode") instanceof String s && !s.isBlank()
                    ? s : CeLinkEntitlementsResult.NO_SUBSCRIPTION;
            boolean hasSubscription = Boolean.TRUE.equals(body.get("hasSubscription"));
            return new CeLinkEntitlementsResult(planCode, hasSubscription);
        } catch (Exception e) {
            log.warn("Failed to resolve CE link entitlements installId={} userId={}: {}",
                    installId, userId, e.getMessage());
            return CeLinkEntitlementsResult.none();
        }
    }

    // ========== Org Access Restrictions ==========

    /**
     * Gets the set of resource IDs that are restricted for a given org member.
     *
     * @param orgId        the organization ID
     * @param userId       the member's user ID
     * @param resourceType the resource type
     * @return set of restricted resource IDs (empty if admin or no restrictions)
     */
    public Set<String> getRestrictedResourceIds(String orgId, String userId, String resourceType) {
        String url = baseUrl + "/api/internal/auth/org-restrictions"
                + "?orgId=" + orgId + "&userId=" + userId + "&resourceType=" + resourceType;
        HttpEntity<Void> entity = new HttpEntity<>(buildHeaders(userId));
        try {
            ResponseEntity<Set<String>> response = restTemplate.exchange(
                    url, HttpMethod.GET, entity,
                    new ParameterizedTypeReference<>() {});
            Set<String> body = response.getBody();
            return body != null ? body : Set.of();
        } catch (Exception e) {
            log.error("Failed to query org restrictions for {}/{}/{}: {}",
                    orgId, userId, resourceType, e.getMessage());
            throw new IllegalStateException("Failed to query org restrictions", e);
        }
    }

    /**
     * Returns the resource IDs the member may not WRITE (delete/assign/modify) - any
     * restriction (DENY or READ-only) blocks writes.
     */
    public Set<String> getWriteRestrictedResourceIds(String orgId, String userId, String resourceType) {
        String url = baseUrl + "/api/internal/auth/org-restrictions/write"
                + "?orgId=" + orgId + "&userId=" + userId + "&resourceType=" + resourceType;
        HttpEntity<Void> entity = new HttpEntity<>(buildHeaders(userId));
        try {
            ResponseEntity<Set<String>> response = restTemplate.exchange(
                    url, HttpMethod.GET, entity,
                    new ParameterizedTypeReference<>() {});
            Set<String> body = response.getBody();
            return body != null ? body : Set.of();
        } catch (Exception e) {
            log.error("Failed to query write-restricted org resources for {}/{}/{}: {}",
                    orgId, userId, resourceType, e.getMessage());
            throw new IllegalStateException("Failed to query org write restrictions", e);
        }
    }

    /**
     * Add a restriction (deny access to a resource for a member).
     */
    public void restrictAccess(String orgId, String memberUserId, String resourceType,
                                String resourceId, String restrictedBy) {
        String url = baseUrl + "/api/internal/auth/org-restrictions";
        Map<String, String> body = Map.of(
                "orgId", orgId,
                "memberUserId", memberUserId,
                "resourceType", resourceType,
                "resourceId", resourceId,
                "restrictedBy", restrictedBy
        );
        HttpEntity<Map<String, String>> entity = new HttpEntity<>(body, buildHeaders(null));
        try {
            restTemplate.exchange(url, HttpMethod.POST, entity, Void.class);
        } catch (Exception e) {
            log.error("Failed to restrict access for {}/{}/{}/{}: {}",
                    orgId, memberUserId, resourceType, resourceId, e.getMessage());
            throw new IllegalStateException("Failed to restrict org resource access", e);
        }
    }

    /**
     * Remove a single restriction.
     */
    public void grantAccess(String orgId, String memberUserId, String resourceType, String resourceId) {
        String url = baseUrl + "/api/internal/auth/org-restrictions"
                + "?orgId=" + orgId + "&memberUserId=" + memberUserId
                + "&resourceType=" + resourceType + "&resourceId=" + resourceId;
        HttpEntity<Void> entity = new HttpEntity<>(buildHeaders(null));
        try {
            restTemplate.exchange(url, HttpMethod.DELETE, entity, Void.class);
        } catch (Exception e) {
            log.error("Failed to grant access for {}/{}/{}/{}: {}",
                    orgId, memberUserId, resourceType, resourceId, e.getMessage());
            throw new IllegalStateException("Failed to grant org resource access", e);
        }
    }

    /**
     * Bulk set restrictions for a member + resource type.
     * Replaces all existing restrictions with the new set.
     */
    public void setRestrictions(String orgId, String memberUserId, String resourceType,
                                 Set<String> restrictedIds, String restrictedBy) {
        setRestrictions(orgId, memberUserId, resourceType, restrictedIds, Map.of(), restrictedBy);
    }

    /**
     * Bulk set restrictions with per-resource permission levels
     * ({@code resourceId -> "DENY"|"READ"}). Ids in {@code restrictedIds} not present
     * in {@code permissionsById} default to DENY (legacy behaviour).
     */
    public void setRestrictions(String orgId, String memberUserId, String resourceType,
                                 Set<String> restrictedIds, Map<String, String> permissionsById,
                                 String restrictedBy) {
        String url = baseUrl + "/api/internal/auth/org-restrictions/bulk";
        Map<String, Object> body = Map.of(
                "orgId", orgId,
                "memberUserId", memberUserId,
                "resourceType", resourceType,
                "restrictedIds", restrictedIds,
                "permissions", permissionsById != null ? permissionsById : Map.of(),
                "restrictedBy", restrictedBy
        );
        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, buildHeaders(null));
        try {
            restTemplate.exchange(url, HttpMethod.PUT, entity, Void.class);
        } catch (Exception e) {
            log.error("Failed to set restrictions for {}/{}/{}: {}",
                    orgId, memberUserId, resourceType, e.getMessage());
            throw new IllegalStateException("Failed to set org resource restrictions", e);
        }
    }

    /**
     * Get all restrictions for a member (for UI display).
     */
    public List<OrgRestrictionDto> getMemberRestrictions(String orgId, String memberUserId) {
        String url = baseUrl + "/api/internal/auth/org-restrictions/member"
                + "?orgId=" + orgId + "&memberUserId=" + memberUserId;
        HttpEntity<Void> entity = new HttpEntity<>(buildHeaders(null));
        try {
            ResponseEntity<List<OrgRestrictionDto>> response = restTemplate.exchange(
                    url, HttpMethod.GET, entity,
                    new ParameterizedTypeReference<>() {});
            return response.getBody() != null ? response.getBody() : Collections.emptyList();
        } catch (Exception e) {
            log.warn("Failed to get member restrictions for {}/{}: {}",
                    orgId, memberUserId, e.getMessage());
            return Collections.emptyList();
        }
    }

    // ========== Display Name ==========

    /**
     * Returns the display name for a user, with in-memory caching (5 min TTL).
     * Returns null if the user has no display name configured.
     *
     * @param userId the user's provider ID (Keycloak sub)
     * @return the display name, or null if not found
     */
    /**
     * Resolve the user's default personal organization id. Post-V261, every
     * user-scoped row carries a non-null organization_id; cross-server CE→cloud
     * paths only know the user id (no X-Organization-ID header) so the cloud
     * resolves the customer's personal-default org server-side before stamping
     * receipts / notifications. Returns {@code null} if the user has no active
     * default-personal membership (degenerate state - onboarding always creates
     * one).
     */
    public String getDefaultOrganizationIdForUser(String userId) {
        if (userId == null || userId.isBlank()) return null;
        String url = baseUrl + "/api/internal/auth/users/" + userId + "/default-organization";
        HttpEntity<Void> entity = new HttpEntity<>(buildHeaders(userId));
        try {
            ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                    url, HttpMethod.GET, entity,
                    new ParameterizedTypeReference<>() {});
            Map<String, Object> body = response.getBody();
            return body != null ? (String) body.get("organizationId") : null;
        } catch (Exception e) {
            log.warn("Failed to fetch default org for user {}: {}", userId, e.getMessage());
            return null;
        }
    }

    /**
     * Resolve the user's platform roles as a CSV string (e.g. {@code "USER,ADMIN"}).
     * Reads the persisted store ({@code auth.user_roles}) server-side - the same
     * source the JWT role claims are built from.
     *
     * <p><b>Why:</b> the agent-cli MCP stdio bridge talks directly to agent-service
     * without going through the gateway, so no JWT validator injects an
     * {@code X-User-Roles} header. agent-service calls this at CLI session start to
     * stamp {@code __userRoles__} into the session credentials so admin-gated tools
     * (e.g. modifying a global skill) work on the bridge path.
     *
     * <p>Returns an empty string when the user has no roles, is unknown, or on
     * transport failure - callers treat all three as "not admin".
     *
     * @param userId the user's provider id (Keycloak sub) or numeric internal id
     * @return CSV of role names, or empty string
     */
    public String getUserRoles(String userId) {
        if (userId == null || userId.isBlank()) return "";
        String url = baseUrl + "/api/internal/auth/users/" + userId + "/roles";
        HttpEntity<Void> entity = new HttpEntity<>(buildHeaders(userId));
        try {
            ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                    url, HttpMethod.GET, entity,
                    new ParameterizedTypeReference<>() {});
            Map<String, Object> body = response.getBody();
            if (body == null) return "";
            Object roles = body.get("roles");
            if (roles instanceof List<?> list) {
                return list.stream()
                        .filter(Objects::nonNull)
                        .map(Object::toString)
                        .collect(Collectors.joining(","));
            }
            return "";
        } catch (Exception e) {
            log.warn("Failed to fetch roles for user {}: {}", userId, e.getMessage());
            return "";
        }
    }

    /**
     * Fetches the publisher identity snapshot ({@code displayName}, {@code email},
     * {@code avatarUrl}) for a user. Called by publication-service at publish /
     * republish time to freeze the publisher's current identity into the
     * publication row server-side - the frontend-supplied publisher fields are
     * untrusted and intentionally ignored downstream.
     *
     * <p>Returns {@code null} on transport failure so callers can fail-fast
     * without an in-process snapshot drift. Cache-bypass on purpose: this is a
     * one-shot read at publish, never on a hot path.
     */
    public PublisherProfileDto getPublisherProfile(String userId) {
        if (userId == null || userId.isBlank()) return null;
        String url = baseUrl + "/api/internal/auth/users/" + userId + "/publisher-profile";
        HttpEntity<Void> entity = new HttpEntity<>(buildHeaders(userId));
        try {
            ResponseEntity<PublisherProfileDto> response = restTemplate.exchange(
                    url, HttpMethod.GET, entity, PublisherProfileDto.class);
            return response.getBody();
        } catch (Exception e) {
            log.warn("Failed to fetch publisher profile for user {}: {}", userId, e.getMessage());
            return null;
        }
    }

    /**
     * Returns the auth user ids of every member of the given organization.
     * <p>Used by agent-service to validate that a human task assignee / reviewer
     * actually belongs to the task's workspace before stamping their id onto the
     * row and notifying them. Returns an <b>empty set</b> on transport failure or
     * a blank org - the caller treats "not in the returned set" as "reject",
     * which fails closed (a transient auth-service outage temporarily blocks new
     * human assignment but never lets a non-member be assigned/notified). Agent
     * assignment never hits this path, so it is unaffected.
     */
    public Set<String> getOrganizationMemberIds(String organizationId) {
        if (organizationId == null || organizationId.isBlank()) return Collections.emptySet();
        String url = baseUrl + "/api/internal/auth/organizations/" + organizationId + "/member-ids";
        HttpEntity<Void> entity = new HttpEntity<>(buildHeaders(null));
        try {
            ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                    url, HttpMethod.GET, entity, new ParameterizedTypeReference<>() {});
            Map<String, Object> body = response.getBody();
            Object ids = body != null ? body.get("userIds") : null;
            if (ids instanceof List<?> list) {
                Set<String> out = new HashSet<>(list.size());
                for (Object o : list) {
                    if (o != null && !o.toString().isBlank()) out.add(o.toString());
                }
                return out;
            }
            return Collections.emptySet();
        } catch (Exception e) {
            log.warn("Failed to fetch member ids for org {}: {}", organizationId, e.getMessage());
            return Collections.emptySet();
        }
    }

    public String getDisplayName(String userId) {
        if (userId == null || userId.isBlank()) return null;

        CachedUserSummary cached = displayNameCache.get(userId);
        if (cached != null && !cached.isExpired()) {
            return cached.displayName();
        }

        String url = baseUrl + "/api/internal/auth/users/" + userId + "/display-name";
        HttpEntity<Void> entity = new HttpEntity<>(buildHeaders(userId));
        try {
            ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                    url, HttpMethod.GET, entity,
                    new ParameterizedTypeReference<>() {});
            Map<String, Object> body = response.getBody();
            String displayName = body != null ? (String) body.get("displayName") : null;
            // avatarUrl stays null on the single-user path - the
            // /users/{id}/display-name endpoint doesn't return it. A subsequent
            // batch call upgrades the entry in place when the user is part of
            // the batch input.
            displayNameCache.put(userId, new CachedUserSummary(displayName, null, System.currentTimeMillis()));
            return displayName;
        } catch (Exception e) {
            log.warn("Failed to fetch display name for user {}: {}", userId, e.getMessage());
            return null;
        }
    }

    /**
     * Resolve a batch of user ids to {@link UserSummaryDto}
     * ({@code displayName} + {@code avatarUrl}). Cache-aware: hits the shared
     * {@link #displayNameCache} first, only un-cached ids hit auth-service in
     * a single POST {@code /api/internal/auth/users/resolve-batch} call.
     *
     * <p>Returns a non-null map for every input id (unknown / failed lookups
     * map to a {@code UserSummaryDto} with {@code displayName=null}). Callers
     * that need a "not present" signal should check
     * {@link UserSummaryDto#displayName()} for null.
     *
     * <p>Used by orchestrator's {@code RecentActivityAggregatorService} to
     * resolve {@code created_by} attribution for ~50 rows in a single RPC
     * (replaces the per-row N+1 that a {@link #getDisplayName} loop would
     * produce).
     *
     * <p><b>Cache write semantics</b>: caches null entries too (matches
     * {@link #getDisplayName}'s line-249 behavior). A user that was deleted
     * after creating a workflow stays cached as null for 5 min, avoiding
     * pounding auth-service on a known-missing id.
     */
    public Map<String, UserSummaryDto> batchResolveUsers(Set<String> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return Collections.emptyMap();
        }

        Map<String, UserSummaryDto> result = new HashMap<>(userIds.size());
        Set<String> toFetch = new HashSet<>();
        long now = System.currentTimeMillis();

        for (String id : userIds) {
            if (id == null || id.isBlank()) continue;
            CachedUserSummary cached = displayNameCache.get(id);
            if (cached != null && !cached.isExpired()) {
                result.put(id, new UserSummaryDto(id, cached.displayName(), cached.avatarUrl()));
            } else {
                toFetch.add(id);
            }
        }

        if (toFetch.isEmpty()) {
            return result;
        }

        String url = baseUrl + "/api/internal/auth/users/resolve-batch";
        HttpEntity<List<String>> entity = new HttpEntity<>(new ArrayList<>(toFetch), buildHeaders(null));
        try {
            // Bounded template - see field javadoc. Caller is the aggregator
            // running synchronously post-fan-out; cannot tolerate an open
            // read timeout.
            ResponseEntity<Map<String, UserSummaryDto>> response = boundedRestTemplate.exchange(
                    url, HttpMethod.POST, entity,
                    new ParameterizedTypeReference<>() {});
            Map<String, UserSummaryDto> fetched = response.getBody();
            if (fetched != null) {
                for (Map.Entry<String, UserSummaryDto> e : fetched.entrySet()) {
                    UserSummaryDto dto = e.getValue();
                    String dn = dto != null ? dto.displayName() : null;
                    String av = dto != null ? dto.avatarUrl() : null;
                    // Cache nulls too - matches getDisplayName's semantics so
                    // repeat lookups for known-missing users don't pound
                    // auth-service for the TTL window.
                    displayNameCache.put(e.getKey(), new CachedUserSummary(dn, av, now));
                    result.put(e.getKey(), dto != null ? dto : new UserSummaryDto(e.getKey(), null, null));
                }
            }
        } catch (Exception e) {
            log.warn("Failed to batch-resolve {} user ids: {} - returning name-less entries for un-fetched",
                    toFetch.size(), e.getMessage());
        }

        // Defensive: every input id MUST have an entry in the response map so
        // downstream enrichment code can use a fixed-key lookup pattern. Ids
        // not returned by auth-service (or absent due to fetch failure) get a
        // null-name placeholder. NOT cached negative - a transient
        // auth-service error shouldn't poison the cache for 5 min.
        for (String id : toFetch) {
            result.putIfAbsent(id, new UserSummaryDto(id, null, null));
        }

        return result;
    }

    // ========== Helpers ==========

    /**
     * Fetches the badge evaluator's view of a user: when the account was created
     * and whether its public profile page exists.
     *
     * <p>Returns {@code null} when auth-service answers 404 (unknown or disabled
     * user) OR on any transport failure - the two are deliberately merged
     * because both callers treat null the same way. The evaluator skips the
     * cohort / tenure metrics for that pass (those badges unlock on the next
     * one), and the public badge endpoint answers 404, which is also the right
     * response for a genuinely missing user.
     */
    public BadgeProfileDto getBadgeProfile(String userId) {
        if (userId == null || userId.isBlank()) return null;
        String url = baseUrl + "/api/internal/auth/users/" + userId + "/badge-profile";
        HttpEntity<Void> entity = new HttpEntity<>(buildHeaders(userId));
        try {
            ResponseEntity<BadgeProfileDto> response = boundedRestTemplate.exchange(
                    url, HttpMethod.GET, entity, BadgeProfileDto.class);
            return response.getBody();
        } catch (Exception e) {
            log.warn("Failed to fetch badge profile for user {}: {}", userId, e.getMessage());
            return null;
        }
    }

    // ========== Locale context ==========

    /**
     * The language and time zone a user reads messages in.
     *
     * @param locale   the app locale reported by the user's browser or picked in the UI, {@code en} when unknown
     * @param timeZone an IANA zone id, {@code UTC} when unknown
     * @param fallback true when auth-service could not answer (unknown user, unreachable, bad body):
     *                 the two values are then the defaults, not the user's, and a caller that
     *                 caches should not keep them
     */
    public record LocaleContext(String locale, String timeZone, boolean fallback) {
        public static final LocaleContext FALLBACK = new LocaleContext("en", "UTC", true);
    }

    /**
     * Reads a user's locale and time zone, for the messages sent to them off-request (emails,
     * chat notices). Never throws: any failure answers {@link LocaleContext#FALLBACK}, so a
     * message goes out in English with UTC times rather than not at all.
     */
    public LocaleContext getLocaleContext(String userId) {
        if (userId == null || userId.isBlank()) return LocaleContext.FALLBACK;
        String url = baseUrl + "/api/internal/auth/users/" + userId.trim() + "/locale-context";
        try {
            ResponseEntity<Map<String, Object>> response = boundedRestTemplate.exchange(
                    url, HttpMethod.GET, new HttpEntity<>(buildHeaders(userId)),
                    new ParameterizedTypeReference<>() {});
            Map<String, Object> body = response.getBody();
            if (body == null) return LocaleContext.FALLBACK;
            Object locale = body.get("locale");
            Object timeZone = body.get("timeZone");
            return new LocaleContext(
                    locale instanceof String l && !l.isBlank() ? l : "en",
                    timeZone instanceof String z && !z.isBlank() ? z : "UTC",
                    false);
        } catch (Exception e) {
            log.debug("Locale context for user {} unavailable: {}", userId, e.getMessage());
            return LocaleContext.FALLBACK;
        }
    }

    // ========== Notification email ==========

    /**
     * Outcome of a notification email: {@code SENT}, {@code NO_ADDRESS} (the
     * account has no usable email) or {@code FAILED} (SMTP refused it, or
     * auth-service could not be reached), with a short reason.
     */
    public record NotificationMailResult(String status, String detail) {
        public boolean sent() {
            return "SENT".equals(status);
        }
    }

    /**
     * Asks auth-service, which owns the address and the mailer, to email one
     * notification to a user. Synchronous on purpose, so the caller can record
     * whether it really went out; callers run it off the request path.
     *
     * <p>Its own timeouts: the SMTP relay alone may take up to three 10 s
     * windows, which the 3 s read timeout of the bounded template would cut off
     * while the mail was still being sent.
     *
     * @param actionPath in-app path starting with {@code /}; auth-service prefixes the public origin
     */
    public NotificationMailResult sendNotificationMail(String userId, String subject, List<String> lines,
                                                       String actionPath, String actionLabel) {
        String url = baseUrl + "/api/internal/auth/notification-mail";
        Map<String, Object> body = new HashMap<>();
        body.put("userId", userId);
        body.put("subject", subject);
        body.put("lines", lines);
        body.put("actionPath", actionPath);
        body.put("actionLabel", actionLabel);
        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, buildHeaders(userId));
        try {
            ResponseEntity<Map<String, Object>> response = mailRestTemplate.exchange(
                    url, HttpMethod.POST, entity, new ParameterizedTypeReference<>() {});
            Map<String, Object> out = response.getBody();
            if (out == null) return new NotificationMailResult("FAILED", "empty response");
            Object status = out.get("status");
            Object detail = out.get("detail");
            return new NotificationMailResult(status != null ? status.toString() : "FAILED",
                    detail != null ? detail.toString() : null);
        } catch (Exception e) {
            log.warn("Notification mail for user={} could not be handed to auth-service: {}", userId, e.getMessage());
            return new NotificationMailResult("FAILED", e.getClass().getSimpleName());
        }
    }

    private static RestTemplate createMailRestTemplate() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(3));
        factory.setReadTimeout(Duration.ofSeconds(40));
        return new RestTemplate(factory);
    }

    private static HttpHeaders buildHeaders(String tenantId) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Content-Type", "application/json");
        if (tenantId != null) {
            headers.set("X-User-ID", tenantId);
        }
        // PR16 - forward X-Organization-ID / X-Organization-Role from the
        // current inbound request so downstream auth-service operations
        // (credential read, org-aware quota check, etc.) see the active
        // workspace. No-op for async/scheduled paths with no request context.
        OrgContextHeaderForwarder.forward(headers);
        return headers;
    }

}
