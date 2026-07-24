package com.apimarketplace.publication.utils;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("PlanSnapshotSanitizer")
class PlanSnapshotSanitizerTest {

    @Test
    @DisplayName("null input returns null")
    void nullInput() {
        assertThat(PlanSnapshotSanitizer.sanitizeForPreview(null)).isNull();
    }

    @Test
    @DisplayName("empty plan returns empty map")
    void emptyPlan() {
        Map<String, Object> result = PlanSnapshotSanitizer.sanitizeForPreview(Map.of());
        assertThat(result).isNotNull().isEmpty();
    }

    @Nested
    @DisplayName("triggers")
    class Triggers {
        @Test
        @DisplayName("keeps only id, type, label, position")
        void keepsWhitelistedKeys() {
            Map<String, Object> trigger = new LinkedHashMap<>();
            trigger.put("id", "trigger:start");
            trigger.put("type", "webhook");
            trigger.put("label", "Start");
            trigger.put("position", Map.of("x", 100, "y", 200));
            trigger.put("params", Map.of("url", "https://secret.com/hook"));
            trigger.put("chatMatch", "hello*");

            Map<String, Object> plan = Map.of("triggers", List.of(trigger));
            Map<String, Object> result = PlanSnapshotSanitizer.sanitizeForPreview(plan);

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> triggers = (List<Map<String, Object>>) result.get("triggers");
            assertThat(triggers).hasSize(1);
            assertThat(triggers.get(0)).containsOnlyKeys("id", "type", "label", "position");
            assertThat(triggers.get(0).get("id")).isEqualTo("trigger:start");
        }
    }

    @Nested
    @DisplayName("mcps")
    class Mcps {
        @Test
        @DisplayName("strips params and sensitive config")
        void stripsParams() {
            Map<String, Object> mcp = new LinkedHashMap<>();
            mcp.put("id", "mcp:fetch");
            mcp.put("label", "Fetch Data");
            mcp.put("type", "rest");
            mcp.put("position", Map.of("x", 0, "y", 0));
            mcp.put("params", Map.of("apiKey", "sk-secret-123", "url", "https://api.example.com"));
            mcp.put("variableMapping", Map.of("input", "{{trigger:start.body}}"));

            Map<String, Object> plan = Map.of("mcps", List.of(mcp));
            Map<String, Object> result = PlanSnapshotSanitizer.sanitizeForPreview(plan);

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> mcps = (List<Map<String, Object>>) result.get("mcps");
            assertThat(mcps.get(0)).containsOnlyKeys("id", "label", "type", "position");
            assertThat(mcps.get(0)).doesNotContainKeys("params", "variableMapping");
        }
    }

    @Nested
    @DisplayName("agents")
    class Agents {
        @Test
        @DisplayName("exposes model + provider (low-sensitivity 'powered by') but strips prompt / temperature; keeps classifyCategories")
        void exposesModelButStripsPromptKeepsClassifyCategories() {
            Map<String, Object> agent = new LinkedHashMap<>();
            agent.put("id", "agent:classifier");
            agent.put("type", "classify");
            agent.put("label", "Route Request");
            agent.put("position", Map.of("x", 300, "y", 100));
            // Low-sensitivity model identity - EXPOSED so the preview shows the real
            // model the app runs on (which LLM = "powered by", not a secret). Pre-fix
            // these were stripped, so the frontend ModelPicker fell back to its default
            // Claude id and a deepseek app rendered as Claude.
            agent.put("model", "claude-sonnet-4-5-20250929");
            agent.put("provider", "anthropic");
            // IP / sensitive fields - MUST be stripped.
            agent.put("systemPrompt", "You are a classifier...");
            agent.put("temperature", 0.7);
            agent.put("maxTokens", 4096);
            // User-facing categories - MUST survive so the canvas renders the
            // right number of output ports + the right per-branch label.
            agent.put("classifyCategories", List.of(
                    Map.of("id", "category-0", "label", "billing", "description", "Invoices, payments"),
                    Map.of("id", "category-1", "label", "support", "description", "Customer help requests")));

            Map<String, Object> plan = Map.of("agents", List.of(agent));
            Map<String, Object> result = PlanSnapshotSanitizer.sanitizeForPreview(plan);

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> agents = (List<Map<String, Object>>) result.get("agents");
            assertThat(agents.get(0)).containsOnlyKeys(
                    "id", "type", "label", "position", "classifyCategories", "model", "provider");
            // The fix: the real model identity is exposed to the preview.
            assertThat(agents.get(0)).containsEntry("model", "claude-sonnet-4-5-20250929");
            assertThat(agents.get(0)).containsEntry("provider", "anthropic");
            // The prompt + sampling settings remain stripped (publisher IP).
            assertThat(agents.get(0)).doesNotContainKeys("systemPrompt", "temperature", "maxTokens");
            // Defense-in-depth: verify the per-category payload survived intact.
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> categories =
                    (List<Map<String, Object>>) agents.get(0).get("classifyCategories");
            assertThat(categories).hasSize(2);
            assertThat(categories.get(0)).containsEntry("label", "billing");
            assertThat(categories.get(1)).containsEntry("label", "support");
        }

        @Test
        @DisplayName("guardrail node (Echo Watch shape): exposes model + provider, strips the screening prompt + guardrailRules + params")
        void guardrailExposesModelButStripsPromptAndRules() {
            // Mirrors the prod "Echo Watch" Risk Screen node: a guardrail agent whose
            // model was switched claude -> deepseek. The preview must show deepseek
            // (model/provider) but never leak the screening prompt or the rule set.
            Map<String, Object> agent = new LinkedHashMap<>();
            agent.put("id", "agent-Risk Screen-1");
            agent.put("type", "guardrail");
            agent.put("label", "Risk Screen");
            agent.put("position", Map.of("x", 871, "y", 60));
            agent.put("model", "deepseek-chat");
            agent.put("provider", "deepseek");
            // IP - the screening instructions and rule descriptions MUST stay stripped.
            agent.put("prompt", "You are screening recent news mentions about a brand...");
            agent.put("params", Map.of("guardrailParams", "{{core:build_feed.output.result.digest_text}}"));
            agent.put("guardrailRules", List.of(
                    Map.of("id", "rule-0", "type", "custom",
                            "config", Map.of("description", "Flag signals of a brand crisis or scandal"))));

            Map<String, Object> plan = Map.of("agents", List.of(agent));
            Map<String, Object> result = PlanSnapshotSanitizer.sanitizeForPreview(plan);

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> agents = (List<Map<String, Object>>) result.get("agents");
            assertThat(agents.get(0)).containsOnlyKeys("id", "type", "label", "position", "model", "provider");
            assertThat(agents.get(0)).containsEntry("model", "deepseek-chat");
            assertThat(agents.get(0)).containsEntry("provider", "deepseek");
            // The "prompt" field name (guardrail) and the rule set must not leak.
            assertThat(agents.get(0)).doesNotContainKeys("prompt", "guardrailRules", "params");
        }

        @Test
        @DisplayName("entity-backed agent (agentConfigId): the publish-time _snapshot_agent_* enrichment - incl. the systemPrompt IP - stays fully stripped")
        void entityBackedAgentEnrichmentStaysStripped() {
            // An agent node referencing a fleet agent entity. At publish time the model
            // and the full system prompt are captured under _snapshot_agent_* keys (for
            // the clone). NONE of those are whitelisted, so the preview must keep only
            // the node identity - the agent's prompt (the real IP surface) never leaks,
            // even though model/provider are now exposed for INLINE agents.
            Map<String, Object> agent = new LinkedHashMap<>();
            agent.put("id", "agent:fleet-ref");
            agent.put("type", "agent");
            agent.put("label", "Support Agent");
            agent.put("position", Map.of("x", 0, "y", 0));
            agent.put("agentConfigId", "11111111-1111-1111-1111-111111111111");
            agent.put("_snapshot_agent_name", "Support Agent");
            agent.put("_snapshot_agent_systemPrompt", "SECRET fleet-agent instructions");
            agent.put("_snapshot_agent_modelName", "deepseek-chat");
            agent.put("_snapshot_agent_modelProvider", "deepseek");
            agent.put("_snapshot_agent_config", Map.of("k", "v"));

            Map<String, Object> plan = Map.of("agents", List.of(agent));
            Map<String, Object> result = PlanSnapshotSanitizer.sanitizeForPreview(plan);

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> agents = (List<Map<String, Object>>) result.get("agents");
            assertThat(agents.get(0)).containsOnlyKeys("id", "type", "label", "position");
            // The entity prompt + the whole enrichment block must be gone.
            assertThat(agents.get(0)).doesNotContainKeys(
                    "agentConfigId", "_snapshot_agent_systemPrompt", "_snapshot_agent_name",
                    "_snapshot_agent_modelName", "_snapshot_agent_modelProvider", "_snapshot_agent_config");
        }

        @Test
        @DisplayName("agent tools survive so marketplace draws agent→tool edges (wiring lives only in agents[].tools)")
        void keepsAgentTools() {
            Map<String, Object> agent = new LinkedHashMap<>();
            agent.put("id", "agent:helper");
            agent.put("type", "agent");
            agent.put("label", "Helper");
            agent.put("position", Map.of("x", 0, "y", 0));
            agent.put("tools", List.of("mcp:fetch_data", "mcp:send_report"));
            agent.put("systemPrompt", "secret instructions");

            Map<String, Object> plan = Map.of("agents", List.of(agent));
            Map<String, Object> result = PlanSnapshotSanitizer.sanitizeForPreview(plan);

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> agents = (List<Map<String, Object>>) result.get("agents");
            assertThat(agents.get(0)).containsOnlyKeys("id", "type", "label", "position", "tools");
            assertThat((List<String>) agents.get(0).get("tools"))
                    .containsExactly("mcp:fetch_data", "mcp:send_report");
        }

        @Test
        @DisplayName("classifyCategories absent on the agent → key simply not present in the sanitized output (no NPE)")
        void absentClassifyCategoriesIsHandledGracefully() {
            Map<String, Object> agent = new LinkedHashMap<>();
            agent.put("id", "agent:helper");
            agent.put("type", "agent");
            agent.put("label", "Helper");
            agent.put("position", Map.of("x", 0, "y", 0));
            // No classifyCategories - normal (non-classify) agent.

            Map<String, Object> plan = Map.of("agents", List.of(agent));
            Map<String, Object> result = PlanSnapshotSanitizer.sanitizeForPreview(plan);

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> agents = (List<Map<String, Object>>) result.get("agents");
            assertThat(agents.get(0)).containsOnlyKeys("id", "type", "label", "position");
        }
    }

    @Nested
    @DisplayName("cores")
    class Cores {

        @SuppressWarnings("unchecked")
        private Map<String, Object> sanitizeSingleCore(Map<String, Object> core) {
            Map<String, Object> plan = Map.of("cores", List.of(core));
            Map<String, Object> result = PlanSnapshotSanitizer.sanitizeForPreview(plan);
            List<Map<String, Object>> cores = (List<Map<String, Object>>) result.get("cores");
            assertThat(cores).hasSize(1);
            return cores.get(0);
        }

        @Test
        @DisplayName("switch keeps switchCases + switchExpression so marketplace edges leave the right case port (regression 2026-06-12)")
        void switchKeepsCasesAndExpression() {
            Map<String, Object> core = new LinkedHashMap<>();
            core.put("id", "core:route_media");
            core.put("type", "switch");
            core.put("label", "route_media");
            core.put("position", Map.of("x", 290, "y", 0));
            core.put("switchExpression", "{{trigger:create_post.output.media_type}}");
            core.put("switchCases", List.of(
                    Map.of("id", "core:route_media-case-0", "type", "case", "label", "Photo", "value", "IMAGE"),
                    Map.of("id", "core:route_media-case-1", "type", "case", "label", "Reel", "value", "REELS"),
                    Map.of("id", "core:route_media-default", "type", "default", "label", "Other")));
            core.put("graphNodeId", "core:route_media");

            Map<String, Object> sanitized = sanitizeSingleCore(core);

            assertThat(sanitized).containsOnlyKeys(
                    "id", "type", "label", "position", "switchExpression", "switchCases");
            // The case array must survive INTACT: the importer maps the plan port
            // `core:route_media:case_N` onto the N-th non-default case to pick the
            // ReactFlow sourceHandle. A stripped/partial array silently rewires
            // every branch onto the first handle.
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> cases = (List<Map<String, Object>>) sanitized.get("switchCases");
            assertThat(cases).hasSize(3);
            assertThat(cases.get(1)).containsEntry("value", "REELS");
            assertThat(cases.get(2)).containsEntry("type", "default");
        }

        @Test
        @DisplayName("decision keeps decisionConditions (if/elseif/else ports)")
        void decisionKeepsConditions() {
            Map<String, Object> core = new LinkedHashMap<>();
            core.put("id", "core:check");
            core.put("type", "decision");
            core.put("label", "Check Status");
            core.put("position", Map.of("x", 500, "y", 200));
            core.put("decisionConditions", List.of(
                    Map.of("type", "if", "expression", "{{mcp:fetch.output.status}} == 'OK'"),
                    Map.of("type", "else", "expression", "")));

            Map<String, Object> sanitized = sanitizeSingleCore(core);

            assertThat(sanitized).containsOnlyKeys(
                    "id", "type", "label", "position", "decisionConditions");
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> conditions =
                    (List<Map<String, Object>>) sanitized.get("decisionConditions");
            assertThat(conditions).hasSize(2);
            assertThat(conditions.get(0)).containsEntry("type", "if");
            // Expression must survive intact - the canvas shows it on the branch.
            assertThat(conditions.get(0))
                    .containsEntry("expression", "{{mcp:fetch.output.status}} == 'OK'");
        }

        @Test
        @DisplayName("fork/option/approval keep their port-defining arrays")
        void forkOptionApprovalKeepPortArrays() {
            Map<String, Object> fork = new LinkedHashMap<>();
            fork.put("id", "core:fan_out");
            fork.put("type", "fork");
            fork.put("label", "Fan Out");
            fork.put("position", Map.of("x", 0, "y", 0));
            fork.put("forkOutputs", List.of(Map.of("id", "b0", "label", "Branch 1")));

            Map<String, Object> option = new LinkedHashMap<>();
            option.put("id", "core:pick");
            option.put("type", "option");
            option.put("label", "Pick");
            option.put("position", Map.of("x", 0, "y", 100));
            option.put("optionChoices", List.of(Map.of("label", "A", "expression", "1 == 1")));

            Map<String, Object> approval = new LinkedHashMap<>();
            approval.put("id", "core:gate");
            approval.put("type", "approval");
            approval.put("label", "Gate");
            approval.put("position", Map.of("x", 0, "y", 200));
            approval.put("approvalOutputs", List.of(
                    Map.of("label", "approved"), Map.of("label", "rejected")));
            // Approval runtime config (roles, thresholds, message) is NOT a port
            // definition - it must stay stripped.
            approval.put("approval", Map.of("approverRoles", List.of("ADMIN"), "requiredApprovals", 2));

            Map<String, Object> plan = Map.of("cores", List.of(fork, option, approval));
            Map<String, Object> result = PlanSnapshotSanitizer.sanitizeForPreview(plan);

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> cores = (List<Map<String, Object>>) result.get("cores");
            assertThat(cores.get(0)).containsOnlyKeys(
                    "id", "type", "label", "position", "forkOutputs");
            assertThat(cores.get(1)).containsOnlyKeys(
                    "id", "type", "label", "position", "optionChoices");
            assertThat(cores.get(2)).containsOnlyKeys(
                    "id", "type", "label", "position", "approvalOutputs");
        }

        @Test
        @DisplayName("still strips runtime/sensitive core config (loop, transform, httpRequest auth, sendEmail, params)")
        void stripsRuntimeAndSensitiveConfig() {
            Map<String, Object> core = new LinkedHashMap<>();
            core.put("id", "core:call");
            core.put("type", "http_request");
            core.put("label", "Call API");
            core.put("position", Map.of("x", 500, "y", 200));
            core.put("loopCondition", "#i < 10");
            core.put("transformConfig", Map.of("template", "..."));
            core.put("httpRequest", Map.of("authConfig", Map.of("bearerToken", "sk-secret")));
            core.put("sendEmail", Map.of("credentialId", "cred-123"));
            core.put("params", Map.of("url", "https://internal.example.com"));

            Map<String, Object> sanitized = sanitizeSingleCore(core);

            assertThat(sanitized).containsOnlyKeys("id", "type", "label", "position");
        }
    }

    @Nested
    @DisplayName("tables")
    class Tables {
        @Test
        @DisplayName("strips crud and dataSourceId")
        void stripsTableConfig() {
            Map<String, Object> table = new LinkedHashMap<>();
            table.put("id", "table:users");
            table.put("type", "read_rows");
            table.put("label", "Read Users");
            table.put("position", Map.of("x", 200, "y", 400));
            table.put("crud", Map.of("where", "email = 'admin@secret.com'"));
            table.put("dataSourceId", "ds-uuid-123");

            Map<String, Object> plan = Map.of("tables", List.of(table));
            Map<String, Object> result = PlanSnapshotSanitizer.sanitizeForPreview(plan);

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> tables = (List<Map<String, Object>>) result.get("tables");
            assertThat(tables.get(0)).containsOnlyKeys("id", "type", "label", "position");
        }
    }

    @Nested
    @DisplayName("edges")
    class Edges {
        @Test
        @DisplayName("keeps only from and to")
        void keepsFromTo() {
            Map<String, Object> edge = new LinkedHashMap<>();
            edge.put("from", "trigger:start");
            edge.put("to", "mcp:fetch");
            edge.put("someExtraField", "should be stripped");

            Map<String, Object> plan = Map.of("edges", List.of(edge));
            Map<String, Object> result = PlanSnapshotSanitizer.sanitizeForPreview(plan);

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> edges = (List<Map<String, Object>>) result.get("edges");
            assertThat(edges.get(0)).containsOnlyKeys("from", "to");
        }
    }

    @Nested
    @DisplayName("notes")
    class Notes {
        @Test
        @DisplayName("preserves all fields (visual only)")
        void preservesAllFields() {
            Map<String, Object> note = new LinkedHashMap<>();
            note.put("id", "note:1");
            note.put("label", "Design Notes");
            note.put("text", "This workflow handles user onboarding");
            note.put("color", "#fef3c7");
            note.put("borderColor", "#f59e0b");
            note.put("textColor", "#92400e");
            note.put("width", 200);
            note.put("height", 100);
            note.put("position", Map.of("x", 50, "y", 50));

            Map<String, Object> plan = Map.of("notes", List.of(note));
            Map<String, Object> result = PlanSnapshotSanitizer.sanitizeForPreview(plan);

            @SuppressWarnings("unchecked")
            List<Object> notes = (List<Object>) result.get("notes");
            assertThat(notes).hasSize(1);

            @SuppressWarnings("unchecked")
            Map<String, Object> sanitizedNote = (Map<String, Object>) notes.get(0);
            assertThat(sanitizedNote).containsAllEntriesOf(note);
        }
    }

    @Nested
    @DisplayName("interfaces")
    class Interfaces {
        @Test
        @DisplayName("keeps visual, snapshot template fields, AND mapping fields needed for the marketplace iframe bridge prefill - regression: stripping actionMapping left the textarea blank in the click-preview")
        void keepsVisualAndSnapshotAndMappingFields() {
            Map<String, Object> iface = new LinkedHashMap<>();
            iface.put("id", "interface:form");
            iface.put("label", "User Form");
            iface.put("position", Map.of("x", 400, "y", 300));
            iface.put("isEntryInterface", true);
            iface.put("showPreview", true);
            iface.put("previewWidth", 300);
            iface.put("previewHeight", 200);
            iface.put("_snapshot_htmlTemplate", "<div>form</div>");
            iface.put("_snapshot_cssTemplate", ".form { color: red; }");
            iface.put("_snapshot_jsTemplate", "console.log('init')");
            iface.put("actionMapping", Map.of("submit", "mcp:process"));
            iface.put("variableMapping", Map.of("name", "{{trigger:start.name}}"));
            iface.put("_snapshot_actionMappings", Map.of("submit", "mcp:process"));
            iface.put("_snapshot_variableMappings", Map.of("name", "{{trigger:start.name}}"));
            iface.put("generateScreenshot", true);
            iface.put("exposeRenderedSource", true);
            iface.put("internalSecret", "should-be-dropped");

            Map<String, Object> plan = Map.of("interfaces", List.of(iface));
            Map<String, Object> result = PlanSnapshotSanitizer.sanitizeForPreview(plan);

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> interfaces = (List<Map<String, Object>>) result.get("interfaces");
            assertThat(interfaces.get(0)).containsKeys(
                    "id", "label", "position", "isEntryInterface",
                    "showPreview", "previewWidth", "previewHeight",
                    "_snapshot_htmlTemplate", "_snapshot_cssTemplate", "_snapshot_jsTemplate",
                    "actionMapping", "variableMapping",
                    "_snapshot_actionMappings", "_snapshot_variableMappings",
                    "generateScreenshot", "exposeRenderedSource"
            );
            // Unknown / non-whitelisted keys still stripped.
            assertThat(interfaces.get(0)).doesNotContainKey("internalSecret");
        }

        @Test
        @DisplayName("keeps _snapshot_format - regression: an app published from a vertical interface was acquired as 1280x800")
        void keepsSnapshotFormat() {
            // This whitelist is a strip-by-default allowlist, so a key absent from it vanishes
            // silently: the acquired interface would render, capture and record at the default
            // shape with nothing in the response to hint why.
            Map<String, Object> iface = new LinkedHashMap<>();
            iface.put("id", "interface:story");
            iface.put("_snapshot_htmlTemplate", "<div>story</div>");
            iface.put("_snapshot_format", "vertical");

            Map<String, Object> plan = Map.of("interfaces", List.of(iface));
            Map<String, Object> result = PlanSnapshotSanitizer.sanitizeForPreview(plan);

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> interfaces = (List<Map<String, Object>>) result.get("interfaces");
            assertThat(interfaces.get(0)).containsEntry("_snapshot_format", "vertical");
        }

        @Test
        @DisplayName("keeps the PDF + video toggles - regression: publishing silently dropped the publisher's choices")
        void keepsPdfAndVideoToggles() {
            // Same allowlist bug as _snapshot_format, pre-existing: these node params were never
            // whitelisted, so a published workflow lost its pdf/video outputs on clone.
            Map<String, Object> iface = new LinkedHashMap<>();
            iface.put("id", "interface:report");
            iface.put("_snapshot_htmlTemplate", "<div>report</div>");
            iface.put("generatePdf", true);
            iface.put("pdfFormat", "Letter");
            iface.put("pdfLandscape", true);
            iface.put("generateVideo", true);
            iface.put("videoPreset", "horizontal");
            iface.put("videoMaxDurationSeconds", 45);
            iface.put("videoMode", "smooth");
            iface.put("videoFps", 60);

            Map<String, Object> plan = Map.of("interfaces", List.of(iface));
            Map<String, Object> result = PlanSnapshotSanitizer.sanitizeForPreview(plan);

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> interfaces = (List<Map<String, Object>>) result.get("interfaces");
            assertThat(interfaces.get(0)).containsKeys(
                    "generatePdf", "pdfFormat", "pdfLandscape",
                    "generateVideo", "videoPreset", "videoMaxDurationSeconds", "videoMode", "videoFps");
        }
    }

    @Nested
    @DisplayName("unknown collections")
    class UnknownCollections {
        @Test
        @DisplayName("drops unknown top-level keys entirely")
        void dropsUnknownKeys() {
            Map<String, Object> plan = new LinkedHashMap<>();
            plan.put("triggers", List.of(Map.of("id", "trigger:start", "type", "manual")));
            plan.put("secretStuff", List.of(Map.of("key", "value")));
            plan.put("internalConfig", "should be dropped");

            Map<String, Object> result = PlanSnapshotSanitizer.sanitizeForPreview(plan);
            assertThat(result).containsOnlyKeys("triggers");
        }
    }

    @Nested
    @DisplayName("original not mutated")
    class Immutability {
        @Test
        @DisplayName("original planSnapshot is not modified")
        void doesNotMutateOriginal() {
            Map<String, Object> trigger = new LinkedHashMap<>();
            trigger.put("id", "trigger:start");
            trigger.put("params", Map.of("secret", "key"));

            Map<String, Object> plan = new LinkedHashMap<>();
            plan.put("triggers", new ArrayList<>(List.of(trigger)));

            PlanSnapshotSanitizer.sanitizeForPreview(plan);

            // original still has params
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> originalTriggers = (List<Map<String, Object>>) plan.get("triggers");
            assertThat(originalTriggers.get(0)).containsKey("params");
        }
    }

    @Nested
    @DisplayName("full integration")
    class Integration {
        @Test
        @DisplayName("realistic plan: all collections sanitized correctly")
        void fullPlanSanitization() {
            Map<String, Object> plan = new LinkedHashMap<>();

            plan.put("triggers", List.of(Map.of(
                    "id", "trigger:webhook", "type", "webhook", "label", "Webhook",
                    "position", Map.of("x", 0, "y", 0), "params", Map.of("path", "/secret")
            )));
            plan.put("mcps", List.of(Map.of(
                    "id", "mcp:call_api", "label", "Call API",
                    "position", Map.of("x", 200, "y", 0), "params", Map.of("apiKey", "sk-xxx")
            )));
            plan.put("agents", List.of(Map.of(
                    "id", "agent:bot", "type", "agent", "label", "Bot",
                    "position", Map.of("x", 400, "y", 0), "systemPrompt", "You are..."
            )));
            plan.put("cores", List.of(Map.of(
                    "id", "core:check", "type", "decision", "label", "Check",
                    "position", Map.of("x", 600, "y", 0),
                    "decisionConditions", List.of(Map.of("expr", "#ok"))
            )));
            plan.put("tables", List.of(Map.of(
                    "id", "table:users", "type", "read_rows", "label", "Users",
                    "position", Map.of("x", 800, "y", 0), "dataSourceId", "ds-1"
            )));
            plan.put("edges", List.of(
                    Map.of("from", "trigger:webhook", "to", "mcp:call_api"),
                    Map.of("from", "mcp:call_api", "to", "agent:bot")
            ));
            plan.put("notes", List.of(Map.of(
                    "id", "note:1", "text", "Hello", "color", "#fff",
                    "borderColor", "#000", "textColor", "#333",
                    "width", 150, "height", 80
            )));
            plan.put("interfaces", List.of(Map.of(
                    "id", "interface:form", "label", "Form",
                    "position", Map.of("x", 100, "y", 300),
                    "actionMapping", Map.of("submit", "mcp:call_api")
            )));

            Map<String, Object> result = PlanSnapshotSanitizer.sanitizeForPreview(plan);

            // All 8 collections present
            assertThat(result).containsOnlyKeys(
                    "triggers", "mcps", "agents", "cores", "tables", "edges", "notes", "interfaces"
            );

            // Spot-check: no sensitive data leaked
            @SuppressWarnings("unchecked")
            var triggers = (List<Map<String, Object>>) result.get("triggers");
            assertThat(triggers.get(0)).doesNotContainKey("params");

            @SuppressWarnings("unchecked")
            var agents = (List<Map<String, Object>>) result.get("agents");
            assertThat(agents.get(0)).doesNotContainKey("systemPrompt");

            @SuppressWarnings("unchecked")
            var cores = (List<Map<String, Object>>) result.get("cores");
            // decisionConditions IS retained - the canvas needs it to wire each
            // branch edge onto the right if/elseif/else output port.
            assertThat(cores.get(0)).containsKey("decisionConditions");

            @SuppressWarnings("unchecked")
            var interfaces = (List<Map<String, Object>>) result.get("interfaces");
            // actionMapping IS retained - needed for marketplace iframe bridge prefill
            assertThat(interfaces.get(0)).containsKey("actionMapping");

            // Notes preserved fully
            @SuppressWarnings("unchecked")
            var notes = (List<Object>) result.get("notes");
            @SuppressWarnings("unchecked")
            var note = (Map<String, Object>) notes.get(0);
            assertThat(note).containsEntry("text", "Hello");

            // Edges intact
            @SuppressWarnings("unchecked")
            var edges = (List<Map<String, Object>>) result.get("edges");
            assertThat(edges).hasSize(2);
            assertThat(edges.get(0)).containsOnlyKeys("from", "to");
        }
    }

    @Nested
    @DisplayName("layoutDirection (scalar passthrough)")
    class LayoutDirection {
        @Test
        @DisplayName("keeps a vertical layoutDirection so the preview canvas renders it")
        void keepsVertical() {
            Map<String, Object> plan = new LinkedHashMap<>();
            plan.put("layoutDirection", "vertical");
            plan.put("triggers", List.of(Map.of("id", "trigger:start", "label", "Start")));

            Map<String, Object> result = PlanSnapshotSanitizer.sanitizeForPreview(plan);

            assertThat(result).containsEntry("layoutDirection", "vertical");
        }

        @Test
        @DisplayName("keeps a horizontal layoutDirection")
        void keepsHorizontal() {
            Map<String, Object> plan = new LinkedHashMap<>();
            plan.put("layoutDirection", "horizontal");

            Map<String, Object> result = PlanSnapshotSanitizer.sanitizeForPreview(plan);

            assertThat(result).containsEntry("layoutDirection", "horizontal");
        }

        @Test
        @DisplayName("absent layoutDirection yields no key (legacy plans stay unchanged)")
        void absentStaysAbsent() {
            Map<String, Object> plan = Map.of(
                    "triggers", List.of(Map.of("id", "trigger:start", "label", "Start")));

            Map<String, Object> result = PlanSnapshotSanitizer.sanitizeForPreview(plan);

            assertThat(result).doesNotContainKey("layoutDirection");
        }
    }
}
