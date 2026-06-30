package com.apimarketplace.agent.tools.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Canonical envelope for agent-facing list responses (workflow.list, application.my,
 * application.search, workflow.runs, agent.list, skill.list, interface.list, …).
 *
 * <p>Solves four real problems uncovered during the 2026-05 agent-UX audit:
 *
 * <ol>
 *   <li><b>Envelope drift.</b> Each list action had its own keys
 *       ({@code totalItems/totalPages} vs {@code total/hasMore}). Agents had to
 *       branch on per-tool shape. This helper emits a single canonical envelope.</li>
 *   <li><b>FlyFinder regression class.</b> A stateless agent on a 1000-app marketplace
 *       paginated 40× instead of refining. The helper emits a structured
 *       {@code hint.action="refine"} when {@code total > caps.hintThreshold} and
 *       <i>hard-refuses</i> with {@code code="PAGINATION_LIMIT_WITHOUT_FILTER"}
 *       when the agent crosses {@code spec.hardRefuseOffset} without a filter.</li>
 *   <li><b>Silent {@code workflow.runs} bug.</b> {@code PageRequest.of(0, 20)} ignored
 *       agent params. The helper takes {@link Bounds} directly; callers should use
 *       {@code EntityManager.setFirstResult/setMaxResults} so arbitrary offsets work.</li>
 *   <li><b>Boilerplate.</b> Six call sites duplicated the same
 *       {@code Math.min/max + skip/limit + envelope} pattern.</li>
 * </ol>
 *
 * <h2>Per-action {@code hint} contract (precedence top→bottom; earlier rows short-circuit later ones)</h2>
 * <table>
 *   <tr><th>action</th><th>required hint fields</th><th>trigger</th></tr>
 *   <tr><td>{@code refine}</td>
 *       <td>{@code reason="large_result_set"}, {@code suggestedFilters}</td>
 *       <td>{@code offset==0 && total>caps.hintThreshold && hasMore}</td></tr>
 *   <tr><td>{@code reset_offset}</td>
 *       <td>{@code reason="offset_beyond_total"}, {@code suggestedOffset=0}</td>
 *       <td>{@code offset>0 && count==0 && total>0}</td></tr>
 *   <tr><td>{@code broaden}</td>
 *       <td>{@code reason="no_results"}</td>
 *       <td>{@code total==0 && offset==0}</td></tr>
 *   <tr><td>{@code next_page}</td>
 *       <td>{@code reason="more_available"}, {@code nextOffset}</td>
 *       <td>{@code hasMore} (and no higher-precedence rule fires)</td></tr>
 * </table>
 *
 * <p>If no rule fires, no {@code hint} key is emitted - convention: <b>absent hint
 * means the response is complete; render items and stop.</b>
 *
 * <h2>Idempotency note</h2>
 * Pagination is offset-based, <b>not cursor-based</b>. Concurrent inserts/deletes
 * between pages can shift items. For stable cursors over volatile lists, callers
 * must add their own sort+filter strategy.
 */
public final class AgentListEnvelope {

    private static final Logger log = LoggerFactory.getLogger(AgentListEnvelope.class);

    /** Validated pagination bounds. Constructor enforces non-negative + non-empty. */
    public record Bounds(int limit, int offset) {
        public Bounds {
            if (limit < 1) throw new IllegalArgumentException("limit must be >= 1, got " + limit);
            if (offset < 0) throw new IllegalArgumentException("offset must be >= 0, got " + offset);
        }
    }

    /**
     * Per-resource pagination policy.
     *
     * <p>{@code defaultLimit} when caller omits {@code limit}, capped at {@code maxLimit}.
     * {@code hintThreshold} is the {@code total} above which a {@code refine} hint fires
     * at {@code offset==0}.
     *
     * <p>The three named statics ({@link #SMALL}, {@link #STANDARD}, {@link #LARGE}) cover
     * the current call sites; new presets MUST go here, not at the call site (keeps the
     * cap inventory auditable in one file).
     */
    public record Caps(int defaultLimit, int maxLimit, int hintThreshold) {
        public static final Caps SMALL    = new Caps(10, 25,  50);
        public static final Caps STANDARD = new Caps(25, 50, 100);
        public static final Caps LARGE    = new Caps(20, 100, 200);

        public Caps {
            if (defaultLimit < 1)
                throw new IllegalArgumentException("defaultLimit must be >= 1");
            if (defaultLimit > maxLimit)
                throw new IllegalArgumentException("defaultLimit must be <= maxLimit");
            if (maxLimit > 1000)
                throw new IllegalArgumentException("maxLimit must be <= 1000 (sanity ceiling)");
            if (hintThreshold < 1)
                throw new IllegalArgumentException("hintThreshold must be >= 1");
        }
    }

    /**
     * Per-action envelope specification.
     *
     * <p>{@code kind} is the agent-readable discriminator ({@code "workflows"},
     * {@code "applications"}, {@code "runs"}); {@code itemsKey} is the JSON key under
     * which the slice is emitted (typically same as {@code kind}); {@code label} is the
     * lowercase-plural noun used in agent-facing messages.
     *
     * <p>{@code hardRefuseOffset}: when the agent paginates past this offset without an
     * active filter, {@link #readBounds} throws
     * {@link InvalidParamsException}({@code PAGINATION_LIMIT_WITHOUT_FILTER}). The
     * compact constructor enforces {@code hardRefuseOffset > caps.hintThreshold} so
     * the agent always sees a {@code refine} hint <i>before</i> hitting the wall.
     *
     * <p>{@code legacyKeys}: optional dual-emit during a backward-compat transition.
     * Currently honored values: {@code "totalItems"} and {@code "totalPages"} - used to
     * keep {@code application.search} consumers working during one release.
     * {@code totalPages = ceil(total / effectiveLimit)} where {@code effectiveLimit}
     * is the post-clamp value (matters when caller passed {@code limit > caps.maxLimit}).
     * Marked for removal once consumers migrate.
     */
    public record Spec(
            Caps caps,
            String kind,
            String itemsKey,
            String label,
            Map<String, Object> nextOptions,
            int hardRefuseOffset,
            Set<String> legacyKeys,
            List<String> suggestedFilters
    ) {
        public Spec {
            if (caps == null)        throw new IllegalArgumentException("caps required");
            if (kind == null   || kind.isBlank())     throw new IllegalArgumentException("kind required");
            if (itemsKey == null || itemsKey.isBlank()) throw new IllegalArgumentException("itemsKey required");
            if (label == null  || label.isBlank())    throw new IllegalArgumentException("label required");
            if (hardRefuseOffset <= caps.hintThreshold)
                throw new IllegalArgumentException(
                    "hardRefuseOffset (" + hardRefuseOffset + ") must exceed caps.hintThreshold ("
                    + caps.hintThreshold + ") so refine fires before the wall");
            // Defensive copies - caller maps are mutable until we wrap them.
            nextOptions      = nextOptions      == null ? Map.of()  : Map.copyOf(nextOptions);
            legacyKeys       = legacyKeys       == null ? Set.of()  : Set.copyOf(legacyKeys);
            suggestedFilters = suggestedFilters == null ? List.of() : List.copyOf(suggestedFilters);
        }

        /**
         * Factory with sensible defaults.
         * {@code hardRefuseOffset = max(200, hintThreshold * 4)} - keeps the wall well above the hint
         * for SMALL/STANDARD caps without forcing callers to pick a number.
         *
         * <p>{@code suggestedFilters} defaults to {@code ["query", "category"]} - the common case
         * for marketplace-style actions (application.search, application.my). Resources that
         * accept different filters (e.g. {@code workflow.runs} = workflow-scoped, no general filter)
         * should pass an explicit list via {@link #withSuggestedFilters} or
         * {@code List.of()} to suppress the {@code refine} hint entirely.
         */
        public static Spec of(Caps caps, String kind, String itemsKey, String label) {
            return new Spec(caps, kind, itemsKey, label, Map.of(),
                    Math.max(200, caps.hintThreshold * 4), Set.of(),
                    List.of("query", "category"));
        }

        public Spec withNext(Map<String, Object> n) {
            return new Spec(caps, kind, itemsKey, label, n, hardRefuseOffset, legacyKeys, suggestedFilters);
        }

        public Spec withHardRefuse(int newOffset) {
            return new Spec(caps, kind, itemsKey, label, nextOptions, newOffset, legacyKeys, suggestedFilters);
        }

        /**
         * Override the filter names suggested by the {@code refine} hint. Pass
         * {@code List.of()} to disable the {@code refine} hint when no general
         * refinement is meaningful for this resource (e.g. {@code workflow.runs}
         * - its scope is the {@code workflow_id} itself).
         */
        public Spec withSuggestedFilters(List<String> filters) {
            return new Spec(caps, kind, itemsKey, label, nextOptions, hardRefuseOffset, legacyKeys, filters);
        }

        /**
         * Opt into dual-emit of legacy envelope keys for one transition release.
         * @deprecated transitional - remove once consumers migrate.
         */
        @Deprecated(forRemoval = true)
        public Spec withLegacyKeys(Set<String> keys) {
            return new Spec(caps, kind, itemsKey, label, nextOptions, hardRefuseOffset, keys, suggestedFilters);
        }
    }

    /**
     * Thrown by {@link #readBounds} when the agent supplies a footgun: oversized limit
     * over the safety ceiling, or pagination past {@code hardRefuseOffset} without a
     * filter. The structured {@code code} lets the orchestrator's MCP error mapper
     * translate it into the agent's error envelope verbatim.
     */
    public static class InvalidParamsException extends RuntimeException {
        public final String code;
        public InvalidParamsException(String code, String msg) {
            super(msg);
            this.code = code;
        }
    }

    /**
     * Read + validate + cap pagination params, with hard-refuse logic.
     *
     * <p>The helper derives {@code hasFilter = !activeFilterKeys.isEmpty()} server-side
     * - callers MUST NOT pre-compute and pass a boolean (audit feedback: caller-supplied
     * truth values are a hardening footgun). Pass the names of filter parameters that
     * are present and non-empty in the request (e.g. {@code {"query"}} when
     * {@code query != null && !query.isBlank()}).
     *
     * <p>Clamps: {@code limit} below 1 → cap to 1; {@code limit > caps.maxLimit} → cap to
     * {@code maxLimit}; {@code offset < 0} → 0. Non-numeric values silently fall back to
     * defaults - agents pass JSON which already constrains types.
     *
     * @throws InvalidParamsException when {@code offset > spec.hardRefuseOffset && !hasFilter}
     */
    public static Bounds readBounds(Map<String, Object> params, Spec spec, Set<String> activeFilterKeys) {
        Integer rawLimit  = ToolParamUtils.getIntParam(params, "limit");
        Integer rawOffset = ToolParamUtils.getIntParam(params, "offset");

        int limit  = rawLimit  == null ? spec.caps.defaultLimit : rawLimit;
        int offset = rawOffset == null ? 0 : rawOffset;

        // Clamp
        limit  = Math.max(1, Math.min(limit, spec.caps.maxLimit));
        offset = Math.max(0, offset);

        boolean hasFilter = activeFilterKeys != null && !activeFilterKeys.isEmpty();
        if (offset > spec.hardRefuseOffset && !hasFilter) {
            throw new InvalidParamsException(
                "PAGINATION_LIMIT_WITHOUT_FILTER",
                "Cannot paginate past offset=" + spec.hardRefuseOffset + " without a filter. "
                    + "Add a filter (e.g. query/category) or restart at offset=0.");
        }
        return new Bounds(limit, offset);
    }

    /**
     * Paginate an already-loaded list + emit the canonical envelope. Use when the full
     * set is already in memory (e.g. {@code application.my} where the publication
     * service returns the full set).
     */
    public static <T> Map<String, Object> paginateInMemory(List<T> all, Bounds bounds, Spec spec) {
        if (all == null) all = List.of();
        long total = all.size();
        List<T> slice = all.stream()
                .skip(bounds.offset())
                .limit(bounds.limit())
                .toList();
        return envelopeInternal(slice, bounds, total, spec);
    }

    /**
     * Wrap an already-sliced page produced by the DB + a known total. Use when
     * pagination is DB-side (e.g. {@code workflow.runs}).
     *
     * <p>Caller contract: the {@code slice} list contains AT MOST {@code bounds.limit()}
     * elements and represents the page starting at {@code bounds.offset()}. The helper
     * cannot verify this - callers MUST ensure the slice is consistent with the bounds
     * they pass.
     */
    public static Map<String, Object> paginateProjection(List<?> slice, Bounds bounds, long total, Spec spec) {
        if (slice == null) slice = List.of();
        if (total < 0) throw new IllegalArgumentException("total must be >= 0, got " + total);
        if (slice.size() > bounds.limit())
            throw new IllegalArgumentException(
                "slice.size=" + slice.size() + " exceeds bounds.limit=" + bounds.limit()
                + " - caller already over-sliced");
        return envelopeInternal(slice, bounds, total, spec);
    }

    // ==================== internal ====================

    private static Map<String, Object> envelopeInternal(List<?> slice, Bounds bounds, long total, Spec spec) {
        int count = slice.size();
        boolean hasMore = (long) bounds.offset() + count < total;

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", "OK");
        out.put("kind", spec.kind);
        out.put(spec.itemsKey, slice);
        out.put("count", count);
        out.put("total", total);
        out.put("offset", bounds.offset());
        out.put("limit", bounds.limit());
        out.put("hasMore", hasMore);

        Map<String, Object> hint = computeHint(bounds, total, count, hasMore, spec);
        if (hint != null) out.put("hint", hint);

        if (!spec.nextOptions.isEmpty()) out.put("NEXT_OPTIONS", spec.nextOptions);

        // Legacy dual-emit (e.g. application.search transition)
        if (spec.legacyKeys.contains("totalItems")) out.put("totalItems", total);
        if (spec.legacyKeys.contains("totalPages")) {
            int totalPages = bounds.limit() > 0 ? (int) Math.ceil((double) total / bounds.limit()) : 0;
            out.put("totalPages", totalPages);
        }

        log.debug("agent.list kind={} offset={} limit={} total={} count={} hint={}",
                spec.kind, bounds.offset(), bounds.limit(), total, count,
                hint != null ? hint.get("action") : "none");

        return out;
    }

    /**
     * Apply hint precedence rules. Returns null when no hint fires (= "render and stop").
     */
    private static Map<String, Object> computeHint(Bounds b, long total, int count, boolean hasMore, Spec spec) {
        // 1. refine - only at offset 0 with too many results, and only when the
        // resource actually has filters the agent can apply. If suggestedFilters is
        // empty, refine is meaningless (no filter to suggest) - fall through to
        // next_page so the agent still gets a useful hint.
        if (b.offset() == 0 && total > spec.caps.hintThreshold && hasMore
                && !spec.suggestedFilters.isEmpty()) {
            Map<String, Object> h = new LinkedHashMap<>();
            h.put("action", "refine");
            h.put("reason", "large_result_set");
            h.put("suggestedFilters", spec.suggestedFilters);
            return h;
        }
        // 2. reset_offset - offset overshoot (natural OR filter-narrowed mid-pagination)
        if (b.offset() > 0 && count == 0 && total > 0) {
            Map<String, Object> h = new LinkedHashMap<>();
            h.put("action", "reset_offset");
            h.put("reason", "offset_beyond_total");
            h.put("suggestedOffset", 0);
            return h;
        }
        // 3. broaden - empty result set at offset 0
        if (total == 0 && b.offset() == 0) {
            Map<String, Object> h = new LinkedHashMap<>();
            h.put("action", "broaden");
            h.put("reason", "no_results");
            return h;
        }
        // 4. next_page - fall-through, only when there's more to fetch
        if (hasMore) {
            Map<String, Object> h = new LinkedHashMap<>();
            h.put("action", "next_page");
            h.put("reason", "more_available");
            h.put("nextOffset", b.offset() + b.limit());
            return h;
        }
        return null;
    }

    private AgentListEnvelope() {}
}
