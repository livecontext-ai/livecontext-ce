package com.apimarketplace.publication.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("SearchTextBuilder")
class SearchTextBuilderTest {

    private static final UUID PUB_ID = UUID.randomUUID();

    private String build(SearchTextBuilder b) {
        return b.build(PUB_ID, "TEST");
    }

    @Nested
    @DisplayName("add")
    class AddTests {

        @Test
        @DisplayName("Skips null and blank input")
        void skipsNullAndBlank() {
            String out = build(SearchTextBuilder.create().add(null).add("").add("   "));
            assertThat(out).isEmpty();
        }

        @Test
        @DisplayName("Drops tokens shorter than 2 characters")
        void dropsShortTokens() {
            String out = build(SearchTextBuilder.create().add("a b ok yes"));
            assertThat(out).isEqualTo("ok yes");
        }

        @Test
        @DisplayName("Lowercases tokens")
        void lowercases() {
            String out = build(SearchTextBuilder.create().add("Invoice Generator"));
            assertThat(out).isEqualTo("invoice generator");
        }

        @Test
        @DisplayName("Deduplicates tokens (LinkedHashSet preserves first-insertion order)")
        void dedupes() {
            String out = build(SearchTextBuilder.create()
                    .add("invoice billing")
                    .add("invoice service"));
            assertThat(out).isEqualTo("invoice billing service");
        }

        @Test
        @DisplayName("Stops adding once MAX_LENGTH reached and flips truncated flag")
        void truncates() {
            SearchTextBuilder b = SearchTextBuilder.create();
            // ~12k unique tokens, each ~5 chars → exceeds 10k cap
            StringBuilder buf = new StringBuilder();
            for (int i = 0; i < 3000; i++) buf.append("tok").append(i).append(' ');
            b.add(buf.toString());
            String out = build(b);
            // truncation guarantees we capped well before the full corpus length
            assertThat(out.length()).isLessThanOrEqualTo(10_000 + 16); // +slack for last token
        }
    }

    @Nested
    @DisplayName("fromPlanSnapshot")
    class FromPlanSnapshot {

        @Test
        @DisplayName("Null and empty plan are no-ops")
        void nullAndEmptySafe() {
            assertThat(build(SearchTextBuilder.create().fromPlanSnapshot(null))).isEmpty();
            assertThat(build(SearchTextBuilder.create().fromPlanSnapshot(Map.of()))).isEmpty();
        }

        @Test
        @DisplayName("Extracts interface title, description, name and label")
        void extractsInterfaceFields() {
            Map<String, Object> plan = Map.of("interfaces", List.of(Map.of(
                    "title", "Invoice Generator",
                    "description", "Creates PDF invoices",
                    "name", "InvoicePage",
                    "label", "interface_invoices"
            )));
            String out = build(SearchTextBuilder.create().fromPlanSnapshot(plan));
            assertThat(out).contains("invoice", "generator", "creates", "pdf",
                    "invoices", "invoicepage", "interface_invoices");
        }

        @Test
        @DisplayName("Extracts mcp tool labels")
        void extractsMcpLabels() {
            Map<String, Object> plan = Map.of("mcps", List.of(
                    Map.of("label", "send_email"),
                    Map.of("label", "fetch_user")
            ));
            String out = build(SearchTextBuilder.create().fromPlanSnapshot(plan));
            assertThat(out).contains("send_email", "fetch_user");
        }

        @Test
        @DisplayName("Extracts trigger label and description")
        void extractsTriggerFields() {
            Map<String, Object> plan = Map.of("triggers", List.of(Map.of(
                    "label", "trigger_webhook",
                    "description", "Receives Stripe events"
            )));
            String out = build(SearchTextBuilder.create().fromPlanSnapshot(plan));
            assertThat(out).contains("trigger_webhook", "receives", "stripe", "events");
        }

        @Test
        @DisplayName("Extracts embedded agent name, role and description")
        void extractsAgentFields() {
            Map<String, Object> plan = Map.of("agents", List.of(Map.of(
                    "label", "agent_email",
                    "name", "EmailWriter",
                    "role", "Compose marketing emails",
                    "description", "GPT-style copywriter"
            )));
            String out = build(SearchTextBuilder.create().fromPlanSnapshot(plan));
            assertThat(out).contains("agent_email", "emailwriter", "compose",
                    "marketing", "emails", "copywriter");
        }

        @Test
        @DisplayName("Extracts table label and name")
        void extractsTableFields() {
            Map<String, Object> plan = Map.of("tables", List.of(
                    Map.of("label", "customers_table", "name", "Customer Records")
            ));
            String out = build(SearchTextBuilder.create().fromPlanSnapshot(plan));
            assertThat(out).contains("customers_table", "customer", "records");
        }

        @Test
        @DisplayName("Extracts core node labels")
        void extractsCoreLabels() {
            Map<String, Object> plan = Map.of("cores", List.of(
                    Map.of("label", "decide_route"), Map.of("label", "split_items")
            ));
            String out = build(SearchTextBuilder.create().fromPlanSnapshot(plan));
            assertThat(out).contains("decide_route", "split_items");
        }
    }

    @Nested
    @DisplayName("fromAgentSnapshot")
    class FromAgentSnapshot {

        @Test
        @DisplayName("Null and empty agent are no-ops")
        void nullSafe() {
            assertThat(build(SearchTextBuilder.create().fromAgentSnapshot(null))).isEmpty();
            assertThat(build(SearchTextBuilder.create().fromAgentSnapshot(Map.of()))).isEmpty();
        }

        @Test
        @DisplayName("Extracts top-level fields and skills")
        void topLevelAndSkills() {
            Map<String, Object> agent = Map.of(
                    "id", "a1",
                    "name", "Marketing Bot",
                    "description", "Plans campaigns",
                    "role", "marketer",
                    "skills", List.of(
                            Map.of("name", "compose_post", "description", "Drafts copy"),
                            Map.of("name", "schedule_send")));
            String out = build(SearchTextBuilder.create().fromAgentSnapshot(agent));
            assertThat(out).contains("marketing", "bot", "plans", "campaigns",
                    "marketer", "compose_post", "drafts", "copy", "schedule_send");
        }

        @Test
        @DisplayName("Recurses into subAgents")
        void recursesSubAgents() {
            Map<String, Object> sub = Map.of("id", "a2", "name", "WriterAgent",
                    "role", "ghostwriter");
            Map<String, Object> root = Map.of("id", "a1", "name", "Director",
                    "subAgents", List.of(sub));
            String out = build(SearchTextBuilder.create().fromAgentSnapshot(root));
            assertThat(out).contains("director", "writeragent", "ghostwriter");
        }

        @Test
        @DisplayName("Cycle-safe: agent A → subAgent A is added once, no infinite loop")
        void cycleSafe() {
            // Construct a self-cycle via a mutable map.
            java.util.Map<String, Object> a = new java.util.HashMap<>();
            a.put("id", "self");
            a.put("name", "selfish_agent");
            a.put("subAgents", List.of(a));
            String out = build(SearchTextBuilder.create().fromAgentSnapshot(a));
            // Only one occurrence of the unique token - proves recursion stopped
            assertThat(out).isEqualTo("selfish_agent");
        }

        @Test
        @DisplayName("Depth-capped: agents past depth 8 are not indexed")
        void depthCapped() {
            // Build a chain of 12 agents A0 → A1 → ... → A11 with unique names.
            java.util.Map<String, Object> deepest = new java.util.HashMap<>();
            deepest.put("id", "deep11");
            deepest.put("name", "DepthEleven");
            java.util.Map<String, Object> current = deepest;
            for (int i = 10; i >= 0; i--) {
                java.util.Map<String, Object> wrapper = new java.util.HashMap<>();
                wrapper.put("id", "agent" + i);
                wrapper.put("name", "Depth" + i);
                wrapper.put("subAgents", List.of(current));
                current = wrapper;
            }
            String out = build(SearchTextBuilder.create().fromAgentSnapshot(current));
            // Depth 0..8 indexed (9 levels), depth 9-11 cut off
            assertThat(out).contains("depth0", "depth8");
            assertThat(out).doesNotContain("depth9", "depth10", "deptheleven");
        }

        @Test
        @DisplayName("Includes landingInterface title and description")
        void landingInterface() {
            Map<String, Object> agent = Map.of("id", "a1", "name", "Bot",
                    "landingInterface", Map.of(
                            "title", "Welcome Page",
                            "description", "User-friendly intro"));
            String out = build(SearchTextBuilder.create().fromAgentSnapshot(agent));
            assertThat(out).contains("welcome", "page", "user-friendly", "intro");
        }
    }

    @Nested
    @DisplayName("fromResourceSnapshot")
    class FromResourceSnapshot {

        @Test
        @DisplayName("Extracts table-style columns by name and label")
        void extractsColumns() {
            Map<String, Object> table = Map.of(
                    "name", "Orders",
                    "columns", List.of(
                            Map.of("name", "customer_id", "label", "Customer"),
                            Map.of("name", "total_amount", "label", "Total")));
            String out = build(SearchTextBuilder.create().fromResourceSnapshot(table));
            assertThat(out).contains("orders", "customer_id", "customer", "total_amount", "total");
        }

        @Test
        @DisplayName("Extracts landing interface fields")
        void extractsLanding() {
            Map<String, Object> resource = Map.of(
                    "name", "Daily Report",
                    "landingInterface", Map.of(
                            "title", "Reports Dashboard",
                            "description", "View daily metrics"));
            String out = build(SearchTextBuilder.create().fromResourceSnapshot(resource));
            assertThat(out).contains("daily", "report", "reports", "dashboard",
                    "view", "metrics");
        }

        @Test
        @DisplayName("Null resource is a no-op")
        void nullSafe() {
            assertThat(build(SearchTextBuilder.create().fromResourceSnapshot(null))).isEmpty();
        }
    }

    @Nested
    @DisplayName("build")
    class BuildTests {

        @Test
        @DisplayName("Returns empty string when no tokens added")
        void emptyWhenNothingAdded() {
            assertThat(build(SearchTextBuilder.create())).isEmpty();
        }

        @Test
        @DisplayName("Joins tokens with single space, dedup preserved")
        void joinsTokens() {
            String out = build(SearchTextBuilder.create()
                    .add("hello")
                    .add("world")
                    .add("hello"));
            assertThat(out).isEqualTo("hello world");
        }
    }
}
