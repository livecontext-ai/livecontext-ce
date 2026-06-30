package com.apimarketplace.orchestrator.services.streaming.state;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("StateUtils")
class StateUtilsTest {

    // ==================== extractInteger ====================

    @Nested
    @DisplayName("extractInteger()")
    class ExtractIntegerTests {

        @Test
        @DisplayName("Should extract integer from first matching key")
        void shouldExtractIntegerFromFirstMatchingKey() {
            Map<String, Object> payload = Map.of("itemIndex", 5, "item_index", 10);
            Integer result = StateUtils.extractInteger(payload, "itemIndex", "item_index");
            assertEquals(5, result);
        }

        @Test
        @DisplayName("Should fall back to second key if first missing")
        void shouldFallBackToSecondKey() {
            Map<String, Object> payload = Map.of("item_index", 10);
            Integer result = StateUtils.extractInteger(payload, "itemIndex", "item_index");
            assertEquals(10, result);
        }

        @Test
        @DisplayName("Should return null when no key matches")
        void shouldReturnNullWhenNoKeyMatches() {
            Map<String, Object> payload = Map.of("other", "value");
            Integer result = StateUtils.extractInteger(payload, "itemIndex", "item_index");
            assertNull(result);
        }

        @Test
        @DisplayName("Should handle Long values")
        void shouldHandleLongValues() {
            Map<String, Object> payload = Map.of("index", 42L);
            Integer result = StateUtils.extractInteger(payload, "index");
            assertEquals(42, result);
        }

        @Test
        @DisplayName("Should handle Double values")
        void shouldHandleDoubleValues() {
            Map<String, Object> payload = Map.of("index", 3.7);
            Integer result = StateUtils.extractInteger(payload, "index");
            assertEquals(3, result);
        }
    }

    // ==================== extractStatus ====================

    @Nested
    @DisplayName("extractStatus()")
    class ExtractStatusTests {

        @Test
        @DisplayName("Should prefer backendStatus over status")
        void shouldPreferBackendStatus() {
            Map<String, Object> payload = Map.of("status", "running", "backendStatus", "completed");
            assertEquals("completed", StateUtils.extractStatus(payload));
        }

        @Test
        @DisplayName("Should fall back to status when backendStatus is empty")
        void shouldFallBackToStatus() {
            Map<String, Object> payload = Map.of("status", "running", "backendStatus", "");
            assertEquals("running", StateUtils.extractStatus(payload));
        }

        @Test
        @DisplayName("Should return empty string when both missing")
        void shouldReturnEmptyStringWhenBothMissing() {
            Map<String, Object> payload = Map.of();
            assertEquals("", StateUtils.extractStatus(payload));
        }
    }

    // ==================== stripPrefix ====================

    @Nested
    @DisplayName("stripPrefix()")
    class StripPrefixTests {

        @Test
        @DisplayName("Should strip mcp prefix")
        void shouldStripMcpPrefix() {
            assertEquals("my_step", StateUtils.stripPrefix("mcp:my_step"));
        }

        @Test
        @DisplayName("Should strip core prefix with port")
        void shouldStripCorePrefixWithPort() {
            assertEquals("if", StateUtils.stripPrefix("core:decision:if"));
        }

        @Test
        @DisplayName("Should return value without prefix unchanged")
        void shouldReturnValueWithoutPrefixUnchanged() {
            assertEquals("my_step", StateUtils.stripPrefix("my_step"));
        }

        @Test
        @DisplayName("Should return null for null input")
        void shouldReturnNullForNull() {
            assertNull(StateUtils.stripPrefix(null));
        }
    }

    // ==================== extractNodeLabel ====================

    @Nested
    @DisplayName("extractNodeLabel()")
    class ExtractNodeLabelTests {

        @Test
        @DisplayName("Should extract label from prefixed step ID")
        void shouldExtractLabel() {
            assertEquals("my_step", StateUtils.extractNodeLabel("mcp:my_step"));
        }
    }

    // ==================== cloneValue ====================

    @Nested
    @DisplayName("cloneValue()")
    class CloneValueTests {

        @Test
        @DisplayName("Should shallow-copy Map")
        void shouldShallowCopyMap() {
            Map<String, Object> original = new LinkedHashMap<>();
            original.put("key", "value");
            Object cloned = StateUtils.cloneValue(original);

            assertInstanceOf(Map.class, cloned);
            assertNotSame(original, cloned);
            assertEquals("value", ((Map<?, ?>) cloned).get("key"));
        }

        @Test
        @DisplayName("Should copy List")
        void shouldCopyList() {
            List<String> original = List.of("a", "b");
            Object cloned = StateUtils.cloneValue(original);

            assertInstanceOf(List.class, cloned);
            assertEquals(original, cloned);
        }

        @Test
        @DisplayName("Should return scalar values unchanged")
        void shouldReturnScalarUnchanged() {
            assertEquals("hello", StateUtils.cloneValue("hello"));
            assertEquals(42, StateUtils.cloneValue(42));
            assertEquals(true, StateUtils.cloneValue(true));
        }

        @Test
        @DisplayName("Should skip null keys and values in Map")
        void shouldSkipNullKeysAndValues() {
            Map<Object, Object> original = new LinkedHashMap<>();
            original.put("good", "value");
            original.put(null, "skipped");
            original.put("alsoGood", null);

            @SuppressWarnings("unchecked")
            Map<String, Object> cloned = (Map<String, Object>) StateUtils.cloneValue(original);

            assertEquals(1, cloned.size());
            assertEquals("value", cloned.get("good"));
        }
    }

    // ==================== stripItemScope ====================

    @Nested
    @DisplayName("stripItemScope()")
    class StripItemScopeTests {

        @Test
        @DisplayName("Should strip #item-N suffix")
        void shouldStripItemSuffix() {
            assertEquals("mcp:step1", StateUtils.stripItemScope("mcp:step1#item-3"));
        }

        @Test
        @DisplayName("Should return value without suffix unchanged")
        void shouldReturnValueWithoutSuffix() {
            assertEquals("mcp:step1", StateUtils.stripItemScope("mcp:step1"));
        }

        @Test
        @DisplayName("Should return null for null input")
        void shouldReturnNullForNull() {
            assertNull(StateUtils.stripItemScope(null));
        }

        @Test
        @DisplayName("Should return null for empty string")
        void shouldReturnNullForEmptyString() {
            assertNull(StateUtils.stripItemScope(""));
        }

        @Test
        @DisplayName("Should return null for whitespace-only string")
        void shouldReturnNullForWhitespace() {
            assertNull(StateUtils.stripItemScope("   "));
        }
    }

    // ==================== hasEdgeActivity ====================

    @Nested
    @DisplayName("hasEdgeActivity()")
    class HasEdgeActivityTests {

        @Test
        @DisplayName("Should return true when running > 0")
        void shouldReturnTrueWhenRunning() {
            Map<String, Object> payload = Map.of("running", 1, "completed", 0, "skipped", 0);
            assertTrue(StateUtils.hasEdgeActivity(payload));
        }

        @Test
        @DisplayName("Should return true when completed > 0")
        void shouldReturnTrueWhenCompleted() {
            Map<String, Object> payload = Map.of("running", 0, "completed", 5, "skipped", 0);
            assertTrue(StateUtils.hasEdgeActivity(payload));
        }

        @Test
        @DisplayName("Should return true when skipped > 0")
        void shouldReturnTrueWhenSkipped() {
            Map<String, Object> payload = Map.of("running", 0, "completed", 0, "skipped", 2);
            assertTrue(StateUtils.hasEdgeActivity(payload));
        }

        @Test
        @DisplayName("Should return false when all zero")
        void shouldReturnFalseWhenAllZero() {
            Map<String, Object> payload = Map.of("running", 0, "completed", 0, "skipped", 0);
            assertFalse(StateUtils.hasEdgeActivity(payload));
        }

        @Test
        @DisplayName("Should return false for null payload")
        void shouldReturnFalseForNull() {
            assertFalse(StateUtils.hasEdgeActivity(null));
        }

        @Test
        @DisplayName("Should return false for empty payload")
        void shouldReturnFalseForEmpty() {
            assertFalse(StateUtils.hasEdgeActivity(Map.of()));
        }
    }

    // ==================== toLong ====================

    @Nested
    @DisplayName("toLong()")
    class ToLongTests {

        @Test
        @DisplayName("Should convert Number to long")
        void shouldConvertNumber() {
            assertEquals(42L, StateUtils.toLong(42));
            assertEquals(42L, StateUtils.toLong(42L));
            assertEquals(42L, StateUtils.toLong(42.9));
        }

        @Test
        @DisplayName("Should convert String to long")
        void shouldConvertString() {
            assertEquals(42L, StateUtils.toLong("42"));
        }

        @Test
        @DisplayName("Should return 0 for invalid String")
        void shouldReturnZeroForInvalidString() {
            assertEquals(0L, StateUtils.toLong("abc"));
        }

        @Test
        @DisplayName("Should return 0 for null")
        void shouldReturnZeroForNull() {
            assertEquals(0L, StateUtils.toLong(null));
        }

        @Test
        @DisplayName("Should return 0 for unsupported type")
        void shouldReturnZeroForUnsupported() {
            assertEquals(0L, StateUtils.toLong(List.of()));
        }
    }

    // ==================== isVirtualLoopNodeId ====================

    @Nested
    @DisplayName("isVirtualLoopNodeId()")
    class IsVirtualLoopNodeIdTests {

        @Test
        @DisplayName("Should detect ::controller format")
        void shouldDetectDoubleColonController() {
            assertTrue(StateUtils.isVirtualLoopNodeId("core:while::controller"));
        }

        @Test
        @DisplayName("Should detect controller suffix without ::")
        void shouldDetectControllerSuffix() {
            assertTrue(StateUtils.isVirtualLoopNodeId("core:whilecontroller"));
        }

        @Test
        @DisplayName("Should detect ::condition_checker format")
        void shouldDetectConditionChecker() {
            assertTrue(StateUtils.isVirtualLoopNodeId("core:while::condition_checker"));
        }

        @Test
        @DisplayName("Should detect condition_checker suffix")
        void shouldDetectConditionCheckerSuffix() {
            assertTrue(StateUtils.isVirtualLoopNodeId("core:whilecondition_checker"));
        }

        @Test
        @DisplayName("Should detect controller in edge IDs")
        void shouldDetectControllerInEdge() {
            assertTrue(StateUtils.isVirtualLoopNodeId("core:whilecontroller->mcp:step1"));
        }

        @Test
        @DisplayName("Should detect reverse edge with loop controller")
        void shouldDetectReverseEdge() {
            assertTrue(StateUtils.isVirtualLoopNodeId("mcp:step1->loop:whilecontroller"));
        }

        @Test
        @DisplayName("Should return false for regular node")
        void shouldReturnFalseForRegularNode() {
            assertFalse(StateUtils.isVirtualLoopNodeId("mcp:my_step"));
        }

        @ParameterizedTest
        @NullAndEmptySource
        @DisplayName("Should return false for null or empty")
        void shouldReturnFalseForNullOrEmpty(String input) {
            assertFalse(StateUtils.isVirtualLoopNodeId(input));
        }
    }

    // ==================== sanitizeStepPayload ====================

    @Nested
    @DisplayName("sanitizeStepPayload()")
    class SanitizeStepPayloadTests {

        @Test
        @DisplayName("Should keep only allowed fields")
        void shouldKeepOnlyAllowedFields() {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("runId", "run-1");
            payload.put("status", "completed");
            payload.put("heavyField", "should be removed");
            payload.put("timestamp", 100L);

            Map<String, Object> result = StateUtils.sanitizeStepPayload("mcp:step1", payload);

            assertTrue(result.containsKey("runId"));
            assertTrue(result.containsKey("status"));
            assertTrue(result.containsKey("timestamp"));
            assertFalse(result.containsKey("heavyField"));
        }

        @Test
        @DisplayName("Should return empty map for null payload")
        void shouldReturnEmptyMapForNull() {
            Map<String, Object> result = StateUtils.sanitizeStepPayload("mcp:step1", null);
            assertTrue(result.isEmpty());
        }

        @Test
        @DisplayName("Should return empty map for empty payload")
        void shouldReturnEmptyMapForEmpty() {
            Map<String, Object> result = StateUtils.sanitizeStepPayload("mcp:step1", Map.of());
            assertTrue(result.isEmpty());
        }

        @Test
        @DisplayName("Should set id and normalizedStepId via sanitizeIdentifiers")
        void shouldSetIdentifiers() {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("normalizedStepId", "mcp:step1");
            payload.put("runId", "run-1");

            Map<String, Object> result = StateUtils.sanitizeStepPayload("mcp:step1", payload);

            assertEquals("mcp:step1", result.get("normalizedStepId"));
            assertTrue(result.containsKey("id"));
        }

        @Test
        @DisplayName("Should preserve browser-agent live-view fields through the sanitize pass")
        void shouldPreserveBrowserAgentFields() {
            // Regression: M6 audit caught that the 6 browser fields added
            // to StepEventBuilder#appendItemMetadata were stripped by the
            // sanitize pass because they weren't in STEP_FIELDS_TO_KEEP.
            // Without this allowlist entry the live-view URL/token never
            // reach the frontend snapshot - the panel stays on its
            // "Waiting…" fallback even though the backend submitted them.
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("runId", "run-1");
            payload.put("session_id", "ses_abc");
            payload.put("cdp_token", "eyJfake.signed.jwt");
            payload.put("cdp_ws_url", "wss://websearch-host.example/cdp/ses_abc");
            payload.put("step_index", 7);
            payload.put("last_action", "click #login");
            payload.put("cost_usd", 0.043);
            payload.put("run_id", "run-1");
            payload.put("node_id", "agent:browser_agent");
            payload.put("heavy_field_to_strip", "noise");

            Map<String, Object> result = StateUtils.sanitizeStepPayload(
                "agent:browser_agent", payload);

            // All 8 browser fields preserved.
            assertEquals("ses_abc", result.get("session_id"));
            assertEquals("eyJfake.signed.jwt", result.get("cdp_token"));
            assertEquals("wss://websearch-host.example/cdp/ses_abc", result.get("cdp_ws_url"));
            assertEquals(7, result.get("step_index"));
            assertEquals("click #login", result.get("last_action"));
            assertEquals(0.043, result.get("cost_usd"));
            assertEquals("run-1", result.get("run_id"));
            assertEquals("agent:browser_agent", result.get("node_id"));
            // Non-allowlisted heavy fields still stripped.
            assertFalse(result.containsKey("heavy_field_to_strip"));
        }
    }

    // ==================== sanitizeIdentifiers ====================

    @Nested
    @DisplayName("sanitizeIdentifiers()")
    class SanitizeIdentifiersTests {

        @Test
        @DisplayName("Should set id from normalizedStepId")
        void shouldSetIdFromNormalized() {
            Map<String, Object> target = new LinkedHashMap<>();
            target.put("normalizedStepId", "mcp:test");
            Map<String, Object> payload = Map.of();

            StateUtils.sanitizeIdentifiers(target, "mcp:test", payload);

            assertEquals("mcp:test", target.get("id"));
            assertEquals("mcp:test", target.get("normalizedStepId"));
        }

        @Test
        @DisplayName("Should fall back to stepAlias when normalized missing")
        void shouldFallBackToAlias() {
            Map<String, Object> target = new LinkedHashMap<>();
            Map<String, Object> payload = Map.of("stepAlias", "my_alias");

            StateUtils.sanitizeIdentifiers(target, "mcp:test", payload);

            assertEquals("my_alias", target.get("stepAlias"));
        }

        @Test
        @DisplayName("Should strip item scope from identifiers")
        void shouldStripItemScope() {
            Map<String, Object> target = new LinkedHashMap<>();
            target.put("normalizedStepId", "mcp:step#item-3");
            Map<String, Object> payload = Map.of();

            StateUtils.sanitizeIdentifiers(target, "mcp:step#item-3", payload);

            assertEquals("mcp:step", target.get("normalizedStepId"));
            assertEquals("mcp:step", target.get("id"));
        }
    }
}
