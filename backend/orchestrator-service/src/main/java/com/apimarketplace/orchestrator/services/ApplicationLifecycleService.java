package com.apimarketplace.orchestrator.services;

import com.apimarketplace.orchestrator.domain.WorkflowEntity;
import com.apimarketplace.orchestrator.repository.WorkflowRepository;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.UUID;

/**
 * Single home for application-clone lifecycle resolution.
 *
 * <p>An acquired marketplace application is materialised locally as exactly one
 * {@link WorkflowEntity.WorkflowType#APPLICATION} clone stamped with the source
 * publication id (the 1:1 invariant guarded by the partial unique index
 * {@code uq_workflow_org_source_pub_application}). Resolving that clone for a
 * given {@code (organizationId, sourcePublicationId)} is the same org-scoped
 * lookup that the application tool's execute / get / runs / uninstall paths and
 * the internal publication-support endpoint each performed inline. Centralising
 * it here removes that duplication and gives the lookup a single, well-tested
 * definition - this exact finder has a history of org-scoping regressions
 * (post-V261 strict-org, the 2026-05-17 teammate-visibility fix), so one place
 * to get it right is worth more than the few saved lines.
 *
 * <p><b>Org source stays the caller's.</b> Callers resolve the organization id
 * from different request contexts (the tool {@code context.orgId()}, the
 * request-scoped {@code TenantResolver.currentRequestOrganizationId()}, or an
 * explicit request param) and gate a missing org their own way before calling
 * in. {@link #resolveClone} therefore takes the org id as a parameter and never
 * picks one for the caller - collapsing those distinct org sources is exactly
 * what reintroduced the teammate-visibility bug, so it must not happen here.
 *
 * <p>Currently a single verb; this is the seed of the centralized application
 * lifecycle service. Further verbs (fork/duplicate-to-editable, public-access,
 * uninstall) land here as that work is built.
 */
@Service
public class ApplicationLifecycleService {

    private final WorkflowRepository workflowRepository;

    public ApplicationLifecycleService(WorkflowRepository workflowRepository) {
        this.workflowRepository = workflowRepository;
    }

    /**
     * Resolve the local {@code APPLICATION} clone an organization acquired from a
     * given publication. Returns empty when the org id or publication id is
     * missing (the caller is expected to have gated a missing org already) or
     * when no clone exists in that organization.
     *
     * @param organizationId      the resolving caller's organization id (never chosen here)
     * @param sourcePublicationId the marketplace publication the clone was acquired from
     */
    public Optional<WorkflowEntity> resolveClone(String organizationId, UUID sourcePublicationId) {
        if (organizationId == null || organizationId.isBlank() || sourcePublicationId == null) {
            return Optional.empty();
        }
        return workflowRepository.findByOrganizationIdAndSourcePublicationIdAndWorkflowType(
                organizationId, sourcePublicationId, WorkflowEntity.WorkflowType.APPLICATION);
    }
}
