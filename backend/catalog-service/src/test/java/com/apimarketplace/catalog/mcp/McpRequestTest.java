package com.apimarketplace.catalog.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for McpRequest.
 *
 * McpRequest represents a JSON-RPC 2.0 request for the MCP protocol.
 */
@DisplayName("McpRequest")
class McpRequestTest {

    private static final ObjectMapper mapper = new ObjectMapper();

    // ========================================================================
    // Constructor tests
    // ========================================================================

    @Nested
    @DisplayName("Constructors")
    class ConstructorTests {

        @Test
        @DisplayName("should create request with default constructor")
        void shouldCreateRequestWithDefaultConstructor() {
            McpRequest request = new McpRequest();

            assertEquals("2.0", request.getJsonrpc());
            assertNull(request.getId());
            assertNull(request.getMethod());
            assertNull(request.getParams());
        }

        @Test
        @DisplayName("should create request with all-args constructor")
        void shouldCreateRequestWithAllArgsConstructor() {
            Long id = 1L;
            String method = "tools/call";
            Map<String, Object> params = Map.of("name", "test");

            McpRequest request = new McpRequest(id, method, params);

            assertEquals("2.0", request.getJsonrpc());
            assertEquals(id, request.getId());
            assertEquals(method, request.getMethod());
            assertEquals(params, request.getParams());
        }
    }

    // ========================================================================
    // Factory method tests - initialize
    // ========================================================================

    @Nested
    @DisplayName("initialize()")
    class InitializeTests {

        @Test
        @DisplayName("should create initialize request with correct method")
        void shouldCreateInitializeRequestWithCorrectMethod() {
            McpRequest request = McpRequest.initialize(1L);

            assertEquals("initialize", request.getMethod());
            assertEquals(1L, request.getId());
        }

        @Test
        @DisplayName("should include protocol version in params")
        @SuppressWarnings("unchecked")
        void shouldIncludeProtocolVersionInParams() {
            McpRequest request = McpRequest.initialize(1L);

            Map<String, Object> params = (Map<String, Object>) request.getParams();
            assertEquals("2024-11-05", params.get("protocolVersion"));
        }

        @Test
        @DisplayName("should include client info in params")
        @SuppressWarnings("unchecked")
        void shouldIncludeClientInfoInParams() {
            McpRequest request = McpRequest.initialize(1L);

            Map<String, Object> params = (Map<String, Object>) request.getParams();
            Map<String, Object> clientInfo = (Map<String, Object>) params.get("clientInfo");

            assertEquals("livecontext-backend", clientInfo.get("name"));
            assertEquals("1.0.0", clientInfo.get("version"));
        }

        @Test
        @DisplayName("should include capabilities in params")
        @SuppressWarnings("unchecked")
        void shouldIncludeCapabilitiesInParams() {
            McpRequest request = McpRequest.initialize(1L);

            Map<String, Object> params = (Map<String, Object>) request.getParams();
            Map<String, Object> capabilities = (Map<String, Object>) params.get("capabilities");

            assertNotNull(capabilities.get("tools"));
            assertNotNull(capabilities.get("resources"));
        }
    }

    // ========================================================================
    // Factory method tests - listTools
    // ========================================================================

    @Nested
    @DisplayName("listTools()")
    class ListToolsTests {

        @Test
        @DisplayName("should create list tools request")
        void shouldCreateListToolsRequest() {
            McpRequest request = McpRequest.listTools(2L);

            assertEquals("tools/list", request.getMethod());
            assertEquals(2L, request.getId());
            assertNotNull(request.getParams());
        }
    }

    // ========================================================================
    // Factory method tests - callTool
    // ========================================================================

    @Nested
    @DisplayName("callTool()")
    class CallToolTests {

        @Test
        @DisplayName("should create call tool request with arguments")
        @SuppressWarnings("unchecked")
        void shouldCreateCallToolRequestWithArguments() throws Exception {
            JsonNode arguments = mapper.readTree("{\"city\": \"Paris\"}");
            McpRequest request = McpRequest.callTool(3L, "weather", arguments);

            assertEquals("tools/call", request.getMethod());
            assertEquals(3L, request.getId());

            Map<String, Object> params = (Map<String, Object>) request.getParams();
            assertEquals("weather", params.get("name"));
            assertEquals(arguments, params.get("arguments"));
        }

        @Test
        @DisplayName("should create call tool request without arguments")
        @SuppressWarnings("unchecked")
        void shouldCreateCallToolRequestWithoutArguments() {
            McpRequest request = McpRequest.callTool(3L, "ping", null);

            Map<String, Object> params = (Map<String, Object>) request.getParams();
            assertEquals("ping", params.get("name"));
            assertNull(params.get("arguments"));
        }
    }

    // ========================================================================
    // Factory method tests - listResources
    // ========================================================================

    @Nested
    @DisplayName("listResources()")
    class ListResourcesTests {

        @Test
        @DisplayName("should create list resources request")
        void shouldCreateListResourcesRequest() {
            McpRequest request = McpRequest.listResources(4L);

            assertEquals("resources/list", request.getMethod());
            assertEquals(4L, request.getId());
        }
    }

    // ========================================================================
    // Factory method tests - readResource
    // ========================================================================

    @Nested
    @DisplayName("readResource()")
    class ReadResourceTests {

        @Test
        @DisplayName("should create read resource request with uri")
        @SuppressWarnings("unchecked")
        void shouldCreateReadResourceRequestWithUri() {
            McpRequest request = McpRequest.readResource(5L, "file:///data.json");

            assertEquals("resources/read", request.getMethod());
            assertEquals(5L, request.getId());

            Map<String, Object> params = (Map<String, Object>) request.getParams();
            assertEquals("file:///data.json", params.get("uri"));
        }
    }

    // ========================================================================
    // Factory method tests - listPrompts
    // ========================================================================

    @Nested
    @DisplayName("listPrompts()")
    class ListPromptsTests {

        @Test
        @DisplayName("should create list prompts request")
        void shouldCreateListPromptsRequest() {
            McpRequest request = McpRequest.listPrompts(6L);

            assertEquals("prompts/list", request.getMethod());
            assertEquals(6L, request.getId());
        }
    }

    // ========================================================================
    // Factory method tests - getPrompt
    // ========================================================================

    @Nested
    @DisplayName("getPrompt()")
    class GetPromptTests {

        @Test
        @DisplayName("should create get prompt request with arguments")
        @SuppressWarnings("unchecked")
        void shouldCreateGetPromptRequestWithArguments() throws Exception {
            JsonNode arguments = mapper.readTree("{\"topic\": \"weather\"}");
            McpRequest request = McpRequest.getPrompt(7L, "analysis", arguments);

            assertEquals("prompts/get", request.getMethod());
            assertEquals(7L, request.getId());

            Map<String, Object> params = (Map<String, Object>) request.getParams();
            assertEquals("analysis", params.get("name"));
            assertEquals(arguments, params.get("arguments"));
        }

        @Test
        @DisplayName("should create get prompt request without arguments")
        @SuppressWarnings("unchecked")
        void shouldCreateGetPromptRequestWithoutArguments() {
            McpRequest request = McpRequest.getPrompt(7L, "default", null);

            Map<String, Object> params = (Map<String, Object>) request.getParams();
            assertEquals("default", params.get("name"));
            assertNull(params.get("arguments"));
        }
    }

    // ========================================================================
    // Factory method tests - ping
    // ========================================================================

    @Nested
    @DisplayName("ping()")
    class PingTests {

        @Test
        @DisplayName("should create ping request")
        void shouldCreatePingRequest() {
            McpRequest request = McpRequest.ping(8L);

            assertEquals("ping", request.getMethod());
            assertEquals(8L, request.getId());
        }
    }

    // ========================================================================
    // Factory method tests - notification
    // ========================================================================

    @Nested
    @DisplayName("notification()")
    class NotificationTests {

        @Test
        @DisplayName("should create notification without id")
        void shouldCreateNotificationWithoutId() {
            Map<String, String> params = Map.of("key", "value");
            McpRequest request = McpRequest.notification("test/notify", params);

            assertNull(request.getId());
            assertEquals("test/notify", request.getMethod());
            assertEquals(params, request.getParams());
        }

        @Test
        @DisplayName("should create initialized notification")
        void shouldCreateInitializedNotification() {
            McpRequest request = McpRequest.initialized();

            assertNull(request.getId());
            assertEquals("notifications/initialized", request.getMethod());
        }
    }

    // ========================================================================
    // isNotification and isRequest tests
    // ========================================================================

    @Nested
    @DisplayName("isNotification() and isRequest()")
    class NotificationRequestTests {

        @Test
        @DisplayName("should return true for isNotification when id is null")
        void shouldReturnTrueForIsNotificationWhenIdIsNull() {
            McpRequest notification = McpRequest.notification("test", null);

            assertTrue(notification.isNotification());
            assertFalse(notification.isRequest());
        }

        @Test
        @DisplayName("should return true for isRequest when id is present")
        void shouldReturnTrueForIsRequestWhenIdIsPresent() {
            McpRequest request = McpRequest.ping(1L);

            assertTrue(request.isRequest());
            assertFalse(request.isNotification());
        }
    }

    // ========================================================================
    // getMethodName tests
    // ========================================================================

    @Nested
    @DisplayName("getMethodName()")
    class GetMethodNameTests {

        @Test
        @DisplayName("should extract method name after slash")
        void shouldExtractMethodNameAfterSlash() {
            McpRequest request = McpRequest.listTools(1L);

            assertEquals("list", request.getMethodName());
        }

        @Test
        @DisplayName("should return full method when no slash")
        void shouldReturnFullMethodWhenNoSlash() {
            McpRequest request = McpRequest.ping(1L);

            assertEquals("ping", request.getMethodName());
        }

        @Test
        @DisplayName("should return null when method is null")
        void shouldReturnNullWhenMethodIsNull() {
            McpRequest request = new McpRequest();

            assertNull(request.getMethodName());
        }

        @Test
        @DisplayName("should handle method ending with slash")
        void shouldHandleMethodEndingWithSlash() {
            McpRequest request = new McpRequest(1L, "test/", null);

            assertEquals("test/", request.getMethodName());
        }
    }

    // ========================================================================
    // getMethodCategory tests
    // ========================================================================

    @Nested
    @DisplayName("getMethodCategory()")
    class GetMethodCategoryTests {

        @Test
        @DisplayName("should extract category before slash")
        void shouldExtractCategoryBeforeSlash() {
            McpRequest request = McpRequest.listTools(1L);

            assertEquals("tools", request.getMethodCategory());
        }

        @Test
        @DisplayName("should return full method when no slash")
        void shouldReturnFullMethodWhenNoSlash() {
            McpRequest request = McpRequest.ping(1L);

            assertEquals("ping", request.getMethodCategory());
        }

        @Test
        @DisplayName("should return null when method is null")
        void shouldReturnNullWhenMethodIsNull() {
            McpRequest request = new McpRequest();

            assertNull(request.getMethodCategory());
        }

        @Test
        @DisplayName("should extract resources category")
        void shouldExtractResourcesCategory() {
            McpRequest request = McpRequest.listResources(1L);

            assertEquals("resources", request.getMethodCategory());
        }

        @Test
        @DisplayName("should extract prompts category")
        void shouldExtractPromptsCategory() {
            McpRequest request = McpRequest.listPrompts(1L);

            assertEquals("prompts", request.getMethodCategory());
        }
    }

    // ========================================================================
    // toString tests
    // ========================================================================

    @Nested
    @DisplayName("toString()")
    class ToStringTests {

        @Test
        @DisplayName("should return formatted string representation")
        void shouldReturnFormattedStringRepresentation() {
            McpRequest request = McpRequest.ping(1L);

            String result = request.toString();

            assertTrue(result.contains("McpRequest"));
            assertTrue(result.contains("id=1"));
            assertTrue(result.contains("method='ping'"));
        }
    }
}
