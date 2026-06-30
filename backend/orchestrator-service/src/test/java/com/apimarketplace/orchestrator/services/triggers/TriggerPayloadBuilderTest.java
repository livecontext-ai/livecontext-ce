package com.apimarketplace.orchestrator.services.triggers;

import com.apimarketplace.orchestrator.config.WorkflowExecutionConfig;
import com.apimarketplace.orchestrator.domain.workflow.Trigger;
import com.apimarketplace.orchestrator.domain.workflow.TriggerBatchResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("TriggerPayloadBuilder")
class TriggerPayloadBuilderTest {

    @Mock
    private WorkflowExecutionConfig config;

    @Mock
    private Trigger mockTrigger;

    @Mock
    private TriggerBatchResult mockBatchResult;

    private TriggerPayloadBuilder builder;

    @BeforeEach
    void setUp() {
        builder = new TriggerPayloadBuilder(config);
    }

    @Nested
    @DisplayName("buildErrorPayload()")
    class BuildErrorPayloadTests {

        @Test
        @DisplayName("Should build error payload with all fields")
        void shouldBuildErrorPayloadWithAllFields() {
            when(mockTrigger.id()).thenReturn("trigger-1");

            Map<String, Object> result = builder.buildErrorPayload(
                mockTrigger, "tenant-1", "FETCH_ERROR", "Failed to fetch data"
            );

            assertEquals("trigger-1", result.get("triggerId"));
            assertEquals("tenant-1", result.get("tenantId"));
            assertEquals("error", result.get("status"));
            assertEquals("FETCH_ERROR", result.get("error"));
            assertEquals("Failed to fetch data", result.get("message"));
            assertEquals(List.of(), result.get("data"));
            assertEquals(0, result.get("count"));
            assertEquals("datasource", result.get("source"));
        }

        @Test
        @DisplayName("Should handle null trigger")
        void shouldHandleNullTrigger() {
            Map<String, Object> result = builder.buildErrorPayload(null, "tenant-1", "ERR", "msg");
            assertNull(result.get("triggerId"));
        }

        @Test
        @DisplayName("Should omit message when blank")
        void shouldOmitMessageWhenBlank() {
            when(mockTrigger.id()).thenReturn("trigger-1");

            Map<String, Object> result = builder.buildErrorPayload(mockTrigger, "tenant-1", "ERR", "");
            assertFalse(result.containsKey("message"));
        }

        @Test
        @DisplayName("Should omit message when null")
        void shouldOmitMessageWhenNull() {
            when(mockTrigger.id()).thenReturn("trigger-1");

            Map<String, Object> result = builder.buildErrorPayload(mockTrigger, "tenant-1", "ERR", null);
            assertFalse(result.containsKey("message"));
        }
    }

    @Nested
    @DisplayName("annotateCapMetadata()")
    class AnnotateCapMetadataTests {

        @Test
        @DisplayName("Should annotate with cap metadata when cap > 0")
        void shouldAnnotateWithCapMetadata() {
            when(config.getMaxDatasourceItems()).thenReturn(1000);
            when(mockBatchResult.realTotalCount()).thenReturn(500);
            when(mockBatchResult.totalCount()).thenReturn(500);

            Map<String, Object> payload = new HashMap<>();
            builder.annotateCapMetadata(payload, mockBatchResult);

            assertEquals(1000, payload.get("maxItemsCap"));
            assertEquals(500, payload.get("realTotalCount"));
            assertFalse(payload.containsKey("maxItemsReached"));
        }

        @Test
        @DisplayName("Should set maxItemsReached when real count exceeds total")
        void shouldSetMaxItemsReachedWhenExceeded() {
            when(config.getMaxDatasourceItems()).thenReturn(1000);
            when(mockBatchResult.realTotalCount()).thenReturn(1500);
            when(mockBatchResult.totalCount()).thenReturn(1000);

            Map<String, Object> payload = new HashMap<>();
            builder.annotateCapMetadata(payload, mockBatchResult);

            assertEquals(true, payload.get("maxItemsReached"));
        }

        @Test
        @DisplayName("Should not add cap metadata when cap is 0")
        void shouldNotAddCapWhenZero() {
            when(config.getMaxDatasourceItems()).thenReturn(0);
            when(mockBatchResult.realTotalCount()).thenReturn(500);

            Map<String, Object> payload = new HashMap<>();
            builder.annotateCapMetadata(payload, mockBatchResult);

            assertFalse(payload.containsKey("maxItemsCap"));
            assertEquals(500, payload.get("realTotalCount"));
        }
    }
}
