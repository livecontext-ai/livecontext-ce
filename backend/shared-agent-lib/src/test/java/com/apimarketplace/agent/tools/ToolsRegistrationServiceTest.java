package com.apimarketplace.agent.tools;

import com.apimarketplace.agent.registry.AgentToolDefinition;
import com.apimarketplace.agent.registry.AgentToolRegistry;
import com.apimarketplace.agent.registry.ToolCategory;
import com.apimarketplace.agent.tools.interceptor.ToolExecutionInterceptor;
import com.apimarketplace.agent.tools.validation.ToolParameterValidator;
import com.apimarketplace.agent.tools.validation.ValidationResult;
import com.apimarketplace.common.web.TenantResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Tests for ToolsRegistrationService.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ToolsRegistrationService")
class ToolsRegistrationServiceTest {

    @Mock
    private AgentToolRegistry registry;

    @Mock
    private ToolParameterValidator validator;

    private ToolsRegistrationService service;

    @BeforeEach
    void setUp() {
        service = new ToolsRegistrationService(registry, List.of(), null, null, null);
    }

    @Nested
    @DisplayName("getProviderForTool()")
    class GetProviderTests {

        @Test
        @DisplayName("should return null when no providers registered")
        void shouldReturnNullWhenNone() {
            assertThat(service.getProviderForTool("unknown")).isNull();
        }

        @Test
        @DisplayName("should find provider from cache after registration")
        void shouldFindFromCache() {
            AgentToolDefinition toolDef = AgentToolDefinition.builder()
                    .name("my_tool").description("test").category(ToolCategory.UTILITY).build();

            ToolsProvider provider = mock(ToolsProvider.class);
            when(provider.getCategory()).thenReturn(ToolCategory.UTILITY);
            when(provider.getTools()).thenReturn(List.of(toolDef));

            ToolsRegistrationService svcWithProvider = new ToolsRegistrationService(
                    registry, List.of(provider), null, null, null);
            svcWithProvider.registerAllTools();

            assertThat(svcWithProvider.getProviderForTool("my_tool")).isEqualTo(provider);
        }
    }

    @Nested
    @DisplayName("executeTool()")
    class ExecuteToolTests {

        @Test
        @DisplayName("should return TOOL_NOT_FOUND when no provider matches")
        void shouldReturnNotFound() {
            ToolsProvider.ToolExecutionResult result = service.executeTool(
                    "nonexistent", Map.of(), ToolsProvider.ToolExecutionContext.empty());

            assertThat(result.success()).isFalse();
            assertThat(result.errorCode()).isEqualTo(ToolErrorCode.TOOL_NOT_FOUND);
        }

        @Test
        @DisplayName("should execute tool via provider when found")
        void shouldExecuteViaProvider() {
            AgentToolDefinition toolDef = AgentToolDefinition.builder()
                    .name("test_tool").description("test").category(ToolCategory.UTILITY).build();

            ToolsProvider provider = mock(ToolsProvider.class);
            when(provider.getCategory()).thenReturn(ToolCategory.UTILITY);
            when(provider.getTools()).thenReturn(List.of(toolDef));
            when(provider.execute(anyString(), any(), any()))
                    .thenReturn(ToolsProvider.ToolExecutionResult.success("result"));

            ToolsRegistrationService svcWithProvider = new ToolsRegistrationService(
                    registry, List.of(provider), null, null, null);
            svcWithProvider.registerAllTools();

            ToolsProvider.ToolExecutionResult result = svcWithProvider.executeTool(
                    "test_tool", Map.of("key", "value"), ToolsProvider.ToolExecutionContext.empty());

            assertThat(result.success()).isTrue();
            assertThat(result.data()).isEqualTo("result");
            verify(provider).execute(eq("test_tool"), any(), any());
        }

        @Test
        @DisplayName("should validate parameters when validator is available")
        void shouldValidateParameters() {
            AgentToolDefinition toolDef = AgentToolDefinition.builder()
                    .name("validated_tool").description("test").category(ToolCategory.UTILITY).build();

            ToolsProvider provider = mock(ToolsProvider.class);
            when(provider.getCategory()).thenReturn(ToolCategory.UTILITY);
            when(provider.getTools()).thenReturn(List.of(toolDef));

            when(validator.applyParameterAliases(any())).thenReturn(Map.of());
            when(validator.validate(anyString(), any()))
                    .thenReturn(ValidationResult.failure(
                            new ValidationResult.ValidationError("param", "Missing", ToolErrorCode.MISSING_PARAMETER)));

            ToolsRegistrationService svcWithValidator = new ToolsRegistrationService(
                    registry, List.of(provider), validator, null, null);
            svcWithValidator.registerAllTools();

            ToolsProvider.ToolExecutionResult result = svcWithValidator.executeTool(
                    "validated_tool", Map.of(), ToolsProvider.ToolExecutionContext.empty());

            assertThat(result.success()).isFalse();
            verify(provider, never()).execute(anyString(), any(), any());
        }

        @Test
        @DisplayName("should handle execution exceptions gracefully")
        void shouldHandleExecutionExceptions() {
            AgentToolDefinition toolDef = AgentToolDefinition.builder()
                    .name("failing_tool").description("test").category(ToolCategory.UTILITY).build();

            ToolsProvider provider = mock(ToolsProvider.class);
            when(provider.getCategory()).thenReturn(ToolCategory.UTILITY);
            when(provider.getTools()).thenReturn(List.of(toolDef));
            when(provider.execute(anyString(), any(), any()))
                    .thenThrow(new RuntimeException("Unexpected error"));

            ToolsRegistrationService svcWithProvider = new ToolsRegistrationService(
                    registry, List.of(provider), null, null, null);
            svcWithProvider.registerAllTools();

            ToolsProvider.ToolExecutionResult result = svcWithProvider.executeTool(
                    "failing_tool", Map.of(), ToolsProvider.ToolExecutionContext.empty());

            assertThat(result.success()).isFalse();
            assertThat(result.errorCode()).isEqualTo(ToolErrorCode.EXECUTION_FAILED);
            assertThat(result.error()).contains("Unexpected error");
        }
    }

    @Nested
    @DisplayName("executeToolAsync()")
    class ExecuteToolAsyncTests {

        @Test
        @DisplayName("Binds context orgId before provider execution on async worker")
        void bindsContextOrgIdBeforeProviderExecution() throws Exception {
            AgentToolDefinition toolDef = AgentToolDefinition.builder()
                    .name("async_tool").description("test").category(ToolCategory.UTILITY).build();

            ToolsProvider provider = mock(ToolsProvider.class);
            when(provider.getCategory()).thenReturn(ToolCategory.UTILITY);
            when(provider.getTools()).thenReturn(List.of(toolDef));
            when(provider.execute(eq("async_tool"), any(), any()))
                    .thenAnswer(invocation -> ToolsProvider.ToolExecutionResult.success(
                            TenantResolver.currentRequestOrganizationId()));

            ToolsRegistrationService svcWithProvider = new ToolsRegistrationService(
                    registry, List.of(provider), null, null, null);
            svcWithProvider.registerAllTools();
            ToolsProvider.ToolExecutionContext context = new ToolsProvider.ToolExecutionContext(
                    "tenant-1", Map.of(), Map.of(), Set.of(), null, null, "org-async-registration", "admin");

            ToolsProvider.ToolExecutionResult result = svcWithProvider
                    .executeToolAsync("async_tool", Map.of(), context)
                    .get(2, TimeUnit.SECONDS);

            assertThat(result.success()).isTrue();
            assertThat(result.data()).isEqualTo("org-async-registration");
            assertThat(TenantResolver.currentRequestOrganizationId())
                    .as("Async org binding must restore the caller thread after completion.")
                    .isNull();
        }
    }

    @Nested
    @DisplayName("registerAllTools()")
    class RegisterAllToolsTests {

        @Test
        @DisplayName("should throw IllegalStateException when a provider fails to register")
        void shouldThrowWhenProviderFails() {
            ToolsProvider failingProvider = mock(ToolsProvider.class);
            when(failingProvider.getCategory()).thenReturn(ToolCategory.WORKFLOW);
            when(failingProvider.getTools()).thenThrow(new RuntimeException("DB deserialization error"));

            ToolsRegistrationService svcWithFailure = new ToolsRegistrationService(
                    registry, List.of(failingProvider), null, null, null);

            assertThatThrownBy(svcWithFailure::registerAllTools)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("1 tool provider(s) failed to register")
                    .hasMessageContaining("DB deserialization error");
        }

        @Test
        @DisplayName("should report all failed providers in the error message")
        void shouldReportAllFailures() {
            ToolsProvider failing1 = mock(ToolsProvider.class);
            when(failing1.getCategory()).thenReturn(ToolCategory.WORKFLOW);
            when(failing1.getTools()).thenThrow(new RuntimeException("Error A"));

            ToolsProvider failing2 = mock(ToolsProvider.class);
            when(failing2.getCategory()).thenReturn(ToolCategory.AGENT);
            when(failing2.getTools()).thenThrow(new RuntimeException("Error B"));

            ToolsRegistrationService svcWithFailures = new ToolsRegistrationService(
                    registry, List.of(failing1, failing2), null, null, null);

            assertThatThrownBy(svcWithFailures::registerAllTools)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("2 tool provider(s) failed to register")
                    .hasMessageContaining("Error A")
                    .hasMessageContaining("Error B");
        }
    }

    @Nested
    @DisplayName("getCacheStats()")
    class CacheStatsTests {

        @Test
        @DisplayName("should return stats with zero initial cache")
        void shouldReturnStats() {
            Map<String, Object> stats = service.getCacheStats();

            assertThat(stats.get("cachedTools")).isEqualTo(0);
            assertThat(stats.get("providers")).isEqualTo(0);
            assertThat(stats.get("interceptors")).isEqualTo(0);
        }
    }
}
