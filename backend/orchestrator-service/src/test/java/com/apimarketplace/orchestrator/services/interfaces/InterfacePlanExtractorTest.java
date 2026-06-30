package com.apimarketplace.orchestrator.services.interfaces;

import com.apimarketplace.orchestrator.domain.workflow.WorkflowPlan;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link InterfacePlanExtractor}.
 * Tests interface ID extraction, variable mapping, and action mapping from workflow plans.
 */
class InterfacePlanExtractorTest {

    private InterfacePlanExtractor extractor;

    @BeforeEach
    void setUp() {
        extractor = new InterfacePlanExtractor();
    }

    private WorkflowPlan planWithOriginal(Map<String, Object> originalPlan) {
        return new WorkflowPlan("test-id", "test-tenant",
                List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(), originalPlan);
    }

    @Nested
    @DisplayName("extractInterfaceIds")
    class ExtractInterfaceIds {

        @Test
        @DisplayName("should extract interface IDs from interfaces array (Strategy 1)")
        void shouldExtractFromInterfacesArray() {
            UUID id1 = UUID.randomUUID();
            UUID id2 = UUID.randomUUID();
            Map<String, Object> plan = Map.of("interfaces", List.of(
                    Map.of("id", id1.toString(), "label", "My Page"),
                    Map.of("id", id2.toString(), "label", "Other Page")
            ));

            Set<UUID> result = extractor.extractInterfaceIds(planWithOriginal(plan));

            assertThat(result).containsExactlyInAnyOrder(id1, id2);
        }

        @Test
        @DisplayName("should extract interface IDs from edges with interface: prefix (Strategy 2)")
        void shouldExtractFromEdges() {
            UUID id1 = UUID.randomUUID();
            Map<String, Object> plan = Map.of(
                    "interfaces", List.of(
                            Map.of("id", id1.toString(), "label", "My Page")
                    ),
                    "edges", List.of(
                            Map.of("from", "trigger:start", "to", "interface:my_page")
                    )
            );

            Set<UUID> result = extractor.extractInterfaceIds(planWithOriginal(plan));

            assertThat(result).contains(id1);
        }

        @Test
        @DisplayName("should parse UUID directly from edge ref when label not in map")
        void shouldParseUuidFromEdgeRef() {
            UUID directId = UUID.randomUUID();
            Map<String, Object> plan = Map.of(
                    "interfaces", List.of(),
                    "edges", List.of(
                            Map.of("from", "trigger:start", "to", "interface:" + directId)
                    )
            );

            Set<UUID> result = extractor.extractInterfaceIds(planWithOriginal(plan));

            assertThat(result).contains(directId);
        }

        @Test
        @DisplayName("should extract from triggers[].interfaceIds (Strategy 3 - deprecated)")
        void shouldExtractFromTriggerInterfaceIds() {
            UUID id1 = UUID.randomUUID();
            Map<String, Object> plan = Map.of("triggers", List.of(
                    Map.of("interfaceIds", List.of(id1.toString()))
            ));

            Set<UUID> result = extractor.extractInterfaceIds(planWithOriginal(plan));

            assertThat(result).contains(id1);
        }

        @Test
        @DisplayName("should extract from mcps[].interfaceIds (Strategy 4 - deprecated)")
        void shouldExtractFromMcpInterfaceIds() {
            UUID id1 = UUID.randomUUID();
            Map<String, Object> plan = Map.of("mcps", List.of(
                    Map.of("interfaceIds", List.of(id1.toString()))
            ));

            Set<UUID> result = extractor.extractInterfaceIds(planWithOriginal(plan));

            assertThat(result).contains(id1);
        }

        @Test
        @DisplayName("should deduplicate IDs across strategies")
        void shouldDeduplicateIds() {
            UUID id1 = UUID.randomUUID();
            Map<String, Object> plan = Map.of(
                    "interfaces", List.of(Map.of("id", id1.toString(), "label", "Page")),
                    "triggers", List.of(Map.of("interfaceIds", List.of(id1.toString()))),
                    "mcps", List.of(Map.of("interfaceIds", List.of(id1.toString())))
            );

            Set<UUID> result = extractor.extractInterfaceIds(planWithOriginal(plan));

            assertThat(result).hasSize(1);
            assertThat(result).contains(id1);
        }

        @Test
        @DisplayName("should return empty set for null original plan")
        void shouldReturnEmptyForNullPlan() {
            Set<UUID> result = extractor.extractInterfaceIds(planWithOriginal(null));
            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("should return empty set for plan without interfaces")
        void shouldReturnEmptyForPlanWithoutInterfaces() {
            Set<UUID> result = extractor.extractInterfaceIds(planWithOriginal(Map.of()));
            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("should skip invalid UUIDs")
        void shouldSkipInvalidUuids() {
            Map<String, Object> plan = Map.of("interfaces", List.of(
                    Map.of("id", "not-a-uuid", "label", "Bad"),
                    Map.of("id", "", "label", "Empty")
            ));

            Set<UUID> result = extractor.extractInterfaceIds(planWithOriginal(plan));

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("should skip interface entries without id")
        void shouldSkipEntriesWithoutId() {
            Map<String, Object> plan = Map.of("interfaces", List.of(
                    Map.of("label", "No ID")
            ));

            Set<UUID> result = extractor.extractInterfaceIds(planWithOriginal(plan));

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("should ignore non-interface edges")
        void shouldIgnoreNonInterfaceEdges() {
            Map<String, Object> plan = Map.of(
                    "interfaces", List.of(),
                    "edges", List.of(
                            Map.of("from", "trigger:start", "to", "mcp:api_call"),
                            Map.of("from", "mcp:api_call", "to", "core:decision:if")
                    )
            );

            Set<UUID> result = extractor.extractInterfaceIds(planWithOriginal(plan));

            assertThat(result).isEmpty();
        }
    }

    @Nested
    @DisplayName("extractMappingsFromPlan")
    class ExtractMappings {

        @Test
        @DisplayName("should extract variable mappings")
        void shouldExtractVariableMappings() {
            UUID id1 = UUID.randomUUID();
            Map<String, Object> plan = Map.of("interfaces", List.of(
                    Map.of("id", id1.toString(),
                            "variableMapping", Map.of("title", "{{mcp:fetch:title}}", "count", "42"))
            ));

            Map<UUID, Map<String, String>> result = extractor.extractMappingsFromPlan(planWithOriginal(plan));

            assertThat(result).containsKey(id1);
            assertThat(result.get(id1)).containsEntry("title", "{{mcp:fetch:title}}");
            assertThat(result.get(id1)).containsEntry("count", "42");
        }

        @Test
        @DisplayName("should return empty map for plan without interfaces")
        void shouldReturnEmptyForNoInterfaces() {
            Map<UUID, Map<String, String>> result = extractor.extractMappingsFromPlan(planWithOriginal(Map.of()));
            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("should return empty map for null plan")
        void shouldReturnEmptyForNullPlan() {
            Map<UUID, Map<String, String>> result = extractor.extractMappingsFromPlan(planWithOriginal(null));
            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("should skip interfaces without variableMapping")
        void shouldSkipWithoutMapping() {
            UUID id1 = UUID.randomUUID();
            Map<String, Object> plan = Map.of("interfaces", List.of(
                    Map.of("id", id1.toString(), "label", "No Mapping")
            ));

            Map<UUID, Map<String, String>> result = extractor.extractMappingsFromPlan(planWithOriginal(plan));

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("should skip interfaces with invalid UUID")
        void shouldSkipInvalidUuid() {
            Map<String, Object> plan = Map.of("interfaces", List.of(
                    Map.of("id", "bad-uuid", "variableMapping", Map.of("key", "value"))
            ));

            Map<UUID, Map<String, String>> result = extractor.extractMappingsFromPlan(planWithOriginal(plan));

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("should handle multiple interfaces")
        void shouldHandleMultipleInterfaces() {
            UUID id1 = UUID.randomUUID();
            UUID id2 = UUID.randomUUID();
            Map<String, Object> plan = Map.of("interfaces", List.of(
                    Map.of("id", id1.toString(), "variableMapping", Map.of("a", "1")),
                    Map.of("id", id2.toString(), "variableMapping", Map.of("b", "2"))
            ));

            Map<UUID, Map<String, String>> result = extractor.extractMappingsFromPlan(planWithOriginal(plan));

            assertThat(result).hasSize(2);
            assertThat(result.get(id1)).containsEntry("a", "1");
            assertThat(result.get(id2)).containsEntry("b", "2");
        }
    }

    @Nested
    @DisplayName("extractActionMappingsFromPlan")
    class ExtractActionMappings {

        @Test
        @DisplayName("should extract action mappings")
        void shouldExtractActionMappings() {
            UUID id1 = UUID.randomUUID();
            Map<String, Object> plan = Map.of("interfaces", List.of(
                    Map.of("id", id1.toString(),
                            "actionMapping", Map.of("#submitBtn", "submit_action", "#cancelBtn", "cancel_action"))
            ));

            Map<UUID, Map<String, String>> result = extractor.extractActionMappingsFromPlan(planWithOriginal(plan));

            assertThat(result).containsKey(id1);
            assertThat(result.get(id1)).containsEntry("#submitBtn", "submit_action");
            assertThat(result.get(id1)).containsEntry("#cancelBtn", "cancel_action");
        }

        @Test
        @DisplayName("should strip single quotes from action mapping keys")
        void shouldStripSingleQuotes() {
            UUID id1 = UUID.randomUUID();
            Map<String, Object> plan = Map.of("interfaces", List.of(
                    Map.of("id", id1.toString(),
                            "actionMapping", Map.of("'#myButton'", "click_action"))
            ));

            Map<UUID, Map<String, String>> result = extractor.extractActionMappingsFromPlan(planWithOriginal(plan));

            assertThat(result.get(id1)).containsEntry("#myButton", "click_action");
        }

        @Test
        @DisplayName("should strip double quotes from action mapping keys")
        void shouldStripDoubleQuotes() {
            UUID id1 = UUID.randomUUID();
            Map<String, Object> plan = Map.of("interfaces", List.of(
                    Map.of("id", id1.toString(),
                            "actionMapping", Map.of("\"#myButton\"", "click_action"))
            ));

            Map<UUID, Map<String, String>> result = extractor.extractActionMappingsFromPlan(planWithOriginal(plan));

            assertThat(result.get(id1)).containsEntry("#myButton", "click_action");
        }

        @Test
        @DisplayName("should not strip mismatched quotes")
        void shouldNotStripMismatchedQuotes() {
            UUID id1 = UUID.randomUUID();
            Map<String, Object> plan = Map.of("interfaces", List.of(
                    Map.of("id", id1.toString(),
                            "actionMapping", Map.of("'#mixed\"", "action"))
            ));

            Map<UUID, Map<String, String>> result = extractor.extractActionMappingsFromPlan(planWithOriginal(plan));

            assertThat(result.get(id1)).containsEntry("'#mixed\"", "action");
        }

        @Test
        @DisplayName("should return empty map for plan without interfaces")
        void shouldReturnEmptyForNoInterfaces() {
            Map<UUID, Map<String, String>> result = extractor.extractActionMappingsFromPlan(planWithOriginal(Map.of()));
            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("should return empty map for null plan")
        void shouldReturnEmptyForNullPlan() {
            Map<UUID, Map<String, String>> result = extractor.extractActionMappingsFromPlan(planWithOriginal(null));
            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("should skip interfaces without actionMapping")
        void shouldSkipWithoutActionMapping() {
            UUID id1 = UUID.randomUUID();
            Map<String, Object> plan = Map.of("interfaces", List.of(
                    Map.of("id", id1.toString(), "label", "No Actions")
            ));

            Map<UUID, Map<String, String>> result = extractor.extractActionMappingsFromPlan(planWithOriginal(plan));

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("should not strip quotes from short keys")
        void shouldNotStripShortKeys() {
            UUID id1 = UUID.randomUUID();
            Map<String, Object> plan = Map.of("interfaces", List.of(
                    Map.of("id", id1.toString(),
                            "actionMapping", Map.of("x", "action"))
            ));

            Map<UUID, Map<String, String>> result = extractor.extractActionMappingsFromPlan(planWithOriginal(plan));

            assertThat(result.get(id1)).containsEntry("x", "action");
        }
    }
}
