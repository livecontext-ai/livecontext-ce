package com.apimarketplace.common.scope;

/**
 * Single source of truth for the multi-org isolation scope predicate. Public
 * auth-required code paths MUST funnel every read/write authorization through
 * {@link #isInStrictScope(String, String, String, String)}; internal channels
 * that intentionally allow owner-OR-org tolerance MUST use
 * {@link #isInOwnerOrOrgScope(String, String, String, String)} on a method
 * annotated with {@link TolerantScope}.
 *
 * <p>Aligned with the canonical strict-isolation pattern shipped in
 * {@code ConversationQueryService#getConversationById} (PR21) and
 * {@code MessageService#isConversationInScope} (2026-05-18 fix). Replacing the
 * scattered {@code ownerMatch || orgMatch} predicates with calls to this
 * helper closes the cross-workspace read/write leak where a caller currently
 * in OrgA workspace could still touch their personal-owned row because their
 * userId matched the row's tenantId regardless of caller's active workspace.
 *
 * <p>Pure stateless utility - no Spring bean, no DB lookup, no logging. Lives
 * in {@code common-lib} so every service has it on the classpath without an
 * additional dependency.
 */
public final class ScopeGuard {

    private ScopeGuard() {
        throw new AssertionError("ScopeGuard is a utility class; do not instantiate.");
    }

    /**
     * Strict-isolation scope predicate. Returns {@code true} iff the row is in
     * the caller's currently-active workspace:
     * <ul>
     *   <li>If {@code callerOrgId} is set (caller is in an org workspace) →
     *       row's {@code organization_id} MUST equal {@code callerOrgId}. The
     *       row's owning user is irrelevant; org workspace is shared between
     *       members.</li>
     *   <li>If {@code callerOrgId} is {@code null} or blank (caller is in
     *       personal workspace) → row MUST be owned by the caller AND have
     *       {@code organization_id IS NULL}. A row tagged with any org is
     *       hidden from personal scope even if the caller created it.</li>
     * </ul>
     *
     * <p>Use this from controllers and services that mutate or expose data
     * keyed by a resource id passed by the user. Out-of-scope rows MUST map
     * to a 404 (not 403) so the row's existence is not leaked across
     * workspace boundaries.
     *
     * @param callerUserId caller's user id (from the gateway-injected
     *                     {@code X-User-ID} header)
     * @param callerOrgId  caller's active workspace org id (from the
     *                     gateway-injected {@code X-Organization-ID} header),
     *                     {@code null} or blank for personal workspace
     * @param entityTenantId the row's owner user id (tenant_id column on the
     *                       entity)
     * @param entityOrgId    the row's organization id ({@code null} when the
     *                       row belongs to personal scope)
     * @return {@code true} iff the row is in the caller's active scope
     */
    public static boolean isInStrictScope(
            String callerUserId,
            String callerOrgId,
            String entityTenantId,
            String entityOrgId) {
        if (callerOrgId != null && !callerOrgId.isBlank()) {
            return callerOrgId.equals(entityOrgId);
        }
        return callerUserId != null
                && !callerUserId.isBlank()
                && callerUserId.equals(entityTenantId)
                && entityOrgId == null;
    }

    /**
     * Tolerant (owner-OR-org) scope predicate. Returns {@code true} if the
     * caller is either the row's owner OR a member of the row's tagged
     * organization, regardless of which workspace the caller is currently
     * viewing.
     *
     * <p><b>RESERVED for internal channels</b> where the caller's authority
     * to touch the row has already been established upstream (e.g. agent
     * execution running with a resolved service identity, gateway
     * {@code ChannelAuthorizer} resolving WS subscriptions before delivery).
     * Public auth-required HTTP endpoints MUST use
     * {@link #isInStrictScope(String, String, String, String)} instead.
     *
     * <p><b>Intentional surface area:</b> the {@code ownerMatch} branch
     * short-circuits on {@code callerUserId == entityTenantId} regardless
     * of whether {@code entityOrgId} is set. So a personal-scope caller
     * MAY match an org-tagged row they own, and an org-scope caller MAY
     * match their personal row. This is by design - internal callers do
     * not flip workspaces and need cross-workspace authority. Strict
     * isolation is the responsibility of {@link #isInStrictScope}.
     *
     * <p>Every call site MUST be on a method (or enclosing type) annotated
     * with {@link TolerantScope} documenting WHY tolerance is intentional.
     * Each service that hosts tolerant call sites ships an
     * {@code OrgScopePredicateInvariantTest} ArchUnit rule asserting (a) no
     * hand-rolled scope predicate exists and (b) every
     * {@code isInOwnerOrOrgScope} caller carries {@link TolerantScope}; the
     * reference implementation lives in {@code orchestrator-service}
     * ({@code archunit/OrgScopePredicateInvariantTest.java}) and other
     * services mirror it. ArchUnit only scans the test classpath of its host
     * module, so per-service mirroring is required.
     *
     * @see #isInStrictScope(String, String, String, String)
     * @see TolerantScope
     */
    public static boolean isInOwnerOrOrgScope(
            String callerUserId,
            String callerOrgId,
            String entityTenantId,
            String entityOrgId) {
        boolean ownerMatch = callerUserId != null
                && !callerUserId.isBlank()
                && callerUserId.equals(entityTenantId);
        boolean orgMatch = callerOrgId != null
                && !callerOrgId.isBlank()
                && callerOrgId.equals(entityOrgId);
        return ownerMatch || orgMatch;
    }

    /**
     * Cross-resource workspace match - compares the workspace of one entity
     * (parent) against another (child) to decide whether the dispatch may
     * cross between them. Returns {@code true} iff both are personal (both
     * org NULL) OR both are tagged with the same org.
     *
     * <p>Reserved for dispatch services that fan-out events from one entity
     * to another (webhook token → pinned run, datasource event → workflow,
     * upstream workflow completion → downstream trigger, etc.). NOT a
     * caller-vs-entity scope check - use {@link #isInStrictScope} for that.
     *
     * <p>Replaces the inline {@code parent != null ? parent.equals(child)
     * : child == null} pattern that was hand-rolled in 8 dispatch sites
     * before this helper landed.
     *
     * @param parentOrg organization id of the originating entity (e.g.
     *                  webhook token's org, datasource's org, upstream
     *                  workflow's org). Post-V263, USER_SCOPED parents
     *                  are non-null; NULL is only valid for the few
     *                  globally-scoped catalog/platform_credentials tables.
     * @param childOrg  organization id of the destination entity (typically
     *                  the pinned run's org). Post-V263 USER_SCOPED children
     *                  are non-null. NULL still legal for global entities.
     * @return {@code true} iff the two workspaces match (both NULL or
     *         equal strings)
     */
    public static boolean crossResourceMatches(String parentOrg, String childOrg) {
        if (parentOrg == null) return childOrg == null;
        return parentOrg.equals(childOrg);
    }
}
