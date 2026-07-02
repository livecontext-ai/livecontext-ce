package com.apimarketplace.agent.loop;

import com.apimarketplace.agent.domain.CompletionRequest;
import com.apimarketplace.agent.logging.AgentLogger;
import com.apimarketplace.agent.tool.ToolExecutionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the {@code AgentLoopContext → CompletionRequest} seam in
 * {@link AgentLoopExecutor#buildCompletionRequest}. Every LLM call in the
 * platform flows through this single builder, so a field silently dropped here
 * is a platform-wide feature loss. Guarded field: {@code reasoningEffort} - the
 * resolved level must reach the direct-API request so {@code ClaudeProvider}
 * can map it to Anthropic {@code output_config.effort}.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AgentLoopExecutor.buildCompletionRequest - context field wiring")
class AgentLoopExecutorRequestWiringTest {

    @Mock private ToolExecutionService toolExecutionService;

    private AgentLoopExecutor executor;

    @BeforeEach
    void setUp() {
        executor = new AgentLoopExecutor(
            toolExecutionService, AgentLogger.NOOP,
            Executors.newSingleThreadExecutor(), 5000L, false);
    }

    @Test
    @DisplayName("reasoningEffort flows from the loop context into the CompletionRequest (regression: was dropped)")
    void reasoningEffortReachesTheRequest() {
        AgentLoopContext context = AgentLoopContext.builder()
            .tenantId("t-1")
            .provider("anthropic")
            .userPrompt("hi")
            .reasoningEffort("xhigh")
            .build();

        CompletionRequest request = executor.buildCompletionRequest(
            context, "claude-fable-5", "sys", List.of(), List.of(), false);

        assertThat(request.reasoningEffort()).isEqualTo("xhigh");
        assertThat(request.model()).isEqualTo("claude-fable-5");
        assertThat(request.tenantId()).isEqualTo("t-1");
    }

    @Test
    @DisplayName("null effort in the context stays null on the request (provider omits the parameter)")
    void nullEffortStaysNull() {
        AgentLoopContext context = AgentLoopContext.builder()
            .tenantId("t-1")
            .provider("anthropic")
            .userPrompt("hi")
            .build();

        CompletionRequest request = executor.buildCompletionRequest(
            context, "claude-fable-5", "sys", List.of(), List.of(), false);

        assertThat(request.reasoningEffort()).isNull();
    }
}
