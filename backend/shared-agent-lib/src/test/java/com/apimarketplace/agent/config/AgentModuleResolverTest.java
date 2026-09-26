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
                "catalog", "table", "interface", "agent", "skill", "memory",
                "workflow", "application", "web_search", "files", "wait", "ask_user", "channel"
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
                "table", "interface", "agent", "skill", "memory", "workflow", "application", "web_search",
                "files", "wait", "ask_user", "channel"
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
            // catalog/skill/files/wait/ask_user/channel are always on; web_search defaults on
            // (absent webSearch). channel is always-on because connecting a chat the user reads
            // has no resource to scope, and leaving it out is the feature being unreachable
            // rather than a restriction.
            assertThat(modules).containsExactlyInAnyOrder(
                "catalog", "skill", "memory", "files", "wait", "ask_user", "channel", "web_search"
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

            assertThat(modules).containsExactlyInAnyOrder(
                "catalog", "skill", "memory", "files", "wait", "ask_user", "channel");
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
                "catalog", "table", "interface", "skill", "memory", "workflow", "web_search",
                "files", "wait", "ask_user", "channel"
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
            assertThat(modules).containsExactlyInAnyOrder(
                "catalog", "skill", "memory", "files", "wait", "ask_user", "channel");
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
        @DisplayName("wait is always enabled regardless of config (harmless pause primitive) - except mode=off")
        void waitAlwaysEnabled() {
            assertThat(AgentModuleResolver.resolveEnabledModules(null)).contains("wait");

            Map<String, Object> modeNone = new HashMap<>();
            modeNone.put("mode", "none");
            assertThat(AgentModuleResolver.resolveEnabledModules(modeNone)).contains("wait");

            Map<String, Object> restricted = new HashMap<>();
            restricted.put("mode", "custom");
            restricted.put("tables", List.of());
            restricted.put("workflows", List.of());
            restricted.put("webSearch", false);
            assertThat(AgentModuleResolver.resolveEnabledModules(restricted)).contains("wait");

            Map<String, Object> modeOff = new HashMap<>();
            modeOff.put("mode", "off");
            assertThat(AgentModuleResolver.resolveEnabledModules(modeOff)).doesNotContain("wait");
        }

        @Test
        @DisplayName("ask_user is always enabled regardless of config (the tool itself answers 'unavailable' off-chat) - except mode=off")
        void askUserAlwaysEnabled() {
            assertThat(AgentModuleResolver.resolveEnabledModules(null)).contains("ask_user");

            Map<String, Object> modeNone = new HashMap<>();
            modeNone.put("mode", "none");
            assertThat(AgentModuleResolver.resolveEnabledModules(modeNone)).contains("ask_user");

            Map<String, Object> restricted = new HashMap<>();
            restricted.put("mode", "custom");
            restricted.put("tables", List.of());
            restricted.put("workflows", List.of());
            restricted.put("webSearch", false);
            assertThat(AgentModuleResolver.resolveEnabledModules(restricted)).contains("ask_user");

            Map<String, Object> modeOff = new HashMap<>();
            modeOff.put("mode", "off");
            assertThat(AgentModuleResolver.resolveEnabledModules(modeOff)).doesNotContain("ask_user");
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

        // ── retired image_generation grant ──────────────────────────────

        /**
         * The legacy image-generation tool and its module are gone. A persisted row
         * that still carries the old grant must resolve to NOTHING: not the retired
         * module (no tool serves it), and not {@code generation} either. Honouring it
         * as a fallback would hand an agent granted images the per-second video models
         * the unified tool also reaches, widening its spending authority without
         * anyone asking.
         */
        @Test
        @DisplayName("a retired imageGeneration grant resolves to no module at all, in any shape or mode")
        void retiredImageGenerationGrantResolvesToNothing() {
            for (Object grant : new Object[]{true, Map.of("enabled", true), Map.of("enabled", false)}) {
                Map<String, Object> config = new HashMap<>();
                config.put("imageGeneration", grant);
                assertThat(AgentModuleResolver.resolveEnabledModules(config))
                        .as("imageGeneration=%s must grant neither module", grant)
                        .doesNotContain("image_generation", "generation");
            }

            // mode=none takes the early-return branch, which reads the grants separately.
            Map<String, Object> modeNone = new HashMap<>();
            modeNone.put("mode", "none");
            modeNone.put("imageGeneration", true);
            assertThat(AgentModuleResolver.resolveEnabledModules(modeNone))
                    .doesNotContain("image_generation", "generation");
        }

        // ── generation (opt-in, spends credits per create) ──────────────

        @Test
        @DisplayName("generation absent → disabled even when toolsConfig is otherwise unrestricted")
        void generationAbsentDisabled() {
            Map<String, Object> config = new HashMap<>();
            Set<String> modules = AgentModuleResolver.resolveEnabledModules(config);
            assertThat(modules).doesNotContain("generation");
        }

        @Test
        @DisplayName("no toolsConfig at all → generation is NOT granted (it spends credits)")
        void generationNotGrantedWithoutConfig() {
            assertThat(AgentModuleResolver.resolveEnabledModules(null)).doesNotContain("generation");
        }

        @Test
        @DisplayName("generation=true → granted, so a configured agent actually receives the tool")
        void generationBooleanTrueEnabled() {
            Map<String, Object> config = new HashMap<>();
            config.put("generation", true);
            assertThat(AgentModuleResolver.resolveEnabledModules(config)).contains("generation");
        }

        @Test
        @DisplayName("generation={enabled:true,...} → granted (config object accepted like imageGeneration)")
        void generationObjectEnabledTrue() {
            Map<String, Object> config = new HashMap<>();
            config.put("generation", Map.of("enabled", true, "model", "seedance-2.0-fast"));
            assertThat(AgentModuleResolver.resolveEnabledModules(config)).contains("generation");
        }

        @Test
        @DisplayName("generation={enabled:false} → denied")
        void generationObjectEnabledFalse() {
            Map<String, Object> config = new HashMap<>();
            config.put("generation", Map.of("enabled", false));
            assertThat(AgentModuleResolver.resolveEnabledModules(config)).doesNotContain("generation");
        }

        @Test
        @DisplayName("generation=false → denied")
        void generationBooleanFalseDisabled() {
            Map<String, Object> config = new HashMap<>();
            config.put("generation", false);
            assertThat(AgentModuleResolver.resolveEnabledModules(config)).doesNotContain("generation");
        }

        /**
         * The two toggles are separate on purpose: an imageGeneration grant was given
         * for images, and a per-second video model spends an order of magnitude more
         * credits. Inheriting it would widen an existing agent's spending authority
         * without anyone asking for it.
         */
        @Test
        @DisplayName("only the generation key grants generation - the retired imageGeneration key never does")
        void onlyTheGenerationKeyGrantsGeneration() {
            Map<String, Object> imageOnly = new HashMap<>();
            imageOnly.put("imageGeneration", true);
            assertThat(AgentModuleResolver.resolveEnabledModules(imageOnly))
                    .doesNotContain("generation");

            Map<String, Object> generationOnly = new HashMap<>();
            generationOnly.put("generation", true);
            assertThat(AgentModuleResolver.resolveEnabledModules(generationOnly))
                    .contains("generation");
        }

        @Test
        @DisplayName("mode=none keeps generation opt-in, and honours the opt-in when present")
        void modeNoneKeepsGenerationOptIn() {
            Map<String, Object> config = new HashMap<>();
            config.put("mode", "none");
            assertThat(AgentModuleResolver.resolveEnabledModules(config)).doesNotContain("generation");

            Map<String, Object> withGeneration = new HashMap<>();
            withGeneration.put("mode", "none");
            withGeneration.put("generation", true);
            assertThat(AgentModuleResolver.resolveEnabledModules(withGeneration)).contains("generation");
        }

        @Test
        @DisplayName("mode=off advertises no tools at all, generation opt-in included")
        void modeOffDropsGenerationEvenWhenOptedIn() {
            Map<String, Object> config = new HashMap<>();
            config.put("mode", "off");
            config.put("generation", true);
            assertThat(AgentModuleResolver.resolveEnabledModules(config)).isEmpty();
        }

        @Test
        @DisplayName("isGenerationEnabled reads its own key and never falls back to imageGeneration")
        void isGenerationEnabledReadsItsOwnKey() {
            assertThat(AgentModuleResolver.isGenerationEnabled(null)).isFalse();
            assertThat(AgentModuleResolver.isGenerationEnabled(Map.of())).isFalse();
            assertThat(AgentModuleResolver.isGenerationEnabled(Map.of("generation", true))).isTrue();
            assertThat(AgentModuleResolver.isGenerationEnabled(Map.of("imageGeneration", true))).isFalse();
            // A config block with no explicit `enabled` field means the user meant it
            assertThat(AgentModuleResolver.isGenerationEnabled(Map.of("generation", Map.of("model", "x")))).isTrue();
            // Malformed values stay deny-safe
            assertThat(AgentModuleResolver.isGenerationEnabled(Map.of("generation", "yes"))).isFalse();
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
