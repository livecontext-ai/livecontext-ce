package com.apimarketplace.orchestrator.tools.workflow.help;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for ConceptsHelpProvider to ensure variable syntax consistency.
 */
@DisplayName("ConceptsHelpProvider Tests")
class ConceptsHelpProviderTest {

    // ==================== Overview Tests ====================

    @Nested
    @DisplayName("Overview Structure")
    class OverviewStructure {

        @Test
        @DisplayName("Overview should contain all required sections")
        void overviewContainsRequiredSections() {
            Map<String, Object> overview = ConceptsHelpProvider.getOverview();

            assertThat(overview).containsKey("title");
            assertThat(overview).containsKey("description");
            assertThat(overview).containsKey("helpTopics");
            assertThat(overview).containsKey("nodeCategories");
            assertThat(overview).containsKey("quickStart");
        }

        @Test
        @DisplayName("Node categories should include all node types")
        void nodeCategoriesIncludeAllTypes() {
            Map<String, Object> overview = ConceptsHelpProvider.getOverview();

            @SuppressWarnings("unchecked")
            Map<String, String> categories = (Map<String, String>) overview.get("nodeCategories");

            assertThat(categories).containsKey("triggers");
            assertThat(categories).containsKey("mcps");
            assertThat(categories).containsKey("agents");
            assertThat(categories).containsKey("cores");
            assertThat(categories).containsKey("tables");
            assertThat(categories).containsKey("interfaces");
        }
    }

    // ==================== Concepts Help Tests ====================

    @Nested
    @DisplayName("Concepts Help Structure")
    class ConceptsHelpStructure {

        @Test
        @DisplayName("Concepts help should include data dependency warning")
        void conceptsIncludeDataDependencyWarning() {
            Map<String, Object> concepts = ConceptsHelpProvider.getConceptsHelp();

            assertThat(concepts).containsKey("0_DATA_DEPENDENCY");

            @SuppressWarnings("unchecked")
            Map<String, Object> dataDep = (Map<String, Object>) concepts.get("0_DATA_DEPENDENCY");
            assertThat(dataDep).isNotNull();
        }

        @Test
        @DisplayName("Concepts help should include quick reference")
        void conceptsIncludeQuickReference() {
            Map<String, Object> concepts = ConceptsHelpProvider.getConceptsHelp();

            assertThat(concepts).containsKey("QUICK_REFERENCE");
        }
    }

    // ==================== Variables Help Tests ====================

    @Nested
    @DisplayName("Variables Help Syntax")
    class VariablesHelpSyntax {

        @Test
        @DisplayName("Variables help should include all prefixes")
        void variablesHelpIncludesAllPrefixes() {
            Map<String, Object> variables = ConceptsHelpProvider.getVariablesHelp();

            assertThat(variables).containsKey("QUICK_REFERENCE");

            @SuppressWarnings("unchecked")
            Map<String, Object> quickRef = (Map<String, Object>) variables.get("QUICK_REFERENCE");

            assertThat(quickRef).containsKey("trigger");
            assertThat(quickRef).containsKey("mcp");
            assertThat(quickRef).containsKey("agent");
            assertThat(quickRef).containsKey("core");
        }

        @Test
        @DisplayName("Quick reference should mention UNIFIED .output. pattern")
        void quickReferenceShowsUnifiedPattern() {
            Map<String, Object> variables = ConceptsHelpProvider.getVariablesHelp();

            @SuppressWarnings("unchecked")
            Map<String, Object> quickRef = (Map<String, Object>) variables.get("QUICK_REFERENCE");

            String unifiedPattern = (String) quickRef.get("unified_pattern");

            assertThat(unifiedPattern).contains(".output.");
            assertThat(unifiedPattern).contains("{{type:label.output.field}}");
        }

        @Test
        @DisplayName("Trigger syntax should use UNIFIED .output. pattern")
        void triggerSyntaxUsesOutput() {
            Map<String, Object> variables = ConceptsHelpProvider.getVariablesHelp();

            @SuppressWarnings("unchecked")
            Map<String, Object> quickRef = (Map<String, Object>) variables.get("QUICK_REFERENCE");

            @SuppressWarnings("unchecked")
            Map<String, Object> triggerRef = (Map<String, Object>) quickRef.get("trigger");

            String syntax = (String) triggerRef.get("syntax");

            assertThat(syntax).contains(".output.");
            assertThat(syntax).contains("{{trigger:label.output.field}}");
        }

        @Test
        @DisplayName("Agent syntax should use UNIFIED .output. pattern")
        void agentSyntaxUsesOutput() {
            Map<String, Object> variables = ConceptsHelpProvider.getVariablesHelp();

            @SuppressWarnings("unchecked")
            Map<String, Object> quickRef = (Map<String, Object>) variables.get("QUICK_REFERENCE");

            @SuppressWarnings("unchecked")
            Map<String, Object> agentRef = (Map<String, Object>) quickRef.get("agent");

            String syntax = (String) agentRef.get("syntax");

            assertThat(syntax).contains(".output.");
            assertThat(syntax).contains("{{agent:label.output.field}}");
        }

        @Test
        @DisplayName("Core syntax should use UNIFIED .output. pattern")
        void coreSyntaxUsesOutput() {
            Map<String, Object> variables = ConceptsHelpProvider.getVariablesHelp();

            @SuppressWarnings("unchecked")
            Map<String, Object> quickRef = (Map<String, Object>) variables.get("QUICK_REFERENCE");

            @SuppressWarnings("unchecked")
            Map<String, Object> coreRef = (Map<String, Object>) quickRef.get("core");

            String syntax = (String) coreRef.get("syntax");

            assertThat(syntax).contains(".output.");
            assertThat(syntax).contains("{{core:label.output.field}}");
        }
    }

    // ==================== SpEL Help Tests ====================

    @Nested
    @DisplayName("SpEL Help Structure")
    class SpelHelpStructure {

        @Test
        @DisplayName("SpEL help should include basic syntax")
        void spelHelpIncludesBasicSyntax() {
            Map<String, Object> spel = ConceptsHelpProvider.getSpelHelp();

            assertThat(spel).containsKey("BASIC_SYNTAX");
        }

        @Test
        @DisplayName("SpEL help should include operators")
        void spelHelpIncludesOperators() {
            Map<String, Object> spel = ConceptsHelpProvider.getSpelHelp();

            assertThat(spel).containsKey("OPERATORS");

            @SuppressWarnings("unchecked")
            Map<String, Object> operators = (Map<String, Object>) spel.get("OPERATORS");

            assertThat(operators).containsKey("arithmetic");
            assertThat(operators).containsKey("comparison");
            assertThat(operators).containsKey("logical");
        }

        @Test
        @DisplayName("SpEL help should include functions")
        void spelHelpIncludesFunctions() {
            Map<String, Object> spel = ConceptsHelpProvider.getSpelHelp();

            assertThat(spel).containsKey("FUNCTIONS");

            @SuppressWarnings("unchecked")
            Map<String, Object> functions = (Map<String, Object>) spel.get("FUNCTIONS");

            assertThat(functions).containsKey("type_casting");
            assertThat(functions).containsKey("utility");
            assertThat(functions).containsKey("math");
            assertThat(functions).containsKey("string_manipulation");
            assertThat(functions).containsKey("string_checks");
            assertThat(functions).containsKey("date_and_format");
        }

        @Test
        @DisplayName("SpEL help should warn about the {{...}} expression-boundary trap")
        void spelHelpIncludesExpressionBoundaryTrap() {
            Map<String, Object> spel = ConceptsHelpProvider.getSpelHelp();

            assertThat(spel).containsKey("EXPRESSION_BOUNDARY_TRAP");

            @SuppressWarnings("unchecked")
            Map<String, Object> trap = (Map<String, Object>) spel.get("EXPRESSION_BOUNDARY_TRAP");

            assertThat(trap).containsKeys("rule", "wrap_the_whole_expression",
                    "switch_wrong", "switch_right", "decision_and_loop_exception", "validate_hint");
            // The corrected switch example must wrap the WHOLE ternary in one {{...}}.
            assertThat((String) trap.get("switch_right")).contains("{{n < 5");
            // The rule must spell out that the operators stay LITERAL (silent failure).
            assertThat((String) trap.get("rule")).containsIgnoringCase("literal");
            // The decision/loop exception must be documented so agents don't over-wrap conditions.
            assertThat((String) trap.get("decision_and_loop_exception")).contains("{{amount}} > 100");
            // The hint must be honest about what validate catches (comparison/ternary, not arithmetic).
            assertThat((String) trap.get("validate_hint")).contains("validate").contains("EXPRESSION_NOT_EVALUATED");
        }

        @Test
        @DisplayName("SpEL prefixes should all use UNIFIED .output. syntax")
        void spelPrefixesConsistent() {
            Map<String, Object> spel = ConceptsHelpProvider.getSpelHelp();

            @SuppressWarnings("unchecked")
            Map<String, Object> basicSyntax = (Map<String, Object>) spel.get("BASIC_SYNTAX");

            @SuppressWarnings("unchecked")
            Map<String, String> prefixes = (Map<String, String>) basicSyntax.get("prefixes");

            // ALL types should have .output. (unified syntax)
            assertThat(prefixes.get("mcp")).contains(".output.");
            assertThat(prefixes.get("trigger")).contains(".output.");
            assertThat(prefixes.get("agent")).contains(".output.");
            assertThat(prefixes.get("core_split_persisted")).contains(".output.");
            assertThat(prefixes.get("loop")).contains(".output.");
        }
    }

    // ==================== Example Consistency Tests ====================

    @Nested
    @DisplayName("Example Syntax Consistency")
    class ExampleSyntaxConsistency {

        @Test
        @DisplayName("Loop variable example should use core: prefix")
        void loopVariableUsesCorePrefix() {
            Map<String, Object> concepts = ConceptsHelpProvider.getConceptsHelp();

            @SuppressWarnings("unchecked")
            Map<String, Object> loopIteration = (Map<String, Object>) concepts.get("5_LOOP_ITERATION");

            String variables = (String) loopIteration.get("variables");

            // Should use core: prefix, not loop. or just {{iteration}}
            assertThat(variables).contains("{{core:");
        }

        @Test
        @DisplayName("Variable visibility should use UNIFIED .output. syntax for all types")
        void variableVisibilityUsesCorrectPrefixes() {
            Map<String, Object> concepts = ConceptsHelpProvider.getConceptsHelp();

            @SuppressWarnings("unchecked")
            Map<String, Object> visibility = (Map<String, Object>) concepts.get("6_VARIABLE_VISIBILITY");

            String syntax = (String) visibility.get("syntax");

            // Should use unified .output. syntax for all types
            assertThat(syntax).contains(".output.");
            assertThat(syntax).contains("{{type:label.output.field}}");
        }

        @Test
        @DisplayName("Quick reference should have correct syntax for all node types")
        void quickReferenceHasCorrectSyntax() {
            Map<String, Object> concepts = ConceptsHelpProvider.getConceptsHelp();

            @SuppressWarnings("unchecked")
            Map<String, String> quickRef = (Map<String, String>) concepts.get("QUICK_REFERENCE");

            assertThat(quickRef).isNotNull();
            assertThat(quickRef).containsKey("fork");
            assertThat(quickRef).containsKey("merge");
            assertThat(quickRef).containsKey("decision");
            assertThat(quickRef).containsKey("loop");
        }
    }

    // ==================== Multi-DAG Help Tests ====================

    @Nested
    @DisplayName("Multi-DAG Help")
    class MultiDagHelp {

        @Test
        @DisplayName("Multi-DAG help should contain all required sections")
        void multiDagHelpContainsRequiredSections() {
            Map<String, Object> help = ConceptsHelpProvider.getMultiDagHelp();

            assertThat(help).containsKey("title");
            assertThat(help).containsKey("description");
            assertThat(help).containsKey("CONCEPT");
            assertThat(help).containsKey("RULES");
            assertThat(help).containsKey("PATTERNS");
        }

        @Test
        @DisplayName("Multi-DAG concept should explain DAG independence")
        void conceptExplainsDagIndependence() {
            Map<String, Object> help = ConceptsHelpProvider.getMultiDagHelp();

            @SuppressWarnings("unchecked")
            Map<String, Object> concept = (Map<String, Object>) help.get("CONCEPT");

            assertThat(concept).containsKey("key_rule");
            assertThat(concept).containsKey("how_it_works");
            assertThat(concept).containsKey("when_to_use");
            assertThat(concept).containsKey("diagram");
        }

        @Test
        @DisplayName("Multi-DAG patterns should include same_dag_interactive and multi_channel")
        void patternsIncludeExpectedKeys() {
            Map<String, Object> help = ConceptsHelpProvider.getMultiDagHelp();

            @SuppressWarnings("unchecked")
            Map<String, Object> patterns = (Map<String, Object>) help.get("PATTERNS");

            assertThat(patterns).containsKey("same_dag_interactive");
            assertThat(patterns).containsKey("multi_channel");
        }

        @Test
        @DisplayName("Multi-DAG rules describe grouping + per-trigger epoch semantics")
        void rulesIncludeDagGrouping() {
            Map<String, Object> help = ConceptsHelpProvider.getMultiDagHelp();

            @SuppressWarnings("unchecked")
            Map<String, Object> rules = (Map<String, Object>) help.get("RULES");

            assertThat(rules).containsKey("dag_grouping");
            assertThat(rules).containsKey("per_trigger_epoch");
            assertThat(rules).containsKey("no_cross_dag_variables");
        }
    }

    // ==================== Concepts Help with Multi-DAG Section ====================

    @Nested
    @DisplayName("Concepts Help includes Multi-DAG")
    class ConceptsHelpMultiDag {

        @Test
        @DisplayName("Concepts help should include multi-DAG section")
        void conceptsIncludeMultiDag() {
            Map<String, Object> concepts = ConceptsHelpProvider.getConceptsHelp();

            assertThat(concepts).containsKey("8_MULTI_DAG");
        }

        @Test
        @DisplayName("Quick reference should include multi_dag entry")
        void quickReferenceIncludesMultiDag() {
            Map<String, Object> concepts = ConceptsHelpProvider.getConceptsHelp();

            @SuppressWarnings("unchecked")
            Map<String, String> quickRef = (Map<String, String>) concepts.get("QUICK_REFERENCE");

            assertThat(quickRef).containsKey("multi_dag");
        }
    }

    // ==================== Minimal Example Tests ====================

    @Nested
    @DisplayName("Minimal Example Structure")
    class MinimalExampleStructure {

        @Test
        @DisplayName("Minimal example should have required workflow fields")
        void minimalExampleHasRequiredFields() {
            Map<String, Object> minimal = ConceptsHelpProvider.getMinimalExample();

            assertThat(minimal).containsKey("name");
            assertThat(minimal).containsKey("plan");

            @SuppressWarnings("unchecked")
            Map<String, Object> plan = (Map<String, Object>) minimal.get("plan");

            assertThat(plan).containsKey("triggers");
            assertThat(plan).containsKey("mcps");
            assertThat(plan).containsKey("edges");
        }

        @Test
        @DisplayName("Minimal example edges should use correct prefixes")
        void minimalExampleEdgesUseCorrectPrefixes() {
            Map<String, Object> minimal = ConceptsHelpProvider.getMinimalExample();

            @SuppressWarnings("unchecked")
            Map<String, Object> plan = (Map<String, Object>) minimal.get("plan");

            @SuppressWarnings("unchecked")
            List<Map<String, String>> edges = (List<Map<String, String>>) plan.get("edges");

            assertThat(edges).isNotEmpty();

            Map<String, String> edge = edges.get(0);
            String from = edge.get("from");
            String to = edge.get("to");

            // From should be trigger:, to should be mcp:
            assertThat(from).startsWith("trigger:");
            assertThat(to).startsWith("mcp:");
        }
    }
}
