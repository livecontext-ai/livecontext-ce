package com.apimarketplace.orchestrator.execution.v2.nodes;

import com.apimarketplace.orchestrator.domain.workflow.Core;
import com.apimarketplace.orchestrator.execution.v2.engine.ExecutionContext;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * HtmlExtract node - parses HTML using CSS selectors via jsoup.
 *
 * Modes:
 *  - "single": extract one item from the document.
 *  - "multiple": loop over elements matching {@code rootSelector} and extract one item per match.
 *
 * Each field has:
 *  - selector: CSS selector relative to the root element
 *  - attribute: "text" | "html" | attribute name (e.g. "href", "src")
 *  - transform: "none" | "trim" | "lowercase" | "uppercase" | "number"
 *  - required: boolean - missing required fields are reported in the errors list
 *  - defaultValue: value used when the field is missing
 */
public class HtmlExtractNode extends BaseNode {

    private static final Logger logger = LoggerFactory.getLogger(HtmlExtractNode.class);

    private final String sourceHtmlExpression;
    private final String extractionMode;
    private final String rootSelector;
    private final List<Core.HtmlExtractField> fields;
    private final boolean cleanWhitespace;

    public HtmlExtractNode(String nodeId, Core.HtmlExtractConfig config) {
        super(nodeId, NodeType.HTML_EXTRACT);
        if (config != null) {
            this.sourceHtmlExpression = config.sourceHtml();
            this.extractionMode = config.extractionMode();
            this.rootSelector = config.rootSelector();
            this.fields = config.fields() != null ? config.fields() : List.of();
            this.cleanWhitespace = config.cleanWhitespace();
        } else {
            this.sourceHtmlExpression = null;
            this.extractionMode = "single";
            this.rootSelector = null;
            this.fields = List.of();
            this.cleanWhitespace = true;
        }
        // Note: field validation (non-blank name + selector) happens at execute() time,
        // NOT in the constructor. Throwing here would crash the entire execution-tree build,
        // killing every other node in the workflow. Fail-fast at execute() isolates the failure
        // to this node and lets the rest of the DAG run.
    }

    @Override
    public NodeExecutionResult execute(ExecutionContext context) {
        long startTime = System.currentTimeMillis();
        logger.info("HtmlExtract node executing: nodeId={}, mode={}, rootSelector={}, fields={}, itemId={}",
            nodeId, extractionMode, rootSelector, fields.size(), context.itemId());

        // Build resolved_params early so every exit path can include it
        Map<String, Object> earlyResolvedParams = new LinkedHashMap<>();
        earlyResolvedParams.put("source_html_expression", sourceHtmlExpression);
        earlyResolvedParams.put("extraction_mode", extractionMode);
        if (rootSelector != null) earlyResolvedParams.put("root_selector", rootSelector);
        earlyResolvedParams.put("clean_whitespace", cleanWhitespace);
        earlyResolvedParams.put("field_count", fields.size());

        if (sourceHtmlExpression == null || sourceHtmlExpression.isBlank()) {
            Map<String, Object> failOutput = Map.of("resolved_params", earlyResolvedParams);
            return NodeExecutionResult.failureWithOutput(nodeId,
                "sourceHtml is required. Configure it with a reference like {{core:fetch.output.body}}",
                failOutput, System.currentTimeMillis() - startTime);
        }

        // Validate fields here (not in constructor) so an invalid field config fails this node
        // in isolation instead of killing the whole execution-tree build.
        for (int i = 0; i < fields.size(); i++) {
            Core.HtmlExtractField f = fields.get(i);
            if (f.name() == null || f.name().isBlank()) {
                Map<String, Object> failOutput = Map.of("resolved_params", earlyResolvedParams);
                return NodeExecutionResult.failureWithOutput(nodeId,
                    "field[" + i + "] is missing a non-blank 'name'. Each field must have {name, selector, attribute}.",
                    failOutput, System.currentTimeMillis() - startTime);
            }
            if (f.selector() == null || f.selector().isBlank()) {
                Map<String, Object> failOutput = Map.of("resolved_params", earlyResolvedParams);
                return NodeExecutionResult.failureWithOutput(nodeId,
                    "field '" + f.name() + "' is missing a non-blank 'selector'. " +
                    "Provide a CSS selector such as 'h1', '.title', or 'a[href]'.",
                    failOutput, System.currentTimeMillis() - startTime);
            }
        }

        try {
            // Resolve the source HTML via templates
            String html;
            if (templateAdapter != null) {
                Map<String, Object> resolved = templateAdapter.resolveTemplates(
                    Map.of("__html__", sourceHtmlExpression), context);
                Object resolvedValue = resolved.get("__html__");
                html = resolvedValue == null ? "" : String.valueOf(resolvedValue);
            } else {
                html = sourceHtmlExpression;
            }

            // Handle empty resolved HTML gracefully: return an empty items list with a note
            // in errors rather than crashing jsoup or producing misleading success output.
            if (html.isBlank()) {
                logger.warn("HtmlExtract: resolved source HTML is empty, returning empty items. nodeId={}, expr={}",
                    nodeId, sourceHtmlExpression);
                Map<String, Object> emptyResult = new HashMap<>();
                emptyResult.put("items", List.of());
                emptyResult.put("count", 0);
                emptyResult.put("matched_root", 0);
                String errMsg = String.format(
                    "sourceHtml expression '%s' resolved to an empty string. Verify the upstream node produced the expected field, and check that the reference path matches the actual output structure (e.g. {{core:my_set.output.html_content}} vs {{core:my_set.output.fields.html_content}} - Set's assignments are flat keys under 'output', not nested under 'fields').",
                    sourceHtmlExpression);
                emptyResult.put("errors", List.of(errMsg));
                Map<String, Object> emptyInput = new LinkedHashMap<>();
                emptyInput.put("source_html_length", 0);
                emptyInput.put("extraction_mode", extractionMode);
                emptyInput.put("root_selector", rootSelector);
                emptyInput.put("field_count", fields.size());
                emptyResult.put("resolved_params", emptyInput);
                return successWithMetadata(emptyResult, context);
            }

            Document doc = Jsoup.parse(html);

            // Determine the root elements
            List<Element> roots = new ArrayList<>();
            if ("multiple".equalsIgnoreCase(extractionMode) && rootSelector != null && !rootSelector.isBlank()) {
                Elements selected = doc.select(rootSelector);
                roots.addAll(selected);
            } else {
                roots.add(doc);
            }

            List<Map<String, Object>> items = new ArrayList<>();
            List<String> errors = new ArrayList<>();

            for (int i = 0; i < roots.size(); i++) {
                Element root = roots.get(i);
                Map<String, Object> item = new LinkedHashMap<>();

                for (Core.HtmlExtractField field : fields) {
                    if (field.name() == null || field.name().isBlank()
                        || field.selector() == null || field.selector().isBlank()) {
                        continue;
                    }
                    Element matched = root.selectFirst(field.selector());
                    Object extracted;
                    if (matched == null) {
                        if (field.required()) {
                            errors.add("field '" + field.name() + "' missing on item " + i);
                        }
                        extracted = field.defaultValue();
                    } else {
                        extracted = extractAttribute(matched, field.attribute());
                    }
                    item.put(field.name(), applyTransform(extracted, field.transform()));
                }
                items.add(item);
            }

            int matchedRoot = roots.size();

            Map<String, Object> result = new HashMap<>();
            result.put("items", items);
            result.put("count", items.size());
            result.put("matched_root", matchedRoot);
            result.put("errors", errors);

            // Persist resolved configuration as resolved_params for the inspector
            // "Resolved parameters" panel (mirror SortNode/FilterNode pattern).
            Map<String, Object> resolvedParams = new LinkedHashMap<>();
            String htmlPreview = html.length() > 500 ? html.substring(0, 500) + "..." : html;
            resolvedParams.put("source_html", htmlPreview);
            resolvedParams.put("source_html_length", html.length());
            resolvedParams.put("extraction_mode", extractionMode);
            if (rootSelector != null) resolvedParams.put("root_selector", rootSelector);
            resolvedParams.put("clean_whitespace", cleanWhitespace);
            resolvedParams.put("fields", fields.stream()
                .map(f -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("name", f.name());
                    m.put("selector", f.selector());
                    m.put("attribute", f.attribute());
                    if (f.transform() != null) m.put("transform", f.transform());
                    return m;
                })
                .toList());
            result.put("resolved_params", resolvedParams);

            logger.info("HtmlExtract completed: nodeId={}, items={}, errors={}, outputKeys={}",
                nodeId, items.size(), errors.size(), result.keySet());
            return successWithMetadata(result, context);

        } catch (Exception e) {
            logger.error("HtmlExtract execution failed: nodeId={}, error={}", nodeId, e.getMessage(), e);
            Map<String, Object> failOutput = Map.of("resolved_params", earlyResolvedParams);
            return NodeExecutionResult.failureWithOutput(nodeId, e.getMessage(),
                failOutput, System.currentTimeMillis() - startTime);
        }
    }

    private Object extractAttribute(Element element, String attribute) {
        if (attribute == null || "text".equalsIgnoreCase(attribute)) {
            String text = element.text();
            return cleanWhitespace ? text.trim() : text;
        }
        if ("html".equalsIgnoreCase(attribute)) {
            return element.html();
        }
        return element.attr(attribute);
    }

    private Object applyTransform(Object value, String transform) {
        if (value == null || transform == null || "none".equalsIgnoreCase(transform)) {
            return value;
        }
        String stringValue = String.valueOf(value);
        return switch (transform.toLowerCase(Locale.ROOT)) {
            case "trim" -> stringValue.trim();
            case "lowercase" -> stringValue.toLowerCase(Locale.ROOT);
            case "uppercase" -> stringValue.toUpperCase(Locale.ROOT);
            case "number" -> {
                try {
                    if (stringValue.contains(".")) yield Double.parseDouble(stringValue.trim());
                    yield Long.parseLong(stringValue.trim());
                } catch (NumberFormatException e) {
                    yield value;
                }
            }
            default -> value;
        };
    }

    public String getSourceHtmlExpression() { return sourceHtmlExpression; }
    public String getExtractionMode() { return extractionMode; }
    public String getRootSelector() { return rootSelector; }
    public List<Core.HtmlExtractField> getFields() { return fields; }
    public boolean isCleanWhitespace() { return cleanWhitespace; }

    public static class Builder {
        private String nodeId;
        private Core.HtmlExtractConfig config;

        public Builder nodeId(String nodeId) { this.nodeId = nodeId; return this; }
        public Builder htmlExtractConfig(Core.HtmlExtractConfig config) { this.config = config; return this; }
        public Builder templateAdapter(Object adapter) { return this; } // injected via acceptServices
        public HtmlExtractNode build() { return new HtmlExtractNode(nodeId, config); }
    }

    public static Builder builder() { return new Builder(); }
}
