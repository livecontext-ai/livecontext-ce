package com.apimarketplace.orchestrator.domain.workflow;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for Agent record.
 *
 * Agent represents an AI node in the workflow (agent, guardrail, classify).
 */
@DisplayName("Agent")
class AgentTest {

    @Nested
    @DisplayName("Constructor validation")
    class ConstructorValidationTests {

        @Test
        @DisplayName("Should create agent with all fields")
        void shouldCreateAgentWithAllFields() {
            Agent agent = new Agent(
                "a1", "agent", "Data Analyzer", null, null, "anthropic", "claude-3-sonnet",
                "You are a data analyst", "Analyze the data", 0.7, 4096, 10, 5,
                List.of("tool1", "tool2"), null, Map.of(), null, null, null, null,
                null);

            assertEquals("a1", agent.id());
            assertEquals("agent", agent.type());
            assertEquals("Data Analyzer", agent.label());
            assertEquals("anthropic", agent.provider());
            assertEquals("claude-3-sonnet", agent.model());
            assertEquals("You are a data analyst", agent.systemPrompt());
            assertEquals("Analyze the data", agent.prompt());
            assertEquals(0.7, agent.temperature());
            assertEquals(4096, agent.maxTokens());
            assertEquals(10, agent.maxIterations());
            assertEquals(5, agent.maxTools());
            assertEquals(List.of("tool1", "tool2"), agent.tools());
        }

        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = {"   ", "\t"})
        @DisplayName("Should throw for null or blank label")
        void shouldThrowForNullOrBlankLabel(String label) {
            assertThrows(IllegalArgumentException.class,
                () -> new Agent("a1", "agent", label, null, null, null, null,
                    null, null, null, null, null, null, null, null, null, null, null, null, null, null));
        }

        @Test
        @DisplayName("Should default type to 'agent'")
        void shouldDefaultTypeToAgent() {
            Agent agent = new Agent("a1", null, "Label", null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null, null, null);

            assertEquals("agent", agent.type());
        }

        @Test
        @DisplayName("Should normalize type to lowercase")
        void shouldNormalizeTypeToLowercase() {
            Agent agent = new Agent("a1", "AGENT", "Label", null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null, null, null);

            assertEquals("agent", agent.type());
        }

        @Test
        @DisplayName("Should leave provider null when unset (no training-data defaults)")
        void shouldLeaveProviderNullWhenUnset() {
            Agent agent = new Agent("a1", "agent", "Label", null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null, null, null);

            assertNull(agent.provider());
        }

        @Test
        @DisplayName("Should default temperature to 0.7")
        void shouldDefaultTemperatureTo07() {
            Agent agent = new Agent("a1", "agent", "Label", null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null, null, null);

            assertEquals(0.7, agent.temperature());
        }

        @Test
        @DisplayName("Should default maxTokens to 4096")
        void shouldDefaultMaxTokensTo4096() {
            Agent agent = new Agent("a1", "agent", "Label", null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null, null, null);

            assertEquals(4096, agent.maxTokens());
        }

        @Test
        @DisplayName("Should default maxIterations to 10")
        void shouldDefaultMaxIterationsTo10() {
            Agent agent = new Agent("a1", "agent", "Label", null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null, null, null);

            assertEquals(10, agent.maxIterations());
        }

        @Test
        @DisplayName("Should default maxTools to 5")
        void shouldDefaultMaxToolsTo5() {
            Agent agent = new Agent("a1", "agent", "Label", null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null, null, null);

            assertEquals(5, agent.maxTools());
        }

        @Test
        @DisplayName("Should filter null and empty tools")
        void shouldFilterNullAndEmptyTools() {
            Agent agent = new Agent("a1", "agent", "Label", null, null, null, null,
                null, null, null, null, null, null, List.of("tool1", "", "  ", "tool2"),
                null, null, null, null, null, null, null);

            assertEquals(List.of("tool1", "tool2"), agent.tools());
        }

        @Test
        @DisplayName("Should default tools to empty list")
        void shouldDefaultToolsToEmptyList() {
            Agent agent = new Agent("a1", "agent", "Label", null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null, null, null);

            assertNotNull(agent.tools());
            assertTrue(agent.tools().isEmpty());
        }

        @Test
        @DisplayName("Should make tools list unmodifiable")
        void shouldMakeToolsListUnmodifiable() {
            Agent agent = new Agent("a1", "agent", "Label", null, null, null, null,
                null, null, null, null, null, null, List.of("tool1"), null, null, null, null, null, null, null);

            assertThrows(UnsupportedOperationException.class,
                () -> agent.tools().add("new_tool"));
        }
    }

    @Nested
    @DisplayName("Agent types")
    class AgentTypesTests {

        @ParameterizedTest
        @ValueSource(strings = {"agent", "guardrail", "classify"})
        @DisplayName("Should accept valid agent types")
        void shouldAcceptValidAgentTypes(String type) {
            Agent agent = new Agent("a1", type, "Label", null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null, null, null);

            assertEquals(type, agent.type());
        }

        @Test
        @DisplayName("Should create guardrail agent")
        void shouldCreateGuardrailAgent() {
            Agent agent = new Agent("a1", "guardrail", "Content Filter", null, null, null, null,
                null, null, null, null, null, null, null, null, null,
                null, null, List.of(Map.of("rule", "no_pii")), "{{mcp:step.output}}", null);

            assertEquals("guardrail", agent.type());
            assertNotNull(agent.guardrailRules());
            assertEquals("{{mcp:step.output}}", agent.guardrailParams());
        }

        @Test
        @DisplayName("Should create classify agent")
        void shouldCreateClassifyAgent() {
            Agent agent = new Agent("a1", "classify", "Intent Classifier", null, null, null, null,
                null, null, null, null, null, null, null, null, null,
                List.of(Map.of("name", "greeting")), "{{trigger:chat.message}}", null, null, null);

            assertEquals("classify", agent.type());
            assertNotNull(agent.classifyCategories());
            assertEquals("{{trigger:chat.message}}", agent.classifyParams());
        }
    }

    @Nested
    @DisplayName("normalizedLabel()")
    class NormalizedLabelTests {

        @ParameterizedTest
        @CsvSource({
            "Data Analyzer, data_analyzer",
            "Content Filter, content_filter",
            "Intent-Classifier, intent_classifier"
        })
        @DisplayName("Should normalize label correctly")
        void shouldNormalizeLabelCorrectly(String label, String expected) {
            Agent agent = new Agent("a1", "agent", label, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null, null, null);

            assertEquals(expected, agent.normalizedLabel());
        }
    }

    @Nested
    @DisplayName("getNormalizedKey()")
    class GetNormalizedKeyTests {

        @ParameterizedTest
        @CsvSource({
            "Data Analyzer, agent:data_analyzer",
            "Content Filter, agent:content_filter",
            "My Agent, agent:my_agent"
        })
        @DisplayName("Should return key with agent: prefix")
        void shouldReturnKeyWithAgentPrefix(String label, String expectedKey) {
            Agent agent = new Agent("a1", "agent", label, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null, null, null);

            assertEquals(expectedKey, agent.getNormalizedKey());
        }
    }

    @Nested
    @DisplayName("withParams()")
    class WithParamsTests {

        @Test
        @DisplayName("Should create new agent with different params")
        void shouldCreateNewAgentWithDifferentParams() {
            Agent original = new Agent("a1", "agent", "Label", null, null, null, null,
                null, null, null, null, null, null, null, null, Map.of("key1", "value1"),
                null, null, null, null, null);
            Map<String, Object> newParams = Map.of("key2", "value2");

            Agent modified = original.withParams(newParams);

            assertNotSame(original, modified);
            assertEquals(Map.of("key1", "value1"), original.params());
            assertEquals(Map.of("key2", "value2"), modified.params());
            assertEquals(original.id(), modified.id());
            assertEquals(original.label(), modified.label());
        }
    }
}
