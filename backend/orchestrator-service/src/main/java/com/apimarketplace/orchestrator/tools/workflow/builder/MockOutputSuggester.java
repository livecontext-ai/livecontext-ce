package com.apimarketplace.orchestrator.tools.workflow.builder;

import com.apimarketplace.agent.domain.NodeDefinition;
import com.apimarketplace.agent.domain.OutputFieldDef;
import com.apimarketplace.orchestrator.domain.workflow.WorkflowPlanParser;
import com.apimarketplace.orchestrator.services.impl.CatalogMockClient;
import com.apimarketplace.orchestrator.services.persistence.schema.NodeDefinitionRegistry;
import com.apimarketplace.orchestrator.utils.LabelNormalizer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Proposes a ready-to-edit mock output for ANY mockable node - the "start from
 * something real" half of the mock mode:
 * <ul>
 *   <li><b>mcp catalog tools</b>: the tool's default example response projected
 *       through its output schema ({@link CatalogMockClient}) - exactly what a
 *       real execution would produce, minus the transport envelope keys.</li>
 *   <li><b>every other node family</b>: a skeleton synthesized from the node
 *       type's declared {@link OutputFieldDef}s ({@link NodeDefinitionRegistry}) -
 *       right keys, placeholder values.</li>
 * </ul>
 * The caller (agent via {@code workflow(action='mock_suggest')}, UI via the
 * inspector's pre-filled editor) edits the proposal freely or ignores it
 * entirely - it is a starting point, never a constraint.
 */
@Service
public class MockOutputSuggester {

    /** Transport/envelope keys stripped from catalog examples - a mock author cares about the data fields. */
    private static final Set<String> ENVELOPE_KEYS = Set.of(
            "tool_id", "execution", "metadata", "message", "http_status");

    /** Suggestion with its provenance. */
    public record Suggestion(Map<String, Object> output, String source, String hint) {}

    private final NodeDefinitionRegistry nodeDefinitionRegistry;

    private CatalogMockClient catalogMockClient;

    public MockOutputSuggester(NodeDefinitionRegistry nodeDefinitionRegistry) {
        this.nodeDefinitionRegistry = nodeDefinitionRegistry;
    }

    @Autowired(required = false)
    public void setCatalogMockClient(CatalogMockClient catalogMockClient) {
        this.catalogMockClient = catalogMockClient;
    }

    /**
     * Builds the proposed mock output for a builder-session node.
     *
     * @param nodeId   normalized node key ({@code mcp:label}, {@code core:label}, ...)
     * @param nodeData the raw node entry from the builder session
     * @param tenantId tenant for the catalog example fetch
     */
    public Suggestion suggest(String nodeId, Map<String, Object> nodeData, String tenantId) {
        if (LabelNormalizer.isMcpKey(nodeId)) {
            String toolId = firstNonBlank(asString(nodeData.get("id")), asString(nodeData.get("tool_id")));
            if (WorkflowPlanParser.isCatalogToolId(toolId) && catalogMockClient != null) {
                try {
                    Map<String, Object> example = catalogMockClient.fetchProjectedExample(toolId, tenantId);
                    Map<String, Object> output = new LinkedHashMap<>();
                    for (Map.Entry<String, Object> entry : example.entrySet()) {
                        if (!ENVELOPE_KEYS.contains(entry.getKey())) {
                            output.put(entry.getKey(), entry.getValue());
                        }
                    }
                    return new Suggestion(output, "catalog_example",
                            "This IS the tool's default example, projected to its output schema. Either paste an "
                                    + "edited copy into mock={output: {...}}, or skip the copy entirely with "
                                    + "mock={source: 'catalog_example'} to always serve the up-to-date example.");
                } catch (CatalogMockClient.MockExampleUnavailableException e) {
                    // fall through to the schema skeleton
                }
            }
        }
        String nodeType = resolveSchemaNodeType(nodeId, nodeData);
        Optional<NodeDefinition> definition = nodeDefinitionRegistry.get(nodeType);
        if (definition.isPresent() && !definition.get().outputs().isEmpty()) {
            return new Suggestion(skeletonFromFields(definition.get().outputs()), "schema",
                    "Skeleton synthesized from this node type's output schema (right keys, placeholder values). "
                            + "Replace the placeholders with realistic data and set it via mock={output: {...}}. "
                            + "You are free to add or drop fields - only what downstream templates reference matters.");
        }
        return new Suggestion(new LinkedHashMap<>(), "none",
                "No output schema is declared for this node type. Author the output freely: include exactly the "
                        + "fields downstream nodes reference (e.g. {{" + nodeId + ".output.<field>}}) and set it via "
                        + "mock={output: {...}}.");
    }

    /** Skeleton value per declared field: defaults win, else a type-shaped placeholder. */
    private Map<String, Object> skeletonFromFields(List<OutputFieldDef> fields) {
        Map<String, Object> skeleton = new LinkedHashMap<>();
        for (OutputFieldDef field : fields) {
            if (Boolean.TRUE.equals(field.runtimeOnly())) {
                continue; // runtime-injected (current_item, ...) - never part of a persisted output
            }
            skeleton.put(field.key(), placeholderFor(field));
        }
        return skeleton;
    }

    private Object placeholderFor(OutputFieldDef field) {
        if (field.hasDefault() && !"__NOW__".equals(field.defaultValue())) {
            return field.defaultValue();
        }
        String type = field.type() != null ? field.type().toLowerCase(Locale.ROOT) : "string";
        return switch (type) {
            case "number" -> 0;
            case "boolean" -> false;
            case "array" -> field.children().isEmpty()
                    ? List.of()
                    : List.of(skeletonFromFields(field.children()));
            case "object" -> skeletonFromFields(field.children());
            case "datetime" -> "2026-01-01T00:00:00Z";
            default -> field.description() != null && !field.description().isBlank()
                    ? "<" + field.key() + ">"
                    : "";
        };
    }

    /**
     * Mirrors the runtime {@code ExecutionNode.schemaNodeType} resolution from the
     * builder session's raw node entry (the node objects don't exist at build time).
     */
    private String resolveSchemaNodeType(String nodeId, Map<String, Object> nodeData) {
        String rawType = asString(nodeData.get("type"));
        if (LabelNormalizer.isAgentKey(nodeId)) {
            if ("classify".equalsIgnoreCase(rawType)) return "CLASSIFY";
            if ("guardrail".equalsIgnoreCase(rawType)) return "GUARDRAIL";
            return "AGENT";
        }
        if (LabelNormalizer.isInterfaceKey(nodeId)) {
            return "INTERFACE";
        }
        if (LabelNormalizer.isTableKey(nodeId) || (rawType != null && rawType.startsWith("crud-"))) {
            return switch (rawType != null ? rawType : "") {
                case "crud-create-row" -> "INSERT_ROW";
                case "crud-read-row" -> "GET_ROWS";
                case "crud-update-row" -> "UPDATE_ROW";
                case "crud-delete-row" -> "DELETE_ROW";
                case "crud-create-column" -> "CREATE_COLUMN";
                case "crud-find" -> "FIND";
                default -> "GET_ROWS";
            };
        }
        if (LabelNormalizer.isMcpKey(nodeId)) {
            return "MCP";
        }
        return rawType != null ? rawType.toUpperCase(Locale.ROOT) : "";
    }

    private static String asString(Object value) {
        return value instanceof String s && !s.isBlank() ? s : null;
    }

    private static String firstNonBlank(String a, String b) {
        return a != null ? a : b;
    }
}
