package com.apimarketplace.orchestrator.services.persistence;

import com.apimarketplace.orchestrator.domain.WorkflowRunEntity;
import com.apimarketplace.orchestrator.repository.WorkflowRepository;
import com.apimarketplace.orchestrator.repository.WorkflowRunRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for estimateRunExecutionDataSize() in WorkflowRunPersistenceService.
 * This method measures the 4 JSONB columns that match StorageReconciliationQueries.EXECUTION_DATA.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("estimateRunExecutionDataSize()")
class EstimateRunExecutionDataSizeTest {

    @Mock private WorkflowRepository workflowRepository;
    @Mock private WorkflowRunRepository workflowRunRepository;
    @Mock private ScheduleSyncService scheduleSyncService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private WorkflowRunPersistenceService service;

    @BeforeEach
    void setUp() {
        service = new WorkflowRunPersistenceService(workflowRepository, workflowRunRepository, scheduleSyncService);
        ReflectionTestUtils.setField(service, "objectMapper", objectMapper);
    }

    @Test
    @DisplayName("Should estimate all 4 columns: state_snapshot + plan + trigger_payload + metadata")
    void shouldEstimateAllFourColumns() throws Exception {
        WorkflowRunEntity run = new WorkflowRunEntity();
        run.setStateSnapshot("{\"version\":3,\"dags\":{}}");
        run.setPlan(Map.of("name", "test"));
        run.setTriggerPayload(Map.of("key", "value"));
        run.setMetadata(Map.of("epoch", 1));

        long expected = run.getStateSnapshot().getBytes(StandardCharsets.UTF_8).length
                + objectMapper.writeValueAsBytes(run.getPlan()).length
                + objectMapper.writeValueAsBytes(run.getTriggerPayload()).length
                + objectMapper.writeValueAsBytes(run.getMetadata()).length;

        long actual = service.estimateRunExecutionDataSize(run);

        assertThat(actual).isEqualTo(expected);
        assertThat(actual).isGreaterThan(0);
    }

    @Test
    @DisplayName("Should return 0 for run with all null columns")
    void shouldReturnZeroForNullColumns() {
        WorkflowRunEntity run = new WorkflowRunEntity();

        assertThat(service.estimateRunExecutionDataSize(run)).isZero();
    }

    @Test
    @DisplayName("Should handle partial data (only state_snapshot set)")
    void shouldHandlePartialData() {
        WorkflowRunEntity run = new WorkflowRunEntity();
        run.setStateSnapshot("{\"version\":3}");

        long expected = "{\"version\":3}".getBytes(StandardCharsets.UTF_8).length;

        assertThat(service.estimateRunExecutionDataSize(run)).isEqualTo(expected);
    }

    @Test
    @DisplayName("Should handle only plan set")
    void shouldHandleOnlyPlanSet() throws Exception {
        WorkflowRunEntity run = new WorkflowRunEntity();
        run.setPlan(Map.of("name", "workflow", "triggers", java.util.List.of()));

        long expected = objectMapper.writeValueAsBytes(run.getPlan()).length;

        assertThat(service.estimateRunExecutionDataSize(run)).isEqualTo(expected);
    }

    @Test
    @DisplayName("Should handle large state_snapshot correctly")
    void shouldHandleLargeSnapshot() {
        WorkflowRunEntity run = new WorkflowRunEntity();
        StringBuilder sb = new StringBuilder("{\"data\":\"");
        for (int i = 0; i < 100_000; i++) sb.append('x');
        sb.append("\"}");
        run.setStateSnapshot(sb.toString());

        assertThat(service.estimateRunExecutionDataSize(run)).isGreaterThan(100_000);
    }

    @Test
    @DisplayName("Should handle Unicode characters in state_snapshot (UTF-8 multi-byte)")
    void shouldHandleUnicodeCharacters() {
        WorkflowRunEntity run = new WorkflowRunEntity();
        String unicodeSnapshot = "{\"label\":\"éàü日本語\"}";
        run.setStateSnapshot(unicodeSnapshot);

        long expected = unicodeSnapshot.getBytes(StandardCharsets.UTF_8).length;
        long actual = service.estimateRunExecutionDataSize(run);

        assertThat(actual).isEqualTo(expected);
        // UTF-8 multi-byte chars → byte length > char count
        assertThat(expected).isGreaterThan(unicodeSnapshot.length());
    }

    @Test
    @DisplayName("Should handle empty string state_snapshot (0 bytes)")
    void shouldHandleEmptyStringSnapshot() {
        WorkflowRunEntity run = new WorkflowRunEntity();
        run.setStateSnapshot("");

        // Empty string → 0 bytes, but not null
        assertThat(service.estimateRunExecutionDataSize(run)).isZero();
    }

    @Test
    @DisplayName("Should handle empty map for plan/metadata/triggerPayload")
    void shouldHandleEmptyMaps() throws Exception {
        WorkflowRunEntity run = new WorkflowRunEntity();
        run.setPlan(Map.of());
        run.setTriggerPayload(Map.of());
        run.setMetadata(Map.of());

        // {} serializes to 2 bytes each
        long expected = objectMapper.writeValueAsBytes(Map.of()).length * 3;

        assertThat(service.estimateRunExecutionDataSize(run)).isEqualTo(expected);
    }
}
