package com.apimarketplace.agent.tools.agent;

import com.apimarketplace.agent.domain.AgentEntity;
import com.apimarketplace.agent.service.AgentService;
import com.apimarketplace.agent.service.ModelCatalogService;
import com.apimarketplace.agent.service.SkillService;
import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionContext;
import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * The flag that decides whether an agent must ask a human before doing something
 * sensitive, on the one call that can change it.
 *
 * <p>These exist because the first version validated the value in {@code create},
 * where nothing applies it, while {@code update} applied it unvalidated. A value
 * neither {@code true} nor {@code false} then read as false through
 * {@code Boolean.TRUE.equals}, so {@code require_tool_authorization='maybe'}
 * DISARMED an armed agent and answered success, with the authorization card silent
 * (its rule matches the literal {@code false}). One ungated call, on the guard
 * itself, and no test on this path to catch it.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AgentCrudModule - the tool-authorization flag")
class AgentCrudModuleToolAuthorizationTest {

    @Mock private AgentService agentService;
    @Mock private SkillService skillService;
    @Mock private com.apimarketplace.agent.webhook.AgentWebhookTokenService webhookTokenService;
    @Mock private ModelCatalogService modelCatalogService;
    @Mock private org.springframework.web.client.RestTemplate restTemplate;

    private AgentCrudModule module;

    private static final String TENANT = "tenant-123";
    private static final UUID AGENT_ID = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        lenient().when(modelCatalogService.isModelAvailable(anyString(), anyString())).thenReturn(true);
        lenient().when(modelCatalogService.getEffectiveDefaultModel()).thenReturn("claude-sonnet-4-6");
        lenient().when(modelCatalogService.getEffectiveDefaultProvider()).thenReturn("anthropic");
        module = new AgentCrudModule(agentService, skillService, webhookTokenService,
                new com.apimarketplace.agent.config.AgentDefaultsConfig(), modelCatalogService,
                restTemplate, "http://localhost:8091", "http://localhost:8080");
    }

    private ToolExecutionContext ctx() {
        return new ToolExecutionContext(TENANT, Map.of("turnId", "turn-1"), Map.of(),
                null, null, null, null, null);
    }

    private void agentExists() {
        AgentEntity agent = new AgentEntity();
        agent.setId(AGENT_ID);
        agent.setName("Night Publisher");
        agent.setModelProvider("openai");
        agent.setModelName("gpt-4");
        agent.setTemperature(BigDecimal.valueOf(0.7));
        agent.setMaxTokens(4096);
        agent.setRequireToolAuthorization(true);
        lenient().when(agentService.getAgent(any(UUID.class), anyString())).thenReturn(Optional.of(agent));
        lenient().when(agentService.getAgent(any(UUID.class), anyString(), any(), any()))
                .thenReturn(Optional.of(agent));
    }

    private ToolExecutionResult update(Object value) {
        agentExists();
        return module.execute("update", Map.of(
                "agent_id", AGENT_ID.toString(),
                "require_tool_authorization", value), TENANT, ctx()).orElseThrow();
    }

    @Test
    @DisplayName("a value that is neither true nor false never reaches the flag")
    void malformedValueDoesNotDisarm() {
        ToolExecutionResult result = update("maybe");

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("must be true or false").contains("Nothing was changed");
        // The bug this pins: reaching the patch with an unusable value disarms an armed
        // agent, reports success, and raises no card, because the disarm rule matches the
        // literal "false" and not "maybe".
        verify(agentService, never()).setRequireToolAuthorization(any(), anyString(), any(), anyBoolean());
    }

    @Test
    @DisplayName("refuses before writing anything, so its own sentence is true")
    void refusesBeforeTheUpdateRuns() {
        ToolExecutionResult result = update(1);

        assertThat(result.error()).contains("Nothing was changed");
        // "Nothing was changed" has to be true: the update, and three sibling patches,
        // run after this point. The arity matters - the module calls the 23-argument
        // overload, and a matcher for the 24-argument one can never match, which makes
        // never() vacuously true and the assertion worthless.
        verify(agentService, never()).updateAgent(any(), anyString(), anyString(), any(), any(),
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any());
        verify(agentService, never()).setInactivityTimeout(any(), anyString(), any(), any());
        verify(agentService, never()).setBacklogEnabled(any(), anyString(), any(), anyBoolean());
    }

    @Test
    @DisplayName("arms the agent when told true")
    void armsOnTrue() {
        agentExists();

        module.execute("update", Map.of(
                "agent_id", AGENT_ID.toString(),
                "require_tool_authorization", true), TENANT, ctx());

        verify(agentService).setRequireToolAuthorization(AGENT_ID, TENANT, null, true);
    }

    @Test
    @DisplayName("disarms the agent when told false, which is what the card is for")
    void disarmsOnFalse() {
        agentExists();

        module.execute("update", Map.of(
                "agent_id", AGENT_ID.toString(),
                "require_tool_authorization", false), TENANT, ctx());

        verify(agentService).setRequireToolAuthorization(AGENT_ID, TENANT, null, false);
    }

    @Test
    @DisplayName("leaves the flag alone when the call does not mention it")
    void untouchedWhenAbsent() {
        agentExists();

        module.execute("update", Map.of(
                "agent_id", AGENT_ID.toString(), "name", "Renamed"), TENANT, ctx());

        verify(agentService, never()).setRequireToolAuthorization(any(), anyString(), any(), anyBoolean());
    }

    @Test
    @DisplayName("create refuses the flag instead of accepting it and dropping it")
    void createRefusesTheFlag() {
        // A complete create otherwise, so the refusal under test is the one that fires
        // and not the required-field validation that runs before it.
        ToolExecutionResult result = module.execute("create", Map.of(
                "name", "Night Publisher",
                "system_prompt", "You publish the evening post.",
                "require_tool_authorization", true), TENANT, ctx()).orElseThrow();

        // create never applies it, so accepting it would report success on an agent that
        // is NOT armed - and its owner would believe it asks before publishing.
        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("create it first").contains("Nothing was created");
        verify(agentService, never()).setRequireToolAuthorization(any(), anyString(), any(), anyBoolean());
    }
}
