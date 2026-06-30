package com.apimarketplace.orchestrator.tools.application;

import com.apimarketplace.orchestrator.domain.WorkflowEntity;
import com.apimarketplace.orchestrator.repository.WorkflowRepository;
import lombok.extern.slf4j.Slf4j;

import java.util.Optional;
import java.util.UUID;

/**
 * Shared disambiguation for the {@code id} vs {@code workflowId} foot-gun.
 *
 * <p>Background (prod 2026-06-05): {@code application(action='my'|'search')} items
 * carry two UUIDs side by side - {@code id}/{@code application_id} (the publication)
 * and {@code workflowId} (the underlying workflow). An agent inspecting an owned app
 * picked {@code workflowId} for the {@code application_id} parameter and got a generic
 * {@code RESOURCE_NOT_FOUND} on {@code get}. The same mistake on {@code execute} hit the
 * same dead end with no hint.
 *
 * <p>This helper turns that cul-de-sac into a single rebound: when a UUID fails to
 * resolve as a publication, it checks whether the UUID is actually the workflowId of an
 * acquired/published app (a workflow whose {@code sourcePublicationId} is set) and, if so,
 * returns a corrective message echoing the real {@code application_id}.
 *
 * <p>Single source of truth - both {@code ApplicationCrudModule.executeGet} and
 * {@code ApplicationExecuteModule.executeApplication} call this so the wording cannot drift.
 */
@Slf4j
final class ApplicationIdDisambiguator {

    private ApplicationIdDisambiguator() {
    }

    /**
     * Best-effort: detect a UUID that is a workflowId-of-an-app and build the corrective
     * error message echoing the real application_id.
     *
     * @param workflowRepository repo to resolve the UUID as a workflow
     * @param id                 the UUID the agent passed as {@code application_id}
     * @return the corrective message if {@code id} is a recognizable workflowId of an app,
     * otherwise {@link Optional#empty()} (caller falls through to the generic 404).
     */
    static Optional<String> workflowIdHint(WorkflowRepository workflowRepository, UUID id) {
        if (workflowRepository == null || id == null) {
            return Optional.empty();
        }
        try {
            return workflowRepository.findById(id)
                    .map(WorkflowEntity::getSourcePublicationId)
                    .map(sourcePublicationId -> {
                        String realAppId = sourcePublicationId.toString();
                        return "This UUID is a workflowId, not an application_id. The application_id "
                                + "for this app is " + realAppId + ". Retry with application_id='"
                                + realAppId + "'.";
                    });
        } catch (Exception e) {
            // Best-effort disambiguation only - never let a lookup hiccup mask the real 404.
            log.debug("workflowIdHint: disambiguation skipped for {}: {}", id, e.getMessage());
            return Optional.empty();
        }
    }
}
