package com.apimarketplace.interfaces.service;

import com.apimarketplace.interfaces.domain.InterfaceEntity;
import com.apimarketplace.interfaces.domain.InterfaceRunSnapshotEntity;
import com.apimarketplace.interfaces.repository.InterfaceRepository;
import com.apimarketplace.interfaces.repository.InterfaceRunSnapshotRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class InterfaceSnapshotServiceTest {

    @Mock private InterfaceRunSnapshotRepository snapshotRepository;
    @Mock private InterfaceRepository interfaceRepository;

    @InjectMocks private InterfaceSnapshotService snapshotService;

    private InterfaceEntity testInterface;
    private UUID interfaceId;
    private UUID runId;

    @BeforeEach
    void setUp() {
        interfaceId = UUID.randomUUID();
        runId = UUID.randomUUID();
        testInterface = new InterfaceEntity();
        testInterface.setId(interfaceId);
        testInterface.setTenantId("tenant-1");
        testInterface.setName("Test");
        testInterface.setDescription("Test Desc");
        testInterface.setHtmlTemplate("<div>Hello</div>");
        testInterface.setCssTemplate(".c {}");
        testInterface.setJsTemplate("alert(1);");
        testInterface.setCreatedAt(Instant.now());
    }

    @Test
    void createSnapshot_shouldCreateNormally() {
        when(snapshotRepository.existsByInterfaceIdAndWorkflowRunId(interfaceId, runId)).thenReturn(false);
        when(interfaceRepository.findById(interfaceId)).thenReturn(Optional.of(testInterface));
        when(snapshotRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        InterfaceRunSnapshotEntity result = snapshotService.createSnapshot(
                interfaceId, runId, null, null, "tenant-1");

        assertThat(result).isNotNull();
        assertThat(result.getInterfaceId()).isEqualTo(interfaceId);
        assertThat(result.getWorkflowRunId()).isEqualTo(runId);
        assertThat(result.getHtmlTemplate()).isEqualTo("<div>Hello</div>");
    }

    @Test
    void createSnapshot_shouldBeIdempotent() {
        InterfaceRunSnapshotEntity existing = InterfaceRunSnapshotEntity.fromInterface(testInterface, runId);
        when(snapshotRepository.existsByInterfaceIdAndWorkflowRunId(interfaceId, runId)).thenReturn(true);
        when(snapshotRepository.findByInterfaceIdAndWorkflowRunId(interfaceId, runId))
                .thenReturn(Optional.of(existing));

        InterfaceRunSnapshotEntity result = snapshotService.createSnapshot(
                interfaceId, runId, null, null, "tenant-1");

        assertThat(result).isNotNull();
        verify(snapshotRepository, never()).save(any());
    }

    @Test
    void createSnapshot_shouldReturnNullWhenInterfaceNotFound() {
        when(snapshotRepository.existsByInterfaceIdAndWorkflowRunId(interfaceId, runId)).thenReturn(false);
        when(interfaceRepository.findById(interfaceId)).thenReturn(Optional.empty());

        InterfaceRunSnapshotEntity result = snapshotService.createSnapshot(
                interfaceId, runId, null, null, "tenant-1");

        assertThat(result).isNull();
        verify(snapshotRepository, never()).save(any());
    }

    @Test
    void createSnapshot_withVariableMappings() {
        when(snapshotRepository.existsByInterfaceIdAndWorkflowRunId(interfaceId, runId)).thenReturn(false);
        when(interfaceRepository.findById(interfaceId)).thenReturn(Optional.of(testInterface));
        when(snapshotRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        Map<String, String> varMappings = Map.of("title", "trigger:data.title");

        InterfaceRunSnapshotEntity result = snapshotService.createSnapshot(
                interfaceId, runId, varMappings, null, "tenant-1");

        assertThat(result.getVariableMappings()).containsEntry("title", "trigger:data.title");
    }

    @Test
    void createSnapshot_withBothMappings() {
        when(snapshotRepository.existsByInterfaceIdAndWorkflowRunId(interfaceId, runId)).thenReturn(false);
        when(interfaceRepository.findById(interfaceId)).thenReturn(Optional.of(testInterface));
        when(snapshotRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        Map<String, String> varMappings = Map.of("title", "data.title");
        Map<String, String> actionMappings = Map.of("submit", "core:process");

        InterfaceRunSnapshotEntity result = snapshotService.createSnapshot(
                interfaceId, runId, varMappings, actionMappings, "tenant-1");

        assertThat(result.getVariableMappings()).containsEntry("title", "data.title");
        assertThat(result.getActionMappings()).containsEntry("submit", "core:process");
    }

    @Test
    void createSnapshot_shouldStripQuotesFromActionMappingKeys() {
        when(snapshotRepository.existsByInterfaceIdAndWorkflowRunId(interfaceId, runId)).thenReturn(false);
        when(interfaceRepository.findById(interfaceId)).thenReturn(Optional.of(testInterface));
        when(snapshotRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        Map<String, String> actionMappings = new LinkedHashMap<>();
        actionMappings.put("'submit'", "core:process");
        actionMappings.put("\"cancel\"", "core:cancel");
        actionMappings.put("normal", "core:normal");

        InterfaceRunSnapshotEntity result = snapshotService.createSnapshot(
                interfaceId, runId, null, actionMappings, "tenant-1");

        assertThat(result.getActionMappings())
                .containsEntry("submit", "core:process")
                .containsEntry("cancel", "core:cancel")
                .containsEntry("normal", "core:normal");
    }

    @Test
    void getSnapshot_shouldReturnWhenFound() {
        InterfaceRunSnapshotEntity snapshot = InterfaceRunSnapshotEntity.fromInterface(testInterface, runId);
        when(snapshotRepository.findByInterfaceIdAndWorkflowRunId(interfaceId, runId))
                .thenReturn(Optional.of(snapshot));

        Optional<InterfaceRunSnapshotEntity> result = snapshotService.getSnapshot(interfaceId, runId);

        assertThat(result).isPresent();
    }

    @Test
    void getSnapshotsForRun_shouldReturnAll() {
        InterfaceRunSnapshotEntity s1 = InterfaceRunSnapshotEntity.fromInterface(testInterface, runId);
        when(snapshotRepository.findByWorkflowRunId(runId)).thenReturn(List.of(s1));

        List<InterfaceRunSnapshotEntity> result = snapshotService.getSnapshotsForRun(runId);

        assertThat(result).hasSize(1);
    }

    @Test
    void deleteSnapshotsForRun_shouldDelegate() {
        snapshotService.deleteSnapshotsForRun(runId);
        verify(snapshotRepository).deleteByWorkflowRunId(runId);
    }

    // ========================================================================
    // refreshSnapshotsFromLiveInterface - added 2026-05-14
    //
    // Mirror of WorkflowResumeService.refreshPlanFromWorkflowDefinition (orchestrator).
    // Each new test pins one branch of the refresh contract so future changes either
    // preserve the prod fix (long-running WAITING_TRIGGER runs pick up agent-driven
    // interface iterations on re-fire) or break loudly here first.
    // ========================================================================

    @Test
    void refreshSnapshots_writesThroughWhenContentChanged() {
        // Existing snapshot was frozen with the OLD HTML; the live interface entity has
        // since been updated with the NEW HTML (e.g. agent fixed a {{var}}-escape bug).
        // The refresh must propagate every field that diverges.
        InterfaceRunSnapshotEntity snap = InterfaceRunSnapshotEntity.fromInterface(testInterface, runId);
        snap.setHtmlTemplate("<div>OLD</div>");
        snap.setCssTemplate(".old {}");
        snap.setJsTemplate("/* old */");
        snap.setName("Old Name");
        snap.setDescription("Old Description");

        when(snapshotRepository.findByWorkflowRunId(runId)).thenReturn(List.of(snap));
        when(interfaceRepository.findById(interfaceId)).thenReturn(Optional.of(testInterface));
        when(snapshotRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        InterfaceSnapshotService.RefreshResult result = snapshotService.refreshSnapshotsFromLiveInterface(runId);

        assertThat(result.refreshed()).as("one snapshot row was updated").isEqualTo(1);
        assertThat(result.unchanged()).isZero();
        assertThat(result.missing()).isZero();
        assertThat(result.errors()).isZero();
        ArgumentCaptor<InterfaceRunSnapshotEntity> captor = ArgumentCaptor.forClass(InterfaceRunSnapshotEntity.class);
        verify(snapshotRepository).save(captor.capture());
        InterfaceRunSnapshotEntity saved = captor.getValue();
        assertThat(saved.getHtmlTemplate()).isEqualTo("<div>Hello</div>");
        assertThat(saved.getCssTemplate()).isEqualTo(".c {}");
        assertThat(saved.getJsTemplate()).isEqualTo("alert(1);");
        assertThat(saved.getName()).isEqualTo("Test");
        assertThat(saved.getDescription()).isEqualTo("Test Desc");
    }

    @Test
    void refreshSnapshots_isNoOpWhenContentUnchanged() {
        // Snapshot already matches the live entity exactly - refresh must not dirty the row.
        // Saves the Hibernate UPDATE round-trip on the common case (stable interfaces).
        InterfaceRunSnapshotEntity snap = InterfaceRunSnapshotEntity.fromInterface(testInterface, runId);

        when(snapshotRepository.findByWorkflowRunId(runId)).thenReturn(List.of(snap));
        when(interfaceRepository.findById(interfaceId)).thenReturn(Optional.of(testInterface));

        InterfaceSnapshotService.RefreshResult result = snapshotService.refreshSnapshotsFromLiveInterface(runId);

        assertThat(result.refreshed()).isZero();
        assertThat(result.unchanged()).as("identical content → skip UPDATE").isEqualTo(1);
        assertThat(result.missing()).isZero();
        verify(snapshotRepository, never()).save(any());
    }

    @Test
    void refreshSnapshots_propagatesAFormatOnlyChange() {
        // Re-shaping an interface (vertical -> widescreen) changes NO template, so a refresh
        // that only compares html/css/js/name/description reports "unchanged" and the waiting
        // run keeps capturing at the old shape forever. The format is refreshable like the
        // templates it travels with.
        InterfaceRunSnapshotEntity snap = InterfaceRunSnapshotEntity.fromInterface(testInterface, runId);
        snap.setFormat("vertical");
        testInterface.setFormat("widescreen");

        when(snapshotRepository.findByWorkflowRunId(runId)).thenReturn(List.of(snap));
        when(interfaceRepository.findById(interfaceId)).thenReturn(Optional.of(testInterface));
        when(snapshotRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        InterfaceSnapshotService.RefreshResult result = snapshotService.refreshSnapshotsFromLiveInterface(runId);

        assertThat(result.refreshed()).as("a format-only edit must reach the run").isEqualTo(1);
        assertThat(result.unchanged()).isZero();
        assertThat(snap.getFormat()).isEqualTo("widescreen");
    }

    @Test
    void refreshSnapshots_isStillNoOpWhenOnlyTheFormatMatches() {
        // Guards the comparison above from over-firing: an identical format must not dirty the
        // row on every trigger fire.
        testInterface.setFormat("vertical");
        InterfaceRunSnapshotEntity snap = InterfaceRunSnapshotEntity.fromInterface(testInterface, runId);

        when(snapshotRepository.findByWorkflowRunId(runId)).thenReturn(List.of(snap));
        when(interfaceRepository.findById(interfaceId)).thenReturn(Optional.of(testInterface));

        InterfaceSnapshotService.RefreshResult result = snapshotService.refreshSnapshotsFromLiveInterface(runId);

        assertThat(result.unchanged()).isEqualTo(1);
        verify(snapshotRepository, never()).save(any());
    }

    @Test
    void refreshSnapshots_keepsFrozenWhenInterfaceDeleted() {
        // The source interface was deleted since the run started (rare but real:
        // user removed the node, or workflow was edited to detach the interface).
        // The frozen snapshot must NOT be blanked - the in-flight UI is more useful
        // than a dead column. User-facing constraint per discussion 2026-05-14.
        InterfaceRunSnapshotEntity snap = InterfaceRunSnapshotEntity.fromInterface(testInterface, runId);
        snap.setHtmlTemplate("<div>FROZEN content the user is still seeing</div>");

        when(snapshotRepository.findByWorkflowRunId(runId)).thenReturn(List.of(snap));
        when(interfaceRepository.findById(interfaceId)).thenReturn(Optional.empty());

        InterfaceSnapshotService.RefreshResult result = snapshotService.refreshSnapshotsFromLiveInterface(runId);

        assertThat(result.refreshed()).isZero();
        assertThat(result.missing()).as("source interface gone → counted as missing, snapshot kept").isEqualTo(1);
        assertThat(result.errors()).isZero();
        verify(snapshotRepository, never()).save(any());
        // The snapshot row itself must still hold its frozen content - we did not touch it.
        assertThat(snap.getHtmlTemplate()).isEqualTo("<div>FROZEN content the user is still seeing</div>");
    }

    @Test
    void refreshSnapshots_returnsEmptyWhenNoSnapshotsExist() {
        // First-fire / no-interface runs: zero snapshots to refresh. Must return cleanly
        // without consulting interfaceRepository (saves an unnecessary lookup).
        when(snapshotRepository.findByWorkflowRunId(runId)).thenReturn(List.of());

        InterfaceSnapshotService.RefreshResult result = snapshotService.refreshSnapshotsFromLiveInterface(runId);

        assertThat(result.total()).isZero();
        verify(interfaceRepository, never()).findById(any());
        verify(snapshotRepository, never()).save(any());
    }

    @Test
    void refreshSnapshots_tolerantToPerSnapshotFailure() {
        // Two snapshots: first throws (corrupt row / repo glitch), second succeeds.
        // The first failure must NOT block the second - the trigger pipeline keeps moving.
        UUID interfaceId2 = UUID.randomUUID();
        InterfaceEntity iface2 = new InterfaceEntity();
        iface2.setId(interfaceId2);
        iface2.setTenantId("tenant-1");
        iface2.setName("Iface 2");
        iface2.setHtmlTemplate("<div>NEW2</div>");

        InterfaceRunSnapshotEntity snap1 = InterfaceRunSnapshotEntity.fromInterface(testInterface, runId);
        snap1.setHtmlTemplate("<div>OLD1</div>");
        InterfaceRunSnapshotEntity snap2 = InterfaceRunSnapshotEntity.fromInterface(iface2, runId);
        snap2.setHtmlTemplate("<div>OLD2</div>");

        when(snapshotRepository.findByWorkflowRunId(runId)).thenReturn(List.of(snap1, snap2));
        when(interfaceRepository.findById(interfaceId)).thenThrow(new RuntimeException("simulated DB error"));
        when(interfaceRepository.findById(interfaceId2)).thenReturn(Optional.of(iface2));
        when(snapshotRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        InterfaceSnapshotService.RefreshResult result = snapshotService.refreshSnapshotsFromLiveInterface(runId);

        assertThat(result.errors()).as("first snapshot failed").isEqualTo(1);
        assertThat(result.refreshed()).as("second snapshot still refreshed").isEqualTo(1);
        // Only snap2 reached save() - snap1 short-circuited on exception.
        verify(snapshotRepository, times(1)).save(any());
    }

    @Test
    void refreshSnapshots_partialFieldChangeStillTriggersWrite() {
        // Only ONE field diverges (e.g. agent edited just the JS template, HTML/CSS unchanged).
        // The Objects.equals comparison must catch the partial divergence and write through -
        // a regression to a "compare HTML only" check would silently drop JS/CSS edits.
        InterfaceRunSnapshotEntity snap = InterfaceRunSnapshotEntity.fromInterface(testInterface, runId);
        snap.setJsTemplate("/* OLD JS - agent has since changed this */");

        when(snapshotRepository.findByWorkflowRunId(runId)).thenReturn(List.of(snap));
        when(interfaceRepository.findById(interfaceId)).thenReturn(Optional.of(testInterface));
        when(snapshotRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        InterfaceSnapshotService.RefreshResult result = snapshotService.refreshSnapshotsFromLiveInterface(runId);

        assertThat(result.refreshed()).isEqualTo(1);
        ArgumentCaptor<InterfaceRunSnapshotEntity> captor = ArgumentCaptor.forClass(InterfaceRunSnapshotEntity.class);
        verify(snapshotRepository).save(captor.capture());
        assertThat(captor.getValue().getJsTemplate()).isEqualTo("alert(1);");
    }

    @Test
    void refreshSnapshots_mixedCountersInOneCall() {
        // Realistic prod shape: a workflow run has 3 interfaces (form + dashboard + detail).
        // Between epoch N and N+1 the agent: (a) deletes the detail interface, (b) edits the
        // dashboard HTML, (c) leaves the form alone. The refresh must produce mixed counters
        // (refreshed=1, unchanged=1, missing=1) in a single call. Without this test a
        // regression that mis-buckets one branch into another (e.g. counts "missing" as
        // "refreshed") would slip through - the existing single-shape tests each isolate
        // one branch.
        UUID interfaceIdChanged = UUID.randomUUID();
        UUID interfaceIdUnchanged = UUID.randomUUID();
        UUID interfaceIdDeleted = UUID.randomUUID();

        InterfaceEntity ifaceChanged = new InterfaceEntity();
        ifaceChanged.setId(interfaceIdChanged);
        ifaceChanged.setTenantId("tenant-1");
        ifaceChanged.setName("Dashboard");
        ifaceChanged.setHtmlTemplate("<div>NEW dashboard</div>");

        InterfaceEntity ifaceUnchanged = new InterfaceEntity();
        ifaceUnchanged.setId(interfaceIdUnchanged);
        ifaceUnchanged.setTenantId("tenant-1");
        ifaceUnchanged.setName("Form");
        ifaceUnchanged.setHtmlTemplate("<form>stable</form>");

        InterfaceRunSnapshotEntity snapChanged = new InterfaceRunSnapshotEntity(
            "tenant-1", interfaceIdChanged, runId, "Dashboard", null, "<div>OLD dashboard</div>");
        InterfaceRunSnapshotEntity snapUnchanged = new InterfaceRunSnapshotEntity(
            "tenant-1", interfaceIdUnchanged, runId, "Form", null, "<form>stable</form>");
        InterfaceRunSnapshotEntity snapDeleted = new InterfaceRunSnapshotEntity(
            "tenant-1", interfaceIdDeleted, runId, "Detail (gone)", null, "<div>FROZEN detail</div>");

        when(snapshotRepository.findByWorkflowRunId(runId))
            .thenReturn(List.of(snapChanged, snapUnchanged, snapDeleted));
        when(interfaceRepository.findById(interfaceIdChanged)).thenReturn(Optional.of(ifaceChanged));
        when(interfaceRepository.findById(interfaceIdUnchanged)).thenReturn(Optional.of(ifaceUnchanged));
        when(interfaceRepository.findById(interfaceIdDeleted)).thenReturn(Optional.empty());
        when(snapshotRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        InterfaceSnapshotService.RefreshResult result = snapshotService.refreshSnapshotsFromLiveInterface(runId);

        assertThat(result.refreshed()).as("dashboard HTML changed → refreshed").isEqualTo(1);
        assertThat(result.unchanged()).as("form unchanged → unchanged").isEqualTo(1);
        assertThat(result.missing()).as("detail deleted → missing, snapshot preserved").isEqualTo(1);
        assertThat(result.errors()).isZero();
        assertThat(result.total()).as("counters partition every snapshot exactly once").isEqualTo(3);
        // Only the changed one was saved.
        verify(snapshotRepository, times(1)).save(any());
        // The deleted-source snapshot's frozen HTML must remain intact.
        assertThat(snapDeleted.getHtmlTemplate()).isEqualTo("<div>FROZEN detail</div>");
    }

    @Test
    void refreshSnapshots_doesNotTouchMappings() {
        // variableMappings / actionMappings live on the workflow plan, NOT on the interface
        // entity. The plan-side refresh (WorkflowResumeService.refreshPlanFromWorkflowDefinition)
        // owns them. This refresh must leave them alone - otherwise a concurrent plan refresh
        // could overwrite the agent's freshly-edited mappings with stale interface-entity data.
        Map<String, String> varMap = new LinkedHashMap<>();
        varMap.put("postsJson", "{{core:prepare_data.output.result.postsJson}}");
        Map<String, String> actionMap = new LinkedHashMap<>();
        actionMap.put("#searchForm", "trigger:search_input:submit");

        InterfaceRunSnapshotEntity snap = InterfaceRunSnapshotEntity.fromInterface(testInterface, runId);
        snap.setHtmlTemplate("<div>OLD</div>");  // force refresh path
        snap.setVariableMappings(varMap);
        snap.setActionMappings(actionMap);

        when(snapshotRepository.findByWorkflowRunId(runId)).thenReturn(List.of(snap));
        when(interfaceRepository.findById(interfaceId)).thenReturn(Optional.of(testInterface));
        when(snapshotRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        snapshotService.refreshSnapshotsFromLiveInterface(runId);

        ArgumentCaptor<InterfaceRunSnapshotEntity> captor = ArgumentCaptor.forClass(InterfaceRunSnapshotEntity.class);
        verify(snapshotRepository).save(captor.capture());
        InterfaceRunSnapshotEntity saved = captor.getValue();
        assertThat(saved.getVariableMappings())
                .as("variableMappings owned by plan refresh, must stay untouched")
                .isEqualTo(varMap);
        assertThat(saved.getActionMappings())
                .as("actionMappings owned by plan refresh, must stay untouched")
                .isEqualTo(actionMap);
    }

    // ===== Org-strict snapshot lookup (post-V263 hardening) =====
    // Regression: before this hardening the internal endpoints `/snapshots/find` and
    // `/snapshots/by-run/{runId}` accepted a (interfaceId, workflowRunId) pair with no scope
    // check, letting a misbehaving inter-service caller UUID-guess across orgs.

    @Test
    void getSnapshotWithOrgIdRoutesToScopedFinderAndSkipsUnscoped() {
        String orgId = "org-A";
        InterfaceRunSnapshotEntity snap = InterfaceRunSnapshotEntity.fromInterface(testInterface, runId);
        when(snapshotRepository.findByInterfaceIdAndWorkflowRunIdInOrgScope(interfaceId, runId, orgId))
                .thenReturn(Optional.of(snap));

        Optional<InterfaceRunSnapshotEntity> result = snapshotService.getSnapshot(interfaceId, runId, orgId);

        assertThat(result).isPresent();
        verify(snapshotRepository).findByInterfaceIdAndWorkflowRunIdInOrgScope(interfaceId, runId, orgId);
        verify(snapshotRepository, never()).findByInterfaceIdAndWorkflowRunId(any(), any());
    }

    @Test
    void getSnapshotWithCrossOrgReturnsEmptyEvenWhenSnapshotExists() {
        String orgB = "org-B";
        // Scoped finder returns empty for the wrong org → UUID-guess from another org gets 404.
        when(snapshotRepository.findByInterfaceIdAndWorkflowRunIdInOrgScope(interfaceId, runId, orgB))
                .thenReturn(Optional.empty());

        Optional<InterfaceRunSnapshotEntity> result = snapshotService.getSnapshot(interfaceId, runId, orgB);

        assertThat(result).isEmpty();
        verify(snapshotRepository, never()).findByInterfaceIdAndWorkflowRunId(any(), any());
    }

    @Test
    void getSnapshotWithNullOrgIdFallsBackToUnscopedLegacyFinder() {
        when(snapshotRepository.findByInterfaceIdAndWorkflowRunId(interfaceId, runId))
                .thenReturn(Optional.empty());

        snapshotService.getSnapshot(interfaceId, runId, null);

        verify(snapshotRepository).findByInterfaceIdAndWorkflowRunId(interfaceId, runId);
        verify(snapshotRepository, never()).findByInterfaceIdAndWorkflowRunIdInOrgScope(any(), any(), any());
    }

    @Test
    void getSnapshotWithBlankOrgIdFallsBackToUnscopedFinder() {
        when(snapshotRepository.findByInterfaceIdAndWorkflowRunId(interfaceId, runId))
                .thenReturn(Optional.empty());

        snapshotService.getSnapshot(interfaceId, runId, "  ");

        verify(snapshotRepository).findByInterfaceIdAndWorkflowRunId(interfaceId, runId);
        verify(snapshotRepository, never()).findByInterfaceIdAndWorkflowRunIdInOrgScope(any(), any(), any());
    }

    @Test
    void getSnapshotsForRunWithOrgIdRoutesToScopedFinder() {
        String orgId = "org-A";
        when(snapshotRepository.findByWorkflowRunIdInOrgScope(runId, orgId)).thenReturn(List.of());

        snapshotService.getSnapshotsForRun(runId, orgId);

        verify(snapshotRepository).findByWorkflowRunIdInOrgScope(runId, orgId);
        verify(snapshotRepository, never()).findByWorkflowRunId(any());
    }

    @Test
    void getSnapshotsForRunWithoutOrgIdFallsBackToUnscoped() {
        when(snapshotRepository.findByWorkflowRunId(runId)).thenReturn(List.of());

        snapshotService.getSnapshotsForRun(runId, null);

        verify(snapshotRepository).findByWorkflowRunId(runId);
        verify(snapshotRepository, never()).findByWorkflowRunIdInOrgScope(any(), any());
    }

    @Test
    void backwardCompat2ArgGetSnapshotDelegatesToUnscoped() {
        when(snapshotRepository.findByInterfaceIdAndWorkflowRunId(interfaceId, runId))
                .thenReturn(Optional.empty());

        snapshotService.getSnapshot(interfaceId, runId);

        verify(snapshotRepository).findByInterfaceIdAndWorkflowRunId(interfaceId, runId);
        verify(snapshotRepository, never()).findByInterfaceIdAndWorkflowRunIdInOrgScope(any(), any(), any());
    }

    // ===== refresh-from-live: org-scope on the mutating endpoint =====
    // Regression: the internal POST /snapshots/refresh-from-live/{runId} endpoint had ZERO scope
    // check, letting any inter-service caller UUID-guess across orgs and overwrite snapshot
    // HTML/CSS/JS by triggering a refresh from the live (cross-org) interface. The org-aware
    // overload routes the read through findByWorkflowRunIdInOrgScope so the write set is empty.

    @Test
    void refreshWithOrgIdRoutesToScopedQueryAndDoesNotWriteCrossOrg() {
        String orgId = "org-A";
        // Scoped query returns empty for this orgId (no snapshots match) → refresh is a no-op.
        when(snapshotRepository.findByWorkflowRunIdInOrgScope(runId, orgId)).thenReturn(List.of());

        InterfaceSnapshotService.RefreshResult result =
                snapshotService.refreshSnapshotsFromLiveInterface(runId, orgId);

        assertThat(result.total()).isZero();
        verify(snapshotRepository).findByWorkflowRunIdInOrgScope(runId, orgId);
        verify(snapshotRepository, never()).findByWorkflowRunId(any());
        verify(snapshotRepository, never()).save(any());
    }

    @Test
    void refreshWithCrossOrgIdDoesNotMutateForeignSnapshots() {
        String wrongOrg = "org-X";
        InterfaceRunSnapshotEntity foreignSnap = InterfaceRunSnapshotEntity.fromInterface(testInterface, runId);
        foreignSnap.setHtmlTemplate("<div>old</div>");
        // Prove the gap is closed by SHAPE: the unscoped legacy finder WOULD return the foreign
        // snapshot (and a buggy code path that falls through to it would mutate the row), but
        // the scoped finder must be the one we hit and it filters by parent
        // interface.organizationId - empty list → refresh loop never executes → no save.
        // verify(.., never()).findByWorkflowRunId asserts the unscoped path is NOT taken.
        // lenient - the stub demonstrates the foreign row exists in the unscoped result set,
        // but the scoped path runs first and returns empty so this stub is intentionally unused
        // at runtime. The verify(never()) below is the load-bearing assertion.
        lenient().when(snapshotRepository.findByWorkflowRunId(runId)).thenReturn(List.of(foreignSnap));
        when(snapshotRepository.findByWorkflowRunIdInOrgScope(runId, wrongOrg)).thenReturn(List.of());

        InterfaceSnapshotService.RefreshResult result =
                snapshotService.refreshSnapshotsFromLiveInterface(runId, wrongOrg);

        assertThat(result.refreshed()).isZero();
        assertThat(result.total()).isZero();
        verify(snapshotRepository).findByWorkflowRunIdInOrgScope(runId, wrongOrg);
        verify(snapshotRepository, never()).findByWorkflowRunId(any());
        verify(snapshotRepository, never()).save(any());
    }

    @Test
    void refreshWithNullOrgIdFallsBackToUnscopedAndLogsWarning() {
        when(snapshotRepository.findByWorkflowRunId(runId)).thenReturn(List.of());

        snapshotService.refreshSnapshotsFromLiveInterface(runId, null);

        verify(snapshotRepository).findByWorkflowRunId(runId);
        verify(snapshotRepository, never()).findByWorkflowRunIdInOrgScope(any(), any());
    }

    @Test
    void backwardCompat1ArgRefreshDelegatesToUnscoped() {
        when(snapshotRepository.findByWorkflowRunId(runId)).thenReturn(List.of());

        snapshotService.refreshSnapshotsFromLiveInterface(runId);

        verify(snapshotRepository).findByWorkflowRunId(runId);
        verify(snapshotRepository, never()).findByWorkflowRunIdInOrgScope(any(), any());
    }
}
