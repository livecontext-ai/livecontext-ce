package com.apimarketplace.catalog.service.execution;

import com.apimarketplace.catalog.domain.dto.ToolExecutionResponse;
import com.apimarketplace.catalog.dto.ToolResponseDto;
import com.apimarketplace.catalog.service.ResponseShaper;
import com.apimarketplace.catalog.service.ToolContextService;
import com.apimarketplace.catalog.service.ToolResponseService;
import com.apimarketplace.catalog.service.exception.ToolNotFoundException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link MockToolExecutionService} - the catalog-side mock
 * execution that serves a tool's default example through the SAME projection
 * + shaping pipeline as a real execution (byte-shape parity), with no HTTP
 * call, no credentials and no billing.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("MockToolExecutionService - projected default example as mock execution")
class MockToolExecutionServiceTest {

    @Mock private ToolContextService toolContextService;
    @Mock private ToolResponseService toolResponseService;
    @Mock private ToolExecutionOrchestrator toolExecutionOrchestrator;
    @Mock private ResponseShaper responseShaper;

    private MockToolExecutionService service;

    private static final String TOOL_SLUG = "slack/post-message";
    private static final UUID TOOL_UUID = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new MockToolExecutionService(
                toolContextService, toolResponseService, toolExecutionOrchestrator,
                responseShaper, new ObjectMapper());
    }

    private ToolContextService.ToolContext context(String outputSchemaJson) {
        ToolContextService.ToolContext ctx = new ToolContextService.ToolContext();
        ctx.setToolId(TOOL_UUID.toString());
        ctx.setApiId(UUID.randomUUID().toString());
        ctx.setToolName("post_message");
        ctx.setEndpoint("/chat.postMessage");
        ctx.setHttpMethod("POST");
        ctx.setIconSlug("slack");
        ctx.setOutputSchemaJson(outputSchemaJson);
        ctx.setExecutionMode("sync");
        return ctx;
    }

    private ToolResponseDto defaultResponse(String exampleJsonb, Integer statusCode) {
        ToolResponseDto dto = new ToolResponseDto();
        dto.setToolId(TOOL_UUID);
        dto.setExampleJsonb(exampleJsonb);
        dto.setStatusCode(statusCode);
        dto.setIsDefault(true);
        return dto;
    }

    private void stubShaperPassthrough() {
        when(responseShaper.shape(any(), isNull(), isNull(), eq(ResponseShaper.Mode.WORKFLOW)))
                .thenAnswer(inv -> new ResponseShaper.ShapingResult(
                        inv.getArgument(0), java.util.List.of(), ResponseShaper.Action.UNTOUCHED, 0, 0));
    }

    @Test
    @DisplayName("unknown tool raises ToolNotFoundException (same contract as real execute)")
    void unknownToolThrows() {
        when(toolContextService.loadToolContext("nope/nothing")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.executeMockTool("nope/nothing", "req-1"))
                .isInstanceOf(ToolNotFoundException.class);
    }

    @Test
    @DisplayName("tool without a default example row raises MockExampleNotFoundException")
    void missingExampleThrows() {
        when(toolContextService.loadToolContext(TOOL_SLUG)).thenReturn(Optional.of(context(null)));
        when(toolResponseService.getDefaultResponseByToolId(TOOL_UUID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.executeMockTool(TOOL_SLUG, "req-1"))
                .isInstanceOf(MockToolExecutionService.MockExampleNotFoundException.class)
                .hasMessageContaining(TOOL_SLUG);
    }

    @Test
    @DisplayName("happy path: example is projected through the tool's output schema and returned with mock metadata")
    void happyPathProjectsAndShapes() {
        String schema = "[{\"key\":\"ok\",\"type\":\"boolean\"}]";
        when(toolContextService.loadToolContext(TOOL_SLUG)).thenReturn(Optional.of(context(schema)));
        when(toolResponseService.getDefaultResponseByToolId(TOOL_UUID))
                .thenReturn(Optional.of(defaultResponse("{\"ok\":true,\"channel\":\"C123\"}", 200)));
        Map<String, Object> projected = Map.of("ok", true);
        when(toolExecutionOrchestrator.projectResult(any(), eq(schema), eq("sync"))).thenReturn(projected);
        stubShaperPassthrough();

        ToolExecutionResponse response = service.executeMockTool(TOOL_SLUG, "req-42");

        assertThat(response.isSuccess()).isTrue();
        assertThat(response.getResult()).isEqualTo(projected);
        assertThat(response.getToolId()).isEqualTo(TOOL_UUID.toString());
        assertThat(response.getRequestId()).isEqualTo("req-42");
        assertThat(response.getMetadata())
                .containsEntry("mock", true)
                .containsEntry("status", "mocked")
                .containsEntry("toolName", "post_message")
                .containsEntry("iconSlug", "slack")
                .containsEntry("httpStatus", Map.of("code", 200));
        // Parity invariant: the projector received the PARSED example
        verify(toolExecutionOrchestrator).projectResult(eq(Map.of("ok", true, "channel", "C123")), eq(schema), eq("sync"));
    }

    @Test
    @DisplayName("schema-less tool: projector is not invoked, raw example is served (real-path parity)")
    void schemaLessSkipsProjection() {
        when(toolContextService.loadToolContext(TOOL_SLUG)).thenReturn(Optional.of(context(null)));
        when(toolResponseService.getDefaultResponseByToolId(TOOL_UUID))
                .thenReturn(Optional.of(defaultResponse("{\"ok\":true}", null)));
        stubShaperPassthrough();

        ToolExecutionResponse response = service.executeMockTool(TOOL_SLUG, "req-1");

        verify(toolExecutionOrchestrator, never()).projectResult(any(), any(), any());
        assertThat(response.getResult()).isEqualTo(Map.of("ok", true));
        // statusCode null defaults to 200 in the metadata envelope
        assertThat(response.getMetadata()).containsEntry("httpStatus", Map.of("code", 200));
    }

    @Test
    @DisplayName("projector failure falls back to the raw example (same discipline as the real path)")
    void projectorFailureFallsBack() {
        String schema = "[{\"key\":\"ok\",\"type\":\"boolean\"}]";
        when(toolContextService.loadToolContext(TOOL_SLUG)).thenReturn(Optional.of(context(schema)));
        when(toolResponseService.getDefaultResponseByToolId(TOOL_UUID))
                .thenReturn(Optional.of(defaultResponse("{\"ok\":true}", 200)));
        when(toolExecutionOrchestrator.projectResult(any(), any(), any()))
                .thenThrow(new IllegalStateException("projection exploded"));
        stubShaperPassthrough();

        ToolExecutionResponse response = service.executeMockTool(TOOL_SLUG, "req-1");

        assertThat(response.isSuccess()).isTrue();
        assertThat(response.getResult()).isEqualTo(Map.of("ok", true));
    }

    @Test
    @DisplayName("empty/invalid example JSON raises MockExampleNotFoundException with a clear message")
    void invalidExampleThrows() {
        when(toolContextService.loadToolContext(TOOL_SLUG)).thenReturn(Optional.of(context(null)));
        when(toolResponseService.getDefaultResponseByToolId(TOOL_UUID))
                .thenReturn(Optional.of(defaultResponse("  ", null)));

        assertThatThrownBy(() -> service.executeMockTool(TOOL_SLUG, "req-1"))
                .isInstanceOf(MockToolExecutionService.MockExampleNotFoundException.class)
                .hasMessageContaining("empty");

        when(toolResponseService.getDefaultResponseByToolId(TOOL_UUID))
                .thenReturn(Optional.of(defaultResponse("{not json", null)));
        assertThatThrownBy(() -> service.executeMockTool(TOOL_SLUG, "req-1"))
                .isInstanceOf(MockToolExecutionService.MockExampleNotFoundException.class)
                .hasMessageContaining("not valid JSON");
    }

    @Test
    @DisplayName("legacy rows: falls back to the 'example' text column when example_jsonb is blank")
    void legacyExampleFallback() {
        when(toolContextService.loadToolContext(TOOL_SLUG)).thenReturn(Optional.of(context(null)));
        ToolResponseDto dto = defaultResponse(null, 201);
        dto.setExample("{\"legacy\":true}");
        when(toolResponseService.getDefaultResponseByToolId(TOOL_UUID)).thenReturn(Optional.of(dto));
        stubShaperPassthrough();

        ToolExecutionResponse response = service.executeMockTool(TOOL_SLUG, "req-1");

        assertThat(response.getResult()).isEqualTo(Map.of("legacy", true));
        assertThat(response.getMetadata()).containsEntry("httpStatus", Map.of("code", 201));
    }
}
