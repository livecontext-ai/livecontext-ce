package com.apimarketplace.orchestrator.services;

import com.apimarketplace.datasource.client.DataSourceClient;
import com.apimarketplace.datasource.client.dto.DataSourceItemDto;
import com.apimarketplace.interfaces.client.InterfaceClient;
import com.apimarketplace.interfaces.client.dto.InterfaceDto;
import com.apimarketplace.interfaces.client.dto.InterfaceSnapshotDto;
import com.apimarketplace.orchestrator.config.OrchestratorLimitsConfig;
import com.apimarketplace.orchestrator.persistence.WorkflowStepDataRepository;
import com.apimarketplace.orchestrator.repository.EpochItemProjection;
import com.apimarketplace.orchestrator.repository.WorkflowRunRepository;
import com.apimarketplace.orchestrator.services.context.RunContextService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Regression coverage for {@link OrchestratorLimitsConfig} hard caps wired into
 * {@link InterfaceRenderService#renderWithDatasource(UUID, String, int, int)}.
 *
 * <p>Pre-fix behaviour: {@code renderWithDatasource} called {@code DataSourceClient.getAllItems()}
 * which loaded the entire table (up to 10 000 rows) into orchestrator heap, then sliced 99% away
 * via {@code subList}. With a non-paginable interface bound to a 10 000-row datasource the heap
 * footprint scaled with the datasource size and the iframe received an unbounded HTML payload.
 *
 * <p>Each test names the cap being exercised so a regression makes the failing test obvious.
 */
@ExtendWith(MockitoExtension.class)
class InterfaceRenderServiceRenderLimitsTest {

    private static final UUID INTERFACE_ID = UUID.fromString("00000000-0000-0000-0000-000000000abc");
    private static final Long DATASOURCE_ID = 42L;
    private static final String TENANT = "tenantA";

    @Mock
    private InterfaceClient interfaceClient;

    @Mock
    private DataSourceClient dataSourceClient;

    @Mock
    private TemplateEngine templateEngine;

    @Mock
    private WorkflowStepDataRepository stepDataRepository;

    @Mock
    private WorkflowRunRepository workflowRunRepository;

    @Mock
    private RunContextService runContextService;

    @InjectMocks
    private InterfaceRenderService service;

    private OrchestratorLimitsConfig limits;

    @BeforeEach
    void setUp() {
        limits = new OrchestratorLimitsConfig();
        ReflectionTestUtils.setField(service, "renderLimits", limits);

        InterfaceDto iface = new InterfaceDto();
        iface.setHtmlTemplate("<div>{{title}}</div>");
        iface.setDataSourceId(DATASOURCE_ID);
        // lenient - used only by the renderWithDatasource tests; the render() workflow-run tests
        // hit getSnapshot instead, so this stub is intentionally unused by half the class.
        lenient().when(interfaceClient.getInterfaceTemplateForRender(INTERFACE_ID)).thenReturn(iface);
    }

    @Test
    @DisplayName("Bounded fetch: requested size 2000 with cap 500 only asks the datasource for 500 rows")
    void clampsRequestedSizeToMaxItemsPerRender() {
        limits.setMaxItemsPerRender(500);
        when(dataSourceClient.getItemsCount(DATASOURCE_ID, TENANT)).thenReturn(10_000);
        when(dataSourceClient.getItems(eq(DATASOURCE_ID), eq(TENANT), eq(0), eq(500)))
                .thenReturn(buildItems(500, "tiny"));
        when(templateEngine.resolveWithMap(anyString(), any())).thenReturn("<div>tiny</div>");

        InterfaceRenderService.InterfaceRenderResult result =
                service.renderWithDatasource(INTERFACE_ID, TENANT, 0, 2000);

        ArgumentCaptor<Integer> limitCaptor = ArgumentCaptor.forClass(Integer.class);
        verify(dataSourceClient).getItems(eq(DATASOURCE_ID), eq(TENANT), eq(0), limitCaptor.capture());
        assertThat(limitCaptor.getValue()).isEqualTo(500);
        verify(dataSourceClient, never()).getAllItems(anyLong(), anyString());

        assertThat(result.items()).hasSize(500);
        assertThat(result.truncation()).isNotNull();
        assertThat(result.truncation().reason()).isEqualTo("max_items_per_render");
        assertThat(result.truncation().total()).isEqualTo(10_000L);
        assertThat(result.truncation().rendered()).isEqualTo(500);
    }

    @Test
    @DisplayName("Bytes guard: rendering stops once cumulative resolved-HTML exceeds maxPayloadBytes")
    void truncatesByBytesWhenCumulativePayloadExceedsCap() {
        limits.setMaxItemsPerRender(500);
        limits.setMaxPayloadBytes(20_000); // 10 KB UTF-16 = 5000 chars
        when(dataSourceClient.getItemsCount(DATASOURCE_ID, TENANT)).thenReturn(100);
        when(dataSourceClient.getItems(eq(DATASOURCE_ID), eq(TENANT), eq(0), eq(10)))
                .thenReturn(buildItems(10, "fat"));
        // Each rendered item is 2 000 chars → 4 000 UTF-16 bytes. The budget is 20 000 B,
        // so items 1..5 fit (sum 20 000 B, equal), item 6 trips strict > → 5 items rendered.
        String fatHtml = "a".repeat(2_000);
        when(templateEngine.resolveWithMap(anyString(), any())).thenReturn(fatHtml);

        InterfaceRenderService.InterfaceRenderResult result =
                service.renderWithDatasource(INTERFACE_ID, TENANT, 0, 10);

        assertThat(result.items()).hasSize(5);
        assertThat(result.truncation()).isNotNull();
        assertThat(result.truncation().reason()).isEqualTo("max_payload_bytes");
        assertThat(result.truncation().rendered()).isEqualTo(5);
        assertThat(result.truncation().total()).isEqualTo(100L);
        assertThat(result.truncation().payloadBytes()).isEqualTo(5L * 2_000L * 2L);
    }

    @Test
    @DisplayName("Happy path: under-cap render returns null truncation so frontend can skip the meta banner")
    void omitsTruncationWhenUnderBothCaps() {
        limits.setMaxItemsPerRender(500);
        when(dataSourceClient.getItemsCount(DATASOURCE_ID, TENANT)).thenReturn(20);
        when(dataSourceClient.getItems(eq(DATASOURCE_ID), eq(TENANT), eq(0), eq(20)))
                .thenReturn(buildItems(20, "ok"));
        when(templateEngine.resolveWithMap(anyString(), any())).thenReturn("<div>ok</div>");

        InterfaceRenderService.InterfaceRenderResult result =
                service.renderWithDatasource(INTERFACE_ID, TENANT, 0, 20);

        assertThat(result.items()).hasSize(20);
        assertThat(result.truncation()).isNull();
    }

    @Test
    @DisplayName("onExceed=fail: count clamp throws IllegalStateException instead of silently truncating")
    void failsFastWhenOnExceedIsFailAndCountClamped() {
        limits.setMaxItemsPerRender(500);
        limits.setOnExceed(OrchestratorLimitsConfig.OnExceed.fail);
        // The interface fetch (getInterfaceTemplateForRender) IS called once before the cap check
        // - that's stubbed in @BeforeEach. What MUST NOT be called is anything that touches the
        // datasource: getItemsCount, getItems, or the deprecated getAllItems. Verify all three.

        assertThatThrownBy(() -> service.renderWithDatasource(INTERFACE_ID, TENANT, 0, 2000))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("maxItemsPerRender=500")
                .hasMessageContaining("onExceed=fail");

        verify(dataSourceClient, never()).getItemsCount(anyLong(), anyString());
        verify(dataSourceClient, never()).getItems(anyLong(), anyString(), anyInt(), anyInt());
        verify(dataSourceClient, never()).getAllItems(anyLong(), anyString());
    }

    @Test
    @DisplayName("First-item bypass: when item 1 alone exceeds maxPayloadBytes, render it anyway (don't return empty)")
    void firstItemAlwaysIncluded() {
        // Regression for the !items.isEmpty() guard in the byte-cap loop. If a refactor
        // simplifies to `cumulativeBytes + itemBytes > maxBytes` the iframe would receive zero
        // items whenever item[0] is bigger than the budget - silent regression with a healthy
        // HTTP 200 + empty response.
        limits.setMaxItemsPerRender(500);
        limits.setMaxPayloadBytes(100); // tiny budget
        when(dataSourceClient.getItemsCount(DATASOURCE_ID, TENANT)).thenReturn(3);
        when(dataSourceClient.getItems(eq(DATASOURCE_ID), eq(TENANT), eq(0), eq(3)))
                .thenReturn(buildItems(3, "huge"));
        // First call returns a 1000-char string → 2000 UTF-16 bytes ≫ 100. Items 2-3 same.
        when(templateEngine.resolveWithMap(anyString(), any())).thenReturn("a".repeat(1_000));

        InterfaceRenderService.InterfaceRenderResult result =
                service.renderWithDatasource(INTERFACE_ID, TENANT, 0, 3);

        assertThat(result.items()).hasSize(1);
        assertThat(result.truncation()).isNotNull();
        assertThat(result.truncation().reason()).isEqualTo("max_payload_bytes");
        assertThat(result.truncation().rendered()).isEqualTo(1);
        assertThat(result.truncation().total()).isEqualTo(3L);
        // Item 1 = 1 000 chars × 2 (UTF-16) = 2 000 bytes cumulative. Asserting the exact value
        // catches a refactor that would silently zero-out payloadBytes on the count-clamp path.
        assertThat(result.truncation().payloadBytes()).isEqualTo(2_000L);
    }

    @Test
    @DisplayName("onExceed=fail: bytes clamp throws IllegalStateException once cumulative payload exceeds the budget")
    void failsFastWhenOnExceedIsFailAndBytesClamped() {
        limits.setMaxItemsPerRender(500);
        limits.setMaxPayloadBytes(8_000); // 4 000 chars budget
        limits.setOnExceed(OrchestratorLimitsConfig.OnExceed.fail);
        when(dataSourceClient.getItemsCount(DATASOURCE_ID, TENANT)).thenReturn(50);
        when(dataSourceClient.getItems(eq(DATASOURCE_ID), eq(TENANT), eq(0), eq(10)))
                .thenReturn(buildItems(10, "fat"));
        when(templateEngine.resolveWithMap(anyString(), any())).thenReturn("a".repeat(3_000));

        assertThatThrownBy(() -> service.renderWithDatasource(INTERFACE_ID, TENANT, 0, 10))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("maxPayloadBytes=8000")
                .hasMessageContaining("onExceed=fail");
    }

    // ===== render() workflow-run path: cap on effectiveSize must surface truncation =====
    // Regression for round-3 BLOCKER: ShowcaseSnapshotBuilder calls render(..., size=200) but
    // the default cap was lowered to 100. render() silently clamped to 100 with NO truncation
    // flag - published snapshots shrank from 200 → 100 items per epoch without any signal on
    // the marketplace preview. The fix mirrors renderWithDatasource by emitting RenderTruncation
    // when cappedSize < requestedSize so single-page consumers can render "Showing N of M" or
    // iterate the remaining pages.
    @Test
    @DisplayName("render() workflow path: requested size > maxItemsPerRender emits RenderTruncation with the real total")
    void renderEmitsTruncationWhenSizeIsClamped() {
        limits.setMaxItemsPerRender(100);
        String runId = "run_abc";
        UUID workflowRunUuid = UUID.randomUUID();
        InterfaceSnapshotDto snap = new InterfaceSnapshotDto();
        snap.setHtmlTemplate("<div>{{x}}</div>");
        snap.setVariableMappings(Map.of());
        snap.setActionMappings(Map.of());

        when(workflowRunRepository.findByRunIdPublic(runId)).thenReturn(java.util.Optional.empty());
        when(stepDataRepository.findWorkflowRunIdsByRunId(runId)).thenReturn(List.of(workflowRunUuid));
        when(interfaceClient.getSnapshot(INTERFACE_ID, workflowRunUuid, TENANT)).thenReturn(snap);
        when(stepDataRepository.findInterfaceNormalizedKeysByRunId(runId)).thenReturn(List.of("interface:x"));
        // 150 distinct (epoch, itemIndex) pairs in the run - caller asks for size=200, gets clamped to 100.
        List<EpochItemProjection> projections = buildProjections(150);
        when(stepDataRepository.findDistinctEpochItemPairsByRunIdAndNormalizedKey(runId, "interface:x"))
                .thenReturn(projections);

        InterfaceRenderService.InterfaceRenderResult result =
                service.render(INTERFACE_ID, runId, TENANT, 0, 200, null);

        assertThat(result.items()).hasSize(100);
        assertThat(result.pagination().totalItems()).isEqualTo(150L);
        assertThat(result.truncation()).isNotNull();
        assertThat(result.truncation().reason()).isEqualTo("max_items_per_render");
        assertThat(result.truncation().rendered()).isEqualTo(100);
        assertThat(result.truncation().total()).isEqualTo(150L);
        // workflow-run render does not compute cumulative HTML weight server-side (resolution
        // happens client-side via window.__RESOLVED_DATA__); payloadBytes is 0 by contract.
        assertThat(result.truncation().payloadBytes()).isEqualTo(0L);
    }

    @Test
    @DisplayName("render() workflow path: trigger action extraction uses narrow context loading")
    void renderUsesNarrowContextForTriggerActionExtraction() {
        limits.setMaxStorageRowBytes(131_072);
        String runId = "run_trigger";
        UUID workflowRunUuid = UUID.randomUUID();
        InterfaceSnapshotDto snap = new InterfaceSnapshotDto();
        snap.setHtmlTemplate("<button id=\"submit\">Submit</button>");
        snap.setVariableMappings(Map.of());
        snap.setActionMappings(Map.of("#submit", "trigger:submit_form:click"));
        Map<String, Object> triggerPayload = Map.of("email", "a@example.com");
        Map<String, Object> triggerStepOutput = Map.of("output", triggerPayload);

        when(workflowRunRepository.findByRunIdPublic(runId)).thenReturn(java.util.Optional.empty());
        when(stepDataRepository.findWorkflowRunIdsByRunId(runId)).thenReturn(List.of(workflowRunUuid));
        when(interfaceClient.getSnapshot(INTERFACE_ID, workflowRunUuid, TENANT)).thenReturn(snap);
        when(stepDataRepository.findInterfaceNormalizedKeysByRunId(runId)).thenReturn(List.of("interface:stats_dashboard"));
        when(stepDataRepository.findDistinctEpochItemPairsByRunIdAndNormalizedKey(runId, "interface:stats_dashboard"))
                .thenReturn(buildProjections(1));
        when(runContextService.loadRunContextForItemNarrowed(
                eq(runId), eq(TENANT), eq(0), eq(0), eq(0), any(), eq(131_072), eq(0)))
                .thenReturn(Map.of("trigger:submit_form", triggerStepOutput));

        InterfaceRenderService.InterfaceRenderResult result =
                service.render(INTERFACE_ID, runId, TENANT, 0, 10, null);

        assertThat(result.items()).hasSize(1);
        assertThat(result.items().get(0).triggerData())
                .containsEntry("trigger:submit_form", triggerPayload);
        verify(runContextService).loadRunContextForItemNarrowed(
                eq(runId), eq(TENANT), eq(0), eq(0), eq(0), any(), eq(131_072), eq(0));
        verify(runContextService, never()).loadRunContextForItem(
                anyString(), anyString(), anyInt(), anyInt(), anyInt());
    }

    @Test
    @DisplayName("render() workflow path: requested size within cap returns null truncation (uniform contract with renderWithDatasource)")
    void renderOmitsTruncationWhenSizeWithinCap() {
        limits.setMaxItemsPerRender(100);
        String runId = "run_xyz";
        UUID workflowRunUuid = UUID.randomUUID();
        InterfaceSnapshotDto snap = new InterfaceSnapshotDto();
        snap.setHtmlTemplate("<div/>");
        snap.setVariableMappings(Map.of());
        snap.setActionMappings(Map.of());

        when(workflowRunRepository.findByRunIdPublic(runId)).thenReturn(java.util.Optional.empty());
        when(stepDataRepository.findWorkflowRunIdsByRunId(runId)).thenReturn(List.of(workflowRunUuid));
        when(interfaceClient.getSnapshot(INTERFACE_ID, workflowRunUuid, TENANT)).thenReturn(snap);
        when(stepDataRepository.findInterfaceNormalizedKeysByRunId(runId)).thenReturn(List.of("interface:x"));
        when(stepDataRepository.findDistinctEpochItemPairsByRunIdAndNormalizedKey(runId, "interface:x"))
                .thenReturn(buildProjections(5));

        InterfaceRenderService.InterfaceRenderResult result =
                service.render(INTERFACE_ID, runId, TENANT, 0, 10, null);

        assertThat(result.items()).hasSize(5);
        assertThat(result.truncation()).isNull();
    }

    private List<EpochItemProjection> buildProjections(int n) {
        List<EpochItemProjection> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            int epoch = i;
            out.add(new EpochItemProjection() {
                @Override public Integer getEpoch() { return epoch; }
                @Override public Integer getItemIndex() { return 0; }
                @Override public Integer getSpawn() { return 0; }
                @Override public Instant getMinStartTime() { return Instant.EPOCH; }
            });
        }
        return out;
    }

    private List<DataSourceItemDto> buildItems(int n, String label) {
        List<DataSourceItemDto> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            out.add(new DataSourceItemDto(
                    (long) i,
                    DATASOURCE_ID,
                    TENANT,
                    Map.of("title", label + "_" + i),
                    0,
                    Instant.EPOCH));
        }
        return out;
    }
}
