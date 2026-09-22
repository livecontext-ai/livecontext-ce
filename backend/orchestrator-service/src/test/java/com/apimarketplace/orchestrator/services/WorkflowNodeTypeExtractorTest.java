package com.apimarketplace.orchestrator.services;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The token grammar of {@link WorkflowNodeTypeExtractor}.
 *
 * <p>These tokens are what {@code node_types} stores and what every list filter
 * compares against, so a change in shape here silently changes which workflows a
 * filter finds - hence one test per rule rather than one broad happy path.
 */
@DisplayName("WorkflowNodeTypeExtractor.extractNodeTypes")
class WorkflowNodeTypeExtractorTest {

    private static Map<String, Object> node(String... keyValues) {
        Map<String, Object> map = new HashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            map.put(keyValues[i], keyValues[i + 1]);
        }
        return map;
    }

    /** A node with one field of any type - for the non-string cases below. */
    private static Map<String, Object> node2(String key, Object value) {
        Map<String, Object> map = new HashMap<>();
        map.put(key, value);
        return map;
    }

    private static Map<String, Object> plan(String branch, Object... nodes) {
        Map<String, Object> plan = new HashMap<>();
        plan.put(branch, List.of(nodes));
        return plan;
    }

    @Nested
    @DisplayName("per-family tokens")
    class Families {

        @Test
        @DisplayName("a trigger yields trigger:<type>")
        void triggerToken() {
            assertThat(WorkflowNodeTypeExtractor.extractNodeTypes(
                    plan("triggers", node("type", "webhook"))))
                    .containsExactly("trigger:webhook");
        }

        @Test
        @DisplayName("a core node yields core:<type>")
        void coreToken() {
            assertThat(WorkflowNodeTypeExtractor.extractNodeTypes(
                    plan("cores", node("type", "loop"))))
                    .containsExactly("core:loop");
        }

        @Test
        @DisplayName("an agent yields agent:<type>")
        void agentToken() {
            assertThat(WorkflowNodeTypeExtractor.extractNodeTypes(
                    plan("agents", node("type", "guardrail"))))
                    .containsExactly("agent:guardrail");
        }

        @Test
        @DisplayName("an agent with no explicit type is a plain agent")
        void agentDefaultsToAgent() {
            assertThat(WorkflowNodeTypeExtractor.extractNodeTypes(
                    plan("agents", node("provider", "openai"))))
                    .containsExactly("agent:agent");
        }

        @Test
        @DisplayName("a table node drops the crud- prefix, matching the frontend registry key")
        void tableStripsCrudPrefix() {
            assertThat(WorkflowNodeTypeExtractor.extractNodeTypes(
                    plan("tables", node("type", "crud-create-row"))))
                    .containsExactly("table:create-row");
        }

        @Test
        @DisplayName("any interface node yields the bare interface token")
        void interfaceToken() {
            assertThat(WorkflowNodeTypeExtractor.extractNodeTypes(
                    plan("interfaces", node("id", "interface:page"))))
                    .containsExactly("interface");
        }

        @Test
        @DisplayName("a non-map interface element still yields the token, matching what the card draws")
        void interfaceTokenIgnoresElementShape() {
            // WorkflowIconExtractor draws the interface glyph for ANY non-empty
            // interfaces array. Requiring a map here would put the glyph on a card
            // whose workflow the "interface" filter option cannot find.
            Map<String, Object> plan = new HashMap<>();
            plan.put("interfaces", List.of("not-a-map"));

            assertThat(WorkflowNodeTypeExtractor.extractNodeTypes(plan))
                    .containsExactly("interface");
        }

        @Test
        @DisplayName("an empty interfaces array yields nothing")
        void emptyInterfacesYieldsNothing() {
            Map<String, Object> plan = new HashMap<>();
            plan.put("interfaces", List.of());

            assertThat(WorkflowNodeTypeExtractor.extractNodeTypes(plan)).isEmpty();
        }
    }

    @Nested
    @DisplayName("mcp slug resolution (must match WorkflowIconExtractor)")
    class McpSlugs {

        @Test
        @DisplayName("an explicit iconSlug wins over the node id")
        void explicitIconSlugWins() {
            assertThat(WorkflowNodeTypeExtractor.extractNodeTypes(
                    plan("mcps", node("id", "gmail_api/send", "iconSlug", "gmail"))))
                    .containsExactly("mcp:gmail");
        }

        @Test
        @DisplayName("without an iconSlug the apiSlug prefix of the id is used")
        void fallsBackToApiSlug() {
            assertThat(WorkflowNodeTypeExtractor.extractNodeTypes(
                    plan("mcps", node("id", "slack/post_message"))))
                    .containsExactly("mcp:slack");
        }

        @Test
        @DisplayName("the catalog's 'mcp' placeholder is rejected, not used as a slug")
        void placeholderIconSlugIsRejected() {
            // COALESCE(icon_slug, 'mcp') in the catalog means an unbranded API arrives
            // with a non-blank but meaningless slug. Taking it at face value would
            // collapse every such API into one mcp:mcp bucket.
            assertThat(WorkflowNodeTypeExtractor.extractNodeTypes(
                    plan("mcps", node("id", "notion/query", "iconSlug", "mcp"))))
                    .containsExactly("mcp:notion");
        }

        @Test
        @DisplayName("the placeholder is rejected whatever its case")
        void placeholderRejectionIsCaseInsensitive() {
            assertThat(WorkflowNodeTypeExtractor.extractNodeTypes(
                    plan("mcps", node("id", "notion/query", "iconSlug", "MCP"))))
                    .containsExactly("mcp:notion");
        }

        @Test
        @DisplayName("an id with no slash is the slug itself")
        void idWithoutSlash() {
            assertThat(WorkflowNodeTypeExtractor.extractNodeTypes(
                    plan("mcps", node("id", "stripe"))))
                    .containsExactly("mcp:stripe");
        }

        @Test
        @DisplayName("an mcp node with no id contributes nothing")
        void blankIdIsSkipped() {
            assertThat(WorkflowNodeTypeExtractor.extractNodeTypes(
                    plan("mcps", node("id", ""))))
                    .isEmpty();
        }
    }

    @Nested
    @DisplayName("output shape")
    class OutputShape {

        @Test
        @DisplayName("tokens are deduplicated - three Gmail steps are one option")
        void deduplicates() {
            assertThat(WorkflowNodeTypeExtractor.extractNodeTypes(plan("mcps",
                    node("id", "gmail/send"),
                    node("id", "gmail/list"),
                    node("id", "gmail/label"))))
                    .containsExactly("mcp:gmail");
        }

        @Test
        @DisplayName("tokens are sorted, so an unchanged plan never rewrites the column")
        void isSorted() {
            Map<String, Object> plan = new HashMap<>();
            plan.put("triggers", List.of(node("type", "webhook")));
            plan.put("mcps", List.of(node("id", "slack/post")));
            plan.put("cores", List.of(node("type", "loop")));

            assertThat(WorkflowNodeTypeExtractor.extractNodeTypes(plan))
                    .containsExactly("core:loop", "mcp:slack", "trigger:webhook");
        }

        @Test
        @DisplayName("tokens are lowercased, so a filter is case-insensitive by construction")
        void lowercases() {
            assertThat(WorkflowNodeTypeExtractor.extractNodeTypes(
                    plan("cores", node("type", "HTTP_Request"))))
                    .containsExactly("core:http_request");
        }

        @Test
        @DisplayName("notes are excluded - a sticky note is not a step to filter by")
        void notesAreNotTokens() {
            Map<String, Object> plan = new HashMap<>();
            plan.put("notes", List.of(node("id", "note:reminder", "type", "note")));
            plan.put("cores", List.of(node("type", "code")));

            assertThat(WorkflowNodeTypeExtractor.extractNodeTypes(plan))
                    .containsExactly("core:code");
        }
    }

    @Nested
    @DisplayName("malformed input never blocks a save")
    class Robustness {

        @Test
        @DisplayName("a null plan yields no tokens")
        void nullPlan() {
            assertThat(WorkflowNodeTypeExtractor.extractNodeTypes(null)).isEmpty();
        }

        @Test
        @DisplayName("an empty plan yields no tokens")
        void emptyPlan() {
            assertThat(WorkflowNodeTypeExtractor.extractNodeTypes(Map.of())).isEmpty();
        }

        @Test
        @DisplayName("a branch that is not an array is ignored instead of throwing")
        void branchIsNotAList() {
            Map<String, Object> plan = new HashMap<>();
            plan.put("cores", "not-a-list");
            plan.put("mcps", List.of(node("id", "gmail/send")));

            assertThat(WorkflowNodeTypeExtractor.extractNodeTypes(plan))
                    .containsExactly("mcp:gmail");
        }

        @Test
        @DisplayName("a non-map element inside a branch is skipped, the rest still counts")
        void elementIsNotAMap() {
            List<Object> cores = new ArrayList<>();
            cores.add("garbage");
            cores.add(node("type", "code"));
            Map<String, Object> plan = new HashMap<>();
            plan.put("cores", cores);

            assertThat(WorkflowNodeTypeExtractor.extractNodeTypes(plan))
                    .containsExactly("core:code");
        }

        @Test
        @DisplayName("a non-string type is ignored instead of throwing, on the LIST read path")
        void nonStringTypeIsIgnored() {
            // A plain cast here would take the whole workflow list down for the
            // tenant holding one odd plan, not just fail that plan's save.
            Map<String, Object> plan = new HashMap<>();
            plan.put("cores", List.of(node2("type", 42), node("type", "wait")));

            assertThat(WorkflowNodeTypeExtractor.extractNodeTypes(plan))
                    .containsExactly("core:wait");
        }

        @Test
        @DisplayName("an agent whose type is not a string still counts as a plain agent")
        void nonStringAgentTypeFallsBackToAgent() {
            Map<String, Object> plan = new HashMap<>();
            plan.put("agents", List.of(node2("type", 7)));

            assertThat(WorkflowNodeTypeExtractor.extractNodeTypes(plan))
                    .containsExactly("agent:agent");
        }

        @Test
        @DisplayName("a node with a null type contributes nothing")
        void nullTypeIsSkipped() {
            List<Object> cores = new ArrayList<>();
            cores.add(new HashMap<String, Object>());
            cores.add(node("type", "wait"));
            Map<String, Object> plan = new HashMap<>();
            plan.put("cores", cores);

            assertThat(WorkflowNodeTypeExtractor.extractNodeTypes(plan))
                    .containsExactly("core:wait");
        }
    }
}
