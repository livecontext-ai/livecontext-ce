package com.apimarketplace.orchestrator.services.persistence;

import com.apimarketplace.common.classification.DataSensitivity;
import com.apimarketplace.common.storage.service.StorageService;
import com.apimarketplace.orchestrator.domain.execution.NodeStatus;
import com.apimarketplace.orchestrator.domain.workflow.StepExecutionResult;
import com.apimarketplace.orchestrator.domain.workflow.WorkflowExecution;
import com.apimarketplace.orchestrator.domain.workflow.WorkflowPlan;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * LC-066 / LC-011: the output of a catalog step that reads Google restricted-scope data is
 * persisted as RESTRICTED (bounded retention, hard-delete sweep), so are the files it produced,
 * and every later payload of the same run inherits the tag.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("StepPayloadService restricted-data classification")
class StepPayloadServiceRestrictedDataTest {

    @Mock private StorageService storageService;
    @Mock private OutputSchemaMapper outputSchemaMapper;
    @Mock private WorkflowExecution execution;
    @Mock private WorkflowPlan plan;

    private StepPayloadService service;

    @BeforeEach
    void setUp() {
        service = new StepPayloadService(storageService, outputSchemaMapper);
        lenient().when(execution.getPlan()).thenReturn(plan);
        lenient().when(execution.getRunId()).thenReturn("run-1");
        lenient().when(plan.getTenantId()).thenReturn("tenant-1");
        lenient().when(plan.getId()).thenReturn("wf-1");
        lenient().when(plan.findStep(anyString())).thenReturn(Optional.empty());
    }

    private static StepExecutionResult result(Map<String, Object> output) {
        return new StepExecutionResult("mcp:gmail", NodeStatus.COMPLETED, "Success", output, 5L, null);
    }

    @Test
    @DisplayName("a Gmail step output (iconSlug stamped by the catalog) is saved RESTRICTED")
    void gmailOutputIsRestricted() {
        Map<String, Object> output = new HashMap<>();
        output.put("messages", List.of(Map.of("snippet", "wire 45000 EUR")));
        output.put("metadata", Map.of("iconSlug", "gmail", "toolName", "list_messages"));
        when(storageService.saveJsonWithContext(anyString(), any(), anyString(), any(), any(), any(), any(),
                anyInt(), anyInt(), anyInt(), any(), any(), eq(DataSensitivity.RESTRICTED)))
                .thenReturn(UUID.randomUUID());

        service.persistStepPayload(execution, "mcp:gmail", "gmail", result(output), Map.of(), 1);

        verify(storageService).saveJsonWithContext(eq("tenant-1"), any(), anyString(), isNull(), any(),
                eq("run-1"), any(), anyInt(), eq(1), anyInt(), eq("wf-1"), eq("STEP_OUTPUT"),
                eq(DataSensitivity.RESTRICTED));
        verify(storageService, never()).saveJsonWithContext(anyString(), any(), anyString(), any(), any(), any(),
                any(), anyInt(), anyInt(), anyInt(), any(), any());
    }

    @Test
    @DisplayName("the files a restricted step produced are tagged RESTRICTED too")
    void producedFilesAreTagged() {
        UUID fileId = UUID.randomUUID();
        Map<String, Object> output = new HashMap<>();
        output.put("attachment", Map.of("_type", "file", "id", fileId.toString(), "path", "t/x.pdf"));
        output.put("metadata", Map.of("iconSlug", "gmail"));
        when(storageService.saveJsonWithContext(anyString(), any(), anyString(), any(), any(), any(), any(),
                anyInt(), anyInt(), anyInt(), any(), any(), eq(DataSensitivity.RESTRICTED)))
                .thenReturn(UUID.randomUUID());

        service.persistStepPayload(execution, "mcp:gmail", "gmail", result(output), Map.of(), 1);

        verify(storageService).markRestricted(eq("tenant-1"), eq(List.of(fileId)), isNull());
    }

    @Test
    @DisplayName("a downstream NORMAL step of a run that already holds restricted data is saved RESTRICTED")
    void runTaintPropagates() {
        when(storageService.runHoldsRestrictedData("run-1")).thenReturn(true);
        when(storageService.saveJsonWithContext(anyString(), any(), anyString(), any(), any(), any(), any(),
                anyInt(), anyInt(), anyInt(), any(), any(), eq(DataSensitivity.RESTRICTED)))
                .thenReturn(UUID.randomUUID());
        StepExecutionResult transform = new StepExecutionResult("core:summary", NodeStatus.COMPLETED, "Success",
                Map.of("summary", "the CEO approved the wire"), 3L, null);

        service.persistStepPayload(execution, "core:summary", "summary", transform, Map.of(), 1);
        service.persistStepPayload(execution, "core:summary", "summary", transform, Map.of(), 2);

        verify(storageService, times(2)).saveJsonWithContext(anyString(), any(), anyString(), any(), any(), any(),
                any(), anyInt(), anyInt(), anyInt(), any(), any(), eq(DataSensitivity.RESTRICTED));
        // The positive answer is cached: the second payload of the run does not query again.
        verify(storageService, times(1)).runHoldsRestrictedData("run-1");
    }

    @Test
    @DisplayName("LC-066: the strict lookup propagates a storage failure, while isRunRestricted answers false for it")
    void strictLookupPropagatesFailure() {
        when(storageService.runHoldsRestrictedData("run-1")).thenThrow(new IllegalStateException("storage down"));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.isRunRestrictedStrict("run-1"))
                .isInstanceOf(IllegalStateException.class);
        assertThat(service.isRunRestricted("run-1")).isFalse();
    }

    @Test
    @DisplayName("LC-066: the strict lookup answers a marked run and a tagged run without failing")
    void strictLookupAnswersKnownRuns() {
        when(storageService.runHoldsRestrictedData("run-tagged")).thenReturn(true);
        when(storageService.runHoldsRestrictedData("run-plain")).thenReturn(false);
        service.markRunRestricted("run-marked");

        assertThat(service.isRunRestrictedStrict("run-marked")).isTrue();
        assertThat(service.isRunRestrictedStrict("run-tagged")).isTrue();
        assertThat(service.isRunRestrictedStrict("run-plain")).isFalse();
        assertThat(service.isRunRestrictedStrict(" ")).isFalse();
    }

    @Test
    @DisplayName("a run marked before its first payload (a fire forwarding restricted data) saves that first payload RESTRICTED, with no lookup")
    void markedRunTagsItsFirstPayload() {
        when(storageService.saveJsonWithContext(anyString(), any(), anyString(), any(), any(), any(), any(),
                anyInt(), anyInt(), anyInt(), any(), any(), eq(DataSensitivity.RESTRICTED)))
                .thenReturn(UUID.randomUUID());
        StepExecutionResult trigger = new StepExecutionResult("trigger:on_row", NodeStatus.COMPLETED, "Success",
                Map.of("subject", "Wire approved"), 1L, null);

        service.markRunRestricted("run-1");
        service.persistStepPayload(execution, "trigger:on_row", "on_row", trigger, Map.of(), 1);

        // Nothing is stored for this run yet, so only the mark can make this payload RESTRICTED.
        verify(storageService).saveJsonWithContext(anyString(), any(), anyString(), any(), any(), eq("run-1"),
                any(), anyInt(), anyInt(), anyInt(), any(), any(), eq(DataSensitivity.RESTRICTED));
        verify(storageService, never()).runHoldsRestrictedData(anyString());
        assertThat(service.isRunRestricted("run-1")).isTrue();
    }

    @Test
    @DisplayName("regression: a trigger output is classified by the platform's tag only, never by integration keys its caller sent")
    void triggerOutputIsClassifiedByThePlatformTagOnly() {
        when(storageService.runHoldsRestrictedData("run-1")).thenReturn(false);
        when(storageService.saveJsonWithContext(anyString(), any(), anyString(), any(), any(), any(), any(),
                anyInt(), anyInt(), anyInt(), any(), any())).thenReturn(UUID.randomUUID());
        when(storageService.saveJsonWithContext(anyString(), any(), anyString(), any(), any(), any(), any(),
                anyInt(), anyInt(), anyInt(), any(), any(), eq(DataSensitivity.RESTRICTED)))
                .thenReturn(UUID.randomUUID());
        // A public webhook body naming Gmail: the caller's data, not Gmail content the platform read.
        StepExecutionResult webhook = new StepExecutionResult("trigger:inbound", NodeStatus.COMPLETED, "Success",
                Map.of("iconSlug", "gmail", "metadata", Map.of("serviceType", "gmail")), 1L, null);
        // A table load holding RESTRICTED rows: the resolver's own tag.
        StepExecutionResult tableLoad = new StepExecutionResult("trigger:on_rows", NodeStatus.COMPLETED, "Success",
                Map.of("data", List.of(), DataSensitivity.CREDENTIAL_KEY, "RESTRICTED"), 1L, null);

        service.persistStepPayload(execution, "trigger:inbound", "inbound", webhook, Map.of(), 1);
        verify(storageService, never()).saveJsonWithContext(anyString(), any(), anyString(), any(), any(), any(),
                any(), anyInt(), anyInt(), anyInt(), any(), any(), eq(DataSensitivity.RESTRICTED));

        service.persistStepPayload(execution, "trigger:on_rows", "on_rows", tableLoad, Map.of(), 1);
        verify(storageService).saveJsonWithContext(anyString(), any(), anyString(), any(), any(), any(),
                any(), anyInt(), anyInt(), anyInt(), any(), any(), eq(DataSensitivity.RESTRICTED));
    }

    @Test
    @DisplayName("marking one run leaves every other run to its own classification")
    void markIsPerRun() {
        when(storageService.runHoldsRestrictedData("run-other")).thenReturn(false);

        service.markRunRestricted("run-1");
        service.markRunRestricted(null);

        assertThat(service.isRunRestricted("run-other")).isFalse();
        assertThat(service.isRunRestricted(null)).isFalse();
    }

    @Test
    @DisplayName("an ordinary step in an ordinary run keeps the untagged save path")
    void normalStepUnchanged() {
        when(storageService.runHoldsRestrictedData("run-1")).thenReturn(false);
        when(storageService.saveJsonWithContext(anyString(), any(), anyString(), any(), any(), any(), any(),
                anyInt(), anyInt(), anyInt(), any(), any())).thenReturn(UUID.randomUUID());
        Map<String, Object> output = new HashMap<>();
        output.put("repos", List.of("a"));
        output.put("metadata", Map.of("iconSlug", "github"));

        service.persistStepPayload(execution, "mcp:github", "github", result(output), Map.of(), 1);

        verify(storageService, never()).saveJsonWithContext(anyString(), any(), anyString(), any(), any(), any(),
                any(), anyInt(), anyInt(), anyInt(), any(), any(), any(DataSensitivity.class));
        verify(storageService, never()).markRestricted(any(), any(), any());
    }

    @Test
    @DisplayName("classify reads both the output metadata and the tool reference")
    void classifyUsesBothWitnesses() {
        assertThat(StepPayloadService.classify(result(Map.of("x", 1)), "gmail/list_messages"))
                .isEqualTo(DataSensitivity.RESTRICTED);
        assertThat(StepPayloadService.classify(result(Map.of("metadata", Map.of("iconSlug", "googledrive"))), "uuid"))
                .isEqualTo(DataSensitivity.RESTRICTED);
        assertThat(StepPayloadService.classify(result(Map.of("x", 1)), "slack/post"))
                .isEqualTo(DataSensitivity.NORMAL);
    }

    // ---- LC-066/CASA re-audit item 4: decision (empty extraMetadata) and skipped-node payloads ----
    //
    // Pre-fix, persistDecisionPayload's empty-extraMetadata branch and persistSkippedNodePayload
    // both bypassed isRunRestricted entirely: a decision with nothing to quote, or a skip
    // envelope naming the node/reason that caused the skip, was always saved NORMAL - never
    // expiring, never swept - even inside a run that already held Gmail/Drive content, breaking
    // the run-level taint invariant every other payload path in this class upholds.

    @Test
    @DisplayName("a decision with EMPTY extraMetadata in a restricted run is saved RESTRICTED")
    void emptyExtraMetadataDecisionInRestrictedRunIsTagged() {
        when(storageService.runHoldsRestrictedData("run-1")).thenReturn(true);
        when(storageService.saveJsonWithContext(anyString(), any(), anyString(), any(), any(), any(), any(),
                anyInt(), anyInt(), anyInt(), any(), any(), eq(DataSensitivity.RESTRICTED)))
                .thenReturn(UUID.randomUUID());

        StepPayloadResult outcome = service.persistStepPayloadOutcome(
                execution, "core:decide", "decide", null, Map.of(), 1, 1, 0);

        assertThat(outcome.stored()).isTrue();
        verify(storageService).saveJsonWithContext(eq("tenant-1"), any(), anyString(), isNull(), isNull(),
                eq("run-1"), any(), anyInt(), eq(1), eq(0), eq("wf-1"), eq("DECISION"),
                eq(DataSensitivity.RESTRICTED));
        verify(storageService, never()).saveJsonWithContext(anyString(), any(), anyString(), any(), any(), any(),
                any(), anyInt(), anyInt(), any(), any());
    }

    @Test
    @DisplayName("a decision with NULL extraMetadata in a restricted run is also saved RESTRICTED "
        + "(null takes the same empty-payload branch as an empty map)")
    void nullExtraMetadataDecisionInRestrictedRunIsTagged() {
        when(storageService.runHoldsRestrictedData("run-1")).thenReturn(true);
        when(storageService.saveJsonWithContext(anyString(), any(), anyString(), any(), any(), any(), any(),
                anyInt(), anyInt(), anyInt(), any(), any(), eq(DataSensitivity.RESTRICTED)))
                .thenReturn(UUID.randomUUID());

        StepPayloadResult outcome = service.persistStepPayloadOutcome(
                execution, "core:decide", "decide", null, null, 1, 1, 0);

        assertThat(outcome.stored()).isTrue();
        verify(storageService).saveJsonWithContext(eq("tenant-1"), any(), anyString(), isNull(), isNull(),
                eq("run-1"), any(), anyInt(), eq(1), eq(0), eq("wf-1"), eq("DECISION"),
                eq(DataSensitivity.RESTRICTED));
    }

    @Test
    @DisplayName("a decision with EMPTY extraMetadata in an ORDINARY run keeps the untagged save path")
    void emptyExtraMetadataDecisionInNormalRunUnchanged() {
        when(storageService.runHoldsRestrictedData("run-1")).thenReturn(false);
        when(storageService.saveJsonWithContext(anyString(), any(), anyString(), any(), any(), any(), any(),
                anyInt(), anyInt(), any(), any())).thenReturn(UUID.randomUUID());

        StepPayloadResult outcome = service.persistStepPayloadOutcome(
                execution, "core:decide", "decide", null, Map.of(), 1, 1, 0);

        assertThat(outcome.stored()).isTrue();
        verify(storageService, never()).saveJsonWithContext(anyString(), any(), anyString(), any(), any(), any(),
                any(), anyInt(), anyInt(), anyInt(), any(), any(), any(DataSensitivity.class));
    }

    @Test
    @DisplayName("a skipped-node payload in a restricted run is saved RESTRICTED")
    void skippedNodePayloadInRestrictedRunIsTagged() {
        when(storageService.runHoldsRestrictedData("run-restricted")).thenReturn(true);
        when(storageService.saveJsonWithContext(anyString(), any(), anyString(), any(), any(), any(), any(),
                any(), anyInt(), anyInt(), any(), any(), eq(DataSensitivity.RESTRICTED)))
                .thenReturn(UUID.randomUUID());

        UUID storageId = service.persistSkippedNodePayload(
                "tenant-1", Map.of("skipReason", "branch not taken"), 2, "run-restricted");

        assertThat(storageId).isNotNull();
        verify(storageService).saveJsonWithContext(eq("tenant-1"), any(), anyString(), isNull(), isNull(),
                isNull(), isNull(), isNull(), eq(2), eq(0), isNull(), eq("SKIPPED_NODE"),
                eq(DataSensitivity.RESTRICTED));
    }

    @Test
    @DisplayName("a skipped-node payload in an ORDINARY run is saved NORMAL")
    void skippedNodePayloadInNormalRunIsNormal() {
        when(storageService.runHoldsRestrictedData("run-1")).thenReturn(false);
        when(storageService.saveJsonWithContext(anyString(), any(), anyString(), any(), any(), any(), any(),
                any(), anyInt(), anyInt(), any(), any(), eq(DataSensitivity.NORMAL)))
                .thenReturn(UUID.randomUUID());

        UUID storageId = service.persistSkippedNodePayload(
                "tenant-1", Map.of("skipReason", "branch not taken"), 2, "run-1");

        assertThat(storageId).isNotNull();
        verify(storageService).saveJsonWithContext(eq("tenant-1"), any(), anyString(), isNull(), isNull(),
                isNull(), isNull(), isNull(), eq(2), eq(0), isNull(), eq("SKIPPED_NODE"),
                eq(DataSensitivity.NORMAL));
    }

    // ---- LC-066/LC-011 re-audit item 3: classifySensitivityForRun, the shared classify-and-tag
    // entry point for SignalResumeService and InterfaceActionService (neither has a
    // StepExecutionResult to build the normal classify(...) path from). ----

    @Test
    @DisplayName("classifySensitivityForRun: RESTRICTED when the run already holds restricted data")
    void classifySensitivityForRunRunTaint() {
        when(storageService.runHoldsRestrictedData("run-1")).thenReturn(true);

        assertThat(service.classifySensitivityForRun("run-1", Map.of("x", 1)))
                .isEqualTo(DataSensitivity.RESTRICTED);
    }

    @Test
    @DisplayName("classifySensitivityForRun: RESTRICTED when the payload's own metadata names a restricted integration")
    void classifySensitivityForRunPayloadMetadata() {
        // No runHoldsRestrictedData stub: the payload-metadata check alone must decide RESTRICTED
        // and short-circuit before isRunRestricted is ever consulted.
        // RestrictedDataPolicy.fromToolMetadata recurses exactly ONE level into a "metadata" key
        // of the map it is GIVEN - it is passed the whole payload here (as classify() passes the
        // whole step output), so "metadata" must sit at this map's own top level, not nested
        // further under "output" (see RestrictedDataPolicyTest for the traversal contract).
        Map<String, Object> payload = Map.of("metadata", Map.of("iconSlug", "gmail"));

        assertThat(service.classifySensitivityForRun("run-1", payload)).isEqualTo(DataSensitivity.RESTRICTED);
        verify(storageService, never()).runHoldsRestrictedData(any());
    }

    @Test
    @DisplayName("classifySensitivityForRun: NORMAL for an ordinary payload in an ordinary run")
    void classifySensitivityForRunNormal() {
        when(storageService.runHoldsRestrictedData("run-1")).thenReturn(false);

        assertThat(service.classifySensitivityForRun("run-1", Map.of("output", Map.of("x", 1))))
                .isEqualTo(DataSensitivity.NORMAL);
    }

    @Test
    @DisplayName("classifySensitivityForRun: a null runId is treated as not-restricted")
    void classifySensitivityForRunNullRunId() {
        assertThat(service.classifySensitivityForRun(null, Map.of("output", Map.of("x", 1))))
                .isEqualTo(DataSensitivity.NORMAL);
        verify(storageService, never()).runHoldsRestrictedData(any());
    }

    @Test
    @DisplayName("the legacy 3-arg persistSkippedNodePayload (no runId) never restricted-tags - back-compat, no regression")
    void skippedNodePayloadLegacyOverloadStaysNormal() {
        when(storageService.saveJsonWithContext(anyString(), any(), anyString(), any(), any(), any(), any(),
                any(), anyInt(), anyInt(), any(), any(), eq(DataSensitivity.NORMAL)))
                .thenReturn(UUID.randomUUID());

        UUID storageId = service.persistSkippedNodePayload("tenant-1", Map.of("skipReason", "x"), 2);

        assertThat(storageId).isNotNull();
        verify(storageService, never()).runHoldsRestrictedData(any());
        verify(storageService).saveJsonWithContext(eq("tenant-1"), any(), anyString(), isNull(), isNull(),
                isNull(), isNull(), isNull(), eq(2), eq(0), isNull(), eq("SKIPPED_NODE"),
                eq(DataSensitivity.NORMAL));
    }
}
