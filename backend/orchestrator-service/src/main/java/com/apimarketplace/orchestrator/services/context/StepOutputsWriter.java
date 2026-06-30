package com.apimarketplace.orchestrator.services.context;

import com.apimarketplace.orchestrator.domain.workflow.WorkflowPlan;
import com.apimarketplace.orchestrator.utils.ConcurrentLruCache;
import com.apimarketplace.orchestrator.utils.LabelNormalizer;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Centralized writer for the {@code stepOutputs} map.
 *
 * <p>Background: the orchestrator's template engine resolves both the full-prefixed
 * node key ({@code mcp:read_email}) AND the bare alias ({@code read_email}) for every
 * step output, depending on whether the template uses the prefix or not. Both lookups
 * MUST return the same value - otherwise a downstream node sees stale data, exactly
 * the symptom of the Daily Email Digest split bug (2026-05-09 commit {@code aa9616b7d}).
 *
 * <p>Before this writer, six sites across {@code V2StepByStepContextManager},
 * {@code SplitAwareNodeExecutor} and {@code RunContextService} each had their own
 * inline copy of {@code put(fullKey, value); String alias = ...; if (alias != null) put(alias, value)}.
 * Each new feature touching stepOutputs had to remember the alias write - a discipline
 * that broke prod three times in seven days. With this writer, the contract is
 * enforced at one spot and ArchUnit forbids direct {@code stepOutputs.put} elsewhere.
 *
 * <p>The class is intentionally stateless: {@link #writeWithAlias} mutates the
 * caller's map and returns nothing. The mémoïzed {@link #aliasMapping(WorkflowPlan)}
 * cache is process-wide (bounded LRU) - plans are immutable per run, so the planId
 * is a safe cache key.
 */
public final class StepOutputsWriter {

    // ── Bounded LRU cache for plan → alias mapping ──────────────────────────────
    // Each run has one plan. Building the alias map is O(N) over all plan nodes,
    // and `getOrCreateContextWithTriggerData` is called on the hot path → memoize.
    private static final int ALIAS_MAPPING_CACHE_MAX = 1_000;
    private static final ConcurrentLruCache<String, Map<String, String>> ALIAS_MAPPING_CACHE =
        new ConcurrentLruCache<>(ALIAS_MAPPING_CACHE_MAX);

    // Memoized hasSplit predicate - plan.getSplitLoops() walks all cores + edges and is
    // called per context-build on the hot path. Audited as MUST-FIX (audit #3).
    private static final ConcurrentLruCache<String, Boolean> HAS_SPLIT_CACHE =
        new ConcurrentLruCache<>(ALIAS_MAPPING_CACHE_MAX);

    private StepOutputsWriter() {}

    /**
     * Extract the bare alias from a fully-prefixed node key.
     *
     * <p>Examples: {@code mcp:read_email → read_email}, {@code core:my_loop → my_loop},
     * {@code read_email → null} (no prefix, nothing to extract), {@code mcp: → null}.
     *
     * <p>This is THE definitive alias extractor - replaces the inline
     * {@code substring(indexOf(':') + 1)} pattern duplicated in 3 prior locations.
     *
     * @return the bare alias, or {@code null} if the key has no colon-separated
     *         prefix (caller should treat that as "no companion write needed").
     */
    public static String bareAlias(String fullKey) {
        if (fullKey == null || fullKey.isEmpty()) return null;
        int colonIndex = fullKey.indexOf(':');
        if (colonIndex < 0 || colonIndex >= fullKey.length() - 1) return null;
        return fullKey.substring(colonIndex + 1);
    }

    /**
     * Write a step output under BOTH its full key and its bare alias.
     *
     * <p>The two writes are atomic from the perspective of any reader: a subsequent
     * {@code stepOutputs.get(fullKey)} and {@code stepOutputs.get(alias)} will return
     * the same reference. This is the contract that the inline duplications kept
     * forgetting.
     *
     * <p>If the key has no prefix (no colon), only the key itself is written - there
     * is no alias to mirror.
     *
     * <p>If {@code outputs} is null or {@code fullKey} is null, the call is a no-op.
     */
    public static void writeWithAlias(Map<String, Object> outputs, String fullKey, Object value) {
        if (outputs == null || fullKey == null) return;
        outputs.put(fullKey, value);
        String alias = bareAlias(fullKey);
        if (alias != null && !alias.equals(fullKey)) {
            outputs.put(alias, value);
        }
    }

    /**
     * Normalize wrongly-prefixed entries already present in {@code outputs}.
     *
     * <p>Storage rows can land with an incorrect prefix - e.g. a trigger output written
     * under {@code mcp:start} when the plan declares {@code trigger:start}. After loading
     * fresh from DB, V2StepByStepContextManager called this normalization step to add
     * an entry under the correct full key (without removing the legacy entry).
     *
     * <p>This method preserves that exact semantic: for every existing entry, if its
     * bare alias resolves (via {@code aliasMap}) to a different full key not yet in
     * {@code outputs}, write it. Then mirror that new full-key write under the alias too.
     *
     * @param outputs  the stepOutputs map (will be mutated)
     * @param aliasMap normalized-alias → correct-full-key (built from the workflow plan)
     */
    public static void normalizeWrongPrefixes(Map<String, Object> outputs, Map<String, String> aliasMap) {
        normalizeWrongPrefixes(outputs, aliasMap, null);
    }

    /**
     * Overload that records a {@code normalization} alias-collision metric every time a
     * wrong-prefix entry is auto-corrected. Useful for detecting upstream storage drift
     * (e.g. a producer writing under {@code mcp:start} when the plan declares
     * {@code trigger:start}) - a sudden spike means the producer regressed.
     */
    public static void normalizeWrongPrefixes(Map<String, Object> outputs, Map<String, String> aliasMap,
                                              java.util.function.Consumer<String> collisionRecorder) {
        if (outputs == null || aliasMap == null || aliasMap.isEmpty()) return;
        // Snapshot to avoid ConcurrentModificationException - outputs is mutated below.
        Map<String, Object> snapshot = new HashMap<>(outputs);
        for (Map.Entry<String, Object> entry : snapshot.entrySet()) {
            String key = entry.getKey();
            String alias = bareAlias(key);
            if (alias == null) alias = key;
            String correctKey = aliasMap.get(alias);
            if (correctKey != null && !correctKey.equals(key) && !outputs.containsKey(correctKey)) {
                writeWithAlias(outputs, correctKey, entry.getValue());
                if (collisionRecorder != null) {
                    collisionRecorder.accept("normalization");
                }
            }
        }
    }

    /**
     * Build (or fetch from cache) the alias → full-key mapping for a workflow plan.
     *
     * <p>The plan is immutable for the lifetime of a run, so this mapping is memoized
     * by {@code plan.getId()}. The LRU cap protects against cardinality leaks if many
     * one-off plans are generated (e.g. tests, hot-edits in builder).
     *
     * <p>This is the same logic that lived in {@code V2StepByStepContextManager.buildAliasMapping}
     * (now delegated here).
     */
    public static Map<String, String> aliasMapping(WorkflowPlan plan) {
        Objects.requireNonNull(plan, "plan");
        String planId = plan.getId();
        if (planId == null || planId.isBlank()) {
            // Anonymous plan (tests, transient): compute without caching.
            return computeAliasMapping(plan);
        }
        return ALIAS_MAPPING_CACHE.computeIfAbsent(planId, k -> computeAliasMapping(plan));
    }

    private static Map<String, String> computeAliasMapping(WorkflowPlan plan) {
        Map<String, String> mapping = new HashMap<>();

        if (plan.getTriggers() != null) {
            for (var trigger : plan.getTriggers()) {
                String label = trigger.label() != null ? trigger.label() : trigger.id();
                String normalizedLabel = LabelNormalizer.normalizeLabel(label);
                if (normalizedLabel != null) {
                    mapping.put(normalizedLabel, trigger.getNormalizedKey());
                }
            }
        }
        if (plan.getMcps() != null) {
            for (var step : plan.getMcps()) {
                String normalizedLabel = LabelNormalizer.normalizeLabel(step.label());
                if (normalizedLabel != null) {
                    mapping.put(normalizedLabel, step.getNormalizedKey());
                }
            }
        }
        if (plan.getAgents() != null) {
            for (var agent : plan.getAgents()) {
                String normalizedLabel = LabelNormalizer.normalizeLabel(agent.label());
                if (normalizedLabel != null) {
                    mapping.put(normalizedLabel, agent.getNormalizedKey());
                }
            }
        }
        if (plan.getCores() != null) {
            for (var core : plan.getCores()) {
                String label = core.label() != null ? core.label() : core.id();
                String normalizedLabel = LabelNormalizer.normalizeLabel(label);
                if (normalizedLabel != null) {
                    mapping.put(normalizedLabel, core.getNormalizedKey());
                }
            }
        }
        if (plan.getTables() != null) {
            for (var table : plan.getTables()) {
                String normalizedLabel = LabelNormalizer.normalizeLabel(table.label());
                if (normalizedLabel != null) {
                    // Tables use "table:" prefix; Step.getNormalizedKey() returns "mcp:" - override.
                    mapping.put(normalizedLabel, "table:" + normalizedLabel);
                }
            }
        }
        if (plan.getInterfaces() != null) {
            for (var iface : plan.getInterfaces()) {
                String normalizedLabel = LabelNormalizer.normalizeLabel(iface.label());
                if (normalizedLabel != null) {
                    mapping.put(normalizedLabel, iface.getNormalizedKey());
                }
            }
        }

        return mapping;
    }

    /**
     * Memoized {@code plan.getSplitLoops().isEmpty() == false} predicate. Computing it
     * walks every core + every edge (see {@code WorkflowPlanAnalyzer.detectSplitLoops}),
     * and it's called on the context-build hot path. Plan is immutable per run → safe to
     * cache by planId.
     *
     * <p>Anonymous plans (null/blank id) skip cache and compute every time - same policy
     * as {@link #aliasMapping}.
     */
    public static boolean planHasSplit(WorkflowPlan plan) {
        if (plan == null) return false;
        String planId = plan.getId();
        if (planId == null || planId.isBlank()) {
            return computeHasSplit(plan);
        }
        return HAS_SPLIT_CACHE.computeIfAbsent(planId, k -> computeHasSplit(plan));
    }

    private static boolean computeHasSplit(WorkflowPlan plan) {
        Map<String, com.apimarketplace.orchestrator.domain.workflow.SplitLoop> splits = plan.getSplitLoops();
        return splits != null && !splits.isEmpty();
    }

    /**
     * Clear the alias-mapping cache. Test-only entry point - the cache is otherwise
     * left to LRU-evict naturally.
     */
    static void clearCacheForTesting() {
        ALIAS_MAPPING_CACHE.clear();
        HAS_SPLIT_CACHE.clear();
    }

    static int cacheSizeForTesting() {
        return ALIAS_MAPPING_CACHE.size();
    }
}
