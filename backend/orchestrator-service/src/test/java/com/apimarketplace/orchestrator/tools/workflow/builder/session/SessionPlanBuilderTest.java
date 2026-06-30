package com.apimarketplace.orchestrator.tools.workflow.builder.session;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for SessionPlanBuilder, focusing on property renaming
 * for frontend compatibility (guardrail and classify nodes).
 */
@DisplayName("SessionPlanBuilder")
class SessionPlanBuilderTest {

    // ═══════════════════════════════════════════════════════════════════════════
    // Guardrail property renaming
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Guardrail property renaming")
    class GuardrailPropertyRenaming {

        @Test
        @DisplayName("Should rename 'content' to 'guardrailParams' for guardrail nodes")
        void shouldRenameContentToGuardrailParams() {
            Map<String, Object> guardrailMcp = new LinkedHashMap<>();
            guardrailMcp.put("label", "content_guard");
            guardrailMcp.put("isGuardrail", true);
            guardrailMcp.put("content", "{{trigger:start.output.user_input}}");

            SessionPlanBuilder builder = createBuilder(List.of(guardrailMcp));
            Map<String, Object> plan = builder.buildPlanMap();

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> agents = (List<Map<String, Object>>) plan.get("agents");
            assertThat(agents).hasSize(1);

            Map<String, Object> agent = agents.get(0);
            assertThat(agent).containsKey("guardrailParams");
            assertThat(agent.get("guardrailParams")).isEqualTo("{{trigger:start.output.user_input}}");
        }

        @Test
        @DisplayName("Should rename 'rules' to 'guardrailRules' for guardrail nodes")
        void shouldRenameRulesToGuardrailRules() {
            Map<String, Object> rules = new LinkedHashMap<>();
            rules.put("pii", "No personal information");
            rules.put("toxicity", "No toxic content");

            Map<String, Object> guardrailMcp = new LinkedHashMap<>();
            guardrailMcp.put("label", "content_guard");
            guardrailMcp.put("isGuardrail", true);
            guardrailMcp.put("content", "test input");
            guardrailMcp.put("rules", rules);

            SessionPlanBuilder builder = createBuilder(List.of(guardrailMcp));
            Map<String, Object> plan = builder.buildPlanMap();

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> agents = (List<Map<String, Object>>) plan.get("agents");
            Map<String, Object> agent = agents.get(0);

            assertThat(agent).containsKey("guardrailRules");
            assertThat(agent.get("guardrailRules")).isEqualTo(rules);
        }

        @Test
        @DisplayName("Should NOT override existing guardrailParams with content")
        void shouldNotOverrideExistingGuardrailParams() {
            Map<String, Object> guardrailMcp = new LinkedHashMap<>();
            guardrailMcp.put("label", "content_guard");
            guardrailMcp.put("isGuardrail", true);
            guardrailMcp.put("content", "old content");
            guardrailMcp.put("guardrailParams", "already set");

            SessionPlanBuilder builder = createBuilder(List.of(guardrailMcp));
            Map<String, Object> plan = builder.buildPlanMap();

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> agents = (List<Map<String, Object>>) plan.get("agents");
            Map<String, Object> agent = agents.get(0);

            assertThat(agent.get("guardrailParams")).isEqualTo("already set");
        }

        @Test
        @DisplayName("Should NOT override existing guardrailRules with rules")
        void shouldNotOverrideExistingGuardrailRules() {
            List<Map<String, Object>> existingRules = List.of(
                Map.of("id", "rule-0", "type", "pii_detection", "action", "flag")
            );

            Map<String, Object> guardrailMcp = new LinkedHashMap<>();
            guardrailMcp.put("label", "content_guard");
            guardrailMcp.put("isGuardrail", true);
            guardrailMcp.put("content", "test");
            guardrailMcp.put("rules", Map.of("pii", "No PII"));
            guardrailMcp.put("guardrailRules", existingRules);

            SessionPlanBuilder builder = createBuilder(List.of(guardrailMcp));
            Map<String, Object> plan = builder.buildPlanMap();

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> agents = (List<Map<String, Object>>) plan.get("agents");
            Map<String, Object> agent = agents.get(0);

            assertThat(agent.get("guardrailRules")).isEqualTo(existingRules);
        }

        @Test
        @DisplayName("Should set type='guardrail' when type field is missing")
        void shouldSetGuardrailType() {
            Map<String, Object> guardrailMcp = new LinkedHashMap<>();
            guardrailMcp.put("label", "content_guard");
            guardrailMcp.put("isGuardrail", true);
            guardrailMcp.put("content", "test");

            SessionPlanBuilder builder = createBuilder(List.of(guardrailMcp));
            Map<String, Object> plan = builder.buildPlanMap();

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> agents = (List<Map<String, Object>>) plan.get("agents");
            assertThat(agents.get(0).get("type")).isEqualTo("guardrail");
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Classify property renaming
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Classify property renaming")
    class ClassifyPropertyRenaming {

        @Test
        @DisplayName("Should rename 'categories' to 'classifyCategories' for classify nodes")
        void shouldRenameCategoriesToClassifyCategories() {
            List<Map<String, Object>> categories = List.of(
                Map.of("label", "spam", "description", "Spam content"),
                Map.of("label", "ham", "description", "Legitimate content")
            );

            Map<String, Object> classifyMcp = new LinkedHashMap<>();
            classifyMcp.put("label", "email_classifier");
            classifyMcp.put("isClassify", true);
            classifyMcp.put("categories", categories);
            classifyMcp.put("content", "{{trigger:start.output.email_body}}");

            SessionPlanBuilder builder = createBuilder(List.of(classifyMcp));
            Map<String, Object> plan = builder.buildPlanMap();

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> agents = (List<Map<String, Object>>) plan.get("agents");
            Map<String, Object> agent = agents.get(0);

            assertThat(agent).containsKey("classifyCategories");
            assertThat(agent.get("classifyCategories")).isEqualTo(categories);
            // categories should have been removed (uses .remove())
            assertThat(agent).doesNotContainKey("categories");
        }

        @Test
        @DisplayName("Should rename 'content' to 'classifyParams' for classify nodes")
        void shouldRenameContentToClassifyParams() {
            Map<String, Object> classifyMcp = new LinkedHashMap<>();
            classifyMcp.put("label", "classifier");
            classifyMcp.put("isClassify", true);
            classifyMcp.put("content", "{{trigger:start.output.text}}");

            SessionPlanBuilder builder = createBuilder(List.of(classifyMcp));
            Map<String, Object> plan = builder.buildPlanMap();

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> agents = (List<Map<String, Object>>) plan.get("agents");
            Map<String, Object> agent = agents.get(0);

            assertThat(agent).containsKey("classifyParams");
            assertThat(agent.get("classifyParams")).isEqualTo("{{trigger:start.output.text}}");
        }

        @Test
        @DisplayName("Should set type='classify' when type field is missing")
        void shouldSetClassifyType() {
            Map<String, Object> classifyMcp = new LinkedHashMap<>();
            classifyMcp.put("label", "classifier");
            classifyMcp.put("isClassify", true);
            classifyMcp.put("content", "test");

            SessionPlanBuilder builder = createBuilder(List.of(classifyMcp));
            Map<String, Object> plan = builder.buildPlanMap();

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> agents = (List<Map<String, Object>>) plan.get("agents");
            assertThat(agents.get(0).get("type")).isEqualTo("classify");
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Agent separation
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Agent vs MCP separation")
    class AgentSeparation {

        @Test
        @DisplayName("Guardrail nodes go to 'agents' list, not 'mcps'")
        void guardrailInAgentsNotMcps() {
            Map<String, Object> guardrailMcp = new LinkedHashMap<>();
            guardrailMcp.put("label", "guard");
            guardrailMcp.put("isGuardrail", true);
            guardrailMcp.put("content", "test");

            Map<String, Object> regularMcp = new LinkedHashMap<>();
            regularMcp.put("label", "gmail_send");

            SessionPlanBuilder builder = createBuilder(List.of(guardrailMcp, regularMcp));
            Map<String, Object> plan = builder.buildPlanMap();

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> agents = (List<Map<String, Object>>) plan.get("agents");
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> mcps = (List<Map<String, Object>>) plan.get("mcps");

            assertThat(agents).hasSize(1);
            assertThat(agents.get(0).get("label")).isEqualTo("guard");

            assertThat(mcps).hasSize(1);
            assertThat(mcps.get(0).get("label")).isEqualTo("gmail_send");
        }

        @Test
        @DisplayName("Regular agent nodes go to 'agents' list")
        void agentInAgentsList() {
            Map<String, Object> agentMcp = new LinkedHashMap<>();
            agentMcp.put("label", "analyzer");
            agentMcp.put("isAgent", true);

            SessionPlanBuilder builder = createBuilder(List.of(agentMcp));
            Map<String, Object> plan = builder.buildPlanMap();

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> agents = (List<Map<String, Object>>) plan.get("agents");
            assertThat(agents).hasSize(1);
            assertThat(agents.get(0).get("type")).isEqualTo("agent");
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Null byte sanitization
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Sanitization")
    class SanitizationTests {

        @Test
        @DisplayName("Should strip null bytes from guardrail content")
        void shouldStripNullBytesFromGuardrailContent() {
            Map<String, Object> guardrailMcp = new LinkedHashMap<>();
            guardrailMcp.put("label", "guard");
            guardrailMcp.put("isGuardrail", true);
            guardrailMcp.put("content", "test\u0000input");

            SessionPlanBuilder builder = createBuilder(List.of(guardrailMcp));
            Map<String, Object> plan = builder.buildPlanMap();

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> agents = (List<Map<String, Object>>) plan.get("agents");
            assertThat(agents.get(0).get("guardrailParams")).isEqualTo("testinput");
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Helper
    // ═══════════════════════════════════════════════════════════════════════════

    private SessionPlanBuilder createBuilder(List<Map<String, Object>> mcps) {
        SessionNodeFinder nodeFinder = new SessionNodeFinder(
            new ArrayList<>(), new ArrayList<>(mcps), new ArrayList<>(), Map.of()
        );
        SessionEdgeManager edgeManager = new SessionEdgeManager(new ArrayList<>(), nodeFinder);
        return new SessionPlanBuilder(
            "Test Workflow",
            "A test workflow",
            new LinkedHashMap<>(),
            new ArrayList<>(),      // triggers
            new ArrayList<>(mcps),  // mcps (agents are also here before separation)
            new ArrayList<>(),      // cores
            new ArrayList<>(),      // interfaces
            new ArrayList<>(),      // tables
            new ArrayList<>(),      // notes
            edgeManager
        );
    }
}
