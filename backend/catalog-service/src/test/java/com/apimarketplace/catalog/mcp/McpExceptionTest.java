package com.apimarketplace.catalog.mcp;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for McpException.
 *
 * McpException is a custom exception for MCP operations.
 */
@DisplayName("McpException")
class McpExceptionTest {

    // ========================================================================
    // Constructor tests
    // ========================================================================

    @Nested
    @DisplayName("Constructors")
    class ConstructorTests {

        @Test
        @DisplayName("should create exception with message only")
        void shouldCreateExceptionWithMessageOnly() {
            McpException exception = new McpException("Test error");

            assertEquals("Test error", exception.getMessage());
            assertNull(exception.getErrorCode());
            assertNull(exception.getErrorData());
            assertNull(exception.getCause());
        }

        @Test
        @DisplayName("should create exception with message and cause")
        void shouldCreateExceptionWithMessageAndCause() {
            RuntimeException cause = new RuntimeException("Original error");
            McpException exception = new McpException("Wrapped error", cause);

            assertEquals("Wrapped error", exception.getMessage());
            assertEquals(cause, exception.getCause());
            assertNull(exception.getErrorCode());
            assertNull(exception.getErrorData());
        }

        @Test
        @DisplayName("should create exception with message and error code")
        void shouldCreateExceptionWithMessageAndErrorCode() {
            McpException exception = new McpException("Connection failed", "CONNECTION_ERROR");

            assertEquals("Connection failed", exception.getMessage());
            assertEquals("CONNECTION_ERROR", exception.getErrorCode());
            assertNull(exception.getErrorData());
        }

        @Test
        @DisplayName("should create exception with message, code and data")
        void shouldCreateExceptionWithMessageCodeAndData() {
            Map<String, String> errorData = Map.of("server", "mcp-server-1");
            McpException exception = new McpException("Error occurred", "CUSTOM_ERROR", errorData);

            assertEquals("Error occurred", exception.getMessage());
            assertEquals("CUSTOM_ERROR", exception.getErrorCode());
            assertEquals(errorData, exception.getErrorData());
        }

        @Test
        @DisplayName("should create exception with message, code and cause")
        void shouldCreateExceptionWithMessageCodeAndCause() {
            RuntimeException cause = new RuntimeException("Root cause");
            McpException exception = new McpException("MCP error", "PROTOCOL_ERROR", cause);

            assertEquals("MCP error", exception.getMessage());
            assertEquals("PROTOCOL_ERROR", exception.getErrorCode());
            assertEquals(cause, exception.getCause());
            assertNull(exception.getErrorData());
        }
    }

    // ========================================================================
    // hasErrorCode tests
    // ========================================================================

    @Nested
    @DisplayName("hasErrorCode()")
    class HasErrorCodeTests {

        @Test
        @DisplayName("should return true when error code is present")
        void shouldReturnTrueWhenErrorCodeIsPresent() {
            McpException exception = new McpException("Error", "SOME_CODE");

            assertTrue(exception.hasErrorCode());
        }

        @Test
        @DisplayName("should return false when error code is null")
        void shouldReturnFalseWhenErrorCodeIsNull() {
            McpException exception = new McpException("Error");

            assertFalse(exception.hasErrorCode());
        }
    }

    // ========================================================================
    // hasErrorData tests
    // ========================================================================

    @Nested
    @DisplayName("hasErrorData()")
    class HasErrorDataTests {

        @Test
        @DisplayName("should return true when error data is present")
        void shouldReturnTrueWhenErrorDataIsPresent() {
            McpException exception = new McpException("Error", "CODE", Map.of("key", "value"));

            assertTrue(exception.hasErrorData());
        }

        @Test
        @DisplayName("should return false when error data is null")
        void shouldReturnFalseWhenErrorDataIsNull() {
            McpException exception = new McpException("Error", "CODE");

            assertFalse(exception.hasErrorData());
        }
    }

    // ========================================================================
    // Factory method tests - connectionError
    // ========================================================================

    @Nested
    @DisplayName("connectionError()")
    class ConnectionErrorTests {

        @Test
        @DisplayName("should create connection error exception")
        void shouldCreateConnectionErrorException() {
            McpException exception = McpException.connectionError("Server unavailable");

            assertEquals("Server unavailable", exception.getMessage());
            assertEquals("CONNECTION_ERROR", exception.getErrorCode());
        }
    }

    // ========================================================================
    // Factory method tests - timeoutError
    // ========================================================================

    @Nested
    @DisplayName("timeoutError()")
    class TimeoutErrorTests {

        @Test
        @DisplayName("should create timeout error exception")
        void shouldCreateTimeoutErrorException() {
            McpException exception = McpException.timeoutError("Request timed out");

            assertEquals("Request timed out", exception.getMessage());
            assertEquals("TIMEOUT_ERROR", exception.getErrorCode());
        }
    }

    // ========================================================================
    // Factory method tests - toolNotFound
    // ========================================================================

    @Nested
    @DisplayName("toolNotFound()")
    class ToolNotFoundTests {

        @Test
        @DisplayName("should create tool not found exception")
        void shouldCreateToolNotFoundException() {
            McpException exception = McpException.toolNotFound("weather");

            assertTrue(exception.getMessage().contains("weather"));
            assertEquals("TOOL_NOT_FOUND", exception.getErrorCode());
        }
    }

    // ========================================================================
    // Factory method tests - toolExecutionError
    // ========================================================================

    @Nested
    @DisplayName("toolExecutionError()")
    class ToolExecutionErrorTests {

        @Test
        @DisplayName("should create tool execution error exception")
        void shouldCreateToolExecutionErrorException() {
            McpException exception = McpException.toolExecutionError("api-call", "Invalid parameters");

            assertTrue(exception.getMessage().contains("api-call"));
            assertTrue(exception.getMessage().contains("Invalid parameters"));
            assertEquals("TOOL_EXECUTION_ERROR", exception.getErrorCode());
        }
    }

    // ========================================================================
    // Factory method tests - protocolError
    // ========================================================================

    @Nested
    @DisplayName("protocolError()")
    class ProtocolErrorTests {

        @Test
        @DisplayName("should create protocol error exception")
        void shouldCreateProtocolErrorException() {
            McpException exception = McpException.protocolError("Invalid JSON-RPC");

            assertTrue(exception.getMessage().contains("Invalid JSON-RPC"));
            assertTrue(exception.getMessage().contains("MCP"));
            assertEquals("PROTOCOL_ERROR", exception.getErrorCode());
        }
    }

    // ========================================================================
    // Factory method tests - configurationError
    // ========================================================================

    @Nested
    @DisplayName("configurationError()")
    class ConfigurationErrorTests {

        @Test
        @DisplayName("should create configuration error exception")
        void shouldCreateConfigurationErrorException() {
            McpException exception = McpException.configurationError("Missing server URL");

            assertTrue(exception.getMessage().contains("Missing server URL"));
            assertTrue(exception.getMessage().contains("MCP"));
            assertEquals("CONFIGURATION_ERROR", exception.getErrorCode());
        }
    }

    // ========================================================================
    // toString tests
    // ========================================================================

    @Nested
    @DisplayName("toString()")
    class ToStringTests {

        @Test
        @DisplayName("should format exception without error code")
        void shouldFormatExceptionWithoutErrorCode() {
            McpException exception = new McpException("Simple error");

            String result = exception.toString();

            assertTrue(result.contains("McpException"));
            assertTrue(result.contains("Simple error"));
            assertFalse(result.contains("["));
        }

        @Test
        @DisplayName("should include error code in toString")
        void shouldIncludeErrorCodeInToString() {
            McpException exception = new McpException("Error", "CUSTOM_CODE");

            String result = exception.toString();

            assertTrue(result.contains("McpException"));
            assertTrue(result.contains("[CUSTOM_CODE]"));
            assertTrue(result.contains("Error"));
        }

        @Test
        @DisplayName("should include cause in toString")
        void shouldIncludeCauseInToString() {
            RuntimeException cause = new RuntimeException("Root cause message");
            McpException exception = new McpException("Wrapper", "CODE", cause);

            String result = exception.toString();

            assertTrue(result.contains("McpException"));
            assertTrue(result.contains("[CODE]"));
            assertTrue(result.contains("Wrapper"));
            assertTrue(result.contains("cause par"));
            assertTrue(result.contains("Root cause message"));
        }
    }

    // ========================================================================
    // Inheritance tests
    // ========================================================================

    @Nested
    @DisplayName("Inheritance")
    class InheritanceTests {

        @Test
        @DisplayName("should be an instance of Exception")
        void shouldBeInstanceOfException() {
            McpException exception = new McpException("Test");

            assertTrue(exception instanceof Exception);
        }

        @Test
        @DisplayName("should be throwable")
        void shouldBeThrowable() {
            McpException exception = new McpException("Test error");

            assertThrows(McpException.class, () -> {
                throw exception;
            });
        }

        @Test
        @DisplayName("should be catchable as Exception")
        void shouldBeCatchableAsException() {
            String caught = null;
            try {
                throw new McpException("MCP error", "ERROR_CODE");
            } catch (Exception e) {
                caught = e.getMessage();
            }

            assertEquals("MCP error", caught);
        }
    }
}
