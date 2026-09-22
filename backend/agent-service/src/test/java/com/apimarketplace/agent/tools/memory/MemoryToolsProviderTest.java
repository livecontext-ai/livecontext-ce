package com.apimarketplace.agent.tools.memory;

import com.apimarketplace.agent.memory.MemoryLimitsConfig;
import com.apimarketplace.agent.registry.AgentToolDefinition;
import com.apimarketplace.agent.registry.ToolCategory;
import com.apimarketplace.agent.tools.ToolErrorCode;
import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionContext;
import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.STRICT_STUBS)
@DisplayName("MemoryToolsProvider")
class MemoryToolsProviderTest {

    @Mock private MemoryCrudModule crudModule;
    @Mock private MemoryHelpModule helpModule;

    private MemoryLimitsConfig limits;
    private MemoryToolsProvider provider;

    @BeforeEach
    void setUp() {
        limits = new MemoryLimitsConfig();
        provider = new MemoryToolsProvider(crudModule, helpModule, limits);
        lenient().when(helpModule.canHandle("help")).thenReturn(true);
        lenient().when(crudModule.canHandle(anyString())).thenAnswer(inv ->
            Set.of("save", "get", "list", "search", "delete").contains(inv.getArgument(0)));
    }

    private static ToolExecutionContext ctx() {
        return new ToolExecutionContext("42", Map.of(), Map.of(), Set.of(), null, null, "org", "MEMBER");
    }

    private AgentToolDefinition tool() {
        List<AgentToolDefinition> tools = provider.getTools();
        assertThat(tools).hasSize(1);
        return tools.get(0);
    }

    @Test
    @DisplayName("registers a single tool named memory, in the AGENT category")
    void registersOneTool() {
        assertThat(tool().name()).isEqualTo("memory");
        assertThat(provider.getCategory()).isEqualTo(ToolCategory.AGENT);
    }

    @Test
    @DisplayName("declares action as the only required parameter, with the six valid values")
    void declaresActionEnum() {
        AgentToolDefinition tool = tool();

        assertThat(tool.requiredParameters()).containsExactly("action");
        assertThat(tool.parameters().stream()
            .filter(p -> p.name().equals("action"))
            .findFirst().orElseThrow().enumValues())
            .containsExactlyInAnyOrder("save", "get", "list", "search", "delete", "help");
    }

    @Test
    @DisplayName("teaches the declarative-vs-imperative rule in the description the model always reads")
    void descriptionCarriesTheDeclarativeRule() {
        assertThat(tool().description())
            .contains("Declarative facts")
            .contains("never instructions")
            .contains("memory(action='help')");
    }

    @Test
    @DisplayName("promises the in-context index only conditionally, and names the fallback")
    void descriptionSaysWhereTheIndexIsWithoutPromisingIt() {
        // Same contract as the help page: the heading exists only when the workspace
        // has memories AND the session gets a system prompt. This description is read
        // by every model on every turn, so an unconditional promise here is the most
        // expensive place to be wrong.
        assertThat(tool().description())
            .contains("When past preferences or decisions help")
            .contains("list(as_index=true)")
            .contains("if absent")
            .doesNotContain("already in your context");
    }

    @Test
    @DisplayName("warns on the summary parameter that the line is paid for on every run")
    void summaryParameterExplainsItsCost() {
        assertThat(tool().parameters().stream()
            .filter(p -> p.name().equals("summary"))
            .findFirst().orElseThrow().description())
            .contains("injected into the index for agents in scope");
    }

    @Test
    @DisplayName("still registers the tool when memory is disabled, so the cache does not chase a missing one")
    void keepsTheToolRegisteredWhenDisabled() {
        limits.setEnabled(false);

        // Withdrawing it looked tidier and was worse: the module resolver adds
        // `memory` to every agent's modules regardless (it cannot see this config),
        // so the routing line would advertise a tool that no longer exists, and
        // CoreToolsCache would re-poll agent-service for it every five minutes.
        assertThat(provider.getTools()).hasSize(1);
    }

    @Test
    @DisplayName("refuses every data action when memory is disabled, saying so in words the agent can act on")
    void dataActionsRefuseWhenDisabled() {
        limits.setEnabled(false);

        for (String action : List.of("save", "get", "list", "search", "delete")) {
            ToolExecutionResult result = provider.execute("memory", Map.of("action", action), ctx());
            assertThat(result.success()).as(action).isFalse();
            assertThat(result.error()).as(action)
                .contains("switched off on this installation")
                .contains("carried in your reply");
        }
    }

    @Test
    @DisplayName("still answers help when memory is disabled, so the refusal can be understood")
    void helpAnswersWhenDisabled() {
        limits.setEnabled(false);
        when(helpModule.execute(anyString(), any(), any(), any()))
            .thenReturn(Optional.of(ToolExecutionResult.success(Map.of("description", "..."))));

        assertThat(provider.execute("memory", Map.of("action", "help"), ctx()).success()).isTrue();
    }

    @Test
    @DisplayName("refuses a tool name it does not own")
    void rejectsForeignToolName() {
        ToolExecutionResult result = provider.execute("skill", Map.of("action", "list"), ctx());

        assertThat(result.success()).isFalse();
        assertThat(result.errorCode()).isEqualTo(ToolErrorCode.TOOL_NOT_FOUND);
    }

    @Test
    @DisplayName("lists the valid actions when the caller omitted the action")
    void missingActionListsTheValidOnes() {
        ToolExecutionResult result = provider.execute("memory", Map.of(), ctx());

        assertThat(result.errorCode()).isEqualTo(ToolErrorCode.MISSING_PARAMETER);
        assertThat(result.error()).contains("save").contains("get").contains("help");
    }

    @Test
    @DisplayName("lists the valid actions when the caller invented one")
    void unknownActionListsTheValidOnes() {
        ToolExecutionResult result = provider.execute("memory", Map.of("action", "forget"), ctx());

        assertThat(result.errorCode()).isEqualTo(ToolErrorCode.INVALID_ENUM_VALUE);
        assertThat(result.error()).contains("Invalid action: forget");
    }

    @Test
    @DisplayName("answers help without a tenant, so a caller can read the docs before it has any context")
    void helpWorksWithoutTenant() {
        when(helpModule.execute(anyString(), any(), any(), any()))
            .thenReturn(Optional.of(ToolExecutionResult.success(Map.of("description", "..."))));

        ToolExecutionContext noTenant =
            new ToolExecutionContext(null, Map.of(), Map.of(), Set.of(), null, null, null, null);

        assertThat(provider.execute("memory", Map.of("action", "help"), noTenant).success()).isTrue();
    }

    @Test
    @DisplayName("requires a tenant for every action that touches data")
    void dataActionsRequireATenant() {
        ToolExecutionContext noTenant =
            new ToolExecutionContext(null, Map.of(), Map.of(), Set.of(), null, null, null, null);

        ToolExecutionResult result = provider.execute("memory", Map.of("action", "list"), noTenant);

        assertThat(result.success()).isFalse();
        assertThat(result.errorCode()).isEqualTo(ToolErrorCode.MISSING_PARAMETER);
    }

    @Test
    @DisplayName("turns an unexpected module failure into a tool error instead of propagating it into the loop")
    void wrapsUnexpectedFailures() {
        when(crudModule.execute(anyString(), any(), any(), any()))
            .thenThrow(new RuntimeException("boom"));

        ToolExecutionResult result = provider.execute("memory", Map.of("action", "list"), ctx());

        assertThat(result.success()).isFalse();
        assertThat(result.errorCode()).isEqualTo(ToolErrorCode.EXECUTION_FAILED);
        assertThat(result.error()).contains("boom");
    }
}
