package com.apimarketplace.orchestrator.services;

import com.apimarketplace.orchestrator.config.OrchestratorLimitsConfig;
import com.apimarketplace.orchestrator.domain.WorkflowRunEntity;
import com.apimarketplace.orchestrator.repository.WorkflowRunRepository;
import com.apimarketplace.orchestrator.services.context.RunContextService;
import com.apimarketplace.orchestrator.services.context.RunContextService.PaginatedVariable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The resolution a node reports its variable mapping from.
 *
 * <p>Its whole claim is that the panel and the page cannot disagree, which rests on two
 * things nothing else asserts: it goes through the SAME resolution a render does, and it
 * reads the run OWNER's tenant rather than the caller's. Both are invisible to the node's
 * own tests, which mock this service.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("InterfaceRenderService - resolution for reporting")
class InterfaceRenderServiceReportingTest {

    @Mock private RunContextService runContextService;
    @Mock private OrchestratorLimitsConfig renderLimits;
    @Mock private WorkflowRunRepository workflowRunRepository;

    @InjectMocks private InterfaceRenderService interfaceRenderService;

    private static final String RUN_ID = "run_report_001";
    private static final String CALLER_TENANT = "tenant-caller";
    private static final String OWNER_TENANT = "tenant-owner";
    private static final int EPOCH = 4;
    private static final int SPAWN = 2;
    private static final int ITEM_INDEX = 7;

    @BeforeEach
    void setUp() {
        lenient().when(renderLimits.getMaxRowsPerVariable()).thenReturn(200);
        lenient().when(renderLimits.getMaxStorageRowBytes()).thenReturn(131072);
        lenient().when(renderLimits.getMaxResolvedVariableBytes()).thenReturn(5_000_000);
        lenient().when(renderLimits.getOnExceed()).thenReturn(OrchestratorLimitsConfig.OnExceed.truncate);
    }

    private void runIsOwnedBy(String ownerTenantId) {
        WorkflowRunEntity run = new WorkflowRunEntity();
        run.setTenantId(ownerTenantId);
        lenient().when(workflowRunRepository.findByRunIdPublic(RUN_ID)).thenReturn(Optional.of(run));
    }

    @Test
    @DisplayName("resolves under the run OWNER's tenant, not the caller's, or a teammate's row reads as empty")
    void resolvesUnderTheRunOwnersTenant() {
        runIsOwnedBy(OWNER_TENANT);
        when(runContextService.resolveVariablePaginated(
                eq("{{mcp:fetch.output.items}}"), eq(RUN_ID), eq(OWNER_TENANT), eq(EPOCH), anyInt(), anyInt()))
            .thenReturn(new PaginatedVariable(List.of(Map.of("id", 1)), 4_812, 0, 1));

        Map<String, Object> resolved = interfaceRenderService.resolveVariablesForReporting(
            Map.of("rows", "{{mcp:fetch.output.items}}"), RUN_ID, CALLER_TENANT, EPOCH, SPAWN, ITEM_INDEX);

        assertThat(resolved).containsKey("rows");
        verify(runContextService).resolveVariablePaginated(
            eq("{{mcp:fetch.output.items}}"), eq(RUN_ID), eq(OWNER_TENANT), eq(EPOCH), anyInt(), anyInt());
    }

    @Test
    @DisplayName("asks for ONE row and reports the true total: reporting needs the count, not the data")
    void asksForOneRowAndKeepsTheTrueTotal() {
        // The cost of this call is rows loaded per variable, and it is paid on a node's
        // execution path. The SQL path returns the real count whatever the page size, so a
        // caller that only describes the variable has no reason to load the render's 200.
        runIsOwnedBy(OWNER_TENANT);
        when(runContextService.resolveVariablePaginated(
                eq("{{mcp:fetch.output.items}}"), eq(RUN_ID), eq(OWNER_TENANT), eq(EPOCH), eq(0), eq(1)))
            .thenReturn(new PaginatedVariable(List.of(Map.of("id", 1)), 4_812, 0, 1));

        Map<String, Object> resolved = interfaceRenderService.resolveVariablesForReporting(
            Map.of("rows", "{{mcp:fetch.output.items}}"), RUN_ID, CALLER_TENANT, EPOCH, SPAWN, ITEM_INDEX);

        assertThat((List<?>) resolved.get("rows")).hasSize(1);
        assertThat(resolved.get("rows__total")).isEqualTo(4_812);
    }

    @Test
    @DisplayName("an expression the SQL path cannot take still resolves, through the same narrowed path a render uses")
    void fallsBackToTheNarrowedPathLikeARender() {
        runIsOwnedBy(OWNER_TENANT);
        when(runContextService.resolveVariablePaginated(
                eq("Hello {{mcp:fetch.output.name}}"), eq(RUN_ID), eq(OWNER_TENANT), eq(EPOCH), anyInt(), anyInt()))
            .thenReturn(null);
        when(runContextService.evaluateExpressionsForItemNarrowed(
                eq(RUN_ID), eq(OWNER_TENANT), eq(EPOCH), eq(SPAWN), eq(ITEM_INDEX),
                anyMap(), anyInt(), anyInt(), anyMap()))
            .thenReturn(Map.of("title", "Hello Alice"));

        Map<String, Object> resolved = interfaceRenderService.resolveVariablesForReporting(
            Map.of("title", "Hello {{mcp:fetch.output.name}}"), RUN_ID, CALLER_TENANT, EPOCH, SPAWN, ITEM_INDEX);

        assertThat(resolved).containsEntry("title", "Hello Alice");
    }

    @Test
    @DisplayName("nothing to resolve, nothing queried: an empty mapping must not cost a round trip")
    void doesNothingWhenThereIsNothingToResolve() {
        assertThat(interfaceRenderService.resolveVariablesForReporting(
            Map.of(), RUN_ID, CALLER_TENANT, EPOCH, SPAWN, ITEM_INDEX)).isEmpty();
        assertThat(interfaceRenderService.resolveVariablesForReporting(
            null, RUN_ID, CALLER_TENANT, EPOCH, SPAWN, ITEM_INDEX)).isEmpty();
        assertThat(interfaceRenderService.resolveVariablesForReporting(
            Map.of("rows", "{{x}}"), null, CALLER_TENANT, EPOCH, SPAWN, ITEM_INDEX)).isEmpty();

        verify(workflowRunRepository, never()).findByRunIdPublic(RUN_ID);
        verify(runContextService, never()).resolveVariablePaginated(
            org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any(), anyInt(), anyInt(), anyInt());
    }

    @Test
    @DisplayName("an unreadable run row falls back to the caller's tenant rather than failing the caller")
    void fallsBackToTheCallerTenantWhenTheRunRowIsGone() {
        lenient().when(workflowRunRepository.findByRunIdPublic(RUN_ID)).thenReturn(Optional.empty());
        when(runContextService.resolveVariablePaginated(
                org.mockito.ArgumentMatchers.any(), eq(RUN_ID), eq(CALLER_TENANT), eq(EPOCH), anyInt(), anyInt()))
            .thenReturn(null);
        when(runContextService.evaluateExpressionsForItemNarrowed(
                eq(RUN_ID), eq(CALLER_TENANT), eq(EPOCH), eq(SPAWN), eq(ITEM_INDEX),
                anyMap(), anyInt(), anyInt(), anyMap()))
            .thenReturn(Map.of("title", "x"));

        Map<String, Object> resolved = interfaceRenderService.resolveVariablesForReporting(
            Map.of("title", "{{core:a.output.title}}"), RUN_ID, CALLER_TENANT, EPOCH, SPAWN, ITEM_INDEX);

        assertThat(resolved).containsEntry("title", "x");
    }
}
