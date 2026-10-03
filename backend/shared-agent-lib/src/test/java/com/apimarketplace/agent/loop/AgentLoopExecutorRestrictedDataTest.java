package com.apimarketplace.agent.loop;

import com.apimarketplace.agent.domain.CompletionRequest;
import com.apimarketplace.agent.domain.Message;
import com.apimarketplace.agent.domain.ToolCall;
import com.apimarketplace.agent.domain.ToolResult;
import com.apimarketplace.agent.logging.AgentLogger;
import com.apimarketplace.agent.provider.LLMProvider;
import com.apimarketplace.agent.tool.ToolExecutionService;
import com.apimarketplace.common.classification.DataSensitivity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * LC-004: Gmail / Drive content only reaches an allow-listed LLM provider on the direct-API path.
 * A restricted tool result is withheld from any other provider, and an execution already tagged
 * restricted is refused before a byte is sent.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AgentLoopExecutor restricted-data gate")
class AgentLoopExecutorRestrictedDataTest {

    private static final String SECRET = "Hi Alice, the wire transfer of 45000 EUR is approved";

    @Mock private ToolExecutionService toolExecutionService;
    @Mock private LLMProvider provider;

    private AgentLoopExecutor executor;
    private LoopExecutionState state;

    @BeforeEach
    void setUp() {
        executor = new AgentLoopExecutor(toolExecutionService, AgentLogger.NOOP,
                Executors.newSingleThreadExecutor(), 5000L, false);
        state = new LoopExecutionState("run-restricted", 10, 0);
    }

    private static ToolResult gmailResult() {
        ToolCall call = ToolCall.builder().id("call_1").toolName("catalog").arguments(Map.of()).build();
        return ToolResult.builder().toolCall(call).success(true)
                .content("{\"body\":\"" + SECRET + "\"}")
                .metadata(Map.of("iconSlug", "gmail")).build();
    }

    @Test
    @DisplayName("a Gmail result is withheld from a provider outside the allow-list and replaced by the refusal")
    void withheldFromDisallowedProvider() {
        executor.addToolResultMessages(state, List.of(gmailResult()), false, "deepseek");

        Message toolMessage = state.getMessages().get(0);
        assertThat(toolMessage.content()).doesNotContain(SECRET).contains("cannot be sent").contains("deepseek");
        assertThat(state.isRestrictedData()).isFalse();
    }

    @Test
    @DisplayName("a Gmail result reaches an allowed provider and marks the execution restricted")
    void reachesAllowedProviderAndTaints() {
        executor.addToolResultMessages(state, List.of(gmailResult()), false, "anthropic");

        assertThat(state.getMessages().get(0).content()).contains(SECRET);
        assertThat(state.isRestrictedData()).isTrue();
    }

    @Test
    @DisplayName("an ordinary result is untouched whatever the provider")
    void ordinaryResultUntouched() {
        ToolCall call = ToolCall.builder().id("call_2").toolName("web_search").arguments(Map.of()).build();
        ToolResult web = ToolResult.builder().toolCall(call).success(true).content("public page")
                .metadata(Map.of("iconSlug", "websearch")).build();

        executor.addToolResultMessages(state, List.of(web), false, "openrouter");

        assertThat(state.getMessages().get(0).content()).isEqualTo("public page");
        assertThat(state.isRestrictedData()).isFalse();
    }

    @Test
    @DisplayName("an execution tagged restricted upstream is refused before the provider is called")
    void taggedExecutionRefusedBeforeCall() {
        when(provider.getProviderName()).thenReturn("openrouter");
        AgentLoopContext context = AgentLoopContext.builder()
                .userPrompt("summarise my inbox")
                .provider("openrouter")
                .model("x")
                .maxIterations(3)
                .credentials(Map.of(DataSensitivity.CREDENTIAL_KEY, "RESTRICTED"))
                .build();

        AgentLoopExecutor.IterationResult result =
                executor.processIteration(provider, "x", context, List.of(), state, "system", null);

        assertThat(result.isError()).isTrue();
        assertThat(result.errorMessage()).contains("openrouter");
        verify(provider, never()).complete(any(CompletionRequest.class));
    }
}
