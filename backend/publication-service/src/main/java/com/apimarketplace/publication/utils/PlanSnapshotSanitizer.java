package com.apimarketplace.publication.utils;

import java.util.*;

/**
 * Strips sensitive data from a workflow planSnapshot for marketplace preview.
 * Uses a whitelist approach: only explicitly listed keys are kept per collection.
 */
public final class PlanSnapshotSanitizer {

    private PlanSnapshotSanitizer() {}

    private static final Set<String> TRIGGER_KEYS = Set.of("id", "type", "label", "position");
    private static final Set<String> MCP_KEYS = Set.of("id", "type", "label", "position");
    // Classify agents need `classifyCategories` to render their per-category
    // output ports + the right label per branch on the marketplace canvas. The
    // array carries only the publisher-facing branch names + descriptions,
    // never the prompt / credentials / model - so it's safe to expose to non-
    // owner viewers. Without it the canvas falls back to 2 default categories
    // and zero ports (user-reported 2026-05-13). `type` is required to
    // distinguish classify from guardrail/normal agents downstream.
    // `tools` is the ONLY place agent→tool wiring lives in the plan (tool edges
    // are deliberately not emitted into edges[]); the importer's createToolEdges
    // reads it to draw the agent→MCP connections. It carries only `mcp:label` /
    // `agent:label` refs whose labels are already exposed via MCP_KEYS and
    // AGENT_KEYS. Without it, published agents render with their tool nodes
    // floating, unconnected.
    // `model` + `provider` are intentionally EXPOSED: which LLM an app runs on is
    // low-sensitivity "powered by X" information that buyers legitimately want to
    // see on the marketplace canvas, and the publisher edits it expecting the
    // preview to reflect the change. Without them the canvas falls back to the
    // frontend ModelPicker's default model (a Claude id), so an app switched to
    // e.g. deepseek-chat still rendered as Claude (user-reported 2026-06-25 on the
    // "Echo Watch" guardrail). The agent's `prompt` / `systemPrompt` /
    // `guardrailRules` / `params` / sampling settings and ALL credentials stay
    // stripped (not whitelisted) - those are the publisher's IP / secrets. The full
    // prompt+model still flow into an acquirer's CLONE, which reads the RAW
    // plan_snapshot, never this sanitized copy (sanitizeForPreview is the
    // read/preview path only - single call site in getPublicationById).
    // SCOPE: this exposes the INLINE model an agent node carries (model/provider).
    // ENTITY-backed agents (those with an agentConfigId) instead carry their model
    // under publish-time enrichment keys (_snapshot_agent_modelName /
    // _snapshot_agent_modelProvider) that the marketplace workflow canvas does not
    // read, and whose sibling _snapshot_agent_systemPrompt is the fleet agent's full
    // prompt (IP). Those _snapshot_agent_* keys are deliberately NOT whitelisted, so
    // the entity agent's prompt stays protected; surfacing an entity agent's model in
    // the preview is a separate change (needs the canvas to read the enrichment key).
    private static final Set<String> AGENT_KEYS = Set.of(
            "id", "type", "label", "position", "classifyCategories", "tools",
            "model", "provider");
    // Branching cores need their port-defining arrays to render the right output
    // handles on the marketplace canvas - same rationale as classifyCategories
    // above: without them the importer falls back to default cases/branches and
    // EdgeCreationService resolves `core:label:case_N`-style edge ports to
    // undefined handles, so every branch visually leaves the wrong port
    // (user-reported 2026-06-12 on a switch node). Per port type:
    //   switch   → switchCases (+ switchExpression for the node body display)
    //   decision → decisionConditions
    //   fork     → forkOutputs
    //   option   → optionChoices
    //   approval → approvalOutputs
    // These carry publisher-facing branch labels/values and branch expressions.
    // Expressions mostly reference node labels already visible on the canvas,
    // but a publisher who inlines a comparison literal does expose it in
    // preview - the same (accepted) disclosure class as the interface
    // variableMapping and _snapshot_jsTemplate whitelisted below.
    // Loop/while/split/guardrail handles are derived from the node id and need
    // no extra data. Secrets (httpRequest.authConfig, sendEmail.credentialId,
    // params…) remain excluded: only the keys listed here survive.
    private static final Set<String> CORE_KEYS = Set.of(
            "id", "type", "label", "position",
            "switchCases", "switchExpression", "decisionConditions",
            "forkOutputs", "optionChoices", "approvalOutputs");
    private static final Set<String> TABLE_KEYS = Set.of("id", "type", "label", "position");
    private static final Set<String> EDGE_KEYS = Set.of("from", "to");
    private static final Set<String> INTERFACE_KEYS = Set.of(
            "id", "label", "position", "isEntryInterface",
            "showPreview", "previewWidth", "previewHeight",
            "_snapshot_htmlTemplate", "_snapshot_cssTemplate", "_snapshot_jsTemplate",
            // The interface's display/capture format. It travels with the templates: without it
            // an app published from a vertical interface is acquired as a 1280x800 one.
            "_snapshot_format",
            // Required for the marketplace iframe bridge to fire prefillForms()
            // (textareas + selects pre-filled with the publisher's chosen values).
            // Without these, the marketplace card and click-preview render the
            // form blank even though triggerData is on the showcase snapshot.
            // Same shape (CSS-selector → trigger-ref / generic var → workflow expr)
            // is already exposed via /showcase-render - whitelisting here is
            // consistent, not a new disclosure.
            "actionMapping", "variableMapping",
            "_snapshot_actionMappings", "_snapshot_variableMappings",
            // Toggle: when true, downstream nodes receive a `screenshot` FileRef
            // output. Must survive publish → clone so the cloned workflow keeps
            // the publisher's choice; the screenshot itself is generated under the
            // cloning tenant's own prefix at run time (not copied).
            "generateScreenshot",
            // Toggle: when true, downstream nodes receive `rendered_html`, `rendered_css`,
            // `rendered_js` string outputs (the resolved interface templates). Survives publish
            // → clone so the cloned workflow keeps the publisher's choice; the rendered source
            // itself is resolved at run time under the cloning tenant's own run context.
            "exposeRenderedSource",
            // Same contract as generateScreenshot / exposeRenderedSource: these are publisher
            // choices that must survive publish → clone; the PDF / MP4 themselves are generated
            // under the cloning tenant's own prefix at run time. They were missing from this
            // whitelist, so publishing silently dropped them and the cloned workflow lost its
            // PDF / video outputs.
            "generatePdf", "pdfFormat", "pdfLandscape",
            "generateVideo", "videoPreset", "videoMaxDurationSeconds", "videoMode", "videoFps"
    );

    // Top-level SCALAR keys kept as-is on the sanitized preview snapshot. These carry
    // no secrets and drive how the marketplace canvas RENDERS the plan, so the preview
    // must honour them. `layoutDirection` ('horizontal' | 'vertical') is the workflow's
    // reading direction: the publisher authored the canvas one way and the preview (and
    // a later clone) must show it the same way. Without this passthrough the loop below
    // keeps only whitelisted COLLECTIONS and silently drops every scalar top-level key.
    private static final Set<String> SCALAR_PASSTHROUGH_KEYS = Set.of("layoutDirection");

    private static final Map<String, Set<String>> COLLECTION_WHITELISTS = Map.of(
            "triggers", TRIGGER_KEYS,
            "mcps", MCP_KEYS,
            "agents", AGENT_KEYS,
            "cores", CORE_KEYS,
            "tables", TABLE_KEYS,
            "edges", EDGE_KEYS,
            "interfaces", INTERFACE_KEYS
    );

    @SuppressWarnings("unchecked")
    public static Map<String, Object> sanitizeForPreview(Map<String, Object> planSnapshot) {
        if (planSnapshot == null) {
            return null;
        }

        Map<String, Object> sanitized = new LinkedHashMap<>();

        for (Map.Entry<String, Object> entry : planSnapshot.entrySet()) {
            String collectionName = entry.getKey();
            Object value = entry.getValue();

            if ("notes".equals(collectionName)) {
                sanitized.put(collectionName, deepCopyList(value));
                continue;
            }

            if (SCALAR_PASSTHROUGH_KEYS.contains(collectionName)) {
                sanitized.put(collectionName, value);
                continue;
            }

            Set<String> whitelist = COLLECTION_WHITELISTS.get(collectionName);
            if (whitelist != null && value instanceof List<?> list) {
                List<Map<String, Object>> sanitizedList = new ArrayList<>(list.size());
                for (Object item : list) {
                    if (item instanceof Map<?, ?> map) {
                        sanitizedList.add(keepKeys((Map<String, Object>) map, whitelist));
                    }
                }
                sanitized.put(collectionName, sanitizedList);
            }
        }

        return sanitized;
    }

    private static Map<String, Object> keepKeys(Map<String, Object> source, Set<String> allowed) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (String key : allowed) {
            if (source.containsKey(key)) {
                result.put(key, source.get(key));
            }
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> deepCopyList(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        List<Object> copy = new ArrayList<>(list.size());
        for (Object item : list) {
            if (item instanceof Map<?, ?> map) {
                copy.add(new LinkedHashMap<>((Map<String, Object>) map));
            } else {
                copy.add(item);
            }
        }
        return copy;
    }
}
