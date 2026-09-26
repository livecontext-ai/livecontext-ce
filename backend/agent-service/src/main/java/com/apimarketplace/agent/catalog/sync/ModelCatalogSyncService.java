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
 *   <li>{@code ?mode=apply} - same as dry-run, then (if no BLOCKING guard
 *       fired, or it was explicitly overridden) calls
 *       {@link CatalogMergeService#merge} with
 *       {@link MergeOptions#forSync()} and stamps the sync-log row.</li>
 * </ul>
 *
 * <p><b>The two guards do not have the same power, and that is the point.</b>
 * {@code count-floor} judges the FEED: a response that lost a fifth of its rows
 * is not trustworthy as a whole, so it aborts the apply. {@code price-sanity}
 * judges ONE ROW against its own baseline, so it holds that row back and lets
 * every other row land.
 *
 * <p>Price-sanity used to abort as well, which quietly made a refresh
 * all-or-nothing: a single moved price cancelled the whole run, so the stored
 * baseline never advanced and the identical rows flagged again on every
 * subsequent run. The operator's only way out was the blanket override, which
 * pushes every flagged price through unread. Holding back just the flagged rows
 * turns the flag into a review queue: the refresh still lands, and an operator
 * who has read the rows re-runs with {@code overrideGuards=price-sanity} to
 * accept them.
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

    /**
     * Providers we NEVER touch from the sync: the 4 CLI bridges. Their rows are
     * DERIVED from the cloud entries by {@link BridgeModelDeriver} (after this
     * filter runs), so letting a feed write them directly would fight the
     * deriver.
     *
     * <p>{@code zai} used to sit here on the assumption it was absent from
     * LiteLLM. It is not: the feed carries the full GLM line (glm-4.5 through
     * glm-5.1), so excluding it froze Z.AI on the three ids hardcoded in
     * {@code application.yml}.
     */
    static final Set<String> EXCLUDED_PROVIDERS = Set.of(
            "claude-code", "codex", "gemini-cli", "mistral-vibe");

    /** Guard names operators can pass in {@code overrideGuards=}. */
    public static final String GUARD_COUNT_FLOOR  = "count-floor";
    public static final String GUARD_PRICE_SANITY = "price-sanity";

    /** 0.8 - feed is rejected if it drops below 80% of the last successful snapshot. */
    private static final BigDecimal COUNT_FLOOR_RATIO = new BigDecimal("0.8");

    /**
     * First half of the price-sanity test: the move must be more than 50% of
     * the old price. On its own it flags nothing - see
     * {@link #PRICE_SANITY_MIN_ABSOLUTE_DELTA}, which the move must clear as
     * well - so this constant does not describe a threshold a reader can act
     * on by itself.
     */
    private static final BigDecimal PRICE_SANITY_RATIO = new BigDecimal("0.5");

    /**
     * Second half of the price-sanity test: the move must ALSO be worth at
     * least this many dollars per million tokens.
     *
     * <p>A ratio alone cannot tell a repricing from a rounding wobble, because
     * the cheapest rows are where a large percentage is worth nothing. Measured
     * on a real refresh, {@code tencent/hy3} moved 0.0825 to 0.1320: a 60%
     * jump, and five cents per million tokens. Flagging that says "a human must
     * look at this" about a number no human decision depends on, and the cost
     * of a noisy flag is not zero: it trains the reader to wave the list
     * through, which is exactly when the one real anomaly gets waved through
     * with it.
     *
     * <p>Deliberately conservative. At 0.10 the rule drops only what is too
     * small to act on: on the five rows of the refresh that prompted this, one
     * is silenced and four still flag. A bigger floor would start hiding real
     * repricings of cheap models, which are precisely the ones a budget is
     * built on.
     */
    private static final BigDecimal PRICE_SANITY_MIN_ABSOLUTE_DELTA = new BigDecimal("0.10");

    private final LiteLlmFeedParser liteLlmParser;
    private final OpenRouterFeedParser openRouterParser;
    private final BridgeModelDeriver bridgeModelDeriver;
    private final NativeModelDiscoveryService discoveryService;
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

    /**
     * Full sync plan - shown to the admin in dry-run mode.
     *
     * <p>{@code discovery} reports the third source (each provider's own
     * {@code /models} endpoint). Its rows are already counted inside
     * {@code added}; the separate breakdown is what tells an admin that the
     * new rows need a price before they can be enabled, and which providers
     * were skipped for want of a key.
     */
    public record SyncPlan(FeedStats stats,
                           List<Map<String, Object>> added,
                           List<Map<String, Object>> updated,
                           int unchanged,
                           List<FlaggedRow> flagged,
                           List<GuardFailure> guardFailures,
                           NativeModelDiscoveryService.DiscoveryResult discovery) {}

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

        // Safety: remove any row under an excluded provider (the CLI bridges).
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

        // 3b. Third source: ask each configured provider what it actually serves.
        // Both feeds are third-party mirrors and lag per vendor (Z.AI shipped
        // glm-5.2/5.3 and Moonshot shipped Kimi K3 while LiteLLM's blocks still
        // ended at glm-5.1 / kimi-k2.6), so a mirror-only catalog is
        // permanently behind for the fastest-moving vendors. Discovery only
        // fills gaps: anything a feed already covers is suppressed, and the
        // rows it emits carry NO price by construction - see
        // NativeModelDiscoveryService for the authority split.
        NativeModelDiscoveryService.DiscoveryResult discovery;
        try {
            discovery = discoveryService.discover(
                    allFeedModels, existing.keySet(),
                    orouter != null ? orouter.models() : List.of(),
                    litellm != null ? litellm.declinedIds() : Set.of());
        } catch (Exception e) {
            // A vendor endpoint misbehaving must never fail a catalog refresh:
            // the feeds' contribution is already computed and still valid.
            log.warn("catalog-sync: native discovery pass failed, continuing with feeds only", e);
            discovery = NativeModelDiscoveryService.DiscoveryResult.empty();
        }
        allFeedModels.addAll(discovery.models());

        // 4. Guards.
        List<GuardFailure> guardFailures = new ArrayList<>();

        // 4a. Count-floor per feed.
        runCountFloorGuard(litellm, orouter, req.overrideGuards(), guardFailures);

        // 4b. Price-sanity per incoming row vs existing. Unlike count-floor
        // this is a PER-ROW guard and never blocks the run: it collects the
        // rows to hold back, and step 7 applies everything else.
        List<FlaggedRow> flagged = new ArrayList<>();
        runPriceSanityGuard(allFeedModels, existing, flagged);

        // 5. Build the diff buckets.
        List<Map<String, Object>> added = new ArrayList<>();
        List<Map<String, Object>> updatedModels = new ArrayList<>();
        int unchanged = 0;

        // Pre-compute flagged keys to drop from apply when sanity not overridden.
        // Withholding the row is the only EFFECT a price-sanity flag has on
        // the apply; the flag itself also travels in the plan and in the
        // sync-log row, which is how an operator gets to review it. It used to be
        // dead code: an aggregate GuardFailure made step 6 return before step 7
        // could ever read this set, so one flagged row cancelled the entire
        // refresh and the DB baseline never moved. The same rows then flagged
        // again on the next run, and the next, until an operator ticked the
        // override and pushed every flagged row through at once.
        Set<String> flaggedKeys = new HashSet<>();
        if (!req.overrideGuards().contains(GUARD_PRICE_SANITY)) {
            for (FlaggedRow f : flagged) flaggedKeys.add(key(f.provider(), f.modelId()));
        }

        for (Map<String, Object> m : allFeedModels) {
            String prov = strOf(m.get("provider"));
            String mid  = strOf(m.get("modelId"));
            if (prov == null || mid == null) continue;
            ModelConfigOverrideEntity row = existing.get(key(prov, mid));
            if (row != null && row.isRetired()) {
                continue; // the merge leaves it untouched (V533), so the preview must not count it
            }
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

        SyncPlan plan = new SyncPlan(stats, added, updatedModels, unchanged, flagged, guardFailures, discovery);

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
                    guardFailuresToJson(guardFailures, flagged, flaggedKeys.size()),
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
                    e.getMessage(), guardFailuresToJson(List.of(), flagged, flaggedKeys.size()),
                    0, 0, 0, flagged.size(),
                    liteLlmCount, openRouterCount);
            return new SyncResult(plan, false, 0, 0, 0, logged.getId());
        }

        // 8. Write successful log row.
        ModelCatalogSyncLogEntity logged = writeLog(req, fetchedAt, sourceTag,
                allFeedModels.size(), liteLlmFeed.checksum(),
                ModelCatalogSyncLogEntity.Outcome.OK, null,
                guardFailuresToJson(List.of(), flagged, flaggedKeys.size()),
                merge.inserted(), merge.updated(), merge.deprecated(), flagged.size(),
                liteLlmCount, openRouterCount);

        // flaggedKeys, not flagged: with overrideGuards=price-sanity the flagged
        // rows went THROUGH, and reporting them as skipped there described the
        // opposite of what the run did.
        log.info("catalog-sync applied: inserted={}, updated={}, deprecated={}, flagged={}, flaggedWithheld={}, syncLogId={}",
                merge.inserted(), merge.updated(), merge.deprecated(),
                flagged.size(), flaggedKeys.size(), logged.getId());

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

    /**
     * Flag rows whose price moved enough to want a human decision.
     *
     * <p>Collects, and does not block. A flagged row is held back from the
     * apply (see {@code flaggedKeys}); every other row lands. The operator
     * pushes the held rows through on a later run with
     * {@code overrideGuards=price-sanity}.
     *
     * <p>That split matters because the two guards answer different questions.
     * {@code count-floor} asks "is this feed response trustworthy at all",
     * which is about the WHOLE payload, so it aborts. Price-sanity asks "did
     * this one model rate move in a way worth reading", which is about ONE row
     * and says nothing about the other three hundred. Letting the second abort
     * the run made a refresh all-or-nothing: the operator could apply nothing,
     * or tick the override and accept every flagged price unread. Neither is a
     * review.
     */
    private void runPriceSanityGuard(List<Map<String, Object>> incoming,
                                     Map<String, ModelConfigOverrideEntity> existing,
                                     List<FlaggedRow> flagged) {
        for (Map<String, Object> m : incoming) {
            String prov = strOf(m.get("provider"));
            String mid  = strOf(m.get("modelId"));
            if (prov == null || mid == null) continue;

            ModelConfigOverrideEntity row = existing.get(key(prov, mid));
            if (row == null) continue;  // new model - no baseline to compare.
            if (row.isRetired()) continue; // never merged (V533), so never withheld either

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
                reason = "priceInput " + driftLabel() + " (" + oldIn + " → " + newIn + ")";
            } else if (driftTooLarge(oldOut, newOut)) {
                reason = "priceOutput " + driftLabel() + " (" + oldOut + " → " + newOut + ")";
            }

            if (reason != null) {
                flagged.add(new FlaggedRow(prov, mid, reason, oldIn, newIn, oldOut, newOut));
            }
        }

        // No aggregate GuardFailure is emitted, on purpose. guardFailures now
        // means exactly "this run must not apply", which is the condition the
        // REST layer turns into a 412, so price-sanity has no business in that
        // list: it holds back rows, not the run. The flagged rows travel in the
        // plan and in the sync-log row instead, where they are a review queue
        // rather than a stop sign.
    }

    /**
     * Has this price moved enough, BOTH in proportion and in money, to want a
     * human decision?
     *
     * <p>Both tests must pass, which is a deliberate trade and not a free one.
     * The ratio catches the SHAPE of an anomaly; the absolute floor rules out
     * the rows where that shape costs nothing. Keeping only the ratio flags
     * five-cent moves on budget models, and a list nobody can finish reading
     * is how the one real anomaly gets waved through.
     *
     * <p>What it gives up, stated plainly: a huge proportional jump on a very
     * cheap model goes unflagged when the money is small. {@code 0.01 → 0.10}
     * is +900% and passes silently, because the delta is one cent under the
     * floor. That is accepted because the flag exists to provoke a human
     * decision, and there is no decision to take about a cent. A model whose
     * rate matters will cross the floor long before it matters.
     */
    private static boolean driftTooLarge(BigDecimal oldV, BigDecimal newV) {
        if (oldV == null || newV == null) return false;
        if (oldV.signum() == 0) return false;
        BigDecimal delta = newV.subtract(oldV).abs();
        if (delta.compareTo(PRICE_SANITY_MIN_ABSOLUTE_DELTA) < 0) return false;
        BigDecimal ratio = delta.divide(oldV, 4, RoundingMode.HALF_UP);
        return ratio.compareTo(PRICE_SANITY_RATIO) > 0;
    }

    /**
     * The whole rule as a label for the flag text, BOTH halves of it, derived
     * from the constants rather than written out.
     *
     * <p>Naming only the ratio was worse than imprecise: two rows can move by
     * the same percentage with only one of them flagged, and an operator
     * reading "changed >50%" on one and nothing on the other has no way to see
     * why. The message has to state the test the row actually failed.
     */
    private static String driftLabel() {
        return "changed >" + percent(PRICE_SANITY_RATIO)
                + "% and >=" + PRICE_SANITY_MIN_ABSOLUTE_DELTA.stripTrailingZeros().toPlainString()
                + "/M";
    }

    private static String percent(BigDecimal ratio) {
        return ratio.multiply(new BigDecimal("100")).stripTrailingZeros().toPlainString();
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
     *
     * <p>Also excluded, and the reason matters: {@code enabled},
     * {@code ranking}, {@code recommended}, {@code canonicalId},
     * {@code rateLimitTpmPerTenant}/{@code RpmPerTenant} and {@code modalities}
     * are local/admin state that NO feed carries. Comparing them would report a
     * permanent phantom "updated" on every row an admin has ever touched. The
     * honest caveat: because {@code MergeOptions.forSync} uses
     * {@code partialUpdate=false}, {@code applyFields} still nulls those on an
     * UNPROTECTED row, and this method hides that. {@code enabled} is the
     * exception: a feed sync never writes it on update (a nulled enabled reads
     * as ON, which auto-exposed models inserted disabled). In practice the admin UI
     * marks exactly these as {@code user_modified_fields} when it writes them,
     * which is what actually protects them - not this exclusion list.
     *
     * <p>{@code displayName} IS compared, because {@code applyFields} writes it
     * and OpenRouter's value is feed-controlled: the parser takes the feed's
     * {@code name} verbatim ("Anthropic: Claude Sonnet 4"), so a provider-side
     * rebrand changes it with every other field stable. Leaving it out let that
     * rename report "unchanged" in the dry-run while the apply performed it,
     * exactly what the paragraph above forbids. (LiteLLM carries no name, so its
     * rows stamp the raw model id and are stable by construction.)
     *
     * <p>Consequence worth knowing: a row whose human name is protected by
     * {@code user_modified_fields} - the curated Kimi/Qwen rows V416 backfills -
     * has a DB name that permanently differs from the feed's, so it reports
     * "updated" on every dry-run even though the apply preserves the name. That
     * is honest (the row does go through the update path) and preferable to a
     * silent rename.
     */
    private static boolean rowEquals(ModelConfigOverrideEntity row, Map<String, Object> m) {
        return Objects.equals(row.getDisplayName(),        strOf(m.get("displayName")))
            && Objects.equals(row.getDescription(),        strOf(m.get("description")))
            && Objects.equals(strList(row.getSupportedEndpoints()),
                              strList(m.get("supportedEndpoints")))
            && Objects.equals(strList(row.getSupportedModalities()),
                              strList(m.get("supportedModalities")))
            && Objects.equals(strList(row.getSupportedOutputModalities()),
                              strList(m.get("supportedOutputModalities")))
            && Objects.equals(row.getPriceInput(),         bigDec(m.get("priceInput")))
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

    /**
     * Normalise the two shapes the endpoint/modality fields take so they can be
     * compared: the entity stores {@code String[]} (whose {@code equals} is
     * identity, hence useless to {@code Objects.equals}) while a feed map
     * carries the raw JSON collection.
     *
     * <p>MUST mirror {@code CatalogMergeService.stringArrayOf}, which is what
     * actually lands in the column: same {@code Collection} (not just
     * {@code List}) acceptance and the same null-element filtering. Diverge and
     * the row reports "updated" forever, because the value written on apply
     * would never compare equal to the value read back. LiteLLM passes
     * {@code supported_endpoints} / {@code supported_modalities} through raw
     * from Jackson, so a JSON {@code null} inside the array really can reach
     * here.
     */
    private static List<String> strList(Object v) {
        if (v instanceof String[] arr) return Arrays.asList(arr);
        if (v instanceof Collection<?> col) {
            return col.stream().filter(Objects::nonNull).map(Object::toString).toList();
        }
        return null;
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
                List.of(new GuardFailure("fetch-or-parse", detail, Map.of())),
                NativeModelDiscoveryService.DiscoveryResult.empty());
        return new SyncResult(emptyPlan, false, 0, 0, 0, logged.getId());
    }

    // ── Formatters ──────────────────────────────────────────────────────────

    /**
     * Build the {@code guard_failures} JSONB payload for the sync-log row.
     *
     * @param withheld how many of {@code flagged} were actually kept OUT of the
     *        apply. Persisted because the two price-sanity outcomes are
     *        otherwise indistinguishable after the fact: a withheld run and an
     *        overridden run both write {@code OK} with the same
     *        {@code flagged_count} and the same rows. While price-sanity still
     *        aborted, {@code OK} plus flags could only mean "overridden", so the
     *        row was unambiguous by accident; making the guard non-blocking
     *        removed that accident, and the log is what is left once the
     *        response is gone.
     *        <p>Written on every row that carries flags, including dry-run,
     *        {@code ABORTED_GUARD} and {@code APPLY_ERROR} rows where no apply
     *        happened at all - and it reads most misleadingly on the last of
     *        those, where the run got past the guards and then failed anyway.
     *        There it reads as "would have been withheld"; the {@code dry_run}
     *        and {@code outcome} columns are what say whether anything ran.
     */
    private static Map<String, Object> guardFailuresToJson(List<GuardFailure> failures,
                                                           List<FlaggedRow> flagged,
                                                           int withheld) {
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
            // Always written alongside the rows, never conditionally: a reader
            // has to be able to tell 0-withheld from "this build did not record
            // it", and an absent key answers neither.
            out.put("flaggedWithheld", withheld);
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

    /**
     * The keys this service builds are handed to
     * {@link NativeModelDiscoveryService#discover} as {@code existingKeys}, so
     * both sides MUST agree on the separator. Delegating rather than repeating
     * the format is what makes that structural instead of a convention two
     * classes are trusted to remember.
     */
    private static String key(String provider, String modelId) {
        return NativeModelDiscoveryService.key(provider, modelId);
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
