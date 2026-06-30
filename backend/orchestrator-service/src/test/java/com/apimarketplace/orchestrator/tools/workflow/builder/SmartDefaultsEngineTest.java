package com.apimarketplace.orchestrator.tools.workflow.builder;

import com.apimarketplace.agent.client.AgentClient;
import com.apimarketplace.orchestrator.config.AgentDefaultsConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * Tests for SmartDefaultsEngine.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SmartDefaultsEngine")
class SmartDefaultsEngineTest {

    @Mock private AgentClient agentClient;

    private SmartDefaultsEngine engine;

    @BeforeEach
    void setUp() {
        AgentDefaultsConfig config = new AgentDefaultsConfig();
        lenient().when(agentClient.getModelsInfo()).thenReturn(Map.of(
                "defaultProvider", "openai",
                "defaultModel", "gpt-5-mini"
        ));
        engine = new SmartDefaultsEngine(config, agentClient);
    }

    @Nested
    @DisplayName("applyAgentDefaults")
    class AgentDefaultsTests {

        @Test
        @DisplayName("Should default provider from catalog")
        void shouldDefaultProviderFromCatalog() {
            Map<String, Object> agent = new HashMap<>();
            agent.put("task", "Hello world");

            engine.applyAgentDefaults(agent);

            assertThat(agent.get("provider")).isEqualTo("openai");
        }

        @Test
        @DisplayName("Should not override existing provider")
        void shouldNotOverrideExistingProvider() {
            Map<String, Object> agent = new HashMap<>();
            agent.put("provider", "anthropic");

            engine.applyAgentDefaults(agent);

            assertThat(agent.get("provider")).isEqualTo("anthropic");
        }

        @Test
        @DisplayName("Should default model from catalog")
        void shouldDefaultModelFromCatalog() {
            Map<String, Object> agent = new HashMap<>();
            agent.put("task", "Say hello");

            engine.applyAgentDefaults(agent);

            assertThat(agent.get("model")).isEqualTo("gpt-5-mini");
        }

        @Test
        @DisplayName("Should rename task to prompt")
        void shouldRenameTaskToPrompt() {
            Map<String, Object> agent = new HashMap<>();
            agent.put("task", "Analyze this");

            engine.applyAgentDefaults(agent);

            assertThat(agent).doesNotContainKey("task");
            assertThat(agent.get("prompt")).isEqualTo("Analyze this");
        }

        @Test
        @DisplayName("Should not override existing prompt")
        void shouldNotOverrideExistingPrompt() {
            Map<String, Object> agent = new HashMap<>();
            agent.put("task", "from task");
            agent.put("prompt", "existing prompt");

            engine.applyAgentDefaults(agent);

            assertThat(agent.get("prompt")).isEqualTo("existing prompt");
        }

        @Test
        @DisplayName("Should not override existing model")
        void shouldNotOverrideExistingModel() {
            Map<String, Object> agent = new HashMap<>();
            agent.put("model", "custom-model");

            engine.applyAgentDefaults(agent);

            assertThat(agent.get("model")).isEqualTo("custom-model");
        }

        @Test
        @DisplayName("Should use catalog model when no task provided")
        void shouldUseCatalogModelWhenNoTask() {
            Map<String, Object> agent = new HashMap<>();

            engine.applyAgentDefaults(agent);

            assertThat(agent.get("model")).isEqualTo("gpt-5-mini");
        }

        @Test
        @DisplayName("Should use custom catalog values")
        void shouldUseCustomCatalogValues() {
            AgentClient customClient = org.mockito.Mockito.mock(AgentClient.class);
            when(customClient.getModelsInfo()).thenReturn(Map.of(
                    "defaultProvider", "anthropic",
                    "defaultModel", "claude-3-5-sonnet"
            ));
            SmartDefaultsEngine customEngine = new SmartDefaultsEngine(new AgentDefaultsConfig(), customClient);

            Map<String, Object> agent = new HashMap<>();
            customEngine.applyAgentDefaults(agent);

            assertThat(agent.get("provider")).isEqualTo("anthropic");
            assertThat(agent.get("model")).isEqualTo("claude-3-5-sonnet");
        }
    }

    @Nested
    @DisplayName("applyTriggerDefaults")
    class TriggerDefaultsTests {

        @Test
        @DisplayName("Should always set CRON strategy for schedule triggers")
        void shouldAlwaysSetCronStrategy() {
            Map<String, Object> trigger = new HashMap<>();
            trigger.put("type", "schedule");

            engine.applyTriggerDefaults(trigger);

            assertThat(trigger.get("strategy")).isEqualTo("CRON");
        }

        @Test
        @DisplayName("Should not apply strategy for non-schedule triggers")
        void shouldNotApplyForNonSchedule() {
            Map<String, Object> trigger = new HashMap<>();
            trigger.put("type", "webhook");

            engine.applyTriggerDefaults(trigger);

            assertThat(trigger).doesNotContainKey("strategy");
        }

        @Test
        @DisplayName("Should not override existing strategy")
        void shouldNotOverrideExistingStrategy() {
            Map<String, Object> trigger = new HashMap<>();
            trigger.put("type", "schedule");
            trigger.put("strategy", "CRON");

            engine.applyTriggerDefaults(trigger);

            assertThat(trigger.get("strategy")).isEqualTo("CRON");
        }
    }

    @Nested
    @DisplayName("applyStepDefaults")
    class StepDefaultsTests {

        @Test
        @DisplayName("Should return step unchanged")
        void shouldReturnStepUnchanged() {
            Map<String, Object> step = new HashMap<>();
            step.put("key", "value");

            Map<String, Object> result = engine.applyStepDefaults(step);

            assertThat(result).isSameAs(step);
            assertThat(result).containsEntry("key", "value");
        }
    }

    @Nested
    @DisplayName("applyDecisionDefaults")
    class DecisionDefaultsTests {

        @Test
        @DisplayName("Should return decision unchanged")
        void shouldReturnDecisionUnchanged() {
            Map<String, Object> decision = new HashMap<>();
            decision.put("label", "Check");

            Map<String, Object> result = engine.applyDecisionDefaults(decision);

            assertThat(result).isSameAs(decision);
        }
    }
}
