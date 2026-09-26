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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

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
 *   <li>Price-sanity guard: the flagged row is withheld while the rest applies, vs. overridden + everything applied.</li>
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
    private NativeModelDiscoveryService discoveryService;
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
        discoveryService = mock(NativeModelDiscoveryService.class);
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
                liteLlmParser, openRouterParser, bridgeModelDeriver, discoveryService, mergeService, mergeRunner,
                modelRepo, syncLogRepo, syncLogWriter, restTemplate));

        // Default: both feeds reachable. Individual tests override as needed.
        doReturn(feedOk("litellm-bytes", "sha-litellm")).when(syncService).fetchLiteLlm();
        doReturn(feedOk("openrouter-bytes", "sha-openrouter")).when(syncService).fetchOpenRouter();

        // Bridge deriver defaults to "no bridges" - tests that care stub it.
        when(bridgeModelDeriver.derive(any())).thenReturn(List.of());

        // Discovery defaults to "asked nobody" - the vendor-endpoint pass is a
        // separate source with its own test; tests that care stub it.
        when(discoveryService.discover(any(), any(), any(), any()))
                .thenReturn(NativeModelDiscoveryService.DiscoveryResult.empty());

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
    @DisplayName("V533: a retired row the feed still carries is neither added, updated, unchanged nor price-flagged")
    void retiredRowIsLeftOutOfThePreview() {
        // The merge leaves a retired row untouched, so the preview must not promise an update
        // (or, if the row were dropped from the baseline, an insert) that will never happen.
        ModelConfigOverrideEntity retired = entity("openai", "gpt-4o",
                new BigDecimal("2.500000"), new BigDecimal("10.000000"));
        retired.setRetiredAt(java.time.Instant.now());
        when(modelRepo.findAllByOrderByRankingAsc()).thenReturn(List.of(retired));
        when(liteLlmParser.parse(any(), any(), any())).thenReturn(
                LiteLlmFeedParser.ParseResult.success(List.of(
                        // A price move far past the sanity guard: it would be flagged on a live row.
                        feedRow("openai", "gpt-4o", "25.000000", "100.000000")
                ), Set.of(), 0, 0, 0, 0, 0));
        when(openRouterParser.parse(any(), any(), any())).thenReturn(
                OpenRouterFeedParser.ParseResult.success(List.of(), 0, 0, 0, 0));

        var result = syncService.sync(ModelCatalogSyncService.SyncRequest.dryRun("tester"));

        assertThat(result.plan().added()).isEmpty();
        assertThat(result.plan().updated()).isEmpty();
        assertThat(result.plan().unchanged()).isZero();
        assertThat(result.plan().flagged()).isEmpty();
    }

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
                ), Set.of(), 0, 0, 0, 0, 0));
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
    @DisplayName("The feed's rejections reach discovery, so the two sources cannot disagree on what is publishable")
    void handsTheFeedsRejectionsToDiscovery() {
        // The only seam between the parser's declined-id index and the filter
        // that consumes it. Untested, passing Set.of() here would break
        // nothing visible: discovery would silently re-admit every model the
        // feed rejected, and every other test would stay green.
        Set<String> declined = Set.of(
                NativeModelDiscoveryService.key("openai", "text-embedding-3-large"),
                NativeModelDiscoveryService.key("openai", "gpt-5-chat"));

        when(liteLlmParser.parse(any(), any(), any())).thenReturn(
                LiteLlmFeedParser.ParseResult.success(List.of(
                        feedRow("openai", "gpt-5.4", "2.500000", "15.000000")
                ), declined, 0, 0, 0, 0, 0));
        when(openRouterParser.parse(any(), any(), any())).thenReturn(
                OpenRouterFeedParser.ParseResult.success(List.of(), 0, 0, 0, 0));

        syncService.sync(ModelCatalogSyncService.SyncRequest.dryRun("tester"));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Set<String>> declinedArg = ArgumentCaptor.forClass(Set.class);
        verify(discoveryService).discover(any(), any(), any(), declinedArg.capture());
        assertThat(declinedArg.getValue()).isEqualTo(declined);
    }

    @Test
    @DisplayName("A LiteLLM outage leaves discovery with no rejections to honour, not a null")
    void passesAnEmptyRejectionSetWhenTheFeedIsDown() {
        // LiteLLM is the only source of declined ids. When it fails the sync
        // still runs on OpenRouter alone, and discovery must get an empty set
        // rather than a null it would have to defend against.
        doReturn(feedErr("litellm down")).when(syncService).fetchLiteLlm();
        when(openRouterParser.parse(any(), any(), any())).thenReturn(
                OpenRouterFeedParser.ParseResult.success(List.of(), 0, 0, 0, 0));

        syncService.sync(ModelCatalogSyncService.SyncRequest.dryRun("tester"));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Set<String>> declinedArg = ArgumentCaptor.forClass(Set.class);
        verify(discoveryService).discover(any(), any(), any(), declinedArg.capture());
        assertThat(declinedArg.getValue()).isNotNull().isEmpty();
    }

    @Test
    @DisplayName("Apply path: invokes mergeService.merge with MergeOptions.forSync + logs inserted/updated/deprecated counts")
    void applyPathInvokesMergeWithSyncOptions() {
        when(liteLlmParser.parse(any(), any(), any())).thenReturn(
                LiteLlmFeedParser.ParseResult.success(List.of(
                        feedRow("openai", "gpt-5.4", "2.500000", "15.000000")
                ), Set.of(), 0, 0, 0, 0, 0));
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
                LiteLlmFeedParser.ParseResult.success(rows, Set.of(), 0, 0, 0, 0, 0));
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
                LiteLlmFeedParser.ParseResult.success(liteRows, Set.of(), 0, 0, 0, 0, 0));
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
    @DisplayName("An aborted run still records how many rows WOULD have been withheld")
    void abortedRunRecordsTheWithheldCountItNeverApplied() {
        // flaggedWithheld is asserted on the two OK paths, and its own javadoc
        // singles out the aborted ones as where it "reads most misleadingly":
        // nothing was applied at all, so the count means "would have been
        // withheld" and only dry_run + outcome say so. Pinning it here is what
        // stops a later reader treating the number as rows actually held back
        // from a run that happened.
        ModelConfigOverrideEntity baseline = entity("openai", "gpt-5.4",
                new BigDecimal("2.000000"), new BigDecimal("15.000000"));
        when(modelRepo.findAllByOrderByRankingAsc()).thenReturn(List.of(baseline));

        // A prior OK run where LiteLLM returned 100 rows; this run returns one,
        // so count-floor blocks the whole apply while the price move is still
        // flagged. That combination is exactly the state being pinned.
        ModelCatalogSyncLogEntity priorRun = new ModelCatalogSyncLogEntity();
        priorRun.setLiteLlmCount(100);
        when(syncLogRepo.findFirstByOutcomeAndDryRunAndLiteLlmCountIsNotNullOrderByCreatedAtDesc(
                eq(ModelCatalogSyncLogEntity.Outcome.OK), eq(Boolean.FALSE)))
                .thenReturn(Optional.of(priorRun));

        when(liteLlmParser.parse(any(), any(), any())).thenReturn(
                LiteLlmFeedParser.ParseResult.success(List.of(
                        feedRow("openai", "gpt-5.4", "10.000000", "15.000000")
                ), Set.of(), 0, 0, 0, 0, 0));
        when(openRouterParser.parse(any(), any(), any())).thenReturn(
                OpenRouterFeedParser.ParseResult.success(List.of(), 0, 0, 0, 0));

        var result = syncService.sync(ModelCatalogSyncService.SyncRequest.apply("ops", Set.of()));

        assertThat(result.applied()).isFalse();
        verify(mergeService, never()).merge(any(), any());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> logPayload = ArgumentCaptor.forClass(Map.class);
        verify(syncLogWriter).write(any(), any(), anyInt(), any(), eq("ops"), eq(false),
                eq(ModelCatalogSyncLogEntity.Outcome.ABORTED_GUARD), any(), logPayload.capture(),
                anyInt(), anyInt(), anyInt(), eq(1), any(), any());
        assertThat(logPayload.getValue()).containsEntry("flaggedWithheld", 1);
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
                ), Set.of(), 0, 0, 0, 0, 0));
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
                ), Set.of(), 0, 0, 0, 0, 0));
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
    @DisplayName("Regression: a flagged price holds back only its own row, the rest of the refresh still applies")
    void priceSanityHoldsBackOnlyTheFlaggedRow() {
        // Pre-fix this asserted the opposite: an aggregate price-sanity
        // GuardFailure made sync() return before the apply, so ONE moved price
        // cancelled the entire refresh. Because nothing was written, the stored
        // baseline never advanced, so the identical row flagged again on the
        // next run and the next, and the operator's only exit was the blanket
        // override that accepts every flagged price unread.
        ModelConfigOverrideEntity flaggedBaseline = entity("openai", "gpt-5.4",
                new BigDecimal("2.000000"), new BigDecimal("15.000000"));
        ModelConfigOverrideEntity steadyBaseline = entity("mistral", "mistral-medium",
                new BigDecimal("1.000000"), new BigDecimal("3.000000"));
        when(modelRepo.findAllByOrderByRankingAsc())
                .thenReturn(List.of(flaggedBaseline, steadyBaseline));

        when(liteLlmParser.parse(any(), any(), any())).thenReturn(
                LiteLlmFeedParser.ParseResult.success(List.of(
                        feedRow("openai", "gpt-5.4", "10.000000", "15.000000"),        // 5x -> flagged
                        feedRow("mistral", "mistral-medium", "1.100000", "3.000000"),  // +10% -> clean
                        feedRow("anthropic", "claude-opus-4-7", "5.0", "25.0")         // new -> no baseline
                ), Set.of(), 0, 0, 0, 0, 0));
        when(openRouterParser.parse(any(), any(), any())).thenReturn(
                OpenRouterFeedParser.ParseResult.success(List.of(), 0, 0, 0, 0));
        when(mergeService.merge(any(), any()))
                .thenReturn(new CatalogMergeService.MergeResult(1, 1, 0, 0, 0, 2));

        var result = syncService.sync(ModelCatalogSyncService.SyncRequest.apply("ops", Set.of()));

        assertThat(result.applied()).isTrue();

        // Only the flagged row is withheld. The clean update and the brand-new
        // model both land, which is the whole difference: a refresh that used
        // to be all-or-nothing now delivers everything it can vouch for.
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Map<String, Object>>> payload = ArgumentCaptor.forClass(List.class);
        verify(mergeService).merge(payload.capture(), any());
        assertThat(payload.getValue()).extracting(m -> m.get("modelId"))
                .containsExactlyInAnyOrder("mistral-medium", "claude-opus-4-7");

        assertThat(result.plan().flagged())
                .extracting(ModelCatalogSyncService.FlaggedRow::modelId)
                .containsExactly("gpt-5.4");

        // The flag is a review queue, not a stop sign. guardFailures means
        // "this run must not apply", and the REST layer turns exactly that into
        // a 412 - so a held-back row must leave it empty, or the caller is told
        // nothing happened when almost everything did.
        assertThat(result.plan().guardFailures()).isEmpty();

        // And the sync-log row has to agree with that story, because it is the
        // only record left once the response is gone: OK rather than
        // ABORTED_GUARD, with the withheld row still counted so the audit trail
        // shows the run was partial. An OK row with flagged=0 would claim a
        // clean refresh that did not happen.
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> logPayload = ArgumentCaptor.forClass(Map.class);
        verify(syncLogWriter).write(any(), any(), anyInt(), any(), eq("ops"), eq(false),
                eq(ModelCatalogSyncLogEntity.Outcome.OK), any(), logPayload.capture(),
                anyInt(), anyInt(), anyInt(), eq(1), any(), any());

        // flagged_count alone can no longer tell the two price-sanity outcomes
        // apart. A withheld run and an overridden run both write OK with the
        // same count and the same rows; while the guard still aborted, OK plus
        // flags could only mean "overridden", so the row was unambiguous by
        // accident. flaggedWithheld is what replaces that accident.
        assertThat(logPayload.getValue()).containsEntry("flaggedWithheld", 1);
    }

    @Test
    @DisplayName("A move of EXACTLY 50% is not a >50% move, so it does not flag")
    void priceSanityRatioBoundaryIsExclusive() {
        // The absolute floor's boundary is pinned meticulously next door while
        // the ratio's was not, so `> ratio` could have become `>= ratio` with
        // nothing to notice. 1.00 -> 1.50 is exactly 50% and clears the
        // absolute floor by a wide margin, which is what isolates the ratio
        // comparison as the only thing under test here.
        ModelConfigOverrideEntity baseline = entity("zai", "glm-tiny",
                new BigDecimal("1.000000"), new BigDecimal("3.000000"));
        when(modelRepo.findAllByOrderByRankingAsc()).thenReturn(List.of(baseline));

        when(liteLlmParser.parse(any(), any(), any())).thenReturn(
                LiteLlmFeedParser.ParseResult.success(List.of(
                        feedRow("zai", "glm-tiny", "1.500000", "3.000000")
                ), Set.of(), 0, 0, 0, 0, 0));
        when(openRouterParser.parse(any(), any(), any())).thenReturn(
                OpenRouterFeedParser.ParseResult.success(List.of(), 0, 0, 0, 0));

        var result = syncService.sync(ModelCatalogSyncService.SyncRequest.dryRun("tester"));

        assertThat(result.plan().flagged()).isEmpty();
    }

    @Test
    @DisplayName("Price-sanity ignores a >50% move worth less than $0.10 per million tokens")
    void priceSanityIgnoresEconomicallyTrivialMoves() {
        // Measured on a real refresh: tencent/hy3 moved 0.0825 to 0.1320. That
        // is +60% and five cents per million tokens. Asking an operator to rule
        // on it is how a list of flags gets waved through, taking the one real
        // anomaly with it.
        ModelConfigOverrideEntity baseline = entity("zai", "glm-tiny",
                new BigDecimal("0.082500"), new BigDecimal("0.330000"));
        when(modelRepo.findAllByOrderByRankingAsc()).thenReturn(List.of(baseline));

        when(liteLlmParser.parse(any(), any(), any())).thenReturn(
                LiteLlmFeedParser.ParseResult.success(List.of(
                        feedRow("zai", "glm-tiny", "0.132000", "0.330000")
                ), Set.of(), 0, 0, 0, 0, 0));
        when(openRouterParser.parse(any(), any(), any())).thenReturn(
                OpenRouterFeedParser.ParseResult.success(List.of(), 0, 0, 0, 0));

        var result = syncService.sync(ModelCatalogSyncService.SyncRequest.dryRun("tester"));

        assertThat(result.plan().flagged()).isEmpty();
    }

    @Test
    @DisplayName("Price-sanity still flags a >50% move the moment it is worth $0.10 per million tokens")
    void priceSanityFlagsAtTheAbsoluteFloorBoundary() {
        // 0.100000 -> 0.200000 is a delta of exactly the floor. The floor is
        // inclusive on purpose: a rule that needed strictly more would leave a
        // silent dead band at its own threshold, and the boundary is the one
        // value a reader of the constant will assume is covered.
        ModelConfigOverrideEntity baseline = entity("zai", "glm-tiny",
                new BigDecimal("0.100000"), new BigDecimal("0.330000"));
        when(modelRepo.findAllByOrderByRankingAsc()).thenReturn(List.of(baseline));

        when(liteLlmParser.parse(any(), any(), any())).thenReturn(
                LiteLlmFeedParser.ParseResult.success(List.of(
                        feedRow("zai", "glm-tiny", "0.200000", "0.330000")
                ), Set.of(), 0, 0, 0, 0, 0));
        when(openRouterParser.parse(any(), any(), any())).thenReturn(
                OpenRouterFeedParser.ParseResult.success(List.of(), 0, 0, 0, 0));

        var result = syncService.sync(ModelCatalogSyncService.SyncRequest.dryRun("tester"));

        assertThat(result.plan().flagged())
                .extracting(ModelCatalogSyncService.FlaggedRow::modelId)
                .containsExactly("glm-tiny");
        // Both thresholds are named, and both are derived from the constants
        // rather than typed out. Naming only the ratio was not merely imprecise:
        // two rows can move by the same percentage with only one flagged, and an
        // operator reading ">50%" on one and nothing on the other cannot see why.
        assertThat(result.plan().flagged().get(0).reason())
                .contains(">50%")
                .contains(">=0.1/M");
    }

    @Test
    @DisplayName("The output price is judged by the same two-part rule as the input price")
    void priceOutputDriftIsFlaggedOnItsOwn()  {
        // Only the priceInput arm was ever asserted, so the output arm could
        // have been given a different threshold, a different message, or no
        // check at all without a single test noticing - on the side of the
        // bill that is usually the larger one.
        ModelConfigOverrideEntity baseline = entity("zai", "glm-tiny",
                new BigDecimal("1.000000"), new BigDecimal("2.000000"));
        when(modelRepo.findAllByOrderByRankingAsc()).thenReturn(List.of(baseline));

        when(liteLlmParser.parse(any(), any(), any())).thenReturn(
                LiteLlmFeedParser.ParseResult.success(List.of(
                        feedRow("zai", "glm-tiny", "1.000000", "8.000000")
                ), Set.of(), 0, 0, 0, 0, 0));
        when(openRouterParser.parse(any(), any(), any())).thenReturn(
                OpenRouterFeedParser.ParseResult.success(List.of(), 0, 0, 0, 0));

        var result = syncService.sync(ModelCatalogSyncService.SyncRequest.dryRun("tester"));

        assertThat(result.plan().flagged())
                .extracting(ModelCatalogSyncService.FlaggedRow::reason)
                .singleElement().asString()
                .startsWith("priceOutput ")
                .contains(">50%")
                .contains(">=0.1/M");
    }

    @Test
    @DisplayName("A zero-price anomaly is flagged whatever the amount, the absolute floor does not apply to it")
    void zeroPriceAnomalyIgnoresTheAbsoluteFloor() {
        // A price falling to 0 is not a repricing, it is a broken feed row, and
        // an unpriced model bills at the platform default rather than the
        // vendor's. The floor exists to silence trivial MOVES and must never
        // silence this: at 0.050000 -> 0 the delta is under the floor.
        ModelConfigOverrideEntity baseline = entity("zai", "glm-tiny",
                new BigDecimal("0.050000"), new BigDecimal("0.330000"));
        when(modelRepo.findAllByOrderByRankingAsc()).thenReturn(List.of(baseline));

        when(liteLlmParser.parse(any(), any(), any())).thenReturn(
                LiteLlmFeedParser.ParseResult.success(List.of(
                        feedRow("zai", "glm-tiny", "0.000000", "0.330000")
                ), Set.of(), 0, 0, 0, 0, 0));
        when(openRouterParser.parse(any(), any(), any())).thenReturn(
                OpenRouterFeedParser.ParseResult.success(List.of(), 0, 0, 0, 0));

        var result = syncService.sync(ModelCatalogSyncService.SyncRequest.dryRun("tester"));

        assertThat(result.plan().flagged())
                .extracting(ModelCatalogSyncService.FlaggedRow::reason)
                .singleElement().asString().contains("dropped to 0");
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
                ), Set.of(), 0, 0, 0, 0, 0));
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

        // The other half of the distinction, and the reason this assertion
        // belongs in BOTH tests: the same flagged row, the same OK outcome, the
        // same flagged_count as the withheld case - and zero withheld, because
        // the operator let it through. One test alone would pin a number
        // without proving it discriminates.
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> logPayload = ArgumentCaptor.forClass(Map.class);
        verify(syncLogWriter).write(any(), any(), anyInt(), any(), eq("ops"), eq(false),
                eq(ModelCatalogSyncLogEntity.Outcome.OK), any(), logPayload.capture(),
                anyInt(), anyInt(), anyInt(), eq(1), any(), any());
        assertThat(logPayload.getValue()).containsEntry("flaggedWithheld", 0);
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
                LiteLlmFeedParser.ParseResult.success(List.of(incoming), Set.of(), 0, 0, 0, 0, 0));
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
                        List.of(fullyPopulatedFeedRow()), Set.of(), 0, 0, 0, 0, 0));
        when(openRouterParser.parse(any(), any(), any())).thenReturn(
                OpenRouterFeedParser.ParseResult.success(List.of(), 0, 0, 0, 0));

        var result = syncService.sync(ModelCatalogSyncService.SyncRequest.dryRun("tester"));

        assertThat(result.plan().unchanged()).isEqualTo(1);
        assertThat(result.plan().updated()).isEmpty();
        assertThat(result.plan().added()).isEmpty();
    }

    @ParameterizedTest(name = "a changed {0} reports \"updated\", not \"unchanged\"")
    @MethodSource("feedOwnedTextFields")
    @DisplayName("Regression: every feed-owned field applyFields writes is compared, so no silent rewrite")
    void feedOwnedFieldChangeIsReportedAsUpdated(String field, Object changedValue) {
        // rowEquals decides what the dry-run shows; the apply writes the row
        // regardless. A field applyFields writes but rowEquals skips is
        // therefore rewritten silently, which the method contract forbids.
        // displayName was the reported case (OpenRouter renames a model); the
        // others are the same defect in the same method - description in
        // particular churns constantly on the OpenRouter feed.
        ModelConfigOverrideEntity existing = fullyPopulatedEntity();
        when(modelRepo.findAllByOrderByRankingAsc()).thenReturn(List.of(existing));

        java.util.HashMap<String, Object> incoming = fullyPopulatedFeedRow();
        incoming.put(field, changedValue);

        when(liteLlmParser.parse(any(), any(), any())).thenReturn(
                LiteLlmFeedParser.ParseResult.success(List.of(incoming), Set.of(), 0, 0, 0, 0, 0));
        when(openRouterParser.parse(any(), any(), any())).thenReturn(
                OpenRouterFeedParser.ParseResult.success(List.of(), 0, 0, 0, 0));

        var result = syncService.sync(ModelCatalogSyncService.SyncRequest.dryRun("tester"));

        assertThat(result.plan().unchanged())
                .as("'%s' changed, so the row must NOT be reported unchanged", field)
                .isZero();
        assertThat(result.plan().updated()).hasSize(1);
    }

    static Stream<Arguments> feedOwnedTextFields() {
        return Stream.of(
                // The reported case: OpenRouter serves displayName from the
                // feed's `name`, so a provider-side rebrand lands here.
                Arguments.of("displayName", "Anthropic: Claude Opus 4.7"),
                Arguments.of("description", "Rewritten marketing copy."),
                Arguments.of("supportedEndpoints", List.of("/v1/chat/completions")),
                Arguments.of("supportedModalities", List.of("text")),
                Arguments.of("supportedOutputModalities", List.of("text", "image")));
    }

    @Test
    @DisplayName("Excluded providers (the 4 CLI bridges) are stripped from feed before diff")
    void excludedProvidersAreFiltered() {
        // Parsers already exclude these in practice, but the service re-filters
        // as belt-and-braces. Test that branch by stubbing parsers to include
        // an excluded provider directly. Bridge rows are DERIVED from the cloud
        // entries by BridgeModelDeriver after this filter, so a feed must never
        // write them itself.
        when(liteLlmParser.parse(any(), any(), any())).thenReturn(
                LiteLlmFeedParser.ParseResult.success(List.of(
                        feedRow("openai", "gpt-5.4", "2.5", "15.0"),
                        feedRow("claude-code", "claude-opus-4-7", "5.0", "25.0"),
                        feedRow("mistral-vibe", "devstral-2", "0.4", "2.0")
                ), Set.of(), 0, 0, 0, 0, 0));
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
    @DisplayName("Regression: zai feed rows reach the merge - the GLM line used to be excluded outright")
    void zaiRowsReachTheMerge() {
        // zai sat in EXCLUDED_PROVIDERS on the assumption it was absent from
        // LiteLLM. It is not (the feed carries glm-4.5 through glm-5.1), so the
        // exclusion froze Z.AI on the ids hardcoded in application.yml.
        when(liteLlmParser.parse(any(), any(), any())).thenReturn(
                LiteLlmFeedParser.ParseResult.success(List.of(
                        feedRow("zai", "glm-5.1", "1.4", "4.4"),
                        feedRow("moonshot", "kimi-k2.6", "0.95", "4.0"),
                        feedRow("qwen", "qwen-max", "1.6", "6.4")
                ), Set.of(), 0, 0, 0, 0, 0));
        when(openRouterParser.parse(any(), any(), any())).thenReturn(
                OpenRouterFeedParser.ParseResult.success(List.of(), 0, 0, 0, 0));
        when(mergeService.merge(any(), any()))
                .thenReturn(new CatalogMergeService.MergeResult(3, 0, 0, 0, 0, 3));

        syncService.sync(ModelCatalogSyncService.SyncRequest.apply("ops", Set.of()));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Map<String, Object>>> payload = ArgumentCaptor.forClass(List.class);
        verify(mergeService).merge(payload.capture(), any());
        assertThat(payload.getValue()).extracting(m -> m.get("provider"))
                .containsExactlyInAnyOrder("zai", "moonshot", "qwen");
    }

    @Test
    @DisplayName("Regression: existing zai DB rows now enter the diff baseline instead of re-appearing as 'added' every sync")
    void existingZaiRowsEnterTheDiffBaseline() {
        // Second EXCLUDED_PROVIDERS call site: loadExistingNonBridge(). While
        // zai sat in that set, its seeded rows were skipped when building the
        // baseline, so an incoming zai row could never match one - it would be
        // classified "added" on every single run and the price-sanity guard,
        // which only compares against baseline rows, could never protect it.
        ModelConfigOverrideEntity seededGlm = entity("zai", "glm-5.1",
                new BigDecimal("1.400000"), new BigDecimal("4.400000"));
        when(modelRepo.findAllByOrderByRankingAsc()).thenReturn(List.of(seededGlm));

        when(liteLlmParser.parse(any(), any(), any())).thenReturn(
                LiteLlmFeedParser.ParseResult.success(List.of(
                        feedRow("zai", "glm-5.1", "1.400000", "4.400000")
                ), Set.of(), 0, 0, 0, 0, 0));
        when(openRouterParser.parse(any(), any(), any())).thenReturn(
                OpenRouterFeedParser.ParseResult.success(List.of(), 0, 0, 0, 0));

        var result = syncService.sync(ModelCatalogSyncService.SyncRequest.dryRun("tester"));

        // Same prices AND same display name on both sides -> the row matches
        // the baseline. Pre-fix this was 0 unchanged / 0 added: zai was
        // stripped from BOTH the feed (removeIf) and the baseline, so the sync
        // was a complete no-op for the provider.
        assertThat(result.plan().unchanged()).isEqualTo(1);
        assertThat(result.plan().added()).isEmpty();
        assertThat(result.plan().updated()).isEmpty();
    }

    @Test
    @DisplayName("Regression: a zai price swing is caught by the price-sanity guard now that the baseline includes it")
    void zaiPriceSwingIsFlaggedByTheSanityGuard() {
        // The flip side of the baseline fix: with zai excluded there was no
        // "old" price to compare against, so an absurd feed price would have
        // been applied unchallenged. Now it is flagged and dropped from apply.
        ModelConfigOverrideEntity seededGlm = entity("zai", "glm-5.1",
                new BigDecimal("1.400000"), new BigDecimal("4.400000"));
        when(modelRepo.findAllByOrderByRankingAsc()).thenReturn(List.of(seededGlm));

        when(liteLlmParser.parse(any(), any(), any())).thenReturn(
                LiteLlmFeedParser.ParseResult.success(List.of(
                        feedRow("zai", "glm-5.1", "14.000000", "44.000000")   // 10x
                ), Set.of(), 0, 0, 0, 0, 0));
        when(openRouterParser.parse(any(), any(), any())).thenReturn(
                OpenRouterFeedParser.ParseResult.success(List.of(), 0, 0, 0, 0));

        var result = syncService.sync(ModelCatalogSyncService.SyncRequest.dryRun("tester"));

        assertThat(result.plan().flagged())
                .extracting(ModelCatalogSyncService.FlaggedRow::modelId)
                .containsExactly("glm-5.1");
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
                ), Set.of(), 0, 0, 0, 0, 0));
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
                ), Set.of(), 0, 0, 0, 0, 0));
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
                ), Set.of(), 0, 0, 0, 0, 0));
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
                ), Set.of(), 0, 0, 0, 0, 0));
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
                ), Set.of(), 0, 0, 0, 0, 0));
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
        m.put("displayName", "claude-opus-4-7");
        m.put("description", "Frontier reasoning model.");
        m.put("supportedEndpoints", List.of("/v1/chat/completions", "/v1/batch"));
        m.put("supportedModalities", List.of("text", "image"));
        m.put("supportedOutputModalities", List.of("text"));
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
        e.setDisplayName("claude-opus-4-7");
        e.setDescription("Frontier reasoning model.");
        e.setSupportedEndpoints(new String[]{"/v1/chat/completions", "/v1/batch"});
        e.setSupportedModalities(new String[]{"text", "image"});
        e.setSupportedOutputModalities(new String[]{"text"});
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
