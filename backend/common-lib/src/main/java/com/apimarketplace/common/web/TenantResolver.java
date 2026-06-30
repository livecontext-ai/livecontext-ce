package com.apimarketplace.common.web;

import jakarta.servlet.http.HttpServletRequest;

import java.util.Optional;

/**
 * Single source of truth for tenant ID resolution from HTTP requests.
 * Extracts tenant ID from X-User-ID header (injected by Gateway from JWT).
 *
 * <p>Provides two API families for backward compatibility:</p>
 * <ul>
 *   <li><b>Family A</b> (orchestrator/datasource style): {@link #resolve}, {@link #resolveOptional},
 *       {@link #resolveOrganizationId}, {@link #resolveOrganizationRole} - throws {@link TenantRequiredException},
 *       returns {@link Optional}.</li>
 *   <li><b>Family B</b> (agent/interface/trigger style): {@link #resolveOrgId}, {@link #resolveOrgRole}
 *       - returns nullable String.</li>
 * </ul>
 */
public class TenantResolver {

    private static final String HEADER_USER_ID = "X-User-ID";
    private static final String HEADER_ORGANIZATION_ID = "X-Organization-ID";
    private static final String HEADER_ORGANIZATION_ROLE = "X-Organization-Role";

    /**
     * Extracts tenant ID from X-User-ID header.
     *
     * @param request The HTTP request
     * @return The tenant ID
     * @throws TenantRequiredException if header is missing or blank
     */
    public String resolve(HttpServletRequest request) {
        String userIdHeader = request.getHeader(HEADER_USER_ID);
        if (userIdHeader == null || userIdHeader.isBlank()) {
            throw new TenantRequiredException("X-User-ID header is required");
        }
        return userIdHeader;
    }

    /**
     * Optional extraction - returns Optional.empty() if header is missing.
     *
     * @param request The HTTP request
     * @return Optional containing tenant ID, or empty if not present
     */
    public Optional<String> resolveOptional(HttpServletRequest request) {
        String userIdHeader = request.getHeader(HEADER_USER_ID);
        if (userIdHeader != null && !userIdHeader.isBlank()) {
            return Optional.of(userIdHeader);
        }
        return Optional.empty();
    }

    /**
     * Extracts tenant ID from the gateway-injected X-User-ID header.
     * Returns {@code null} when the header is absent so callers can fail-closed
     * via {@link #validate(String)} (401/400) or fall through to a default.
     *
     * <p>Header is the only source of truth - the previous {@code fallbackParam}
     * argument was a Bug-#4 surface (client-supplied identity) closed by
     * round-4 audit 2026-05-17 and removed entirely.
     *
     * @param request The HTTP request
     * @return The tenant ID from header, or null when absent
     */
    public String resolveOrNull(HttpServletRequest request) {
        String userIdHeader = request.getHeader(HEADER_USER_ID);
        if (userIdHeader != null && !userIdHeader.isBlank()) {
            return userIdHeader;
        }
        return null;
    }

    /**
     * Validates that a tenant ID is not null or blank.
     *
     * @param tenantId The tenant ID to validate
     * @throws TenantRequiredException if tenant ID is null or blank
     */
    public void validate(String tenantId) {
        if (tenantId == null || tenantId.isBlank()) {
            throw new TenantRequiredException("Tenant ID cannot be null or empty");
        }
    }

    /**
     * Extracts organization ID from X-Organization-ID header (Family A - Optional).
     */
    public Optional<String> resolveOrganizationId(HttpServletRequest request) {
        String orgId = request.getHeader(HEADER_ORGANIZATION_ID);
        return (orgId != null && !orgId.isBlank()) ? Optional.of(orgId) : Optional.empty();
    }

    /**
     * Extracts organization role from X-Organization-Role header (Family A - Optional).
     */
    public Optional<String> resolveOrganizationRole(HttpServletRequest request) {
        String role = request.getHeader(HEADER_ORGANIZATION_ROLE);
        return (role != null && !role.isBlank()) ? Optional.of(role) : Optional.empty();
    }

    // --- Family B aliases (nullable String, for agent/interface/trigger callers) ---

    /**
     * Extracts organization ID, returns null if missing (Family B alias).
     */
    public String resolveOrgId(HttpServletRequest request) {
        return resolveOrganizationId(request).orElse(null);
    }

    /**
     * Extracts organization role, returns null if missing (Family B alias).
     */
    public String resolveOrgRole(HttpServletRequest request) {
        return resolveOrganizationRole(request).orElse(null);
    }

    /**
     * Pulls X-Organization-ID from the current request via Spring's
     * {@code RequestContextHolder} - no HttpServletRequest argument needed.
     * Returns null on @Async / @Scheduled / daemon threads where no servlet
     * request is bound; callers fall through to personal scope (the strict
     * isolation default for un-tagged executions).
     *
     * <p>Phase 3 MIGRATION_ORG_ID_NOT_NULL.md (2026-05-19) - falls back to the
     * thread-local set by {@link #runWithOrgScope(String, Runnable)} when no
     * servlet request is bound. This closes the V261 NOT NULL gap on async
     * paths (Quartz daemons, raw {@code Executors.newFixedThreadPool} workers
     * in AgentQueueWorkerService) where the producer captured the orgId at
     * enqueue time and the consumer must restore it before running the task.
     *
     * <p>Used by service-layer paths that cannot inject HttpServletRequest
     * (MCP tool modules, persist hooks, recurrence/skill creators). PR16
     * forwarders ensure cross-service HTTP hops carry the header so this
     * resolves correctly even for sub-agent-initiated dispatches.
     */
    public static String currentRequestOrganizationId() {
        try {
            org.springframework.web.context.request.RequestAttributes attrs =
                org.springframework.web.context.request.RequestContextHolder.getRequestAttributes();
            if (attrs instanceof org.springframework.web.context.request.ServletRequestAttributes sra) {
                String orgId = sra.getRequest().getHeader(HEADER_ORGANIZATION_ID);
                if (orgId != null && !orgId.isBlank()) {
                    return orgId;
                }
            }
        } catch (Exception ignored) {
            // fall through to thread-local fallback
        }
        return ASYNC_ORG_SCOPE.get();
    }

    // Phase 3 MIGRATION_ORG_ID_NOT_NULL.md - thread-local fallback for async paths.
    // Bound by runWithOrgScope() at the start of a queue/daemon task, read by
    // currentRequestOrganizationId() and the OrgScopedEntityListener filet.
    private static final ThreadLocal<String> ASYNC_ORG_SCOPE = new ThreadLocal<>();

    // Same shape as ASYNC_ORG_SCOPE but for the workspace role (OWNER/ADMIN/MEMBER).
    // Bound by runWithOrgScope(orgId, orgRole, task) variant + read by
    // currentRequestOrganizationRole(). 2026-05-21 follow-up to the "Access to
    // this agent is restricted" prod incident: AgentService.updateAgent called
    // canAccess(...,null) → deny-list rejected the owner because role wasn't
    // threaded through. The fix at the source method now reads via this
    // ThreadLocal + servlet header fallback below.
    private static final ThreadLocal<String> ASYNC_ORG_ROLE = new ThreadLocal<>();

    /**
     * Mirror of {@link #currentRequestOrganizationId} for the {@code X-User-ID}
     * header. Returns the tenant/user ID of the in-flight request, or null on
     * async/scheduler threads where no servlet request is bound (callers fall
     * back to platform-scoped resolution downstream).
     *
     * <p>Used by lib-side singletons that cannot inject {@code HttpServletRequest}
     * but still need the current user - e.g.
     * {@link com.apimarketplace.agent.credential.LlmCredentialRepository}'s
     * user-first credential lookup. There is no async-thread fallback here:
     * user-scoped credentials should not bleed across enqueue/dequeue boundaries
     * (unlike org scope, which is preserved by {@link #runWithOrgScope} for
     * async paths that captured an org tag at enqueue time).
     */
    public static String currentRequestUserId() {
        try {
            org.springframework.web.context.request.RequestAttributes attrs =
                org.springframework.web.context.request.RequestContextHolder.getRequestAttributes();
            if (attrs instanceof org.springframework.web.context.request.ServletRequestAttributes sra) {
                String userId = sra.getRequest().getHeader(HEADER_USER_ID);
                if (userId != null && !userId.isBlank()) {
                    return userId;
                }
            }
        } catch (Exception ignored) {
            // No request bound - return null so callers fall back to platform scope.
        }
        return null;
    }

    /**
     * Mirror of {@link #currentRequestOrganizationId} for the workspace role
     * header ({@code X-Organization-Role}). Returns null on threads with neither
     * a bound servlet request nor an active {@link #runWithOrgScope} ROLE binding.
     *
     * <p>Callers that pass this value to {@code OrgAccessGuard.canAccess(...,
     * orgRole)} or {@code AgentService.update/clone/delete} can rely on the
     * full source-of-truth chain: gateway injects {@code X-Organization-Role}
     * (validated against the user's membership), servlet filters bind it via
     * {@code RequestContextHolder}, async wraps preserve it via
     * {@link #runWithOrgScopeWithRole}. Returning null is treated by
     * downstream deny-list checks as "unknown role" - for owner-restricted
     * surfaces the call falls through to a conservative deny, which is the
     * 2026-05-21 prod bug we're closing.
     */
    public static String currentRequestOrganizationRole() {
        try {
            org.springframework.web.context.request.RequestAttributes attrs =
                org.springframework.web.context.request.RequestContextHolder.getRequestAttributes();
            if (attrs instanceof org.springframework.web.context.request.ServletRequestAttributes sra) {
                String role = sra.getRequest().getHeader(HEADER_ORGANIZATION_ROLE);
                if (role != null && !role.isBlank()) {
                    return role;
                }
            }
        } catch (Exception ignored) {
            // fall through to thread-local fallback
        }
        return ASYNC_ORG_ROLE.get();
    }

    /**
     * Binds {@code orgId} to a thread-local for the duration of {@code task},
     * so {@link #currentRequestOrganizationId()} can resolve it on threads that
     * have no servlet request bound. Use from async queue workers, Quartz job
     * methods, raw {@code Executors} thread bodies - anywhere
     * {@code RequestContextHolder} is empty but the producer captured an org.
     *
     * <p>Nested calls stack via prev/restore. Empty/null {@code orgId} clears
     * the binding (equivalent to "no scope" - caller falls back to personal
     * default downstream).
     */
    /**
     * Post-V261 (2026-05-19) - single source of truth for the
     * "{@code organizationId} required after V261" guard. Throws
     * {@link IllegalArgumentException} when the input is null or blank;
     * returns the input string otherwise so callers can chain.
     *
     * <p>Replaces ~83 inline copies of the same null-or-blank check across
     * services. Centralizing here means a future error-message tweak
     * (e.g. "tenantId required after V261" → "workspace context required")
     * is a one-file change.
     */
    public static String requireOrgId(String organizationId) {
        if (organizationId == null || organizationId.isBlank()) {
            throw new IllegalArgumentException("organizationId required after V261");
        }
        return organizationId;
    }

    public static void runWithOrgScope(String orgId, Runnable task) {
        String prev = ASYNC_ORG_SCOPE.get();
        try {
            if (orgId != null && !orgId.isBlank()) {
                ASYNC_ORG_SCOPE.set(orgId);
            } else {
                ASYNC_ORG_SCOPE.remove();
            }
            task.run();
        } finally {
            if (prev != null) {
                ASYNC_ORG_SCOPE.set(prev);
            } else {
                ASYNC_ORG_SCOPE.remove();
            }
        }
    }

    /**
     * Bind both orgId AND orgRole on the current thread for the duration of
     * {@code task}. Symmetric to {@link #runWithOrgScope(String, Runnable)} -
     * used by async paths that captured a full (orgId, orgRole) tuple at
     * enqueue time and need to restore both on the worker thread for downstream
     * canAccess deny-list checks. Nested calls stack via prev/restore on both
     * ThreadLocals independently.
     */
    public static void runWithOrgScope(String orgId, String orgRole, Runnable task) {
        String prevOrg = ASYNC_ORG_SCOPE.get();
        String prevRole = ASYNC_ORG_ROLE.get();
        try {
            if (orgId != null && !orgId.isBlank()) {
                ASYNC_ORG_SCOPE.set(orgId);
            } else {
                ASYNC_ORG_SCOPE.remove();
            }
            if (orgRole != null && !orgRole.isBlank()) {
                ASYNC_ORG_ROLE.set(orgRole);
            } else {
                ASYNC_ORG_ROLE.remove();
            }
            task.run();
        } finally {
            if (prevOrg != null) ASYNC_ORG_SCOPE.set(prevOrg); else ASYNC_ORG_SCOPE.remove();
            if (prevRole != null) ASYNC_ORG_ROLE.set(prevRole); else ASYNC_ORG_ROLE.remove();
        }
    }
}
