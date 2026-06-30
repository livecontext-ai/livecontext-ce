package com.apimarketplace.catalog.tools;

import com.apimarketplace.agent.registry.AgentToolDefinition;
import com.apimarketplace.agent.tools.ToolErrorCode;
import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionContext;
import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionResult;
import com.apimarketplace.agent.tools.common.ToolModule;
import com.apimarketplace.catalog.util.CredentialTypeNormalizer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Module handling catalog schema operations (response schemas).
 * SpEL help moved to {@link CatalogHelpModule} via {@code catalog(action='help', topic='schema')}.
 */
@Slf4j
@Component
public class CatalogSchemaModule implements ToolModule {

    private final RestTemplate restTemplate;

    @Value("${server.port:8081}")
    private int serverPort;

    private static final Set<String> HANDLED_ACTIONS = Set.of("response_schema");

    public CatalogSchemaModule() {
        this.restTemplate = new RestTemplate();
    }

    @Override
    public List<AgentToolDefinition> getToolDefinitions() {
        return List.of();
    }

    @Override
    public boolean canHandle(String toolName) {
        return HANDLED_ACTIONS.contains(toolName);
    }

    @Override
    public Optional<ToolExecutionResult> execute(String toolName, Map<String, Object> parameters,
                                                  String tenantId, ToolExecutionContext context) {
        if (!canHandle(toolName)) {
            return Optional.empty();
        }

        return Optional.of(switch (toolName) {
            case "response_schema" -> executeToolResponseSchema(parameters, tenantId, context);
            default -> ToolExecutionResult.failure(ToolErrorCode.TOOL_NOT_FOUND, "Unknown action: " + toolName);
        });
    }

    @SuppressWarnings("unchecked")
    private ToolExecutionResult executeToolResponseSchema(Map<String, Object> parameters,
                                                          String tenantId,
                                                          ToolExecutionContext context) {
        String rawToolId = (String) parameters.get("tool_id");

        if (rawToolId == null || rawToolId.isBlank()) {
            return ToolExecutionResult.failure(ToolErrorCode.MISSING_PARAMETER, "tool_id is required");
        }

        // Trim once and reuse - the validated form must also be the forwarded form,
        // otherwise whitespace-wrapped UUIDs (e.g. copied from a log line) pass UUID
        // validation but reach the internal /skeleton endpoint as `/.../  uuid  /skeleton`,
        // where Spring's @PathVariable UUID converter throws on the surrounding spaces.
        String toolId = rawToolId.trim();
        if (toolId.isEmpty()) {
            // Whitespace-only after trim - same semantic as missing.
            return ToolExecutionResult.failure(ToolErrorCode.MISSING_PARAMETER, "tool_id is required");
        }

        // Validate UUID shape BEFORE forwarding to the internal /skeleton endpoint.
        // The internal controller declares @PathVariable UUID toolId - Spring's
        // converter throws on a malformed string and the global exception handler
        // returns a generic 500 INTERNAL_ERROR to the agent. That happens in
        // practice when the LLM hallucinates a UUID (observed in prod:
        // "b52dd821-...-b3eb41tried" - the model literally completed the suffix
        // with the word "tried"). Surfacing a typed validation error here lets
        // the agent retry with a corrected id instead of bouncing on a 500.
        try {
            java.util.UUID.fromString(toolId);
        } catch (IllegalArgumentException e) {
            return ToolExecutionResult.failure(ToolErrorCode.INVALID_PARAMETER_VALUE,
                "tool_id must be a valid UUID (got: '" + rawToolId + "')");
        }

        try {
            String localUrl = "http://localhost:" + serverPort;
            String skeletonUrl = localUrl + "/api/v1/structure/tool/" + toolId + "/skeleton";

            HttpHeaders headers = CatalogToolHeaderSupport.jsonHeaders(tenantId, context);
            HttpEntity<Void> entity = new HttpEntity<>(headers);

            ResponseEntity<Map> response = restTemplate.exchange(skeletonUrl, HttpMethod.GET, entity, Map.class);

            if (response.getStatusCode() == HttpStatus.OK && response.getBody() != null) {
                Map<String, Object> result = response.getBody();

                // SpEL examples only when paths are non-empty (cold-start returns paths=[]).
                if (result.get("paths") instanceof List<?> paths && !paths.isEmpty()) {
                    List<String> spelExamples = paths.stream()
                        .map(Object::toString)
                        .filter(p -> !p.contains("[]"))
                        .limit(5)
                        .map(p -> {
                            String path = p.split(" -> ")[0];
                            return "#result['" + path.replace(".", "']['") + "']";
                        })
                        .toList();
                    result.put("spelExamples", spelExamples);
                }

                // Augment with the input parameter contract so the agent knows which values
                // are admissible BEFORE calling catalog(action='execute'). Without this, the
                // params enriched with default/allowedValues stay invisible to the LLM and
                // agents re-derive enums by trial-and-error on execute() failures.
                // ALSO load-bearing on cold-start: the response skeleton may be null because
                // the tool has never been executed, but the inputSchema is static and lets
                // the agent build a well-formed execute() call to seed the skeleton.
                // Single /info fetch feeds BOTH the input contract and the credential
                // requirement below - they come from the same endpoint, so we never double-GET.
                Map<String, Object> info = fetchToolInfo(toolId, headers);
                if (info != null) {
                    List<Map<String, Object>> inputSchema = slimInputSchema(info);
                    if (inputSchema != null && !inputSchema.isEmpty()) {
                        result.put("inputSchema", inputSchema);
                    }
                    // Surface the credential requirement so the agent learns, BEFORE execute(),
                    // whether the tool needs a credential and of what KIND (api_key, oauth2,
                    // bearer_token, basic_auth, none) - and which OAuth scopes a connect would
                    // request. Without this the type lived only in the DB and the agent could
                    // not tell the user what request_credential will trigger.
                    result.put("credential", CredentialTypeNormalizer.buildRequirement(info));
                }

                return ToolExecutionResult.success(result);
            } else {
                return ToolExecutionResult.failure(ToolErrorCode.RESOURCE_NOT_FOUND,
                    "Tool response schema not found for: " + toolId);
            }

        } catch (Exception e) {
            log.error("Error fetching tool response schema for {}: {}", toolId, e.getMessage());
            return ToolExecutionResult.failure(ToolErrorCode.EXECUTION_FAILED,
                "Failed to fetch schema: " + e.getMessage());
        }
    }

    /**
     * Fetches the tool's {@code /api/catalog/tools/{id}/info} body once. Both the input
     * contract ({@link #slimInputSchema}) and the credential requirement
     * ({@link CredentialTypeNormalizer#buildRequirement}) are derived from this single
     * response, so we never issue two GETs for the same tool.
     *
     * <p>Network failure or a missing tool returns {@code null} - the caller falls back to
     * "no inputSchema / no credential field" rather than failing the whole response_schema call.
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> fetchToolInfo(String toolId, HttpHeaders headers) {
        try {
            String url = "http://localhost:" + serverPort + "/api/catalog/tools/" + toolId + "/info";
            ResponseEntity<Map> response = restTemplate.exchange(
                    url, HttpMethod.GET, new HttpEntity<>(headers), Map.class);
            if (response.getStatusCode() != HttpStatus.OK || response.getBody() == null) {
                return null;
            }
            return response.getBody();
        } catch (Exception e) {
            log.debug("Could not fetch /info for tool {}: {}", toolId, e.getMessage());
            return null;
        }
    }

    /**
     * Slims the typed input contract from a {@code /info} body
     * (enriched in Phase A5 with {@code defaultValue}/{@code allowedValues}) into a list
     * ready for the LLM. Filters empty/null fields so the agent context window isn't padded
     * with noise. Returns {@code null} when the body carries no {@code parameters} array.
     */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> slimInputSchema(Map<String, Object> info) {
        try {
            Object rawParams = info.get("parameters");
            if (!(rawParams instanceof List<?> rawList)) {
                return null;
            }
            List<Map<String, Object>> result = new ArrayList<>(rawList.size());
            for (Object o : rawList) {
                if (!(o instanceof Map<?, ?> p)) continue;
                Map<String, Object> slim = new LinkedHashMap<>();
                Object name = p.get("name");
                if (name == null) continue;
                slim.put("name", name);
                // /info exposes data type as `dataType` and parameter location as `type`.
                Object dataType = p.get("dataType");
                if (dataType != null) slim.put("type", dataType);
                Object location = p.get("type");
                if (location instanceof String s
                        && (s.equals("header") || s.equals("path")
                            || s.equals("query") || s.equals("body"))) {
                    slim.put("location", s);
                }
                Object req = p.get("required");
                if (req instanceof Boolean b) {
                    slim.put("required", b);
                }
                Object desc = p.get("description");
                if (desc instanceof String ds && !ds.isBlank()) {
                    slim.put("description", ds);
                }
                Object def = p.get("defaultValue");
                if (def != null && !"".equals(def)) {
                    slim.put("default", def);
                }
                // exampleValue is concrete signal for the LLM ("here's what a typical
                // call looks like") - often more useful than the abstract type alone.
                // Drop blanks so a missing example doesn't pollute the prompt.
                Object example = p.get("exampleValue");
                if (example instanceof String es && !es.isBlank()) {
                    slim.put("example", es);
                } else if (example != null && !"".equals(example) && !(example instanceof String)) {
                    slim.put("example", example);
                }
                Object allowed = p.get("allowedValues");
                if (allowed instanceof List<?> al && !al.isEmpty()) {
                    slim.put("allowedValues", al);
                }
                result.add(slim);
            }
            return result;
        } catch (Exception e) {
            log.debug("Could not slim inputSchema from /info body: {}", e.getMessage());
            return null;
        }
    }
}
