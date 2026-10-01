package com.apimarketplace.orchestrator.services;

import com.apimarketplace.interfaces.client.InterfaceClient;
import com.apimarketplace.interfaces.client.dto.InterfaceDto;
import com.apimarketplace.interfaces.client.dto.InterfaceSnapshotDto;
import com.apimarketplace.orchestrator.config.OrchestratorLimitsConfig;
import com.apimarketplace.orchestrator.domain.WorkflowRunEntity;
import com.apimarketplace.orchestrator.persistence.WorkflowStepDataRepository;
import com.apimarketplace.orchestrator.repository.EpochItemProjection;
import com.apimarketplace.orchestrator.repository.WorkflowRunRepository;
import com.apimarketplace.orchestrator.services.InterfaceRenderService.ResolvedTemplateSnapshot;
import com.apimarketplace.orchestrator.services.context.RunContextService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The page a capture (screenshot / PDF / video) and the {@code rendered_*} outputs are drawn from.
 *
 * <p>Live bug (2026-09-29, Pro Mail Filter): an interface inside a split rendered a reply
 * preview per mail. The node's own reported variables were right for item 0, but the captured
 * PNG showed item 4's sender and subject: the epoch-only resolution lists every row of the
 * interface node in the epoch, the SKIPPED rows of the items it did not run for included, sorts
 * them highest index first and keeps the first. These tests reproduce that batch.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("InterfaceRenderService - snapshot resolved for one execution")
class InterfaceRenderServiceItemSnapshotTest {

    @Mock private RunContextService runContextService;
    @Mock private OrchestratorLimitsConfig renderLimits;
    @Mock private WorkflowRunRepository workflowRunRepository;
    @Mock private InterfaceClient interfaceClient;
    @Mock private WorkflowStepDataRepository stepDataRepository;

    @InjectMocks private InterfaceRenderService interfaceRenderService;

    private static final UUID INTERFACE_ID = UUID.fromString("ea3cb965-9f23-46d9-94c1-809278e9a7fc");
    private static final UUID WORKFLOW_RUN_ID = UUID.fromString("00000000-0000-0000-0000-00000000c0de");
    private static final String RUN_ID = "run_split_batch";
    private static final String CALLER_TENANT = "tenant-caller";
    private static final String OWNER_TENANT = "tenant-owner";
    private static final String INTERFACE_KEY = "interface:mail_preview";
    private static final int EPOCH = 1;
    private static final Map<String, String> MAPPINGS = Map.of("subject", "{{core:snapshot.output.subject}}");

    @BeforeEach
    void setUp() {
        lenient().when(renderLimits.getMaxRowsPerVariable()).thenReturn(200);
        lenient().when(renderLimits.getMaxStorageRowBytes()).thenReturn(131072);
        lenient().when(renderLimits.getMaxResolvedVariableBytes()).thenReturn(5_000_000);
        lenient().when(renderLimits.getMaxItemsPerRender()).thenReturn(50);
        lenient().when(renderLimits.getOnExceed()).thenReturn(OrchestratorLimitsConfig.OnExceed.truncate);

        WorkflowRunEntity run = new WorkflowRunEntity();
        run.setTenantId(OWNER_TENANT);
        lenient().when(workflowRunRepository.findByRunIdPublic(RUN_ID)).thenReturn(Optional.of(run));
        lenient().when(stepDataRepository.findWorkflowRunIdsByRunId(RUN_ID)).thenReturn(List.of(WORKFLOW_RUN_ID));

        // Every item resolves to its OWN subject, so a page drawn from the wrong item is visible.
        lenient().when(runContextService.resolveVariablePaginated(
                anyString(), eq(RUN_ID), eq(OWNER_TENANT), anyInt(), anyInt(), anyInt()))
            .thenReturn(null);
        lenient().when(runContextService.evaluateExpressionsForItemNarrowed(
                eq(RUN_ID), eq(OWNER_TENANT), eq(EPOCH), anyInt(), anyInt(),
                anyMap(), anyInt(), anyInt(), any()))
            .thenAnswer(inv -> Map.of("subject", "subject of item " + inv.getArgument(4)));
    }

    private void interfaceSnapshot(String html, Map<String, String> mappings) {
        InterfaceSnapshotDto dto = new InterfaceSnapshotDto();
        dto.setHtmlTemplate(html);
        dto.setCssTemplate("h1{color:red}");
        dto.setJsTemplate("window.__DONE__=true;");
        dto.setFormat("classic");
        dto.setVariableMappings(mappings);
        lenient().when(interfaceClient.getSnapshot(INTERFACE_ID, WORKFLOW_RUN_ID, OWNER_TENANT)).thenReturn(dto);
    }

    /** The prod batch: one interface row per item of a 5-item split, 4 of them SKIPPED. */
    private void interfaceRowsForFiveItems() {
        lenient().when(stepDataRepository.findInterfaceNormalizedKeysByRunId(RUN_ID)).thenReturn(List.of(INTERFACE_KEY));
        List<EpochItemProjection> rows = IntStream.range(0, 5).mapToObj(i -> row(EPOCH, 0, i)).toList();
        lenient().when(stepDataRepository.findDistinctEpochItemPairsByRunIdAndNormalizedKey(RUN_ID, INTERFACE_KEY))
            .thenReturn(rows);
    }

    private static EpochItemProjection row(int epoch, int spawn, int itemIndex) {
        return new EpochItemProjection() {
            @Override public Integer getEpoch() { return epoch; }
            @Override public Integer getItemIndex() { return itemIndex; }
            @Override public Integer getSpawn() { return spawn; }
            @Override public Instant getMinStartTime() { return Instant.EPOCH; }
        };
    }

    @Test
    @DisplayName("in a 5-item split, the page for item 0 carries item 0's data, not item 4's")
    void pageForTheExecutedItemCarriesThatItemsData() {
        interfaceSnapshot("<h1>{{subject|none}}</h1>", MAPPINGS);
        interfaceRowsForFiveItems();

        Optional<ResolvedTemplateSnapshot> snapshot =
            interfaceRenderService.resolveTemplateSnapshot(INTERFACE_ID, RUN_ID, CALLER_TENANT, EPOCH, 0, 0);

        assertThat(snapshot).isPresent();
        assertThat(snapshot.get().html()).isEqualTo("<h1>subject of item 0</h1>");
        assertThat(snapshot.get().vars()).containsEntry("subject", "subject of item 0");
    }

    @Test
    @DisplayName("the epoch-only overload is unchanged: it still draws the epoch's first row (the highest index)")
    void epochOnlyOverloadKeepsItsBehaviour() {
        // Pins the behaviour the item-scoped overload exists to avoid, and that callers with no
        // item (itemIndex null) still get unchanged.
        interfaceSnapshot("<h1>{{subject|none}}</h1>", MAPPINGS);
        interfaceRowsForFiveItems();

        Optional<ResolvedTemplateSnapshot> snapshot =
            interfaceRenderService.resolveTemplateSnapshot(INTERFACE_ID, RUN_ID, CALLER_TENANT, EPOCH);

        assertThat(snapshot).isPresent();
        assertThat(snapshot.get().html()).isEqualTo("<h1>subject of item 4</h1>");
    }

    @Test
    @DisplayName("a null item index delegates to the epoch-only overload, byte for byte")
    void nullItemIndexDelegatesToTheEpochOnlyOverload() {
        interfaceSnapshot("<h1>{{subject|none}}</h1>", MAPPINGS);
        interfaceRowsForFiveItems();

        ResolvedTemplateSnapshot withoutItem =
            interfaceRenderService.resolveTemplateSnapshot(INTERFACE_ID, RUN_ID, CALLER_TENANT, EPOCH, 3, null).orElseThrow();
        ResolvedTemplateSnapshot epochOnly =
            interfaceRenderService.resolveTemplateSnapshot(INTERFACE_ID, RUN_ID, CALLER_TENANT, EPOCH).orElseThrow();

        assertThat(withoutItem).isEqualTo(epochOnly);
    }

    @Test
    @DisplayName("resolves at the execution's own spawn and index, under the run OWNER's tenant")
    void resolvesAtTheExecutionsCoordinatesUnderTheOwnersTenant() {
        interfaceSnapshot("<h1>{{subject|none}}</h1>", MAPPINGS);

        interfaceRenderService.resolveTemplateSnapshot(INTERFACE_ID, RUN_ID, CALLER_TENANT, EPOCH, 2, 3);

        verify(runContextService).evaluateExpressionsForItemNarrowed(
            eq(RUN_ID), eq(OWNER_TENANT), eq(EPOCH), eq(2), eq(3), anyMap(), anyInt(), anyInt(), any());
    }

    @Test
    @DisplayName("never lists the epoch's interface rows: that listing is where the wrong item came from")
    void neverListsTheEpochsInterfaceRows() {
        interfaceSnapshot("<h1>{{subject|none}}</h1>", MAPPINGS);
        interfaceRowsForFiveItems();

        interfaceRenderService.resolveTemplateSnapshot(INTERFACE_ID, RUN_ID, CALLER_TENANT, EPOCH, 0, 0);

        verify(stepDataRepository, never()).findDistinctEpochItemPairsByRunIdAndNormalizedKey(anyString(), anyString());
        verify(stepDataRepository, never()).findDistinctEpochItemPairsExcludingTriggers(anyString());
    }

    @Test
    @DisplayName("carries the snapshot's css, js and format, like the epoch-only overload")
    void carriesCssJsAndFormat() {
        interfaceSnapshot("<h1>{{subject|none}}</h1>", MAPPINGS);

        ResolvedTemplateSnapshot snapshot =
            interfaceRenderService.resolveTemplateSnapshot(INTERFACE_ID, RUN_ID, CALLER_TENANT, EPOCH, 0, 0).orElseThrow();

        assertThat(snapshot.css()).isEqualTo("h1{color:red}");
        assertThat(snapshot.js()).isEqualTo("window.__DONE__=true;");
        assertThat(snapshot.format()).isEqualTo("classic");
    }

    @Test
    @DisplayName("a static interface (no variable mapping) renders its defaults and resolves nothing")
    void staticInterfaceResolvesNothing() {
        interfaceSnapshot("<h1>{{subject|none}}</h1>", Map.of());

        ResolvedTemplateSnapshot snapshot =
            interfaceRenderService.resolveTemplateSnapshot(INTERFACE_ID, RUN_ID, CALLER_TENANT, EPOCH, 0, 0).orElseThrow();

        assertThat(snapshot.html()).isEqualTo("<h1>none</h1>");
        assertThat(snapshot.vars()).isEmpty();
        verifyNoInteractions(runContextService);
    }

    @Test
    @DisplayName("no template renders an empty page, as render() does, and resolves nothing")
    void noTemplateRendersAnEmptyPage() {
        lenient().when(interfaceClient.getSnapshot(INTERFACE_ID, WORKFLOW_RUN_ID, OWNER_TENANT)).thenReturn(null);
        lenient().when(interfaceClient.getInterfaceTemplateForRender(INTERFACE_ID)).thenReturn(null);

        ResolvedTemplateSnapshot itemScoped =
            interfaceRenderService.resolveTemplateSnapshot(INTERFACE_ID, RUN_ID, CALLER_TENANT, EPOCH, 0, 0).orElseThrow();
        ResolvedTemplateSnapshot epochOnly =
            interfaceRenderService.resolveTemplateSnapshot(INTERFACE_ID, RUN_ID, CALLER_TENANT, EPOCH).orElseThrow();

        assertThat(itemScoped.html()).isEmpty();
        assertThat(itemScoped).isEqualTo(epochOnly);
        verifyNoInteractions(runContextService);
    }

    @Test
    @DisplayName("an interface with no row yet (the executing item has not persisted) still resolves that item")
    void resolvesTheItemBeforeTheInterfaceHasAnyRow() {
        // The capture runs INSIDE the execution, before the node's own row is written; for the
        // first item of a run no interface row exists at all.
        interfaceSnapshot("<h1>{{subject|none}}</h1>", MAPPINGS);
        lenient().when(stepDataRepository.findInterfaceNormalizedKeysByRunId(RUN_ID)).thenReturn(List.of());

        ResolvedTemplateSnapshot snapshot =
            interfaceRenderService.resolveTemplateSnapshot(INTERFACE_ID, RUN_ID, CALLER_TENANT, EPOCH, 0, 2).orElseThrow();

        assertThat(snapshot.html()).isEqualTo("<h1>subject of item 2</h1>");
    }

    private void runInWorkspace(String tenant, String org) {
        WorkflowRunEntity run = new WorkflowRunEntity();
        run.setTenantId(tenant);
        run.setOrganizationId(org);
        lenient().when(workflowRunRepository.findByRunIdPublic(RUN_ID)).thenReturn(Optional.of(run));
    }

    private static InterfaceDto liveInterface(String tenant, String org) {
        InterfaceDto dto = new InterfaceDto();
        dto.setId(INTERFACE_ID);
        dto.setTenantId(tenant);
        dto.setOrganizationId(org);
        dto.setHtmlTemplate("<div>live template</div>");
        return dto;
    }

    @Test
    @DisplayName("live-interface fallback: an interface of the run's own workspace renders through the item-scoped path")
    void liveFallbackInTheRunsWorkspaceRenders() {
        runInWorkspace(OWNER_TENANT, "org-1");
        lenient().when(interfaceClient.getSnapshot(INTERFACE_ID, WORKFLOW_RUN_ID, OWNER_TENANT)).thenReturn(null);
        lenient().when(interfaceClient.getInterfaceTemplateForRender(INTERFACE_ID))
            .thenReturn(liveInterface("teammate", "org-1"));

        ResolvedTemplateSnapshot snapshot =
            interfaceRenderService.resolveTemplateSnapshot(INTERFACE_ID, RUN_ID, CALLER_TENANT, EPOCH, 0, 0).orElseThrow();

        assertThat(snapshot.html()).isEqualTo("<div>live template</div>");
    }

    @Test
    @DisplayName("live-interface fallback: another workspace's interface renders nothing through the item-scoped path")
    void liveFallbackOutsideTheRunsWorkspaceRendersNothing() {
        runInWorkspace("attacker", "org-attacker");
        lenient().when(interfaceClient.getSnapshot(any(), any(), any())).thenReturn(null);
        lenient().when(interfaceClient.getInterfaceTemplateForRender(INTERFACE_ID))
            .thenReturn(liveInterface("victim", "org-victim"));

        ResolvedTemplateSnapshot snapshot =
            interfaceRenderService.resolveTemplateSnapshot(INTERFACE_ID, RUN_ID, "attacker", EPOCH, 0, 0).orElseThrow();

        assertThat(snapshot.html()).isEmpty();
        assertThat(snapshot.html()).doesNotContain("live template");
    }
}
