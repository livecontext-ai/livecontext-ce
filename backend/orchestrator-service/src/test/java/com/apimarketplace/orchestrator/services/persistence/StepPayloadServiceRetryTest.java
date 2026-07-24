package com.apimarketplace.orchestrator.services.persistence;

import com.apimarketplace.common.storage.exception.QuotaExceededException;
import com.apimarketplace.common.storage.exception.StorageSerializationException;
import com.apimarketplace.common.storage.service.StorageService;
import com.apimarketplace.orchestrator.domain.workflow.StepExecutionResult;
import com.apimarketplace.orchestrator.domain.workflow.WorkflowExecution;
import com.apimarketplace.orchestrator.domain.workflow.WorkflowPlan;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.sql.SQLException;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Layer (d) of the output-loss fix: the ONE deliberate bounded retry around
 * the step-payload storage write, with a DISCRIMINATED outcome.
 *
 * <ul>
 *   <li>transient failure → retried once, succeeds on attempt 2;</li>
 *   <li>quota → never retried (tenant action, not time);</li>
 *   <li>data-shaped (serialization / SQLSTATE class 22 e.g. 22P05) → never
 *       retried (the same bytes fail the same way every time);</li>
 *   <li>retries exhausted → TRANSIENT_EXHAUSTED, never a silent null.</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("StepPayloadService - bounded retry + discriminated outcome")
class StepPayloadServiceRetryTest {

    @Mock private StorageService storageService;
    @Mock private OutputSchemaMapper outputSchemaMapper;
    @Mock private WorkflowExecution execution;
    @Mock private WorkflowPlan plan;

    private StepPayloadService service;

    @BeforeEach
    void setUp() {
        service = new StepPayloadService(storageService, outputSchemaMapper);
        service.setRetryBackoffMs(0); // keep tests fast - the backoff itself is policy, not mechanism

        lenient().when(execution.getPlan()).thenReturn(plan);
        lenient().when(execution.getRunId()).thenReturn("run-1");
        lenient().when(plan.getTenantId()).thenReturn("tenant-1");
        lenient().when(plan.getId()).thenReturn("wf-1");
        lenient().when(plan.findStep(anyString())).thenReturn(Optional.empty());
        lenient().when(outputSchemaMapper.hasMapper(any())).thenReturn(false);
    }

    private StepExecutionResult successResult() {
        return StepExecutionResult.success("mcp:step", Map.of("data", "value"), 100L);
    }

    private StepPayloadResult persist() {
        return service.persistStepPayloadOutcome(
                execution, "mcp:step", "alias", successResult(), Map.of(), 0, 1, 0);
    }

    @Nested
    @DisplayName("Transient failures")
    class Transient {

        @Test
        @DisplayName("first attempt fails transiently, retry succeeds: outcome is stored() with the storage id, save called exactly twice")
        void retrySucceedsOnSecondAttempt() {
            UUID storageId = UUID.randomUUID();
            when(storageService.saveJsonWithContext(anyString(), any(), anyString(), any(), any(),
                    anyString(), anyString(), anyInt(), anyInt(), anyInt(), anyString(), anyString()))
                    .thenThrow(new RuntimeException("connection reset"))
                    .thenReturn(storageId);

            StepPayloadResult outcome = persist();

            assertThat(outcome.stored()).isTrue();
            assertThat(outcome.storageId()).isEqualTo(storageId);
            assertThat(outcome.cause()).isNull();
            verify(storageService, times(2)).saveJsonWithContext(anyString(), any(), anyString(), any(), any(),
                    anyString(), anyString(), anyInt(), anyInt(), anyInt(), anyString(), anyString());
        }

        @Test
        @DisplayName("retries exhausted: discriminated TRANSIENT_EXHAUSTED, save called exactly MAX_SAVE_ATTEMPTS times")
        void retriesExhaustedReportsTransientExhausted() {
            when(storageService.saveJsonWithContext(anyString(), any(), anyString(), any(), any(),
                    anyString(), anyString(), anyInt(), anyInt(), anyInt(), anyString(), anyString()))
                    .thenThrow(new RuntimeException("still down"));

            StepPayloadResult outcome = persist();

            assertThat(outcome.stored()).isFalse();
            assertThat(outcome.cause()).isEqualTo(PayloadFailureCause.TRANSIENT_EXHAUSTED);
            verify(storageService, times(StepPayloadService.MAX_SAVE_ATTEMPTS))
                    .saveJsonWithContext(anyString(), any(), anyString(), any(), any(),
                            anyString(), anyString(), anyInt(), anyInt(), anyInt(), anyString(), anyString());
        }
    }

    @Nested
    @DisplayName("Non-retryable causes")
    class NonRetryable {

        @Test
        @DisplayName("quota exceeded is NOT retried: exactly one attempt, cause QUOTA_EXCEEDED")
        void quotaIsNotRetried() {
            when(storageService.saveJsonWithContext(anyString(), any(), anyString(), any(), any(),
                    anyString(), anyString(), anyInt(), anyInt(), anyInt(), anyString(), anyString()))
                    .thenThrow(new QuotaExceededException("hard limit", "tenant-1"));

            StepPayloadResult outcome = persist();

            assertThat(outcome.stored()).isFalse();
            assertThat(outcome.cause()).isEqualTo(PayloadFailureCause.QUOTA_EXCEEDED);
            verify(storageService, times(1)).saveJsonWithContext(anyString(), any(), anyString(), any(), any(),
                    anyString(), anyString(), anyInt(), anyInt(), anyInt(), anyString(), anyString());
        }

        @Test
        @DisplayName("serialization failure is NOT retried: exactly one attempt, cause SERIALIZATION")
        void serializationIsNotRetried() {
            when(storageService.saveJsonWithContext(anyString(), any(), anyString(), any(), any(),
                    anyString(), anyString(), anyInt(), anyInt(), anyInt(), anyString(), anyString()))
                    .thenThrow(new StorageSerializationException("cannot serialize", new RuntimeException()));

            StepPayloadResult outcome = persist();

            assertThat(outcome.stored()).isFalse();
            assertThat(outcome.cause()).isEqualTo(PayloadFailureCause.SERIALIZATION);
            verify(storageService, times(1)).saveJsonWithContext(anyString(), any(), anyString(), any(), any(),
                    anyString(), anyString(), anyInt(), anyInt(), anyInt(), anyString(), anyString());
        }

        @Test
        @DisplayName("a 22P05-class SQL failure (SQLSTATE class 22 in the cause chain) is NOT retried: exactly one attempt, cause SERIALIZATION")
        void dataShapedSqlStateIsNotRetried() {
            RuntimeException wrapped = new RuntimeException("insert failed",
                    new SQLException("unsupported Unicode escape sequence", "22P05"));
            when(storageService.saveJsonWithContext(anyString(), any(), anyString(), any(), any(),
                    anyString(), anyString(), anyInt(), anyInt(), anyInt(), anyString(), anyString()))
                    .thenThrow(wrapped);

            StepPayloadResult outcome = persist();

            assertThat(outcome.stored()).isFalse();
            assertThat(outcome.cause()).isEqualTo(PayloadFailureCause.SERIALIZATION);
            verify(storageService, times(1)).saveJsonWithContext(anyString(), any(), anyString(), any(), any(),
                    anyString(), anyString(), anyInt(), anyInt(), anyInt(), anyString(), anyString());
        }
    }

    @Nested
    @DisplayName("isDataShaped discrimination")
    class DataShaped {

        @Test
        @DisplayName("SQLSTATE class 22 anywhere in the cause chain is data-shaped")
        void sqlState22IsDataShaped() {
            assertThat(StepPayloadService.isDataShaped(
                    new RuntimeException(new SQLException("bad", "22P05")))).isTrue();
            assertThat(StepPayloadService.isDataShaped(
                    new SQLException("bad", "22001"))).isTrue();
        }

        @Test
        @DisplayName("StorageSerializationException is data-shaped")
        void serializationExceptionIsDataShaped() {
            assertThat(StepPayloadService.isDataShaped(
                    new StorageSerializationException("x", new RuntimeException()))).isTrue();
        }

        @Test
        @DisplayName("connection-class SQL states and plain runtime failures are NOT data-shaped (retryable)")
        void transientFailuresAreNotDataShaped() {
            assertThat(StepPayloadService.isDataShaped(
                    new SQLException("connection refused", "08001"))).isFalse();
            assertThat(StepPayloadService.isDataShaped(
                    new RuntimeException("timeout"))).isFalse();
            assertThat(StepPayloadService.isDataShaped(
                    new SQLException("no state", (String) null))).isFalse();
        }
    }
}
