package com.apimarketplace.orchestrator.tools.workflow.builder;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Fetches tool schemas from the catalog service.
 * Used to get output schemas for workflow steps.
 */
@Slf4j
@Component
public class ToolSchemaFetcher {

    private final RestTemplate restTemplate;

    @Value("${catalog.service.url:http://localhost:8081}")
    private String catalogServiceUrl;

    public ToolSchemaFetcher() {
        this.restTemplate = new RestTemplate();
    }

    /**
     * Fetch the response schema (skeleton) for a tool.
     *
     * @param toolId The tool UUID
     * @return Map containing skeleton, paths, and tool info
     */
    public Optional<ToolSchemaResult> fetchToolSchema(String toolId) {
        if (toolId == null || toolId.isBlank()) {
            return Optional.empty();
        }

        // Skip catalog fetch for non-UUID tool IDs (CRUD operations, compound IDs, etc.)
        if (!isValidUUID(toolId)) {
            log.debug("Skipping catalog fetch for non-UUID toolId: {}", toolId);
            return Optional.empty();
        }

        try {
            String url = catalogServiceUrl + "/api/v1/structure/tool/" + toolId + "/skeleton";

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<Void> entity = new HttpEntity<>(headers);

            @SuppressWarnings("unchecked")
            ResponseEntity<Map> response = restTemplate.exchange(url, HttpMethod.GET, entity, Map.class);

            if (response.getStatusCode() == HttpStatus.OK && response.getBody() != null) {
                @SuppressWarnings("unchecked")
                Map<String, Object> body = response.getBody();

                return Optional.of(ToolSchemaResult.builder()
                        .toolId(toolId)
                        .skeleton(body.get("skeleton"))
                        .paths(extractPaths(body))
                        .toolName((String) body.get("toolName"))
                        .toolDescription((String) body.get("description"))
                        .build());
            }

            return Optional.empty();

        } catch (org.springframework.web.client.HttpClientErrorException.NotFound e) {
            log.debug("No schema available for tool {}", toolId);
            return Optional.empty();
        } catch (Exception e) {
            log.warn("Failed to fetch tool schema for {}: {}", toolId, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Tri-state result for tool existence checks. Lets callers distinguish a
     * deterministic "tool does not exist" (404 / non-UUID input) from a
     * transient catalog outage where we genuinely don't know.
     */
    public enum ToolExistence { EXISTS, NOT_FOUND, UNKNOWN }

    /**
     * Reserved tool-ID sentinels that bypass catalog existence checks. These are
     * pseudo-IDs the workflow engine handles internally (no catalog lookup).
     * Centralized here so callers (McpCreator, WorkflowBuilderPlanExporter,
     * StepValidator) all stay in sync.
     */
    public static final Set<String> RESERVED_TOOL_SENTINELS = Set.of("__transform__", "__wait__");

    public static boolean isReservedToolSentinel(String toolId) {
        return toolId != null && RESERVED_TOOL_SENTINELS.contains(toolId);
    }

    // Tiny in-memory cache for fetchToolInfo. Three calls per node creation
    // (info + skeleton + input schema) used to all hit the catalog uncached;
    // most workflow construction sessions reuse the same handful of toolIds.
    private static final Duration TOOL_INFO_TTL = Duration.ofMinutes(5);
    private static final int CACHE_SOFT_CAP = 2000;
    private final ConcurrentHashMap<String, CachedToolInfo> toolInfoCache = new ConcurrentHashMap<>();

    private record CachedToolInfo(Optional<Map<String, Object>> info, ToolExistence existence, Instant fetchedAt) {}

    /**
     * Insert into the cache with opportunistic eviction. Bounds the map at
     * CACHE_SOFT_CAP entries: when exceeded, sweep expired entries (cheap walk)
     * and, if still over, drop the oldest. Keeps the cache from growing
     * unbounded under fuzzing/spam without pulling in a full LRU dependency.
     */
    private void cachePut(String toolId, CachedToolInfo entry) {
        toolInfoCache.put(toolId, entry);
        if (toolInfoCache.size() > CACHE_SOFT_CAP) {
            Instant cutoff = Instant.now().minus(TOOL_INFO_TTL);
            toolInfoCache.entrySet().removeIf(e -> e.getValue().fetchedAt.isBefore(cutoff));
            // If still over (every entry fresh), drop the absolute oldest until under.
            while (toolInfoCache.size() > CACHE_SOFT_CAP) {
                toolInfoCache.entrySet().stream()
                    .min(Comparator.comparing(e -> e.getValue().fetchedAt))
                    .ifPresent(oldest -> toolInfoCache.remove(oldest.getKey()));
            }
        }
    }

    /**
     * Fetch basic tool info (name, description, iconSlug) without full schema.
     */
    public Optional<Map<String, Object>> fetchToolInfo(String toolId) {
        return resolveToolInfo(toolId).info;
    }

    /**
     * Definitive existence check that distinguishes a real "not found" from a
     * transient outage. Use this when the answer drives a hard rejection.
     */
    public ToolExistence checkToolExists(String toolId) {
        return resolveToolInfo(toolId).existence;
    }

    private CachedToolInfo resolveToolInfo(String toolId) {
        if (toolId == null || toolId.isBlank()) {
            return new CachedToolInfo(Optional.empty(), ToolExistence.NOT_FOUND, Instant.now());
        }

        // Cache lookup - keyed on the raw toolId so both UUID and slug forms cache separately.
        CachedToolInfo cached = toolInfoCache.get(toolId);
        if (cached != null && Duration.between(cached.fetchedAt, Instant.now()).compareTo(TOOL_INFO_TTL) < 0) {
            return cached;
        }

        // Dispatch by shape: UUID → /api/catalog/tools/{uuid}/info (schema endpoint),
        // apiSlug/toolSlug → /api/workflow-inspector/tools/{toolSlug} (slug endpoint).
        // Anything else (fabricated labels like "Label_1", CRUD pseudo-IDs) is deterministically
        // NOT_FOUND so the LLM-workflow validator hard-rejects it.
        if (isValidUUID(toolId)) {
            return fetchViaUuid(toolId);
        }
        if (toolId.contains("/")) {
            // Format "apiSlug/toolSlug" - legacy plans imported via snapshots or older MCP
            // plan-writers stored this shape. The catalog HAS these tools indexed by
            // tool_slug (which already includes the api-slug prefix, e.g. "gmail-list-messages"),
            // so we strip everything before the slash and look up by slug.
            int slashIdx = toolId.indexOf('/');
            String toolSlug = slashIdx >= 0 ? toolId.substring(slashIdx + 1) : toolId;
            return fetchViaSlug(toolId, toolSlug);
        }
        log.debug("Skipping catalog fetch for non-UUID non-slug toolId: {}", toolId);
        return new CachedToolInfo(Optional.empty(), ToolExistence.NOT_FOUND, Instant.now());
    }

    private CachedToolInfo fetchViaUuid(String toolId) {
        try {
            String url = catalogServiceUrl + "/api/catalog/tools/" + toolId + "/info";
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<Void> entity = new HttpEntity<>(headers);

            @SuppressWarnings("unchecked")
            ResponseEntity<Map> response = restTemplate.exchange(url, HttpMethod.GET, entity, Map.class);

            if (response.getStatusCode() == HttpStatus.OK && response.getBody() != null) {
                CachedToolInfo entry = new CachedToolInfo(
                    Optional.of(response.getBody()), ToolExistence.EXISTS, Instant.now());
                cachePut(toolId, entry);
                return entry;
            }
            CachedToolInfo entry = new CachedToolInfo(Optional.empty(), ToolExistence.NOT_FOUND, Instant.now());
            cachePut(toolId, entry);
            return entry;
        } catch (HttpClientErrorException.NotFound nf) {
            CachedToolInfo entry = new CachedToolInfo(Optional.empty(), ToolExistence.NOT_FOUND, Instant.now());
            cachePut(toolId, entry);
            return entry;
        } catch (Exception e) {
            log.warn("Catalog UUID lookup failed for tool {} (transient): {}", toolId, e.getMessage());
            return new CachedToolInfo(Optional.empty(), ToolExistence.UNKNOWN, Instant.now());
        }
    }

    /**
     * Slug-form resolver for {@code apiSlug/toolSlug} ids. Uses the workflow-inspector
     * slug endpoint which indexes by {@code tool_slug} column (already api-prefixed, e.g.
     * {@code gmail-list-messages}). Used when the LLM plan-writer or a snapshot import
     * produced slash-form ids instead of catalog UUIDs - so the validator can still tell
     * whether the tool actually exists rather than rejecting the whole plan.
     */
    private CachedToolInfo fetchViaSlug(String cacheKey, String toolSlug) {
        try {
            String url = catalogServiceUrl + "/api/workflow-inspector/tools/" + toolSlug;
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<Void> entity = new HttpEntity<>(headers);

            @SuppressWarnings("unchecked")
            ResponseEntity<Map> response = restTemplate.exchange(url, HttpMethod.GET, entity, Map.class);

            if (response.getStatusCode() == HttpStatus.OK && response.getBody() != null) {
                CachedToolInfo entry = new CachedToolInfo(
                    Optional.of(response.getBody()), ToolExistence.EXISTS, Instant.now());
                cachePut(cacheKey, entry);
                return entry;
            }
            CachedToolInfo entry = new CachedToolInfo(Optional.empty(), ToolExistence.NOT_FOUND, Instant.now());
            cachePut(cacheKey, entry);
            return entry;
        } catch (HttpClientErrorException.NotFound nf) {
            CachedToolInfo entry = new CachedToolInfo(Optional.empty(), ToolExistence.NOT_FOUND, Instant.now());
            cachePut(cacheKey, entry);
            return entry;
        } catch (Exception e) {
            log.warn("Catalog slug lookup failed for tool {} (transient): {}", cacheKey, e.getMessage());
            return new CachedToolInfo(Optional.empty(), ToolExistence.UNKNOWN, Instant.now());
        }
    }

    @SuppressWarnings("unchecked")
    private List<String> extractPaths(Map<String, Object> body) {
        Object paths = body.get("paths");
        if (paths instanceof List) {
            return (List<String>) paths;
        }
        return List.of();
    }

    /**
     * Convert paths to output schema map (field -> type).
     */
    public Map<String, String> pathsToOutputSchema(List<String> paths) {
        Map<String, String> outputs = new LinkedHashMap<>();
        for (String path : paths) {
            // Format: "field.subfield -> type"
            String[] parts = path.split(" -> ");
            if (parts.length == 2) {
                outputs.put(parts[0], parts[1]);
            }
        }
        return outputs;
    }

    /**
     * Generate reference syntax for step outputs.
     */
    public Map<String, String> generateReferenceSyntax(String stepNodeId, List<String> paths) {
        Map<String, String> refs = new LinkedHashMap<>();
        for (String path : paths) {
            String[] parts = path.split(" -> ");
            if (parts.length >= 1) {
                String fieldPath = parts[0];
                // Skip array paths for simple reference
                if (!fieldPath.contains("[]")) {
                    String ref = "{{" + stepNodeId + ".output." + fieldPath.replace(".", ".") + "}}";
                    refs.put(fieldPath, ref);
                }
            }
        }
        // Limit to prevent excessively large responses
        if (refs.size() > 100) {
            Map<String, String> limited = new LinkedHashMap<>();
            int count = 0;
            for (Map.Entry<String, String> entry : refs.entrySet()) {
                if (count++ >= 100) break;
                limited.put(entry.getKey(), entry.getValue());
            }
            return limited;
        }
        return refs;
    }

    /**
     * Fetch tool info including input parameters.
     * Returns required and optional input parameters for the tool.
     */
    public Optional<ToolInputSchema> fetchToolInputSchema(String toolId) {
        Optional<Map<String, Object>> toolInfoOpt = fetchToolInfo(toolId);
        if (toolInfoOpt.isEmpty()) {
            return Optional.empty();
        }

        Map<String, Object> toolInfo = toolInfoOpt.get();
        String toolName = (String) toolInfo.get("name");
        String toolDescription = (String) toolInfo.get("description");

        // Extract input parameters from the tool definition
        @SuppressWarnings("unchecked")
        Map<String, Object> inputSchema = (Map<String, Object>) toolInfo.get("inputSchema");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> parameters = (List<Map<String, Object>>) toolInfo.get("parameters");

        Map<String, ToolParameter> requiredParams = new LinkedHashMap<>();
        Map<String, ToolParameter> optionalParams = new LinkedHashMap<>();

        // Try parameters list first (common format)
        if (parameters != null && !parameters.isEmpty()) {
            for (Map<String, Object> param : parameters) {
                String paramName = (String) param.get("name");
                String paramType = (String) param.get("type");
                String paramDesc = (String) param.get("description");
                Boolean required = (Boolean) param.get("required");

                if (paramName != null) {
                    ToolParameter toolParam = new ToolParameter(paramName, paramType, paramDesc);
                    if (Boolean.TRUE.equals(required)) {
                        requiredParams.put(paramName, toolParam);
                    } else {
                        optionalParams.put(paramName, toolParam);
                    }
                }
            }
        }
        // Fallback to inputSchema.properties (OpenAPI style)
        else if (inputSchema != null) {
            @SuppressWarnings("unchecked")
            Map<String, Object> properties = (Map<String, Object>) inputSchema.get("properties");
            @SuppressWarnings("unchecked")
            List<String> required = (List<String>) inputSchema.get("required");
            Set<String> requiredSet = required != null ? new HashSet<>(required) : Set.of();

            if (properties != null) {
                for (Map.Entry<String, Object> entry : properties.entrySet()) {
                    String paramName = entry.getKey();
                    @SuppressWarnings("unchecked")
                    Map<String, Object> propDef = (Map<String, Object>) entry.getValue();
                    String paramType = (String) propDef.get("type");
                    String paramDesc = (String) propDef.get("description");

                    ToolParameter toolParam = new ToolParameter(paramName, paramType, paramDesc);
                    if (requiredSet.contains(paramName)) {
                        requiredParams.put(paramName, toolParam);
                    } else {
                        optionalParams.put(paramName, toolParam);
                    }
                }
            }
        }

        return Optional.of(ToolInputSchema.builder()
                .toolId(toolId)
                .toolName(toolName)
                .toolDescription(toolDescription)
                .requiredParameters(requiredParams)
                .optionalParameters(optionalParams)
                .build());
    }

    /**
     * Check if a string is a valid UUID format.
     * Returns false for CRUD operations (e.g., "insert_row"),
     * compound tool IDs (e.g., "api-slug/tool-slug"), and other non-UUID strings.
     */
    private boolean isValidUUID(String str) {
        if (str == null || str.isBlank()) {
            return false;
        }
        try {
            java.util.UUID.fromString(str);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /**
     * Result of fetching a tool schema.
     */
    @lombok.Data
    @lombok.Builder
    public static class ToolSchemaResult {
        private String toolId;
        private String toolName;
        private String toolDescription;
        private Object skeleton;
        private List<String> paths;
    }

    /**
     * Tool input parameter info.
     */
    public record ToolParameter(String name, String type, String description) {}

    /**
     * Tool input schema with required and optional parameters.
     */
    @lombok.Data
    @lombok.Builder
    public static class ToolInputSchema {
        private String toolId;
        private String toolName;
        private String toolDescription;
        private Map<String, ToolParameter> requiredParameters;
        private Map<String, ToolParameter> optionalParameters;

        public boolean hasRequiredParameters() {
            return requiredParameters != null && !requiredParameters.isEmpty();
        }
    }
}
