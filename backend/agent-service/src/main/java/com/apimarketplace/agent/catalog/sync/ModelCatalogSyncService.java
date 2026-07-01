package com.apimarketplace.agent.catalog.sync;

import com.apimarketplace.agent.catalog.bundle.CatalogMergeService;
import com.apimarketplace.agent.catalog.bundle.MergeOptions;
import com.apimarketplace.agent.domain.ModelCatalogSyncLogEntity;
import com.apimarketplace.agent.domain.ModelConfigOverrideEntity;
import com.apimarketplace.agent.repository.ModelCatalogSyncLogRepository;
import com.apimarketplace.agent.repository.ModelConfigOverrideRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;

/**
 * Orchestrates the live catalog sync from LiteLLM + OpenRouter into
 * {@code model_config_overrides}. Never touches bridges; never writes to
 * {@code catalog_bundles}.
 *
 * <p>Two call sites:
 * <ul>
 *   <li>{@code ?mode=dry-run} - fetches, runs guards, computes the diff, writes a
 *       {@link ModelCatalogSyncLogEntity} row, returns the plan. No row in
 *       {@code model_config_overrides} is touched.</li>
 *   <li>{@code ?mode=apply} - same as dry-run, then (if guards pass or are
 *       explicitly overridden) calls {@link CatalogMergeService#merge} with
 *       {@link MergeOptions#forSync()} and stamps the sync-log row.</li>
 * </ul>
 *
 * <p>Transactionality: {@link #sync} is {@code @Transactional} as the
 * outer scope. The merge step runs in a dedicated {@link
 * org.springframework.transaction.annotation.Propagation#REQUIRES_NEW} TX via
 * {@link CatalogSyncMergeRunner} so a merge failure rolls back only the merge
 * and NOT the outer sync; the outer then writes an {@code APPLY_ERROR} log
 * row via {@link ModelCatalogSyncLogWriter} (itself REQUIRES_NEW) and returns
 * cleanly. Without this isolation, a DB constraint violation inside merge
 * marked the outer TX as rollback-only and Spring threw
 * {@code UnexpectedRollbackException} on sync-method exit even though the
 * exception was caught. The {@link CatalogMergeService}'s {@code afterCommit}
 * pricing mirror still fires - on the inner TX's commit, which is what we
 * want.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ModelCatalogSyncService {

    /** Providers we NEVER touch from the sync. Bridges + zai (not in LiteLLM). */
    static final Set<String> EXCLUDED_PROVIDERS = Set.of(
            "claude-code", "codex", "gemini-cli", "mistral-vibe", "zai");

    /** Guard names operators can pass in {@code overrideGuards=}. */
    public static final String GUARD_COUNT_FLOOR  = "count-floor";
    public static final String GUARD_PRICE_SANITY = "price-sanity";

    /** 0.8 - feed is rejected if it drops below 80% of the last successful snapshot. */
    private static final BigDecimal COUNT_FLOOR_RATIO = new BigDecimal("0.8");

    /** Price sanity threshold - Δ > 50% triggers a flag. */
    private static final BigDecimal PRICE_SANITY_RATIO = new BigDecimal("0.5");

    private final LiteLlmFeedParser liteLlmParser;
    private final OpenRouterFeedParser openRouterParser;
    private final BridgeModelDeriver bridgeModelDeriver;
    private final CatalogMergeService mergeService;
    private final CatalogSyncMergeRunner mergeRunner;
    private final ModelConfigOverrideRepository modelRepo;
    private final ModelCatalogSyncLogRepository syncLogRepo;
    private final ModelCatalogSyncLogWriter syncLogWriter;
    private final RestTemplate restTemplate;

    @Value("${catalog.sync.litellm-commit-sha:main}")
    private String liteLlmCommitSha;

    @Value("${catalog.sync.litellm-url-template:https://raw.githubusercontent.com/BerriAI/litellm/%s/model_prices_and_context_window.json}")
    private String liteLlmUrlTemplate;

    @Value("${catalog.sync.openrouter-url:https://openrouter.ai/api/v1/models}")
    private String openRouterUrl;

    @Value("${catalog.sync.fetch-timeout-ms:15000}")
    private int fetchTimeoutMs;

    // ── Public API ──────────────────────────────────────────────────────────

    /** What to do. */
    public record SyncRequest(boolean dryRun, Set<String> overrideGuards,
                              String triggeredBy) {
        public static SyncRequest dryRun(String by) {
            return new SyncRequest(true, Set.of(), by);
        }
        public static SyncRequest apply(String by, Set<String> overrides) {
            return new SyncRequest(false, overrides == null ? Set.of() : overrides, by);
        }
    }

    /** Flagged row - a price change big enough to require admin confirmation. */
    public record FlaggedRow(String provider, String modelId, String reason,
                             BigDecimal oldPriceInput, BigDecimal newPriceInput,
                             BigDecimal oldPriceOutput, BigDecimal newPriceOutput) {}

    /** A guard firing - count-floor or similar. Payload is guard-specific. */
    public record GuardFailure(String guard, String detail, Map<String, Object> data) {}

    /** High-level stats about the fetched feeds. */
    public record FeedStats(int liteLlmKept, int openRouterKept,
                            Map<String, Integer> liteLlmRejected,
                            Map<String, Integer> openRouterRejected) {}

    /** Full sync plan - shown to the admin in dry-run mode. */
    public record SyncPlan(FeedStats stats,
                           List<Map<String, Object>> added,
                           List<Map<String, Object>> updated,
                           int unchanged,
                           List<FlaggedRow> flagged,
                           List<GuardFailure> guardFailures) {}

    /** Full result - includes the plan + applied counts (zero if dry-run). */
    public record SyncResult(SyncPlan plan, boolean applied,
                             int inserted, int updatedCount, int deprecated,
                             Long syncLogId) {}

    /**
     * Single entry point used by the REST controller. Wraps the whole flow
     * in a transaction so the merge's {@code afterCommit} hook is armed.
     */
    @Transactional
    public SyncResult sync(SyncRequest req) {
        Objects.requireNonNull(req, "req");
        Instant fetchedAt = Instant.now();

        // 1. Fetch both feeds. Each failure is tolerated independently - a
        //    LiteLLM outage must not block an OpenRouter-only sync and
        //    vice-versa.
        FetchedFeed liteLlmFeed = fetchLiteLlm();
        FetchedFeed openRouterFeed = fetchOpenRouter();

        if (liteLlmFeed.isError() && openRouterFeed.isError()) {
            String err = "both feeds failed: litellm=" + liteLlmFeed.errorMessage() +
                    " ; openrouter=" + openRouterFeed.errorMessage();
            return abortAndLog(req, fetchedAt, "both", 0, null, liteLlmFeed.checksum(),
                    ModelCatalogSyncLogEntity.Outcome.FETCH_ERROR, err, Map.of());
        }

        // 2. Parse each feed that succeeded.
        LiteLlmFeedParser.ParseResult litellm =
                liteLlmFeed.isError() ? null
                        : liteLlmParser.parse(liteLlmFeed.bytes(), liteLlmFeed.checksum(),
                                fetchedAt.toString());
        OpenRouterFeedParser.ParseResult orouter =
                openRouterFeed.isError() ? null
                        : openRouterParser.parse(openRouterFeed.bytes(), openRouterUrl,
                                fetchedAt.toString());

        // Hard parse errors - abort.
        if (litellm != null && !litellm.isSuccess()) {
            return abortAndLog(req, fetchedAt, "litellm", 0, null, liteLlmFeed.checksum(),
                    ModelCatalogSyncLogEntity.Outcome.SCHEMA_ERROR, litellm.errorMessage(), Map.of());
        }
        if (orouter != null && !orouter.isSuccess()) {
            return abortAndLog(req, fetchedAt, "openrouter", 0, null, null,
                    ModelCatalogSyncLogEntity.Outcome.SCHEMA_ERROR, orouter.errorMessage(), Map.of());
        }

        List<Map<String, Object>> allFeedModels = new ArrayList<>();
        if (litellm != null) allFeedModels.addAll(litellm.models());
        if (orouter != null) allFeedModels.addAll(orouter.models());

        // Safety: remove any row under an excluded provider (bridges + zai).
        // Parsers already filter, but this is belt-and-braces.
        allFeedModels.removeIf(m -> isExcludedProvider(strOf(m.get("provider"))));

        // Derive bridge rows from LiteLLM cloud entries (AFTER the exclusion
        // filter, so the parsers stay purely cloud). The deriver honors
        // BridgeAllowlist for which ids are exposed and copies price +
        // context + capabilities from the underlying cloud model.
        if (litellm != null) {
            List<Map<String, Object>> bridgeRows = bridgeModelDeriver.derive(litellm.models());
            allFeedModels.addAll(bridgeRows);
        }

        String sourceTag = sourceTag(litellm != null, orouter != null);

        // 3. Load existing non-bridge rows for diff + guards.
        Map<String, ModelConfigOverrideEntity> existing = loadExistingNonBridge();

        // 4. Guards.
        List<GuardFailure> guardFailures = new ArrayList<>();

        // 4a. Count-floor per feed.
        runCountFloorGuard(litellm, orouter, req.overrideGuards(), guardFailures);

        // 4b. Price-sanity per incoming row vs existing.
        List<FlaggedRow> flagged = new ArrayList<>();
        runPriceSanityGuard(allFeedModels, existing, req.overrideGuards(), flagged, guardFailures);

        // 5. Build the diff buckets.
        List<Map<String, Object>> added = new ArrayList<>();
        List<Map<String, Object>> updatedModels = new ArrayList<>();
        int unchanged = 0;

        // Pre-compute flagged keys to drop from apply when sanity not overridden.
        Set<String> flaggedKeys = new HashSet<>();
        if (!req.overrideGuards().contains(GUARD_PRICE_SANITY)) {
            for (FlaggedRow f : flagged) flaggedKeys.add(key(f.provider(), f.modelId()));
        }

        for (Map<String, Object> m : allFeedModels) {
            String prov = strOf(m.get("provider"));
            String mid  = strOf(m.get("modelId"));
            if (prov == null || mid == null) continue;
            ModelConfigOverrideEntity row = existing.get(key(prov, mid));
            if (row == null) {
                added.add(m);
                continue;
            }
            if (rowEquals(row, m)) {
                unchanged++;
            } else {
                updatedModels.add(m);
            }
        }

        FeedStats stats = new FeedStats(
                litellm != null ? litellm.models().size() : 0,
                orouter != null ? orouter.models().size() : 0,
                litellm != null ? Map.of(
                        "provider", litellm.rejectedProvider(),
                        "mode",     litellm.rejectedMode(),
                        "noTools",  litellm.rejectedNoTools(),
                        "slash",    litellm.rejectedSlash(),
                        "schema",   litellm.rejectedSchema()
                ) : Map.of(),
                orouter != null ? Map.of(
                        "suffix",    orouter.rejectedSuffix(),
                        "noPricing", orouter.rejectedNoPricing(),
                        "noTools",   orouter.rejectedNoTools(),
                        "schema",    orouter.rejectedSchema()
                ) : Map.of()
        );

        SyncPlan plan = new SyncPlan(stats, added, updatedModels, unchanged, flagged, guardFailures);

        Integer liteLlmCount  = litellm != null ? litellm.models().size() : null;
        Integer openRouterCount = orouter != null ? orouter.models().size() : null;

        // 6. Dry-run OR any non-overridden guard failure - log and return without applying.
        if (req.dryRun() || !guardFailures.isEmpty()) {
            ModelCatalogSyncLogEntity.Outcome outcome =
                    guardFailures.isEmpty() ? ModelCatalogSyncLogEntity.Outcome.OK
                                            : ModelCatalogSyncLogEntity.Outcome.ABORTED_GUARD;
            ModelCatalogSyncLogEntity logged = writeLog(req, fetchedAt, sourceTag,
                    allFeedModels.size(), liteLlmFeed.checksum(), outcome,
                    outcome == ModelCatalogSyncLogEntity.Outcome.ABORTED_GUARD
                            ? describeGuards(guardFailures) : null,
                    guardFailuresToJson(guardFailures, flagged),
                    0, 0, 0, flagged.size(),
                    liteLlmCount, openRouterCount);
            return new SyncResult(plan, false, 0, 0, 0, logged.getId());
        }

        // 7. Apply via CatalogMergeService. Drop rows that tripped the
        //    price-sanity guard (admin must explicitly override to push them).
        List<Map<String, Object>> toApply = new ArrayList<>(allFeedModels.size());
        for (Map<String, Object> m : allFeedModels) {
            if (!flaggedKeys.contains(key(strOf(m.get("provider")), strOf(m.get("modelId"))))) {
                toApply.add(m);
            }
        }

        CatalogMergeService.MergeResult merge;
        try {
            // Run merge in REQUIRES_NEW so its rollback does NOT poison the
            // enclosing sync() TX with rollback-only - otherwise Spring throws
            // UnexpectedRollbackException on method exit even though we catch
            // and handle the failure below.
            merge = mergeRunner.merge(toApply, MergeOptions.forSync());
        } catch (Exception e) {
            log.error("catalog-sync: merge failed", e);
            ModelCatalogSyncLogEntity logged = writeLog(req, fetchedAt, sourceTag,
                    allFeedModels.size(), liteLlmFeed.checksum(),
                    ModelCatalogSyncLogEntity.Outcome.APPLY_ERROR,
                    e.getMessage(), guardFailuresToJson(List.of(), flagged),
                    0, 0, 0, flagged.size(),
                    liteLlmCount, openRouterCount);
            return new SyncResult(plan, false, 0, 0, 0, logged.getId());
        }

        // 8. Write successful log row.
        ModelCatalogSyncLogEntity logged = writeLog(req, fetchedAt, sourceTag,
                allFeedModels.size(), liteLlmFeed.checksum(),
                ModelCatalogSyncLogEntity.Outcome.OK, null,
                guardFailuresToJson(List.of(), flagged),
                merge.inserted(), merge.updated(), merge.deprecated(), flagged.size(),
                liteLlmCount, openRouterCount);

        log.info("catalog-sync applied: inserted={}, updated={}, deprecated={}, flaggedSkipped={}, syncLogId={}",
                merge.inserted(), merge.updated(), merge.deprecated(), flagged.size(), logged.getId());

        return new SyncResult(plan, true,
                merge.inserted(), merge.updated(), merge.deprecated(), logged.getId());
    }

    /**
     * Most recent sync attempts (any outcome), newest first. Backs the
     * admin UI "history" tab.
     */
    public List<ModelCatalogSyncLogEntity> recentHistory(int limit) {
        return syncLogRepo.findAllByOrderByCreatedAtDesc(PageRequest.of(0, limit));
    }

    // ── Feed fetch ──────────────────────────────────────────────────────────

    private record FetchedFeed(byte[] bytes, String checksum, String errorMessage) {
        boolean isError() { return bytes == null; }
        static FetchedFeed ok(byte[] b, String sha) { return new FetchedFeed(b, sha, null); }
        static FetchedFeed err(String msg) { return new FetchedFeed(null, null, msg); }
    }

    FetchedFeed fetchLiteLlm() {
        String url = String.format(liteLlmUrlTemplate, liteLlmCommitSha);
        try {
            HttpHeaders h = new HttpHeaders();
            h.set(HttpHeaders.USER_AGENT, "livecontext-catalog-sync/1.0");
            ResponseEntity<byte[]> resp = restTemplate.exchange(url, HttpMethod.GET,
                    new HttpEntity<>(h), byte[].class);
            byte[] body = resp.getBody();
            if (body == null || body.length == 0) {
                return FetchedFeed.err("LiteLLM feed empty from " + url);
            }
            return FetchedFeed.ok(body, sha256(body));
        } catch (Exception e) {
            return FetchedFeed.err("LiteLLM fetch failed from " + url + ": " + e.getMessage());
        }
    }

    FetchedFeed fetchOpenRouter() {
        try {
            HttpHeaders h = new HttpHeaders();
            h.set(HttpHeaders.USER_AGENT, "livecontext-catalog-sync/1.0");
            h.set(HttpHeaders.ACCEPT, "application/json");
            ResponseEntity<byte[]> resp = restTemplate.exchange(openRouterUrl, HttpMethod.GET,
                    new HttpEntity<>(h), byte[].class);
            byte[] body = resp.getBody();
            if (body == null || body.length == 0) {
                return FetchedFeed.err("OpenRouter feed empty");
            }
            return FetchedFeed.ok(body, sha256(body));
        } catch (Exception e) {
            return FetchedFeed.err("OpenRouter fetch failed: " + e.getMessage());
        }
    }

    // ── Guards ──────────────────────────────────────────────────────────────

    /**
     * Count-floor guard: each feed's new count must be ≥ 80% of its own last
     * successful baseline. Comparison is per-feed, not on the combined total,
     * so a degraded run (e.g. OpenRouter down, LiteLLM up) compares
     * LiteLLM's 485 against the last LiteLLM-only OK - NOT against a past
     * "both" run whose total was inflated by OpenRouter. A combined "both"
     * log row can't be decomposed into per-feed counts after the fact, so
     * the baseline lookup only considers prior runs whose source exactly
     * matches the current feed being checked.
     *
     * <p>First-ever-per-feed: no baseline → skip (same as original behavior).
     */
    private void runCountFloorGuard(LiteLlmFeedParser.ParseResult litellm,
                                    OpenRouterFeedParser.ParseResult orouter,
                                    Set<String> overrides,
                                    List<GuardFailure> failures) {
        if (overrides.contains(GUARD_COUNT_FLOOR)) return;

        if (litellm != null) {
            checkFeedCountFloor("litellm", litellm.models().size(), failures);
        }
        if (orouter != null) {
            checkFeedCountFloor("openrouter", orouter.models().size(), failures);
        }
    }

    private void checkFeedCountFloor(String feed, int currentCount, List<GuardFailure> failures) {
        // Baseline comes from the most recent OK run whose per-feed counter
        // is populated. A "both" run contributes its feed-specific count
        // (not the combined total), so degraded runs compare apples to
        // apples. Runs where this feed failed to fetch have NULL for that
        // counter and are correctly skipped by the JPA query.
        Optional<ModelCatalogSyncLogEntity> lastOk = "litellm".equals(feed)
                ? syncLogRepo.findFirstByOutcomeAndDryRunAndLiteLlmCountIsNotNullOrderByCreatedAtDesc(
                        ModelCatalogSyncLogEntity.Outcome.OK, Boolean.FALSE)
                : syncLogRepo.findFirstByOutcomeAndDryRunAndOpenRouterCountIsNotNullOrderByCreatedAtDesc(
                        ModelCatalogSyncLogEntity.Outcome.OK, Boolean.FALSE);

        if (lastOk.isEmpty()) {
            // First-ever OK run for this feed - no baseline to compare against.
            return;
        }

        Integer baselineInt = "litellm".equals(feed)
                ? lastOk.get().getLiteLlmCount()
                : lastOk.get().getOpenRouterCount();
        if (baselineInt == null) return;  // defensive - query filter guarantees non-null
        int baseline = baselineInt;
        BigDecimal floor = new BigDecimal(baseline).multiply(COUNT_FLOOR_RATIO)
                .setScale(0, RoundingMode.DOWN);

        if (new BigDecimal(currentCount).compareTo(floor) < 0) {
            failures.add(new GuardFailure(GUARD_COUNT_FLOOR,
                    feed + " feed size " + currentCount + " < 80% of last baseline " + baseline
                            + " (floor=" + floor + ")",
                    Map.of(
                            "feed",         feed,
                            "currentCount", currentCount,
                            "baseline",     baseline,
                            "floor",        floor.intValue(),
                            "ratio",        COUNT_FLOOR_RATIO.toString()
                    )));
        }
    }

    private void runPriceSanityGuard(List<Map<String, Object>> incoming,
                                     Map<String, ModelConfigOverrideEntity> existing,
                                     Set<String> overrides,
                                     List<FlaggedRow> flagged,
                                     List<GuardFailure> failures) {
        boolean overridden = overrides.contains(GUARD_PRICE_SANITY);

        for (Map<String, Object> m : incoming) {
            String prov = strOf(m.get("provider"));
            String mid  = strOf(m.get("modelId"));
            if (prov == null || mid == null) continue;

            ModelConfigOverrideEntity row = existing.get(key(prov, mid));
            if (row == null) continue;  // new model - no baseline to compare.

            BigDecimal oldIn = row.getPriceInput();
            BigDecimal oldOut = row.getPriceOutput();
            BigDecimal newIn = bigDec(m.get("priceInput"));
            BigDecimal newOut = bigDec(m.get("priceOutput"));

            String reason = null;
            // Zero-price anomaly (only dangerous if it WAS non-zero).
            if (newIn != null && newIn.signum() == 0 && oldIn != null && oldIn.signum() > 0) {
                reason = "priceInput dropped to 0 (was " + oldIn + ")";
            } else if (newOut != null && newOut.signum() == 0 && oldOut != null && oldOut.signum() > 0) {
                reason = "priceOutput dropped to 0 (was " + oldOut + ")";
            } else if (driftTooLarge(oldIn, newIn)) {
                reason = "priceInput changed >50% (" + oldIn + " → " + newIn + ")";
            } else if (driftTooLarge(oldOut, newOut)) {
                reason = "priceOutput changed >50% (" + oldOut + " → " + newOut + ")";
            }

            if (reason != null) {
                flagged.add(new FlaggedRow(prov, mid, reason, oldIn, newIn, oldOut, newOut));
            }
        }

        // One aggregate GuardFailure when flags exist and not overridden -
        // the REST controller maps this to a 412 Precondition Failed.
        if (!flagged.isEmpty() && !overridden) {
            failures.add(new GuardFailure(GUARD_PRICE_SANITY,
                    flagged.size() + " row(s) flagged - override with overrideGuards=price-sanity",
                    Map.of("flaggedCount", flagged.size())));
        }
    }

    private static boolean driftTooLarge(BigDecimal oldV, BigDecimal newV) {
        if (oldV == null || newV == null) return false;
        if (oldV.signum() == 0) return false;
        BigDecimal delta = newV.subtract(oldV).abs();
        BigDecimal ratio = delta.divide(oldV, 4, RoundingMode.HALF_UP);
        return ratio.compareTo(PRICE_SANITY_RATIO) > 0;
    }

    // ── Diff helpers ────────────────────────────────────────────────────────

    private Map<String, ModelConfigOverrideEntity> loadExistingNonBridge() {
        Map<String, ModelConfigOverrideEntity> out = new HashMap<>();
        for (ModelConfigOverrideEntity row : modelRepo.findAllByOrderByRankingAsc()) {
            if (isExcludedProvider(row.getProvider())) continue;
            if ("bridge".equals(row.getProviderKind())) continue;
            out.put(key(row.getProvider(), row.getModelId()), row);
        }
        return out;
    }

    /**
     * Does the feed row match the DB row, across every field the merge path
     * writes? This is what powers the UI's "added / updated / unchanged"
     * counts - if the equality lies, admins see a misleading diff.
     *
     * <p>Must stay aligned with {@code CatalogMergeService.applyFields} - any
     * field that applyFields writes must be compared here, or changes to that
     * field will be silently classified "unchanged" in the dry-run preview
     * while the apply still mutates it.
     *
     * <p>Fields NOT compared: {@code userModifiedFields}, {@code source},
     * {@code providerKind}, {@code bundleVersion}, {@code lastSyncedAt},
     * {@code feedMetadata}. These are bookkeeping, never authoritative diffs.
     * {@code displayName} is compared because the first insert stamps it, but
     * subsequent syncs preserve it through user_modified_fields semantics.
     */
    private static boolean rowEquals(ModelConfigOverrideEntity row, Map<String, Object> m) {
        return Objects.equals(row.getPriceInput(),         bigDec(m.get("priceInput")))
            && Objects.equals(row.getPriceOutput(),        bigDec(m.get("priceOutput")))
            && Objects.equals(row.getPriceInputBatch(),    bigDec(m.get("priceInputBatch")))
            && Objects.equals(row.getPriceOutputBatch(),   bigDec(m.get("priceOutputBatch")))
            && Objects.equals(row.getPriceCacheRead(),     bigDec(m.get("priceCacheRead")))
            && Objects.equals(row.getPriceCacheWrite(),    bigDec(m.get("priceCacheWrite")))
            && Objects.equals(row.getPriceFloorInput(),    bigDec(m.get("priceFloorInput")))
            && Objects.equals(row.getPriceFloorOutput(),   bigDec(m.get("priceFloorOutput")))
            && Objects.equals(row.getContextWindow(),      intOf(m.get("contextWindow")))
            && Objects.equals(row.getMaxOutputTokens(),    intOf(m.get("maxOutputTokens")))
            && Objects.equals(row.getSupportsTools(),      boolOf(m.get("supportsTools")))
            && Objects.equals(row.getSupportsVision(),     boolOf(m.get("supportsVision")))
            && Objects.equals(row.getSupportsPromptCaching(),   boolOf(m.get("supportsPromptCaching")))
            && Objects.equals(row.getSupportsReasoning(),       boolOf(m.get("supportsReasoning")))
            && Objects.equals(row.getSupportsComputerUse(),     boolOf(m.get("supportsComputerUse")))
            && Objects.equals(row.getSupportsResponseSchema(),  boolOf(m.get("supportsResponseSchema")))
            && Objects.equals(row.getSupportsWebSearch(),       boolOf(m.get("supportsWebSearch")))
            && Objects.equals(row.getTier(),               strOf(m.get("tier")))
            && Objects.equals(row.getMode(),               strOf(m.get("mode")))
            && Objects.equals(row.getDeprecationDate() != null ? row.getDeprecationDate().toString() : null,
                              strOf(m.get("deprecationDate")))
            && Objects.equals(row.getReleaseDate() != null ? row.getReleaseDate().toString() : null,
                              strOf(m.get("releaseDate")))
            && Objects.equals(row.getRateLimitTpm(),       intOf(m.get("rateLimitTpm")))
            && Objects.equals(row.getRateLimitRpm(),       intOf(m.get("rateLimitRpm")));
    }

    // ── Log writer ──────────────────────────────────────────────────────────

    private ModelCatalogSyncLogEntity writeLog(SyncRequest req, Instant fetchedAt,
                                               String source, int modelCount, String checksum,
                                               ModelCatalogSyncLogEntity.Outcome outcome,
                                               String errorDetail,
                                               Map<String, Object> guardFailuresJson,
                                               int added, int updated, int deprecated,
                                               int flagged,
                                               Integer liteLlmCount, Integer openRouterCount) {
        // Delegated to a Propagation.REQUIRES_NEW helper so a merge failure
        // in the enclosing sync() TX does NOT poison the log insert with
        // "current transaction is aborted".
        return syncLogWriter.write(source, fetchedAt, modelCount, checksum,
                req.triggeredBy(), req.dryRun(), outcome, errorDetail,
                guardFailuresJson, added, updated, deprecated, flagged,
                liteLlmCount, openRouterCount);
    }

    private SyncResult abortAndLog(SyncRequest req, Instant fetchedAt, String source,
                                   int modelCount, Object stats, String checksum,
                                   ModelCatalogSyncLogEntity.Outcome outcome,
                                   String detail, Map<String, Object> guardsJson) {
        // Fetch/parse-time abort: per-feed counts are unknown, stay null.
        ModelCatalogSyncLogEntity logged = writeLog(req, fetchedAt, source, modelCount,
                checksum, outcome, detail, guardsJson, 0, 0, 0, 0,
                null, null);
        SyncPlan emptyPlan = new SyncPlan(
                new FeedStats(0, 0, Map.of(), Map.of()),
                List.of(), List.of(), 0, List.of(),
                List.of(new GuardFailure("fetch-or-parse", detail, Map.of())));
        return new SyncResult(emptyPlan, false, 0, 0, 0, logged.getId());
    }

    // ── Formatters ──────────────────────────────────────────────────────────

    private static Map<String, Object> guardFailuresToJson(List<GuardFailure> failures,
                                                           List<FlaggedRow> flagged) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (!failures.isEmpty()) {
            List<Map<String, Object>> failList = new ArrayList<>();
            for (GuardFailure f : failures) {
                failList.add(Map.of("guard", f.guard(), "detail", f.detail(), "data", f.data()));
            }
            out.put("guards", failList);
        }
        if (!flagged.isEmpty()) {
            List<Map<String, Object>> flagList = new ArrayList<>();
            for (FlaggedRow r : flagged) {
                Map<String, Object> rm = new LinkedHashMap<>();
                rm.put("provider", r.provider());
                rm.put("modelId",  r.modelId());
                rm.put("reason",   r.reason());
                if (r.oldPriceInput() != null)  rm.put("oldPriceInput",  r.oldPriceInput());
                if (r.newPriceInput() != null)  rm.put("newPriceInput",  r.newPriceInput());
                if (r.oldPriceOutput() != null) rm.put("oldPriceOutput", r.oldPriceOutput());
                if (r.newPriceOutput() != null) rm.put("newPriceOutput", r.newPriceOutput());
                flagList.add(rm);
            }
            out.put("flaggedRows", flagList);
        }
        return out;
    }

    private static String describeGuards(List<GuardFailure> failures) {
        if (failures.isEmpty()) return null;
        StringBuilder sb = new StringBuilder();
        for (GuardFailure f : failures) {
            if (sb.length() > 0) sb.append(" ; ");
            sb.append(f.guard()).append(": ").append(f.detail());
        }
        return sb.toString();
    }

    private static String sourceTag(boolean litellmOk, boolean openRouterOk) {
        if (litellmOk && openRouterOk) return "both";
        if (litellmOk) return "litellm";
        if (openRouterOk) return "openrouter";
        return "none";
    }

    private static String key(String provider, String modelId) {
        return provider + '\0' + modelId;
    }

    /**
     * Null-safe membership check against {@link #EXCLUDED_PROVIDERS}. That set
     * is an immutable {@code Set.of(...)} which is null-hostile:
     * {@code contains(null)} throws NPE ("Cannot invoke Object.hashCode()")
     * rather than returning {@code false}. A feed row or DB row with a null
     * provider is simply "not excluded", so treat it as such.
     */
    private static boolean isExcludedProvider(String provider) {
        return provider != null && EXCLUDED_PROVIDERS.contains(provider);
    }

    private static String sha256(byte[] b) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(b);
            StringBuilder sb = new StringBuilder(d.length * 2);
            for (byte x : d) sb.append(String.format("%02x", x));
            return sb.toString();
        } catch (Exception e) {
            return null;
        }
    }

    private static String strOf(Object v) { return v == null ? null : v.toString(); }

    private static Boolean boolOf(Object v) {
        if (v == null) return null;
        if (v instanceof Boolean b) return b;
        return Boolean.parseBoolean(v.toString());
    }

    private static Integer intOf(Object v) {
        if (v == null) return null;
        if (v instanceof Number n) return n.intValue();
        try { return Integer.parseInt(v.toString()); }
        catch (NumberFormatException e) { return null; }
    }

    private static BigDecimal bigDec(Object v) {
        if (v == null) return null;
        try { return new BigDecimal(v.toString()); }
        catch (NumberFormatException e) { return null; }
    }
}
