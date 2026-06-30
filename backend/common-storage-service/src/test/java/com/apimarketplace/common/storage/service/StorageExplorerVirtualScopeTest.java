package com.apimarketplace.common.storage.service;

import com.apimarketplace.common.storage.dto.StorageExplorerDto;
import com.apimarketplace.common.storage.dto.StorageExplorerProjection;
import com.apimarketplace.common.storage.dto.VirtualFolderAddress;
import com.apimarketplace.common.storage.dto.VirtualFolderAddress.Level;
import com.apimarketplace.common.storage.repository.StorageExplorerRepository;
import com.apimarketplace.common.storage.repository.StorageExplorerRepository.SliceResult;
import com.apimarketplace.common.storage.repository.StorageExplorerRepository.VirtualGroup;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import org.mockito.ArgumentCaptor;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("StorageExplorerService.searchVirtualScope - run level / collapse / root composition / pagination")
class StorageExplorerVirtualScopeTest {

    private static final String ORG = "org-1";
    private static final String WF = "11111111-2222-3333-4444-555555555555";
    private static final String RUN_A = "run-aaaa";
    private static final String RUN_B = "run-bbbb";

    private StorageExplorerRepository repo;
    private StorageExplorerService service;

    @BeforeEach
    void setUp() {
        repo = mock(StorageExplorerRepository.class);
        service = new StorageExplorerService(repo);
        // Sensible empties so a level's other queries (runs, previews, manual folders, leaf files) don't NPE.
        lenient().when(repo.previewFilesForVirtualGroups(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(Map.of());
        lenient().when(repo.listRootManualFolders(any(), any(), any())).thenReturn(List.of());
        lenient().when(repo.countChildrenByParent(any(), any(), any())).thenReturn(Map.of());
        lenient().when(repo.findPreviewFilesByParent(any(), any(), any())).thenReturn(Map.of());
        lenient().when(repo.listVirtualLeafFiles(any(), any(), any(), any(), any(), any(), any(), any(), any(),
                anyBoolean(), anyBoolean(), any(), any(), any(), any(), anyBoolean(), anyInt(), anyInt()))
                .thenReturn(new SliceResult(List.of(), 0));
    }

    private static Pageable page(int p, int size) {
        return PageRequest.of(p, size);
    }

    private static VirtualGroup group(String key, long count) {
        return new VirtualGroup(key, count, Instant.parse("2026-06-08T00:00:00Z"));
    }

    private static StorageExplorerProjection fileRow(UUID id, String name) {
        return new StorageExplorerProjection(id, "S3_FILE", "STEP_OUTPUT", name, "image/png",
                100, Instant.parse("2026-06-08T00:00:00Z"), WF, null, "run", "step", 0,
                "key/" + name, null, false, null);
    }

    private static StorageExplorerProjection manualFolderRow(UUID id, String name) {
        return new StorageExplorerProjection(id, null, null, name, null,
                null, Instant.parse("2026-06-08T00:00:00Z"), null, null, null, null, null,
                null, null, true, null);
    }

    // ============================ Date-scope threading ============================

    @Test
    @DisplayName("date-scoped overload threads dateFrom/dateTo into the leaf-file query "
            + "(regression: the virtual path dropped the date filter, leaving them null)")
    void dateScopedOverloadThreadsDatesIntoLeafFiles() {
        Instant from = Instant.parse("2026-06-01T00:00:00Z");
        Instant to = Instant.parse("2026-06-30T23:59:59Z");

        // ITERATION level goes straight to leafFiles() → listVirtualLeafFiles, no GROUP-BY in the way.
        service.searchVirtualScope("1", ORG, new VirtualFolderAddress(WF, 0, 0, 0), null, null, null,
                true, true, null, from, to, List.of(), page(0, 20));

        verify(repo).listVirtualLeafFiles(eq(ORG), eq(WF), any(), eq(0), eq(0), eq(0),
                any(), any(), any(), anyBoolean(), anyBoolean(), any(),
                eq(from), eq(to), any(), eq(false), anyInt(), anyInt());
    }

    @Test
    @DisplayName("back-compat overload (no dates) passes null dateFrom/dateTo through (full window)")
    void backCompatOverloadPassesNullDates() {
        service.searchVirtualScope("1", ORG, new VirtualFolderAddress(WF, 0, 0, 0), null, null, null,
                true, true, null, List.of(), page(0, 20));

        verify(repo).listVirtualLeafFiles(eq(ORG), eq(WF), any(), eq(0), eq(0), eq(0),
                any(), any(), any(), anyBoolean(), anyBoolean(), any(),
                isNull(), isNull(), any(), eq(false), anyInt(), anyInt());
    }

    // ============================ RUN level ============================

    @Nested
    @DisplayName("RUN level (workflow → run → epoch)")
    class RunLevel {

        @Test
        @DisplayName("workflow with >1 run → RUN folders (epochs would collide across runs otherwise)")
        void multipleRunsYieldRunFolders() {
            when(repo.listVirtualGroups(eq(ORG), eq(Level.RUN), eq(WF), eq(null), eq(null), eq(null), any(), any()))
                    .thenReturn(List.of(group(RUN_A, 3), group(RUN_B, 2)));

            Page<StorageExplorerDto> p = service.searchVirtualScope("1", ORG,
                    new VirtualFolderAddress(WF, null, null, null), null, null, null,
                    true, true, null, List.of(), page(0, 20));

            assertThat(p.getContent()).hasSize(2);
            assertThat(p.getContent()).allMatch(d -> "RUN".equals(d.virtualKind()));
            assertThat(p.getContent()).extracting(StorageExplorerDto::virtualId)
                    .containsExactly("wf:" + WF + "/r" + RUN_A, "wf:" + WF + "/r" + RUN_B);
            // The 1-based run number ("Run 1", "Run 2") rides in the epoch coordinate, oldest-first.
            assertThat(p.getContent()).extracting(StorageExplorerDto::epoch).containsExactly(1, 2);
            // The collapsed epoch path is NOT taken when there are ≥2 runs.
            verify(repo, never()).listVirtualGroups(eq(ORG), eq(Level.EPOCH), eq(WF), eq(null), any(), any(), any(), any());
        }

        @Test
        @DisplayName("workflow with exactly 1 run → COLLAPSE: epoch folders directly (no run nesting), run unconstrained")
        void singleRunCollapsesToEpochs() {
            when(repo.listVirtualGroups(eq(ORG), eq(Level.RUN), eq(WF), eq(null), eq(null), eq(null), any(), any()))
                    .thenReturn(List.of(group(RUN_A, 6)));
            when(repo.listVirtualGroups(eq(ORG), eq(Level.EPOCH), eq(WF), eq(null), eq(null), eq(null), any(), any()))
                    .thenReturn(List.of(group("0", 4), group("1", 2)));

            Page<StorageExplorerDto> p = service.searchVirtualScope("1", ORG,
                    new VirtualFolderAddress(WF, null, null, null), null, null, null,
                    true, true, null, List.of(), page(0, 20));

            assertThat(p.getContent()).hasSize(2);
            assertThat(p.getContent()).allMatch(d -> "EPOCH".equals(d.virtualKind()));
            // Collapsed → epoch address carries NO run segment. virtualId keeps the RAW epoch.
            assertThat(p.getContent()).extracting(StorageExplorerDto::virtualId)
                    .containsExactly("wf:" + WF + "/e0", "wf:" + WF + "/e1");
            // The label-bearing epoch coordinate is the REAL stored epoch (matches the EpochSlider),
            // shown oldest-first - here raw epochs 0,1.
            assertThat(p.getContent()).extracting(StorageExplorerDto::epoch).containsExactly(0, 1);
        }

        @Test
        @DisplayName("workflow with 0 runs (e.g. all filtered out) → COLLAPSE to epochs")
        void zeroRunsCollapsesToEpochs() {
            when(repo.listVirtualGroups(eq(ORG), eq(Level.RUN), eq(WF), eq(null), eq(null), eq(null), any(), any()))
                    .thenReturn(List.of());
            when(repo.listVirtualGroups(eq(ORG), eq(Level.EPOCH), eq(WF), eq(null), eq(null), eq(null), any(), any()))
                    .thenReturn(List.of(group("0", 1)));

            Page<StorageExplorerDto> p = service.searchVirtualScope("1", ORG,
                    new VirtualFolderAddress(WF, null, null, null), null, null, null,
                    true, true, null, List.of(), page(0, 20));

            assertThat(p.getContent()).hasSize(1);
            assertThat(p.getContent().get(0).virtualKind()).isEqualTo("EPOCH");
        }

        @Test
        @DisplayName("RUN level (wf:<id>/r<run>) → that run's epoch folders, addresses scoped to the run")
        void runLevelListsItsEpochs() {
            when(repo.listVirtualGroups(eq(ORG), eq(Level.EPOCH), eq(WF), eq(RUN_A), eq(null), eq(null), any(), any()))
                    .thenReturn(List.of(group("0", 2), group("1", 5)));

            Page<StorageExplorerDto> p = service.searchVirtualScope("1", ORG,
                    new VirtualFolderAddress(WF, RUN_A, null, null, null), null, null, null,
                    true, true, null, List.of(), page(0, 20));

            assertThat(p.getContent()).hasSize(2);
            assertThat(p.getContent()).allMatch(d -> "EPOCH".equals(d.virtualKind()));
            // The epoch address keeps the run segment AND the RAW epoch so navigation stays scoped.
            assertThat(p.getContent()).extracting(StorageExplorerDto::virtualId)
                    .containsExactly("wf:" + WF + "/r" + RUN_A + "/e0", "wf:" + WF + "/r" + RUN_A + "/e1");
            // Label coordinate = REAL stored epoch (matches the EpochSlider) - here raw 0,1.
            assertThat(p.getContent()).extracting(StorageExplorerDto::epoch).containsExactly(0, 1);
        }

        @Test
        @DisplayName("epoch folders show their REAL stored value (oldest-first), NOT a positional index - "
                + "a run whose raw epochs are {2,3} shows Epoch 2, Epoch 3 (matching the EpochSlider), "
                + "not the renumbered Epoch 1, Epoch 2")
        void epochsShowRealStoredValueNotPositional() {
            // Repo returns groups newest-first (MAX(created_at) DESC); raw epoch keys 3 then 2.
            when(repo.listVirtualGroups(eq(ORG), eq(Level.EPOCH), eq(WF), eq(RUN_A), eq(null), eq(null), any(), any()))
                    .thenReturn(List.of(group("3", 1), group("2", 1)));

            Page<StorageExplorerDto> p = service.searchVirtualScope("1", ORG,
                    new VirtualFolderAddress(WF, RUN_A, null, null, null), null, null, null,
                    true, true, null, List.of(), page(0, 20));

            assertThat(p.getContent()).hasSize(2);
            // Display is oldest-first; virtualId carries the RAW epoch (2 then 3) for navigation…
            assertThat(p.getContent()).extracting(StorageExplorerDto::virtualId)
                    .containsExactly("wf:" + WF + "/r" + RUN_A + "/e2", "wf:" + WF + "/r" + RUN_A + "/e3");
            // …and the label coordinate is the SAME real epoch, not a positional 1,2.
            assertThat(p.getContent()).extracting(StorageExplorerDto::epoch).containsExactly(2, 3);
        }

        @Test
        @DisplayName("REGRESSION: a single epoch folder for raw epoch 4 (epochs 0-3 produced no file) is "
                + "labelled 'Epoch 4', not the positional 'Epoch 1' - the file maps to the epoch the user "
                + "actually ran (matches the EpochSlider)")
        void loneLateEpochShowsItsRealValueNotEpochOne() {
            // The reported bug: download happens at epoch 4, the 3 prior epochs produced no file, so this is
            // the ONLY epoch group. Positional numbering labelled it "Epoch 1"; it must read "Epoch 4".
            when(repo.listVirtualGroups(eq(ORG), eq(Level.EPOCH), eq(WF), eq(RUN_A), eq(null), eq(null), any(), any()))
                    .thenReturn(List.of(group("4", 1)));

            Page<StorageExplorerDto> p = service.searchVirtualScope("1", ORG,
                    new VirtualFolderAddress(WF, RUN_A, null, null, null), null, null, null,
                    true, true, null, List.of(), page(0, 20));

            assertThat(p.getContent()).hasSize(1);
            // virtualId keeps the raw epoch (4) for navigation; label coordinate is now ALSO the real 4.
            assertThat(p.getContent().get(0).virtualId()).isEqualTo("wf:" + WF + "/r" + RUN_A + "/e4");
            assertThat(p.getContent().get(0).epoch()).isEqualTo(4);
        }

        @Test
        @DisplayName("a lone reusable-trigger epoch (raw epoch 1) is labelled 'Epoch 1' - reusable triggers "
                + "(form/webhook) start at raw epoch 1, so the real value already reads naturally")
        void singleReusableTriggerEpochShowsEpochOne() {
            when(repo.listVirtualGroups(eq(ORG), eq(Level.EPOCH), eq(WF), eq(RUN_A), eq(null), eq(null), any(), any()))
                    .thenReturn(List.of(group("1", 1)));

            Page<StorageExplorerDto> p = service.searchVirtualScope("1", ORG,
                    new VirtualFolderAddress(WF, RUN_A, null, null, null), null, null, null,
                    true, true, null, List.of(), page(0, 20));

            assertThat(p.getContent()).hasSize(1);
            // virtualId keeps the raw epoch (1) for navigation; label coordinate is the same real epoch (1).
            assertThat(p.getContent().get(0).virtualId()).isEqualTo("wf:" + WF + "/r" + RUN_A + "/e1");
            assertThat(p.getContent().get(0).epoch()).isEqualTo(1);
        }

        @Test
        @DisplayName("epoch under a run (wf:<id>/r<run>/e0) → spawns pinned to the run; spawn address keeps the run segment")
        void epochUnderRunPinsRunOnSpawns() {
            when(repo.listVirtualGroups(eq(ORG), eq(Level.SPAWN), eq(WF), eq(RUN_A), eq(0), eq(null), any(), any()))
                    .thenReturn(List.of(group("0", 3), group("1", 2)));

            Page<StorageExplorerDto> p = service.searchVirtualScope("1", ORG,
                    new VirtualFolderAddress(WF, RUN_A, 0, null, null), null, null, null,
                    true, true, null, List.of(), page(0, 20));

            assertThat(p.getContent()).hasSize(2);
            assertThat(p.getContent()).allMatch(d -> "SPAWN".equals(d.virtualKind()));
            assertThat(p.getContent()).extracting(StorageExplorerDto::virtualId)
                    .containsExactly("wf:" + WF + "/r" + RUN_A + "/e0/s0", "wf:" + WF + "/r" + RUN_A + "/e0/s1");
        }
    }

    // ============================ COLLAPSE rules (single-run / collapsed addresses) ============================

    @Nested
    @DisplayName("EPOCH collapse rules")
    class EpochCollapse {

        @Test
        @DisplayName("epoch with >1 spawn → SPAWN folders, no files")
        void multipleSpawnsYieldSpawnFolders() {
            when(repo.listVirtualGroups(eq(ORG), eq(Level.SPAWN), eq(WF), eq(null), eq(0), eq(null), any(), any()))
                    .thenReturn(List.of(group("0", 3), group("1", 2)));

            Page<StorageExplorerDto> p = service.searchVirtualScope("1", ORG,
                    new VirtualFolderAddress(WF, 0, null, null), null, null, null,
                    true, true, null, List.of(), page(0, 20));

            assertThat(p.getContent()).hasSize(2);
            assertThat(p.getContent()).allMatch(d -> "SPAWN".equals(d.virtualKind()));
            assertThat(p.getContent()).extracting(StorageExplorerDto::virtualId)
                    .containsExactly("wf:" + WF + "/e0/s0", "wf:" + WF + "/e0/s1");
            // No leaf-file query at this level.
            verify(repo, never()).listVirtualLeafFiles(any(), any(), any(), any(), any(), any(), any(), any(), any(),
                    anyBoolean(), anyBoolean(), any(), any(), any(), any(), anyBoolean(), anyInt(), anyInt());
        }

        @Test
        @DisplayName("epoch with 1 spawn + >1 iteration → ITERATION folders (+ the spawn's null-item files, none here)")
        void singleSpawnMultipleItemsYieldIterationFolders() {
            when(repo.listVirtualGroups(eq(ORG), eq(Level.SPAWN), eq(WF), eq(null), eq(0), eq(null), any(), any()))
                    .thenReturn(List.of(group("0", 5)));
            when(repo.listVirtualGroups(eq(ORG), eq(Level.ITERATION), eq(WF), eq(null), eq(0), eq(0), any(), any()))
                    .thenReturn(List.of(group("0", 2), group("1", 3)));

            Page<StorageExplorerDto> p = service.searchVirtualScope("1", ORG,
                    new VirtualFolderAddress(WF, 0, null, null), null, null, null,
                    true, true, null, List.of(), page(0, 20));

            assertThat(p.getContent()).hasSize(2);
            assertThat(p.getContent()).allMatch(d -> "ITERATION".equals(d.virtualKind()));
            assertThat(p.getContent()).extracting(StorageExplorerDto::virtualId)
                    .containsExactly("wf:" + WF + "/e0/s0/i0", "wf:" + WF + "/e0/s0/i1");
            assertThat(p.getContent()).extracting(StorageExplorerDto::spawn).containsOnly(0);
            // The iteration-folders branch ALSO fetches the spawn's NON-split (item_index IS NULL) files
            // - nullItemOnly=true - so a non-split file in the spawn is never orphaned. None here → just folders.
            verify(repo).listVirtualLeafFiles(eq(ORG), eq(WF), eq(null), eq(0), eq(0), eq(null), any(), any(), any(),
                    anyBoolean(), anyBoolean(), any(), any(), any(), any(), eq(true), anyInt(), anyInt());
        }

        @Test
        @DisplayName("epoch with 1 spawn + >1 iteration + a non-split (null item_index) file → "
                + "ITERATION folders AND the null-item file shown at spawn level (never orphaned)")
        void nullItemFilesShownAlongsideIterationFolders() {
            UUID screenshot = UUID.randomUUID();
            when(repo.listVirtualGroups(eq(ORG), eq(Level.SPAWN), eq(WF), eq(null), eq(0), eq(null), any(), any()))
                    .thenReturn(List.of(group("0", 5)));
            when(repo.listVirtualGroups(eq(ORG), eq(Level.ITERATION), eq(WF), eq(null), eq(0), eq(0), any(), any()))
                    .thenReturn(List.of(group("0", 2), group("1", 3)));
            when(repo.listVirtualLeafFiles(eq(ORG), eq(WF), eq(null), eq(0), eq(0), eq(null), any(), any(), any(),
                    anyBoolean(), anyBoolean(), any(), any(), any(), any(), eq(true), anyInt(), anyInt()))
                    .thenReturn(new SliceResult(List.of(fileRow(screenshot, "form_screenshot.png")), 1));

            Page<StorageExplorerDto> p = service.searchVirtualScope("1", ORG,
                    new VirtualFolderAddress(WF, 0, null, null), null, null, null,
                    true, true, null, List.of(), page(0, 20));

            // 2 iteration folders (header) + the 1 null-item file (body) = 3 - the file is NOT lost.
            assertThat(p.getTotalElements()).isEqualTo(3);
            assertThat(p.getContent()).hasSize(3);
            assertThat(p.getContent().get(0).isFolder()).isTrue();
            assertThat(p.getContent().get(1).isFolder()).isTrue();
            assertThat(p.getContent().get(2).isFolder()).isFalse();
            assertThat(p.getContent().get(2).id()).isEqualTo(screenshot);
        }

        @Test
        @DisplayName("epoch with 1 spawn + 1 iteration → COLLAPSE to files, no folders")
        void singleSpawnSingleItemCollapsesToFiles() {
            UUID f1 = UUID.randomUUID();
            when(repo.listVirtualGroups(eq(ORG), eq(Level.SPAWN), eq(WF), eq(null), eq(0), eq(null), any(), any()))
                    .thenReturn(List.of(group("0", 4)));
            when(repo.listVirtualGroups(eq(ORG), eq(Level.ITERATION), eq(WF), eq(null), eq(0), eq(0), any(), any()))
                    .thenReturn(List.of(group("0", 4)));
            when(repo.listVirtualLeafFiles(eq(ORG), eq(WF), eq(null), eq(0), eq(0), eq(null), any(), any(), any(),
                    anyBoolean(), anyBoolean(), any(), any(), any(), any(), anyBoolean(), anyInt(), anyInt()))
                    .thenReturn(new SliceResult(List.of(fileRow(f1, "a.png")), 1));

            Page<StorageExplorerDto> p = service.searchVirtualScope("1", ORG,
                    new VirtualFolderAddress(WF, 0, null, null), null, null, null,
                    true, true, null, List.of(), page(0, 20));

            assertThat(p.getContent()).hasSize(1);
            assertThat(p.getContent().get(0).isFolder()).isFalse();
            assertThat(p.getContent().get(0).id()).isEqualTo(f1);
            assertThat(p.getTotalElements()).isEqualTo(1);
            verify(repo).listVirtualLeafFiles(eq(ORG), eq(WF), eq(null), eq(0), eq(0), eq(null), any(), any(), any(),
                    anyBoolean(), anyBoolean(), any(), any(), any(), any(), anyBoolean(), anyInt(), anyInt());
        }

        @Test
        @DisplayName("epoch with 0 spawns → empty page")
        void noSpawnsYieldEmpty() {
            when(repo.listVirtualGroups(eq(ORG), eq(Level.SPAWN), eq(WF), eq(null), eq(0), eq(null), any(), any()))
                    .thenReturn(List.of());

            Page<StorageExplorerDto> p = service.searchVirtualScope("1", ORG,
                    new VirtualFolderAddress(WF, 0, null, null), null, null, null,
                    true, true, null, List.of(), page(0, 20));

            assertThat(p.getContent()).isEmpty();
            assertThat(p.getTotalElements()).isZero();
        }
    }

    @Nested
    @DisplayName("SPAWN collapse rules")
    class SpawnCollapse {

        @Test
        @DisplayName("spawn with >1 iteration → ITERATION folders")
        void multipleItemsYieldFolders() {
            when(repo.listVirtualGroups(eq(ORG), eq(Level.ITERATION), eq(WF), eq(null), eq(1), eq(2), any(), any()))
                    .thenReturn(List.of(group("0", 1), group("1", 1)));

            Page<StorageExplorerDto> p = service.searchVirtualScope("1", ORG,
                    new VirtualFolderAddress(WF, 1, 2, null), null, null, null,
                    true, true, null, List.of(), page(0, 20));

            assertThat(p.getContent()).hasSize(2);
            assertThat(p.getContent()).allMatch(d -> "ITERATION".equals(d.virtualKind()));
        }

        @Test
        @DisplayName("spawn with ≤1 iteration → COLLAPSE to that spawn's files")
        void singleItemCollapsesToFiles() {
            UUID f1 = UUID.randomUUID();
            when(repo.listVirtualGroups(eq(ORG), eq(Level.ITERATION), eq(WF), eq(null), eq(1), eq(2), any(), any()))
                    .thenReturn(List.of(group("0", 1)));
            when(repo.listVirtualLeafFiles(eq(ORG), eq(WF), eq(null), eq(1), eq(2), eq(null), any(), any(), any(),
                    anyBoolean(), anyBoolean(), any(), any(), any(), any(), anyBoolean(), anyInt(), anyInt()))
                    .thenReturn(new SliceResult(List.of(fileRow(f1, "b.png")), 1));

            Page<StorageExplorerDto> p = service.searchVirtualScope("1", ORG,
                    new VirtualFolderAddress(WF, 1, 2, null), null, null, null,
                    true, true, null, List.of(), page(0, 20));

            assertThat(p.getContent()).hasSize(1);
            assertThat(p.getContent().get(0).isFolder()).isFalse();
        }
    }

    @Test
    @DisplayName("ITERATION level → only leaf files of (wf,e,s,i)")
    void iterationLevelListsLeafFiles() {
        UUID f1 = UUID.randomUUID();
        when(repo.listVirtualLeafFiles(eq(ORG), eq(WF), eq(null), eq(1), eq(2), eq(3), any(), any(), any(),
                anyBoolean(), anyBoolean(), any(), any(), any(), any(), anyBoolean(), anyInt(), anyInt()))
                .thenReturn(new SliceResult(List.of(fileRow(f1, "c.png")), 1));

        Page<StorageExplorerDto> p = service.searchVirtualScope("1", ORG,
                new VirtualFolderAddress(WF, 1, 2, 3), null, null, null,
                true, true, null, List.of(), page(0, 20));

        assertThat(p.getContent()).hasSize(1);
        assertThat(p.getContent().get(0).isFolder()).isFalse();
        assertThat(p.getContent().get(0).id()).isEqualTo(f1);
    }

    @Test
    @DisplayName("WORKFLOW level with a single run → epoch folders, no files")
    void workflowLevelListsEpochFolders() {
        when(repo.listVirtualGroups(eq(ORG), eq(Level.RUN), eq(WF), eq(null), eq(null), eq(null), any(), any()))
                .thenReturn(List.of(group(RUN_A, 6)));
        when(repo.listVirtualGroups(eq(ORG), eq(Level.EPOCH), eq(WF), eq(null), eq(null), eq(null), any(), any()))
                .thenReturn(List.of(group("0", 4), group("1", 2)));

        Page<StorageExplorerDto> p = service.searchVirtualScope("1", ORG,
                new VirtualFolderAddress(WF, null, null, null), null, null, null,
                true, true, null, List.of(), page(0, 20));

        assertThat(p.getContent()).hasSize(2);
        assertThat(p.getContent()).allMatch(d -> "EPOCH".equals(d.virtualKind()));
        // Epoch coordinate is the REAL stored epoch (matches the EpochSlider), oldest-first - raw 0,1.
        assertThat(p.getContent()).extracting(StorageExplorerDto::epoch).containsExactly(0, 1);
        verify(repo, never()).listVirtualLeafFiles(any(), any(), any(), any(), any(), any(), any(), any(), any(),
                anyBoolean(), anyBoolean(), any(), any(), any(), any(), anyBoolean(), anyInt(), anyInt());
    }

    @Test
    @DisplayName("the caller's file filter (filesOnly/s3Only/sourceType/fileCategory) is THREADED into the "
            + "epoch grouping - so a folder only appears for a group with a matching real file")
    void fileFilterThreadedIntoGrouping() {
        when(repo.listVirtualGroups(eq(ORG), eq(Level.RUN), eq(WF), eq(null), eq(null), eq(null), any(), any()))
                .thenReturn(List.of(group(RUN_A, 1)));
        when(repo.listVirtualGroups(eq(ORG), eq(Level.EPOCH), eq(WF), eq(null), eq(null), eq(null), any(), any()))
                .thenReturn(List.of(group("0", 1)));

        service.searchVirtualScope("1", ORG, new VirtualFolderAddress(WF, null, null, null),
                /* search */ null, /* sourceType */ "STEP_OUTPUT", /* storageType */ null,
                /* filesOnly */ true, /* s3Only */ true, /* fileCategory */ "images", List.of(), page(0, 20));

        ArgumentCaptor<StorageExplorerRepository.VirtualFileFilter> f =
                ArgumentCaptor.forClass(StorageExplorerRepository.VirtualFileFilter.class);
        verify(repo).listVirtualGroups(eq(ORG), eq(Level.EPOCH), eq(WF), eq(null), eq(null), eq(null), any(), f.capture());
        assertThat(f.getValue().filesOnly()).isTrue();
        assertThat(f.getValue().s3Only()).isTrue();
        assertThat(f.getValue().sourceType()).isEqualTo("STEP_OUTPUT");
        assertThat(f.getValue().fileCategory()).isEqualTo("images");
    }

    // ============================ ROOT composition ============================

    @Nested
    @DisplayName("ROOT composition (address == null)")
    class Root {

        @Test
        @DisplayName("manual root folders ++ virtual workflow folders ++ loose files (workflow_id IS NULL)")
        void composesManualVirtualAndLooseFiles() {
            UUID manualId = UUID.randomUUID();
            UUID looseId = UUID.randomUUID();
            when(repo.listRootManualFolders(eq(ORG), any(), any()))
                    .thenReturn(List.of(manualFolderRow(manualId, "My Folder")));
            when(repo.countChildrenByParent(eq(ORG), any(), any())).thenReturn(Map.of(manualId, 2));
            when(repo.listVirtualGroups(eq(ORG), eq(Level.WORKFLOW), eq(null), eq(null), eq(null), eq(null), any(), any()))
                    .thenReturn(List.of(group(WF, 7)));
            when(repo.listVirtualLeafFiles(eq(ORG), eq(null), eq(null), eq(null), eq(null), eq(null), any(), any(), any(),
                    anyBoolean(), anyBoolean(), any(), any(), any(), any(), anyBoolean(), anyInt(), anyInt()))
                    .thenReturn(new SliceResult(List.of(fileRow(looseId, "loose.png")), 1));

            Page<StorageExplorerDto> p = service.searchVirtualScope("1", ORG,
                    null, null, null, null, true, true, null, List.of(), page(0, 20));

            // Everything fits on ONE page (1 loose file ≤ page size) → a single page of
            // 1 manual + 1 workflow folder + 1 loose file = 3, no phantom second page.
            assertThat(p.getTotalElements()).isEqualTo(3);
            assertThat(p.getTotalPages()).isEqualTo(1);
            assertThat(p.getContent()).hasSize(3);
            assertThat(p.getContent().get(0).isFolder()).isTrue();
            assertThat(p.getContent().get(0).id()).isEqualTo(manualId);
            assertThat(p.getContent().get(0).childCount()).isEqualTo(2);
            assertThat(p.getContent().get(1).virtualKind()).isEqualTo("WORKFLOW");
            assertThat(p.getContent().get(1).virtualId()).isEqualTo("wf:" + WF);
            assertThat(p.getContent().get(1).childCount()).isEqualTo(7);
            assertThat(p.getContent().get(2).isFolder()).isFalse();
            assertThat(p.getContent().get(2).id()).isEqualTo(looseId);
        }

        @Test
        @DisplayName("manual + virtual folders INTERLEAVE by last activity (newest first) - a newer workflow "
                + "folder sorts BEFORE an older manual folder, and the manual folder's createdAt is stamped with its activity")
        void foldersInterleaveByLastActivity() {
            UUID manualId = UUID.randomUUID();
            Instant manualActivity = Instant.parse("2026-01-01T00:00:00Z");   // OLD last-child
            Instant virtualActivity = Instant.parse("2026-06-17T00:00:00Z");  // NEW latest file
            when(repo.listRootManualFolders(eq(ORG), any(), any()))
                    .thenReturn(List.of(manualFolderRow(manualId, "Old Folder")));
            when(repo.countChildrenByParent(eq(ORG), any(), any())).thenReturn(Map.of(manualId, 1));
            when(repo.latestChildCreatedAtByParent(eq(ORG), any(), any()))
                    .thenReturn(Map.of(manualId, manualActivity));
            when(repo.listVirtualGroups(eq(ORG), eq(Level.WORKFLOW), eq(null), eq(null), eq(null), eq(null), any(), any()))
                    .thenReturn(List.of(new VirtualGroup(WF, 3, virtualActivity)));

            Page<StorageExplorerDto> p = service.searchVirtualScope("1", ORG,
                    null, null, null, null, true, true, null, List.of(), page(0, 20));

            // The NEWER virtual workflow folder must sort BEFORE the OLDER manual folder (pre-fix:
            // manual folders were always emitted first regardless of recency).
            assertThat(p.getContent()).hasSize(2);
            assertThat(p.getContent().get(0).virtualKind()).isEqualTo("WORKFLOW");
            assertThat(p.getContent().get(0).createdAt()).isEqualTo(virtualActivity);
            // The manual folder is second AND its createdAt is its last activity (not the folder's own date).
            assertThat(p.getContent().get(1).id()).isEqualTo(manualId);
            assertThat(p.getContent().get(1).createdAt()).isEqualTo(manualActivity);
        }

        @Test
        @DisplayName("root loose-file query is scoped to workflow_id IS NULL (passes null workflowId)")
        void looseFilesAreWorkflowIdNull() {
            service.searchVirtualScope("1", ORG, null, null, null, null,
                    true, true, null, List.of(), page(0, 20));

            verify(repo).listVirtualLeafFiles(eq(ORG), eq(null), eq(null), eq(null), eq(null), eq(null), any(), any(), any(),
                    anyBoolean(), anyBoolean(), any(), any(), any(), any(), anyBoolean(), anyInt(), anyInt());
        }

        @Test
        @DisplayName("non-blank search OMITS virtual workflow folders but keeps manual folders + loose files")
        void searchOmitsVirtualWorkflowFolders() {
            UUID manualId = UUID.randomUUID();
            when(repo.listRootManualFolders(eq(ORG), eq("report"), any()))
                    .thenReturn(List.of(manualFolderRow(manualId, "Reports")));

            Page<StorageExplorerDto> p = service.searchVirtualScope("1", ORG,
                    null, "report", null, null, true, true, null, List.of(), page(0, 20));

            // The WORKFLOW-level virtual grouping must NOT be queried when searching.
            verify(repo, never()).listVirtualGroups(eq(ORG), eq(Level.WORKFLOW), any(), any(), any(), any(), any(), any());
            verify(repo).listRootManualFolders(eq(ORG), eq("report"), any());
            verify(repo).listVirtualLeafFiles(eq(ORG), eq(null), any(), any(), any(), any(), eq("report"), any(), any(),
                    anyBoolean(), anyBoolean(), any(), any(), any(), any(), anyBoolean(), anyInt(), anyInt());
            assertThat(p.getContent()).noneMatch(d -> "WORKFLOW".equals(d.virtualKind()));
        }
    }

    // ===================== All folders on page 1; files paginate after =====================

    @Nested
    @DisplayName("All folders ride page 1; only the loose files paginate (page-aligned)")
    class Pagination {

        @Test
        @DisplayName("every folder lands on page 1 even when there are MORE folders than the page size")
        void allFoldersOnFirstPageRegardlessOfCount() {
            // 5 workflow folders, page size 3 - pre-fix only 3 fit on page 1 and 2 spilled onto page 2.
            when(repo.listVirtualGroups(eq(ORG), eq(Level.WORKFLOW), eq(null), eq(null), eq(null), eq(null), any(), any()))
                    .thenReturn(List.of(
                            group("11111111-1111-1111-1111-111111111111", 1),
                            group("22222222-2222-2222-2222-222222222222", 1),
                            group("33333333-3333-3333-3333-333333333333", 1),
                            group("44444444-4444-4444-4444-444444444444", 1),
                            group("55555555-5555-5555-5555-555555555555", 1)));
            // No loose files.
            when(repo.listVirtualLeafFiles(eq(ORG), eq(null), any(), any(), any(), any(), any(), any(), any(),
                    anyBoolean(), anyBoolean(), any(), any(), any(), any(), anyBoolean(), anyInt(), anyInt()))
                    .thenReturn(new SliceResult(List.of(), 0));

            Page<StorageExplorerDto> p = service.searchVirtualScope("1", ORG,
                    null, null, null, null, true, true, null, List.of(), page(0, 3));

            // All 5 folders on page 1 despite a page size of 3 - they never spill onto page 2.
            assertThat(p.getContent()).hasSize(5);
            assertThat(p.getContent()).allMatch(StorageExplorerDto::isFolder);
        }

        @Test
        @DisplayName("page 1 = ALL folders + the first file page; the file query is page-aligned (offset 0, limit pageSize)")
        void firstPageHasAllFoldersPlusFirstFilePage() {
            when(repo.listVirtualGroups(eq(ORG), eq(Level.WORKFLOW), eq(null), eq(null), eq(null), eq(null), any(), any()))
                    .thenReturn(List.of(group(WF, 1), group("22222222-2222-2222-2222-222222222222", 1)));
            UUID f0 = UUID.randomUUID();
            UUID f1 = UUID.randomUUID();
            UUID f2 = UUID.randomUUID();
            // The file query is page-aligned now: offset 0, limit = pageSize (3) - NOT a leftover slot.
            when(repo.listVirtualLeafFiles(eq(ORG), eq(null), any(), any(), any(), any(), any(), any(), any(),
                    anyBoolean(), anyBoolean(), any(), any(), any(), any(), anyBoolean(), eq(3), eq(0)))
                    .thenReturn(new SliceResult(List.of(fileRow(f0, "f0.png"), fileRow(f1, "f1.png"), fileRow(f2, "f2.png")), 5));

            Page<StorageExplorerDto> p = service.searchVirtualScope("1", ORG,
                    null, null, null, null, true, true, null, List.of(), page(0, 3));

            // Page 1 = 2 folders (all) + the first file page (3 files); the pager counts files only → 5.
            assertThat(p.getTotalElements()).isEqualTo(5);
            assertThat(p.getContent()).hasSize(5);
            assertThat(p.getContent().get(0).virtualKind()).isEqualTo("WORKFLOW");
            assertThat(p.getContent().get(1).virtualKind()).isEqualTo("WORKFLOW");
            assertThat(p.getContent().subList(2, 5)).allMatch(d -> !d.isFolder());
            verify(repo).listVirtualLeafFiles(eq(ORG), eq(null), any(), any(), any(), any(), any(), any(), any(),
                    anyBoolean(), anyBoolean(), any(), any(), any(), any(), anyBoolean(), eq(3), eq(0));
        }

        @Test
        @DisplayName("page 2 carries ONLY files (no folders) and the file query is page-aligned (offset = page*size)")
        void laterPagesAreFilesOnly() {
            when(repo.listVirtualGroups(eq(ORG), eq(Level.WORKFLOW), eq(null), eq(null), eq(null), eq(null), any(), any()))
                    .thenReturn(List.of(group(WF, 1), group("22222222-2222-2222-2222-222222222222", 1)));
            UUID f3 = UUID.randomUUID();
            UUID f4 = UUID.randomUUID();
            // Page index 1 (0-based) → page-aligned file offset = 3 (NOT offset - folderCount = 1).
            // Files 3 & 4 of 5 land here (consistent with the slice total of 5).
            when(repo.listVirtualLeafFiles(eq(ORG), eq(null), any(), any(), any(), any(), any(), any(), any(),
                    anyBoolean(), anyBoolean(), any(), any(), any(), any(), anyBoolean(), eq(3), eq(3)))
                    .thenReturn(new SliceResult(List.of(fileRow(f3, "f3.png"), fileRow(f4, "f4.png")), 5));

            Page<StorageExplorerDto> p = service.searchVirtualScope("1", ORG,
                    null, null, null, null, true, true, null, List.of(), page(1, 3));

            // No folders on page 2 - only that page's file rows.
            assertThat(p.getContent()).hasSize(2);
            assertThat(p.getContent()).allMatch(d -> !d.isFolder());
            assertThat(p.getTotalElements()).isEqualTo(5);
            verify(repo).listVirtualLeafFiles(eq(ORG), eq(null), any(), any(), any(), any(), any(), any(), any(),
                    anyBoolean(), anyBoolean(), any(), any(), any(), any(), anyBoolean(), eq(3), eq(3));
        }
    }
}
