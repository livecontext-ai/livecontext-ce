package com.apimarketplace.catalog.service;

import com.apimarketplace.catalog.service.ResponseShaper.Action;
import com.apimarketplace.catalog.service.ResponseShaper.ShapingResult;
import com.apimarketplace.catalog.service.ResponseShaper.TruncationPattern;
import com.apimarketplace.catalog.service.ToolContextService.ToolContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Build {@code metadata.nextAction} for a shaped tool response. One concrete
 * action per response, in priority order:
 *
 * <ol>
 *   <li><b>Array digest fired</b> → suggest pagination using a resolved cursor
 *       (and optional page-size) parameter from the tool's input schema. When
 *       the heuristic finds nothing, emit a prose-only hint pointing the
 *       agent at {@code catalog(action='response_schema')}.</li>
 *   <li><b>Per-leaf truncation only</b> → suggest {@code expand} with the top-3
 *       patterns by largest leaf size.</li>
 *   <li><b>Oversize fallback fired</b> → suggest {@code max_items=1} to walk
 *       the dataset one item at a time.</li>
 *   <li>Otherwise → fall through (caller may emit a DB hint).</li>
 * </ol>
 *
 * <p>All branches that emit a {@code params.parameters} block <b>merge with
 * {@code originalParams}</b> (spread-then-override), so the agent doesn't lose
 * required call params like {@code dataset_id}.
 */
@Component
@RequiredArgsConstructor
public class NextActionBuilder {

    private final PaginationParamResolver resolver;

    /**
     * @return {@code Optional.empty()} when no shaping action applies (caller
     *         falls through to DB hint).
     */
    public Optional<Map<String, Object>> build(
            ToolContext context,
            ShapingResult shapingResult,
            Map<String, Object> originalParams) {

        Action action = shapingResult.action();
        if (action == Action.UNTOUCHED) {
            return Optional.empty();
        }
        if (action == Action.ARRAY_DIGESTED) {
            return Optional.of(buildDigestAction(context, shapingResult, originalParams));
        }
        if (action == Action.LEAVES_ONLY) {
            return Optional.of(buildLeavesAction(context, shapingResult, originalParams));
        }
        if (action == Action.OVERSIZE_FALLBACK) {
            return Optional.of(buildOversizeAction(context, shapingResult, originalParams));
        }
        return Optional.empty();
    }

    private Map<String, Object> buildDigestAction(
            ToolContext context, ShapingResult shaping, Map<String, Object> originalParams) {

        DigestRange range = findDigestRange(shaping.data());
        Optional<String> cursorName = resolver.resolveCursor(safeParams(context));
        Optional<String> sizeName = resolver.resolveSize(safeParams(context));

        Map<String, Object> action = new LinkedHashMap<>();
        action.put("tool", "catalog");

        if (cursorName.isPresent()) {
            String cursor = cursorName.get();
            int suggestedCursorValue = range != null ? range.skippedFrom : 0;
            int rangeFrom = range != null ? range.skippedFrom : 0;
            int rangeTo = range != null ? range.skippedTo : 0;
            int totalItems = range != null ? range.totalItems : 0;
            int previewItems = range != null ? range.previewItems : 0;

            Map<String, Object> mergedParams = mergeParams(originalParams);
            mergedParams.put(cursor, suggestedCursorValue);
            String hint;
            if (sizeName.isPresent()) {
                String size = sizeName.get();
                mergedParams.put(size, 1);
                hint = String.format(
                        "Showing %d/%d items. The suggested call paginates from %s=%d with %s=1 (next item). " +
                        "Change `%s` to any value in [%d..%d] (zero-based) to jump to a specific item.",
                        previewItems, totalItems, cursor, suggestedCursorValue, size,
                        cursor, rangeFrom, rangeTo);
            } else {
                hint = String.format(
                        "Showing %d/%d items. The suggested call uses %s=%d (zero-based). " +
                        "Change `%s` to any value in [%d..%d] to jump to a specific item.",
                        previewItems, totalItems, cursor, suggestedCursorValue,
                        cursor, rangeFrom, rangeTo);
            }
            action.put("hint", hint);
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("tool_id", context.getToolId());
            params.put("parameters", mergedParams);
            action.put("params", params);
        } else {
            // No cursor resolved - prose only.
            int rangeFrom = range != null ? range.skippedFrom : 0;
            int rangeTo = range != null ? range.skippedTo : 0;
            int totalItems = range != null ? range.totalItems : 0;
            int previewItems = range != null ? range.previewItems : 0;
            String hint = String.format(
                    "Showing %d/%d items (skipped indices [%d..%d]). " +
                    "This tool's input schema does not advertise an offset or cursor parameter - " +
                    "call catalog(action='response_schema', tool_id='%s') to inspect available pagination params, " +
                    "OR call again with max_items=1 to see one full item at a time.",
                    previewItems, totalItems, rangeFrom, rangeTo,
                    context != null ? context.getToolId() : "<id>");
            action.put("hint", hint);
        }
        return action;
    }

    private Map<String, Object> buildLeavesAction(
            ToolContext context, ShapingResult shaping, Map<String, Object> originalParams) {

        // Top 3 patterns by bytes desc - already pre-sorted in ShapingResult.
        List<TruncationPattern> top3 = shaping.truncatedPatterns().stream().limit(3).toList();
        List<String> expandPaths = top3.stream().map(TruncationPattern::path).toList();

        int totalPatterns = shaping.truncatedPatterns().size();
        String hint = String.format(
                "%d large fields were truncated (showing top %d of %d patterns by size). " +
                "Re-call with expand=%s to get full content.",
                totalPatterns, top3.size(), totalPatterns, expandPaths);

        Map<String, Object> mergedParams = mergeParams(originalParams);
        Map<String, Object> action = new LinkedHashMap<>();
        action.put("tool", "catalog");
        action.put("hint", hint);
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("tool_id", context.getToolId());
        params.put("parameters", mergedParams);
        params.put("expand", expandPaths);
        action.put("params", params);
        return action;
    }

    private Map<String, Object> buildOversizeAction(
            ToolContext context, ShapingResult shaping, Map<String, Object> originalParams) {

        Map<String, Object> action = new LinkedHashMap<>();
        action.put("tool", "catalog");
        action.put("hint", String.format(
                "Response was too large (%d bytes) to summarise inline; only the schema skeleton is shown. " +
                "Re-call with max_items=1 to retrieve items one at a time.",
                shaping.rawBytes()));
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("tool_id", context.getToolId());
        params.put("parameters", mergeParams(originalParams));
        params.put("max_items", 1);
        action.put("params", params);
        return action;
    }

    // ---- helpers --------------------------------------------------------------

    private static Map<String, Object> mergeParams(Map<String, Object> originalParams) {
        Map<String, Object> merged = new LinkedHashMap<>();
        if (originalParams != null) {
            merged.putAll(originalParams);
        }
        return merged;
    }

    private static List<ToolContext.ParamMeta> safeParams(ToolContext context) {
        if (context == null || context.getParameters() == null) return List.of();
        return context.getParameters();
    }

    /** Locate the first array_digest's range info in a shaped tree. */
    @SuppressWarnings("unchecked")
    private static DigestRange findDigestRange(Object tree) {
        if (tree instanceof Map<?,?> m && "array_digest".equals(m.get("_shape"))) {
            int total = toInt(m.get("total_items"));
            int preview = toInt(m.get("preview_items"));
            int from = toInt(m.get("skipped_from"));
            int to = toInt(m.get("skipped_to"));
            return new DigestRange(total, preview, from, to);
        }
        if (tree instanceof Map<?,?> m) {
            for (Object v : m.values()) {
                DigestRange r = findDigestRange(v);
                if (r != null) return r;
            }
        }
        if (tree instanceof List<?> l) {
            for (Object v : l) {
                DigestRange r = findDigestRange(v);
                if (r != null) return r;
            }
        }
        return null;
    }

    private static int toInt(Object o) {
        if (o instanceof Number n) return n.intValue();
        return 0;
    }

    private record DigestRange(int totalItems, int previewItems, int skippedFrom, int skippedTo) {}
}
