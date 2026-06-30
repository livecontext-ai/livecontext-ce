package com.apimarketplace.agent.mcp;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for McpMessageTypes - MCP protocol message types.
 */
@DisplayName("McpMessageTypes")
class McpMessageTypesTest {

    @Nested
    @DisplayName("ServerInfo")
    class ServerInfoTests {

        @Test
        @DisplayName("should create ServerInfo with fields")
        void shouldCreateServerInfo() {
            McpMessageTypes.ServerCapabilities caps = new McpMessageTypes.ServerCapabilities(true, true, false);
            McpMessageTypes.ServerInfo info = new McpMessageTypes.ServerInfo("TestServer", "2.0", caps);

            assertThat(info.name()).isEqualTo("TestServer");
            assertThat(info.version()).isEqualTo("2.0");
            assertThat(info.capabilities()).isEqualTo(caps);
        }

        @Test
        @DisplayName("defaultInfo should return standard server info")
        void shouldReturnDefaultInfo() {
            McpMessageTypes.ServerInfo info = McpMessageTypes.ServerInfo.defaultInfo();

            assertThat(info.name()).isEqualTo("LiveContext Agent Tools");
            assertThat(info.version()).isEqualTo("1.0.0");
            assertThat(info.capabilities()).isNotNull();
            assertThat(info.capabilities().tools()).isTrue();
            assertThat(info.capabilities().resources()).isTrue();
            assertThat(info.capabilities().prompts()).isFalse();
        }
    }

    @Nested
    @DisplayName("ServerCapabilities")
    class ServerCapabilitiesTests {

        @Test
        @DisplayName("should store capability flags")
        void shouldStoreCapabilities() {
            McpMessageTypes.ServerCapabilities caps = new McpMessageTypes.ServerCapabilities(true, false, true);

            assertThat(caps.tools()).isTrue();
            assertThat(caps.resources()).isFalse();
            assertThat(caps.prompts()).isTrue();
        }
    }

    @Nested
    @DisplayName("InitializeRequest")
    class InitializeRequestTests {

        @Test
        @DisplayName("should create initialize request")
        void shouldCreateRequest() {
            McpMessageTypes.ClientInfo clientInfo = new McpMessageTypes.ClientInfo("TestClient", "1.0");
            McpMessageTypes.InitializeRequest req = new McpMessageTypes.InitializeRequest(
                    "2024-11-05", clientInfo, Map.of("tools", true));

            assertThat(req.protocolVersion()).isEqualTo("2024-11-05");
            assertThat(req.clientInfo().name()).isEqualTo("TestClient");
            assertThat(req.clientInfo().version()).isEqualTo("1.0");
            assertThat(req.capabilities()).containsEntry("tools", true);
        }
    }

    @Nested
    @DisplayName("InitializeResponse")
    class InitializeResponseTests {

        @Test
        @DisplayName("create should return standard response")
        void shouldCreateStandardResponse() {
            McpMessageTypes.InitializeResponse response = McpMessageTypes.InitializeResponse.create();

            assertThat(response.protocolVersion()).isEqualTo("2024-11-05");
            assertThat(response.serverInfo()).isNotNull();
            assertThat(response.capabilities()).isNotNull();
            assertThat(response.capabilities().tools()).isTrue();
        }
    }

    @Nested
    @DisplayName("ToolCallResponse")
    class ToolCallResponseTests {

        @Test
        @DisplayName("success should create non-error response with text content")
        void shouldCreateSuccessResponse() {
            McpMessageTypes.ToolCallResponse response = McpMessageTypes.ToolCallResponse.success("result data");

            assertThat(response.isError()).isFalse();
            assertThat(response.content()).hasSize(1);
            assertThat(response.content().get(0).type()).isEqualTo("text");
            assertThat(response.content().get(0).text()).isEqualTo("result data");
            assertThat(response.content().get(0).mimeType()).isNull();
        }

        @Test
        @DisplayName("error should create error response")
        void shouldCreateErrorResponse() {
            McpMessageTypes.ToolCallResponse response = McpMessageTypes.ToolCallResponse.error("Something failed");

            assertThat(response.isError()).isTrue();
            assertThat(response.content()).hasSize(1);
            assertThat(response.content().get(0).text()).isEqualTo("Something failed");
        }

        @Test
        @DisplayName("json should create non-error response with map data")
        void shouldCreateJsonResponse() {
            Map<String, Object> data = Map.of("key", "value");
            McpMessageTypes.ToolCallResponse response = McpMessageTypes.ToolCallResponse.json(data);

            assertThat(response.isError()).isFalse();
            assertThat(response.content()).hasSize(1);
            assertThat(response.content().get(0).text()).isEqualTo(data.toString());
        }
    }

    @Nested
    @DisplayName("ToolsListResponse")
    class ToolsListResponseTests {

        @Test
        @DisplayName("should create tools list response")
        void shouldCreateToolsListResponse() {
            McpMessageTypes.McpTool tool = new McpMessageTypes.McpTool(
                    "search", "Search API", Map.of("type", "object"));
            McpMessageTypes.ToolsListResponse response = new McpMessageTypes.ToolsListResponse(List.of(tool));

            assertThat(response.tools()).hasSize(1);
            assertThat(response.tools().get(0).name()).isEqualTo("search");
            assertThat(response.tools().get(0).description()).isEqualTo("Search API");
        }
    }

    @Nested
    @DisplayName("ToolCallRequest")
    class ToolCallRequestTests {

        @Test
        @DisplayName("should create tool call request")
        void shouldCreateRequest() {
            McpMessageTypes.ToolCallRequest req = new McpMessageTypes.ToolCallRequest(
                    "search", Map.of("query", "test"));

            assertThat(req.name()).isEqualTo("search");
            assertThat(req.arguments()).containsEntry("query", "test");
        }
    }

    @Nested
    @DisplayName("ResourcesListResponse")
    class ResourcesListResponseTests {

        @Test
        @DisplayName("should create resources list response")
        void shouldCreateResponse() {
            McpMessageTypes.McpResource resource = new McpMessageTypes.McpResource(
                    "resource://test", "Test", "A test resource", "application/json");
            McpMessageTypes.ResourcesListResponse response = new McpMessageTypes.ResourcesListResponse(
                    List.of(resource));

            assertThat(response.resources()).hasSize(1);
            assertThat(response.resources().get(0).uri()).isEqualTo("resource://test");
            assertThat(response.resources().get(0).name()).isEqualTo("Test");
            assertThat(response.resources().get(0).mimeType()).isEqualTo("application/json");
        }
    }

    @Nested
    @DisplayName("ResourceReadRequest / ResourceReadResponse")
    class ResourceReadTests {

        @Test
        @DisplayName("should create resource read request")
        void shouldCreateReadRequest() {
            McpMessageTypes.ResourceReadRequest req = new McpMessageTypes.ResourceReadRequest("resource://test");
            assertThat(req.uri()).isEqualTo("resource://test");
        }

        @Test
        @DisplayName("should create resource read response with contents")
        void shouldCreateReadResponse() {
            McpMessageTypes.ResourceContent content = new McpMessageTypes.ResourceContent(
                    "resource://test", "text/plain", "Hello", null);
            McpMessageTypes.ResourceReadResponse response = new McpMessageTypes.ResourceReadResponse(
                    List.of(content));

            assertThat(response.contents()).hasSize(1);
            assertThat(response.contents().get(0).uri()).isEqualTo("resource://test");
            assertThat(response.contents().get(0).text()).isEqualTo("Hello");
            assertThat(response.contents().get(0).blob()).isNull();
        }
    }

    @Nested
    @DisplayName("JsonRpcRequest")
    class JsonRpcRequestTests {

        @Test
        @DisplayName("should default jsonrpc to 2.0 when null")
        void shouldDefaultJsonrpcVersion() {
            McpMessageTypes.JsonRpcRequest req = new McpMessageTypes.JsonRpcRequest(
                    null, "initialize", Map.of(), 1);

            assertThat(req.jsonrpc()).isEqualTo("2.0");
        }

        @Test
        @DisplayName("should preserve explicit jsonrpc version")
        void shouldPreserveExplicitVersion() {
            McpMessageTypes.JsonRpcRequest req = new McpMessageTypes.JsonRpcRequest(
                    "2.0", "tools/list", null, 2);

            assertThat(req.jsonrpc()).isEqualTo("2.0");
            assertThat(req.method()).isEqualTo("tools/list");
            assertThat(req.id()).isEqualTo(2);
        }
    }

    @Nested
    @DisplayName("JsonRpcResponse")
    class JsonRpcResponseTests {

        @Test
        @DisplayName("success should create response with result and no error")
        void shouldCreateSuccess() {
            McpMessageTypes.JsonRpcResponse response = McpMessageTypes.JsonRpcResponse.success(
                    Map.of("tools", List.of()), 1);

            assertThat(response.jsonrpc()).isEqualTo("2.0");
            assertThat(response.result()).isNotNull();
            assertThat(response.error()).isNull();
            assertThat(response.id()).isEqualTo(1);
        }

        @Test
        @DisplayName("error should create response with error and no result")
        void shouldCreateError() {
            McpMessageTypes.JsonRpcResponse response = McpMessageTypes.JsonRpcResponse.error(
                    -32601, "Method not found", 1);

            assertThat(response.jsonrpc()).isEqualTo("2.0");
            assertThat(response.result()).isNull();
            assertThat(response.error()).isNotNull();
            assertThat(response.error().code()).isEqualTo(-32601);
            assertThat(response.error().message()).isEqualTo("Method not found");
            assertThat(response.id()).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("JsonRpcError")
    class JsonRpcErrorTests {

        @Test
        @DisplayName("should have standard error codes")
        void shouldHaveStandardCodes() {
            assertThat(McpMessageTypes.JsonRpcError.PARSE_ERROR).isEqualTo(-32700);
            assertThat(McpMessageTypes.JsonRpcError.INVALID_REQUEST).isEqualTo(-32600);
            assertThat(McpMessageTypes.JsonRpcError.METHOD_NOT_FOUND).isEqualTo(-32601);
            assertThat(McpMessageTypes.JsonRpcError.INVALID_PARAMS).isEqualTo(-32602);
            assertThat(McpMessageTypes.JsonRpcError.INTERNAL_ERROR).isEqualTo(-32603);
        }

        @Test
        @DisplayName("should create error with code, message, and data")
        void shouldCreateError() {
            McpMessageTypes.JsonRpcError error = new McpMessageTypes.JsonRpcError(
                    -32602, "Invalid params", Map.of("field", "name"));

            assertThat(error.code()).isEqualTo(-32602);
            assertThat(error.message()).isEqualTo("Invalid params");
            assertThat(error.data()).isNotNull();
        }
    }
}
