package com.apimarketplace.catalog.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.*;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for McpClient.
 *
 * McpClient manages MCP (Model Context Protocol) connections of different types:
 * LOCAL_MCP, REMOTE_MCP, and API_GATEWAY.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("McpClient")
class McpClientTest {

    @Mock
    private RestTemplate restTemplate;

    private McpClient mcpClient;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        mcpClient = new McpClient(restTemplate);
        objectMapper = new ObjectMapper();
    }

    // ========================================================================
    // getNextRequestId tests
    // ========================================================================

    @Nested
    @DisplayName("getNextRequestId()")
    class GetNextRequestIdTests {

        @Test
        @DisplayName("should return incrementing IDs")
        void shouldReturnIncrementingIds() {
            long first = mcpClient.getNextRequestId();
            long second = mcpClient.getNextRequestId();
            long third = mcpClient.getNextRequestId();

            assertEquals(first + 1, second);
            assertEquals(second + 1, third);
        }

        @Test
        @DisplayName("should start from 1")
        void shouldStartFromOne() {
            long first = mcpClient.getNextRequestId();

            assertTrue(first >= 1);
        }
    }

    // ========================================================================
    // RemoteMcpConnection tests
    // ========================================================================

    @Nested
    @DisplayName("RemoteMcpConnection")
    class RemoteMcpConnectionTests {

        @Test
        @DisplayName("should return correct connection ID")
        void shouldReturnCorrectConnectionId() {
            McpClient.RemoteMcpConnection connection = new McpClient.RemoteMcpConnection(
                "test-server",
                "http://localhost:8080",
                new HttpHeaders(),
                restTemplate,
                objectMapper
            );

            assertEquals("test-server", connection.getId());
        }

        @Test
        @DisplayName("should return REMOTE_MCP as server type")
        void shouldReturnRemoteMcpAsServerType() {
            McpClient.RemoteMcpConnection connection = new McpClient.RemoteMcpConnection(
                "test-server",
                "http://localhost:8080",
                new HttpHeaders(),
                restTemplate,
                objectMapper
            );

            assertEquals("REMOTE_MCP", connection.getServerType());
        }

        @Test
        @DisplayName("should not be connected by default")
        void shouldNotBeConnectedByDefault() {
            McpClient.RemoteMcpConnection connection = new McpClient.RemoteMcpConnection(
                "test-server",
                "http://localhost:8080",
                new HttpHeaders(),
                restTemplate,
                objectMapper
            );

            assertFalse(connection.isConnected());
        }

        @Test
        @DisplayName("should be connected after setConnected(true)")
        void shouldBeConnectedAfterSetConnectedTrue() {
            McpClient.RemoteMcpConnection connection = new McpClient.RemoteMcpConnection(
                "test-server",
                "http://localhost:8080",
                new HttpHeaders(),
                restTemplate,
                objectMapper
            );

            connection.setConnected(true);

            assertTrue(connection.isConnected());
        }

        @Test
        @DisplayName("should throw exception when sending request while not connected")
        void shouldThrowExceptionWhenSendingRequestWhileNotConnected() {
            McpClient.RemoteMcpConnection connection = new McpClient.RemoteMcpConnection(
                "test-server",
                "http://localhost:8080",
                new HttpHeaders(),
                restTemplate,
                objectMapper
            );

            McpRequest request = McpRequest.initialize(1L);

            assertThrows(McpException.class, () -> connection.sendRequest(request));
        }

        @Test
        @DisplayName("should disconnect on close")
        void shouldDisconnectOnClose() {
            McpClient.RemoteMcpConnection connection = new McpClient.RemoteMcpConnection(
                "test-server",
                "http://localhost:8080",
                new HttpHeaders(),
                restTemplate,
                objectMapper
            );
            connection.setConnected(true);

            connection.close();

            assertFalse(connection.isConnected());
        }

        @Test
        @DisplayName("should trim trailing slash from base URL")
        void shouldTrimTrailingSlashFromBaseUrl() throws Exception {
            McpClient.RemoteMcpConnection connection = new McpClient.RemoteMcpConnection(
                "test-server",
                "http://localhost:8080/",
                new HttpHeaders(),
                restTemplate,
                objectMapper
            );
            connection.setConnected(true);

            McpRequest request = McpRequest.initialize(1L);
            McpResponse mockResponse = new McpResponse();
            mockResponse.setId(1L);

            when(restTemplate.exchange(
                eq("http://localhost:8080/mcp"),
                eq(HttpMethod.POST),
                any(HttpEntity.class),
                eq(McpResponse.class)
            )).thenReturn(ResponseEntity.ok(mockResponse));

            connection.sendRequest(request);

            verify(restTemplate).exchange(
                eq("http://localhost:8080/mcp"),
                eq(HttpMethod.POST),
                any(HttpEntity.class),
                eq(McpResponse.class)
            );
        }
    }

    // ========================================================================
    // ApiGatewayConnection tests
    // ========================================================================

    @Nested
    @DisplayName("ApiGatewayConnection")
    class ApiGatewayConnectionTests {

        @Test
        @DisplayName("should return correct connection ID")
        void shouldReturnCorrectConnectionId() {
            McpClient.ApiGatewayConnection connection = new McpClient.ApiGatewayConnection(
                "gateway-server",
                "http://api.example.com",
                new HttpHeaders(),
                restTemplate
            );

            assertEquals("gateway-server", connection.getId());
        }

        @Test
        @DisplayName("should return API_GATEWAY as server type")
        void shouldReturnApiGatewayAsServerType() {
            McpClient.ApiGatewayConnection connection = new McpClient.ApiGatewayConnection(
                "gateway-server",
                "http://api.example.com",
                new HttpHeaders(),
                restTemplate
            );

            assertEquals("API_GATEWAY", connection.getServerType());
        }

        @Test
        @DisplayName("should not be connected by default")
        void shouldNotBeConnectedByDefault() {
            McpClient.ApiGatewayConnection connection = new McpClient.ApiGatewayConnection(
                "gateway-server",
                "http://api.example.com",
                new HttpHeaders(),
                restTemplate
            );

            assertFalse(connection.isConnected());
        }

        @Test
        @DisplayName("should throw exception for standard MCP request")
        void shouldThrowExceptionForStandardMcpRequest() {
            McpClient.ApiGatewayConnection connection = new McpClient.ApiGatewayConnection(
                "gateway-server",
                "http://api.example.com",
                new HttpHeaders(),
                restTemplate
            );

            McpRequest request = McpRequest.initialize(1L);

            assertThrows(McpException.class, () -> connection.sendRequest(request));
        }

        @Test
        @DisplayName("should create correct full URL with leading slash")
        void shouldCreateCorrectFullUrlWithLeadingSlash() {
            McpClient.ApiGatewayConnection connection = new McpClient.ApiGatewayConnection(
                "gateway-server",
                "http://api.example.com",
                new HttpHeaders(),
                restTemplate
            );

            String fullUrl = connection.getFullUrl("/users");

            assertEquals("http://api.example.com/users", fullUrl);
        }

        @Test
        @DisplayName("should create correct full URL without leading slash")
        void shouldCreateCorrectFullUrlWithoutLeadingSlash() {
            McpClient.ApiGatewayConnection connection = new McpClient.ApiGatewayConnection(
                "gateway-server",
                "http://api.example.com",
                new HttpHeaders(),
                restTemplate
            );

            String fullUrl = connection.getFullUrl("users");

            assertEquals("http://api.example.com/users", fullUrl);
        }

        @Test
        @DisplayName("should trim trailing slash from base URL")
        void shouldTrimTrailingSlashFromBaseUrl() {
            McpClient.ApiGatewayConnection connection = new McpClient.ApiGatewayConnection(
                "gateway-server",
                "http://api.example.com/",
                new HttpHeaders(),
                restTemplate
            );

            String fullUrl = connection.getFullUrl("/users");

            assertEquals("http://api.example.com/users", fullUrl);
        }

        @Test
        @DisplayName("should create HTTP request with payload")
        void shouldCreateHttpRequestWithPayload() {
            HttpHeaders headers = new HttpHeaders();
            headers.add("Authorization", "Bearer token");

            McpClient.ApiGatewayConnection connection = new McpClient.ApiGatewayConnection(
                "gateway-server",
                "http://api.example.com",
                headers,
                restTemplate
            );

            HttpEntity<Object> request = connection.createHttpRequest(Map.of("key", "value"));

            assertNotNull(request);
            assertNotNull(request.getBody());
            assertEquals("Bearer token", request.getHeaders().getFirst("Authorization"));
        }

        @Test
        @DisplayName("should return rest template")
        void shouldReturnRestTemplate() {
            McpClient.ApiGatewayConnection connection = new McpClient.ApiGatewayConnection(
                "gateway-server",
                "http://api.example.com",
                new HttpHeaders(),
                restTemplate
            );

            assertSame(restTemplate, connection.getRestTemplate());
        }

        @Test
        @DisplayName("should return headers")
        void shouldReturnHeaders() {
            HttpHeaders headers = new HttpHeaders();
            headers.add("X-Custom", "value");

            McpClient.ApiGatewayConnection connection = new McpClient.ApiGatewayConnection(
                "gateway-server",
                "http://api.example.com",
                headers,
                restTemplate
            );

            assertEquals("value", connection.getHeaders().getFirst("X-Custom"));
        }
    }

    // ========================================================================
    // listTools tests
    // ========================================================================

    @Nested
    @DisplayName("listTools()")
    class ListToolsTests {

        @Test
        @DisplayName("should throw exception for API Gateway connection")
        void shouldThrowExceptionForApiGatewayConnection() {
            McpClient.ApiGatewayConnection connection = new McpClient.ApiGatewayConnection(
                "gateway-server",
                "http://api.example.com",
                new HttpHeaders(),
                restTemplate
            );
            connection.setConnected(true);

            assertThrows(McpException.class, () -> mcpClient.listTools(connection));
        }

        @Test
        @DisplayName("should return empty list when response has no tools")
        void shouldReturnEmptyListWhenResponseHasNoTools() throws Exception {
            McpClient.RemoteMcpConnection connection = new McpClient.RemoteMcpConnection(
                "test-server",
                "http://localhost:8080",
                new HttpHeaders(),
                restTemplate,
                objectMapper
            );
            connection.setConnected(true);

            McpResponse mockResponse = new McpResponse();
            mockResponse.setId(1L);
            mockResponse.setResult(objectMapper.createObjectNode());

            when(restTemplate.exchange(
                anyString(),
                eq(HttpMethod.POST),
                any(HttpEntity.class),
                eq(McpResponse.class)
            )).thenReturn(ResponseEntity.ok(mockResponse));

            List<McpTool> tools = mcpClient.listTools(connection);

            assertTrue(tools.isEmpty());
        }

        @Test
        @DisplayName("should parse tools from response")
        void shouldParseToolsFromResponse() throws Exception {
            McpClient.RemoteMcpConnection connection = new McpClient.RemoteMcpConnection(
                "test-server",
                "http://localhost:8080",
                new HttpHeaders(),
                restTemplate,
                objectMapper
            );
            connection.setConnected(true);

            String responseJson = """
                {
                    "tools": [
                        {"name": "tool1", "description": "Tool 1"},
                        {"name": "tool2", "description": "Tool 2"}
                    ]
                }
                """;
            JsonNode resultNode = objectMapper.readTree(responseJson);

            McpResponse mockResponse = new McpResponse();
            mockResponse.setId(1L);
            mockResponse.setResult(resultNode);

            when(restTemplate.exchange(
                anyString(),
                eq(HttpMethod.POST),
                any(HttpEntity.class),
                eq(McpResponse.class)
            )).thenReturn(ResponseEntity.ok(mockResponse));

            List<McpTool> tools = mcpClient.listTools(connection);

            assertEquals(2, tools.size());
            assertEquals("tool1", tools.get(0).getName());
            assertEquals("tool2", tools.get(1).getName());
        }

        @Test
        @DisplayName("should throw exception when response has error")
        void shouldThrowExceptionWhenResponseHasError() throws Exception {
            McpClient.RemoteMcpConnection connection = new McpClient.RemoteMcpConnection(
                "test-server",
                "http://localhost:8080",
                new HttpHeaders(),
                restTemplate,
                objectMapper
            );
            connection.setConnected(true);

            McpResponse mockResponse = new McpResponse();
            mockResponse.setId(1L);
            McpResponse.McpError error = new McpResponse.McpError();
            error.setCode(-32600);
            error.setMessage("Invalid request");
            mockResponse.setError(error);

            when(restTemplate.exchange(
                anyString(),
                eq(HttpMethod.POST),
                any(HttpEntity.class),
                eq(McpResponse.class)
            )).thenReturn(ResponseEntity.ok(mockResponse));

            assertThrows(McpException.class, () -> mcpClient.listTools(connection));
        }
    }

    // ========================================================================
    // callTool tests
    // ========================================================================

    @Nested
    @DisplayName("callTool()")
    class CallToolTests {

        @Test
        @DisplayName("should throw exception for API Gateway connection")
        void shouldThrowExceptionForApiGatewayConnection() {
            McpClient.ApiGatewayConnection connection = new McpClient.ApiGatewayConnection(
                "gateway-server",
                "http://api.example.com",
                new HttpHeaders(),
                restTemplate
            );
            connection.setConnected(true);

            JsonNode args = objectMapper.createObjectNode();

            assertThrows(McpException.class, () -> mcpClient.callTool(connection, "toolName", args));
        }

        @Test
        @DisplayName("should return success result when response is successful")
        void shouldReturnSuccessResultWhenResponseIsSuccessful() throws Exception {
            McpClient.RemoteMcpConnection connection = new McpClient.RemoteMcpConnection(
                "test-server",
                "http://localhost:8080",
                new HttpHeaders(),
                restTemplate,
                objectMapper
            );
            connection.setConnected(true);

            String responseJson = "{\"data\": \"result\"}";
            JsonNode resultNode = objectMapper.readTree(responseJson);

            McpResponse mockResponse = new McpResponse();
            mockResponse.setId(1L);
            mockResponse.setResult(resultNode);

            when(restTemplate.exchange(
                anyString(),
                eq(HttpMethod.POST),
                any(HttpEntity.class),
                eq(McpResponse.class)
            )).thenReturn(ResponseEntity.ok(mockResponse));

            McpToolResult result = mcpClient.callTool(connection, "testTool", objectMapper.createObjectNode());

            assertTrue(result.isSuccess());
            assertNotNull(result.getData());
        }

        @Test
        @DisplayName("should return error result when response has error")
        void shouldReturnErrorResultWhenResponseHasError() throws Exception {
            McpClient.RemoteMcpConnection connection = new McpClient.RemoteMcpConnection(
                "test-server",
                "http://localhost:8080",
                new HttpHeaders(),
                restTemplate,
                objectMapper
            );
            connection.setConnected(true);

            McpResponse mockResponse = new McpResponse();
            mockResponse.setId(1L);
            McpResponse.McpError error = new McpResponse.McpError();
            error.setCode(-32600);
            error.setMessage("Tool execution failed");
            mockResponse.setError(error);

            when(restTemplate.exchange(
                anyString(),
                eq(HttpMethod.POST),
                any(HttpEntity.class),
                eq(McpResponse.class)
            )).thenReturn(ResponseEntity.ok(mockResponse));

            McpToolResult result = mcpClient.callTool(connection, "testTool", objectMapper.createObjectNode());

            assertFalse(result.isSuccess());
            assertNotNull(result.getError());
        }
    }

    // ========================================================================
    // callApiGatewayTool tests
    // ========================================================================

    @Nested
    @DisplayName("callApiGatewayTool()")
    class CallApiGatewayToolTests {

        @Test
        @DisplayName("should return success result for successful request")
        void shouldReturnSuccessResultForSuccessfulRequest() throws Exception {
            McpClient.ApiGatewayConnection connection = new McpClient.ApiGatewayConnection(
                "gateway-server",
                "http://api.example.com",
                new HttpHeaders(),
                restTemplate
            );
            connection.setConnected(true);

            Map<String, Object> responseBody = Map.of("result", "success");

            when(restTemplate.exchange(
                eq("http://api.example.com/users"),
                eq(HttpMethod.GET),
                any(HttpEntity.class),
                eq(Object.class)
            )).thenReturn(ResponseEntity.ok(responseBody));

            McpToolResult result = mcpClient.callApiGatewayTool(
                connection,
                "/users",
                "GET",
                objectMapper.createObjectNode(),
                null
            );

            assertTrue(result.isSuccess());
        }

        @Test
        @DisplayName("should return error result for non-2xx response")
        void shouldReturnErrorResultForNon2xxResponse() throws Exception {
            McpClient.ApiGatewayConnection connection = new McpClient.ApiGatewayConnection(
                "gateway-server",
                "http://api.example.com",
                new HttpHeaders(),
                restTemplate
            );
            connection.setConnected(true);

            when(restTemplate.exchange(
                anyString(),
                eq(HttpMethod.GET),
                any(HttpEntity.class),
                eq(Object.class)
            )).thenReturn(ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build());

            McpToolResult result = mcpClient.callApiGatewayTool(
                connection,
                "/users",
                "GET",
                objectMapper.createObjectNode(),
                null
            );

            assertFalse(result.isSuccess());
        }

        @Test
        @DisplayName("should add additional headers to request")
        void shouldAddAdditionalHeadersToRequest() throws Exception {
            HttpHeaders baseHeaders = new HttpHeaders();
            baseHeaders.add("Authorization", "Bearer token");

            McpClient.ApiGatewayConnection connection = new McpClient.ApiGatewayConnection(
                "gateway-server",
                "http://api.example.com",
                baseHeaders,
                restTemplate
            );
            connection.setConnected(true);

            Map<String, String> additionalHeaders = Map.of("X-Custom-Header", "custom-value");

            when(restTemplate.exchange(
                anyString(),
                eq(HttpMethod.POST),
                any(HttpEntity.class),
                eq(Object.class)
            )).thenReturn(ResponseEntity.ok(Map.of()));

            mcpClient.callApiGatewayTool(
                connection,
                "/data",
                "POST",
                objectMapper.createObjectNode(),
                additionalHeaders
            );

            verify(restTemplate).exchange(
                anyString(),
                eq(HttpMethod.POST),
                argThat(entity -> {
                    HttpHeaders headers = entity.getHeaders();
                    return "Bearer token".equals(headers.getFirst("Authorization"))
                        && "custom-value".equals(headers.getFirst("X-Custom-Header"));
                }),
                eq(Object.class)
            );
        }

        @Test
        @DisplayName("should handle different HTTP methods")
        void shouldHandleDifferentHttpMethods() throws Exception {
            McpClient.ApiGatewayConnection connection = new McpClient.ApiGatewayConnection(
                "gateway-server",
                "http://api.example.com",
                new HttpHeaders(),
                restTemplate
            );
            connection.setConnected(true);

            when(restTemplate.exchange(
                anyString(),
                any(HttpMethod.class),
                any(HttpEntity.class),
                eq(Object.class)
            )).thenReturn(ResponseEntity.ok(Map.of()));

            // Test PUT method
            mcpClient.callApiGatewayTool(connection, "/resource", "PUT", objectMapper.createObjectNode(), null);
            verify(restTemplate).exchange(anyString(), eq(HttpMethod.PUT), any(HttpEntity.class), eq(Object.class));

            // Test DELETE method
            mcpClient.callApiGatewayTool(connection, "/resource", "DELETE", objectMapper.createObjectNode(), null);
            verify(restTemplate).exchange(anyString(), eq(HttpMethod.DELETE), any(HttpEntity.class), eq(Object.class));
        }

        @Test
        @DisplayName("should throw exception when request fails")
        void shouldThrowExceptionWhenRequestFails() {
            McpClient.ApiGatewayConnection connection = new McpClient.ApiGatewayConnection(
                "gateway-server",
                "http://api.example.com",
                new HttpHeaders(),
                restTemplate
            );
            connection.setConnected(true);

            when(restTemplate.exchange(
                anyString(),
                any(HttpMethod.class),
                any(HttpEntity.class),
                eq(Object.class)
            )).thenThrow(new RuntimeException("Network error"));

            assertThrows(McpException.class, () ->
                mcpClient.callApiGatewayTool(connection, "/users", "GET", objectMapper.createObjectNode(), null)
            );
        }
    }

    // ========================================================================
    // McpConnection interface tests
    // ========================================================================

    @Nested
    @DisplayName("McpConnection interface")
    class McpConnectionInterfaceTests {

        @Test
        @DisplayName("LocalMcpConnection should return LOCAL_MCP as server type")
        void localMcpConnectionShouldReturnLocalMcpAsServerType() throws Exception {
            // Create a mock process
            Process mockProcess = mock(Process.class);
            when(mockProcess.getOutputStream()).thenReturn(mock(java.io.OutputStream.class));
            when(mockProcess.getInputStream()).thenReturn(
                new java.io.ByteArrayInputStream("test".getBytes())
            );

            McpClient.LocalMcpConnection connection = new McpClient.LocalMcpConnection(
                "local-server",
                mockProcess,
                objectMapper
            );

            assertEquals("LOCAL_MCP", connection.getServerType());
            assertEquals("local-server", connection.getId());
        }

        @Test
        @DisplayName("LocalMcpConnection should not be connected by default")
        void localMcpConnectionShouldNotBeConnectedByDefault() throws Exception {
            Process mockProcess = mock(Process.class);
            when(mockProcess.getOutputStream()).thenReturn(mock(java.io.OutputStream.class));
            when(mockProcess.getInputStream()).thenReturn(
                new java.io.ByteArrayInputStream("test".getBytes())
            );

            McpClient.LocalMcpConnection connection = new McpClient.LocalMcpConnection(
                "local-server",
                mockProcess,
                objectMapper
            );

            assertFalse(connection.isConnected());
        }
    }
}
