package com.apimarketplace.orchestrator.services.context;

import com.apimarketplace.common.storage.domain.StorageEntity;
import com.apimarketplace.common.storage.repository.StorageRepository;
import com.apimarketplace.orchestrator.metrics.WorkflowMetrics;
import com.apimarketplace.orchestrator.services.TemplateEngine;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/**
 * Centralized service for extracting and managing workflow run context.
 *
 * This service provides:
 * 1. Efficient context loading from storage (single query per run)
 * 2. SpEL-compatible context map construction
 * 3. Expression evaluation using TemplateEngine
 * 4. Support for step-by-step execution context reconstruction
 *
 * All queries are epoch-aware: only storage entries matching the current epoch
 * are returned. This prevents reusable triggers (chat/webhook) from seeing
 * stale data from previous epochs.
 *
 * Context structure for SpEL evaluation:
 * {
 *   "mcp:enricher": { "output": { ... } },
 *   "trigger:webhook": { "output": { ... } },
 *   "agent:assistant": { "output": { ... } }
 * }
 */
@Service
public class RunContextService {

    private static final Logger logger = LoggerFactory.getLogger(RunContextService.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();

    private final StorageRepository storageRepository;
    private final TemplateEngine templateEngine;
    private final WorkflowMetrics metrics;

    @Autowired
    public RunContextService(StorageRepository storageRepository, TemplateEngine templateEngine,
                             WorkflowMetrics metrics) {
        this.storageRepository = storageRepository;
        this.templateEngine = templateEngine;
        this.metrics = metrics;
    }

    /**
     * Test-only constructor - bypasses the metrics dependency.
     */
    public RunContextService(StorageRepository storageRepository, TemplateEngine templateEngine) {
        this(storageRepository, templateEngine, null);
    }

    /**
     * Load the complete context for a workflow run, filtered by epoch.
     * Returns a Map suitable for SpEL evaluation with TemplateEngine.
     *
     * @param runId The workflow run ID
     * @param tenantId The tenant ID
     * @param epoch The trigger epoch number
     * @return Map with step keys as keys and output data as values
     */
    @Transactional(readOnly = true)
    public Map<String, Object> loadRunContext(String runId, String tenantId, int epoch) {
        return loadRunContext(runId, tenantId, epoch, 0);
    }

    /**
     * Load the complete context for a workflow run, filtered by epoch and spawn.
     *
     * <p>Uses the latest-spawn query: for each step_key, returns the most recent
     * spawn <= maxSpawn. This allows rerun nodes (spawn=1+) to see predecessor
     * outputs from spawn=0 while using fresh outputs from re-executed nodes.
     *
     * <p>Also builds a context provenance map recording which spawn each step's
     * output came from, enabling full traceability of context sources.
     *
     * @param runId The workflow run ID
     * @param tenantId The tenant ID
     * @param epoch The trigger epoch number
     * @param spawn The maximum spawn to consider (inclusive)
     * @return Map with step keys as keys and output data as values
     */
    @Transactional(readOnly = true)
    public Map<String, Object> loadRunContext(String runId, String tenantId, int epoch, int spawn) {
        if (runId == null || tenantId == null) {
            logger.warn("[RunContext] Cannot load context: runId={}, tenantId={}", runId, tenantId);
            return Map.of();
        }

        logger.debug("[RunContext] Loading context for runId={}, epoch={}, spawn={}", runId, epoch, spawn);

        List<StorageEntity> storages;
        if (spawn > 0) {
            storages = storageRepository.findByRunIdAndEpochWithLatestSpawn(runId, epoch, spawn, tenantId);
        } else {
            storages = storageRepository.findByRunIdAndEpoch(runId, epoch, tenantId);
        }

        if (storages.isEmpty()) {
            logger.debug("[RunContext] No storage entries found for runId={}, epoch={}, spawn={}", runId, epoch, spawn);
            return Map.of();
        }

        Map<String, Object> context = buildContextFromStorages(storages);

        // Build context provenance: which spawn each step_key's output came from
        if (spawn > 0) {
            Map<String, Integer> provenance = new LinkedHashMap<>();
            for (StorageEntity storage : storages) {
                if (storage.getStepKey() != null) {
                    provenance.put(storage.getStepKey(), storage.getSpawn());
                }
            }
            if (!provenance.isEmpty()) {
                context.put("__contextProvenance", provenance);
                logger.debug("[RunContext] Context provenance: {}", provenance);
            }
        }

        logger.info("[RunContext] Loaded context with {} step outputs for runId={}, epoch={}, spawn={}",
            context.size(), runId, epoch, spawn);
        return context;
    }

    /**
     * Load context for a specific item index (for loop/split scenarios), filtered by epoch.
     *
     * @param runId The workflow run ID
     * @param tenantId The tenant ID
     * @param epoch The trigger epoch number
     * @param itemIndex The item index to filter by
     * @return Map with step keys as keys and output data as values
     */
    @Transactional(readOnly = true)
    public Map<String, Object> loadRunContextForItem(String runId, String tenantId, int epoch, int itemIndex) {
        if (runId == null || tenantId == null) {
            return Map.of();
        }

        logger.debug("[RunContext] Loading context for runId={}, epoch={}, itemIndex={}", runId, epoch, itemIndex);

        List<StorageEntity> storages = storageRepository.findByRunIdAndEpoch(runId, epoch, tenantId);
        return buildPerItemContext(storages, itemIndex);
    }

    /**
     * Load context for a specific item index with spawn awareness, filtered by epoch.
     * When spawn > 0, uses latest-spawn query to get the correct context for that spawn.
     *
     * @param runId The workflow run ID
     * @param tenantId The tenant ID
     * @param epoch The trigger epoch number
     * @param spawn The spawn number (re-execution counter within epoch)
     * @param itemIndex The item index to filter by
     * @return Map with step keys as keys and output data as values
     */
    @Transactional(readOnly = true)
    public Map<String, Object> loadRunContextForItem(String runId, String tenantId, int epoch, int spawn, int itemIndex) {
        if (runId == null || tenantId == null) {
            return Map.of();
        }

        logger.debug("[RunContext] Loading context for runId={}, epoch={}, spawn={}, itemIndex={}", runId, epoch, spawn, itemIndex);

        List<StorageEntity> storages;
        if (spawn > 0) {
            storages = storageRepository.findByRunIdAndEpochWithLatestSpawn(runId, epoch, spawn, tenantId);
        } else {
            storages = storageRepository.findByRunIdAndEpoch(runId, epoch, tenantId);
        }

        return buildPerItemContext(storages, itemIndex);
    }

    /**
     * Load context for a specific item index, restricted to the requested step-key tokens.
     *
     * <p>This is the non-SpEL companion to {@link #evaluateExpressionsForItemNarrowed}: callers
     * that already know the step keys they need can avoid the unbounded
     * {@link StorageRepository#findByRunIdAndEpoch} path and still get the same full-key + alias
     * context shape as {@link #loadRunContextForItem}.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> loadRunContextForItemNarrowed(String runId, String tenantId, int epoch, int spawn, int itemIndex,
                                                             Collection<String> stepKeyTokens, int maxRowBytes, int maxCollectionSize) {
        if (runId == null || tenantId == null || stepKeyTokens == null || stepKeyTokens.isEmpty()) {
            return Map.of();
        }
        if (spawn > 0) {
            return loadRunContextForItem(runId, tenantId, epoch, spawn, itemIndex);
        }

        return loadNarrowedContextForItem(runId, tenantId, epoch, itemIndex, stepKeyTokens, maxRowBytes, maxCollectionSize);
    }

    /**
     * Builds a per-item context with two-pass precedence:
     * <ol>
     *   <li><b>Pass 1 (per-item match)</b>: storages whose {@code item_index == itemIndex} populate
     *       the context. Both full key (e.g., {@code mcp:read_email}) and alias (e.g., {@code read_email})
     *       come from the SAME storage row, eliminating the alias/full-key drift that caused the
     *       Daily Email Digest split bug (2026-05-07).</li>
     *   <li><b>Pass 2 (shared fallback)</b>: for step_keys NOT already populated, fall back to
     *       storages with {@code item_index == null} or {@code == 0} (shared upstream nodes:
     *       trigger, pre-split steps, post-merge nodes that didn't run per-item).</li>
     * </ol>
     *
     * <p>Iteration order is the storages list order - relies on
     * {@link StorageRepository#findByRunIdAndEpoch} ORDER BY {@code created_at, id DESC}
     * tiebreaker for deterministic last-wins within a pass.
     *
     * <p>If no per-item match exists for a step_key (e.g., node skipped/not-routed for this item)
     * and no shared row exists either, that step_key is omitted - downstream templates resolve to
     * null, mirroring the upstream-failed semantics.
     */
    private Map<String, Object> buildPerItemContext(List<StorageEntity> storages, int itemIndex) {
        Map<String, Object> context = new LinkedHashMap<>();
        java.util.Set<String> populatedStepKeys = new java.util.HashSet<>();

        // Pass 1: per-item match - highest precedence, alias and full-key from same row
        for (StorageEntity storage : storages) {
            String stepKey = storage.getStepKey();
            if (stepKey == null || stepKey.isEmpty()) continue;
            Integer si = storage.getItemIndex();
            if (si == null || si != itemIndex) continue;

            try {
                Map<String, Object> data = parseStorageData(storage);
                if (data != null && !data.isEmpty()) {
                    StepOutputsWriter.writeWithAlias(context, stepKey, data);
                    populatedStepKeys.add(stepKey);
                }
            } catch (Exception e) {
                logger.warn("[RunContext] Failed to parse storage {}: {}", storage.getId(), e.getMessage());
            }
        }

        // Pass 2: shared fallback (item_index == null OR 0) for step_keys NOT yet populated
        for (StorageEntity storage : storages) {
            String stepKey = storage.getStepKey();
            if (stepKey == null || stepKey.isEmpty()) continue;
            if (populatedStepKeys.contains(stepKey)) continue;
            Integer si = storage.getItemIndex();
            if (si != null && si != 0) continue;

            try {
                Map<String, Object> data = parseStorageData(storage);
                if (data != null && !data.isEmpty()) {
                    StepOutputsWriter.writeWithAlias(context, stepKey, data);
                    populatedStepKeys.add(stepKey);
                    if (metrics != null) {
                        metrics.recordPerItemFallback();
                    }
                }
            } catch (Exception e) {
                logger.warn("[RunContext] Failed to parse storage {}: {}", storage.getId(), e.getMessage());
            }
        }

        return context;
    }

    /**
     * Evaluate expressions for a specific item index with spawn awareness, filtered by epoch.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> evaluateExpressionsForItem(String runId, String tenantId, int epoch, int spawn, int itemIndex, Map<String, String> mappings) {
        if (mappings == null || mappings.isEmpty()) {
            return Map.of();
        }

        Map<String, Object> context = loadRunContextForItem(runId, tenantId, epoch, spawn, itemIndex);
        return evaluateExpressionsWithContext(context, mappings);
    }

    /**
     * Narrowed variant of {@link #evaluateExpressionsForItem} - extracts the step_keys
     * referenced by the SpEL {@code mappings} and loads ONLY those rows from storage,
     * skipping rows whose persisted {@code size_bytes >= maxRowBytes}. Oversized rows
     * are surfaced as {@code {"__oversized": true, "size_bytes": N}} markers so templates
     * can detect them without their JSONB ever entering heap.
     *
     * <p>Falls back to the unbounded path when:
     * <ul>
     *   <li>{@code mappings} is empty (nothing to narrow against)
     *   <li>{@code spawn &gt; 0} (the spawn-aware DISTINCT ON path is not yet narrowed -
     *       falls back to {@link #loadRunContextForItem(String, String, int, int, int)};
     *       reruns are rare and the existing path is the unbounded one this method replaces
     *       for the hot path)
     *   <li>No step_key tokens could be extracted from the mappings (e.g. all literals)
     * </ul>
     *
     * <p>Post-2026-05-22 OOM fix: the prod fire was here. {@code resolveVariablesOptimized}
     * called {@code evaluateExpressionsForItem} → {@code loadRunContextForItem} →
     * {@code findByRunIdAndEpoch} which materialized every storage row of the epoch
     * (~30 rows × 5-260 KB JSONB). Now we load 2-5 step_keys × ≤128 KB.
     *
     * @param maxRowBytes hard cap on individual storage row size in bytes; rows at or above
     *                    this size are substituted with the {@code __oversized} marker
     * @return resolved expression results, with truncation markers for oversized step outputs
     */
    @Transactional(readOnly = true)
    public Map<String, Object> evaluateExpressionsForItemNarrowed(String runId, String tenantId, int epoch, int spawn, int itemIndex,
                                                                   Map<String, String> mappings, int maxRowBytes) {
        return evaluateExpressionsForItemNarrowed(runId, tenantId, epoch, spawn, itemIndex, mappings, maxRowBytes, 0);
    }

    /**
     * Overload with early collection-size cap applied BEFORE SpEL evaluation.
     * When {@code maxCollectionSize > 0}, any nested Collection in the loaded context
     * is truncated to that size immediately after JSONB deserialization - preventing
     * large arrays (e.g. 539 items) from occupying heap during SpEL resolution.
     * The downstream {@code clampResolvedVariables} in InterfaceRenderService then
     * becomes a no-op safety net.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> evaluateExpressionsForItemNarrowed(String runId, String tenantId, int epoch, int spawn, int itemIndex,
                                                                   Map<String, String> mappings, int maxRowBytes, int maxCollectionSize) {
        if (mappings == null || mappings.isEmpty()) {
            return Map.of();
        }
        if (spawn > 0) {
            return evaluateExpressionsForItem(runId, tenantId, epoch, spawn, itemIndex, mappings);
        }

        java.util.Set<String> tokens = extractStepKeyTokens(mappings.values());
        if (tokens.isEmpty()) {
            return evaluateExpressionsForItem(runId, tenantId, epoch, spawn, itemIndex, mappings);
        }

        // Pre-query distinct step_keys (cheap projection) to resolve aliases → full keys.
        // Aliases ({{label.output.X}}) are written under both full and bare forms by
        // StepOutputsWriter.writeWithAlias - the storage row's step_key is the FULL form
        // (`mcp:label`), so we must map alias tokens back to full keys before filtering.
        List<String> distinctStepKeys = storageRepository.findDistinctStepKeysByRunIdAndEpoch(runId, epoch, tenantId);
        if (distinctStepKeys.isEmpty()) {
            // No storage rows yet for this epoch (early-replay window, scheduled-but-unfired
            // run, or all rows have NULL step_key). Resolve against empty context so SpEL
            // literals + {{var|default}} defaults still surface - early-return Map.of() would
            // silently drop user-facing labels and defaults.
            return evaluateExpressionsWithContext(Map.of(), mappings);
        }

        java.util.Set<String> targetStepKeys = resolveTokensToFullStepKeys(tokens, distinctStepKeys);
        if (targetStepKeys.isEmpty()) {
            // Every token referred to a step_key NOT in this epoch (typo, dropped upstream
            // node, prefix-drift). Same fallback: let SpEL evaluate the literals + defaults
            // for the partial-resolution case rather than wiping the entire variable set.
            return evaluateExpressionsWithContext(Map.of(), mappings);
        }

        Map<String, Object> context = loadBoundedContextForItem(
            runId, tenantId, epoch, itemIndex, targetStepKeys, maxRowBytes, maxCollectionSize);

        return evaluateExpressionsWithContext(context, mappings);
    }

    private Map<String, Object> loadNarrowedContextForItem(String runId, String tenantId, int epoch, int itemIndex,
                                                           Collection<String> stepKeyTokens, int maxRowBytes, int maxCollectionSize) {
        java.util.Set<String> tokens = new java.util.LinkedHashSet<>();
        for (String token : stepKeyTokens) {
            if (token != null && !token.isBlank()) {
                tokens.add(token);
            }
        }
        if (tokens.isEmpty()) {
            return Map.of();
        }

        List<String> distinctStepKeys = storageRepository.findDistinctStepKeysByRunIdAndEpoch(runId, epoch, tenantId);
        if (distinctStepKeys.isEmpty()) {
            return Map.of();
        }

        java.util.Set<String> targetStepKeys = resolveTokensToFullStepKeys(tokens, distinctStepKeys);
        if (targetStepKeys.isEmpty()) {
            return Map.of();
        }

        return loadBoundedContextForItem(runId, tenantId, epoch, itemIndex, targetStepKeys, maxRowBytes, maxCollectionSize);
    }

    private Map<String, Object> loadBoundedContextForItem(String runId, String tenantId, int epoch, int itemIndex,
                                                          java.util.Set<String> targetStepKeys, int maxRowBytes, int maxCollectionSize) {
        List<StorageEntity> storages = storageRepository.findByRunIdAndEpochAndStepKeyInBounded(
            runId, epoch, targetStepKeys, maxRowBytes, tenantId);
        Map<String, Object> context = buildPerItemContext(storages, itemIndex);

        // Early-clamp: truncate large collections in context before SpEL evaluation.
        if (maxCollectionSize > 0) {
            earlyClampCollections(context, maxCollectionSize);
        }

        addOversizedMarkers(runId, tenantId, epoch, targetStepKeys, maxRowBytes, storages, context);
        return context;
    }

    private void addOversizedMarkers(String runId, String tenantId, int epoch, java.util.Set<String> targetStepKeys,
                                     int maxRowBytes, List<StorageEntity> storages, Map<String, Object> context) {
        java.util.Set<String> populatedStepKeys = new java.util.HashSet<>();
        for (StorageEntity s : storages) {
            if (s.getStepKey() != null) populatedStepKeys.add(s.getStepKey());
        }
        java.util.Set<String> missingStepKeys = new java.util.HashSet<>(targetStepKeys);
        missingStepKeys.removeAll(populatedStepKeys);
        if (missingStepKeys.isEmpty()) {
            return;
        }

        List<Object[]> oversized = storageRepository.findOversizedStepKeyMetaForEpoch(
            runId, epoch, missingStepKeys, maxRowBytes, tenantId);
        for (Object[] row : oversized) {
            String stepKey = (String) row[0];
            Integer sizeBytes = (Integer) row[1];
            Map<String, Object> marker = new LinkedHashMap<>();
            marker.put("__oversized", true);
            marker.put("size_bytes", sizeBytes);
            Map<String, Object> wrapped = new LinkedHashMap<>();
            wrapped.put("output", marker);
            StepOutputsWriter.writeWithAlias(context, stepKey, wrapped);
            logger.warn("[RunContext] Oversized step output skipped: runId={}, stepKey={}, sizeBytes={}, cap={}",
                runId, stepKey, sizeBytes, maxRowBytes);
        }
    }

    /**
     * Regex pattern that captures the leading address token of a SpEL placeholder.
     * Matches {@code {{token.…}}} where {@code token} is the part before the first dot
     * and contains only word chars and optional colon-prefix (e.g. {@code mcp:fetch_emails}
     * or {@code fetch_emails}).
     *
     * <p>Tested shapes:
     * <ul>
     *   <li>{@code {{mcp:fetch_emails.output.items}}} → {@code mcp:fetch_emails}
     *   <li>{@code {{trigger:cron.output.cron}}} → {@code trigger:cron}
     *   <li>{@code {{table:load_processed.output.rows}}} → {@code table:load_processed}
     *   <li>{@code {{load_processed.output.rows}}} → {@code load_processed} (alias form)
     *   <li>{@code {{ cron.output.cron }}} → {@code cron} (leading whitespace tolerated)
     * </ul>
     */
    private static final java.util.regex.Pattern STEP_KEY_TOKEN_PATTERN =
        java.util.regex.Pattern.compile("\\{\\{\\s*([a-zA-Z_][a-zA-Z0-9_:]*)\\.");

    static java.util.Set<String> extractStepKeyTokens(java.util.Collection<String> expressions) {
        java.util.Set<String> tokens = new java.util.LinkedHashSet<>();
        if (expressions == null) return tokens;
        for (String expr : expressions) {
            if (expr == null || !expr.contains("{{")) continue;
            java.util.regex.Matcher m = STEP_KEY_TOKEN_PATTERN.matcher(expr);
            while (m.find()) {
                tokens.add(m.group(1));
            }
        }
        return tokens;
    }

    /**
     * Maps each token to one or more full storage step_keys. Full-key tokens
     * ({@code mcp:label}) are kept as-is when present in the distinct set. Alias tokens
     * ({@code label}) are matched against the bare-alias of each known full key
     * ({@link StepOutputsWriter#bareAlias}). A single alias can resolve to multiple
     * full keys if two node types share a label (unusual but allowed) - both are returned.
     */
    static java.util.Set<String> resolveTokensToFullStepKeys(java.util.Set<String> tokens, List<String> distinctStepKeys) {
        java.util.Set<String> result = new java.util.LinkedHashSet<>();
        java.util.Set<String> known = new java.util.HashSet<>(distinctStepKeys);
        for (String token : tokens) {
            if (token.indexOf(':') >= 0) {
                // Full-key token: prefer exact match…
                if (known.contains(token)) {
                    result.add(token);
                    continue;
                }
                // …else fall back to alias matching to absorb prefix drift (e.g. plan
                // declares {{trigger:start.…}} but storage row landed under
                // {{mcp:start.…}} - mirrors StepOutputsWriter.normalizeWrongPrefixes).
                String tokenAlias = StepOutputsWriter.bareAlias(token);
                if (tokenAlias != null) {
                    for (String fullKey : distinctStepKeys) {
                        String alias = StepOutputsWriter.bareAlias(fullKey);
                        if (tokenAlias.equals(alias)) {
                            result.add(fullKey);
                        }
                    }
                }
                continue;
            }
            // Alias token: scan known full keys for a matching bare alias. A single alias
            // can map to multiple full keys (rare but allowed); include all matches.
            for (String fullKey : distinctStepKeys) {
                String alias = StepOutputsWriter.bareAlias(fullKey);
                if (alias != null && alias.equals(token)) {
                    result.add(fullKey);
                }
            }
        }
        return result;
    }

    /**
     * Get the output of a specific step, filtered by epoch.
     *
     * @param runId The workflow run ID
     * @param stepKey The step key (e.g., "mcp:enricher")
     * @param epoch The trigger epoch number
     * @param tenantId The tenant ID
     * @return Optional containing the step output data
     */
    @Transactional(readOnly = true)
    public Optional<Map<String, Object>> getStepOutput(String runId, String stepKey, int epoch, String tenantId) {
        return storageRepository.findByRunIdAndStepKeyAndEpoch(runId, stepKey, epoch, tenantId)
                .map(this::parseStorageData);
    }

    /**
     * Get the output of a specific step for a specific item index, filtered by epoch.
     */
    @Transactional(readOnly = true)
    public Optional<Map<String, Object>> getStepOutput(String runId, String stepKey, int itemIndex, int epoch, String tenantId) {
        return storageRepository.findByRunIdAndStepKeyAndItemIndexAndEpoch(runId, stepKey, itemIndex, epoch, tenantId)
                .map(this::parseStorageData);
    }

    /**
     * Evaluate a SpEL expression against the run context, filtered by epoch.
     *
     * @param runId The workflow run ID
     * @param tenantId The tenant ID
     * @param epoch The trigger epoch number
     * @param expression The SpEL expression (e.g., "{{mcp:enricher.output.data.title}}")
     * @return The evaluated result
     */
    @Transactional(readOnly = true)
    public Object evaluateExpression(String runId, String tenantId, int epoch, String expression) {
        Map<String, Object> context = loadRunContext(runId, tenantId, epoch);
        return templateEngine.evaluateTemplateWithMap(expression, context);
    }

    /**
     * Evaluate multiple expressions and return results keyed by variable name, filtered by epoch.
     * Used for interface variable resolution.
     *
     * @param runId The workflow run ID
     * @param tenantId The tenant ID
     * @param epoch The trigger epoch number
     * @param mappings Map of generic variable name to SpEL expression
     * @return Map of generic variable name to resolved value
     */
    @Transactional(readOnly = true)
    public Map<String, Object> evaluateExpressions(String runId, String tenantId, int epoch, Map<String, String> mappings) {
        if (mappings == null || mappings.isEmpty()) {
            return Map.of();
        }

        Map<String, Object> context = loadRunContext(runId, tenantId, epoch);
        return evaluateExpressionsWithContext(context, mappings);
    }

    /**
     * Evaluate expressions for a specific item index, filtered by epoch.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> evaluateExpressionsForItem(String runId, String tenantId, int epoch, int itemIndex, Map<String, String> mappings) {
        if (mappings == null || mappings.isEmpty()) {
            return Map.of();
        }

        Map<String, Object> context = loadRunContextForItem(runId, tenantId, epoch, itemIndex);
        return evaluateExpressionsWithContext(context, mappings);
    }

    /**
     * Evaluate expressions with a pre-loaded context.
     * Useful when context is already available (e.g., during execution).
     */
    public Map<String, Object> evaluateExpressionsWithContext(Map<String, Object> context, Map<String, String> mappings) {
        Map<String, Object> resolved = new LinkedHashMap<>();

        for (Map.Entry<String, String> entry : mappings.entrySet()) {
            String genericName = entry.getKey();
            String expression = entry.getValue();

            if (expression == null) {
                continue;
            }

            // Literal pass-through: if the mapping value contains no template
            // placeholder (`{{…}}`), treat it as a plain string constant. The
            // previous implementation wrapped EVERY value in `{{…}}` and sent
            // it to SpEL, which blew up on natural-language literals like
            // "Test Workflow" (parser error → caught as "unresolved") - so the
            // interface showed its fallback default instead of the literal.
            // Expressions still go through the template engine below.
            if (!expression.contains("{{")) {
                resolved.put(genericName, expression);
                continue;
            }

            try {
                Object value = templateEngine.evaluateTemplateWithMap(expression, context);
                if (value != null) {
                    resolved.put(genericName, value);
                    logger.debug("[RunContext] Resolved {} = {}", genericName, truncateForLog(value));
                }
            } catch (Exception e) {
                logger.warn("[RunContext] Failed to evaluate {}: {} - {}", genericName, expression, e.getMessage());
            }
        }

        return resolved;
    }

    /**
     * Extract value at a specific path from a step's output using native JSON path query, filtered by epoch.
     * More efficient for extracting a single value.
     */
    @Transactional(readOnly = true)
    public Optional<String> extractValueFromStep(String runId, String stepKey, int epoch, String tenantId, String... path) {
        String value = storageRepository.extractValueFromStepWithEpoch(runId, stepKey, epoch, tenantId, path);
        return Optional.ofNullable(value);
    }

    // ========== Helper Methods ==========

    private Map<String, Object> buildContextFromStorages(List<StorageEntity> storages) {
        Map<String, Object> context = new LinkedHashMap<>();

        for (StorageEntity storage : storages) {
            String stepKey = storage.getStepKey();
            if (stepKey == null || stepKey.isEmpty()) {
                continue;
            }

            try {
                Map<String, Object> data = parseStorageData(storage);
                if (data != null && !data.isEmpty()) {
                    // Full key + alias written atomically. Last-wins ordering relies on the
                    // storage list being ORDER BY (createdAt, id DESC) - see
                    // findByRunIdAndEpoch / findByRunIdAndEpochWithLatestSpawn.
                    // Per-item callers must use loadRunContextForItem (Pass 1 + Pass 2 fallback),
                    // not this flat builder.
                    StepOutputsWriter.writeWithAlias(context, stepKey, data);

                    logger.debug("[RunContext] Added {} to context, keys: {}", stepKey, data.keySet());
                }
            } catch (Exception e) {
                logger.warn("[RunContext] Failed to parse storage {}: {}", storage.getId(), e.getMessage());
            }
        }

        return context;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseStorageData(StorageEntity storage) {
        if (storage == null || storage.getData() == null) {
            return null;
        }

        try {
            return objectMapper.readValue(storage.getData(), new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            logger.warn("[RunContext] Failed to parse storage data: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Recursively walk the context map and truncate any Collection/List value
     * that exceeds {@code maxSize}. Operates in-place (mutates the map).
     * Depth is bounded to 3 levels (step → output → field) to avoid runaway
     * traversal on deeply nested structures.
     */
    @SuppressWarnings("unchecked")
    private void earlyClampCollections(Map<String, Object> map, int maxSize) {
        earlyClampCollectionsRecursive(map, maxSize, 0);
    }

    @SuppressWarnings("unchecked")
    private void earlyClampCollectionsRecursive(Map<String, Object> map, int maxSize, int depth) {
        if (depth > 3) return; // safety: don't traverse infinitely deep structures
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            Object value = entry.getValue();
            if (value instanceof List<?> list && list.size() > maxSize) {
                // Replace with truncated copy - GC can reclaim the original large list
                entry.setValue(new ArrayList<>(list.subList(0, maxSize)));
            } else if (value instanceof Map<?, ?> nested) {
                earlyClampCollectionsRecursive((Map<String, Object>) nested, maxSize, depth + 1);
            }
        }
    }

    private String truncateForLog(Object value) {
        if (value == null) return "null";
        String str = value.toString();
        return str.length() > 100 ? str.substring(0, 100) + "..." : str;
    }

    // ========== SQL-Level Array Pagination ==========

    /**
     * Regex matching a "pure reference" expression - a single {{step.path.field}} with
     * no surrounding text, no SpEL functions, no concatenation. Only these can be
     * resolved via the SQL array-slice path.
     *
     * <p>Examples that match:
     * <ul>
     *   <li>{@code {{mcp:fetch_emails.output.items}}} → step=mcp:fetch_emails, path=output.items</li>
     *   <li>{@code {{ trigger:cron.output.data }}} → step=trigger:cron, path=output.data</li>
     * </ul>
     *
     * <p>Examples that do NOT match (fall back to full-load path):
     * <ul>
     *   <li>{@code {{formatDate(mcp:x.output.date, 'DD/MM')}}} - function call</li>
     *   <li>{@code Hello {{mcp:x.output.name}}!} - surrounding text</li>
     * </ul>
     */
    private static final java.util.regex.Pattern PURE_REF_PATTERN =
        java.util.regex.Pattern.compile("^\\{\\{\\s*([a-zA-Z_][a-zA-Z0-9_:]*(?:\\.[a-zA-Z_][a-zA-Z0-9_]*)*)\\s*\\}\\}$");

    /**
     * Result of SQL-level array pagination for a single variable.
     */
    public record PaginatedVariable(List<Object> items, int totalCount, int page, int pageSize) {}

    /**
     * Attempt to resolve a variable expression using SQL-level JSONB array pagination.
     * Only works for "pure reference" expressions like {@code {{mcp:fetch.output.items}}}.
     *
     * <p>Returns null if the expression is not a pure reference (complex SpEL, function
     * calls, surrounding text, etc.) - the caller falls back to the full-load + earlyClamp
     * path for these.
     *
     * @param expression  the raw SpEL expression (e.g. {@code {{mcp:fetch.output.items}}})
     * @param runId       workflow run ID
     * @param tenantId    tenant ID
     * @param epoch       epoch number
     * @param page        0-based page index
     * @param pageSize    items per page
     * @return paginated result, or null if expression is not suitable for SQL pagination
     */
    @Transactional(readOnly = true)
    public PaginatedVariable resolveVariablePaginated(
            String expression, String runId, String tenantId, int epoch, int page, int pageSize) {

        if (expression == null || runId == null || tenantId == null) return null;

        // Only pure references can use the SQL path
        java.util.regex.Matcher m = PURE_REF_PATTERN.matcher(expression);
        if (!m.matches()) return null;

        String fullRef = m.group(1); // e.g. "mcp:fetch_emails.output.items"
        int firstDot = fullRef.indexOf('.');
        if (firstDot < 0) return null; // Need at least step.field

        String stepToken = fullRef.substring(0, firstDot); // e.g. "mcp:fetch_emails" or "fetch_emails"
        String jsonPath = fullRef.substring(firstDot + 1);  // e.g. "output.items"

        // Resolve alias token → full step_key (if needed)
        List<String> distinctStepKeys = storageRepository.findDistinctStepKeysByRunIdAndEpoch(runId, epoch, tenantId);
        if (distinctStepKeys.isEmpty()) return null;

        java.util.Set<String> resolved = resolveTokensToFullStepKeys(
            java.util.Set.of(stepToken), distinctStepKeys);
        if (resolved.isEmpty()) return null;

        // Use the first resolved step_key (typically there's only one)
        String stepKey = resolved.iterator().next();

        // Count total elements via SQL
        Integer totalCount = storageRepository.countArrayAtPath(runId, stepKey, epoch, tenantId, jsonPath);
        if (totalCount == null || totalCount == 0) return null;

        // Fetch a bounded page. The caller controls pageSize through orchestrator limits,
        // but clamp here too because this method is also covered directly by unit tests.
        int effectivePageSize = Math.max(1, pageSize);
        int totalPages = Math.max(1, (int) Math.ceil((double) totalCount / effectivePageSize));
        int effectivePage = Math.max(0, Math.min(page, totalPages - 1));
        int offset = effectivePage * effectivePageSize;
        List<String> jsonElements = storageRepository.getArraySliceAtPath(
            runId, stepKey, epoch, tenantId, jsonPath, effectivePageSize, offset);

        // Parse each JSON element string back to an Object
        List<Object> items = new ArrayList<>(jsonElements.size());
        for (String jsonStr : jsonElements) {
            try {
                items.add(objectMapper.readValue(jsonStr, Object.class));
            } catch (Exception e) {
                logger.warn("[RunContext] Failed to parse paginated element: {}", e.getMessage());
                items.add(jsonStr); // fallback: raw string
            }
        }

        return new PaginatedVariable(items, totalCount, effectivePage, effectivePageSize);
    }
}
