package com.apimarketplace.orchestrator.tools.workflow.help;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for ExamplesHelpProvider.
 */
@DisplayName("ExamplesHelpProvider")
class ExamplesHelpProviderTest {

    @Nested
    @DisplayName("getPlanHelp")
    class GetPlanHelpTests {

        @Test
        @DisplayName("Should return plan help with title and description")
        void shouldReturnPlanHelp() {
            Map<String, Object> help = ExamplesHelpProvider.getPlanHelp();

            assertThat(help).isNotNull();
            assertThat(help).containsKey("title");
            assertThat(help).containsKey("description");
            assertThat(help).containsKey("get_plan");
            assertThat(help).containsKey("set_plan");
            assertThat(help).containsKey("plan_structure");
            assertThat(help).containsKey("edge_ports");
        }

        @Test
        @DisplayName("Should have get_plan section with purpose and usage")
        void shouldHaveGetPlanSection() {
            Map<String, Object> help = ExamplesHelpProvider.getPlanHelp();

            @SuppressWarnings("unchecked")
            Map<String, Object> getPlan = (Map<String, Object>) help.get("get_plan");
            assertThat(getPlan).containsKey("purpose");
            assertThat(getPlan).containsKey("usage");
        }

        @Test
        @DisplayName("Should have set_plan section with purpose and validation")
        void shouldHaveSetPlanSection() {
            Map<String, Object> help = ExamplesHelpProvider.getPlanHelp();

            @SuppressWarnings("unchecked")
            Map<String, Object> setPlan = (Map<String, Object>) help.get("set_plan");
            assertThat(setPlan).containsKey("purpose");
            assertThat(setPlan).containsKey("validation");
        }
    }

    @Nested
    @DisplayName("getCompleteExamples")
    class GetCompleteExamplesTests {

        @Test
        @DisplayName("Should include multi-DAG example")
        void shouldIncludeMultiDagExample() {
            Map<String, Object> examples = ExamplesHelpProvider.getCompleteExamples();

            assertThat(examples).containsKey("examples");

            @SuppressWarnings("unchecked")
            Map<String, Object> exampleMap = (Map<String, Object>) examples.get("examples");
            assertThat(exampleMap).containsKey("8_multiDag");
        }

        @Test
        @DisplayName("Multi-DAG example should have multiple triggers in plan")
        void multiDagExampleHasMultipleTriggers() {
            Map<String, Object> examples = ExamplesHelpProvider.getCompleteExamples();

            @SuppressWarnings("unchecked")
            Map<String, Object> exampleMap = (Map<String, Object>) examples.get("examples");

            @SuppressWarnings("unchecked")
            Map<String, Object> multiDag = (Map<String, Object>) exampleMap.get("8_multiDag");

            assertThat(multiDag).containsKey("plan");
            assertThat(multiDag).containsKey("note");

            @SuppressWarnings("unchecked")
            Map<String, Object> plan = (Map<String, Object>) multiDag.get("plan");

            @SuppressWarnings("unchecked")
            List<?> triggers = (List<?>) plan.get("triggers");

            assertThat(triggers).hasSizeGreaterThanOrEqualTo(2);
        }

        @Test
        @DisplayName("Multi-DAG example edges should reference different triggers")
        void multiDagEdgesReferenceDifferentTriggers() {
            Map<String, Object> examples = ExamplesHelpProvider.getCompleteExamples();

            @SuppressWarnings("unchecked")
            Map<String, Object> exampleMap = (Map<String, Object>) examples.get("examples");

            @SuppressWarnings("unchecked")
            Map<String, Object> multiDag = (Map<String, Object>) exampleMap.get("8_multiDag");

            @SuppressWarnings("unchecked")
            Map<String, Object> plan = (Map<String, Object>) multiDag.get("plan");

            @SuppressWarnings("unchecked")
            List<Map<String, String>> edges = (List<Map<String, String>>) plan.get("edges");

            // Edges should reference at least 2 different trigger sources
            long distinctTriggerSources = edges.stream()
                .map(e -> e.get("from"))
                .filter(from -> from != null && from.startsWith("trigger:"))
                .distinct()
                .count();

            assertThat(distinctTriggerSources).isGreaterThanOrEqualTo(2);
        }
    }
}
