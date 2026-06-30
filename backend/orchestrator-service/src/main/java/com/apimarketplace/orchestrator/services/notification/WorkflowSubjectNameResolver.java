package com.apimarketplace.orchestrator.services.notification;

import com.apimarketplace.orchestrator.domain.WorkflowEntity;
import com.apimarketplace.orchestrator.repository.WorkflowRepository;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Resolves names for {@code subject_type = WORKFLOW} notifications. Reads
 * the live {@code workflows} table (same orchestrator schema as
 * {@code notifications}) so a workflow rename is immediately reflected on
 * the bell - V172 did NOT denormalize the name onto the notification row.
 *
 * <p>Renamed-since-emit workflows render with the current name; deleted
 * workflows are absent from the result map and the read service renders
 * them as {@link NotificationService#DELETED_WORKFLOW_LABEL}.
 */
@Component
public class WorkflowSubjectNameResolver implements SubjectNameResolver {

    private final WorkflowRepository workflowRepository;

    public WorkflowSubjectNameResolver(WorkflowRepository workflowRepository) {
        this.workflowRepository = workflowRepository;
    }

    @Override
    public String subjectType() {
        return SubjectNameResolver.WORKFLOW;
    }

    @Override
    public Map<UUID, String> resolveNames(Set<UUID> subjectIds) {
        if (subjectIds.isEmpty()) return Map.of();
        return workflowRepository.findAllById(subjectIds).stream()
                .collect(Collectors.toMap(
                        WorkflowEntity::getId,
                        w -> Optional.ofNullable(w.getName()).orElse("Workflow")));
    }
}
