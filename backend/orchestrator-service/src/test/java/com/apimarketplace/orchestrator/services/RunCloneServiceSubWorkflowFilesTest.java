package com.apimarketplace.orchestrator.services;

import com.apimarketplace.common.storage.domain.StorageEntity;
import com.apimarketplace.common.storage.repository.StorageRepository;
import com.apimarketplace.common.storage.service.StorageBreakdownService;
import com.apimarketplace.interfaces.client.InterfaceClient;
import com.apimarketplace.orchestrator.domain.WorkflowRunEntity;
import com.apimarketplace.orchestrator.domain.WorkflowStepDataEntity;
import com.apimarketplace.orchestrator.persistence.WorkflowStepDataRepository;
import com.apimarketplace.orchestrator.repository.WorkflowRunRepository;
import com.apimarketplace.orchestrator.services.file.FileStorageService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Reproduction for the publication-snapshot audit HIGH "H4": a showcase run that contains a
 * SubWorkflowNode loses the sub-workflow's files. The sub-workflow runs as a SEPARATE
 * WorkflowRunEntity; its outputs (FileRefs referencing the CHILD run's storage rows) are embedded
 * into the parent step output along with the child's {@code subRunId}. {@code cloneRun} collected
 * storage ids only from the PARENT run, so the descendant blobs were never copied and the cloned
 * showcase still addressed the publisher's child-run rows by id (cross-tenant leak / 404).
 *
 * <p>This pins the new recursive descendant-discovery: from the parent run's stored outputs it must
 * find each child run's storage rows - and RECURSE into nested sub-workflows (a snapshot is a copy;
 * the data must be copied at every depth).</p>
 */
@DisplayName("RunCloneService - recursive descendant sub-workflow storage discovery (audit H4)")
class RunCloneServiceSubWorkflowFilesTest {

    private WorkflowRunRepository workflowRunRepository;
    private WorkflowStepDataRepository workflowStepDataRepository;
    private StorageRepository storageRepository;
    private RunCloneService service;
    private Method collectDescendantStorageIds;

    @BeforeEach
    void setUp() throws Exception {
        workflowRunRepository = mock(WorkflowRunRepository.class);
        workflowStepDataRepository = mock(WorkflowStepDataRepository.class);
        storageRepository = mock(StorageRepository.class);
        service = new RunCloneService(
                workflowRunRepository, workflowStepDataRepository, storageRepository,
                mock(FileStorageService.class), mock(JdbcTemplate.class),
                mock(StorageBreakdownService.class), new ObjectMapper(), mock(InterfaceClient.class));
        collectDescendantStorageIds = RunCloneService.class.getDeclaredMethod(
                "collectDescendantStorageIds", WorkflowRunEntity.class, Set.class);
        collectDescendantStorageIds.setAccessible(true);
    }

    @Test
    @DisplayName("finds child AND grandchild sub-workflow storage rows from the parent run's embedded subRunIds")
    @SuppressWarnings("unchecked")
    void discoversDescendantStorageRecursively() throws Exception {
        UUID parentStorageId = UUID.randomUUID();
        UUID childStorageId = UUID.randomUUID();
        UUID grandStorageId = UUID.randomUUID();
        UUID childRunUuid = UUID.randomUUID();
        UUID grandRunUuid = UUID.randomUUID();

        WorkflowRunEntity parentRun = run(UUID.randomUUID(), "src-run");
        WorkflowRunEntity childRun = run(childRunUuid, "child-run");
        WorkflowRunEntity grandRun = run(grandRunUuid, "grand-run");

        // Parent output embeds the child's file (by child storage id) + the child's subRunId.
        StorageEntity parentRow = jsonRow(parentStorageId,
                "{\"result\":{\"out\":{\"_type\":\"file\",\"path\":\"p\",\"id\":\"" + childStorageId + "\"}},"
                        + "\"subRunId\":\"child-run\"}");
        StorageEntity childRow = jsonRow(childStorageId,
                "{\"result\":{\"out\":{\"_type\":\"file\",\"path\":\"cp\",\"id\":\"" + grandStorageId + "\"}},"
                        + "\"subRunId\":\"grand-run\"}");
        StorageEntity grandRow = jsonRow(grandStorageId, "{\"done\":true}"); // leaf, no further subRunId

        // Pre-create step mocks OUTSIDE any when(...) - building a mock inside thenReturn(...) nests
        // stubbing (UnfinishedStubbingException).
        WorkflowStepDataEntity childStep = step(childStorageId);
        WorkflowStepDataEntity grandStep = step(grandStorageId);

        when(storageRepository.findAllById(Set.of(parentStorageId))).thenReturn(List.of(parentRow));
        when(workflowRunRepository.findByRunIdPublic("child-run")).thenReturn(Optional.of(childRun));
        when(workflowStepDataRepository.findByWorkflowRunIdOrderByIdAsc(childRunUuid))
                .thenReturn(List.of(childStep));
        when(storageRepository.findAllById(Set.of(childStorageId))).thenReturn(List.of(childRow));
        when(workflowRunRepository.findByRunIdPublic("grand-run")).thenReturn(Optional.of(grandRun));
        when(workflowStepDataRepository.findByWorkflowRunIdOrderByIdAsc(grandRunUuid))
                .thenReturn(List.of(grandStep));
        when(storageRepository.findAllById(Set.of(grandStorageId))).thenReturn(List.of(grandRow));

        Set<UUID> result = (Set<UUID>) collectDescendantStorageIds.invoke(
                service, parentRun, Set.of(parentStorageId));

        assertThat(result)
                .as("both the child and the nested grandchild sub-workflow storage rows must be "
                  + "discovered so the clone copies their blobs and remaps the embedded refs")
                .containsExactlyInAnyOrder(childStorageId, grandStorageId);
    }

    private static WorkflowRunEntity run(UUID id, String publicId) {
        WorkflowRunEntity r = mock(WorkflowRunEntity.class);
        when(r.getId()).thenReturn(id);
        when(r.getRunIdPublic()).thenReturn(publicId);
        return r;
    }

    private static StorageEntity jsonRow(UUID id, String data) {
        StorageEntity e = new StorageEntity();
        e.setId(id);
        e.setData(data);
        return e;
    }

    private static WorkflowStepDataEntity step(UUID outputStorageId) {
        WorkflowStepDataEntity s = mock(WorkflowStepDataEntity.class);
        when(s.getOutputStorageId()).thenReturn(outputStorageId);
        return s;
    }
}
