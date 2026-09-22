package com.apimarketplace.agent.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Grant-sentinel coverage for {@link AgentModuleResolver#isResourceAccessible}.
 * <p>
 * The broad module-resolution suite lives in {@code shared-agent-lib}; this class
 * pins the per-family {@code <key>Grant} ∈ {none|all|custom} behaviour that lives
 * in {@code agent-common}, including the fallback to the legacy list rule when the
 * grant is absent (V163-safe).
 */
@DisplayName("AgentModuleResolver")
class AgentModuleResolverTest {

    @Nested
    @DisplayName("isResourceAccessible - GRANT sentinel")
    class IsResourceAccessibleGrant {

        @Test
        @DisplayName("grant='all' → accessible even when the list is empty")
        void grantAllAccessibleWithEmptyList() {
            Map<String, Object> config = new HashMap<>();
            config.put("workflows", List.of());          // would be BLOCKED by the legacy rule
            config.put("workflowsGrant", "all");
            assertThat(AgentModuleResolver.isResourceAccessible(config, "workflows")).isTrue();
        }

        @Test
        @DisplayName("grant='all' → accessible even when the list key is absent")
        void grantAllAccessibleWithAbsentList() {
            Map<String, Object> config = new HashMap<>();
            config.put("workflowsGrant", "all");
            assertThat(AgentModuleResolver.isResourceAccessible(config, "workflows")).isTrue();
        }

        @Test
        @DisplayName("grant='none' → blocked even when the list is non-empty")
        void grantNoneBlockedWithNonEmptyList() {
            Map<String, Object> config = new HashMap<>();
            config.put("tables", List.of("t1", "t2"));   // would be ACCESSIBLE by the legacy rule
            config.put("tablesGrant", "none");
            assertThat(AgentModuleResolver.isResourceAccessible(config, "tables")).isFalse();
        }

        @Test
        @DisplayName("grant='custom' → accessible iff the list is non-empty")
        void grantCustomDrivenByList() {
            Map<String, Object> nonEmpty = new HashMap<>();
            nonEmpty.put("interfaces", List.of("i1"));
            nonEmpty.put("interfacesGrant", "custom");
            assertThat(AgentModuleResolver.isResourceAccessible(nonEmpty, "interfaces")).isTrue();

            Map<String, Object> empty = new HashMap<>();
            empty.put("interfaces", List.of());
            empty.put("interfacesGrant", "custom");
            assertThat(AgentModuleResolver.isResourceAccessible(empty, "interfaces")).isFalse();

            Map<String, Object> absentList = new HashMap<>();
            absentList.put("interfacesGrant", "custom");
            assertThat(AgentModuleResolver.isResourceAccessible(absentList, "interfaces")).isFalse();
        }

        @Test
        @DisplayName("absent grant → DENY regardless of the list (no legacy fallback; deny-safe net)")
        void absentGrantDeniesRegardlessOfList() {
            // Grant is authoritative: an absent grant never consults the list and
            // never grants - it resolves to "none" (deny). The full backfill +
            // normalizeToolsConfig guarantee a grant exists at runtime; this is only
            // the deny-safe net for an un-backfilled row.
            assertThat(AgentModuleResolver.isResourceAccessible(new HashMap<>(), "agents")).isFalse();

            Map<String, Object> emptyList = new HashMap<>();
            emptyList.put("agents", List.of());
            assertThat(AgentModuleResolver.isResourceAccessible(emptyList, "agents")).isFalse();

            Map<String, Object> nonEmptyList = new HashMap<>();
            nonEmptyList.put("agents", List.of("a1"));   // a non-empty list does NOT grant without an explicit grant
            assertThat(AgentModuleResolver.isResourceAccessible(nonEmptyList, "agents")).isFalse();
        }

        @Test
        @DisplayName("unknown grant value → DENY (no legacy fallback)")
        void unknownGrantDenies() {
            Map<String, Object> config = new HashMap<>();
            config.put("applications", List.of("app-1"));
            config.put("applicationsGrant", "bogus");
            // An unrecognised grant is treated as deny, never the list.
            assertThat(AgentModuleResolver.isResourceAccessible(config, "applications")).isFalse();
        }
    }

    @Test
    @DisplayName("mode=none honours webSearch=false instead of re-enabling the module")
    void modeNoneHonoursWebSearchToggle() {
        // Regression: the mode=none branch added web_search unconditionally. Chat survived it
        // because AgentContextBuilder filters the tool a second time, but AgentNode (workflow
        // agents), SubAgentExecutionHandler and CliAgentService call this resolver with no second
        // filter, and WebSearchToolsProvider.execute has no permission check of its own. So an
        // agent configured mode='none', webSearch=false received a working web_search tool.
        Map<String, Object> cfg = new java.util.HashMap<>();
        cfg.put("mode", "none");
        cfg.put("webSearch", false);

        assertThat(AgentModuleResolver.resolveEnabledModules(cfg)).doesNotContain("web_search");
    }

    @Test
    @DisplayName("mode=none keeps web_search when the toggle is absent or true (opt-OUT semantics)")
    void modeNoneKeepsWebSearchWhenNotDisabled() {
        Map<String, Object> absent = new java.util.HashMap<>();
        absent.put("mode", "none");
        assertThat(AgentModuleResolver.resolveEnabledModules(absent)).contains("web_search");

        Map<String, Object> explicitlyTrue = new java.util.HashMap<>();
        explicitlyTrue.put("mode", "none");
        explicitlyTrue.put("webSearch", true);
        assertThat(AgentModuleResolver.resolveEnabledModules(explicitlyTrue)).contains("web_search");
    }

    @Test
    @DisplayName("mode=none still blocks catalog and still keeps the internal resource modules")
    void modeNoneKeepsItsDocumentedIntent() {
        // The families stay unconditional on purpose: that is this branch's documented intent
        // ("only MCP/catalog tools blocked; internal tools stay enabled"), and their execution is
        // closed anyway because the credentials carry an empty allow-list. Pinned so the
        // web_search fix above is not widened into a behaviour change nobody asked for.
        Map<String, Object> cfg = new java.util.HashMap<>();
        cfg.put("mode", "none");
        cfg.put("webSearch", false);

        assertThat(AgentModuleResolver.resolveEnabledModules(cfg))
                .doesNotContain("catalog")
                .contains("table", "interface", "agent", "skill", "memory", "workflow", "application", "files");
    }

}
