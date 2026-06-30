package com.apimarketplace.orchestrator.services.context;

import com.apimarketplace.common.storage.domain.StorageEntity;
import com.apimarketplace.common.storage.repository.StorageRepository;
import com.apimarketplace.orchestrator.services.TemplateEngine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Regression for the narrow-load path in
 * {@link RunContextService#evaluateExpressionsForItemNarrowed}.
 *
 * <p>Pre-fix prod behavior (2026-05-22 21:01 UTC): every interface render called
 * {@code loadRunContextForItem} → {@code findByRunIdAndEpoch} which materialised
 * every storage row of the epoch (including the 235 KB {@code table:load_processed}
 * JSONB blob). Multiplied by 10+ concurrent Redis listener threads → OOM.
 *
 * <p>Post-fix: extract step_keys referenced by the SpEL mappings, fetch ONLY those
 * via {@code findByRunIdAndEpochAndStepKeyInBounded} with a size predicate. Oversized
 * rows surface as {@code __oversized} markers instead of loading into heap.
 */
@ExtendWith(MockitoExtension.class)
class RunContextServiceNarrowLoadTest {

    private static final String RUN_ID = "run_narrow_001";
    private static final String TENANT = "tenant-1";
    private static final int EPOCH = 46;
    private static final int SPAWN = 0;
    private static final int ITEM_INDEX = 0;
    private static final int MAX_ROW_BYTES = 131_072;

    @Mock
    private StorageRepository storageRepository;

    @Mock
    private TemplateEngine templateEngine;

    private RunContextService service;

    @BeforeEach
    void setUp() {
        service = new RunContextService(storageRepository, templateEngine);
        // Tests in this class assert on the storage-fetch shape (which step_keys were
        // queried, and that the unbounded path was NOT called) - not on the SpEL output.
        // The clamp + SpEL eval correctness is covered by
        // InterfaceRenderServicePostSpelClampTest. Stub returns a sentinel so resolved
        // map is non-empty when needed.
        org.mockito.Mockito.lenient().when(templateEngine.evaluateTemplateWithMap(anyString(), any()))
            .thenReturn("ok");
    }

    @Test
    @DisplayName("loadRunContextForItemFetchesOnlyStepKeysReferencedByMappings: mapping {{mcp:fetch_emails.output.items}} narrows fetch to that single step_key")
    void loadRunContextForItemFetchesOnlyStepKeysReferencedByMappings() {
        when(storageRepository.findDistinctStepKeysByRunIdAndEpoch(RUN_ID, EPOCH, TENANT))
            .thenReturn(List.of("mcp:fetch_emails", "table:load_processed", "agent:classify"));
        when(storageRepository.findByRunIdAndEpochAndStepKeyInBounded(
                eq(RUN_ID), eq(EPOCH), any(), eq(MAX_ROW_BYTES), eq(TENANT)))
            .thenReturn(List.of(stubStorage("mcp:fetch_emails", Map.of("items", List.of("a", "b")))));

        Map<String, String> mappings = Map.of("emails", "{{mcp:fetch_emails.output.items}}");

        service.evaluateExpressionsForItemNarrowed(
            RUN_ID, TENANT, EPOCH, SPAWN, ITEM_INDEX, mappings, MAX_ROW_BYTES);

        // The unbounded path MUST NOT have been called - this is the load-bearing assertion
        // that captures the 2026-05-22 OOM-prevention contract.
        verify(storageRepository, never()).findByRunIdAndEpoch(anyString(), anyInt(), anyString());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<String>> stepKeysCaptor = ArgumentCaptor.forClass(Collection.class);
        verify(storageRepository).findByRunIdAndEpochAndStepKeyInBounded(
            eq(RUN_ID), eq(EPOCH), stepKeysCaptor.capture(), eq(MAX_ROW_BYTES), eq(TENANT));
        assertThat(stepKeysCaptor.getValue()).containsExactlyInAnyOrder("mcp:fetch_emails");
    }

    @Test
    @DisplayName("loadRunContextForItemNarrowedFetchesOnlyRequestedTriggerKeys: interface action extraction does not hydrate unrelated table rows")
    void loadRunContextForItemNarrowedFetchesOnlyRequestedTriggerKeys() {
        when(storageRepository.findDistinctStepKeysByRunIdAndEpoch(RUN_ID, EPOCH, TENANT))
            .thenReturn(List.of("trigger:submit_form", "table:load_processed", "mcp:fetch_emails"));
        when(storageRepository.findByRunIdAndEpochAndStepKeyInBounded(
                eq(RUN_ID), eq(EPOCH), any(), eq(MAX_ROW_BYTES), eq(TENANT)))
            .thenReturn(List.of(stubStorage("trigger:submit_form", Map.of("output", Map.of("email", "a@example.com")))));

        Map<String, Object> context = service.loadRunContextForItemNarrowed(
            RUN_ID, TENANT, EPOCH, SPAWN, ITEM_INDEX,
            Set.of("trigger:submit_form"), MAX_ROW_BYTES, 0);

        verify(storageRepository, never()).findByRunIdAndEpoch(anyString(), anyInt(), anyString());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<String>> stepKeysCaptor = ArgumentCaptor.forClass(Collection.class);
        verify(storageRepository).findByRunIdAndEpochAndStepKeyInBounded(
            eq(RUN_ID), eq(EPOCH), stepKeysCaptor.capture(), eq(MAX_ROW_BYTES), eq(TENANT));
        assertThat(stepKeysCaptor.getValue()).containsExactlyInAnyOrder("trigger:submit_form");
        assertThat(context).containsKey("trigger:submit_form");
        assertThat(context).doesNotContainKey("table:load_processed");
    }

    @Test
    @DisplayName("aliasMappingsResolveToFullStepKeysBeforeFetch: {{fetch_emails.output.items}} (alias form) maps to mcp:fetch_emails via distinct-step-keys lookup")
    void aliasMappingsResolveToFullStepKeysBeforeFetch() {
        when(storageRepository.findDistinctStepKeysByRunIdAndEpoch(RUN_ID, EPOCH, TENANT))
            .thenReturn(List.of("mcp:fetch_emails", "table:load_processed"));
        when(storageRepository.findByRunIdAndEpochAndStepKeyInBounded(
                eq(RUN_ID), eq(EPOCH), any(), eq(MAX_ROW_BYTES), eq(TENANT)))
            .thenReturn(List.of(stubStorage("mcp:fetch_emails", Map.of("items", List.of("a")))));

        Map<String, String> mappings = Map.of("emails", "{{fetch_emails.output.items}}");

        service.evaluateExpressionsForItemNarrowed(
            RUN_ID, TENANT, EPOCH, SPAWN, ITEM_INDEX, mappings, MAX_ROW_BYTES);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<String>> stepKeysCaptor = ArgumentCaptor.forClass(Collection.class);
        verify(storageRepository).findByRunIdAndEpochAndStepKeyInBounded(
            eq(RUN_ID), eq(EPOCH), stepKeysCaptor.capture(), eq(MAX_ROW_BYTES), eq(TENANT));
        assertThat(stepKeysCaptor.getValue()).containsExactlyInAnyOrder("mcp:fetch_emails");
    }

    @Test
    @DisplayName("oversizedStepKeysSurfaceAsMarkerNotFullRow: a 235 KB row is replaced by {__oversized:true,size_bytes:N} so the JSONB never enters heap")
    void oversizedStepKeysSurfaceAsMarkerNotFullRow() {
        when(storageRepository.findDistinctStepKeysByRunIdAndEpoch(RUN_ID, EPOCH, TENANT))
            .thenReturn(List.of("table:load_processed"));
        // Bounded fetch returns NOTHING (the row was filtered out by sizeBytes < maxRowBytes)
        when(storageRepository.findByRunIdAndEpochAndStepKeyInBounded(
                eq(RUN_ID), eq(EPOCH), any(), eq(MAX_ROW_BYTES), eq(TENANT)))
            .thenReturn(List.of());
        // The oversized-meta query surfaces the missing step_key with its real size
        List<Object[]> oversizedMeta = List.<Object[]>of(new Object[]{"table:load_processed", 261_000});
        when(storageRepository.findOversizedStepKeyMetaForEpoch(
                eq(RUN_ID), eq(EPOCH), any(), eq(MAX_ROW_BYTES), eq(TENANT)))
            .thenReturn(oversizedMeta);

        Map<String, String> mappings = Map.of("rows", "{{table:load_processed.output.items}}");

        service.evaluateExpressionsForItemNarrowed(
            RUN_ID, TENANT, EPOCH, SPAWN, ITEM_INDEX, mappings, MAX_ROW_BYTES);

        // The full-JSONB fetch path MUST NOT have been called
        verify(storageRepository, never()).findByRunIdAndEpoch(anyString(), anyInt(), anyString());
        verify(storageRepository).findOversizedStepKeyMetaForEpoch(
            eq(RUN_ID), eq(EPOCH), any(), eq(MAX_ROW_BYTES), eq(TENANT));
    }

    @Test
    @DisplayName("extractStepKeyTokensCapturesFullKeysAndAliases: parser handles both {{mcp:x.…}} and {{x.…}} forms")
    void extractStepKeyTokensCapturesFullKeysAndAliases() {
        List<String> expressions = List.of(
            "{{mcp:fetch_emails.output.items}}",
            "{{trigger:cron.output.cron}}",
            "{{ load_processed.output.rows }}",
            "static literal no template",
            "{{agent:classify.output.selected_category}}");

        Set<String> tokens = RunContextService.extractStepKeyTokens(expressions);

        assertThat(tokens).containsExactlyInAnyOrder(
            "mcp:fetch_emails", "trigger:cron", "load_processed", "agent:classify");
    }

    @Test
    @DisplayName("resolveTokensToFullStepKeysMatchesAliasViaBareAliasOfFullKey: alias 'fetch_emails' resolves to 'mcp:fetch_emails' when present in known set")
    void resolveTokensToFullStepKeysMatchesAliasViaBareAliasOfFullKey() {
        Set<String> tokens = Set.of("fetch_emails", "trigger:cron");
        List<String> known = List.of("mcp:fetch_emails", "table:load_processed", "trigger:cron");

        Set<String> result = RunContextService.resolveTokensToFullStepKeys(tokens, known);

        assertThat(result).containsExactlyInAnyOrder("mcp:fetch_emails", "trigger:cron");
    }

    @Test
    @DisplayName("spawn>0RerunFallsBackToUnboundedPathToPreserveLatestSpawnSemantics: rerun (spawn=1) bypasses narrow load - documents intentional gap to be closed in a follow-up")
    void spawnGreaterThanZeroFallsBackToUnboundedPath() {
        // Reruns are rare; the spawn-aware DISTINCT ON narrowed query is not yet wired.
        // The fallback must hit the existing unbounded findByRunIdAndEpochWithLatestSpawn
        // path (not findByRunIdAndEpoch) so latest-spawn semantics are preserved.
        when(storageRepository.findByRunIdAndEpochWithLatestSpawn(eq(RUN_ID), eq(EPOCH), eq(1), eq(TENANT)))
            .thenReturn(List.of());
        Map<String, String> mappings = Map.of("emails", "{{mcp:fetch_emails.output.items}}");

        service.evaluateExpressionsForItemNarrowed(
            RUN_ID, TENANT, EPOCH, /* spawn */ 1, ITEM_INDEX, mappings, MAX_ROW_BYTES);

        verify(storageRepository).findByRunIdAndEpochWithLatestSpawn(eq(RUN_ID), eq(EPOCH), eq(1), eq(TENANT));
        verify(storageRepository, never()).findByRunIdAndEpochAndStepKeyInBounded(
            anyString(), anyInt(), any(), anyInt(), anyString());
        verify(storageRepository, never()).findDistinctStepKeysByRunIdAndEpoch(anyString(), anyInt(), anyString());
    }

    @Test
    @DisplayName("emptyDistinctStepKeysFallsBackToSpELEvaluationSoLiteralsAndDefaultsStillResolve: early-replay run with no storage rows yet still surfaces literals (P0 regression fix)")
    void emptyDistinctStepKeysFallsBackToSpELEvaluation() {
        when(storageRepository.findDistinctStepKeysByRunIdAndEpoch(RUN_ID, EPOCH, TENANT))
            .thenReturn(List.of());
        // A mapping with a literal-only value (no {{…}}) should pass through as-is.
        Map<String, String> mappings = new java.util.LinkedHashMap<>();
        mappings.put("title", "Static Title");
        mappings.put("emails", "{{mcp:fetch_emails.output.items}}");

        Map<String, Object> resolved = service.evaluateExpressionsForItemNarrowed(
            RUN_ID, TENANT, EPOCH, SPAWN, ITEM_INDEX, mappings, MAX_ROW_BYTES);

        // Literal pass-through still works - would have been wiped to {} by the early-return
        // bug the audit caught.
        assertThat(resolved).containsEntry("title", "Static Title");
        verify(storageRepository, never()).findByRunIdAndEpochAndStepKeyInBounded(
            anyString(), anyInt(), any(), anyInt(), anyString());
    }

    @Test
    @DisplayName("emptyTargetStepKeysFallsBackToSpELEvaluation: every token references a step_key NOT in this epoch (typo/dropped node) - literals still resolve (P0 regression fix)")
    void emptyTargetStepKeysFallsBackToSpELEvaluation() {
        when(storageRepository.findDistinctStepKeysByRunIdAndEpoch(RUN_ID, EPOCH, TENANT))
            .thenReturn(List.of("mcp:other_node"));
        // Mapping references a step_key the epoch never wrote - targetStepKeys ends up empty.
        Map<String, String> mappings = new java.util.LinkedHashMap<>();
        mappings.put("label", "Literal Value");
        mappings.put("missing", "{{mcp:nonexistent_node.output.x}}");

        Map<String, Object> resolved = service.evaluateExpressionsForItemNarrowed(
            RUN_ID, TENANT, EPOCH, SPAWN, ITEM_INDEX, mappings, MAX_ROW_BYTES);

        assertThat(resolved).containsEntry("label", "Literal Value");
        verify(storageRepository, never()).findByRunIdAndEpochAndStepKeyInBounded(
            anyString(), anyInt(), any(), anyInt(), anyString());
    }

    @Test
    @DisplayName("resolveTokensPrefixDriftFallsBackToAliasMatchForFullKeyToken: plan declares {{trigger:start.…}} but storage row landed under {{mcp:start.…}} → alias match resolves both")
    void resolveTokensPrefixDriftFallsBackToAliasMatch() {
        // Token has prefix `trigger:start` but the storage row landed under `mcp:start`
        // (prefix drift documented in StepOutputsWriter.normalizeWrongPrefixes).
        Set<String> tokens = Set.of("trigger:start");
        List<String> known = List.of("mcp:start", "table:other");

        Set<String> result = RunContextService.resolveTokensToFullStepKeys(tokens, known);

        assertThat(result).containsExactlyInAnyOrder("mcp:start");
    }

    @Test
    @DisplayName("earlyClampTruncatesCollectionsBeforeSpELEvaluation: 8-arg overload with maxCollectionSize=5 caps nested lists so SpEL evaluates a truncated context")
    void earlyClampTruncatesCollectionsBeforeSpELEvaluation() {
        // Storage data structure: {"output": {"items": [200 elements], "count": 200}}
        // After buildPerItemContext: context["mcp:fetch_emails"] = {"output": {"items": [...], "count": 200}}
        List<Object> largeList = new java.util.ArrayList<>();
        for (int i = 0; i < 200; i++) largeList.add("item-" + i);
        Map<String, Object> data = Map.of("output", Map.of("items", largeList, "count", 200));

        when(storageRepository.findDistinctStepKeysByRunIdAndEpoch(RUN_ID, EPOCH, TENANT))
            .thenReturn(List.of("mcp:fetch_emails"));
        when(storageRepository.findByRunIdAndEpochAndStepKeyInBounded(
                eq(RUN_ID), eq(EPOCH), any(), eq(MAX_ROW_BYTES), eq(TENANT)))
            .thenReturn(List.of(stubStorage("mcp:fetch_emails", data)));

        // Capture the context map passed to the template engine to verify early-clamp worked
        @SuppressWarnings("unchecked")
        org.mockito.ArgumentCaptor<Map<String, Object>> contextCaptor =
            org.mockito.ArgumentCaptor.forClass(Map.class);
        when(templateEngine.evaluateTemplateWithMap(anyString(), contextCaptor.capture()))
            .thenReturn("clamped");

        Map<String, String> mappings = Map.of("emails", "{{mcp:fetch_emails.output.items}}");

        // 8-arg overload with maxCollectionSize=5
        service.evaluateExpressionsForItemNarrowed(
            RUN_ID, TENANT, EPOCH, SPAWN, ITEM_INDEX, mappings, MAX_ROW_BYTES, 5);

        // Verify the template engine received a context where the list was truncated to 5
        Map<String, Object> capturedContext = contextCaptor.getValue();
        @SuppressWarnings("unchecked")
        Map<String, Object> stepMap = (Map<String, Object>) capturedContext.get("mcp:fetch_emails");
        assertThat(stepMap).isNotNull();
        @SuppressWarnings("unchecked")
        Map<String, Object> outputMap = (Map<String, Object>) stepMap.get("output");
        assertThat(outputMap).isNotNull();
        Object items = outputMap.get("items");
        assertThat(items).isInstanceOf(java.util.List.class);
        assertThat((java.util.List<?>) items).hasSize(5);
        // Scalar value (count) should pass through untouched
        assertThat(outputMap.get("count")).isEqualTo(200);
    }

    @Test
    @DisplayName("earlyClampWithZeroMaxCollectionSizeDoesNotTruncate: 8-arg overload with maxCollectionSize=0 is a no-op (backward compat)")
    void earlyClampWithZeroMaxCollectionSizeDoesNotTruncate() {
        List<Object> largeList = new java.util.ArrayList<>();
        for (int i = 0; i < 100; i++) largeList.add("row-" + i);
        Map<String, Object> data = Map.of("output", Map.of("items", largeList));

        when(storageRepository.findDistinctStepKeysByRunIdAndEpoch(RUN_ID, EPOCH, TENANT))
            .thenReturn(List.of("mcp:step"));
        when(storageRepository.findByRunIdAndEpochAndStepKeyInBounded(
                eq(RUN_ID), eq(EPOCH), any(), eq(MAX_ROW_BYTES), eq(TENANT)))
            .thenReturn(List.of(stubStorage("mcp:step", data)));

        @SuppressWarnings("unchecked")
        org.mockito.ArgumentCaptor<Map<String, Object>> contextCaptor =
            org.mockito.ArgumentCaptor.forClass(Map.class);
        when(templateEngine.evaluateTemplateWithMap(anyString(), contextCaptor.capture()))
            .thenReturn("ok");

        Map<String, String> mappings = Map.of("items", "{{mcp:step.output.items}}");

        // maxCollectionSize=0 → no truncation
        service.evaluateExpressionsForItemNarrowed(
            RUN_ID, TENANT, EPOCH, SPAWN, ITEM_INDEX, mappings, MAX_ROW_BYTES, 0);

        Map<String, Object> capturedContext = contextCaptor.getValue();
        @SuppressWarnings("unchecked")
        Map<String, Object> stepMap = (Map<String, Object>) capturedContext.get("mcp:step");
        @SuppressWarnings("unchecked")
        Map<String, Object> outputMap = (Map<String, Object>) stepMap.get("output");
        Object items = outputMap.get("items");
        assertThat(items).isInstanceOf(java.util.List.class);
        assertThat((java.util.List<?>) items).hasSize(100); // NOT truncated
    }

    private static StorageEntity stubStorage(String stepKey, Map<String, Object> data) {
        StorageEntity e = new StorageEntity();
        e.setStepKey(stepKey);
        try {
            e.setData(new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(data));
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
        e.setItemIndex(0);
        return e;
    }
}
