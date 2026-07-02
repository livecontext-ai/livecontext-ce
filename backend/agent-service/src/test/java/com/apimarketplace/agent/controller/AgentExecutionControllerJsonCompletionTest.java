package com.apimarketplace.agent.controller;

import com.apimarketplace.agent.client.dto.execution.JsonCompletionRequestDto;
import com.apimarketplace.agent.client.dto.execution.JsonCompletionResponseDto;
import com.apimarketplace.agent.completion.ProviderLlmJsonInvoker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the {@code /json-completion} endpoint on
 * {@link AgentExecutionController}. Asserts the controller forwards the
 * request to {@link ProviderLlmJsonInvoker} with the correct field
 * mapping and wraps the raw content in a {@link JsonCompletionResponseDto}.
 */
@DisplayName("AgentExecutionController#executeJsonCompletion")
@ExtendWith(MockitoExtension.class)
class AgentExecutionControllerJsonCompletionTest {

    @Mock ProviderLlmJsonInvoker invoker;

    private AgentExecutionController controller;

    @BeforeEach
    void setUp() {
        controller = new AgentExecutionController(null, null, null, invoker);
    }

    @Test
    @DisplayName("Maps request fields to invoker args and wraps the content in the response DTO")
    void mapsAndWrapsResponse() {
        when(invoker.invoke(eq("google"), eq("gemini-3-flash"), eq("SYS"), eq("USER"), eq("tenant-42")))
                .thenReturn("{\"k\":\"v\"}");

        JsonCompletionRequestDto req = new JsonCompletionRequestDto(
                "google", "gemini-3-flash", "SYS", "USER", "tenant-42");

        ResponseEntity<JsonCompletionResponseDto> response = controller.executeJsonCompletion(null, req);

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().content()).isEqualTo("{\"k\":\"v\"}");
    }

    @Test
    @DisplayName("Null tenantId is forwarded verbatim (system-internal caller)")
    void nullTenantForwarded() {
        when(invoker.invoke(eq("anthropic"), eq("claude"), eq("s"), eq("u"), eq(null)))
                .thenReturn("{}");

        JsonCompletionRequestDto req = new JsonCompletionRequestDto(
                "anthropic", "claude", "s", "u", null);

        ResponseEntity<JsonCompletionResponseDto> response = controller.executeJsonCompletion(null, req);

        assertThat(response.getBody().content()).isEqualTo("{}");
    }

    @Test
    @DisplayName("Invoker IllegalStateException propagates as-is (Spring maps to 500)")
    void invokerErrorPropagates() {
        when(invoker.invoke(eq("google"), eq("m"), eq("s"), eq("u"), eq(null)))
                .thenThrow(new IllegalStateException("empty content"));

        JsonCompletionRequestDto req = new JsonCompletionRequestDto("google", "m", "s", "u", null);

        assertThatThrownBy(() -> controller.executeJsonCompletion(null, req))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("empty content");
    }

    // ── Model execution links (third link consumer - compaction summariser gap) ──

    @Test
    @DisplayName("Honors an API-target execution link: the invoker runs the linked pair, not the requested one")
    void apiTargetLinkSwapsExecutionPair() {
        com.apimarketplace.agent.service.ModelExecutionLinkService linkService =
                org.mockito.Mockito.mock(com.apimarketplace.agent.service.ModelExecutionLinkService.class);
        org.springframework.test.util.ReflectionTestUtils.setField(controller, "executionLinkService", linkService);
        when(linkService.resolveSingleCompletionTarget("anthropic", "claude-haiku-4-5"))
                .thenReturn(new com.apimarketplace.agent.service.ModelExecutionLinkService.SingleCompletionTarget(
                        "openrouter", "anthropic/claude-haiku"));
        when(invoker.invoke(eq("openrouter"), eq("anthropic/claude-haiku"), eq("s"), eq("u"), eq("t-39")))
                .thenReturn("{\"summary\":\"ok\"}");

        JsonCompletionRequestDto req = new JsonCompletionRequestDto(
                "anthropic", "claude-haiku-4-5", "s", "u", "t-39");

        ResponseEntity<JsonCompletionResponseDto> response = controller.executeJsonCompletion(null, req);

        assertThat(response.getBody().content()).isEqualTo("{\"summary\":\"ok\"}");
    }

    @Test
    @DisplayName("Bridge-target link rejection propagates as IllegalArgumentException (GlobalExceptionHandler maps to 400)")
    void bridgeTargetLinkRejectionPropagates() {
        com.apimarketplace.agent.service.ModelExecutionLinkService linkService =
                org.mockito.Mockito.mock(com.apimarketplace.agent.service.ModelExecutionLinkService.class);
        org.springframework.test.util.ReflectionTestUtils.setField(controller, "executionLinkService", linkService);
        when(linkService.resolveSingleCompletionTarget("anthropic", "claude-opus-4-8"))
                .thenThrow(new IllegalArgumentException("BRIDGE_EXECUTION_NOT_RELAYABLE: ..."));

        JsonCompletionRequestDto req = new JsonCompletionRequestDto(
                "anthropic", "claude-opus-4-8", "s", "u", null);

        assertThatThrownBy(() -> controller.executeJsonCompletion(null, req))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("BRIDGE_EXECUTION_NOT_RELAYABLE");
        org.mockito.Mockito.verifyNoInteractions(invoker);
    }

    @Test
    @DisplayName("Absent link service (CE / feature off) keeps the requested pair verbatim")
    void absentLinkServiceKeepsRequestedPair() {
        // setUp leaves executionLinkService null - the CE wiring.
        when(invoker.invoke(eq("deepseek"), eq("deepseek-chat"), eq("s"), eq("u"), eq(null)))
                .thenReturn("{}");

        JsonCompletionRequestDto req = new JsonCompletionRequestDto(
                "deepseek", "deepseek-chat", "s", "u", null);

        assertThat(controller.executeJsonCompletion(null, req).getBody().content()).isEqualTo("{}");
    }
}
