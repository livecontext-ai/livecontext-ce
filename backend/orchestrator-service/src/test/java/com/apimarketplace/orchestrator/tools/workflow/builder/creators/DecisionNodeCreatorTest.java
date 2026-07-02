package com.apimarketplace.orchestrator.tools.workflow.builder.creators;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for DecisionNodeCreator format recovery logic.
 * Validates that hallucinated LLM formats for decision conditions
 * are properly converted to the standard {condition, label} format.
 */
@DisplayName("DecisionNodeCreator")
class DecisionNodeCreatorTest {

    @Nested
    @DisplayName("tryRecoverConditions")
    class TryRecoverConditionsTests {

        @Test
        @DisplayName("Should return null when no 'condition' key exists")
        void shouldReturnNullWhenNoConditionKey() {
            Map<String, Object> params = Map.of("label", "Check");
            assertThat(DecisionNodeCreator.tryRecoverConditions(params)).isNull();
        }

        @Test
        @DisplayName("Should return list directly when 'condition' is already a List")
        void shouldReturnListDirectlyWhenConditionIsList() {
            List<Map<String, Object>> conditionsList = List.of(
                Map.of("condition", "{{x}} == 1", "label", "One"),
                Map.of("condition", "default", "label", "Other")
            );
            Map<String, Object> params = Map.of("condition", conditionsList);

            List<Map<String, Object>> result = DecisionNodeCreator.tryRecoverConditions(params);

            assertThat(result).isNotNull().hasSize(2);
            assertThat(result.get(0).get("condition")).isEqualTo("{{x}} == 1");
            assertThat(result.get(1).get("label")).isEqualTo("Other");
        }

        @Test
        @DisplayName("Should convert {if: {field, operator}} hallucination to conditions array")
        void shouldConvertIfElseFieldOperatorFormat() {
            // This is the exact hallucinated format from the user's report
            Map<String, Object> ifBranch = new LinkedHashMap<>();
            ifBranch.put("field", "{{mcp:search_arxiv.results}}");
            ifBranch.put("operator", "is_not_empty");

            Map<String, Object> conditionObj = new LinkedHashMap<>();
            conditionObj.put("if", ifBranch);

            Map<String, Object> params = new LinkedHashMap<>();
            params.put("condition", conditionObj);
            params.put("ports", List.of("if", "else")); // Also hallucinated, should be ignored

            List<Map<String, Object>> result = DecisionNodeCreator.tryRecoverConditions(params);

            assertThat(result).isNotNull().hasSize(2);
            // if branch
            assertThat(result.get(0).get("condition")).isEqualTo("{{isempty(mcp:search_arxiv.results)}} == false");
            assertThat(result.get(0).get("label")).isEqualTo("Branch");
            // else branch (auto-generated)
            assertThat(result.get(1).get("condition")).isEqualTo("default");
            assertThat(result.get(1).get("label")).isEqualTo("Otherwise");
        }

        @Test
        @DisplayName("Should convert {if: {field, operator}, else: {label}} format")
        void shouldConvertIfElseWithElseLabel() {
            Map<String, Object> ifBranch = new LinkedHashMap<>();
            ifBranch.put("field", "{{mcp:api.output.count}}");
            ifBranch.put("operator", "greater_than");
            ifBranch.put("value", "0");

            Map<String, Object> elseBranch = new LinkedHashMap<>();
            elseBranch.put("label", "No Results");

            Map<String, Object> conditionObj = new LinkedHashMap<>();
            conditionObj.put("if", ifBranch);
            conditionObj.put("else", elseBranch);

            Map<String, Object> params = Map.of("condition", conditionObj);

            List<Map<String, Object>> result = DecisionNodeCreator.tryRecoverConditions(params);

            assertThat(result).isNotNull().hasSize(2);
            assertThat(result.get(0).get("condition")).isEqualTo("{{mcp:api.output.count}} > 0");
            assertThat(result.get(1).get("condition")).isEqualTo("default");
            assertThat(result.get(1).get("label")).isEqualTo("No Results");
        }

        @Test
        @DisplayName("Should convert {if: expression_string, else: label_string} format")
        void shouldConvertIfElseStringFormat() {
            Map<String, Object> conditionObj = new LinkedHashMap<>();
            conditionObj.put("if", "{{mcp:step.output.status}} == 'ok'");
            conditionObj.put("else", "Error Case");

            Map<String, Object> params = Map.of("condition", conditionObj);

            List<Map<String, Object>> result = DecisionNodeCreator.tryRecoverConditions(params);

            assertThat(result).isNotNull().hasSize(2);
            assertThat(result.get(0).get("condition")).isEqualTo("{{mcp:step.output.status}} == 'ok'");
            assertThat(result.get(0).get("label")).isEqualTo("If");
            assertThat(result.get(1).get("condition")).isEqualTo("default");
            assertThat(result.get(1).get("label")).isEqualTo("Error Case");
        }

        @Test
        @DisplayName("Should convert single condition object to conditions array with else")
        void shouldConvertSingleConditionObject() {
            Map<String, Object> conditionObj = new LinkedHashMap<>();
            conditionObj.put("expression", "{{mcp:api.output.valid}} == true");
            conditionObj.put("label", "Valid");

            Map<String, Object> params = Map.of("condition", conditionObj);

            List<Map<String, Object>> result = DecisionNodeCreator.tryRecoverConditions(params);

            assertThat(result).isNotNull().hasSize(2);
            assertThat(result.get(0).get("condition")).isEqualTo("{{mcp:api.output.valid}} == true");
            assertThat(result.get(0).get("label")).isEqualTo("Valid");
            assertThat(result.get(1).get("condition")).isEqualTo("default");
            assertThat(result.get(1).get("label")).isEqualTo("Otherwise");
        }

        @Test
        @DisplayName("Should handle is_empty operator")
        void shouldHandleIsEmptyOperator() {
            Map<String, Object> ifBranch = new LinkedHashMap<>();
            ifBranch.put("field", "{{mcp:fetch.output.data}}");
            ifBranch.put("operator", "is_empty");

            Map<String, Object> conditionObj = new LinkedHashMap<>();
            conditionObj.put("if", ifBranch);

            Map<String, Object> params = Map.of("condition", conditionObj);

            List<Map<String, Object>> result = DecisionNodeCreator.tryRecoverConditions(params);

            assertThat(result).isNotNull();
            assertThat(result.get(0).get("condition")).isEqualTo("{{isempty(mcp:fetch.output.data)}} == true");
        }

        @Test
        @DisplayName("Should handle equals operator with value")
        void shouldHandleEqualsOperator() {
            Map<String, Object> ifBranch = new LinkedHashMap<>();
            ifBranch.put("field", "{{mcp:api.output.status}}");
            ifBranch.put("operator", "equals");
            ifBranch.put("value", "success");

            Map<String, Object> conditionObj = new LinkedHashMap<>();
            conditionObj.put("if", ifBranch);

            Map<String, Object> params = Map.of("condition", conditionObj);

            List<Map<String, Object>> result = DecisionNodeCreator.tryRecoverConditions(params);

            assertThat(result).isNotNull();
            assertThat(result.get(0).get("condition")).isEqualTo("{{mcp:api.output.status}} == \"success\"");
        }

        @Test
        @DisplayName("Should handle contains operator")
        void shouldHandleContainsOperator() {
            Map<String, Object> ifBranch = new LinkedHashMap<>();
            ifBranch.put("field", "{{mcp:search.output.text}}");
            ifBranch.put("operator", "contains");
            ifBranch.put("value", "error");

            Map<String, Object> conditionObj = new LinkedHashMap<>();
            conditionObj.put("if", ifBranch);

            Map<String, Object> params = Map.of("condition", conditionObj);

            List<Map<String, Object>> result = DecisionNodeCreator.tryRecoverConditions(params);

            assertThat(result).isNotNull();
            assertThat(result.get(0).get("condition")).isEqualTo("{{contains(mcp:search.output.text, 'error')}}");
        }

        @Test
        @DisplayName("Should handle if with elseif branches")
        void shouldHandleElseifBranches() {
            Map<String, Object> conditionObj = new LinkedHashMap<>();
            conditionObj.put("if", "{{x}} > 100");
            conditionObj.put("elseif", "{{x}} > 50");
            conditionObj.put("else", "Low");

            Map<String, Object> params = Map.of("condition", conditionObj);

            List<Map<String, Object>> result = DecisionNodeCreator.tryRecoverConditions(params);

            assertThat(result).isNotNull().hasSize(3);
            assertThat(result.get(0).get("condition")).isEqualTo("{{x}} > 100");
            assertThat(result.get(1).get("condition")).isEqualTo("{{x}} > 50");
            assertThat(result.get(2).get("condition")).isEqualTo("default");
            assertThat(result.get(2).get("label")).isEqualTo("Low");
        }

        @Test
        @DisplayName("Should return null for non-map non-list condition value")
        void shouldReturnNullForStringCondition() {
            Map<String, Object> params = Map.of("condition", "some_string");

            List<Map<String, Object>> result = DecisionNodeCreator.tryRecoverConditions(params);

            assertThat(result).isNull();
        }

        @Test
        @DisplayName("Should handle condition map without if/else keys (single condition with field)")
        void shouldHandleSingleConditionWithField() {
            Map<String, Object> conditionObj = new LinkedHashMap<>();
            conditionObj.put("field", "{{mcp:step.output.value}}");
            conditionObj.put("operator", "not_equals");
            conditionObj.put("value", "null");

            Map<String, Object> params = Map.of("condition", conditionObj);

            List<Map<String, Object>> result = DecisionNodeCreator.tryRecoverConditions(params);

            assertThat(result).isNotNull().hasSize(2);
            assertThat(result.get(0).get("condition")).isEqualTo("{{mcp:step.output.value}} != \"null\"");
            assertThat(result.get(1).get("condition")).isEqualTo("default");
        }
    }

    @Nested
    @DisplayName("stripBraces")
    class StripBracesTests {

        @Test
        @DisplayName("Should strip surrounding {{ }} braces")
        void shouldStripBraces() {
            // Testing indirectly through tryRecoverConditions with field+operator format
            Map<String, Object> ifBranch = new LinkedHashMap<>();
            ifBranch.put("field", "{{mcp:step.output}}");
            ifBranch.put("operator", "is_not_empty");

            Map<String, Object> conditionObj = new LinkedHashMap<>();
            conditionObj.put("if", ifBranch);

            Map<String, Object> params = Map.of("condition", conditionObj);

            List<Map<String, Object>> result = DecisionNodeCreator.tryRecoverConditions(params);

            assertThat(result).isNotNull();
            // The braces should be stripped for the isempty() function call, then re-wrapped
            assertThat(result.get(0).get("condition")).isEqualTo("{{isempty(mcp:step.output)}} == false");
        }

        @Test
        @DisplayName("Should not strip when no braces present")
        void shouldNotStripWhenNoBraces() {
            Map<String, Object> ifBranch = new LinkedHashMap<>();
            ifBranch.put("field", "mcp:step.output");
            ifBranch.put("operator", "is_empty");

            Map<String, Object> conditionObj = new LinkedHashMap<>();
            conditionObj.put("if", ifBranch);

            Map<String, Object> params = Map.of("condition", conditionObj);

            List<Map<String, Object>> result = DecisionNodeCreator.tryRecoverConditions(params);

            assertThat(result).isNotNull();
            assertThat(result.get(0).get("condition")).isEqualTo("{{isempty(mcp:step.output)}} == true");
        }
    }

    @Nested
    @DisplayName("Label fallback - recovered conditions should never use expressions as labels")
    class LabelFallbackTests {

        @Test
        @DisplayName("Recovered field+operator condition should have 'Branch' label, not expression")
        void recoveredFieldOperatorShouldNotUseExpressionAsLabel() {
            Map<String, Object> ifBranch = new LinkedHashMap<>();
            ifBranch.put("field", "{{mcp:search.output.results}}");
            ifBranch.put("operator", "is_not_empty");
            // No 'label' key provided - this is the hallucination case

            Map<String, Object> conditionObj = new LinkedHashMap<>();
            conditionObj.put("if", ifBranch);

            Map<String, Object> params = Map.of("condition", conditionObj);

            List<Map<String, Object>> result = DecisionNodeCreator.tryRecoverConditions(params);

            assertThat(result).isNotNull();
            // Label should NOT be the expression "{{isempty(...)}}"
            String label = (String) result.get(0).get("label");
            assertThat(label).doesNotContain("{{");
            assertThat(label).doesNotContain("isempty");
            assertThat(label).isEqualTo("Branch");
        }

        @Test
        @DisplayName("Recovered conditions with labels should preserve them")
        void recoveredConditionsWithLabelsShouldPreserveThem() {
            Map<String, Object> ifBranch = new LinkedHashMap<>();
            ifBranch.put("field", "{{mcp:api.output.status}}");
            ifBranch.put("operator", "equals");
            ifBranch.put("value", "success");
            ifBranch.put("label", "Success Path");

            Map<String, Object> conditionObj = new LinkedHashMap<>();
            conditionObj.put("if", ifBranch);

            Map<String, Object> params = Map.of("condition", conditionObj);

            List<Map<String, Object>> result = DecisionNodeCreator.tryRecoverConditions(params);

            assertThat(result).isNotNull();
            assertThat(result.get(0).get("label")).isEqualTo("Success Path");
        }

        @Test
        @DisplayName("Condition with only expression and no label should get 'Branch' fallback")
        void conditionWithOnlyExpressionShouldGetBranchLabel() {
            Map<String, Object> conditionObj = new LinkedHashMap<>();
            conditionObj.put("expression", "{{mcp:api.output.count}} > 10");
            // No label

            Map<String, Object> params = Map.of("condition", conditionObj);

            List<Map<String, Object>> result = DecisionNodeCreator.tryRecoverConditions(params);

            assertThat(result).isNotNull();
            String label = (String) result.get(0).get("label");
            assertThat(label).doesNotContain("{{");
            assertThat(label).isEqualTo("Branch");
        }
    }

    @Nested
    @DisplayName("expandDecisionConditions")
    class ExpandDecisionConditionsTests {

        @Test
        @DisplayName("Should add else when no else exists")
        void shouldAddElseWhenMissing() {
            Map<String, Object> core = new LinkedHashMap<>();
            core.put("id", "core:check_status");

            List<Map<String, Object>> conditions = new ArrayList<>();
            conditions.add(new LinkedHashMap<>(Map.of("id", "check_status-if", "type", "if", "expression", "{{x}} > 0")));

            String port = DecisionNodeCreator.expandDecisionConditions(core, conditions, 1);

            assertThat(port).isEqualTo("else");
            assertThat(conditions).hasSize(2);
            assertThat(conditions.get(1).get("type")).isEqualTo("else");
            assertThat(conditions.get(1).get("id")).isEqualTo("check_status-else");
            assertThat(conditions.get(1).get("label")).isEqualTo("Otherwise");
            assertThat(conditions.get(1).get("expression")).isEqualTo("default");
        }

        @Test
        @DisplayName("Should add elseif before else when else already exists")
        void shouldAddElseifWhenElseExists() {
            Map<String, Object> core = new LinkedHashMap<>();
            core.put("id", "core:check_status");

            List<Map<String, Object>> conditions = new ArrayList<>();
            conditions.add(new LinkedHashMap<>(Map.of("id", "check_status-if", "type", "if", "expression", "{{x}} > 0")));
            conditions.add(new LinkedHashMap<>(Map.of("id", "check_status-else", "type", "else", "expression", "default")));

            // nextIdx=2 means both if and else ports are used, need a 3rd
            String port = DecisionNodeCreator.expandDecisionConditions(core, conditions, 2);

            // Runtime numbering (Core.getDecisionPorts) is position-1: the new
            // elseif lands at position 1 of [if, elseif, else] => elseif_0. The
            // old spec pinned elseif_1 (nextIdx-1 also counted the wired else
            // edge), a port the runtime never declares - the same
            // declared-vs-wired desync as the fork branch overflow.
            assertThat(port).isEqualTo("elseif_0");
            assertThat(conditions).hasSize(3);
            // elseif should be inserted before else
            assertThat(conditions.get(1).get("type")).isEqualTo("elseif");
            assertThat(conditions.get(1).get("id")).isEqualTo("check_status-elseif-0");
            assertThat(conditions.get(1).get("label")).isEqualTo("Else If 1");
            // else should remain last
            assertThat(conditions.get(2).get("type")).isEqualTo("else");
        }

        @Test
        @DisplayName("Should add multiple elseifs before else correctly")
        void shouldAddMultipleElseifsBeforeElse() {
            Map<String, Object> core = new LinkedHashMap<>();
            core.put("id", "core:route");

            List<Map<String, Object>> conditions = new ArrayList<>();
            conditions.add(new LinkedHashMap<>(Map.of("id", "route-if", "type", "if", "expression", "{{x}} > 100")));
            conditions.add(new LinkedHashMap<>(Map.of("id", "route-else", "type", "else", "expression", "default")));

            // First expansion: [if, else] => insert at position 1 => elseif_0
            String port1 = DecisionNodeCreator.expandDecisionConditions(core, conditions, 2);
            assertThat(port1).isEqualTo("elseif_0");
            assertThat(conditions).hasSize(3);

            // Second expansion: [if, elseif, else] => insert at position 2 => elseif_1
            String port2 = DecisionNodeCreator.expandDecisionConditions(core, conditions, 3);
            assertThat(port2).isEqualTo("elseif_1");
            assertThat(conditions).hasSize(4);

            // Order: if, elseif_0, elseif_1, else - contiguous runtime ports
            assertThat(conditions.get(0).get("type")).isEqualTo("if");
            assertThat(conditions.get(1).get("type")).isEqualTo("elseif");
            assertThat(conditions.get(2).get("type")).isEqualTo("elseif");
            assertThat(conditions.get(3).get("type")).isEqualTo("else");
        }
    }
}
