package com.apimarketplace.orchestrator.services;

import com.apimarketplace.orchestrator.config.OrchestratorLimitsConfig;
import com.apimarketplace.orchestrator.services.context.RunContextService;
import com.apimarketplace.orchestrator.services.context.RunContextService.PaginatedVariable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Tests for the SQL-level variable pagination path in InterfaceRenderService.
 *
 * <p>Pure array references use the SQL JSONB array-slice path
 * (resolveVariablePaginated). Explicit variablePages entries select a requested
 * page; otherwise the server resolves page 0 by default. Variables that cannot
 * use the SQL path fall back to the standard narrowed path.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("InterfaceRenderService - variable pagination split")
class InterfaceRenderServicePaginationTest {

    @Mock private RunContextService runContextService;
    @Mock private OrchestratorLimitsConfig renderLimits;

    @InjectMocks private InterfaceRenderService interfaceRenderService;

    private static final String RUN_ID = "run_pag_001";
    private static final String TENANT = "tenant-1";
    private static final int EPOCH = 5;
    private static final int SPAWN = 0;
    private static final int ITEM_INDEX = 0;

    @BeforeEach
    void setUp() {
        lenient().when(renderLimits.getMaxRowsPerVariable()).thenReturn(50);
        lenient().when(renderLimits.getMaxStorageRowBytes()).thenReturn(131072);
        lenient().when(renderLimits.getMaxResolvedVariableBytes()).thenReturn(5_000_000);
        lenient().when(renderLimits.getOnExceed()).thenReturn(OrchestratorLimitsConfig.OnExceed.truncate);
    }

    @Test
    @DisplayName("Paginated variable uses SQL path + adds metadata markers")
    void paginatedVarUsesSqlPath() throws Exception {
        Map<String, String> mappings = new LinkedHashMap<>();
        mappings.put("rows", "{{mcp:fetch.output.items}}");
        mappings.put("title", "{{mcp:fetch.output.title}}");

        Map<String, Integer> variablePages = Map.of("rows", 2);

        // SQL path returns paginated result for "rows"
        when(runContextService.resolveVariablePaginated(
                eq("{{mcp:fetch.output.items}}"), eq(RUN_ID), eq(TENANT), eq(EPOCH), eq(2), eq(50)))
            .thenReturn(new PaginatedVariable(List.of(Map.of("id", 101), Map.of("id", 102)), 485, 2, 50));

        // Standard path resolves "title" (not in variablePages)
        when(runContextService.evaluateExpressionsForItemNarrowed(
                eq(RUN_ID), eq(TENANT), eq(EPOCH), eq(SPAWN), eq(ITEM_INDEX),
                anyMap(), anyInt(), anyInt()))
            .thenReturn(Map.of("title", "My Emails"));

        // Invoke the private method via reflection
        Method method = InterfaceRenderService.class.getDeclaredMethod(
                "resolveVariablesWithPagination",
                Map.class, String.class, int.class, int.class, int.class, String.class, Map.class);
        method.setAccessible(true);

        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) method.invoke(
                interfaceRenderService, mappings, RUN_ID, EPOCH, SPAWN, ITEM_INDEX, TENANT, variablePages);

        // Paginated variable has items + metadata markers
        assertThat(result.get("rows")).isInstanceOf(List.class);
        assertThat((List<?>) result.get("rows")).hasSize(2);
        assertThat(result.get("rows__total")).isEqualTo(485);
        assertThat(result.get("rows__page")).isEqualTo(2);
        assertThat(result.get("rows__pageSize")).isEqualTo(50);
        assertThat(result.get("rows__count")).isEqualTo(2);
        assertThat(result.get("rows__totalPages")).isEqualTo(10);
        assertThat(result.get("rows__truncated")).isEqualTo(true);
        assertThat(result.get("rows__paginationSupported")).isEqualTo(true);

        // Standard variable resolved normally
        assertThat(result.get("title")).isEqualTo("My Emails");
    }

    @Test
    @DisplayName("Empty variablePages auto-paginates pure array refs on page 0")
    void emptyVariablePagesAutoPaginatesPureArrayRefs() throws Exception {
        Map<String, String> mappings = Map.of("rows", "{{mcp:fetch.output.items}}");
        Map<String, Integer> variablePages = Map.of();

        when(runContextService.resolveVariablePaginated(
                eq("{{mcp:fetch.output.items}}"), eq(RUN_ID), eq(TENANT), eq(EPOCH), eq(0), eq(50)))
            .thenReturn(new PaginatedVariable(List.of("a", "b"), 125, 0, 50));

        Method method = InterfaceRenderService.class.getDeclaredMethod(
                "resolveVariablesWithPagination",
                Map.class, String.class, int.class, int.class, int.class, String.class, Map.class);
        method.setAccessible(true);

        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) method.invoke(
                interfaceRenderService, mappings, RUN_ID, EPOCH, SPAWN, ITEM_INDEX, TENANT, variablePages);

        assertThat(result.get("rows")).isInstanceOf(List.class);
        assertThat(result.get("rows")).isEqualTo(List.of("a", "b"));
        assertThat(result.get("rows__total")).isEqualTo(125);
        assertThat(result.get("rows__page")).isEqualTo(0);
        assertThat(result.get("rows__pageSize")).isEqualTo(50);
        assertThat(result.get("rows__count")).isEqualTo(2);
        assertThat(result.get("rows__totalPages")).isEqualTo(3);
        assertThat(result.get("rows__truncated")).isEqualTo(true);
        assertThat(result.get("rows__paginationSupported")).isEqualTo(true);

        verify(runContextService, never()).evaluateExpressionsForItemNarrowed(
                any(), any(), anyInt(), anyInt(), anyInt(), anyMap(), anyInt(), anyInt());
    }

    @Test
    @DisplayName("SQL path returns null → variable falls back to standard path")
    void sqlPathFallsBackToStandard() throws Exception {
        Map<String, String> mappings = new LinkedHashMap<>();
        mappings.put("data", "{{formatDate(mcp:fetch.output.date, 'DD/MM')}}");

        Map<String, Integer> variablePages = Map.of("data", 0);

        // SQL path returns null (complex expression not suitable)
        when(runContextService.resolveVariablePaginated(
                anyString(), eq(RUN_ID), eq(TENANT), eq(EPOCH), eq(0), eq(50)))
            .thenReturn(null);

        // Falls back to standard
        when(runContextService.evaluateExpressionsForItemNarrowed(
                eq(RUN_ID), eq(TENANT), eq(EPOCH), eq(SPAWN), eq(ITEM_INDEX),
                anyMap(), anyInt(), anyInt()))
            .thenReturn(Map.of("data", "25/05"));

        Method method = InterfaceRenderService.class.getDeclaredMethod(
                "resolveVariablesWithPagination",
                Map.class, String.class, int.class, int.class, int.class, String.class, Map.class);
        method.setAccessible(true);

        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) method.invoke(
                interfaceRenderService, mappings, RUN_ID, EPOCH, SPAWN, ITEM_INDEX, TENANT, variablePages);

        assertThat(result.get("data")).isEqualTo("25/05");
        assertThat(result.containsKey("data__total")).isFalse();
    }

    @Test
    @DisplayName("Non-truncated variable has truncated=false marker")
    void nonTruncatedVariable() throws Exception {
        Map<String, String> mappings = Map.of("rows", "{{mcp:fetch.output.items}}");
        Map<String, Integer> variablePages = Map.of("rows", 0);

        // Array has only 10 items, pageSize is 50 - not truncated
        when(runContextService.resolveVariablePaginated(
                eq("{{mcp:fetch.output.items}}"), eq(RUN_ID), eq(TENANT), eq(EPOCH), eq(0), eq(50)))
            .thenReturn(new PaginatedVariable(List.of(Map.of("id", 1)), 10, 0, 50));

        Method method = InterfaceRenderService.class.getDeclaredMethod(
                "resolveVariablesWithPagination",
                Map.class, String.class, int.class, int.class, int.class, String.class, Map.class);
        method.setAccessible(true);

        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) method.invoke(
                interfaceRenderService, mappings, RUN_ID, EPOCH, SPAWN, ITEM_INDEX, TENANT, variablePages);

        assertThat(result.get("rows__truncated")).isEqualTo(false);
        assertThat(result.get("rows__count")).isEqualTo(1);
        assertThat(result.get("rows__totalPages")).isEqualTo(1);
        assertThat(result.get("rows__paginationSupported")).isEqualTo(true);
    }
}
