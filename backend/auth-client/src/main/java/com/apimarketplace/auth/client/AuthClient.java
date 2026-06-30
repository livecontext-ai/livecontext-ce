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

import com.apimarketplace.auth.client.dto.OrgRestrictionDto;
import com.apimarketplace.auth.client.dto.PublisherProfileDto;
import com.apimarketplace.common.auth.UserSummaryDto;

import java.time.Duration;
import java.util.ArrayList;
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
     * Cloud-side authorization check for CE LLM relay calls. The caller is the
     * authenticated cloud user and the install id is the CE instance presenting
     * the bearer token. Returns false on any malformed id or transport failure.
     */
    public boolean userOwnsActiveCeLink(String userId, String installId) {
        if (userId == null || userId.isBlank() || installId == null || installId.isBlank()) {
            return false;
        }
        try {
            UUID.fromString(installId);
        } catch (IllegalArgumentException invalidInstallId) {
            return false;
        }
        String url = baseUrl + "/api/internal/auth/ce-link/" + installId + "/active";
        HttpEntity<Void> entity = new HttpEntity<>(buildHeaders(userId));
        try {
            ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                    url, HttpMethod.GET, entity,
                    new ParameterizedTypeReference<>() {});
            Map<String, Object> body = response.getBody();
            return response.getStatusCode().is2xxSuccessful()
                    && body != null
                    && Boolean.TRUE.equals(body.get("active"));
        } catch (Exception e) {
            log.warn("Failed to validate CE link installId={} userId={}: {}",
                    installId, userId, e.getMessage());
            return false;
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
