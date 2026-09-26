package com.apimarketplace.agent.tools.common;

import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionResult;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The one shape of a {@code present} answer, whichever tool owns the resource:
 * {@code workflow} (application, run, workflow), {@code table}, {@code interface},
 * {@code agent} and {@code files}. Each tool resolves the resource through its own
 * {@code get} path (allow-list, workspace scope, member rules) and only then calls this, so
 * presenting never reaches further than reading does.
 *
 * <p>The visualization type is {@code present_<kind>}, never the plain {@code table} /
 * {@code interface} / {@code agent} markers: those ride on ordinary write results, and the
 * frontend opens {@code present_*} on every page. Reusing the plain types would move the
 * user's view on every build step.
 */
public final class PresentedView {

    public static final String TYPE_PREFIX = "present_";

    private PresentedView() {}

    /**
     * @param kind  the resource kind, also the visualization suffix (table, interface, ...)
     * @param ids   the ids echoed back to the agent (e.g. {@code table_id})
     * @param id    the id the frontend opens
     * @param title the panel title
     * @param extra extra visualization fields (e.g. {@code runId}), may be empty
     */
    public static ToolExecutionResult result(String kind, Map<String, Object> ids, String id, String title,
                                             Map<String, Object> extra) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("presented", kind);
        data.putAll(ids);
        data.put("message", "Asked the user's side panel to show this " + kind + ".");
        Map<String, Object> visualization = new LinkedHashMap<>();
        visualization.put("type", TYPE_PREFIX + kind);
        visualization.put("id", id);
        visualization.put("title", title);
        visualization.putAll(extra);
        return ToolExecutionResult.success(data, Map.of("visualization", visualization));
    }

    public static ToolExecutionResult result(String kind, String idParam, String id, String title) {
        return result(kind, Map.of(idParam, id), id, title, Map.of());
    }

    /** A blank name falls back to the kind's label, so the panel never shows an empty title. */
    public static String titleOf(String name, String fallback) {
        return name != null && !name.isBlank() ? name : fallback;
    }

    /** The agent's optional {@code title} param wins over the resource's own title. */
    public static String requestedTitleOr(Map<String, Object> params, String resourceTitle) {
        Object title = params != null ? params.get("title") : null;
        String s = title != null ? title.toString().trim() : "";
        return s.isEmpty() ? resourceTitle : s;
    }
}
