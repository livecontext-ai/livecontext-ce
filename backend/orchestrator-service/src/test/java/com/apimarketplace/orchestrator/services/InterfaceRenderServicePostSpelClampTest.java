package com.apimarketplace.orchestrator.services;

import com.apimarketplace.orchestrator.config.OrchestratorLimitsConfig;
import com.apimarketplace.orchestrator.services.context.RunContextService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * Regression for the post-SpEL clamp in
 * {@link InterfaceRenderService#resolveVariablesOptimized}.
 *
 * <p>Pre-fix prod scenario (2026-05-22 21:01 UTC): {@code resolveVariablesOptimized}
 * returned {@code var rows = ArrayList(size=485)} unchanged. With 10+ Redis listener
 * threads concurrently calling this path during state reconstruction, the cumulative
 * heap pressure from the materialized 485-element lists + per-item interpolated HTML
 * exhausted the 1.5 GB Xmx - humongous regions stuck at 512, JVM exited via
 * {@code +ExitOnOutOfMemoryError}.
 *
 * <p>Post-fix: per-variable {@code Collection} clamp to {@code maxRowsPerVariable} +
 * cumulative {@code maxResolvedVariableBytes} short-circuit.
 */
@ExtendWith(MockitoExtension.class)
class InterfaceRenderServicePostSpelClampTest {

    private static final String RUN_ID = "run_test_001";
    private static final String TENANT = "tenant-1";

    @Mock
    private RunContextService runContextService;

    @InjectMocks
    private InterfaceRenderService service;

    private OrchestratorLimitsConfig limits;

    @BeforeEach
    void setUp() {
        limits = new OrchestratorLimitsConfig();
        ReflectionTestUtils.setField(service, "renderLimits", limits);
    }

    @Test
    @DisplayName("renderClampsResolvedListVariableToMaxRowsPerVariable: 485-row collection (prod scenario) is truncated to maxRowsPerVariable with __truncated marker")
    void renderClampsResolvedListVariableToMaxRowsPerVariable() {
        limits.setMaxRowsPerVariable(50);
        limits.setMaxResolvedVariableBytes(Integer.MAX_VALUE);
        limits.setMaxStorageRowBytes(Integer.MAX_VALUE);

        List<Map<String, Object>> rows = new ArrayList<>();
        for (int i = 0; i < 485; i++) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", i);
            row.put("subject", "Email " + i);
            rows.add(row);
        }

        Map<String, Object> resolvedFromRunContext = new LinkedHashMap<>();
        resolvedFromRunContext.put("rows", rows);
        resolvedFromRunContext.put("total_count", 485);

        when(runContextService.evaluateExpressionsForItemNarrowed(
                eq(RUN_ID), eq(TENANT), anyInt(), anyInt(), anyInt(), org.mockito.ArgumentMatchers.anyMap(), anyInt(), anyInt()))
            .thenReturn(resolvedFromRunContext);

        Map<String, String> mappings = Map.of(
            "rows", "{{table:load_processed.output.items}}",
            "total_count", "{{table:load_processed.output.item_count}}");

        Map<String, Object> result = ReflectionTestUtils.invokeMethod(
            service, "resolveVariablesOptimized", mappings, RUN_ID, 1, 0, 0, TENANT);

        assertThat(result).isNotNull();
        assertThat(result.get("rows")).isInstanceOf(java.util.Collection.class);
        java.util.Collection<?> truncatedRows = (java.util.Collection<?>) result.get("rows");
        assertThat(truncatedRows).hasSize(50);
        assertThat(result.get("rows__total")).isEqualTo(485);
        assertThat(result.get("rows__page")).isEqualTo(0);
        assertThat(result.get("rows__pageSize")).isEqualTo(50);
        assertThat(result.get("rows__count")).isEqualTo(50);
        assertThat(result.get("rows__totalPages")).isEqualTo(10);
        assertThat(result.get("rows__paginationSupported")).isEqualTo(false);
        assertThat(result.get("rows__truncated")).isEqualTo(true);
        // Scalar variable passes through untouched
        assertThat(result.get("total_count")).isEqualTo(485);
    }

    @Test
    @DisplayName("renderShortCircuitsWhenCumulativeResolvedBytesExceedBudget: byte cap stops mid-resolve and stamps top-level truncation flag")
    void renderShortCircuitsWhenCumulativeResolvedBytesExceedBudget() {
        limits.setMaxRowsPerVariable(10_000);
        // Config setter clamps maxResolvedVariableBytes to a min of 1024 - pick the lowest
        // allowed value and size the test data so iter 1's collection estimate exceeds it.
        // Post-audit estimator recurses one level: each element contributes
        // {@code String.length × 2} bytes. 200 elements × ~20 B (10-char strings × 2 UTF-16)
        // ≈ 4 064 B per list → iter 1 alone trips the 1 024 budget; iter 2 must break at
        // the top-of-loop guard without inserting {@code other_rows}, exercising the
        // "already-added-stays, subsequent-dropped" contract from the clamp javadoc.
        limits.setMaxResolvedVariableBytes(1024);
        limits.setMaxStorageRowBytes(Integer.MAX_VALUE);

        List<Object> firstList = new ArrayList<>();
        for (int i = 0; i < 200; i++) firstList.add("rowelement" + i);   // 10+ chars
        List<Object> secondList = new ArrayList<>();
        for (int i = 0; i < 200; i++) secondList.add("otherelmt" + i);   // 10+ chars

        Map<String, Object> resolvedFromRunContext = new LinkedHashMap<>();
        resolvedFromRunContext.put("rows", firstList);
        resolvedFromRunContext.put("other_rows", secondList);

        when(runContextService.evaluateExpressionsForItemNarrowed(
                eq(RUN_ID), eq(TENANT), anyInt(), anyInt(), anyInt(), org.mockito.ArgumentMatchers.anyMap(), anyInt(), anyInt()))
            .thenReturn(resolvedFromRunContext);

        Map<String, String> mappings = Map.of(
            "rows", "{{table:a.output.items}}",
            "other_rows", "{{table:b.output.items}}");

        Map<String, Object> result = ReflectionTestUtils.invokeMethod(
            service, "resolveVariablesOptimized", mappings, RUN_ID, 1, 0, 0, TENANT);

        assertThat(result).isNotNull();
        assertThat(result).containsKey("rows");
        assertThat(result).doesNotContainKey("other_rows");
        assertThat(result.get("__resolved_variables_truncated")).isEqualTo(true);
        assertThat(result.get("__resolved_variables_bytes")).isNotNull();
    }

    @Test
    @DisplayName("onExceedFailRaisesIllegalStateExceptionInsteadOfSilentTruncation: row-cap violation surfaces as error with onExceed=fail")
    void onExceedFailRaisesIllegalStateExceptionInsteadOfSilentTruncation() {
        limits.setMaxRowsPerVariable(10);
        limits.setOnExceed(OrchestratorLimitsConfig.OnExceed.fail);

        List<Object> rows = new ArrayList<>();
        for (int i = 0; i < 20; i++) rows.add("row-" + i);

        Map<String, Object> resolvedFromRunContext = new LinkedHashMap<>();
        resolvedFromRunContext.put("rows", rows);

        when(runContextService.evaluateExpressionsForItemNarrowed(
                eq(RUN_ID), eq(TENANT), anyInt(), anyInt(), anyInt(), org.mockito.ArgumentMatchers.anyMap(), anyInt(), anyInt()))
            .thenReturn(resolvedFromRunContext);

        Map<String, String> mappings = Map.of("rows", "{{table:a.output.items}}");

        // ReflectionTestUtils may wrap the thrown IllegalStateException - assert on the
        // message text rather than the exact exception type so a refactor of the invoke
        // shim does not break this test. The message string is the invariant.
        assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(
                service, "resolveVariablesOptimized", mappings, RUN_ID, 1, 0, 0, TENANT))
            .hasMessageContaining("maxRowsPerVariable=10");
    }
}
