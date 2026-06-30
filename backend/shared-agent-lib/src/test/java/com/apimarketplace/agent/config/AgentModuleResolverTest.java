package com.apimarketplace.agent.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("AgentModuleResolver")
class AgentModuleResolverTest {

    @Nested
    @DisplayName("resolveEnabledModules")
    class ResolveEnabledModules {

        @Test
        @DisplayName("null toolsConfig should enable all opt-out modules (image_generation stays opt-in)")
        void nullToolsConfigEnablesAll() {
            Set<String> modules = AgentModuleResolver.resolveEnabledModules(null);

            assertThat(modules).containsExactlyInAnyOrder(
                "catalog", "table", "interface", "agent", "skill",
                "workflow", "application", "web_search", "files"
            );
            assertThat(modules).doesNotContain("image_generation");
        }

        @Test
        @DisplayName("mode=none should block catalog but keep all internal tools")
        void modeNoneBlocksCatalogOnly() {
            Map<String, Object> config = new HashMap<>();
            config.put("mode", "none");

            Set<String> modules = AgentModuleResolver.resolveEnabledModules(config);

            assertThat(modules).doesNotContain("catalog");
            assertThat(modules).containsExactlyInAnyOrder(
                "table", "interface", "agent", "skill", "workflow", "application", "web_search", "files"
            );
        }

        @Test
        @DisplayName("mode=off should resolve to NO modules at all - a tool-less judge agent advertises 0 tool schemas")
        void modeOffResolvesToNoModules() {
            Map<String, Object> config = new HashMap<>();
            config.put("mode", "off");
            // Grants present must be IRRELEVANT - mode=off is checked first and wins, so the agent
            // advertises ZERO tools (distinct from mode=none, which keeps the internal tools).
            config.put("tablesGrant", "all");
            config.put("agentsGrant", "all");
            config.put("webSearch", true);

            Set<String> modules = AgentModuleResolver.resolveEnabledModules(config);

            assertThat(modules)
                .as("mode=off must drop EVERY module incl. always-on catalog/skill/files")
                .isEmpty();
        }

        @Test
        @DisplayName("empty config (no grants) enables only the always-on modules + web_search")
        void emptyConfigEnablesOnlyAlwaysOnModules() {
            Map<String, Object> config = new HashMap<>();

            Set<String> modules = AgentModuleResolver.resolveEnabledModules(config);

            // No grants → the 5 internal families are DENIED (authoritative, no list fallback).
            // catalog/skill/files are always on; web_search defaults on (absent webSearch).
            assertThat(modules).containsExactlyInAnyOrder(
                "catalog", "skill", "files", "web_search"
            );
        }

        @Test
        @DisplayName("empty lists should block resources")
        void emptyListsBlockResources() {
            Map<String, Object> config = new HashMap<>();
            config.put("mode", "custom");
            config.put("tables", List.of());        // blocked
            config.put("interfaces", List.of());     // blocked
            config.put("agents", List.of());         // blocked
            config.put("workflows", List.of());      // blocked
            config.put("applications", List.of());   // blocked
            config.put("webSearch", false);           // disabled

            Set<String> modules = AgentModuleResolver.resolveEnabledModules(config);

            assertThat(modules).containsExactlyInAnyOrder("catalog", "skill", "files");
        }

        @Test
        @DisplayName("custom grants with non-empty lists enable those resources; none/absent grants block")
        void nonEmptyListsEnableResources() {
            Map<String, Object> config = new HashMap<>();
            config.put("mode", "custom");
            config.put("tables", List.of("table-1"));
            config.put("tablesGrant", "custom");
            config.put("interfaces", List.of("iface-1"));
            config.put("interfacesGrant", "custom");
            config.put("agents", List.of());            // none → blocked
            config.put("agentsGrant", "none");
            config.put("workflows", List.of("wf-1"));
            config.put("workflowsGrant", "custom");
            config.put("applications", List.of());      // none → blocked
            config.put("applicationsGrant", "none");
            config.put("webSearch", true);

            Set<String> modules = AgentModuleResolver.resolveEnabledModules(config);

            assertThat(modules).containsExactlyInAnyOrder(
                "catalog", "table", "interface", "skill", "workflow", "web_search", "files"
            );
            assertThat(modules).doesNotContain("agent", "application");
        }

        @Test
        @DisplayName("webSearch false disables web_search; families without a grant stay denied")
        void webSearchFalseDisabled() {
            Map<String, Object> config = new HashMap<>();
            config.put("webSearch", false);

            Set<String> modules = AgentModuleResolver.resolveEnabledModules(config);

            assertThat(modules).doesNotContain("web_search");
            // No grants → the 5 internal families are denied; only the always-on modules remain.
            assertThat(modules).containsExactlyInAnyOrder("catalog", "skill", "files");
        }

        @Test
        @DisplayName("webSearch true should enable web_search module")
        void webSearchTrueEnabled() {
            Map<String, Object> config = new HashMap<>();
            config.put("webSearch", true);

            Set<String> modules = AgentModuleResolver.resolveEnabledModules(config);

            assertThat(modules).contains("web_search");
        }

        @Test
        @DisplayName("skill is always enabled regardless of config")
        void skillAlwaysEnabled() {
            Map<String, Object> config = new HashMap<>();
            config.put("mode", "custom");
            config.put("tables", List.of());
            config.put("interfaces", List.of());
            config.put("agents", List.of());
            config.put("workflows", List.of());
            config.put("applications", List.of());
            config.put("webSearch", false);

            Set<String> modules = AgentModuleResolver.resolveEnabledModules(config);

            assertThat(modules).contains("skill");
        }

        @Test
        @DisplayName("files is always enabled regardless of config (read-only, org-scoped)")
        void filesAlwaysEnabled() {
            assertThat(AgentModuleResolver.resolveEnabledModules(null)).contains("files");

            Map<String, Object> modeNone = new HashMap<>();
            modeNone.put("mode", "none");
            assertThat(AgentModuleResolver.resolveEnabledModules(modeNone)).contains("files");

            Map<String, Object> restricted = new HashMap<>();
            restricted.put("mode", "custom");
            restricted.put("tables", List.of());
            restricted.put("workflows", List.of());
            restricted.put("webSearch", false);
            assertThat(AgentModuleResolver.resolveEnabledModules(restricted)).contains("files");
        }

        @Test
        @DisplayName("catalog is excluded with mode=none")
        void catalogExcludedModeNone() {
            Map<String, Object> config = new HashMap<>();
            config.put("mode", "none");

            Set<String> modules = AgentModuleResolver.resolveEnabledModules(config);

            assertThat(modules).doesNotContain("catalog");
        }

        @Test
        @DisplayName("catalog is enabled with mode=all")
        void catalogEnabledModeAll() {
            Set<String> modules = AgentModuleResolver.resolveEnabledModules(null);

            assertThat(modules).contains("catalog");
        }

        // ── image_generation (opt-in) ───────────────────────────────────

        @Test
        @DisplayName("image_generation absent → disabled even when toolsConfig is otherwise unrestricted")
        void imageGenAbsentDisabled() {
            Map<String, Object> config = new HashMap<>();
            // No imageGeneration key
            Set<String> modules = AgentModuleResolver.resolveEnabledModules(config);
            assertThat(modules).doesNotContain("image_generation");
        }

        @Test
        @DisplayName("imageGeneration=true → enabled")
        void imageGenBooleanTrueEnabled() {
            Map<String, Object> config = new HashMap<>();
            config.put("imageGeneration", true);
            Set<String> modules = AgentModuleResolver.resolveEnabledModules(config);
            assertThat(modules).contains("image_generation");
        }

        @Test
        @DisplayName("imageGeneration={enabled:true,...} → enabled")
        void imageGenObjectEnabledTrue() {
            Map<String, Object> config = new HashMap<>();
            config.put("imageGeneration", Map.of(
                    "enabled", true,
                    "provider", "openai",
                    "model", "gpt-image-1.5",
                    "quality", "medium"));
            Set<String> modules = AgentModuleResolver.resolveEnabledModules(config);
            assertThat(modules).contains("image_generation");
        }

        @Test
        @DisplayName("imageGeneration={enabled:false} → disabled")
        void imageGenObjectEnabledFalse() {
            Map<String, Object> config = new HashMap<>();
            config.put("imageGeneration", Map.of("enabled", false));
            Set<String> modules = AgentModuleResolver.resolveEnabledModules(config);
            assertThat(modules).doesNotContain("image_generation");
        }

        @Test
        @DisplayName("image_generation independent of webSearch toggle (no cross-contamination)")
        void imageGenIndependentOfWebSearch() {
            Map<String, Object> config = new HashMap<>();
            config.put("webSearch", true);
            config.put("imageGeneration", false);
            Set<String> modules = AgentModuleResolver.resolveEnabledModules(config);
            assertThat(modules).contains("web_search");
            assertThat(modules).doesNotContain("image_generation");
        }

        @Test
        @DisplayName("mode=none keeps image_generation opt-in posture")
        void modeNoneKeepsImageGenOptIn() {
            // Even though mode=none enables all "internal" tools by convention,
            // image_generation is treated as a separately-priced capability and
            // must be explicitly enabled.
            Map<String, Object> config = new HashMap<>();
            config.put("mode", "none");
            Set<String> modules = AgentModuleResolver.resolveEnabledModules(config);
            assertThat(modules).doesNotContain("image_generation");

            Map<String, Object> configWithIg = new HashMap<>();
            configWithIg.put("mode", "none");
            configWithIg.put("imageGeneration", true);
            assertThat(AgentModuleResolver.resolveEnabledModules(configWithIg)).contains("image_generation");
        }
    }

    @Nested
    @DisplayName("isResourceAccessible")
    class IsResourceAccessible {

        @Test
        @DisplayName("grant=all → accessible even with an empty list")
        void grantAllAccessible() {
            Map<String, Object> config = new HashMap<>();
            config.put("tablesGrant", "all");
            assertThat(AgentModuleResolver.isResourceAccessible(config, "tables")).isTrue();
        }

        @Test
        @DisplayName("grant=none → blocked even with a non-empty list")
        void grantNoneBlocked() {
            Map<String, Object> config = new HashMap<>();
            config.put("tables", List.of("t1", "t2"));
            config.put("tablesGrant", "none");
            assertThat(AgentModuleResolver.isResourceAccessible(config, "tables")).isFalse();
        }

        @Test
        @DisplayName("grant=custom → accessible iff the id list (custom payload) is non-empty")
        void grantCustomDrivenByList() {
            Map<String, Object> nonEmpty = new HashMap<>();
            nonEmpty.put("tables", List.of("t1"));
            nonEmpty.put("tablesGrant", "custom");
            assertThat(AgentModuleResolver.isResourceAccessible(nonEmpty, "tables")).isTrue();

            Map<String, Object> empty = new HashMap<>();
            empty.put("tables", List.of());
            empty.put("tablesGrant", "custom");
            assertThat(AgentModuleResolver.isResourceAccessible(empty, "tables")).isFalse();
        }

        @Test
        @DisplayName("absent grant → DENY regardless of the raw list value (no legacy fallback)")
        void absentGrantDenied() {
            assertThat(AgentModuleResolver.isResourceAccessible(new HashMap<>(), "tables")).isFalse();
            assertThat(AgentModuleResolver.isResourceAccessible(Map.of("tables", List.of()), "tables")).isFalse();
            assertThat(AgentModuleResolver.isResourceAccessible(Map.of("tables", List.of("t1")), "tables")).isFalse();
            assertThat(AgentModuleResolver.isResourceAccessible(Map.of("tables", "some-string"), "tables")).isFalse();
        }
    }

    @Nested
    @DisplayName("isBooleanEnabled")
    class IsBooleanEnabled {

        @Test
        @DisplayName("null value means enabled")
        void nullValueEnabled() {
            Map<String, Object> config = new HashMap<>();
            assertThat(AgentModuleResolver.isBooleanEnabled(config, "webSearch")).isTrue();
        }

        @Test
        @DisplayName("true value means enabled")
        void trueValueEnabled() {
            Map<String, Object> config = Map.of("webSearch", true);
            assertThat(AgentModuleResolver.isBooleanEnabled(config, "webSearch")).isTrue();
        }

        @Test
        @DisplayName("false value means disabled")
        void falseValueDisabled() {
            Map<String, Object> config = Map.of("webSearch", false);
            assertThat(AgentModuleResolver.isBooleanEnabled(config, "webSearch")).isFalse();
        }

        @Test
        @DisplayName("non-boolean value treated as enabled")
        void nonBooleanValueEnabled() {
            Map<String, Object> config = Map.of("webSearch", "yes");
            assertThat(AgentModuleResolver.isBooleanEnabled(config, "webSearch")).isTrue();
        }
    }
}
