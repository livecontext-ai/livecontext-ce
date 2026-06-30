package com.apimarketplace.agent.service.execution;

import com.apimarketplace.agent.domain.ToolCall;
import com.apimarketplace.agent.domain.ToolDefinition;
import com.apimarketplace.agent.domain.ToolResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withBadRequest;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

@DisplayName("RemoteToolExecutionService")
class RemoteToolExecutionServiceTest {

    private RemoteToolExecutionService service;
    private MockRestServiceServer server;

    @BeforeEach
    void setUp() {
        service = new RemoteToolExecutionService(new ObjectMapper());
        ReflectionTestUtils.setField(service, "datasourceUrl", "http://datasource.test");
        RestTemplate restTemplate = (RestTemplate) ReflectionTestUtils.getField(service, "restTemplate");
        server = MockRestServiceServer.bindTo(restTemplate).build();
    }

    @Test
    @DisplayName("forwards plain child credentials over namespaced parent credentials to remote tools")
    void forwardsPlainChildCredentialsToRemoteTools() {
        server.expect(requestTo("http://datasource.test/api/agent-tools/execute"))
            .andExpect(method(HttpMethod.POST))
            .andExpect(header("X-User-ID", "tenant-1"))
            .andExpect(header("X-Organization-ID", "org-1"))
            .andExpect(header("X-Organization-Role", "admin"))
            .andExpect(jsonPath("$.tool").value("table"))
            .andExpect(jsonPath("$.turnId").value("turn-1"))
            .andExpect(jsonPath("$.messageId").value("turn-1"))
            .andExpect(jsonPath("$.allowedTableIds[0]").value("child-table"))
            .andExpect(jsonPath("$.allowedWorkflowIds[0]").value("parent-workflow"))
            .andExpect(jsonPath("$.tableAccessMode").value("read"))
            .andExpect(jsonPath("$.workflowAccessMode").value("read"))
            .andExpect(jsonPath("$.workflowRunId").value("plain-run"))
            .andExpect(jsonPath("$.orgId").value("org-1"))
            .andRespond(withSuccess("{\"success\":true,\"data\":{\"ok\":true}}", MediaType.APPLICATION_JSON));

        ToolResult result = service.executeTool(
            new ToolCall("tool-call-1", "table", Map.of("action", "list"), null),
            ToolDefinition.builder().name("table").build(),
            "tenant-1",
            Map.ofEntries(
                Map.entry("turnId", "turn-1"),
                Map.entry("allowedTableIds", List.of("child-table")),
                Map.entry("__allowedTableIds__", List.of("parent-table")),
                Map.entry("__allowedWorkflowIds__", List.of("parent-workflow")),
                Map.entry("tableAccessMode", "read"),
                Map.entry("__tableAccessMode__", "write"),
                Map.entry("__workflowAccessMode__", "read"),
                Map.entry("orgId", "org-1"),
                Map.entry("orgRole", "admin"),
                Map.entry("workflowRunId", "plain-run"),
                Map.entry("__workflowRunId__", "parent-run")
            )
        );

        assertThat(result.success()).isTrue();
        server.verify();
    }

    /**
     * Invoke a remote {@code table} tool (routes to datasource-service) whose
     * /api/agent-tools/execute response body is exactly {@code responseJson}, so the
     * parseExecutionResponse contract can be exercised through the public executeTool entry.
     */
    private ToolResult executeTableExpectingResponse(String responseJson) {
        server.expect(requestTo("http://datasource.test/api/agent-tools/execute"))
            .andExpect(method(HttpMethod.POST))
            .andRespond(withSuccess(responseJson, MediaType.APPLICATION_JSON));

        return service.executeTool(
            new ToolCall("tool-call-1", "table", Map.of("action", "list"), null),
            ToolDefinition.builder().name("table").build(),
            "tenant-1",
            Map.of());
    }

    @Test
    @DisplayName("success result serializes the 'result' field to JSON content and reports no error")
    void parsesSuccessWithResultField() {
        ToolResult result = executeTableExpectingResponse(
            "{\"success\":true,\"result\":{\"rows\":[{\"id\":\"r1\"}]}}");

        assertThat(result.success()).isTrue();
        assertThat(result.error()).isNull();
        assertThat(result.content()).isEqualTo("{\"rows\":[{\"id\":\"r1\"}]}");
        server.verify();
    }

    @Test
    @DisplayName("explicit top-level error response yields a failed result carrying that error")
    void parsesTopLevelErrorResponse() {
        ToolResult result = executeTableExpectingResponse(
            "{\"success\":false,\"error\":\"table not found\"}");

        assertThat(result.success()).isFalse();
        assertThat(result.error()).isEqualTo("table not found");
        assertThat(result.content()).isNull();
        server.verify();
    }

    @Test
    @DisplayName("nested result.success=false downgrades an outer success and surfaces the nested error")
    void parsesNestedFailureWithinSuccessEnvelope() {
        ToolResult result = executeTableExpectingResponse(
            "{\"success\":true,\"result\":{\"success\":false,\"error\":\"row locked\"}}");

        assertThat(result.success()).isFalse();
        assertThat(result.error()).isEqualTo("row locked");
        server.verify();
    }

    @Test
    @DisplayName("absent success and error fields are inferred as success with empty-object content")
    void infersSuccessWhenNeitherSuccessNorErrorPresent() {
        ToolResult result = executeTableExpectingResponse("{}");

        assertThat(result.success()).isTrue();
        assertThat(result.error()).isNull();
        assertThat(result.content()).isEqualTo("{}");
        server.verify();
    }

    @Test
    @DisplayName("failure without an error field falls back to the 'message' field")
    void fallsBackToMessageFieldOnFailure() {
        ToolResult result = executeTableExpectingResponse(
            "{\"success\":false,\"message\":\"quota exceeded\"}");

        assertThat(result.success()).isFalse();
        assertThat(result.error()).isEqualTo("quota exceeded");
        server.verify();
    }

    @Test
    @DisplayName("failure with neither error nor message defaults the error to 'Unknown error'")
    void defaultsToUnknownErrorWhenFailureCarriesNoMessage() {
        ToolResult result = executeTableExpectingResponse("{\"success\":false}");

        assertThat(result.success()).isFalse();
        assertThat(result.error()).isEqualTo("Unknown error");
        server.verify();
    }

    @Test
    @DisplayName("response metadata is merged onto the result alongside the remote source tag")
    void passesThroughResponseMetadata() {
        ToolResult result = executeTableExpectingResponse(
            "{\"success\":true,\"data\":{\"ok\":true},\"metadata\":{\"rowCount\":7}}");

        assertThat(result.success()).isTrue();
        assertThat(result.metadata())
            .containsEntry("source", "agent_service_remote")
            .containsEntry("rowCount", 7);
        server.verify();
    }

    @Test
    @DisplayName("HTTP 4xx from an unknown tool surfaces the body error and remote_tool_error source")
    void parsesHttpErrorBodyForInvalidTool() {
        server.expect(requestTo("http://datasource.test/api/agent-tools/execute"))
            .andExpect(method(HttpMethod.POST))
            .andRespond(withBadRequest()
                .body("{\"error\":\"unknown tool: bogus\"}")
                .contentType(MediaType.APPLICATION_JSON));

        ToolResult result = service.executeTool(
            new ToolCall("tool-call-1", "table", Map.of("action", "list"), null),
            ToolDefinition.builder().name("table").build(),
            "tenant-1",
            Map.of());

        assertThat(result.success()).isFalse();
        assertThat(result.error()).isEqualTo("unknown tool: bogus");
        assertThat(result.metadata()).containsEntry("source", "remote_tool_error");
        server.verify();
    }

    @Test
    @DisplayName("a conversation-local tool with __toolCallbackUrl__ routes to that callback URL, not orchestrator")
    void conversationLocalToolRoutesToCallbackUrl() {
        // set_conversation_title is in CONVERSATION_LOCAL_TOOLS; with __toolCallbackUrl__
        // present it must POST to the callback URL (conversation-service), forwarding the
        // conversation context, instead of hopping through orchestrator/datasource.
        String callbackUrl = "http://conversation.test/api/internal/conversation/tool-callback";
        server.expect(requestTo(callbackUrl))
            .andExpect(method(HttpMethod.POST))
            .andExpect(header("X-User-ID", "tenant-1"))
            .andExpect(jsonPath("$.tool").value("set_conversation_title"))
            .andExpect(jsonPath("$.conversationId").value("conv-9"))
            .andExpect(jsonPath("$.turnId").value("turn-9"))
            .andRespond(withSuccess("{\"success\":true,\"data\":{\"titled\":true}}", MediaType.APPLICATION_JSON));

        ToolResult result = service.executeTool(
            new ToolCall("tool-call-conv", "set_conversation_title", Map.of("title", "Hello"), null),
            ToolDefinition.builder().name("set_conversation_title").build(),
            "tenant-1",
            Map.of(
                "__toolCallbackUrl__", callbackUrl,
                "conversationId", "conv-9",
                "turnId", "turn-9"
            ));

        assertThat(result.success()).isTrue();
        // Sole expectation is the callback URL: server.verify() fails if orchestrator was called instead.
        server.verify();
    }

    @Test
    @DisplayName("datasource-service 500 (HttpServerErrorException) yields a failed result, not the 4xx body-parse path")
    void datasourceServerErrorYieldsFailedResult() {
        // A 5xx surfaces as HttpServerErrorException, which is NOT the HttpClientErrorException
        // branch: it falls through to the generic catch and produces a "Remote execution error".
        server.expect(requestTo("http://datasource.test/api/agent-tools/execute"))
            .andExpect(method(HttpMethod.POST))
            .andRespond(withServerError());

        ToolResult result = service.executeTool(
            new ToolCall("tool-call-500", "table", Map.of("action", "list"), null),
            ToolDefinition.builder().name("table").build(),
            "tenant-1",
            Map.of());

        assertThat(result.success()).isFalse();
        assertThat(result.error()).startsWith("Remote execution error:");
        // remote_tool_error metadata belongs only to the 4xx body-parse path; the generic
        // catch leaves metadata unset (null), proving we took the server-error branch not the 4xx one.
        assertThat(result.metadata()).isNull();
        server.verify();
    }

    @Test
    @DisplayName("a transport-level failure (connection timeout) yields a 'Remote execution error' carrying the cause message")
    void transportFailureYieldsGenericRemoteExecutionError() {
        // withException makes the RestTemplate throw ResourceAccessException wrapping the I/O
        // error; that hits the generic catch (lines 218-226) - not an HttpClientErrorException.
        server.expect(requestTo("http://datasource.test/api/agent-tools/execute"))
            .andExpect(method(HttpMethod.POST))
            .andRespond(withException(new java.net.SocketTimeoutException("Connection timeout")));

        ToolResult result = service.executeTool(
            new ToolCall("tool-call-timeout", "table", Map.of("action", "list"), null),
            ToolDefinition.builder().name("table").build(),
            "tenant-1",
            Map.of());

        assertThat(result.success()).isFalse();
        assertThat(result.error())
            .startsWith("Remote execution error:")
            .contains("Connection timeout");
        server.verify();
    }

    @Test
    @DisplayName("agent(action='execute') with no SubAgentExecutionHandler falls through to remote routing (no NPE)")
    void agentExecuteWithoutHandlerFallsThroughToRemoteRouting() {
        // subAgentExecutionHandler and agentToolsProvider are both null (plain unit construction),
        // so agent(action='execute') is neither intercepted locally nor sub-agent-handled; it must
        // fall through to the orchestrator core-tools hop rather than dereferencing a null handler.
        // Use an exempt (workflow-run) context so the authorization gate does not pause it first.
        ReflectionTestUtils.setField(service, "orchestratorUrl", "http://orchestrator.test");

        server.expect(requestTo("http://orchestrator.test/api/agent-tools/execute"))
            .andExpect(method(HttpMethod.POST))
            .andExpect(jsonPath("$.tool").value("agent"))
            .andRespond(withSuccess("{\"success\":true,\"data\":{\"ran\":true}}", MediaType.APPLICATION_JSON));

        ToolResult result = service.executeTool(
            new ToolCall("tool-call-agent", "agent", Map.of("action", "execute", "id", "sub-1"), null),
            ToolDefinition.builder().name("agent").build(),
            "tenant-1",
            Map.of("__workflowRunId__", "run-1"));

        assertThat(result.success()).isTrue();
        // Routed to orchestrator (server.verify) without NPE; result carries the remote-source tag.
        assertThat(result.metadata()).containsEntry("source", "agent_service_remote");
        server.verify();
    }
}
