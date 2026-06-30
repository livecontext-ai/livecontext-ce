package com.apimarketplace.agent.summary;

import com.apimarketplace.agent.summary.AgentCompactionModelResolver.ModelRef;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Stage 5.2b - pin the 3-branch resolution ladder. These tests
 * document the fallback order as a spec the frontend / config can
 * rely on.
 */
@DisplayName("AgentCompactionModelResolver - override → primary → yaml ladder (Stage 5.2b)")
class AgentCompactionModelResolverTest {

    private static final String YAML_PROV = "anthropic";
    private static final String YAML_NAME = "claude-haiku-4-5";

    @Test
    @DisplayName("explicit override wins when both provider and name are set")
    void overrideWins() {
        ModelRef ref = AgentCompactionModelResolver.resolve(
                "openai", "gpt-4o-mini",
                "anthropic", "claude-sonnet-4-5",
                YAML_PROV, YAML_NAME);
        assertThat(ref).isEqualTo(new ModelRef("openai", "gpt-4o-mini"));
    }

    @Test
    @DisplayName("partial override (provider set, name blank) falls through to primary")
    void partialOverrideFallsThrough() {
        // The frontend's model picker should not commit a broken
        // state, but defence-in-depth: if one field somehow lands
        // NULL, we treat the override as unset rather than guess a
        // name. Pin the fall-through so a future "make partial
        // overrides stick" refactor breaks loudly.
        ModelRef ref = AgentCompactionModelResolver.resolve(
                "openai", null,
                "anthropic", "claude-sonnet-4-5",
                YAML_PROV, YAML_NAME);
        assertThat(ref).isEqualTo(new ModelRef("anthropic", "claude-sonnet-4-5"));

        ModelRef ref2 = AgentCompactionModelResolver.resolve(
                null, "gpt-4o-mini",
                "anthropic", "claude-sonnet-4-5",
                YAML_PROV, YAML_NAME);
        assertThat(ref2).isEqualTo(new ModelRef("anthropic", "claude-sonnet-4-5"));
    }

    @Test
    @DisplayName("blank override (whitespace) falls through")
    void blankOverrideFallsThrough() {
        ModelRef ref = AgentCompactionModelResolver.resolve(
                "   ", "  ",
                "anthropic", "claude-sonnet-4-5",
                YAML_PROV, YAML_NAME);
        assertThat(ref).isEqualTo(new ModelRef("anthropic", "claude-sonnet-4-5"));
    }

    @Test
    @DisplayName("null override AND null primary → yaml default")
    void yamlDefaultUsedWhenBothHigherTiersBlank() {
        ModelRef ref = AgentCompactionModelResolver.resolve(
                null, null,
                null, null,
                YAML_PROV, YAML_NAME);
        assertThat(ref).isEqualTo(new ModelRef(YAML_PROV, YAML_NAME));
    }

    @Test
    @DisplayName("legacy row (primary blank) skips to yaml default")
    void legacyRowFallsThrough() {
        // Pre-4a rows may have NULL primary model (before the
        // model-registry backfill landed). Pin the yaml fallback so
        // they aren't summariser-broken.
        ModelRef ref = AgentCompactionModelResolver.resolve(
                null, null,
                "", "",
                YAML_PROV, YAML_NAME);
        assertThat(ref).isEqualTo(new ModelRef(YAML_PROV, YAML_NAME));
    }

    @Test
    @DisplayName("all three tiers blank → IllegalStateException (config bug, not silent fallback)")
    void allBlankThrows() {
        // We refuse to substitute a hard-coded fallback here - the
        // yaml default is the contract, and missing it is a
        // startup-time config error. A silent "use Haiku" would
        // misroute compaction spend to Anthropic when the tenant may
        // have meant otherwise.
        assertThatThrownBy(() -> AgentCompactionModelResolver.resolve(
                        null, null,
                        null, null,
                        null, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("AgentDefaultsConfig.compactionModel");
    }

    @Test
    @DisplayName("yaml-only partial (provider set, name blank) also throws - no guess")
    void yamlPartialThrows() {
        assertThatThrownBy(() -> AgentCompactionModelResolver.resolve(
                        null, null,
                        null, null,
                        "anthropic", "   "))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("ModelRef rejects null provider or name (record invariant)")
    void modelRefRequiresNonNull() {
        assertThatThrownBy(() -> new ModelRef(null, "x"))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("provider");
        assertThatThrownBy(() -> new ModelRef("x", null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("name");
    }

    @Test
    @DisplayName("override precedence: even weak yaml + valid primary → override still wins")
    void overridePrecedenceIndependentOfOthers() {
        // Hermetic: the resolver's ladder is LEXICOGRAPHIC on tiers,
        // not based on "quality" comparisons between models. Pin so
        // a refactor that tries to "pick the best model available"
        // can't sneak in.
        ModelRef ref = AgentCompactionModelResolver.resolve(
                "ollama", "llama3-8b",                         // weaker
                "anthropic", "claude-opus-4-7",                // stronger primary
                YAML_PROV, YAML_NAME);
        assertThat(ref.provider()).isEqualTo("ollama");
        assertThat(ref.name()).isEqualTo("llama3-8b");
    }
}
