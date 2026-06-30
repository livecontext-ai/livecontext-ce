package com.apimarketplace.orchestrator.services.notification;

import com.apimarketplace.orchestrator.domain.WorkflowEntity;
import com.apimarketplace.orchestrator.repository.WorkflowRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link WorkflowSubjectNameResolver}. Pins the contract that
 * {@link NotificationService}'s dispatcher relies on:
 * <ul>
 *   <li>{@code subjectType()} returns "WORKFLOW" (matches the value the
 *       emitter writes to the {@code subject_type} column).</li>
 *   <li>{@code resolveNames} returns names from the live workflow table -
 *       a rename is reflected immediately on the bell.</li>
 *   <li>Empty input short-circuits without a DB hit.</li>
 *   <li>Workflow with null name falls back to "Workflow" placeholder
 *       (would otherwise NPE downstream when the bell renders).</li>
 *   <li>Deleted workflows are absent from the result map (not represented
 *       as null) - the read service handles the absence with the
 *       {@code DELETED_WORKFLOW_LABEL} placeholder.</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("WorkflowSubjectNameResolver")
class WorkflowSubjectNameResolverTest {

    @Mock private WorkflowRepository workflowRepository;

    private WorkflowSubjectNameResolver resolver;

    @BeforeEach
    void setUp() {
        resolver = new WorkflowSubjectNameResolver(workflowRepository);
    }

    @Test
    @DisplayName("subjectType() returns 'WORKFLOW' - must match the emitter's SUBJECT_TYPE_WORKFLOW constant")
    void subjectTypeMatchesEmitter() {
        assertThat(resolver.subjectType()).isEqualTo("WORKFLOW");
    }

    @Test
    @DisplayName("Empty input short-circuits - no repository call")
    void emptyInputSkipsRepository() {
        Map<UUID, String> result = resolver.resolveNames(Set.of());
        assertThat(result).isEmpty();
        verify(workflowRepository, never()).findAllById(any());
    }

    @Test
    @DisplayName("Returns live names - workflow rename reflected immediately")
    void resolvesLiveName() {
        UUID id = UUID.randomUUID();
        WorkflowEntity wf = new WorkflowEntity();
        wf.setId(id);
        wf.setName("Renamed-Live-WF");
        when(workflowRepository.findAllById(any())).thenReturn(List.of(wf));

        Map<UUID, String> result = resolver.resolveNames(Set.of(id));

        assertThat(result).containsEntry(id, "Renamed-Live-WF");
    }

    @Test
    @DisplayName("Workflow with null name falls back to 'Workflow' placeholder - guards downstream NPE")
    void nullNameFallsBackToPlaceholder() {
        UUID id = UUID.randomUUID();
        WorkflowEntity wf = new WorkflowEntity();
        wf.setId(id);
        wf.setName(null);
        when(workflowRepository.findAllById(any())).thenReturn(List.of(wf));

        Map<UUID, String> result = resolver.resolveNames(Set.of(id));

        assertThat(result).containsEntry(id, "Workflow");
    }

    @Test
    @DisplayName("Deleted workflows (id absent from repository result) are absent from the returned map - caller renders the placeholder")
    void deletedWorkflowAbsentFromResult() {
        UUID present = UUID.randomUUID();
        UUID deleted = UUID.randomUUID();
        WorkflowEntity wf = new WorkflowEntity();
        wf.setId(present);
        wf.setName("Live");
        when(workflowRepository.findAllById(any())).thenReturn(List.of(wf));

        Map<UUID, String> result = resolver.resolveNames(Set.of(present, deleted));

        assertThat(result).containsOnlyKeys(present);
        assertThat(result).doesNotContainKey(deleted);
    }
}
