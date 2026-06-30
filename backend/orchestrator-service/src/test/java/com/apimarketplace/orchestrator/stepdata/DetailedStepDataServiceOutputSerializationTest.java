package com.apimarketplace.orchestrator.stepdata;

import com.apimarketplace.common.storage.service.StorageService;
import com.apimarketplace.orchestrator.domain.WorkflowStepDataEntity;
import com.apimarketplace.orchestrator.domain.execution.NodeType;
import com.apimarketplace.orchestrator.persistence.WorkflowStepDataRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("DetailedStepDataService output serialization")
class DetailedStepDataServiceOutputSerializationTest {

    private static final String RUN_ID = "run-null-output";
    private static final String STEP_ALIAS = "unsafe_download";
    private static final String TENANT_ID = "tenant-null-output";

    @Mock private WorkflowStepDataRepository repository;
    @Mock private StorageService storageService;
    @Mock private ColumnDefinitionService columnDefinitionService;

    @Test
    @DisplayName("Preserves null fields inside output when the global mapper skips null map values")
    void preservesNullOutputFieldsWhenGlobalMapperSkipsNullMapValues() throws Exception {
        UUID storageId = UUID.randomUUID();
        WorkflowStepDataEntity entity = new WorkflowStepDataEntity();
        entity.setId(1L);
        entity.setWorkflowRunId(UUID.randomUUID());
        entity.setRunId(RUN_ID);
        entity.setStepAlias(STEP_ALIAS);
        entity.setToolId("Unsafe Download");
        entity.setTenantId(TENANT_ID);
        entity.setStatus("FAILED");
        entity.setNodeType(NodeType.DOWNLOAD_FILE);
        entity.setOutputStorageId(storageId);

        Map<String, Object> nested = new LinkedHashMap<>();
        nested.put("checksum", null);
        nested.put("size", 0);

        Map<String, Object> output = new LinkedHashMap<>();
        output.put("file", null);
        output.put("source_url", "http://localhost:8099/actuator/health");
        output.put("metadata", nested);

        Map<String, Object> storagePayload = new LinkedHashMap<>();
        storagePayload.put("output", output);

        when(repository.findDetailedByRunIdAndStepAliasAndTenantId(
                eq(RUN_ID), eq(STEP_ALIAS), eq(TENANT_ID), isNull(), any()))
                .thenReturn(new PageImpl<>(List.of(entity), PageRequest.of(0, 20), 1));
        when(storageService.getByIdReadOnly(storageId, TENANT_ID)).thenReturn(Optional.of(storagePayload));
        when(columnDefinitionService.deriveColumnsFromRows(any())).thenReturn(List.of());

        DetailedStepDataService service = new DetailedStepDataService(
                repository,
                storageService,
                columnDefinitionService,
                new StepDataRowMapper()
        );

        DetailedStepDataResponse response = service.getDetailedStepData(
                RUN_ID, STEP_ALIAS, TENANT_ID, 1, 20, null, null);

        ObjectMapper mapper = new ObjectMapper()
                .configure(SerializationFeature.WRITE_NULL_MAP_VALUES, false);
        String json = mapper.writeValueAsString(response);

        assertThat(json).contains("\"file\":null");
        assertThat(json).contains("\"checksum\":null");
        assertThat(json).contains("\"source_url\":\"http://localhost:8099/actuator/health\"");
    }
}
