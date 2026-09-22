package com.apimarketplace.agent.controller;

import com.apimarketplace.agent.bridge.BridgeAccessDecision;
import com.apimarketplace.agent.bridge.BridgeAccessDeniedException;
import com.apimarketplace.agent.client.dto.execution.JsonCompletionRequestDto;
import com.apimarketplace.agent.client.dto.execution.JsonCompletionResponseDto;
import com.apimarketplace.agent.service.execution.JsonCompletionService;
import jakarta.servlet.http.HttpServletRequest;
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
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the {@code /json-completion} endpoint on {@link AgentExecutionController}.
 * The controller is a thin layer: it hands the request and the inbound roles to
 * {@link JsonCompletionService} (which decides where the completion runs) and wraps the
 * content in a {@link JsonCompletionResponseDto}. Routing itself is tested on the service.
 */
@DisplayName("AgentExecutionController#executeJsonCompletion")
@ExtendWith(MockitoExtension.class)
class AgentExecutionControllerJsonCompletionTest {

    @Mock JsonCompletionService jsonCompletionService;
    @Mock HttpServletRequest httpRequest;

    private AgentExecutionController controller;

    @BeforeEach
    void setUp() {
        controller = new AgentExecutionController(null, null, null, jsonCompletionService);
    }

    @Test
    @DisplayName("Hands the request to the service and wraps the content in the response DTO")
    void forwardsAndWrapsResponse() {
        JsonCompletionRequestDto req = new JsonCompletionRequestDto(
                "google", "gemini-3-flash", "SYS", "USER", "tenant-42");
        when(jsonCompletionService.complete(same(req), isNull())).thenReturn("{\"k\":\"v\"}");

        ResponseEntity<JsonCompletionResponseDto> response = controller.executeJsonCompletion(null, req);

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().content()).isEqualTo("{\"k\":\"v\"}");
    }

    @Test
    @DisplayName("The inbound X-User-Roles reach the service: a bridge the caller NAMED is judged on them")
    void forwardsUserRoles() {
        when(httpRequest.getHeader("X-User-Roles")).thenReturn(" USER,ADMIN ");
        JsonCompletionRequestDto req = new JsonCompletionRequestDto("claude-code", "m", "s", "u", "t");
        when(jsonCompletionService.complete(same(req), eq("USER,ADMIN"))).thenReturn("{}");

        assertThat(controller.executeJsonCompletion(httpRequest, req).getBody().content()).isEqualTo("{}");
    }

    @Test
    @DisplayName("Service IllegalStateException propagates as-is (Spring maps to 500)")
    void serviceErrorPropagates() {
        JsonCompletionRequestDto req = new JsonCompletionRequestDto("google", "m", "s", "u", null);
        when(jsonCompletionService.complete(same(req), isNull()))
                .thenThrow(new IllegalStateException("empty content"));

        assertThatThrownBy(() -> controller.executeJsonCompletion(null, req))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("empty content");
    }

    @Test
    @DisplayName("A bridge access denial propagates untouched so the advice maps it to 403/429")
    void bridgeDenialPropagates() {
        JsonCompletionRequestDto req = new JsonCompletionRequestDto("claude-code", "m", "s", "u", "t");
        when(jsonCompletionService.complete(same(req), isNull())).thenThrow(
                new BridgeAccessDeniedException("claude-code", BridgeAccessDecision.REASON_NOT_ADMIN, null));

        assertThatThrownBy(() -> controller.executeJsonCompletion(null, req))
                .isInstanceOf(BridgeAccessDeniedException.class);
    }
}
