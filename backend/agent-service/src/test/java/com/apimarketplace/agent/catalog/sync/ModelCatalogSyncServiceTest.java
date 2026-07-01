package com.apimarketplace.agent.catalog.sync;

import com.apimarketplace.agent.catalog.bundle.CatalogMergeService;
import com.apimarketplace.agent.catalog.bundle.MergeOptions;
import com.apimarketplace.agent.domain.ModelCatalogSyncLogEntity;
import com.apimarketplace.agent.domain.ModelConfigOverrideEntity;
import com.apimarketplace.agent.repository.ModelCatalogSyncLogRepository;
import com.apimarketplace.agent.repository.ModelConfigOverrideRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit coverage for {@link ModelCatalogSyncService}. Every assertion here
 * targets a behaviour that has previously regressed - not a speculative path.
 *
 * <p>Scope map:
 * <ul>
 *   <li>sync()/apply branches: happy-path dry-run, happy-path apply, merge
 *       failure downgrading to {@code APPLY_ERROR}.</li>
 *   <li>Error envelopes: both feeds failing, individual schema errors.</li>
 *   <li>Count-floor guard: per-feed baselines (V3.1 fix #2), override switch,
 *       first-ever baseline skip.</li>
 *   <li>Price-sanity guard: flag + non-applied vs. overridden + applied.</li>
 *   <li>Diff classification: rowEquals across every V125-era field (V3.1 fix
 *       #1), excluded providers, bridge-derived rows round-trip.</li>
 *   <li>Sync-log plumbing: delegated to {@link ModelCatalogSyncLogWriter} so
 *       a merge failure doesn't poison the log insert (REQUIRES_NEW contract).</li>
 * </ul>
 *
 * <p>The service is spied so {@link ModelCatalogSyncService#fetchLiteLlm()}
 * and {@link ModelCatalogSyncService#fetchOpenRouter()} can be stubbed without
 * mocking {@link RestTemplate}'s internals - the fetch layer is covered by
 * parser tests and an integration-level feed-signature check, so repeating
 * bytes-level assertions here would be noise.
 */
@DisplayName("ModelCatalogSyncService - sync plan + guards + apply orchestration")
class ModelCatalogSyncServiceTest {

    private LiteLlmFeedParser liteLlmParser;
    private OpenRouterFeedParser openRouterParser;
    private BridgeModelDeriver bridgeModelDeriver;
    private CatalogMergeService mergeService;
    private ModelConfigOverrideRepository modelRepo;
    private ModelCatalogSyncLogRepository syncLogRepo;
    private ModelCatalogSyncLogWriter syncLogWriter;
    private RestTemplate restTemplate;

    private ModelCatalogSyncService syncService;

    @BeforeEach
    void setUp() {
        liteLlmParser = mock(LiteLlmFeedParser.class);
        openRouterParser = mock(OpenRouterFeedParser.class);
        bridgeModelDeriver = mock(BridgeModelDeriver.class);
        mergeService = mock(CatalogMergeService.class);
        modelRepo = mock(ModelConfigOverrideRepository.class);
        syncLogRepo = mock(ModelCatalogSyncLogRepository.class);
        syncLogWriter = mock(ModelCatalogSyncLogWriter.class);
        restTemplate = mock(RestTemplate.class);

        // mergeRunner delegates straight to mergeService in unit tests so the
        // REQUIRES_NEW wrapper doesn't obscure the assertions below.
        CatalogSyncMergeRunner mergeRunner = mock(CatalogSyncMergeRunner.class);
        when(mergeRunner.merge(any(), any()))
                .thenAnswer(inv -> mergeService.merge(inv.getArgument(0), inv.getArgument(1)));

        syncService = spy(new ModelCatalogSyncService(
                liteLlmParser, openRouterParser, bridgeModelDeriver, mergeService, mergeRunner,
                modelRepo, syncLogRepo, syncLogWriter, restTemplate));

        // Default: both feeds reachable. Individual tests override as needed.
        doReturn(feedOk("litellm-bytes", "sha-litellm")).when(syncService).fetchLiteLlm();
        doReturn(feedOk("openrouter-bytes", "sha-openrouter")).when(syncService).fetchOpenRouter();

        // Bridge deriver defaults to "no bridges" - tests that care stub it.
        when(bridgeModelDeriver.derive(any())).thenReturn(List.of());

        // Log writer echoes back a populated entity so the service can extract
        // an id to return in the SyncResult - matches production behaviour
        // where the Flyway-generated identity column is filled on save().
        when(syncLogWriter.write(anyString(), any(), anyInt(), any(), any(), anyBoolean(),
                any(), any(), any(), anyInt(), anyInt(), anyInt(), anyInt(), any(), any()))
                .thenAnswer(inv -> {
                    ModelCatalogSyncLogEntity e = new ModelCatalogSyncLogEntity();
                    e.setId(42L);
                    e.setSource(inv.getArgument(0));
                    e.setOutcome(inv.getArgument(6));
                    return e;
                });

        // Empty DB by default - tests that want existing rows stub this.
        when(modelRepo.findAllByOrderByRankingAsc()).thenReturn(List.of());
    }

    // ────────────────────────────────────────────────────────────────────────
    // sync() orchestration
    // ────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Dry-run OK: parses both feeds, classifies diff, writes log, does NOT call mergeService")
    void dryRunClassifiesAndLogsWithoutApplying() {
        // One existing row, one new incoming row → added=1, unchanged=1.
        ModelConfigOverrideEntity existing = entity("openai", "gpt-5.4",
                new BigDecimal("2.500000"), new BigDecimal("15.000000"));
        when(modelRepo.findAllByOrderByRankingAsc()).thenReturn(List.of(existing));

        when(liteLlmParser.parse(any(), any(), any())).thenReturn(
                LiteLlmFeedParser.ParseResult.success(List.of(
                        feedRow("openai", "gpt-5.4", "2.500000", "15.000000"),
                        feedRow("anthropic", "claude-opus-4-7", "5.000000", "25.000000")
                ), 0, 0, 0, 0, 0));
        when(openRouterParser.parse(any(), any(), any())).thenReturn(
                OpenRouterFeedParser.ParseResult.success(List.of(), 0, 0, 0, 0));

        var result = syncService.sync(ModelCatalogSyncService.SyncRequest.dryRun("tester"));

        assertThat(result.applied()).isFalse();
        assertThat(result.plan().added()).hasSize(1);
        assertThat(result.plan().unchanged()).isEqualTo(1);
        assertThat(result.plan().guardFailures()).isEmpty();
        assertThat(result.syncLogId()).isEqualTo(42L);
        verify(mergeService, never()).merge(any(), any());

        ArgumentCaptor<ModelCatalogSyncLogEntity.Outcome> outcome =
                ArgumentCaptor.forClass(ModelCatalogSyncLogEntity.Outcome.class);
        verify(syncLogWriter).write(eq("both"), any(), anyInt(), any(),
                eq("tester"), eq(true), outcome.capture(), any(), any(),
                anyInt(), anyInt(), anyInt(), anyInt(), any(), any());
        assertThat(outcome.getValue()).isEqualTo(ModelCatalogSyncLogEntity.Outcome.OK);
    }

    @Test
    @DisplayName("Apply path: invokes mergeService.merge with MergeOptions.forSync + logs inserted/updated/deprecated counts")
    void applyPathInvokesMergeWithSyncOptions() {
        when(liteLlmParser.parse(any(), any(), any())).thenReturn(
                LiteLlmFeedParser.ParseResult.success(List.of(
                        feedRow("openai", "gpt-5.4", "2.500000", "15.000000")
                ), 0, 0, 0, 0, 0));
        when(openRouterParser.parse(any(), any(), any())).thenReturn(
                OpenRouterFeedParser.ParseResult.success(List.of(), 0, 0, 0, 0));

        when(mergeService.merge(any(), any()))
                .thenReturn(new CatalogMergeService.MergeResult(3, 2, 1, 0, 0, 5));

        // First-ever-per-feed baselines absent → no count-floor trip.
        when(syncLogRepo.findFirstByOutcomeAndDryRunAndLiteLlmCountIsNotNullOrderByCreatedAtDesc(
                any(), any())).thenReturn(Optional.empty());
        when(syncLogRepo.findFirstByOutcomeAndDryRunAndOpenRouterCountIsNotNullOrderByCreatedAtDesc(
                any(), any())).thenReturn(Optional.empty());

        var result = syncService.sync(
                ModelCatalogSyncService.SyncRequest.apply("ops", Set.of()));

        ArgumentCaptor<MergeOptions> opts = ArgumentCaptor.forClass(MergeOptions.class);
        verify(mergeService).merge(any(), opts.capture());
        // forSync() is the ONLY opts factory the sync path may use - if a
        // future refactor swaps it for forBundle() / a custom builder, bridge
        // rows start going through the wrong retention semantics silently.
        assertThat(opts.getValue()).isEqualTo(MergeOptions.forSync());

        assertThat(result.applied()).isTrue();
        assertThat(result.inserted()).isEqualTo(3);
        assertThat(result.updatedCount()).isEqualTo(2);
        assertThat(result.deprecated()).isEqualTo(1);
    }

    @Test
    @DisplayName("Both feeds fail → FETCH_ERROR log, mergeService never called, syncLogId still returned")
    void bothFeedsFailingProducesFetchErrorLog() {
        doReturn(feedErr("LiteLLM 503")).when(syncService).fetchLiteLlm();
        doReturn(feedErr("OpenRouter 502")).when(syncService).fetchOpenRouter();

        var result = syncService.sync(ModelCatalogSyncService.SyncRequest.apply("ops", Set.of()));

        assertThat(result.applied()).isFalse();
        verify(mergeService, never()).merge(any(), any());
        verify(syncLogWriter).write(eq("both"), any(), eq(0), any(), eq("ops"), eq(false),
                eq(ModelCatalogSyncLogEntity.Outcome.FETCH_ERROR), any(), any(),
                anyInt(), anyInt(), anyInt(), anyInt(), eq(null), eq(null));
    }

    @Test
    @DisplayName("LiteLLM schema parse error → SCHEMA_ERROR log, apply skipped")
    void liteLlmSchemaErrorAborts() {
        when(liteLlmParser.parse(any(), any(), any())).thenReturn(
                LiteLlmFeedParser.ParseResult.failure("unexpected root type"));

        var result = syncService.sync(ModelCatalogSyncService.SyncRequest.apply("ops", Set.of()));

        assertThat(result.applied()).isFalse();
        verify(mergeService, never()).merge(any(), any());
        verify(syncLogWriter).write(eq("litellm"), any(), anyInt(), any(), any(), anyBoolean(),
                eq(ModelCatalogSyncLogEntity.Outcome.SCHEMA_ERROR), eq("unexpected root type"),
                any(), anyInt(), anyInt(), anyInt(), anyInt(), any(), any());
    }

    // ────────────────────────────────────────────────────────────────────────
    // Count-floor guard - V3.1 fix #2
    // ────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Count-floor: LiteLLM drops below 80% of last per-feed baseline → ABORTED_GUARD, merge skipped")
    void countFloorBreachAbortsApply() {
        // Baseline: a prior OK non-dry-run run where LiteLLM returned 100.
        ModelCatalogSyncLogEntity baseline = new ModelCatalogSyncLogEntity();
        baseline.setLiteLlmCount(100);
        when(syncLogRepo.findFirstByOutcomeAndDryRunAndLiteLlmCountIsNotNullOrderByCreatedAtDesc(
                eq(ModelCatalogSyncLogEntity.Outcome.OK), eq(Boolean.FALSE)))
                .thenReturn(Optional.of(baseline));
        when(syncLogRepo.findFirstByOutcomeAndDryRunAndOpenRouterCountIsNotNullOrderByCreatedAtDesc(
                any(), any())).thenReturn(Optional.empty());

        // Current LiteLLM: 50 rows (50 / 100 = 0.5, floor = 0.8).
        List<Map<String, Object>> rows = new java.util.ArrayList<>();
        for (int i = 0; i < 50; i++) {
            rows.add(feedRow("openai", "model-" + i, "1.0", "2.0"));
        }
        when(liteLlmParser.parse(any(), any(), any())).thenReturn(
                LiteLlmFeedParser.ParseResult.success(rows, 0, 0, 0, 0, 0));
        when(openRouterParser.parse(any(), any(), any())).thenReturn(
                OpenRouterFeedParser.ParseResult.success(List.of(), 0, 0, 0, 0));

        var result = syncService.sync(ModelCatalogSyncService.SyncRequest.apply("ops", Set.of()));

        assertThat(result.applied()).isFalse();
        assertThat(result.plan().guardFailures()).anySatisfy(g ->
                assertThat(g.guard()).isEqualTo(ModelCatalogSyncService.GUARD_COUNT_FLOOR));
        verify(mergeService, never()).merge(any(), any());
        verify(syncLogWriter).write(any(), any(), anyInt(), any(), any(), anyBoolean(),
                eq(ModelCatalogSyncLogEntity.Outcome.ABORTED_GUARD), any(),
                any(), anyInt(), anyInt(), anyInt(), anyInt(), any(), any());
    }

    @Test
    @DisplayName("Count-floor baselines are per-feed: each feed compares against its own last non-null count")
    void countFloorBaselinesArePerFeed() {
        // LiteLLM baseline 200, OpenRouter baseline 60. Current LiteLLM 180
        // (90% of 200, passes), current OpenRouter 10 (17% of 60, breaches).
        // The per-feed lookup must isolate which baseline is used for which
        // feed - collapsing to a single baseline (e.g. the combined total)
        // would either mask the OR breach or spuriously trip LiteLLM.
        ModelCatalogSyncLogEntity litellmBaseline = new ModelCatalogSyncLogEntity();
        litellmBaseline.setLiteLlmCount(200);
        ModelCatalogSyncLogEntity openRouterBaseline = new ModelCatalogSyncLogEntity();
        openRouterBaseline.setOpenRouterCount(60);
        when(syncLogRepo.findFirstByOutcomeAndDryRunAndLiteLlmCountIsNotNullOrderByCreatedAtDesc(
                any(), any())).thenReturn(Optional.of(litellmBaseline));
        when(syncLogRepo.findFirstByOutcomeAndDryRunAndOpenRouterCountIsNotNullOrderByCreatedAtDesc(
                any(), any())).thenReturn(Optional.of(openRouterBaseline));

        List<Map<String, Object>> liteRows = new java.util.ArrayList<>();
        for (int i = 0; i < 180; i++) liteRows.add(feedRow("openai", "l-" + i, "1.0", "2.0"));
        List<Map<String, Object>> orRows = new java.util.ArrayList<>();
        for (int i = 0; i < 10; i++) orRows.add(feedRow("mistral", "or-" + i, "1.0", "2.0"));
        when(liteLlmParser.parse(any(), any(), any())).thenReturn(
                LiteLlmFeedParser.ParseResult.success(liteRows, 0, 0, 0, 0, 0));
        when(openRouterParser.parse(any(), any(), any())).thenReturn(
                OpenRouterFeedParser.ParseResult.success(orRows, 0, 0, 0, 0));

        var result = syncService.sync(ModelCatalogSyncService.SyncRequest.apply("ops", Set.of()));

        assertThat(result.plan().guardFailures()).hasSize(1);
        var failure = result.plan().guardFailures().get(0);
        assertThat(failure.guard()).isEqualTo(ModelCatalogSyncService.GUARD_COUNT_FLOOR);
        assertThat(failure.data()).containsEntry("feed", "openrouter");
        assertThat(failure.data()).containsEntry("baseline", 60);
    }

    @Test
    @DisplayName("Count-floor override: overrideGuards=count-floor lets an otherwise-breaching feed apply")
    void countFloorOverrideSkipsGuard() {
        ModelCatalogSyncLogEntity baseline = new ModelCatalogSyncLogEntity();
        baseline.setLiteLlmCount(100);
        when(syncLogRepo.findFirstByOutcomeAndDryRunAndLiteLlmCountIsNotNullOrderByCreatedAtDesc(
                any(), any())).thenReturn(Optional.of(baseline));

        when(liteLlmParser.parse(any(), any(), any())).thenReturn(
                LiteLlmFeedParser.ParseResult.success(List.of(
                        feedRow("openai", "gpt-5.4", "2.5", "15.0")
                ), 0, 0, 0, 0, 0));
        when(openRouterParser.parse(any(), any(), any())).thenReturn(
                OpenRouterFeedParser.ParseResult.success(List.of(), 0, 0, 0, 0));
        when(mergeService.merge(any(), any()))
                .thenReturn(new CatalogMergeService.MergeResult(1, 0, 0, 0, 0, 1));

        var result = syncService.sync(ModelCatalogSyncService.SyncRequest.apply(
                "ops", Set.of(ModelCatalogSyncService.GUARD_COUNT_FLOOR)));

        assertThat(result.applied()).isTrue();
        assertThat(result.plan().guardFailures()).isEmpty();
        verify(mergeService).merge(any(), any());
    }

    @Test
    @DisplayName("Count-floor first-run: no baseline in DB → guard is a no-op, apply proceeds")
    void countFloorFirstRunSkipsGuard() {
        when(syncLogRepo.findFirstByOutcomeAndDryRunAndLiteLlmCountIsNotNullOrderByCreatedAtDesc(
                any(), any())).thenReturn(Optional.empty());
        when(syncLogRepo.findFirstByOutcomeAndDryRunAndOpenRouterCountIsNotNullOrderByCreatedAtDesc(
                any(), any())).thenReturn(Optional.empty());

        when(liteLlmParser.parse(any(), any(), any())).thenReturn(
                LiteLlmFeedParser.ParseResult.success(List.of(
                        feedRow("openai", "gpt-5.4", "2.5", "15.0")
                ), 0, 0, 0, 0, 0));
        when(openRouterParser.parse(any(), any(), any())).thenReturn(
                OpenRouterFeedParser.ParseResult.success(List.of(), 0, 0, 0, 0));
        when(mergeService.merge(any(), any()))
                .thenReturn(new CatalogMergeService.MergeResult(1, 0, 0, 0, 0, 1));

        var result = syncService.sync(ModelCatalogSyncService.SyncRequest.apply("ops", Set.of()));

        assertThat(result.applied()).isTrue();
        assertThat(result.plan().guardFailures()).isEmpty();
    }

    // ────────────────────────────────────────────────────────────────────────
    // Price-sanity guard
    // ────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Price-sanity: >50% drift flags the row, trips ABORTED_GUARD, row excluded from apply")
    void priceSanityBlocksApplyByDefault() {
        ModelConfigOverrideEntity existing = entity("openai", "gpt-5.4",
                new BigDecimal("2.000000"), new BigDecimal("15.000000"));
        when(modelRepo.findAllByOrderByRankingAsc()).thenReturn(List.of(existing));

        // New price 10 (quintupled - huge drift).
        when(liteLlmParser.parse(any(), any(), any())).thenReturn(
                LiteLlmFeedParser.ParseResult.success(List.of(
                        feedRow("openai", "gpt-5.4", "10.000000", "15.000000")
                ), 0, 0, 0, 0, 0));
        when(openRouterParser.parse(any(), any(), any())).thenReturn(
                OpenRouterFeedParser.ParseResult.success(List.of(), 0, 0, 0, 0));

        var result = syncService.sync(ModelCatalogSyncService.SyncRequest.apply("ops", Set.of()));

        assertThat(result.applied()).isFalse();
        assertThat(result.plan().flagged()).hasSize(1);
        assertThat(result.plan().flagged().get(0).modelId()).isEqualTo("gpt-5.4");
        assertThat(result.plan().guardFailures()).anyMatch(g ->
                ModelCatalogSyncService.GUARD_PRICE_SANITY.equals(g.guard()));
        verify(mergeService, never()).merge(any(), any());
    }

    @Test
    @DisplayName("Price-sanity override: flagged row passes through to merge, guardFailures stays empty, flagged list still populated for the audit trail")
    void priceSanityOverridePushesFlaggedRowThrough() {
        // One existing row, one incoming drift + one clean incoming.
        ModelConfigOverrideEntity existing = entity("openai", "gpt-5.4",
                new BigDecimal("2.000000"), new BigDecimal("15.000000"));
        when(modelRepo.findAllByOrderByRankingAsc()).thenReturn(List.of(existing));

        when(liteLlmParser.parse(any(), any(), any())).thenReturn(
                LiteLlmFeedParser.ParseResult.success(List.of(
                        feedRow("openai", "gpt-5.4", "10.000000", "15.000000"),       // flagged (5x)
                        feedRow("anthropic", "claude-opus-4-7", "5.0", "25.0")        // clean (new)
                ), 0, 0, 0, 0, 0));
        when(openRouterParser.parse(any(), any(), any())).thenReturn(
                OpenRouterFeedParser.ParseResult.success(List.of(), 0, 0, 0, 0));
        when(mergeService.merge(any(), any()))
                .thenReturn(new CatalogMergeService.MergeResult(1, 1, 0, 0, 0, 2));

        var result = syncService.sync(ModelCatalogSyncService.SyncRequest.apply(
                "ops", Set.of(ModelCatalogSyncService.GUARD_PRICE_SANITY)));

        assertThat(result.applied()).isTrue();
        // Override contract: operator acknowledged the flag, so the flagged
        // row is included in the merge payload (both rows flow through).
        // flaggedKeys is only populated when the override is ABSENT - proving
        // here that admin acknowledgement takes effect at the payload-build
        // step, not merely at the guard-fail-to-412 step.
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Map<String, Object>>> payload = ArgumentCaptor.forClass(List.class);
        verify(mergeService).merge(payload.capture(), any());
        assertThat(payload.getValue()).extracting(m -> m.get("modelId"))
                .containsExactlyInAnyOrder("gpt-5.4", "claude-opus-4-7");

        // Even when overridden, the plan.flagged() list stays populated so
        // the sync-log row keeps an auditable record of "what was overridden".
        assertThat(result.plan().flagged()).hasSize(1);
        assertThat(result.plan().guardFailures()).isEmpty();
    }

    // ────────────────────────────────────────────────────────────────────────
    // Diff classification + rowEquals coverage - V3.1 fix #1
    // ────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("rowEquals compares every V125 field: a change to any one (here: contextWindow) flips unchanged → updated")
    void rowEqualsCoversContextWindow() {
        // Existing row has every applyFields-written field populated.
        ModelConfigOverrideEntity existing = fullyPopulatedEntity();
        when(modelRepo.findAllByOrderByRankingAsc()).thenReturn(List.of(existing));

        // Incoming row matches every field except contextWindow (400000 → 500000).
        Map<String, Object> incoming = fullyPopulatedFeedRow();
        incoming.put("contextWindow", 500_000);

        when(liteLlmParser.parse(any(), any(), any())).thenReturn(
                LiteLlmFeedParser.ParseResult.success(List.of(incoming), 0, 0, 0, 0, 0));
        when(openRouterParser.parse(any(), any(), any())).thenReturn(
                OpenRouterFeedParser.ParseResult.success(List.of(), 0, 0, 0, 0));

        var result = syncService.sync(ModelCatalogSyncService.SyncRequest.dryRun("tester"));

        assertThat(result.plan().updated()).hasSize(1);
        assertThat(result.plan().unchanged()).isZero();
    }

    @Test
    @DisplayName("rowEquals matches exactly: identical V125 feed row stays in unchanged bucket, not updated")
    void rowEqualsUnchangedWhenEveryFieldMatches() {
        ModelConfigOverrideEntity existing = fullyPopulatedEntity();
        when(modelRepo.findAllByOrderByRankingAsc()).thenReturn(List.of(existing));
        when(liteLlmParser.parse(any(), any(), any())).thenReturn(
                LiteLlmFeedParser.ParseResult.success(
                        List.of(fullyPopulatedFeedRow()), 0, 0, 0, 0, 0));
        when(openRouterParser.parse(any(), any(), any())).thenReturn(
                OpenRouterFeedParser.ParseResult.success(List.of(), 0, 0, 0, 0));

        var result = syncService.sync(ModelCatalogSyncService.SyncRequest.dryRun("tester"));

        assertThat(result.plan().unchanged()).isEqualTo(1);
        assertThat(result.plan().updated()).isEmpty();
        assertThat(result.plan().added()).isEmpty();
    }

    @Test
    @DisplayName("Excluded providers (bridges + zai) are stripped from feed before diff")
    void excludedProvidersAreFiltered() {
        // Parsers already exclude these in practice, but the service re-filters
        // as belt-and-braces. Test that branch by stubbing parsers to include
        // an excluded provider directly.
        when(liteLlmParser.parse(any(), any(), any())).thenReturn(
                LiteLlmFeedParser.ParseResult.success(List.of(
                        feedRow("openai", "gpt-5.4", "2.5", "15.0"),
                        feedRow("claude-code", "claude-opus-4-7", "5.0", "25.0"),
                        feedRow("zai", "glm-5-turbo", "0.1", "0.3")
                ), 0, 0, 0, 0, 0));
        when(openRouterParser.parse(any(), any(), any())).thenReturn(
                OpenRouterFeedParser.ParseResult.success(List.of(), 0, 0, 0, 0));
        when(mergeService.merge(any(), any()))
                .thenReturn(new CatalogMergeService.MergeResult(1, 0, 0, 0, 0, 1));

        syncService.sync(ModelCatalogSyncService.SyncRequest.apply("ops", Set.of()));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Map<String, Object>>> payload = ArgumentCaptor.forClass(List.class);
        verify(mergeService).merge(payload.capture(), any());
        assertThat(payload.getValue()).extracting(m -> m.get("provider"))
                .containsExactly("openai");
    }

    @Test
    @DisplayName("A feed row with a null provider does not crash the excluded-provider filter (null-hostile Set.of guard)")
    void nullProviderRowDoesNotCrashExcludedFilter() {
        // EXCLUDED_PROVIDERS is an immutable Set.of(...) whose contains(null)
        // throws NPE. Parsers never emit a null provider, but the service
        // re-filters as belt-and-braces, so a null must be treated as
        // "not excluded" rather than aborting the whole sync. A HashMap-backed
        // row is required because Map.of rejects null values.
        Map<String, Object> nullProviderRow = new java.util.HashMap<>();
        nullProviderRow.put("provider", null);
        nullProviderRow.put("modelId", "orphan");
        nullProviderRow.put("priceInput", "1.0");
        nullProviderRow.put("priceOutput", "2.0");

        when(liteLlmParser.parse(any(), any(), any())).thenReturn(
                LiteLlmFeedParser.ParseResult.success(List.of(
                        feedRow("openai", "gpt-5.4", "2.5", "15.0"),
                        nullProviderRow
                ), 0, 0, 0, 0, 0));
        when(openRouterParser.parse(any(), any(), any())).thenReturn(
                OpenRouterFeedParser.ParseResult.success(List.of(), 0, 0, 0, 0));

        var result = syncService.sync(ModelCatalogSyncService.SyncRequest.dryRun("tester"));

        // No crash; the valid row is classified, the null-provider row is
        // skipped by the downstream (prov == null) guard.
        assertThat(result.plan().added()).extracting(m -> m.get("modelId"))
                .containsExactly("gpt-5.4");
    }

    @Test
    @DisplayName("An existing DB row with a null provider does not crash loadExistingNonBridge (second null-hostile call site)")
    void nullProviderExistingDbRowDoesNotCrashLoad() {
        // Second EXCLUDED_PROVIDERS.contains(...) call site: loading existing
        // rows for the diff. A DB row with a null provider must be treated as
        // "not excluded" rather than aborting the sync. Pre-fix this threw the
        // same Object.hashCode() NPE out of sync().
        ModelConfigOverrideEntity nullProviderRow = new ModelConfigOverrideEntity();
        nullProviderRow.setProvider(null);
        nullProviderRow.setModelId("legacy-orphan");
        ModelConfigOverrideEntity valid = entity("openai", "gpt-5.4",
                new BigDecimal("2.500000"), new BigDecimal("15.000000"));
        when(modelRepo.findAllByOrderByRankingAsc()).thenReturn(List.of(nullProviderRow, valid));

        when(liteLlmParser.parse(any(), any(), any())).thenReturn(
                LiteLlmFeedParser.ParseResult.success(List.of(
                        feedRow("openai", "gpt-5.4", "2.500000", "15.000000")
                ), 0, 0, 0, 0, 0));
        when(openRouterParser.parse(any(), any(), any())).thenReturn(
                OpenRouterFeedParser.ParseResult.success(List.of(), 0, 0, 0, 0));

        var result = syncService.sync(ModelCatalogSyncService.SyncRequest.dryRun("tester"));

        // No crash; incoming gpt-5.4 matches the valid existing row (unchanged),
        // and the null-provider existing row is inert in the diff.
        assertThat(result.plan().unchanged()).isEqualTo(1);
        assertThat(result.plan().added()).isEmpty();
    }

    @Test
    @DisplayName("Null-provider feed row is harmless on the apply path: no crash, valid row still merged")
    void nullProviderRowApplyPathDoesNotCrash() {
        Map<String, Object> nullProviderRow = new java.util.HashMap<>();
        nullProviderRow.put("provider", null);
        nullProviderRow.put("modelId", "orphan");
        nullProviderRow.put("priceInput", "1.0");
        nullProviderRow.put("priceOutput", "2.0");

        when(liteLlmParser.parse(any(), any(), any())).thenReturn(
                LiteLlmFeedParser.ParseResult.success(List.of(
                        feedRow("openai", "gpt-5.4", "2.5", "15.0"),
                        nullProviderRow
                ), 0, 0, 0, 0, 0));
        when(openRouterParser.parse(any(), any(), any())).thenReturn(
                OpenRouterFeedParser.ParseResult.success(List.of(), 0, 0, 0, 0));
        when(mergeService.merge(any(), any()))
                .thenReturn(new CatalogMergeService.MergeResult(1, 0, 0, 0, 0, 1));

        var result = syncService.sync(ModelCatalogSyncService.SyncRequest.apply("ops", Set.of()));

        assertThat(result.applied()).isTrue();
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Map<String, Object>>> payload = ArgumentCaptor.forClass(List.class);
        verify(mergeService).merge(payload.capture(), any());
        // The valid row still reaches merge; the null-provider row flows through
        // as a no-op (CatalogMergeService skips null-provider rows) instead of
        // crashing the excluded-provider filter upstream.
        assertThat(payload.getValue()).extracting(m -> m.get("modelId")).contains("gpt-5.4");
    }

    @Test
    @DisplayName("Bridge rows are derived from LiteLLM cloud entries (AFTER excluded-provider filter) and merged into feed")
    void bridgeRowsAreDerivedAndAppended() {
        when(liteLlmParser.parse(any(), any(), any())).thenReturn(
                LiteLlmFeedParser.ParseResult.success(List.of(
                        feedRow("anthropic", "claude-opus-4-7", "5.0", "25.0")
                ), 0, 0, 0, 0, 0));
        when(openRouterParser.parse(any(), any(), any())).thenReturn(
                OpenRouterFeedParser.ParseResult.success(List.of(), 0, 0, 0, 0));

        Map<String, Object> bridgeRow = feedRow("claude-code", "claude-opus-4-7", "5.0", "25.0");
        when(bridgeModelDeriver.derive(any())).thenReturn(List.of(bridgeRow));
        when(mergeService.merge(any(), any()))
                .thenReturn(new CatalogMergeService.MergeResult(2, 0, 0, 0, 0, 2));

        syncService.sync(ModelCatalogSyncService.SyncRequest.apply("ops", Set.of()));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Map<String, Object>>> payload = ArgumentCaptor.forClass(List.class);
        verify(mergeService).merge(payload.capture(), any());
        assertThat(payload.getValue()).extracting(m -> m.get("provider"))
                .containsExactlyInAnyOrder("anthropic", "claude-code");
    }

    // ────────────────────────────────────────────────────────────────────────
    // Apply-path failure → APPLY_ERROR, log still written via REQUIRES_NEW writer
    // ────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Merge throws: writer is still called with APPLY_ERROR + inserted/updated counts stay 0")
    void mergeFailureLogsApplyErrorWithoutPoisoningWriter() {
        when(liteLlmParser.parse(any(), any(), any())).thenReturn(
                LiteLlmFeedParser.ParseResult.success(List.of(
                        feedRow("openai", "gpt-5.4", "2.5", "15.0")
                ), 0, 0, 0, 0, 0));
        when(openRouterParser.parse(any(), any(), any())).thenReturn(
                OpenRouterFeedParser.ParseResult.success(List.of(), 0, 0, 0, 0));
        when(mergeService.merge(any(), any()))
                .thenThrow(new RuntimeException("constraint violation on price_input"));

        var result = syncService.sync(ModelCatalogSyncService.SyncRequest.apply("ops", Set.of()));

        assertThat(result.applied()).isFalse();
        assertThat(result.inserted()).isZero();
        assertThat(result.updatedCount()).isZero();
        assertThat(result.deprecated()).isZero();
        // The writer lives on Propagation.REQUIRES_NEW. If the service
        // regressed to calling repo.save directly instead of going through
        // syncLogWriter, a merge-throw would poison the enclosing TX and the
        // insert would silently fail. Verifying the writer gets called with
        // APPLY_ERROR proves the REQUIRES_NEW isolation is in the call graph.
        verify(syncLogWriter).write(any(), any(), anyInt(), any(), any(), anyBoolean(),
                eq(ModelCatalogSyncLogEntity.Outcome.APPLY_ERROR),
                eq("constraint violation on price_input"), any(),
                anyInt(), anyInt(), anyInt(), anyInt(), any(), any());
    }

    // ────────────────────────────────────────────────────────────────────────
    // History - thin pass-through, keeps coverage honest
    // ────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("recentHistory delegates to repo with newest-first pageable, respecting the limit")
    void recentHistoryPassesPageableToRepo() {
        when(syncLogRepo.findAllByOrderByCreatedAtDesc(any())).thenReturn(List.of());

        syncService.recentHistory(25);

        verify(syncLogRepo, times(1)).findAllByOrderByCreatedAtDesc(argThat(p ->
                p.getPageSize() == 25 && p.getPageNumber() == 0));
    }

    // ────────────────────────────────────────────────────────────────────────
    // Fixture helpers
    // ────────────────────────────────────────────────────────────────────────

    private static <T> T argThat(org.mockito.ArgumentMatcher<T> m) {
        return org.mockito.ArgumentMatchers.argThat(m);
    }

    /** Reflection-only factory for the package-private FetchedFeed success path. */
    private static Object feedOk(String body, String sha) {
        try {
            var cls = Class.forName("com.apimarketplace.agent.catalog.sync.ModelCatalogSyncService$FetchedFeed");
            var ok = cls.getDeclaredMethod("ok", byte[].class, String.class);
            ok.setAccessible(true);
            return ok.invoke(null, body.getBytes(), sha);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static Object feedErr(String msg) {
        try {
            var cls = Class.forName("com.apimarketplace.agent.catalog.sync.ModelCatalogSyncService$FetchedFeed");
            var err = cls.getDeclaredMethod("err", String.class);
            err.setAccessible(true);
            return err.invoke(null, msg);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static ModelConfigOverrideEntity entity(String provider, String modelId,
                                                     BigDecimal priceIn, BigDecimal priceOut) {
        ModelConfigOverrideEntity e = new ModelConfigOverrideEntity();
        e.setProvider(provider);
        e.setModelId(modelId);
        e.setPriceInput(priceIn);
        e.setPriceOutput(priceOut);
        return e;
    }

    private static Map<String, Object> feedRow(String provider, String modelId,
                                                String priceIn, String priceOut) {
        return new java.util.HashMap<>(Map.of(
                "provider", provider,
                "modelId", modelId,
                "priceInput", priceIn,
                "priceOutput", priceOut
        ));
    }

    /**
     * Feed row populated with every field {@code rowEquals} compares. Mirrors
     * the shape {@link CatalogMergeService} reads - adding a new applyFields
     * field without updating rowEquals + this fixture will make the test fail,
     * which is the whole point (V3.1 fix #1).
     */
    private static java.util.HashMap<String, Object> fullyPopulatedFeedRow() {
        java.util.HashMap<String, Object> m = new java.util.HashMap<>();
        m.put("provider", "anthropic");
        m.put("modelId", "claude-opus-4-7");
        m.put("priceInput", "5.000000");
        m.put("priceOutput", "25.000000");
        m.put("priceInputBatch", "2.500000");
        m.put("priceOutputBatch", "12.500000");
        m.put("priceCacheRead", "0.500000");
        m.put("priceCacheWrite", "6.250000");
        m.put("priceFloorInput", "4.000000");
        m.put("priceFloorOutput", "20.000000");
        m.put("contextWindow", 400_000);
        m.put("maxOutputTokens", 128_000);
        m.put("supportsTools", true);
        m.put("supportsVision", true);
        m.put("supportsPromptCaching", true);
        m.put("supportsReasoning", true);
        m.put("supportsComputerUse", false);
        m.put("supportsResponseSchema", true);
        m.put("supportsWebSearch", false);
        m.put("tier", "top");
        m.put("mode", "chat");
        m.put("deprecationDate", "2027-01-01");
        m.put("releaseDate", "2026-04-15");
        m.put("rateLimitTpm", 500_000);
        m.put("rateLimitRpm", 5_000);
        return m;
    }

    /** Entity twin of {@link #fullyPopulatedFeedRow()}. Same values, typed. */
    private static ModelConfigOverrideEntity fullyPopulatedEntity() {
        ModelConfigOverrideEntity e = new ModelConfigOverrideEntity();
        e.setProvider("anthropic");
        e.setModelId("claude-opus-4-7");
        e.setPriceInput(new BigDecimal("5.000000"));
        e.setPriceOutput(new BigDecimal("25.000000"));
        e.setPriceInputBatch(new BigDecimal("2.500000"));
        e.setPriceOutputBatch(new BigDecimal("12.500000"));
        e.setPriceCacheRead(new BigDecimal("0.500000"));
        e.setPriceCacheWrite(new BigDecimal("6.250000"));
        e.setPriceFloorInput(new BigDecimal("4.000000"));
        e.setPriceFloorOutput(new BigDecimal("20.000000"));
        e.setContextWindow(400_000);
        e.setMaxOutputTokens(128_000);
        e.setSupportsTools(true);
        e.setSupportsVision(true);
        e.setSupportsPromptCaching(true);
        e.setSupportsReasoning(true);
        e.setSupportsComputerUse(false);
        e.setSupportsResponseSchema(true);
        e.setSupportsWebSearch(false);
        e.setTier("top");
        e.setMode("chat");
        e.setDeprecationDate(LocalDate.parse("2027-01-01"));
        e.setReleaseDate(LocalDate.parse("2026-04-15"));
        e.setRateLimitTpm(500_000);
        e.setRateLimitRpm(5_000);
        return e;
    }
}
