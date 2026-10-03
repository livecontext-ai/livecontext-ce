package com.apimarketplace.orchestrator.services.publication;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.apimarketplace.interfaces.client.InterfaceClient;
import com.apimarketplace.orchestrator.domain.WorkflowRunEntity;
import com.apimarketplace.orchestrator.persistence.WorkflowStepDataRepository;
import com.apimarketplace.orchestrator.repository.SignalWaitRepository;
import com.apimarketplace.orchestrator.repository.WorkflowRunRepository;
import com.apimarketplace.orchestrator.services.InterfaceRenderService;
import com.apimarketplace.orchestrator.services.StepAggregationService;
import com.apimarketplace.orchestrator.services.StorageSkeletonService;
import com.apimarketplace.orchestrator.services.epoch.WorkflowEpochService;
import com.apimarketplace.orchestrator.services.resume.WorkflowResumeService;
import com.apimarketplace.orchestrator.services.state.StateSnapshotService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The {@code stepFiles} section: the file a node produced, frozen per epoch so a marketplace
 * visitor's canvas can hang the same pill under the node that the owner's canvas does.
 *
 * <p>It has to exist as its own section because nothing else in the snapshot carries it. An
 * epoch-pinned capture drops {@code runState.steps[].output} entirely and {@code aggregatedSteps}
 * never held outputs, so before this the published canvas showed no file under any node - and,
 * more quietly, the publish-time copy pass had no step FileRef to preserve, which is what made a
 * published preview depend on the publisher never deleting the file.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("ShowcaseSnapshotBuilder - stepFiles")
class ShowcaseSnapshotBuilderStepFilesTest {

    @Mock private WorkflowRunRepository workflowRunRepository;
    @Mock private WorkflowResumeService workflowResumeService;
    @Mock private StateSnapshotService stateSnapshotService;
    @Mock private WorkflowEpochService workflowEpochService;
    @Mock private StepAggregationService stepAggregationService;
    @Mock private SignalWaitRepository signalWaitRepository;
    @Mock private InterfaceRenderService interfaceRenderService;
    @Mock private InterfaceClient interfaceClient;
    @Mock private WorkflowStepDataRepository workflowStepDataRepository;
    @Mock private StorageSkeletonService storageSkeletonService;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ShowcaseSnapshotBuilder builder() {
        return new ShowcaseSnapshotBuilder(
                workflowRunRepository,
                workflowResumeService,
                stateSnapshotService,
                workflowEpochService,
                stepAggregationService,
                signalWaitRepository,
                interfaceRenderService,
                interfaceClient,
                workflowStepDataRepository,
                storageSkeletonService,
                MAPPER,
                org.mockito.Mockito.mock(com.apimarketplace.orchestrator.services.persistence.StepPayloadService.class));
    }

    /** Minimal stand-in for the repository's alias + output-storage projection. */
    private record OutputRef(String alias, UUID storageId)
            implements WorkflowStepDataRepository.EpochOutputProjection {
        @Override public String getStepAlias() { return alias; }
        @Override public UUID getOutputStorageId() { return storageId; }
    }

    private WorkflowRunEntity run(String runId, String tenantId) {
        WorkflowRunEntity run = new WorkflowRunEntity();
        ReflectionTestUtils.setField(run, "id", UUID.randomUUID());
        run.setRunIdPublic(runId);
        run.setTenantId(tenantId);
        run.setOrganizationId(null);
        when(workflowRunRepository.findByRunIdPublic(runId)).thenReturn(Optional.of(run));
        when(interfaceClient.getSnapshotsForRun(run.getId(), tenantId, null)).thenReturn(List.of());
        return run;
    }

    /** Make an epoch-pinned capture believe the epoch exists (durable per-epoch rows). */
    private void stubEpochExists(String runId, int epoch) {
        when(workflowEpochService.listEpochTimestamps(runId)).thenReturn(List.of());
        when(workflowEpochService.getEpochState(runId, epoch)).thenReturn(Map.of(
                "epoch", epoch,
                "nodes", Map.of("core:download_file", Map.of("COMPLETED", 1)),
                "edges", Map.of()));
        when(stepAggregationService.getAggregatedSteps(runId, epoch)).thenReturn(Optional.of(List.of()));
        when(signalWaitRepository.findActiveByRunIdAndEpoch(runId, epoch)).thenReturn(List.of());
    }

    /** The run's epoch list, which is what an unpinned capture discovers its epochs from. */
    private void stubEpochTimestamps(String runId, int... epochs) {
        List<com.apimarketplace.orchestrator.repository.WorkflowEpochRepository.EpochTimestampRow> rows =
                new java.util.ArrayList<>();
        for (int epoch : epochs) {
            rows.add(new com.apimarketplace.orchestrator.repository.WorkflowEpochRepository
                    .EpochTimestampRow(epoch, null, null));
        }
        when(workflowEpochService.listEpochTimestamps(runId)).thenReturn(rows);
    }

    /** The whole stored output of a step. */
    private void stubOutput(UUID storageId, String tenantId, String json) {
        stubOutputAt(storageId, tenantId, "output", json);
    }

    /** One JSON path of a step's stored output; "output.file" is the narrow path tried first. */
    private void stubOutputAt(UUID storageId, String tenantId, String jsonPath, String json) {
        try {
            when(storageSkeletonService.getObjectAtPath(storageId, tenantId, jsonPath))
                    .thenReturn(Optional.of(MAPPER.readTree(json)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String fileRefJson(String path, String name, String mime, int size) {
        return "{\"_type\":\"file\",\"path\":\"" + path + "\",\"name\":\"" + name
                + "\",\"mimeType\":\"" + mime + "\",\"size\":" + size + "}";
    }

    private static String fileOutput(String path, String name, String mime, int size) {
        return "{\"file\":" + fileRefJson(path, name, mime, size) + "}";
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> stepFiles(Optional<Map<String, Object>> snapshot) {
        return (Map<String, Object>) snapshot.orElseThrow().get("stepFiles");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> aliasesOf(Map<String, Object> section, String epochKey) {
        return (Map<String, Object>) section.get(epochKey);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> refOf(Map<String, Object> perAlias, String alias) {
        return (Map<String, Object>) perAlias.get(alias);
    }

    @Test
    @DisplayName("freezes the FileRef a node produced, under the epoch the capture was pinned to")
    void freezesTheNodesFileRef() {
        run("run-1", "tenant-owner");
        stubEpochExists("run-1", 3);
        UUID storageId = UUID.randomUUID();
        when(workflowStepDataRepository.findCompletedOutputRefsByRunIdAndEpoch("run-1", 3))
                .thenReturn(List.of(new OutputRef("download_file", storageId)));
        stubOutput(storageId, "tenant-owner",
                "{\"file\":{\"_type\":\"file\",\"path\":\"7/wf/run/clip.mp4\",\"name\":\"clip.mp4\","
                        + "\"mimeType\":\"video/mp4\",\"size\":2048,\"id\":\"st-9\"}}");

        Map<String, Object> section = stepFiles(builder().capture("run-1", "tenant-owner", null, 3));

        // A pinned capture is renumbered to epoch 1 so the marketplace always shows clean
        // numbering - the section must follow that, or the canvas looks up epoch 1 and finds 3.
        assertThat(section).containsOnlyKeys("1");
        Map<String, Object> perAlias = aliasesOf(section, "1");
        assertThat(perAlias).containsOnlyKeys("download_file");
        assertThat(refOf(perAlias, "download_file"))
                .containsEntry("_type", "file")
                .containsEntry("path", "7/wf/run/clip.mp4")
                .containsEntry("name", "clip.mp4")
                .containsEntry("mimeType", "video/mp4")
                .containsEntry("size", 2048L)
                .containsEntry("id", "st-9");
    }

    @Test
    @DisplayName("a node that ran several times contributes its MOST RECENT file, not its first")
    void latestExecutionWins() {
        // The projection comes back ordered by row id, so the last row of an alias is its most
        // recently WRITTEN execution. Freezing the FIRST would pin a file the node has since
        // replaced, which is never what a showcase wants to show.
        run("run-2", "tenant-owner");
        stubEpochExists("run-2", 3);
        UUID older = UUID.randomUUID();
        UUID newer = UUID.randomUUID();
        when(workflowStepDataRepository.findCompletedOutputRefsByRunIdAndEpoch("run-2", 3))
                .thenReturn(List.of(new OutputRef("render", older), new OutputRef("render", newer)));
        stubOutput(older, "tenant-owner", fileOutput("7/a/first.png", "first.png", "image/png", 1));
        stubOutput(newer, "tenant-owner", fileOutput("7/a/last.png", "last.png", "image/png", 2));

        Map<String, Object> section = stepFiles(builder().capture("run-2", "tenant-owner", null, 3));

        assertThat(refOf(aliasesOf(section, "1"), "render")).containsEntry("path", "7/a/last.png");
        verify(storageSkeletonService, never()).getObjectAtPath(older, "tenant-owner", "output");
    }

    @Test
    @DisplayName("a node whose output holds no file contributes no entry at all")
    void nodeWithoutAFileIsAbsent() {
        run("run-3", "tenant-owner");
        stubEpochExists("run-3", 3);
        UUID storageId = UUID.randomUUID();
        when(workflowStepDataRepository.findCompletedOutputRefsByRunIdAndEpoch("run-3", 3))
                .thenReturn(List.of(new OutputRef("fetch_json", storageId)));
        stubOutput(storageId, "tenant-owner", "{\"items\":[{\"id\":1}],\"status\":\"ok\"}");

        assertThat(stepFiles(builder().capture("run-3", "tenant-owner", null, 3))).isEmpty();
    }

    @Test
    @DisplayName("a FileRef with no path is skipped - it can be neither copied nor signed")
    void pathlessRefIsSkipped() {
        // The publish-time copy keys the namespace copy on the path and the render-time signer
        // mints the visitor's URL from it. Freezing a path-less ref would put a pill on the
        // canvas that can never open.
        run("run-4", "tenant-owner");
        stubEpochExists("run-4", 3);
        UUID storageId = UUID.randomUUID();
        when(workflowStepDataRepository.findCompletedOutputRefsByRunIdAndEpoch("run-4", 3))
                .thenReturn(List.of(new OutputRef("legacy", storageId)));
        stubOutput(storageId, "tenant-owner",
                "{\"file\":{\"_type\":\"file\",\"name\":\"ghost.bin\",\"mimeType\":\"application/octet-stream\",\"size\":3}}");

        assertThat(stepFiles(builder().capture("run-4", "tenant-owner", null, 3))).isEmpty();
    }

    @Test
    @DisplayName("outputs are read under the RUN OWNER's tenant, not the caller's")
    void readsUnderTheRunOwnerTenant() {
        // Storage reads are tenant-scoped and answer empty for the wrong tenant, silently. An
        // org-mate capturing a run they can see would otherwise freeze a canvas with no files.
        WorkflowRunEntity run = new WorkflowRunEntity();
        ReflectionTestUtils.setField(run, "id", UUID.randomUUID());
        run.setRunIdPublic("run-5");
        run.setTenantId("tenant-owner");
        run.setOrganizationId("org-acme");
        when(workflowRunRepository.findByRunIdPublic("run-5")).thenReturn(Optional.of(run));
        when(interfaceClient.getSnapshotsForRun(run.getId(), "tenant-mate", "org-acme")).thenReturn(List.of());
        stubEpochExists("run-5", 3);
        UUID storageId = UUID.randomUUID();
        when(workflowStepDataRepository.findCompletedOutputRefsByRunIdAndEpoch("run-5", 3))
                .thenReturn(List.of(new OutputRef("render", storageId)));
        stubOutput(storageId, "tenant-owner", fileOutput("7/a/x.png", "x.png", "image/png", 4));

        Map<String, Object> section = stepFiles(builder().capture("run-5", "tenant-mate", "org-acme", 3));

        assertThat(section).containsKey("1");
        verify(storageSkeletonService, never()).getObjectAtPath(any(), eq("tenant-mate"), anyString());
    }

    @Test
    @DisplayName("a run with no tenant yields an empty section and spends no storage read")
    void noTenantMeansNoReads() {
        run("run-6", null);
        stubEpochExists("run-6", 3);

        assertThat(stepFiles(builder().capture("run-6", null, null, 3))).isEmpty();
        verify(workflowStepDataRepository, never()).findCompletedOutputRefsByRunIdAndEpoch(anyString(), anyInt());
    }

    @Test
    @DisplayName("an unpinned capture keys the section by the run's real epoch numbers")
    void unpinnedCaptureKeepsRealEpochNumbers() {
        run("run-7", "tenant-owner");
        when(workflowEpochService.listEpochTimestamps("run-7")).thenReturn(List.of());
        UUID storageId = UUID.randomUUID();
        when(workflowStepDataRepository.findCompletedOutputRefsByRunIdAndEpoch("run-7", 0))
                .thenReturn(List.of(new OutputRef("download_file", storageId)));
        stubOutput(storageId, "tenant-owner", fileOutput("7/a/one.pdf", "one.pdf", "application/pdf", 5));

        Map<String, Object> section = stepFiles(builder().capture("run-7", "tenant-owner", null, null));

        assertThat(section).containsOnlyKeys("0");
    }

    @Test
    @DisplayName("reads the canonical output.file first, and does not touch the whole output when it hits")
    void narrowPathAvoidsReadingTheWholeOutput() {
        // Every static file producer puts its ref exactly there. Reading the full output would
        // materialise a scraper's or an LLM step's megabytes as a String only to throw them away.
        run("run-fast", "tenant-owner");
        stubEpochExists("run-fast", 3);
        UUID storageId = UUID.randomUUID();
        when(workflowStepDataRepository.findCompletedOutputRefsByRunIdAndEpoch("run-fast", 3))
                .thenReturn(List.of(new OutputRef("download_file", storageId)));
        stubOutputAt(storageId, "tenant-owner", "output.file",
                fileRefJson("7/a/fast.mp4", "fast.mp4", "video/mp4", 7));

        Map<String, Object> section = stepFiles(builder().capture("run-fast", "tenant-owner", null, 3));

        assertThat(refOf(aliasesOf(section, "1"), "download_file")).containsEntry("path", "7/a/fast.mp4");
        verify(storageSkeletonService, never()).getObjectAtPath(storageId, "tenant-owner", "output");
    }

    @Test
    @DisplayName("falls back to the whole output for a tool that nests its ref deeper")
    void fallsBackToTheWholeOutput() {
        run("run-deep", "tenant-owner");
        stubEpochExists("run-deep", 3);
        UUID storageId = UUID.randomUUID();
        when(workflowStepDataRepository.findCompletedOutputRefsByRunIdAndEpoch("run-deep", 3))
                .thenReturn(List.of(new OutputRef("image_generation", storageId)));
        stubOutput(storageId, "tenant-owner",
                "{\"data\":{\"images\":[" + fileRefJson("7/a/deep.png", "deep.png", "image/png", 8) + "]}}");

        Map<String, Object> section = stepFiles(builder().capture("run-deep", "tenant-owner", null, 3));

        assertThat(refOf(aliasesOf(section, "1"), "image_generation")).containsEntry("path", "7/a/deep.png");
    }

    @Test
    @DisplayName("the read budget is spent across ALL epochs together, not per epoch")
    void readBudgetIsGlobal() {
        // Per-epoch would authorise epochs x nodes reads inside one read-only transaction, and
        // that multiplier is what makes a long-lived reusable trigger expensive to publish.
        run("run-budget", "tenant-owner");
        stubEpochTimestamps("run-budget", 0, 1, 2, 3, 4);
        for (int e = 0; e <= 4; e++) {
            List<WorkflowStepDataRepository.EpochOutputProjection> rows = new java.util.ArrayList<>();
            for (int n = 0; n < 200; n++) {
                UUID id = UUID.randomUUID();
                rows.add(new OutputRef("node_" + n, id));
                stubOutputAt(id, "tenant-owner", "output.file",
                        fileRefJson("7/a/e" + e + "n" + n + ".png", "x.png", "image/png", 1));
            }
            when(workflowStepDataRepository.findCompletedOutputRefsByRunIdAndEpoch("run-budget", e))
                    .thenReturn(rows);
        }

        Map<String, Object> section = stepFiles(builder().capture("run-budget", "tenant-owner", null, null));

        int frozen = section.values().stream().mapToInt(v -> ((Map<?, ?>) v).size()).sum();
        assertThat(frozen)
                .as("5 epochs x 200 nodes must not authorise 1000 storage reads")
                .isLessThanOrEqualTo(400);
        assertThat(frozen).isGreaterThan(0);
    }

    @Test
    @DisplayName("the budget is spent NEWEST epoch first, so an exhausted one costs the oldest their pills")
    void budgetIsSpentNewestFirst() {
        run("run-order", "tenant-owner");
        stubEpochTimestamps("run-order", 0, 1, 2);
        for (int e = 0; e <= 2; e++) {
            List<WorkflowStepDataRepository.EpochOutputProjection> rows = new java.util.ArrayList<>();
            for (int n = 0; n < 200; n++) {
                UUID id = UUID.randomUUID();
                rows.add(new OutputRef("node_" + n, id));
                stubOutputAt(id, "tenant-owner", "output.file",
                        fileRefJson("7/a/e" + e + "n" + n + ".png", "x.png", "image/png", 1));
            }
            when(workflowStepDataRepository.findCompletedOutputRefsByRunIdAndEpoch("run-order", e))
                    .thenReturn(rows);
        }

        Map<String, Object> section = stepFiles(builder().capture("run-order", "tenant-owner", null, null));

        assertThat(section).as("the newest epoch is the one a visitor is most likely to open")
                .containsKey("2");
        assertThat(section).doesNotContainKey("0");
    }

    @Test
    @DisplayName("a row lookup that throws costs that epoch its pills, never the capture")
    void rowLookupFailureIsContained() {
        run("run-boom", "tenant-owner");
        stubEpochExists("run-boom", 3);
        when(workflowStepDataRepository.findCompletedOutputRefsByRunIdAndEpoch("run-boom", 3))
                .thenThrow(new RuntimeException("db down"));

        Optional<Map<String, Object>> snapshot = builder().capture("run-boom", "tenant-owner", null, 3);

        assertThat(snapshot).isPresent();
        assertThat(stepFiles(snapshot)).isEmpty();
    }

    @Test
    @DisplayName("an output read that throws costs THAT node its pill, and the others still get theirs")
    void outputReadFailureIsPerNode() {
        run("run-partial", "tenant-owner");
        stubEpochExists("run-partial", 3);
        UUID broken = UUID.randomUUID();
        UUID fine = UUID.randomUUID();
        when(workflowStepDataRepository.findCompletedOutputRefsByRunIdAndEpoch("run-partial", 3))
                .thenReturn(List.of(new OutputRef("broken", broken), new OutputRef("fine", fine)));
        when(storageSkeletonService.getObjectAtPath(broken, "tenant-owner", "output.file"))
                .thenThrow(new RuntimeException("storage down"));
        stubOutputAt(fine, "tenant-owner", "output.file", fileRefJson("7/a/ok.png", "ok.png", "image/png", 2));

        Map<String, Object> section = stepFiles(builder().capture("run-partial", "tenant-owner", null, 3));

        assertThat(aliasesOf(section, "1")).containsOnlyKeys("fine");
    }

    @Test
    @DisplayName("a row with no alias or no output storage id is skipped without a read")
    void malformedRowsAreSkipped() {
        run("run-null", "tenant-owner");
        stubEpochExists("run-null", 3);
        UUID good = UUID.randomUUID();
        when(workflowStepDataRepository.findCompletedOutputRefsByRunIdAndEpoch("run-null", 3))
                .thenReturn(java.util.Arrays.asList(
                        new OutputRef(null, UUID.randomUUID()),
                        new OutputRef("  ", UUID.randomUUID()),
                        new OutputRef("orphan", null),
                        new OutputRef("good", good)));
        stubOutputAt(good, "tenant-owner", "output.file", fileRefJson("7/a/ok.png", "ok.png", "image/png", 2));

        Map<String, Object> section = stepFiles(builder().capture("run-null", "tenant-owner", null, 3));

        assertThat(aliasesOf(section, "1")).containsOnlyKeys("good");
    }

    @Test
    @DisplayName("an unpinned capture keys EVERY epoch it froze, not just the first")
    void unpinnedCaptureKeepsEveryEpoch() {
        run("run-multi", "tenant-owner");
        stubEpochTimestamps("run-multi", 0, 1, 2);
        for (int e = 0; e <= 2; e++) {
            UUID id = UUID.randomUUID();
            when(workflowStepDataRepository.findCompletedOutputRefsByRunIdAndEpoch("run-multi", e))
                    .thenReturn(List.of(new OutputRef("render", id)));
            stubOutputAt(id, "tenant-owner", "output.file",
                    fileRefJson("7/a/e" + e + ".png", "e" + e + ".png", "image/png", 1));
        }

        Map<String, Object> section = stepFiles(builder().capture("run-multi", "tenant-owner", null, null));

        assertThat(section).containsOnlyKeys("0", "1", "2");
        assertThat(refOf(aliasesOf(section, "2"), "render")).containsEntry("path", "7/a/e2.png");
    }

    @Test
    @DisplayName("the budget counts READS, so a step whose fast path misses costs two of them")
    void budgetChargesEachRead() {
        // Charging one per STEP would let a run of catalog/MCP tools - none of which write the
        // canonical output.file - issue twice the round trips the budget names, and the
        // expensive whole-output extraction is the second of the two.
        run("run-miss", "tenant-owner");
        stubEpochExists("run-miss", 3);
        List<WorkflowStepDataRepository.EpochOutputProjection> rows = new java.util.ArrayList<>();
        for (int n = 0; n < 400; n++) {
            UUID id = UUID.randomUUID();
            rows.add(new OutputRef("node_" + n, id));
            // No output.file: the fast path misses and the whole output has to be read.
            stubOutput(id, "tenant-owner",
                    "{\"data\":{\"images\":[" + fileRefJson("7/a/n" + n + ".png", "x.png", "image/png", 1) + "]}}");
        }
        when(workflowStepDataRepository.findCompletedOutputRefsByRunIdAndEpoch("run-miss", 3)).thenReturn(rows);

        Map<String, Object> section = stepFiles(builder().capture("run-miss", "tenant-owner", null, 3));

        assertThat(aliasesOf(section, "1"))
                .as("400 reads at two per step is 200 nodes, not 400")
                .hasSize(200);
        verify(storageSkeletonService, org.mockito.Mockito.times(400))
                .getObjectAtPath(any(), anyString(), anyString());
    }

    @Test
    @DisplayName("the canonical output.file WINS over a ref sitting elsewhere in the same output - a selection rule, not just a shortcut")
    void canonicalFieldWinsOverAnEarlierRef() {
        // A walk of the whole output would take the first ref in encounter order, which is what
        // the owner's canvas does. The canonical field is the one the platform itself writes,
        // and preferring it beats letting JSON key order decide.
        run("run-both", "tenant-owner");
        stubEpochExists("run-both", 3);
        UUID storageId = UUID.randomUUID();
        when(workflowStepDataRepository.findCompletedOutputRefsByRunIdAndEpoch("run-both", 3))
                .thenReturn(List.of(new OutputRef("odd_tool", storageId)));
        stubOutputAt(storageId, "tenant-owner", "output.file",
                fileRefJson("7/a/canonical.mp4", "canonical.mp4", "video/mp4", 2));
        stubOutput(storageId, "tenant-owner",
                "{\"images\":[" + fileRefJson("7/a/elsewhere.png", "elsewhere.png", "image/png", 1) + "],"
                        + "\"file\":" + fileRefJson("7/a/canonical.mp4", "canonical.mp4", "video/mp4", 2) + "}");

        Map<String, Object> section = stepFiles(builder().capture("run-both", "tenant-owner", null, 3));

        assertThat(refOf(aliasesOf(section, "1"), "odd_tool")).containsEntry("path", "7/a/canonical.mp4");
    }

    @Test
    @DisplayName("the epoch cap is a SECOND ceiling: a run of many small epochs is cut by it long before the read budget bites")
    void epochCapCutsBeforeTheBudget() {
        // 25 epochs x 2 fast-path steps is 50 reads, nowhere near the budget - so the budget
        // tests can never see this limit, and without a case here an off-by-one or a dropped
        // subList would change which epochs a visitor can browse with nothing failing.
        run("run-many-epochs", "tenant-owner");
        int[] all = new int[25];
        for (int e = 0; e < 25; e++) all[e] = e;
        stubEpochTimestamps("run-many-epochs", all);
        for (int e = 0; e < 25; e++) {
            UUID id = UUID.randomUUID();
            when(workflowStepDataRepository.findCompletedOutputRefsByRunIdAndEpoch("run-many-epochs", e))
                    .thenReturn(List.of(new OutputRef("render", id)));
            stubOutputAt(id, "tenant-owner", "output.file",
                    fileRefJson("7/a/e" + e + ".png", "e" + e + ".png", "image/png", 1));
        }

        Map<String, Object> section = stepFiles(builder().capture("run-many-epochs", "tenant-owner", null, null));

        assertThat(section).hasSize(20);
        assertThat(section).as("the newest epochs are the ones a visitor opens").containsKey("24");
        assertThat(section).as("the oldest are the ones dropped").doesNotContainKey("4");
        assertThat(section).containsKey("5");
    }
}
