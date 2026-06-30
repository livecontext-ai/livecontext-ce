package com.apimarketplace.auth.client.access;

import java.util.Optional;
import java.util.Set;

/**
 * SPI implemented by each service that owns one or more resource types.
 *
 * <p>The owning service is the only one that can answer "which org owns
 * resource {type}/{id}?" without violating the cross-schema rule
 * ({@code Each service MUST only query its own DB schema}). Cross-service
 * lookups happen via {@code /api/internal/<service>/owner/{type}/{id}}
 * (endpoint added in PR-1.d). The HMAC inter-service contract guarding
 * those endpoints lands in PR-1.h.
 *
 * <p>Concrete services register a {@code @Component} bean implementing this
 * interface. Each implementation handles the resource types it owns (e.g.
 * orchestrator-service handles {@code workflow}; agent-service handles
 * {@code agent}).
 *
 * <p>Example (orchestrator-service):
 * <pre>{@code
 * @Component
 * public class WorkflowOwnershipResolver implements ResourceOwnershipResolver {
 *   private final WorkflowRepository repo;
 *   public Set<String> handledTypes() { return Set.of("workflow"); }
 *   public Optional<String> resolveOwnerOrgId(String type, String id) {
 *     // Null check first - Set.of(...).contains(null) throws NPE.
 *     if (type == null || id == null || !handledTypes().contains(type)) {
 *       return Optional.empty();
 *     }
 *     try {
 *       return repo.findOrganizationIdById(UUID.fromString(id));
 *     } catch (IllegalArgumentException e) {
 *       return Optional.empty(); // malformed UUID
 *     }
 *   }
 * }
 * }</pre>
 */
public interface ResourceOwnershipResolver {

    /**
     * Resolve the org that owns the given resource.
     *
     * @param resourceType canonical resource type (workflow, agent, datasource, interface, project)
     * @param resourceId   the resource ID
     * @return the owning org ID, or {@link Optional#empty()} if the resource
     *         is not found, the resolver does not handle this type, or no
     *         org owns it (e.g. legacy personal-only resource).
     */
    Optional<String> resolveOwnerOrgId(String resourceType, String resourceId);

    /**
     * Resource types this resolver handles. Used by aggregators to dispatch
     * lookups to the right resolver without trying every bean.
     */
    Set<String> handledTypes();
}
