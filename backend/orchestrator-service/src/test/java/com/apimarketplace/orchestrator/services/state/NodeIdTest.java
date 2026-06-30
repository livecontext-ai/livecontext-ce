package com.apimarketplace.orchestrator.services.state;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("NodeId")
class NodeIdTest {

    @Nested
    @DisplayName("Canonical constructor validation")
    class ConstructorValidationTests {

        @Test
        @DisplayName("Should create with valid type and label")
        void shouldCreateWithValidTypeAndLabel() {
            NodeId id = new NodeId("mcp", "my_step");
            assertEquals("mcp", id.type());
            assertEquals("my_step", id.label());
        }

        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = {"  ", "\t"})
        @DisplayName("Should reject null or blank type")
        void shouldRejectNullOrBlankType(String type) {
            assertThrows(IllegalArgumentException.class, () -> new NodeId(type, "label"));
        }

        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = {"  ", "\t"})
        @DisplayName("Should reject null or blank label")
        void shouldRejectNullOrBlankLabel(String label) {
            assertThrows(IllegalArgumentException.class, () -> new NodeId("mcp", label));
        }
    }

    @Nested
    @DisplayName("Factory methods")
    class FactoryMethodTests {

        @Test
        @DisplayName("trigger() should create with trigger type")
        void triggerShouldCreateCorrectType() {
            NodeId id = NodeId.trigger("My Webhook");
            assertEquals("trigger", id.type());
            assertEquals("my_webhook", id.label());
        }

        @Test
        @DisplayName("step() should create with mcp type")
        void stepShouldCreateCorrectType() {
            NodeId id = NodeId.step("API Call");
            assertEquals("mcp", id.type());
            assertEquals("api_call", id.label());
        }

        @Test
        @DisplayName("loop() should create with core type")
        void loopShouldCreateCorrectType() {
            NodeId id = NodeId.loop("For Each");
            assertEquals("core", id.type());
            assertEquals("for_each", id.label());
        }

        @Test
        @DisplayName("split() should create with core type")
        void splitShouldCreateCorrectType() {
            NodeId id = NodeId.split("Process Items");
            assertEquals("core", id.type());
            assertEquals("process_items", id.label());
        }

        @Test
        @DisplayName("decision() should create with core type")
        void decisionShouldCreateCorrectType() {
            NodeId id = NodeId.decision("Check Value");
            assertEquals("core", id.type());
            assertEquals("check_value", id.label());
        }

        @Test
        @DisplayName("agent() should create with agent type")
        void agentShouldCreateCorrectType() {
            NodeId id = NodeId.agent("My Assistant");
            assertEquals("agent", id.type());
            assertEquals("my_assistant", id.label());
        }

        @Test
        @DisplayName("Factory methods should throw for null labels")
        void shouldThrowForNullLabels() {
            assertThrows(IllegalArgumentException.class, () -> NodeId.step(null));
            assertThrows(IllegalArgumentException.class, () -> NodeId.trigger(null));
            assertThrows(IllegalArgumentException.class, () -> NodeId.agent(null));
        }
    }

    @Nested
    @DisplayName("parse()")
    class ParseTests {

        @ParameterizedTest(name = "parse(\"{0}\") should produce type=\"{1}\", label=\"{2}\"")
        @CsvSource({
            "mcp:my_step, mcp, my_step",
            "trigger:webhook, trigger, webhook",
            "core:for_each, core, for_each",
            "agent:assistant, agent, assistant"
        })
        @DisplayName("Should parse prefixed strings correctly")
        void shouldParsePrefixedStrings(String raw, String expectedType, String expectedLabel) {
            NodeId id = NodeId.parse(raw);
            assertEquals(expectedType, id.type());
            assertEquals(expectedLabel, id.label());
        }

        @Test
        @DisplayName("Should default to step type for non-prefixed strings")
        void shouldDefaultToStepType() {
            NodeId id = NodeId.parse("my_step");
            assertEquals("mcp", id.type());
        }

        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = {"  "})
        @DisplayName("Should throw for null or blank input")
        void shouldThrowForNullOrBlank(String raw) {
            assertThrows(IllegalArgumentException.class, () -> NodeId.parse(raw));
        }
    }

    @Nested
    @DisplayName("tryParse()")
    class TryParseTests {

        @Test
        @DisplayName("Should return NodeId for valid input")
        void shouldReturnForValid() {
            NodeId id = NodeId.tryParse("mcp:step1");
            assertNotNull(id);
            assertEquals("mcp", id.type());
        }

        @Test
        @DisplayName("Should return null for null input")
        void shouldReturnNullForNull() {
            assertNull(NodeId.tryParse(null));
        }

        @Test
        @DisplayName("Should return null for blank input")
        void shouldReturnNullForBlank() {
            assertNull(NodeId.tryParse(""));
        }
    }

    @Nested
    @DisplayName("parseWithFallback()")
    class ParseWithFallbackTests {

        @Test
        @DisplayName("Should use fallback type for non-prefixed input")
        void shouldUseFallbackType() {
            NodeId id = NodeId.parseWithFallback("my_label", "trigger");
            assertEquals("trigger", id.type());
        }

        @Test
        @DisplayName("Should use actual prefix when present")
        void shouldUseActualPrefix() {
            NodeId id = NodeId.parseWithFallback("mcp:my_step", "trigger");
            assertEquals("mcp", id.type());
        }
    }

    @Nested
    @DisplayName("toKey()")
    class ToKeyTests {

        @Test
        @DisplayName("Should return type:label format")
        void shouldReturnCorrectFormat() {
            NodeId id = new NodeId("mcp", "my_step");
            assertEquals("mcp:my_step", id.toKey());
        }
    }

    @Nested
    @DisplayName("Type check methods")
    class TypeCheckTests {

        @Test
        @DisplayName("isTrigger() should return true for trigger type")
        void isTriggerShouldReturnTrue() {
            assertTrue(NodeId.trigger("webhook").isTrigger());
            assertFalse(NodeId.step("step1").isTrigger());
        }

        @Test
        @DisplayName("isStep() should return true for mcp type")
        void isStepShouldReturnTrue() {
            assertTrue(NodeId.step("api_call").isStep());
            assertFalse(NodeId.trigger("webhook").isStep());
        }

        @Test
        @DisplayName("isLoop() should return true for core type")
        void isLoopShouldReturnTrue() {
            assertTrue(NodeId.loop("for_each").isLoop());
        }

        @Test
        @DisplayName("isAgent() should return true for agent type")
        void isAgentShouldReturnTrue() {
            assertTrue(NodeId.agent("assistant").isAgent());
            assertFalse(NodeId.step("step1").isAgent());
        }

        @Test
        @DisplayName("isExecutableNode() should return true for step and agent")
        void isExecutableNodeShouldWork() {
            assertTrue(NodeId.step("step1").isExecutableNode());
            assertTrue(NodeId.agent("agent1").isExecutableNode());
            assertFalse(NodeId.trigger("webhook").isExecutableNode());
        }

        @Test
        @DisplayName("isCore() should return true for loop, split, decision")
        void isCoreShouldWork() {
            assertTrue(NodeId.loop("loop1").isCore());
            assertTrue(NodeId.split("split1").isCore());
            assertTrue(NodeId.decision("check").isCore());
            assertFalse(NodeId.step("step1").isCore());
        }
    }

    @Nested
    @DisplayName("Equality and hashCode")
    class EqualityTests {

        @Test
        @DisplayName("Should be equal based on canonical key")
        void shouldBeEqualByKey() {
            NodeId a = new NodeId("mcp", "my_step");
            NodeId b = new NodeId("mcp", "my_step");

            assertEquals(a, b);
            assertEquals(a.hashCode(), b.hashCode());
        }

        @Test
        @DisplayName("Should not be equal for different types")
        void shouldNotBeEqualForDifferentTypes() {
            NodeId a = new NodeId("mcp", "my_step");
            NodeId b = new NodeId("trigger", "my_step");

            assertNotEquals(a, b);
        }

        @Test
        @DisplayName("toString() should return canonical key")
        void toStringShouldReturnKey() {
            NodeId id = new NodeId("mcp", "my_step");
            assertEquals("mcp:my_step", id.toString());
        }
    }

    @Nested
    @DisplayName("Type constants")
    class TypeConstantsTests {

        @Test
        @DisplayName("Should have correct type constants")
        void shouldHaveCorrectConstants() {
            assertEquals("trigger", NodeId.TYPE_TRIGGER);
            assertEquals("mcp", NodeId.TYPE_MCP);
            assertEquals("core", NodeId.TYPE_CORE);
            assertEquals("agent", NodeId.TYPE_AGENT);
        }

        @Test
        @DisplayName("Legacy constants should match new ones")
        void legacyConstantsShouldMatch() {
            assertEquals(NodeId.TYPE_MCP, NodeId.TYPE_STEP);
            assertEquals(NodeId.TYPE_CORE, NodeId.TYPE_LOOP);
            assertEquals(NodeId.TYPE_CORE, NodeId.TYPE_SPLIT);
            assertEquals(NodeId.TYPE_CORE, NodeId.TYPE_DECISION);
        }
    }
}
