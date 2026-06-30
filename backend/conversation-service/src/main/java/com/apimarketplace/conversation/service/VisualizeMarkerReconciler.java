package com.apimarketplace.conversation.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Corrects {@code [visualize:&lt;type&gt;:&lt;id&gt;(:&lt;runId|title&gt;)?]} markers an
 * assistant typed into its reply text against the AUTHORITATIVE {@code visualization}
 * metadata emitted by the tools that ran in the same turn.
 *
 * <p><b>Why this exists.</b> The visualize card the chat renders inline is parsed
 * from a marker the LLM writes into its own prose (see {@code MarkdownRender.tsx}).
 * The marker grammar requires the LLM to reproduce a 36-char UUID verbatim -
 * something models routinely get wrong. A real 2026-06-05 incident: a workflow edit
 * saved correctly with id {@code 837a75d4-9d83-4037-973e-9d8e0b6db56f}, but the
 * agent's summary text wrote {@code [visualize:workflow:837a75d4-9d83-4047-b2e9-d3c00e46eb3a]}
 * - a confabulated id sharing only the prefix. Result: the rendered card 404'd, AND
 * the agent re-read its own hallucinated id from history and called
 * {@code workflow(action='load')} with it next turn → "Workflow not found".
 *
 * <p>The tool result that produced the card already carries the correct id in
 * {@code metadata.visualization = {type, id, title, runId?}} (persisted into each
 * {@code tool_calls} entry by {@code ConversationAgentService.buildToolCallEntry}).
 * This reconciler reads that authoritative reference back and rewrites any marker of
 * the same type whose id (or, for run cards, runId) does not match - fixing the
 * rendered card (parsed from persisted content) AND the agent's next-turn
 * self-reference (history is read from persisted content), for every surface, at one
 * persistence chokepoint ({@code MessageService.addMessage}).
 *
 * <p><b>Conservative by design - only rewrites a provable mistake:</b>
 * <ul>
 *   <li>A marker is touched ONLY when exactly ONE distinct authoritative visualization
 *       of that type ran this turn. With zero (marker references a prior turn / a
 *       read-only action - those strip {@code visualization} - / no tool produced one)
 *       or several distinct references of that type, the marker is left untouched.</li>
 *   <li>Symmetric marker-side guard: if the reply text references more than one distinct
 *       id of a type, no marker of that type is corrected - a single authoritative ref
 *       cannot be attributed to one of several markers, so a valid card for a DIFFERENT
 *       entity the agent merely mentioned is never repointed.</li>
 *   <li>The {@code datasource}/{@code table} synonym is unified ({@code MarkdownRender}
 *       renders them as the same card) so a {@code [visualize:datasource:…]} marker is
 *       matched against the {@code table}-typed metadata the datasource tool emits.
 *       Other types match strictly ({@code workflow} ≠ {@code workflow_run}).</li>
 *   <li>The id segment is corrected when it differs. The trailing segment is corrected
 *       ONLY when the authoritative reference carries a {@code runId} (run cards:
 *       {@code workflow_run}, {@code application}) - i.e. the trailing segment is a
 *       runId, not a title. A 3-field marker is never grown to a 4-field one (that
 *       would flip a static card into a live-run card), and a title is never rewritten.</li>
 *   <li>The marker's own type token is preserved verbatim; only id/runId are rewritten.</li>
 *   <li>Any parsing problem is swallowed and the original content returned - this must
 *       never break message persistence.</li>
 * </ul>
 *
 * <p>The live card shown <i>during</i> a turn is already driven by the authoritative
 * metadata directly (the streaming event carries {@code visualization.id}), so this
 * reconciler targets the persisted inline-chat card and the agent's history re-read.
 */
public final class VisualizeMarkerReconciler {

    private static final Logger log = LoggerFactory.getLogger(VisualizeMarkerReconciler.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    // [visualize:<type>:<id>(:<runId-or-title>)?]
    //  group(1)=type  group(2)=id  group(3)=optional trailing segment (runId or title)
    private static final Pattern MARKER =
            Pattern.compile("\\[visualize:([a-zA-Z_]+):([^:\\]]+)(?::([^\\]]+))?\\]");

    /** One authoritative visualization reference from a tool result this turn. */
    private record VizRef(String id, String runId) {}

    private VisualizeMarkerReconciler() {}

    /**
     * Return {@code content} with any mistyped visualize-marker id/runId corrected
     * against the authoritative {@code visualization} entries inside
     * {@code toolCallsJson}. Returns {@code content} unchanged (including {@code null})
     * when there is nothing to do or anything goes wrong.
     */
    public static String reconcile(String content, String toolCallsJson) {
        if (content == null || content.isBlank()
                || toolCallsJson == null || toolCallsJson.isBlank()
                || !content.contains("[visualize:")) {
            return content;
        }

        try {
            Map<String, Set<VizRef>> refsByType = authoritativeRefsByType(toolCallsJson);
            if (refsByType.isEmpty()) {
                return content;
            }

            // Marker-side ambiguity guard: the distinct marker ids present per
            // (canonical) type. If the text references more than one id of a type, a
            // single authoritative ref can't be matched to a specific marker, so we
            // must NOT repoint - symmetric to the "more than one authoritative ref →
            // skip" guard. This protects a valid card for a DIFFERENT entity the agent
            // merely mentioned alongside the one it acted on this turn.
            Map<String, Set<String>> markerIdsByType = markerIdsByType(content);

            Matcher m = MARKER.matcher(content);
            StringBuilder out = new StringBuilder();
            while (m.find()) {
                String type = m.group(1);
                String id = m.group(2);
                String trailing = m.group(3); // runId or title, may be null

                String correctedId = id;
                String correctedTrailing = trailing;

                String ct = canonicalType(type);
                Set<VizRef> refs = refsByType.get(ct);
                Set<String> markerIds = markerIdsByType.get(ct);
                if (refs != null && refs.size() == 1 && markerIds != null && markerIds.size() == 1) {
                    VizRef ref = refs.iterator().next();
                    if (!ref.id().equals(id)) {
                        correctedId = ref.id();
                        log.warn("Reconciled mistyped visualize marker id: type={} {} -> {} "
                                        + "(authoritative id from tool result this turn)",
                                type, id, correctedId);
                    }
                    // Only a run card carries a runId; only then is the trailing
                    // segment a runId we can correct. Never grow a 3-field marker.
                    if (ref.runId() != null && trailing != null && !ref.runId().equals(trailing)) {
                        correctedTrailing = ref.runId();
                        log.warn("Reconciled mistyped visualize marker runId: type={} {} -> {} "
                                        + "(authoritative runId from tool result this turn)",
                                type, trailing, correctedTrailing);
                    }
                }

                String rebuilt = "[visualize:" + type + ":" + correctedId
                        + (correctedTrailing != null ? ":" + correctedTrailing : "") + "]";
                m.appendReplacement(out, Matcher.quoteReplacement(rebuilt));
            }
            m.appendTail(out);
            return out.toString();

        } catch (Exception e) {
            log.warn("VisualizeMarkerReconciler failed (non-fatal) - keeping original content: {}",
                    e.getMessage());
            return content;
        }
    }

    /**
     * Collect the distinct marker ids present in {@code content}, keyed by
     * (canonical) type. Used to skip correction when the text references several ids
     * of one type - the single authoritative ref then cannot be attributed to a
     * specific marker.
     */
    private static Map<String, Set<String>> markerIdsByType(String content) {
        Map<String, Set<String>> markerIdsByType = new LinkedHashMap<>();
        Matcher scan = MARKER.matcher(content);
        while (scan.find()) {
            markerIdsByType.computeIfAbsent(canonicalType(scan.group(1)), k -> new LinkedHashSet<>())
                    .add(scan.group(2));
        }
        return markerIdsByType;
    }

    /**
     * Collect the set of distinct authoritative visualization references per
     * (canonical) type from the {@code visualization} objects embedded in the
     * {@code tool_calls} entries.
     */
    private static Map<String, Set<VizRef>> authoritativeRefsByType(String toolCallsJson) throws Exception {
        Map<String, Set<VizRef>> refsByType = new LinkedHashMap<>();
        JsonNode arr = MAPPER.readTree(toolCallsJson);
        if (arr == null || !arr.isArray()) {
            return refsByType;
        }
        for (JsonNode entry : arr) {
            JsonNode viz = entry.get("visualization");
            if (viz == null || !viz.isObject()) continue;
            String type = textOrNull(viz.get("type"));
            String id = textOrNull(viz.get("id"));
            if (type == null || id == null) continue;
            String runId = textOrNull(viz.get("runId"));
            refsByType.computeIfAbsent(canonicalType(type), k -> new LinkedHashSet<>())
                    .add(new VizRef(id, runId));
        }
        return refsByType;
    }

    /**
     * {@code datasource} and {@code table} are the same card in the renderer
     * ({@code VisualizeBlock.tsx}); the datasource tool emits a {@code datasource}
     * marker but {@code table}-typed metadata. Fold them to one key so the marker is
     * matchable. All other types are kept as-is (strict).
     */
    private static String canonicalType(String type) {
        return "datasource".equals(type) ? "table" : type;
    }

    private static String textOrNull(JsonNode node) {
        if (node == null || node.isNull()) return null;
        String s = node.asText();
        return (s == null || s.isBlank()) ? null : s;
    }
}
