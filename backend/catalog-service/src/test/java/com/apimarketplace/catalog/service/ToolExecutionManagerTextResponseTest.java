package com.apimarketplace.catalog.service;

import com.apimarketplace.catalog.domain.dto.ToolExecutionRequest;
import com.apimarketplace.catalog.domain.dto.ToolExecutionResponse;
import com.apimarketplace.catalog.repository.ApiRepository;
import com.apimarketplace.catalog.repository.ToolNextHintRepository;
import com.apimarketplace.catalog.service.billing.CatalogToolBillingService;
import com.apimarketplace.catalog.service.execution.BinaryResponseHandler;
import com.apimarketplace.catalog.service.execution.OutputProjector;
import com.apimarketplace.catalog.service.execution.ToolExecutionOrchestrator;
import com.apimarketplace.credential.client.CredentialClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * A TEXT answer (CSV, XML) reaches the caller through the entry path, with the REAL orchestrator
 * and projector and a real seed-shaped output schema.
 *
 * <p>Regression: once the engine started reading text bodies instead of failing on them, the
 * text went through object projection, which returns an empty map for anything that is not an
 * object. A tool with a schema then answered success with no data at all: charged, and green on
 * the tool-health board. 212 seed endpoints declare {@code response.type=text}.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ToolExecutionManager - a text answer is never dropped by the output projection")
class ToolExecutionManagerTextResponseTest {

    private static final String TOOL = "influxdb/query_data";

    @Mock private ToolContextService toolContextService;
    @Mock private ApiService apiService;
    @Mock private ResponseShaper responseShaper;
    @Mock private NextActionBuilder nextActionBuilder;
    @Mock private ResponseCache responseCache;
    @Mock private ToolNextHintRepository toolNextHintRepository;
    @Mock private ToolResponseService toolResponseService;
    @Mock private BinaryResponseHandler binaryResponseHandler;
    @Mock private CatalogToolBillingService catalogBillingService;
    @Mock private CredentialClient credentialClient;
    @Mock private ApiRepository apiRepository;

    private ToolExecutionManager manager;
    private ToolContextService.ToolContext context;
    private Object providerBody;

    @BeforeEach
    void setUp() {
        manager = new ToolExecutionManager(
                toolContextService, apiService, new ObjectMapper(), responseShaper,
                nextActionBuilder, responseCache, toolNextHintRepository, toolResponseService,
                new ToolExecutionOrchestrator(new OutputProjector(new ObjectMapper())),
                binaryResponseHandler, catalogBillingService, credentialClient, apiRepository, null);

        context = new ToolContextService.ToolContext();
        context.setApiId(UUID.randomUUID().toString());
        context.setToolName("query_data");
        context.setExecutionMode("sync");
        context.setToolId(UUID.randomUUID().toString());
        when(toolContextService.loadToolContext(TOOL)).thenReturn(Optional.of(context));

        lenient().when(apiService.executeApiTool(anyString(), anyString(), any(), any(), anyString()))
                .thenAnswer(inv -> {
                    Map<String, Object> result = new LinkedHashMap<>();
                    result.put("success", true);
                    result.put("status", 200);
                    result.put("httpStatus", Map.of("code", 200));
                    result.put("data", providerBody);
                    return result;
                });
        lenient().when(responseShaper.shape(any(), any(), any(), any(), anyBoolean()))
                .thenAnswer(inv -> new ResponseShaper.ShapingResult(
                        inv.getArgument(0), List.of(), ResponseShaper.Action.UNTOUCHED, 1, 1));
        lenient().when(nextActionBuilder.build(any(), any(), any())).thenReturn(Optional.empty());
        lenient().when(binaryResponseHandler.dehydrateInlineBase64(any(), anyString(), anyString()))
                .thenReturn(null);
        lenient().when(responseCache.get(anyString(), anyMap())).thenReturn(null);
    }

    private Map<?, ?> run() {
        ToolExecutionRequest request = ToolExecutionRequest.builder()
                .parameters(Map.of("query", "x"))
                .billingScopeKind("RUN")
                .billingScopeId("run-1")
                .build();
        ToolExecutionResponse response = manager.executeTool(TOOL, request, "user-1", "org-1", "req-1");
        assertThat(response.getResult()).isInstanceOf(Map.class);
        return (Map<?, ?>) response.getResult();
    }

    @Test
    @DisplayName("REGRESSION: a CSV tool with a single string field gets the CSV under that field")
    void csvLandsUnderTheSingleStringField() {
        context.setOutputSchemaJson("[{\"key\":\"csv\",\"type\":\"string\",\"description\":\"CSV result\"}]");
        providerBody = "_time,_value\n2026-09-29T00:00:00Z,1\n";

        Map<?, ?> out = run();

        assertThat(out.get("csv")).isEqualTo("_time,_value\n2026-09-29T00:00:00Z,1\n");
    }

    @Test
    @DisplayName("REGRESSION: an XML tool whose schema declares an object gets the raw XML under data, not {}")
    void xmlReachesTheCallerUnderData() {
        context.setOutputSchemaJson("[{\"key\":\"ResponseMetadata\",\"type\":\"object\",\"description\":\"\","
                + "\"children\":[{\"key\":\"RequestId\",\"type\":\"string\",\"description\":\"\"}]}]");
        providerBody = "<DeleteTopicResponse><ResponseMetadata><RequestId>r1</RequestId></ResponseMetadata>"
                + "</DeleteTopicResponse>";

        Map<?, ?> out = run();

        assertThat(out.get("data")).isEqualTo(providerBody);
        assertThat(out.containsKey("ResponseMetadata")).isFalse();
    }

    @Test
    @DisplayName("REGRESSION: a text body the dehydrator turned into a file reference lands under the single string field")
    void dehydratedTextUnderSingleStringField() {
        context.setOutputSchemaJson("[{\"key\":\"content\",\"type\":\"string\",\"description\":\"\"}]");
        providerBody = "QUJD".repeat(20_000);
        Map<String, Object> fileRef = Map.of("_type", "file", "path", "tool-assets/x.bin");
        when(binaryResponseHandler.dehydrateInlineBase64(any(), anyString(), anyString()))
                .thenReturn(new BinaryResponseHandler.DehydrationResult(fileRef,
                        List.of(new BinaryResponseHandler.DehydratedAsset("", fileRef))));

        Map<?, ?> out = run();

        assertThat(out.get("content")).isEqualTo(fileRef);
    }

    @Test
    @DisplayName("a dehydrated text body with an object schema lands under data, never merged or dropped")
    void dehydratedTextUnderData() {
        context.setOutputSchemaJson("[{\"key\":\"Result\",\"type\":\"object\",\"description\":\"\"}]");
        providerBody = "QUJD".repeat(20_000);
        Map<String, Object> fileRef = Map.of("_type", "file", "path", "tool-assets/x.bin");
        when(binaryResponseHandler.dehydrateInlineBase64(any(), anyString(), anyString()))
                .thenReturn(new BinaryResponseHandler.DehydrationResult(fileRef,
                        List.of(new BinaryResponseHandler.DehydratedAsset("", fileRef))));

        Map<?, ?> out = run();

        assertThat(out.get("data")).isEqualTo(fileRef);
        assertThat(out.containsKey("_type")).isFalse();
    }

    @Test
    @DisplayName("REGRESSION Typesense export_documents: NDJSON with a single string field keeps the JSONL text")
    void jsonLinesUnderSingleStringField() {
        context.setOutputSchemaJson("[{\"key\":\"documents\",\"type\":\"string\",\"description\":\"JSONL string\"}]");
        String jsonl = "{\"id\":\"1\"}\n{\"id\":\"2\"}\n";
        providerBody = new com.apimarketplace.catalog.service.execution.JsonLines(
                List.of(Map.of("id", "1"), Map.of("id", "2")), jsonl);

        Map<?, ?> out = run();

        assertThat(out.get("documents")).isEqualTo(jsonl);
    }

    @Test
    @DisplayName("a JSON object answer projects exactly as before")
    void jsonObjectUnchanged() {
        context.setOutputSchemaJson("[{\"key\":\"csv\",\"type\":\"string\",\"description\":\"\"}]");
        providerBody = Map.of("csv", "a", "extra", "dropped");

        Map<?, ?> out = run();

        assertThat(out.get("csv")).isEqualTo("a");
        assertThat(out.containsKey("extra")).isFalse();
    }
}
