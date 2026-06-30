package com.apimarketplace.catalog.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for McpResponse.
 *
 * McpResponse represents a JSON-RPC 2.0 response for the MCP protocol.
 */
@DisplayName("McpResponse")
class McpResponseTest {

    private static final ObjectMapper mapper = new ObjectMapper();

    // ========================================================================
    // Constructor tests
    // ========================================================================

    @Nested
    @DisplayName("Constructors")
    class ConstructorTests {

        @Test
        @DisplayName("should create response with default constructor")
        void shouldCreateResponseWithDefaultConstructor() {
            McpResponse response = new McpResponse();

            assertEquals("2.0", response.getJsonrpc());
            assertNull(response.getId());
            assertNull(response.getResult());
            assertNull(response.getError());
        }

        @Test
        @DisplayName("should create success response with result")
        void shouldCreateSuccessResponseWithResult() throws Exception {
            JsonNode result = mapper.readTree("{\"data\": \"test\"}");
            McpResponse response = new McpResponse(1L, result);

            assertEquals("2.0", response.getJsonrpc());
            assertEquals(1L, response.getId());
            assertEquals(result, response.getResult());
            assertNull(response.getError());
        }

        @Test
        @DisplayName("should create error response with McpError")
        void shouldCreateErrorResponseWithMcpError() {
            McpResponse.McpError error = new McpResponse.McpError(-32600, "Invalid request");
            McpResponse response = new McpResponse(1L, error);

            assertEquals("2.0", response.getJsonrpc());
            assertEquals(1L, response.getId());
            assertNull(response.getResult());
            assertEquals(error, response.getError());
        }
    }

    // ========================================================================
    // Factory method tests - success
    // ========================================================================

    @Nested
    @DisplayName("success()")
    class SuccessTests {

        @Test
        @DisplayName("should create success response")
        void shouldCreateSuccessResponse() throws Exception {
            JsonNode result = mapper.readTree("{\"status\": \"ok\"}");
            McpResponse response = McpResponse.success(1L, result);

            assertEquals(1L, response.getId());
            assertEquals(result, response.getResult());
            assertNull(response.getError());
            assertTrue(response.isSuccess());
            assertFalse(response.isError());
        }

        @Test
        @DisplayName("should create success response with null result")
        void shouldCreateSuccessResponseWithNullResult() {
            McpResponse response = McpResponse.success(1L, null);

            assertEquals(1L, response.getId());
            assertNull(response.getResult());
            assertTrue(response.isSuccess());
        }
    }

    // ========================================================================
    // Factory method tests - error
    // ========================================================================

    @Nested
    @DisplayName("error()")
    class ErrorTests {

        @Test
        @DisplayName("should create error response with data")
        void shouldCreateErrorResponseWithData() throws Exception {
            JsonNode data = mapper.readTree("{\"details\": \"more info\"}");
            McpResponse response = McpResponse.error(1L, -32600, "Invalid request", data);

            assertEquals(1L, response.getId());
            assertNull(response.getResult());
            assertNotNull(response.getError());
            assertEquals(-32600, response.getError().getCode());
            assertEquals("Invalid request", response.getError().getMessage());
            assertEquals(data, response.getError().getData());
        }

        @Test
        @DisplayName("should create error response without data")
        void shouldCreateErrorResponseWithoutData() {
            McpResponse response = McpResponse.error(1L, -32601, "Method not found");

            assertNotNull(response.getError());
            assertEquals(-32601, response.getError().getCode());
            assertEquals("Method not found", response.getError().getMessage());
            assertNull(response.getError().getData());
        }
    }

    // ========================================================================
    // isError and isSuccess tests
    // ========================================================================

    @Nested
    @DisplayName("isError() and isSuccess()")
    class ErrorSuccessTests {

        @Test
        @DisplayName("should return isError true when error is present")
        void shouldReturnIsErrorTrueWhenErrorIsPresent() {
            McpResponse response = McpResponse.error(1L, -32600, "Error");

            assertTrue(response.isError());
            assertFalse(response.isSuccess());
        }

        @Test
        @DisplayName("should return isSuccess true when error is null")
        void shouldReturnIsSuccessTrueWhenErrorIsNull() throws Exception {
            JsonNode result = mapper.readTree("{}");
            McpResponse response = McpResponse.success(1L, result);

            assertTrue(response.isSuccess());
            assertFalse(response.isError());
        }
    }

    // ========================================================================
    // getErrorMessage and getErrorCode tests
    // ========================================================================

    @Nested
    @DisplayName("getErrorMessage() and getErrorCode()")
    class ErrorAccessorTests {

        @Test
        @DisplayName("should return error message when error exists")
        void shouldReturnErrorMessageWhenErrorExists() {
            McpResponse response = McpResponse.error(1L, -32600, "Invalid request");

            assertEquals("Invalid request", response.getErrorMessage());
        }

        @Test
        @DisplayName("should return null for error message when no error")
        void shouldReturnNullForErrorMessageWhenNoError() throws Exception {
            McpResponse response = McpResponse.success(1L, mapper.readTree("{}"));

            assertNull(response.getErrorMessage());
        }

        @Test
        @DisplayName("should return error code when error exists")
        void shouldReturnErrorCodeWhenErrorExists() {
            McpResponse response = McpResponse.error(1L, -32601, "Method not found");

            assertEquals(-32601, response.getErrorCode());
        }

        @Test
        @DisplayName("should return null for error code when no error")
        void shouldReturnNullForErrorCodeWhenNoError() throws Exception {
            McpResponse response = McpResponse.success(1L, mapper.readTree("{}"));

            assertNull(response.getErrorCode());
        }
    }

    // ========================================================================
    // toString tests
    // ========================================================================

    @Nested
    @DisplayName("toString()")
    class ToStringTests {

        @Test
        @DisplayName("should format error response in toString")
        void shouldFormatErrorResponseInToString() {
            McpResponse response = McpResponse.error(1L, -32600, "Error message");

            String result = response.toString();

            assertTrue(result.contains("McpResponse"));
            assertTrue(result.contains("id=1"));
            assertTrue(result.contains("error="));
        }

        @Test
        @DisplayName("should format success response in toString")
        void shouldFormatSuccessResponseInToString() throws Exception {
            JsonNode result = mapper.readTree("{\"data\": \"test\"}");
            McpResponse response = McpResponse.success(1L, result);

            String str = response.toString();

            assertTrue(str.contains("McpResponse"));
            assertTrue(str.contains("id=1"));
            assertTrue(str.contains("result="));
        }
    }

    // ========================================================================
    // McpError tests
    // ========================================================================

    @Nested
    @DisplayName("McpError")
    class McpErrorTests {

        @Nested
        @DisplayName("Constructors")
        class ErrorConstructorTests {

            @Test
            @DisplayName("should create error with default constructor")
            void shouldCreateErrorWithDefaultConstructor() {
                McpResponse.McpError error = new McpResponse.McpError();

                assertEquals(0, error.getCode());
                assertNull(error.getMessage());
                assertNull(error.getData());
            }

            @Test
            @DisplayName("should create error with code and message")
            void shouldCreateErrorWithCodeAndMessage() {
                McpResponse.McpError error = new McpResponse.McpError(-32600, "Invalid");

                assertEquals(-32600, error.getCode());
                assertEquals("Invalid", error.getMessage());
                assertNull(error.getData());
            }

            @Test
            @DisplayName("should create error with code, message and data")
            void shouldCreateErrorWithCodeMessageAndData() throws Exception {
                JsonNode data = mapper.readTree("{\"info\": \"details\"}");
                McpResponse.McpError error = new McpResponse.McpError(-32600, "Invalid", data);

                assertEquals(-32600, error.getCode());
                assertEquals("Invalid", error.getMessage());
                assertEquals(data, error.getData());
            }
        }

        @Nested
        @DisplayName("Error code constants")
        class ErrorCodeTests {

            @Test
            @DisplayName("should have correct JSON-RPC error codes")
            void shouldHaveCorrectJsonRpcErrorCodes() {
                assertEquals(-32700, McpResponse.McpError.PARSE_ERROR);
                assertEquals(-32600, McpResponse.McpError.INVALID_REQUEST);
                assertEquals(-32601, McpResponse.McpError.METHOD_NOT_FOUND);
                assertEquals(-32602, McpResponse.McpError.INVALID_PARAMS);
                assertEquals(-32603, McpResponse.McpError.INTERNAL_ERROR);
            }

            @Test
            @DisplayName("should have correct MCP-specific error codes")
            void shouldHaveCorrectMcpSpecificErrorCodes() {
                assertEquals(-32000, McpResponse.McpError.TOOL_NOT_FOUND);
                assertEquals(-32001, McpResponse.McpError.TOOL_EXECUTION_ERROR);
                assertEquals(-32002, McpResponse.McpError.RESOURCE_NOT_FOUND);
                assertEquals(-32003, McpResponse.McpError.RESOURCE_ACCESS_ERROR);
                assertEquals(-32004, McpResponse.McpError.CONNECTION_ERROR);
                assertEquals(-32005, McpResponse.McpError.TIMEOUT_ERROR);
            }
        }

        @Nested
        @DisplayName("Factory methods")
        class ErrorFactoryTests {

            @Test
            @DisplayName("should create parse error")
            void shouldCreateParseError() {
                McpResponse.McpError error = McpResponse.McpError.parseError("Invalid JSON");

                assertEquals(McpResponse.McpError.PARSE_ERROR, error.getCode());
                assertEquals("Invalid JSON", error.getMessage());
            }

            @Test
            @DisplayName("should create invalid request error")
            void shouldCreateInvalidRequestError() {
                McpResponse.McpError error = McpResponse.McpError.invalidRequest("Missing id");

                assertEquals(McpResponse.McpError.INVALID_REQUEST, error.getCode());
                assertEquals("Missing id", error.getMessage());
            }

            @Test
            @DisplayName("should create method not found error")
            void shouldCreateMethodNotFoundError() {
                McpResponse.McpError error = McpResponse.McpError.methodNotFound("unknown/method");

                assertEquals(McpResponse.McpError.METHOD_NOT_FOUND, error.getCode());
                assertTrue(error.getMessage().contains("unknown/method"));
            }

            @Test
            @DisplayName("should create invalid params error")
            void shouldCreateInvalidParamsError() {
                McpResponse.McpError error = McpResponse.McpError.invalidParams("Missing required param");

                assertEquals(McpResponse.McpError.INVALID_PARAMS, error.getCode());
                assertEquals("Missing required param", error.getMessage());
            }

            @Test
            @DisplayName("should create internal error")
            void shouldCreateInternalError() {
                McpResponse.McpError error = McpResponse.McpError.internalError("Server error");

                assertEquals(McpResponse.McpError.INTERNAL_ERROR, error.getCode());
                assertEquals("Server error", error.getMessage());
            }

            @Test
            @DisplayName("should create tool not found error")
            void shouldCreateToolNotFoundError() {
                McpResponse.McpError error = McpResponse.McpError.toolNotFound("weather");

                assertEquals(McpResponse.McpError.TOOL_NOT_FOUND, error.getCode());
                assertTrue(error.getMessage().contains("weather"));
            }

            @Test
            @DisplayName("should create tool execution error")
            void shouldCreateToolExecutionError() {
                McpResponse.McpError error = McpResponse.McpError.toolExecutionError("Timeout");

                assertEquals(McpResponse.McpError.TOOL_EXECUTION_ERROR, error.getCode());
                assertTrue(error.getMessage().contains("Timeout"));
            }

            @Test
            @DisplayName("should create resource not found error")
            void shouldCreateResourceNotFoundError() {
                McpResponse.McpError error = McpResponse.McpError.resourceNotFound("file:///data.json");

                assertEquals(McpResponse.McpError.RESOURCE_NOT_FOUND, error.getCode());
                assertTrue(error.getMessage().contains("file:///data.json"));
            }

            @Test
            @DisplayName("should create connection error")
            void shouldCreateConnectionError() {
                McpResponse.McpError error = McpResponse.McpError.connectionError("Connection refused");

                assertEquals(McpResponse.McpError.CONNECTION_ERROR, error.getCode());
                assertTrue(error.getMessage().contains("Connection refused"));
            }

            @Test
            @DisplayName("should create timeout error")
            void shouldCreateTimeoutError() {
                McpResponse.McpError error = McpResponse.McpError.timeoutError("30 seconds");

                assertEquals(McpResponse.McpError.TIMEOUT_ERROR, error.getCode());
                assertTrue(error.getMessage().contains("30 seconds"));
            }
        }
    }
}
