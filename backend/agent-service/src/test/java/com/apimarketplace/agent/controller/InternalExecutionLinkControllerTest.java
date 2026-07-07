package com.apimarketplace.agent.controller;

import com.apimarketplace.agent.service.ModelExecutionLinkService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * The internal resolve endpoint feeding orchestrator's BrowserAgentModule. The
 * contract under test: only direct-API targets are ever returned (a bridge-target
 * link yields 204, keeping the caller on the billed pair), and resolution is
 * ALL-scope only (the null activity source is pinned by the resolve stub).
 */
@DisplayName("InternalExecutionLinkController")
@ExtendWith(MockitoExtension.class)
class InternalExecutionLinkControllerTest {

    @Mock private ModelExecutionLinkService service;

    private InternalExecutionLinkController controller;

    @BeforeEach
    void setUp() {
        controller = new InternalExecutionLinkController(service);
    }

    @Test
    @DisplayName("a direct-API link is returned as {executionProvider, executionModel}")
    void apiTargetReturned() {
        when(service.resolve("anthropic", "claude-opus-4-8", null))
            .thenReturn(Optional.of(new ModelExecutionLinkService.ExecutionRoute(
                "openrouter", "anthropic/claude-3.5-sonnet")));

        ResponseEntity<Map<String, Object>> response =
            controller.resolveApiTarget("anthropic", "claude-opus-4-8");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody())
            .containsEntry("executionProvider", "openrouter")
            .containsEntry("executionModel", "anthropic/claude-3.5-sonnet");
    }

    @Test
    @DisplayName("an unlinked pair yields 204 (caller keeps the billed pair)")
    void unlinkedPairIs204() {
        when(service.resolve("openai", "gpt-5", null)).thenReturn(Optional.empty());

        ResponseEntity<Map<String, Object>> response = controller.resolveApiTarget("openai", "gpt-5");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(response.getBody()).isNull();
    }

    @Test
    @DisplayName("a bridge-target link yields 204, not the bridge slug - the browser runner cannot execute on a CLI bridge")
    void bridgeTargetIs204() {
        when(service.resolve("anthropic", "claude-opus-4-8", null))
            .thenReturn(Optional.of(new ModelExecutionLinkService.ExecutionRoute(
                "claude-code", "claude-opus-4-8")));

        ResponseEntity<Map<String, Object>> response =
            controller.resolveApiTarget("anthropic", "claude-opus-4-8");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(response.getBody()).isNull();
    }
}
