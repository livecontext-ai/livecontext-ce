package com.apimarketplace.orchestrator.services.persistence.schema;

import com.apimarketplace.agent.domain.NodeDefinition;
import com.apimarketplace.agent.domain.OutputFieldDef;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Pulls every active catalog API tool from catalog-service at startup and registers each
 * one as a dynamic NodeDefinition in NodeDefinitionRegistry.
 *
 * Result: MCP nodes (catalog tools) appear in {@code GET /api/node-definitions} alongside
 * core/agent/trigger nodes, with their typed {@code outputSchema}. The frontend variable
 * picker, autocomplete, and LLM agent prompts therefore consume them through the SAME
 * single source of truth.
 *
 * Triggered on {@link ApplicationReadyEvent} (after both Spring contexts and the catalog
 * service are reachable). Failures are logged but do not block the orchestrator from
 * starting - without dynamic registration MCPs simply won't appear in {@code /api/node-definitions}
 * and the frontend variable picker degrades gracefully.
 */
@Component
public class CatalogMcpRegistrar {

    private static final Logger logger = LoggerFactory.getLogger(CatalogMcpRegistrar.class);

    private final NodeDefinitionRegistry registry;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final String catalogBaseUrl;

    public CatalogMcpRegistrar(NodeDefinitionRegistry registry,
                               RestTemplate restTemplate,
                               ObjectMapper objectMapper,
                               @Value("${orchestrator.catalog.base-url:http://localhost:8081}") String catalogBaseUrl) {
        this.registry = registry;
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
        this.catalogBaseUrl = catalogBaseUrl;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void registerAllCatalogMcps() {
        String url = catalogBaseUrl + "/api/catalog/tools/typed-definitions";
        List<Map<String, Object>> entries;
        try {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> body = restTemplate.getForObject(url, List.class);
            entries = body == null ? List.of() : body;
        } catch (Exception e) {
            logger.warn("CatalogMcpRegistrar: failed to fetch typed definitions from {} ({}). " +
                "MCP nodes will not appear in /api/node-definitions until next restart.", url, e.getMessage());
            return;
        }

        int registered = 0;
        int skippedNoSchema = 0;
        for (Map<String, Object> entry : entries) {
            try {
                NodeDefinition def = toNodeDefinition(entry);
                if (def == null) {
                    skippedNoSchema++;
                    continue;
                }
                registry.registerDynamic(def);
                registered++;
            } catch (Exception e) {
                logger.warn("CatalogMcpRegistrar: skipping malformed entry {}: {}",
                    entry.get("nodeType"), e.getMessage());
            }
        }
        logger.info("CatalogMcpRegistrar: registered {} MCP node definitions ({} skipped - no outputSchema yet)",
            registered, skippedNoSchema);
    }

    /**
     * Build a {@link NodeDefinition} from one entry returned by /api/catalog/tools/typed-definitions.
     * Returns {@code null} if the tool has no {@code outputSchema} yet (migration in progress).
     */
    private NodeDefinition toNodeDefinition(Map<String, Object> entry) {
        String nodeType = (String) entry.get("nodeType");
        if (nodeType == null || nodeType.isBlank()) return null;

        String outputSchemaJson = (String) entry.get("outputSchemaJson");
        if (outputSchemaJson == null || outputSchemaJson.isBlank()) {
            return null; // skip tools that haven't been migrated to typed outputs yet
        }

        List<OutputFieldDef> outputs = parseOutputSchema(outputSchemaJson);
        if (outputs.isEmpty()) return null;

        String label = (String) entry.getOrDefault("label", nodeType);
        String iconSlug = (String) entry.get("iconSlug");
        String apiName = (String) entry.get("apiName");
        String description = (String) entry.get("description");
        String executionMode = (String) entry.getOrDefault("executionMode", "sync");

        Map<String, Object> metadata = new HashMap<>();
        metadata.put("iconSlug", iconSlug);
        String iconUrl = (String) entry.get("iconUrl");
        if (iconUrl != null && !iconUrl.isBlank()) {
            metadata.put("iconUrl", iconUrl);
        }
        metadata.put("apiName", apiName);
        metadata.put("executionMode", executionMode);

        // V166: per-endpoint OAuth scope requirements + unique-per-API integration name.
        // Surfaced so the frontend can compute "missing scopes" client-side without an
        // extra API call. iconSlug is brand-shared (googlecloud covers GCS, Translate, …)
        // while platformCredentialName is unique per API and matches auth.credentials.integration.
        String integrationName = (String) entry.get("platformCredentialName");
        if (integrationName != null && !integrationName.isBlank()) {
            metadata.put("integrationName", integrationName);
        }
        Object requiredScopesObj = entry.get("requiredScopes");
        if (requiredScopesObj instanceof List<?> rs && !rs.isEmpty()) {
            // Cast safety: the upstream JSON deserializer (RestTemplate -> Jackson) maps a
            // JSON array of strings to ArrayList<String>. Surrounding registerAllCatalogMcps
            // try/catch contains any unexpected element type per entry.
            metadata.put("requiredScopes", rs);
        }

        return NodeDefinition.builder()
            .nodeType(nodeType)
            .label(label)
            .category("mcp")
            .variablePrefix("mcp")
            .description(description == null ? "" : description)
            .outputs(outputs)
            .keywords(List.of("mcp", iconSlug == null ? "" : iconSlug, label))
            .metadata(metadata)
            .build();
    }

    /**
     * Parse the JSONB output_schema (array of {key,type,description,children?}) into
     * the canonical {@link OutputFieldDef} record. Recursive.
     */
    private List<OutputFieldDef> parseOutputSchema(String json) {
        try {
            JsonNode root = objectMapper.readTree(json);
            if (!root.isArray()) return List.of();
            List<OutputFieldDef> result = new ArrayList<>(root.size());
            for (JsonNode field : root) {
                OutputFieldDef parsed = parseField(field);
                if (parsed != null) result.add(parsed);
            }
            return result;
        } catch (Exception e) {
            logger.warn("Failed to parse outputSchema JSON: {}", e.getMessage());
            return List.of();
        }
    }

    private OutputFieldDef parseField(JsonNode node) {
        if (node == null || !node.isObject()) return null;
        String key = node.path("key").asText("");
        String type = node.path("type").asText("");
        String description = node.path("description").asText("");
        if (key.isBlank() || type.isBlank()) return null;

        List<OutputFieldDef> children = List.of();
        JsonNode childrenNode = node.path("children");
        if (childrenNode.isArray() && childrenNode.size() > 0) {
            List<OutputFieldDef> tmp = new ArrayList<>(childrenNode.size());
            for (JsonNode child : childrenNode) {
                OutputFieldDef parsed = parseField(child);
                if (parsed != null) tmp.add(parsed);
            }
            children = tmp;
        }

        return OutputFieldDef.builder()
            .key(key)
            .type(type)
            .description(description)
            .children(children)
            .build();
    }
}
