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
}
