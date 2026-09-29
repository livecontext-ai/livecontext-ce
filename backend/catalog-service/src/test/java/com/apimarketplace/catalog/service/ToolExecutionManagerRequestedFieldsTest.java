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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The chain from the caller's parameters to the projection mode, with the REAL orchestrator and
 * projector. The unit tests on {@code OutputProjector} and {@code RequestedFieldSelection} each
 * prove their half; only this one fails if {@code ToolExecutionManager} stops passing the
 * caller's selection on, which would bring the silent drop straight back.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ToolExecutionManager - fields the caller selected survive the output projection")
class ToolExecutionManagerRequestedFieldsTest {

    private static final String TOOL = "twitter/get_user_timeline";
    private static final UUID TOOL_ID = UUID.randomUUID();
    private static final String SCHEMA = "[{\"key\":\"data\",\"type\":\"array\",\"description\":\"\","
        + "\"children\":[{\"key\":\"id\",\"type\":\"string\",\"description\":\"\"},"
        + "{\"key\":\"text\",\"type\":\"string\",\"description\":\"\"}]}]";

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
    /** In-memory stand-in for Redis, keyed like the real cache: key + the call's parameters. */
    private final Map<List<Object>, Object> cacheStore = new java.util.HashMap<>();

    @BeforeEach
    void setUp() {
        ToolExecutionOrchestrator orchestrator =
            new ToolExecutionOrchestrator(new OutputProjector(new ObjectMapper()));
        manager = new ToolExecutionManager(
                toolContextService, apiService, new ObjectMapper(), responseShaper,
                nextActionBuilder, responseCache, toolNextHintRepository, toolResponseService,
                orchestrator, binaryResponseHandler, catalogBillingService,
                credentialClient, apiRepository, /* ceCatalogCloudRelay */ null);

        context = new ToolContextService.ToolContext();
        context.setApiId(UUID.randomUUID().toString());
        context.setToolName("get_user_timeline");
        context.setOutputSchemaJson(SCHEMA);
        context.setExecutionMode("sync");
        context.setToolId(TOOL_ID.toString());
        when(toolContextService.loadToolContext(TOOL)).thenReturn(Optional.of(context));

        Map<String, Object> tweet = new LinkedHashMap<>();
        tweet.put("id", "1");
        tweet.put("text", "hello https://t.co/x");
        tweet.put("attachments", Map.of("media_keys", List.of("13_1")));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("data", List.of(tweet));
        body.put("includes", Map.of("media", List.of(Map.of("media_key", "13_1", "type", "video"))));
        lenient().when(apiService.executeApiTool(anyString(), anyString(), any(), any(), anyString()))
                .thenAnswer(inv -> {
                    Map<String, Object> result = new LinkedHashMap<>();
                    result.put("success", true);
                    result.put("data", body);
                    return result;
                });
        lenient().when(responseShaper.shape(any(), any(), any(), any(), anyBoolean()))
                .thenAnswer(inv -> new ResponseShaper.ShapingResult(
                        inv.getArgument(0), List.of(), ResponseShaper.Action.UNTOUCHED, 1, 1));
        lenient().when(nextActionBuilder.build(any(), any(), any())).thenReturn(Optional.empty());
        lenient().when(binaryResponseHandler.dehydrateInlineBase64(any(), anyString(), anyString()))
                .thenReturn(null);
        lenient().when(responseCache.get(anyString(), anyMap()))
                .thenAnswer(inv -> cacheStore.get(List.of(inv.getArgument(0), inv.getArgument(1))));
        lenient().doAnswer(inv -> {
            cacheStore.put(List.of(inv.getArgument(0), inv.getArgument(1)), inv.getArgument(2));
            return null;
        }).when(responseCache).put(anyString(), anyMap(), any());
    }

    private Map<?, ?> run(Map<String, Object> parameters) {
        return run(parameters, "RUN");        // no response cache on this path
    }

    private Map<?, ?> run(Map<String, Object> parameters, String scopeKind) {
        ToolExecutionRequest request = ToolExecutionRequest.builder()
                .parameters(parameters)
                .billingScopeKind(scopeKind)
                .billingScopeId(scopeKind.toLowerCase() + "-1")
                .build();
        ToolExecutionResponse response = manager.executeTool(TOOL, request, "user-1", "org-1", "req-1");
        assertThat(response.getResult()).isInstanceOf(Map.class);
        return (Map<?, ?>) response.getResult();
    }

    @Test
    @DisplayName("REGRESSION 2026-09-28: tweet.fields=attachments + expansions come back, green AND complete")
    void selectedFieldsSurvive() {
        Map<?, ?> out = run(Map.of("id", "2040536646546755584",
                "tweet.fields", "attachments", "expansions", "attachments.media_keys"));

        Map<?, ?> tweet = (Map<?, ?>) ((List<?>) out.get("data")).get(0);
        assertThat(tweet.get("attachments")).isEqualTo(Map.of("media_keys", List.of("13_1")));
        assertThat(out.get("includes")).isNotNull();
    }

    @Test
    @DisplayName("a widened call seeds the skeleton from the DEFAULT projection of the same answer")
    void widenedCallSeedsTheSkeletonFromTheDefaultProjection() {
        run(Map.of("id", "1", "tweet.fields", "attachments"), "STREAM");

        // Skipping the save instead would leave response_schema empty forever on a tool every
        // caller widens; saving the widened tree would teach it fields a plain call never returns.
        verify(toolResponseService).autoSaveFromExecution(eq(TOOL_ID), argThat(saved -> {
            Map<?, ?> tweet = (Map<?, ?>) ((List<?>) ((Map<?, ?>) saved).get("data")).get(0);
            return tweet.keySet().equals(java.util.Set.of("id", "text")) && ((Map<?, ?>) saved).size() == 1;
        }), any());
    }

    @Test
    @DisplayName("a tool with no output schema seeds the skeleton from the answer itself, widened or not")
    void schemaLessToolSeedsFromTheAnswer() {
        context.setOutputSchemaJson(null);

        run(Map.of("id", "1", "tweet.fields", "attachments"), "STREAM");

        verify(toolResponseService).autoSaveFromExecution(eq(TOOL_ID),
            argThat(saved -> ((Map<?, ?>) saved).containsKey("includes")), any());
    }

    @Test
    @DisplayName("a widened answer is cached under its own parameters: a later plain call gets the default shape")
    void cacheKeepsTheTwoShapesApart() {
        Map<?, ?> widened = run(Map.of("id", "1", "tweet.fields", "attachments"), "STREAM");
        Map<?, ?> plain = run(Map.of("id", "1"), "STREAM");

        assertThat(widened.containsKey("includes")).isTrue();
        assertThat(List.copyOf(plain.keySet())).isEqualTo(List.of("data"));
    }

    @Test
    @DisplayName("a plain call still seeds the skeleton, from the default projection")
    void plainCallStillSeedsTheSkeleton() {
        run(Map.of("id", "1"), "STREAM");

        verify(toolResponseService).autoSaveFromExecution(eq(TOOL_ID), any(), any());
    }

    @Test
    @DisplayName("a call that selected nothing projects exactly as before: undeclared fields dropped")
    void callWithoutSelectionIsUnchanged() {
        Map<?, ?> out = run(Map.of("id", "2040536646546755584"));

        Map<?, ?> tweet = (Map<?, ?>) ((List<?>) out.get("data")).get(0);
        assertThat(List.copyOf(tweet.keySet())).isEqualTo(List.of("id", "text"));
        assertThat(List.copyOf(out.keySet())).isEqualTo(List.of("data"));
    }
}
