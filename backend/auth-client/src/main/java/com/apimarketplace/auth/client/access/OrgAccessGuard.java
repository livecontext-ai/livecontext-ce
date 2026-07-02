package com.apimarketplace.auth.client.access;

import com.apimarketplace.auth.client.dto.OrgRestrictionDto;

import java.util.List;
import java.util.Set;
import java.util.function.Function;

/**
 * Org-level deny-list access control guard.
 *
 * <p>Members of an org see all org resources by default. OWNER/ADMIN can restrict
 * specific members from specific resources. OWNER/ADMIN always bypass all
 * restrictions.
 *
 * <p>This guard is the canonical entry point for both <b>read filtering</b>
 * (lists, single fetches) and <b>write enforcement</b> (delete, status toggle,
 * clone, diff-sensitive update - see PR-2). Each consuming service injects this
 * single bean instead of maintaining its own copy.
 *
 * <p><b>orgRole resolution:</b> the {@code orgRole} argument MUST be sourced
 * from the {@code X-Organization-Role} header, which the gateway strips and
 * re-injects from a JWT-validated claim ({@code AuthenticationFilter}). Callers
 * must never read it from a raw client header.
 *
 * <p>Persistence is delegated to auth-service via {@code AuthClient} HTTP. Read
 * queries are authoritative by default; an optional positive-TTL in-process cache
 * can be enabled with EventBus invalidation in controlled deployments.
 *
 * <h3>Behavioural deltas vs the pre-factorisation per-service copies</h3>
 * The four prior {@code OrgAccessService} copies (orchestrator, agent, datasource,
 * interface) shared the bulk of their logic but differed on a few null-argument
 * paths. The factorised implementation tightens those edges and is documented
 * here so that callers can rely on a single contract:
 * <ul>
 *   <li>{@link #canAccess} with {@code resourceId == null} <b>always</b> returns
 *       {@code false}, including for OWNER/ADMIN. The orchestrator copy previously
 *       returned {@code true} because {@code Set.contains(null)} was {@code false}.
 *       New contract: null resourceId is fail-fast deny (defensive against caller
 *       bugs). No production caller passes {@code null}.</li>
 *   <li>{@link #filterAccessible} with {@code items == null} returns {@code null}
 *       instead of throwing {@link NullPointerException}. Empty input
 *       short-circuits without an {@code AuthClient} call (cheap optimisation).</li>
 *   <li>{@link #getRestrictedResourceIds} with any of {@code orgId / userId /
 *       resourceType} being {@code null} returns an empty set without contacting
 *       {@code AuthClient}. The prior copies would have forwarded the nulls and
 *       relied on the auth-service controller to 400.</li>
 * </ul>
 */
public interface OrgAccessGuard {

    /**
     * Return the set of resource IDs that are restricted for a given org member.
     * OWNER/ADMIN always sees no restrictions.
     *
     * @param orgId        organization ID
     * @param userId       member user ID
     * @param resourceType canonical resource type (workflow, agent, datasource, interface, project,
     *                     file, application, skill)
     * @param orgRole      member's role in this org (OWNER, ADMIN, MEMBER, VIEWER) - sourced from
     *                     gateway-validated {@code X-Organization-Role} header
     * @return set of restricted resource IDs (empty for OWNER/ADMIN)
     */
    Set<String> getRestrictedResourceIds(String orgId, String userId, String resourceType, String orgRole);

    /**
     * Check whether a member can access (READ) a specific resource - a READ-only
     * restriction still passes, only a DENY restriction blocks.
     */
    boolean canAccess(String orgId, String userId, String resourceType, String resourceId, String orgRole);

    /**
     * Set of resource IDs the member may not WRITE (delete / assign / modify) - ANY
     * restriction (DENY or READ-only) blocks writes. Empty for OWNER/ADMIN.
     */
    Set<String> getWriteRestrictedResourceIds(String orgId, String userId, String resourceType, String orgRole);

    /**
     * Check whether a member can WRITE (delete / assign / modify) a specific resource.
     * Returns false if the resource carries ANY restriction (DENY or READ-only).
     * OWNER/ADMIN always return true.
     *
     * <p><b>VIEWER is read-only at the role level:</b> when {@code orgId} is present
     * (org workspace) and {@code orgRole} is VIEWER, this returns {@code false}
     * regardless of per-resource restrictions. Historically this boundary was
     * enforced only by ad-hoc {@code isViewerRole} checks copy-pasted per
     * controller, which left ungated endpoints writable by VIEWER (e.g. workflow
     * delete/save/restore). The guard is now the single source of truth; the
     * remaining per-controller checks are an earlier, clearer 403 for the same
     * decision.
     */
    boolean canWrite(String orgId, String userId, String resourceType, String resourceId, String orgRole);

    /**
     * Role-level write gate, for call sites that have no per-resource id: creation
     * endpoints, and bulk mutations driven by the {@link #getWriteRestrictedResourceIds}
     * exclusion set (which cannot express "everything is restricted" for a VIEWER).
     * True when the caller is in an org workspace ({@code orgId} present) with the
     * read-only VIEWER role. {@link #canWrite} applies this internally.
     */
    static boolean isRoleWriteBlocked(String orgId, String orgRole) {
        return orgId != null && !orgId.isBlank()
                && orgRole != null && "VIEWER".equalsIgnoreCase(orgRole.trim());
    }

    /**
     * Filter a list of resources, removing the restricted ones.
     * OWNER/ADMIN passes through unchanged.
     */
    <T> List<T> filterAccessible(List<T> items, String orgId, String userId,
                                  String resourceType, String orgRole,
                                  Function<T, String> idExtractor);

    /**
     * Persist a single restriction (deny a member from a resource).
     */
    void restrictAccess(String orgId, String memberUserId, String resourceType,
                        String resourceId, String restrictedBy);

    /**
     * Remove a single restriction (grant access back).
     */
    void grantAccess(String orgId, String memberUserId, String resourceType, String resourceId);

    /**
     * Bulk replace all restrictions for a (member, resourceType) pair (all DENY).
     */
    void setRestrictions(String orgId, String memberUserId, String resourceType,
                         Set<String> restrictedIds, String restrictedBy);

    /**
     * Bulk replace all restrictions for a (member, resourceType) pair, each with a
     * permission level ({@code resourceId -> "DENY"|"READ"}). Ids not present in the
     * map default to DENY.
     */
    void setRestrictions(String orgId, String memberUserId, String resourceType,
                         Set<String> restrictedIds, java.util.Map<String, String> permissionsById,
                         String restrictedBy);

    /**
     * List all restrictions for a member (UI display).
     */
    List<OrgRestrictionDto> getMemberRestrictions(String orgId, String memberUserId);

    /**
     * Invalidate cache entries for a specific (orgId, userId) pair.
     * Call after a restrictAccess / grantAccess / setRestrictions mutation.
     */
    void invalidateCache(String orgId, String userId);

    /**
     * Invalidate every cache entry for an org. Use sparingly (org-wide role
     * change, org soft-deleted).
     */
    void invalidateCacheForOrg(String orgId);
}
