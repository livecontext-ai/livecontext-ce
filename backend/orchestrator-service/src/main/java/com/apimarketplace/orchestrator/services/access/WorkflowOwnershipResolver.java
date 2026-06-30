package com.apimarketplace.orchestrator.services.access;

import com.apimarketplace.auth.client.access.ResourceOwnershipResolver;
import com.apimarketplace.orchestrator.repository.WorkflowRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * {@link ResourceOwnershipResolver} for workflows owned by orchestrator-service.
 *
 * <p>Answers "which org owns workflow {@code id}?" via the lightweight
 * {@code findOrganizationIdById} projection - no JSONB plan hydration.
 *
 * <p>Returns {@link Optional#empty()} when the workflow is not found, the id
 * is not a valid UUID, or the workflow is personal (no {@code organization_id}).
 */
@Component
public class WorkflowOwnershipResolver implements ResourceOwnershipResolver {

    private static final Logger log = LoggerFactory.getLogger(WorkflowOwnershipResolver.class);
    private static final Set<String> HANDLED = Set.of("workflow");

    private final WorkflowRepository workflowRepository;

    public WorkflowOwnershipResolver(WorkflowRepository workflowRepository) {
        this.workflowRepository = workflowRepository;
    }

    @Override
    public Set<String> handledTypes() {
        return HANDLED;
    }

    @Override
    public Optional<String> resolveOwnerOrgId(String resourceType, String resourceId) {
        // Null check first - Set.of(...).contains(null) throws NPE on immutable sets.
        if (resourceType == null || resourceId == null || !HANDLED.contains(resourceType)) {
            return Optional.empty();
        }
        UUID id;
        try {
            id = UUID.fromString(resourceId);
        } catch (IllegalArgumentException e) {
            log.debug("resolveOwnerOrgId: not a UUID workflow id '{}'", resourceId);
            return Optional.empty();
        }
        return workflowRepository.findOrganizationIdById(id);
    }
}
