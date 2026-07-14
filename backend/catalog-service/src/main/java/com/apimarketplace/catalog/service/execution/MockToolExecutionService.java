package com.apimarketplace.catalog.service.execution;

import com.apimarketplace.catalog.domain.dto.ToolExecutionResponse;
import com.apimarketplace.catalog.dto.ToolResponseDto;
import com.apimarketplace.catalog.service.ResponseShaper;
import com.apimarketplace.catalog.service.ToolContextService;
import com.apimarketplace.catalog.service.ToolResponseService;
import com.apimarketplace.catalog.service.exception.ToolNotFoundException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Mock execution: serves a tool's DEFAULT example response ({@code tool_responses}
 * row, imported from the seed {@code apiFixtures} or synthesized from the output
 * schema) through the SAME post-processing pipeline as a real execution -
 * {@code ToolExecutionOrchestrator.projectResult} (output-schema projection) then
 * {@code ResponseShaper} in WORKFLOW mode - so the returned payload is byte-shaped
 * exactly like what a real run would produce.
 *
 * <p>Deliberately absent compared to {@code ToolExecutionManager.executeTool}:
 * no HTTP call, no credential resolution, no billing hook, no response cache,
 * no binary dehydration (example data never carries real binaries). Consumed by
 * the orchestrator's per-node mock mode ({@code CatalogMockClient}) and never by
 * production tool execution.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MockToolExecutionService {

    private final ToolContextService toolContextService;
    private final ToolResponseService toolResponseService;
    private final ToolExecutionOrchestrator toolExecutionOrchestrator;
    private final ResponseShaper responseShaper;
    private final ObjectMapper objectMapper;

    /** Thrown when the tool exists but has no usable default example row. */
    public static class MockExampleNotFoundException extends RuntimeException {
        public MockExampleNotFoundException(String message) {
            super(message);
        }
    }

    /**
     * Builds the mock execution response for a tool.
     *
     * @param toolIdOrSlug tool UUID or {@code apiSlug/toolSlug} (same resolution as real execute)
     * @param requestId    request correlation id (echoed back)
     * @throws ToolNotFoundException        when the tool does not exist
     * @throws MockExampleNotFoundException when no default example row exists or it is empty
     */
    public ToolExecutionResponse executeMockTool(String toolIdOrSlug, String requestId) {
        long start = System.currentTimeMillis();
        ToolContextService.ToolContext context = toolContextService.loadToolContext(toolIdOrSlug)
                .orElseThrow(() -> new ToolNotFoundException(toolIdOrSlug));

        ToolResponseDto defaultResponse = loadDefaultResponse(context, toolIdOrSlug)
                .orElseThrow(() -> new MockExampleNotFoundException(
                        "No default example response configured for tool '" + toolIdOrSlug + "'"));

        Object exampleData = parseExample(defaultResponse, toolIdOrSlug);

        // Same projection discipline as the real path: schema-less tools pass through,
        // projector failures fall back to the raw example (never fail the mock for a
        // projection edge case the real path would also have survived).
        if (context.getOutputSchemaJson() != null) {
            try {
                exampleData = toolExecutionOrchestrator.projectResult(
                        exampleData, context.getOutputSchemaJson(), context.getExecutionMode());
            } catch (Exception projectorEx) {
                log.warn("OutputProjector failed for mock of tool {}: {} - falling back to raw example",
                        context.getToolName(), projectorEx.getMessage());
            }
        }

        // WORKFLOW shaping mode - mock consumers are workflow nodes (per-leaf
        // truncation only, array shapes preserved for SpEL consumers).
        ResponseShaper.ShapingResult shapingResult =
                responseShaper.shape(exampleData, null, null, ResponseShaper.Mode.WORKFLOW);
        Object finalResult = shapingResult.data();

        int statusCode = defaultResponse.getStatusCode() != null ? defaultResponse.getStatusCode() : 200;

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("toolName", context.getToolName());
        metadata.put("endpoint", context.getEndpoint());
        metadata.put("method", context.getHttpMethod());
        metadata.put("apiId", context.getApiId());
        if (context.getIconSlug() != null) {
            metadata.put("iconSlug", context.getIconSlug());
        }
        metadata.put("status", "mocked");
        metadata.put("mock", true);
        metadata.put("httpStatus", Map.of("code", statusCode));

        return ToolExecutionResponse.builder()
                .success(true)
                .result(finalResult)
                .metadata(metadata)
                .executionTimeMs(System.currentTimeMillis() - start)
                .toolId(context.getToolId())
                .requestId(requestId)
                .build();
    }

    private Optional<ToolResponseDto> loadDefaultResponse(ToolContextService.ToolContext context,
                                                          String toolIdOrSlug) {
        if (context.getToolId() != null) {
            try {
                return toolResponseService.getDefaultResponseByToolId(UUID.fromString(context.getToolId()));
            } catch (IllegalArgumentException notAUuid) {
                // fall through to slug lookup
            }
        }
        return toolResponseService.getDefaultResponseByToolSlug(toolIdOrSlug);
    }

    /** Prefers {@code example_jsonb}; falls back to the legacy {@code example} text when it parses as JSON. */
    private Object parseExample(ToolResponseDto response, String toolIdOrSlug) {
        String json = response.getExampleJsonb();
        if (json == null || json.isBlank()) {
            json = response.getExample();
        }
        if (json == null || json.isBlank()) {
            throw new MockExampleNotFoundException(
                    "Default example response for tool '" + toolIdOrSlug + "' is empty");
        }
        try {
            return objectMapper.readValue(json, Object.class);
        } catch (Exception e) {
            throw new MockExampleNotFoundException(
                    "Default example response for tool '" + toolIdOrSlug + "' is not valid JSON: " + e.getMessage());
        }
    }
}
