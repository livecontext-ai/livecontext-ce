package com.apimarketplace.agent.tools.common;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Shared byte-cap walker for tool results that must reach the LLM context.
 *
 * <p>Centralizes the "cap large strings, pass through FileRefs, walk maps and
 * lists recursively" logic so every provider can apply the same agent-safe
 * limit. Without this, each provider re-implements its own size-cap (or
 * forgets to) and one will eventually leak megabytes of inline binary into
 * the agent's tool-result token budget - exactly the failure mode that
 * iterations 1-6 of the binary-handling refactor uncovered, one path at a
 * time, in {@code image_generation}, {@code workflow.execute},
 * {@code workflow.get_node_output}, {@code application.execute}.
 *
 * <p>Two entry points:
 * <ul>
 *   <li>{@link #capLargeStrings(Object)} - recursive walk that ONLY caps
 *       strings (preserves full lists). Use when the consumer needs to see
 *       the structure intact (e.g. a workflow-node inspector, a CRUD
 *       result with N rows where N is the point of the call).</li>
 *   <li>{@link #capLargeStringsAndLists(Object, int, int)} - recursive walk
 *       that ALSO collapses large lists into a {@code row_count + preview}
 *       summary. Use when the agent only needs a hint of the shape (e.g.
 *       a workflow run summary).</li>
 * </ul>
 *
 * <p>FileRef-shaped Maps ({@code _type:"file"}) pass through verbatim - they
 * are already the lightweight reference shape that replaces the inline
 * binary, and walking inside them adds noise without bytes saved.
 */
public final class ToolResultSizeCap {

    /** Per-leaf string size cap. 128 KB ≈ 32K tokens - generous enough for
     *  legitimate text (Wikipedia-length markdown, long JSON dumps, source
     *  files, scraped pages) while still well under the agent harness's
     *  ~25-30K-token tool-result ceiling so multiple results per turn fit.
     *  Anything bigger is almost always inline binary that should have been
     *  stripped upstream. Public so callers can adopt the same threshold or
     *  compare against it.
     *
     *  <p>Note: measured in {@code String.length()} (Java chars / UTF-16
     *  code units), not UTF-8 bytes. For ASCII the two coincide; for
     *  multi-byte text (Chinese, Arabic, emoji) the actual UTF-8 byte size
     *  can be 3-4× the threshold. The looser real cap is acceptable -
     *  bound is bound - but the constant name reflects the design intent. */
    public static final int MAX_STRING_BYTES = 128 * 1024;

    /** Preview window for truncated strings: enough for the agent to
     *  recognise the content and decide whether to re-fetch via a more
     *  specific tool. Kept proportional to the cap (~1.5%). */
    public static final int STRING_PREVIEW_BYTES = 2 * 1024;

    /** Default list-truncation threshold for {@link #capLargeStringsAndLists}. */
    public static final int DEFAULT_LIST_TRUNCATE_THRESHOLD = 4;

    /** Default preview window for truncated lists. */
    public static final int DEFAULT_LIST_PREVIEW_MAX_ROWS = 3;

    private ToolResultSizeCap() { /* utility */ }

    /**
     * Recursively walks the value and caps any string longer than
     * {@link #MAX_STRING_BYTES}. Maps and Lists are walked but never
     * collapsed; FileRef Maps are returned verbatim.
     */
    public static Object capLargeStrings(Object value) {
        return walk(value, /* listThreshold */ Integer.MAX_VALUE, /* listPreviewSize */ 0);
    }

    /** Map-typed convenience overload - useful for {@code Map<String,Object>}
     *  outputs without forcing the caller to cast. */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> capLargeStrings(Map<String, Object> output) {
        Object res = capLargeStrings((Object) output);
        return res instanceof Map<?, ?> m ? (Map<String, Object>) m : output;
    }

    /**
     * Recursively walks the value, capping strings AND collapsing large
     * lists. Maps walked, Lists ≥ {@code listThreshold} replaced by a
     * {@code {row_count, preview, truncated:true}} summary.
     *
     * @param listThreshold size at which a list becomes a summary
     * @param listPreviewSize how many leading rows to keep in the summary
     */
    public static Object capLargeStringsAndLists(Object value, int listThreshold, int listPreviewSize) {
        return walk(value, listThreshold, listPreviewSize);
    }

    @SuppressWarnings("unchecked")
    private static Object walk(Object value, int listThreshold, int listPreviewSize) {
        if (value == null) return null;
        // FileRef pass-through: don't recurse into a {_type:"file", path, ...}
        // Map - it's already the lightweight reference shape.
        if (value instanceof Map<?, ?> mapVal && "file".equals(mapVal.get("_type"))) {
            return value;
        }
        if (value instanceof Map<?, ?> mapVal) {
            Map<String, Object> sub = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : mapVal.entrySet()) {
                sub.put(String.valueOf(e.getKey()), walk(e.getValue(), listThreshold, listPreviewSize));
            }
            return sub;
        }
        if (value instanceof List<?> list) {
            if (list.size() >= listThreshold) {
                Map<String, Object> truncated = new LinkedHashMap<>();
                truncated.put("row_count", list.size());
                List<Object> preview = new ArrayList<>(listPreviewSize);
                for (int i = 0; i < Math.min(listPreviewSize, list.size()); i++) {
                    preview.add(walk(list.get(i), listThreshold, listPreviewSize));
                }
                truncated.put("preview", preview);
                truncated.put("truncated", true);
                return truncated;
            }
            List<Object> sub = new ArrayList<>(list.size());
            for (Object item : list) sub.add(walk(item, listThreshold, listPreviewSize));
            return sub;
        }
        if (value instanceof String str && str.length() > MAX_STRING_BYTES) {
            String preview = str.substring(0, Math.min(STRING_PREVIEW_BYTES, str.length()));
            boolean looksBase64 = str.length() > 256
                    && str.substring(0, 256).matches("^[A-Za-z0-9+/\\-_\\s]+={0,2}$");
            Map<String, Object> truncated = new LinkedHashMap<>();
            truncated.put("truncated", true);
            truncated.put("original_length", str.length());
            truncated.put("preview", preview);
            if (looksBase64) {
                truncated.put("note", "string-shaped binary elided from agent context - fetch via a typed tool if you need the bytes");
            }
            return truncated;
        }
        return value;
    }
}
