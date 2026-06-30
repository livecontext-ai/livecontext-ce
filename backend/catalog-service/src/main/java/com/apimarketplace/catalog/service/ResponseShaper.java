package com.apimarketplace.catalog.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Shape API responses for LLM consumption within a size budget.
 *
 * <p>Replaces the legacy per-leaf-only truncator. Algorithm:
 * <ol>
 *   <li><b>Pass 0</b> - mark FileRef Maps ({@code _type:"file"}) as opaque so they
 *       survive untouched through any subsequent walk.</li>
 *   <li><b>Pass 1</b> - clip every string leaf above {@code MAX_STRING_SIZE_AGENT}
 *       (4 KB). Records canonicalised pattern + count + max bytes per pattern
 *       in the result. Bypassed for paths in the {@code expand} set.</li>
 *   <li><b>Pass 2 (AGENT mode only)</b> - if total serialised size exceeds
 *       {@code MAX_TOTAL_RESPONSE_SIZE} (64 KB), digest the largest array first
 *       (deterministic order: byteCost desc, then path asc). Each digest keeps
 *       the first 3 items + {@code skipped_from}/{@code skipped_to} ints.
 *       Up to {@code MAX_REPACK_ITERATIONS} (5) digests; if still over budget
 *       OR no array to digest → Pass 1.5.</li>
 *   <li><b>Pass 1.5 (AGENT mode only)</b> - re-clip every string leaf at 1 KB.
 *       If still over → emit oversize fallback ({@code _shape:"oversize"} with
 *       the JSON skeleton).</li>
 *   <li><b>Pass 3</b> - root-shape preservation. If the root was a {@code List}
 *       and a digest replaced it, the shaper returns a length-1 list wrapping
 *       the digest map so {@code ToolExecutionManager}'s wrap-as-{@code data}
 *       branch fires correctly and {@code httpStatus} doesn't merge into
 *       digest keys.</li>
 * </ol>
 *
 * <p>WORKFLOW mode runs Pass 0 + Pass 1 only - preserves array shapes for SpEL
 * and {@code OutputProjector} schemas declaring {@code items: array<...>}.
 */
@Slf4j
@Component
public class ResponseShaper {

    static final int MAX_STRING_SIZE_AGENT = 4096;       // 4 KB per leaf
    static final int MAX_STRING_SIZE_WORKFLOW = 4096;    // same - workflow LLM steps benefit too
    static final int MAX_STRING_SIZE_FALLBACK = 1024;    // pass-1.5 cap
    static final int MAX_TOTAL_RESPONSE_SIZE = 65_536;   // 64 KB total budget (agent only)
    static final int PREVIEW_LENGTH = 200;
    static final int MAX_DEPTH = 50;
    static final int MAX_DIGEST_PREVIEW_ITEMS = 3;
    static final int MAX_REPACK_ITERATIONS = 5;

    private static final ObjectMapper SIZE_MAPPER = new ObjectMapper();

    public enum Mode { AGENT, WORKFLOW }
    public enum Action { UNTOUCHED, LEAVES_ONLY, ARRAY_DIGESTED, OVERSIZE_FALLBACK }

    /** Aggregated truncation pattern: same canonical path → one entry. */
    public record TruncationPattern(String path, int count, int bytes) {}

    /** Result of shaping. Data may be a Map, a List, or a primitive. */
    public record ShapingResult(
            Object data,
            List<TruncationPattern> truncatedPatterns,
            Action action,
            int rawBytes,
            int shapedBytes) {

        public boolean hasTruncatedPatterns() {
            return truncatedPatterns != null && !truncatedPatterns.isEmpty();
        }
    }

    /** AGENT mode entry point. */
    public ShapingResult shape(Object response, List<String> expandPaths, Integer maxItems) {
        return shape(response, expandPaths, maxItems, Mode.AGENT);
    }

    public ShapingResult shape(Object response, List<String> expandPaths, Integer maxItems, Mode mode) {
        if (response == null) {
            return new ShapingResult(null, List.of(), Action.UNTOUCHED, 0, 0);
        }

        Set<String> expandSet = expandPaths != null ? new HashSet<>(expandPaths) : Set.of();
        // Aggregator state, mutated by the recursive walk.
        Map<String, int[]> patternAgg = new HashMap<>();   // canonicalPath -> {count, maxBytes}

        int rawBytes = serializedBytes(response);
        int leafCap = mode == Mode.WORKFLOW ? MAX_STRING_SIZE_WORKFLOW : MAX_STRING_SIZE_AGENT;
        Object shaped;
        try {
            shaped = walk(response, "", expandSet, 0, leafCap, patternAgg);
        } catch (Exception e) {
            log.warn("ResponseShaper: walk failed, returning raw response: {}", e.getMessage());
            return new ShapingResult(response, List.of(), Action.UNTOUCHED, rawBytes, rawBytes);
        }

        Action action = patternAgg.isEmpty() ? Action.UNTOUCHED : Action.LEAVES_ONLY;

        // Apply max_items cap (top-level arrays only) BEFORE pass-2 budget.
        // AGENT mode only - workflow callers don't pass max_items and we don't
        // want to surprise them with digest markers.
        if (mode == Mode.AGENT && maxItems != null && maxItems >= 0) {
            shaped = applyMaxItemsTopLevelV2(shaped, maxItems);
            // After max_items, recompute action - if any array was capped, a digest is in the tree.
            if (containsArrayDigest(shaped)) {
                action = Action.ARRAY_DIGESTED;
            }
        }

        if (mode == Mode.AGENT) {
            int currentSize = serializedBytes(shaped);
            if (currentSize > MAX_TOTAL_RESPONSE_SIZE) {
                ShapeAndAction repacked = repackToBudget(shaped);
                shaped = repacked.tree;
                if (repacked.action != null) {
                    action = repacked.action;
                }
            }
        }

        // Pass-3 root-shape preservation: if the root was a List and we replaced
        // it with a digest Map, wrap into a length-1 list so callers expecting
        // a List at root keep working (notably ToolExecutionManager:243-248
        // httpStatus wrap branch).
        if (response instanceof List && shaped instanceof Map<?,?> m && m.containsKey("_shape")) {
            shaped = List.of(shaped);
        }

        int shapedBytes = serializedBytes(shaped);
        return new ShapingResult(shaped, aggregateToList(patternAgg), action, rawBytes, shapedBytes);
    }

    // ---- Pass-1 walk ----------------------------------------------------------

    @SuppressWarnings("unchecked")
    private Object walk(Object value, String path, Set<String> expandSet, int depth,
                        int leafCap, Map<String, int[]> patternAgg) {
        if (depth > MAX_DEPTH) {
            return "[MAX_DEPTH_REACHED]";
        }
        if (shouldExpand(path, expandSet)) {
            return value;
        }
        if (value == null) {
            return null;
        }
        // Pass-0 sentinel: FileRef Map (_type:"file") is opaque - return verbatim.
        if (value instanceof Map<?,?> m && "file".equals(m.get("_type"))) {
            return value;
        }
        if (value instanceof Map) {
            Map<String, Object> map = (Map<String, Object>) value;
            Map<String, Object> result = new LinkedHashMap<>();
            for (Map.Entry<String, Object> e : map.entrySet()) {
                String key = e.getKey();
                String childPath = path.isEmpty() ? key : path + "." + key;
                result.put(key, walk(e.getValue(), childPath, expandSet, depth + 1, leafCap, patternAgg));
            }
            return result;
        }
        if (value instanceof List) {
            List<?> list = (List<?>) value;
            List<Object> result = new ArrayList<>(list.size());
            for (int i = 0; i < list.size(); i++) {
                String childPath = path + "[" + i + "]";
                result.add(walk(list.get(i), childPath, expandSet, depth + 1, leafCap, patternAgg));
            }
            return result;
        }
        if (value instanceof String s) {
            int len = s.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
            if (len > leafCap) {
                String canonical = PathPattern.canonicalize(path);
                patternAgg.merge(canonical, new int[]{1, len},
                        (a, b) -> new int[]{a[0] + 1, Math.max(a[1], b[1])});
                if (isLikelyBase64(s)) {
                    return String.format("[BASE64_CONTENT: %s]", formatSize(len));
                }
                String preview = s.length() > PREVIEW_LENGTH ? s.substring(0, PREVIEW_LENGTH) : s;
                return String.format("%s...\n[TRUNCATED: %s]", preview, formatSize(len));
            }
            return s;
        }
        return value;
    }

    // ---- expand path matching (preserves v3 semantics including array wildcard) ----

    static boolean shouldExpand(String currentPath, Set<String> expandPaths) {
        if (expandPaths == null || expandPaths.isEmpty() || currentPath == null || currentPath.isEmpty()) {
            return false;
        }
        String normalised = PathPattern.canonicalize(currentPath);
        if (expandPaths.contains(currentPath) || expandPaths.contains(normalised)) {
            return true;
        }
        for (String ep : expandPaths) {
            if (currentPath.startsWith(ep + ".") || currentPath.startsWith(ep + "[")
                    || normalised.startsWith(ep + ".") || normalised.startsWith(ep + "[")) {
                return true;
            }
        }
        return false;
    }

    // ---- max_items cap (top-level arrays only) -------------------------------

    /**
     * Cap top-level arrays at {@code maxItems}. If the array is already at or
     * below the cap, it is passed through unchanged (no digest wrapping). The
     * `Map` return for capped arrays uses the same {@code _shape:"array_digest"}
     * shape as the budget-driven digest in pass 2 - uniform structure for the
     * agent regardless of which trigger fired.
     */
    @SuppressWarnings("unchecked")
    private Object applyMaxItemsTopLevelV2(Object root, int maxItems) {
        if (root instanceof List<?> list) {
            return capArrayOrPassthrough(list, maxItems);
        }
        if (root instanceof Map<?,?> m) {
            Map<String, Object> map = (Map<String, Object>) m;
            Map<String, Object> result = new LinkedHashMap<>();
            for (Map.Entry<String, Object> e : map.entrySet()) {
                Object v = e.getValue();
                if (v instanceof List<?> sub) {
                    result.put(e.getKey(), capArrayOrPassthrough(sub, maxItems));
                } else {
                    result.put(e.getKey(), v);
                }
            }
            return result;
        }
        return root;
    }

    /** Returns a digest Map when capping trims items; otherwise returns the original list. */
    private Object capArrayOrPassthrough(List<?> list, int maxItems) {
        if (maxItems >= list.size()) {
            return list; // no-op when the cap is non-trimming
        }
        int previewCount = Math.max(0, Math.min(maxItems, list.size()));
        List<Object> preview = new ArrayList<>(list.subList(0, previewCount));
        Map<String, Object> digest = new LinkedHashMap<>();
        digest.put("_shape", "array_digest");
        digest.put("total_items", list.size());
        digest.put("preview_items", previewCount);
        digest.put("items", preview);
        if (previewCount < list.size()) {
            digest.put("skipped_from", previewCount);
            digest.put("skipped_to", list.size() - 1);
        }
        return digest;
    }

    // ---- Pass-2 budget enforcement -------------------------------------------

    private record ShapeAndAction(Object tree, Action action) {}

    private ShapeAndAction repackToBudget(Object tree) {
        Object current = tree;
        for (int iter = 0; iter < MAX_REPACK_ITERATIONS; iter++) {
            int size = serializedBytes(current);
            if (size <= MAX_TOTAL_RESPONSE_SIZE) {
                return new ShapeAndAction(current, Action.ARRAY_DIGESTED);
            }
            List<ArrayLocator> arrays = collectArraysByCost(current);
            if (arrays.isEmpty()) {
                // No array to digest → straight to Pass 1.5
                return passOneFiveFallback(current);
            }
            ArrayLocator winner = arrays.get(0);
            current = replaceAtPath(current, winner.path, makeDigest(winner.list));
        }
        // Repack cap hit, still over → Pass 1.5
        if (serializedBytes(current) > MAX_TOTAL_RESPONSE_SIZE) {
            return passOneFiveFallback(current);
        }
        return new ShapeAndAction(current, Action.ARRAY_DIGESTED);
    }

    private Map<String, Object> makeDigest(List<?> list) {
        int previewCount = Math.min(MAX_DIGEST_PREVIEW_ITEMS, list.size());
        List<Object> preview = new ArrayList<>(list.subList(0, previewCount));
        Map<String, Object> digest = new LinkedHashMap<>();
        digest.put("_shape", "array_digest");
        digest.put("total_items", list.size());
        digest.put("preview_items", previewCount);
        digest.put("items", preview);
        if (previewCount < list.size()) {
            digest.put("skipped_from", previewCount);
            digest.put("skipped_to", list.size() - 1);
        }
        return digest;
    }

    /** Locator for an array inside a tree at a recorded path. */
    private record ArrayLocator(String path, List<?> list, int byteCost) {}

    /** Collect arrays in DFS order with serialised byte cost; sort byteCost desc, path asc. */
    @SuppressWarnings("unchecked")
    private List<ArrayLocator> collectArraysByCost(Object root) {
        List<ArrayLocator> out = new ArrayList<>();
        collectArraysRec(root, "", out);
        out.sort(Comparator
                .comparingInt(ArrayLocator::byteCost).reversed()
                .thenComparing(ArrayLocator::path));
        return out;
    }

    @SuppressWarnings("unchecked")
    private void collectArraysRec(Object node, String path, List<ArrayLocator> out) {
        if (node instanceof Map<?,?> m) {
            // FileRef is opaque
            if ("file".equals(m.get("_type"))) return;
            // Don't redigest an existing digest
            if ("array_digest".equals(m.get("_shape")) || "oversize".equals(m.get("_shape"))) return;
            for (Map.Entry<?, ?> e : m.entrySet()) {
                String childPath = path.isEmpty() ? String.valueOf(e.getKey()) : path + "." + e.getKey();
                collectArraysRec(e.getValue(), childPath, out);
            }
        } else if (node instanceof List<?> list) {
            // Skip arrays whose only members are FileRef Maps.
            boolean allFileRefs = !list.isEmpty() && list.stream().allMatch(it ->
                    it instanceof Map<?,?> mm && "file".equals(mm.get("_type")));
            if (!allFileRefs) {
                int cost = serializedBytes(list);
                out.add(new ArrayLocator(path, list, cost));
            }
            for (int i = 0; i < list.size(); i++) {
                collectArraysRec(list.get(i), path + "[" + i + "]", out);
            }
        }
    }

    /** Replace the value at {@code path} (in our DFS notation) with {@code replacement}. */
    @SuppressWarnings("unchecked")
    private Object replaceAtPath(Object root, String path, Object replacement) {
        if (path == null || path.isEmpty()) {
            return replacement;
        }
        // Tokenise path into a list of (kind, key) pairs.
        List<PathStep> steps = parsePath(path);
        return replaceRec(root, steps, 0, replacement);
    }

    private record PathStep(boolean isIndex, String key, int idx) {}

    private List<PathStep> parsePath(String path) {
        List<PathStep> out = new ArrayList<>();
        StringBuilder buf = new StringBuilder();
        int i = 0;
        while (i < path.length()) {
            char c = path.charAt(i);
            if (c == '.') {
                if (buf.length() > 0) {
                    out.add(new PathStep(false, buf.toString(), -1));
                    buf.setLength(0);
                }
                i++;
            } else if (c == '[') {
                if (buf.length() > 0) {
                    out.add(new PathStep(false, buf.toString(), -1));
                    buf.setLength(0);
                }
                int end = path.indexOf(']', i);
                int idx = Integer.parseInt(path.substring(i + 1, end));
                out.add(new PathStep(true, null, idx));
                i = end + 1;
            } else {
                buf.append(c);
                i++;
            }
        }
        if (buf.length() > 0) {
            out.add(new PathStep(false, buf.toString(), -1));
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private Object replaceRec(Object node, List<PathStep> steps, int idx, Object replacement) {
        if (idx >= steps.size()) {
            return replacement;
        }
        PathStep step = steps.get(idx);
        if (step.isIndex) {
            List<Object> list = new ArrayList<>((List<Object>) node);
            list.set(step.idx, replaceRec(list.get(step.idx), steps, idx + 1, replacement));
            return list;
        } else {
            Map<String, Object> map = new LinkedHashMap<>((Map<String, Object>) node);
            map.put(step.key, replaceRec(map.get(step.key), steps, idx + 1, replacement));
            return map;
        }
    }

    // ---- Pass-1.5 wide-object fallback ---------------------------------------

    private ShapeAndAction passOneFiveFallback(Object tree) {
        // Re-clip every string leaf at MAX_STRING_SIZE_FALLBACK.
        Map<String, int[]> dummyAgg = new HashMap<>();
        Object reclipped = walk(tree, "", Set.of(), 0, MAX_STRING_SIZE_FALLBACK, dummyAgg);
        if (serializedBytes(reclipped) <= MAX_TOTAL_RESPONSE_SIZE) {
            return new ShapeAndAction(reclipped, Action.OVERSIZE_FALLBACK);
        }
        // Last resort: skeleton.
        Map<String, Object> oversize = new LinkedHashMap<>();
        oversize.put("_shape", "oversize");
        oversize.put("total_size_bytes", serializedBytes(tree));
        oversize.put("skeleton", buildSkeleton(tree, 0));
        return new ShapeAndAction(oversize, Action.OVERSIZE_FALLBACK);
    }

    /** Lightweight inline skeleton: keep keys + types, drop values. */
    @SuppressWarnings("unchecked")
    private Object buildSkeleton(Object node, int depth) {
        if (depth > MAX_DEPTH) return "<deep>";
        if (node == null) return "null";
        if (node instanceof Map<?,?> m) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : m.entrySet()) {
                out.put(String.valueOf(e.getKey()), buildSkeleton(e.getValue(), depth + 1));
            }
            return out;
        }
        if (node instanceof List<?> l) {
            if (l.isEmpty()) return List.of();
            return List.of(buildSkeleton(l.get(0), depth + 1));
        }
        if (node instanceof String) return "<string>";
        if (node instanceof Boolean) return "<boolean>";
        if (node instanceof Number) return "<number>";
        return "<value>";
    }

    // ---- helpers --------------------------------------------------------------

    private boolean containsArrayDigest(Object tree) {
        if (tree instanceof Map<?,?> m) {
            if ("array_digest".equals(m.get("_shape"))) return true;
            for (Object v : m.values()) {
                if (containsArrayDigest(v)) return true;
            }
        }
        if (tree instanceof List<?> l) {
            for (Object v : l) if (containsArrayDigest(v)) return true;
        }
        return false;
    }

    int serializedBytes(Object tree) {
        try {
            return SIZE_MAPPER.writeValueAsBytes(tree).length;
        } catch (JsonProcessingException e) {
            // Fallback: rough toString length.
            return String.valueOf(tree).length();
        }
    }

    private List<TruncationPattern> aggregateToList(Map<String, int[]> agg) {
        if (agg.isEmpty()) return List.of();
        List<TruncationPattern> out = new ArrayList<>(agg.size());
        for (Map.Entry<String, int[]> e : agg.entrySet()) {
            out.add(new TruncationPattern(e.getKey(), e.getValue()[0], e.getValue()[1]));
        }
        // Stable order: bytes desc, then path asc.
        out.sort(Comparator.comparingInt(TruncationPattern::bytes).reversed()
                .thenComparing(TruncationPattern::path));
        return Collections.unmodifiableList(out);
    }

    private static boolean isLikelyBase64(String str) {
        if (str.length() < 100) return false;
        String sample = str.substring(0, Math.min(100, str.length()));
        int b64 = 0;
        for (char c : sample.toCharArray()) {
            if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') ||
                (c >= '0' && c <= '9') || c == '+' || c == '/' || c == '=') {
                b64++;
            }
        }
        return (double) b64 / sample.length() > 0.9;
    }

    private static String formatSize(int bytes) {
        if (bytes < 1024) return bytes + "B";
        if (bytes < 1024 * 1024) return String.format("%.1fKB", bytes / 1024.0);
        return String.format("%.1fMB", bytes / (1024.0 * 1024.0));
    }
}
