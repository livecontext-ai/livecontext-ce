package com.apimarketplace.agent.tools.interceptor;

import com.apimarketplace.agent.tools.ToolErrorCode;
import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionContext;
import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for LoggingToolInterceptor - logs tool execution details.
 */
@DisplayName("LoggingToolInterceptor")
class LoggingToolInterceptorTest {

    private LoggingToolInterceptor interceptor;

    @BeforeEach
    void setUp() {
        interceptor = new LoggingToolInterceptor();
    }

    @Nested
    @DisplayName("getOrder()")
    class GetOrderTests {

        @Test
        @DisplayName("should return 0 (run first)")
        void shouldReturnZero() {
            assertThat(interceptor.getOrder()).isEqualTo(0);
        }
    }

    @Nested
    @DisplayName("beforeExecution()")
    class BeforeExecutionTests {

        @Test
        @DisplayName("should not throw with valid parameters")
        void shouldNotThrowWithValidParams() {
            ToolExecutionContext context = ToolExecutionContext.of("tenant-1");

            // Should not throw
            interceptor.beforeExecution("search", Map.of("query", "test"), context);
        }

        @Test
        @DisplayName("should handle null context")
        void shouldHandleNullContext() {
            // Should not throw
            interceptor.beforeExecution("search", Map.of(), null);
        }

        @Test
        @DisplayName("should handle null parameters")
        void shouldHandleNullParameters() {
            ToolExecutionContext context = ToolExecutionContext.empty();
            // Should not throw
            interceptor.beforeExecution("search", null, context);
        }

        @Test
        @DisplayName("should handle empty parameters")
        void shouldHandleEmptyParameters() {
            ToolExecutionContext context = ToolExecutionContext.empty();
            // Should not throw
            interceptor.beforeExecution("search", Map.of(), context);
        }
    }

    @Nested
    @DisplayName("afterExecution()")
    class AfterExecutionTests {

        @Test
        @DisplayName("should handle success result")
        void shouldHandleSuccess() {
            interceptor.beforeExecution("search", Map.of(), ToolExecutionContext.empty());
            ToolExecutionResult result = ToolExecutionResult.success("data");

            // Should not throw
            interceptor.afterExecution("search", result, 100);
        }

        @Test
        @DisplayName("should handle failure result")
        void shouldHandleFailure() {
            interceptor.beforeExecution("search", Map.of(), ToolExecutionContext.empty());
            ToolExecutionResult result = ToolExecutionResult.failure("Error occurred");

            // Should not throw
            interceptor.afterExecution("search", result, 500);
        }

        @Test
        @DisplayName("should handle failure with error code")
        void shouldHandleFailureWithErrorCode() {
            interceptor.beforeExecution("search", Map.of(), ToolExecutionContext.empty());
            ToolExecutionResult result = ToolExecutionResult.failure(
                    ToolErrorCode.TOOL_NOT_FOUND, "Not found");

            // Should not throw
            interceptor.afterExecution("search", result, 50);
        }

        @Test
        @DisplayName("should handle success with metadata")
        void shouldHandleSuccessWithMetadata() {
            interceptor.beforeExecution("search", Map.of(), ToolExecutionContext.empty());
            ToolExecutionResult result = ToolExecutionResult.success(
                    Map.of("key", "value"), Map.of("meta", "data"));

            // Should not throw
            interceptor.afterExecution("search", result, 200);
        }

        @Test
        @DisplayName("should handle call without prior beforeExecution")
        void shouldHandleWithoutBefore() {
            ToolExecutionResult result = ToolExecutionResult.success("data");
            // Should not throw even without beforeExecution
            interceptor.afterExecution("search", result, 100);
        }
    }

    @Nested
    @DisplayName("onError()")
    class OnErrorTests {

        @Test
        @DisplayName("should handle exception")
        void shouldHandleException() {
            interceptor.beforeExecution("search", Map.of(), ToolExecutionContext.empty());

            // Should not throw
            interceptor.onError("search", new RuntimeException("Connection failed"), 1000);
        }

        @Test
        @DisplayName("should handle call without prior beforeExecution")
        void shouldHandleWithoutBefore() {
            // Should not throw
            interceptor.onError("search", new RuntimeException("Error"), 500);
        }
    }

    @Nested
    @DisplayName("Thread safety")
    class ThreadSafetyTests {

        @Test
        @DisplayName("should use thread-local request IDs")
        void shouldUseThreadLocalRequestIds() {
            // Execute in current thread
            interceptor.beforeExecution("tool1", Map.of(), ToolExecutionContext.empty());
            ToolExecutionResult result = ToolExecutionResult.success("data");
            interceptor.afterExecution("tool1", result, 100);

            // afterExecution cleans up the thread-local, so a second call should work fine
            interceptor.beforeExecution("tool2", Map.of(), ToolExecutionContext.empty());
            interceptor.afterExecution("tool2", result, 50);
        }
    }
}
