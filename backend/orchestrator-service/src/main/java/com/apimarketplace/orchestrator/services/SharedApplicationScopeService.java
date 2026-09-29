package com.apimarketplace.orchestrator.services;

import com.apimarketplace.common.scope.ScopeGuard;
import com.apimarketplace.common.storage.repository.StorageRepository;
import com.apimarketplace.orchestrator.domain.WorkflowEntity;
import com.apimarketplace.orchestrator.domain.WorkflowRunEntity;
import com.apimarketplace.orchestrator.repository.WorkflowRepository;
import com.apimarketplace.orchestrator.repository.WorkflowRunRepository;
import com.apimarketplace.orchestrator.services.interfaces.InterfacePlanExtractor;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Decides whether a row belongs to the application an APPLICATION share link exposes.
 *
 * <p>A share token authenticates its holder AS THE OWNER, so the owner's strict scope alone would
 * let the holder read every workflow, interface and file of the owner's workspace. The shared
 * application is defined here, where its rows live: the workflows cloned for the publication
 * ({@code source_publication_id == publicationId}) and the runs started for it
 * ({@code publication_id == publicationId}). interface-service and storage-service reach these
 * checks through {@code InternalSharedApplicationScopeController}.
 */
@Service
public class SharedApplicationScopeService {

    private final WorkflowRepository workflowRepository;
    private final WorkflowRunRepository workflowRunRepository;
    private final InterfacePlanExtractor interfacePlanExtractor;
    private final StorageRepository storageRepository;

    /**
     * ALLOW verdicts of the output scan per (publication, workspace, file). A shared page loads each
     * image by id, often several times per render; without this every load re-scans the application
     * runs' outputs. Only a "yes" is cached: a viewer can see a file id before the step output that
     * references it is saved, and a cached "no" would keep that image broken for the whole TTL. A
     * refusal is therefore always re-checked. Short TTL so a revoked link stops working within a
     * minute.
     */
    private final Cache<String, Boolean> outputReferenceVerdicts = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofSeconds(60))
            .maximumSize(10_000)
            .build();

    public SharedApplicationScopeService(WorkflowRepository workflowRepository,
                                         WorkflowRunRepository workflowRunRepository,
                                         InterfacePlanExtractor interfacePlanExtractor,
                                         StorageRepository storageRepository) {
        this.workflowRepository = workflowRepository;
        this.workflowRunRepository = workflowRunRepository;
        this.interfacePlanExtractor = interfacePlanExtractor;
        this.storageRepository = storageRepository;
    }

    /**
     * True when {@code interfaceId} is referenced by the plan of a workflow cloned for the
     * publication inside the owner's workspace {@code organizationId}.
     */
    @Transactional(readOnly = true)
    public boolean interfaceBelongsToApplication(UUID publicationId, String organizationId, UUID interfaceId) {
        if (publicationId == null || interfaceId == null || isBlank(organizationId)) {
            return false;
        }
        return workflowRepository.findAllByOrganizationIdAndSourcePublicationId(organizationId, publicationId)
                .stream()
                .anyMatch(wf -> interfacePlanExtractor.extractInterfaceIds(wf.getPlan()).contains(interfaceId));
    }

    /**
     * True when a file row was produced by the application. Three ways, any one suffices:
     * <ul>
     *   <li>its {@code runId} is a run started for the publication (strict scope checked);</li>
     *   <li>its {@code workflowId} is one of the publication's clones (strict scope checked);</li>
     *   <li>its id is referenced by a persisted output of a run started for the publication in
     *       the owner's workspace. This is how the application reaches files a tool uploaded
     *       WITHOUT run tags (catalog binary responses, image generation). A sub-workflow's own
     *       run carries no publication id, but the parent's sub_workflow step output embeds the
     *       child's outputs, so a child file the application shows is found through the parent.</li>
     * </ul>
     * Anything else, including a file id that merely exists in the owner's workspace, is refused.
     */
    @Transactional(readOnly = true)
    public boolean fileBelongsToApplication(UUID publicationId, String tenantId, String organizationId,
                                            String runId, String workflowId, UUID fileId) {
        if (publicationId == null) {
            return false;
        }
        String pub = publicationId.toString();
        if (!isBlank(runId)) {
            Optional<WorkflowRunEntity> run = workflowRunRepository.findByRunIdPublic(runId);
            if (run.isPresent()
                    && pub.equalsIgnoreCase(run.get().getPublicationId())
                    && ScopeGuard.isInStrictScope(tenantId, organizationId,
                            run.get().getTenantId(), run.get().getOrganizationId())) {
                return true;
            }
        }
        UUID wfId = parseUuid(workflowId);
        if (wfId != null) {
            Optional<WorkflowEntity> wf = workflowRepository.findById(wfId);
            if (wf.isPresent()
                    && publicationId.equals(wf.get().getSourcePublicationId())
                    && ScopeGuard.isInStrictScope(tenantId, organizationId,
                            wf.get().getTenantId(), wf.get().getOrganizationId())) {
                return true;
            }
        }
        return fileReferencedByApplicationRuns(pub, organizationId, fileId);
    }

    private boolean fileReferencedByApplicationRuns(String publicationId, String organizationId, UUID fileId) {
        if (fileId == null || isBlank(organizationId)) {
            return false;
        }
        String key = publicationId + "|" + organizationId + "|" + fileId;
        if (outputReferenceVerdicts.getIfPresent(key) != null) {
            return true;
        }
        List<String> runIds = workflowRunRepository
                .findRunIdsPublicByPublicationIdAndOrganizationId(publicationId, organizationId);
        boolean referenced = !runIds.isEmpty()
                && storageRepository.existsRunRowReferencing(runIds, fileId.toString());
        if (referenced) {
            outputReferenceVerdicts.put(key, Boolean.TRUE);
        }
        return referenced;
    }

    private static UUID parseUuid(String raw) {
        if (isBlank(raw)) {
            return null;
        }
        try {
            return UUID.fromString(raw.trim());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
