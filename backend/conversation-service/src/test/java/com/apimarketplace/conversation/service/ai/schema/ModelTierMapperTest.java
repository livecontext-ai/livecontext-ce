package com.apimarketplace.conversation.service.ai.schema;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Stage 4a.2 - pin the tier-to-{@link SchemaMode} lookup contract.
 *
 * <p>Covers:
 * <ul>
 *   <li>Default tier map ships {@code top/high/mid=SLIM}, {@code budget=FULL}
 *   so the fleet's 4-tier classification routes correctly out-of-the-box.</li>
 *   <li>Per-model overrides win over tier lookup - guards weak-at-tool-use
 *   models like {@code glm-5-turbo} that carry a high quality tier but can't
 *   bootstrap from a help directive.</li>
 *   <li>Unknown model / blank tier falls through to the fail-safe default
 *   ({@link SchemaMode#FULL}).</li>
 * </ul>
 */
@DisplayName("ModelTierMapper - tier → SchemaMode (Stage 4a.2)")
class ModelTierMapperTest {

    private JitSchemaProperties props;
    private ModelTierMapper mapper;

    @BeforeEach
    void setUp() {
        props = new JitSchemaProperties();
        mapper = new ModelTierMapper(props);
    }

    @Nested
    @DisplayName("default tier map (out-of-the-box)")
    class DefaultTierMap {

        @Test
        @DisplayName("top tier → SLIM")
        void topTierIsSlim() {
            assertThat(mapper.resolve("anthropic", "claude-opus-4-6", "top")).isEqualTo(SchemaMode.SLIM);
        }

        @Test
        @DisplayName("high tier → SLIM")
        void highTierIsSlim() {
            assertThat(mapper.resolve("anthropic", "claude-sonnet-4-6", "high")).isEqualTo(SchemaMode.SLIM);
        }

        @Test
        @DisplayName("mid tier → SLIM")
        void midTierIsSlim() {
            assertThat(mapper.resolve("anthropic", "claude-haiku-4-5", "mid")).isEqualTo(SchemaMode.SLIM);
        }

        @Test
        @DisplayName("budget tier → FULL (weak models keep the full schema)")
        void budgetTierIsFull() {
            assertThat(mapper.resolve("deepseek", "deepseek-chat", "budget")).isEqualTo(SchemaMode.FULL);
        }
    }

    @Nested
    @DisplayName("tier normalisation")
    class TierNormalisation {

        @Test
        @DisplayName("tier lookup is case-insensitive")
        void casingIsIgnored() {
            assertThat(mapper.resolve("anthropic", "some-model", "TOP")).isEqualTo(SchemaMode.SLIM);
            assertThat(mapper.resolve("anthropic", "some-model", "Budget")).isEqualTo(SchemaMode.FULL);
        }

        @Test
        @DisplayName("whitespace around tier is trimmed")
        void whitespaceTrimmed() {
            assertThat(mapper.resolve("anthropic", "some-model", "  high  ")).isEqualTo(SchemaMode.SLIM);
        }

        @Test
        @DisplayName("unknown tier label falls through to default (FULL)")
        void unknownTierIsFallback() {
            assertThat(mapper.resolve("anthropic", "mystery", "legendary")).isEqualTo(SchemaMode.FULL);
        }
    }

    @Nested
    @DisplayName("per-model overrides win over tier")
    class ModelOverrides {

        @Test
        @DisplayName("override forces weak high-tier model to FULL (glm-5-turbo)")
        void weakHighTierModelForcedFull() {
            Map<String, SchemaMode> overrides = new HashMap<>();
            overrides.put("glm-5-turbo", SchemaMode.FULL);
            props.setModelOverrides(overrides);

            // Even though "high" tier defaults to SLIM, the override wins.
            assertThat(mapper.resolve("zai", "glm-5-turbo", "high")).isEqualTo(SchemaMode.FULL);
        }

        @Test
        @DisplayName("override can also promote a budget model to SLIM if explicitly configured")
        void overridePromotesBudgetToSlim() {
            Map<String, SchemaMode> overrides = new HashMap<>();
            overrides.put("custom-small-but-smart", SchemaMode.SLIM);
            props.setModelOverrides(overrides);

            assertThat(mapper.resolve("openai", "custom-small-but-smart", "budget")).isEqualTo(SchemaMode.SLIM);
        }

        @Test
        @DisplayName("overrides match on exact modelId (case-sensitive)")
        void overrideIsCaseSensitive() {
            Map<String, SchemaMode> overrides = new HashMap<>();
            overrides.put("glm-5-turbo", SchemaMode.FULL);
            props.setModelOverrides(overrides);

            // Different case doesn't match - falls through to tier.
            assertThat(mapper.resolve("zai", "GLM-5-Turbo", "high")).isEqualTo(SchemaMode.SLIM);
        }
    }

    @Nested
    @DisplayName("null / blank inputs")
    class NullBlank {

        @Test
        @DisplayName("null modelId → unknown default (no lookup performed)")
        void nullModelIdReturnsDefault() {
            assertThat(mapper.resolve("anthropic", null, "top")).isEqualTo(SchemaMode.FULL);
        }

        @Test
        @DisplayName("blank modelId → unknown default")
        void blankModelIdReturnsDefault() {
            assertThat(mapper.resolve("anthropic", "   ", "top")).isEqualTo(SchemaMode.FULL);
        }

        @Test
        @DisplayName("null tier still consults override first")
        void nullTierStillHitsOverride() {
            Map<String, SchemaMode> overrides = new HashMap<>();
            overrides.put("claude-opus-4-6", SchemaMode.SLIM);
            props.setModelOverrides(overrides);

            assertThat(mapper.resolve("anthropic", "claude-opus-4-6", null)).isEqualTo(SchemaMode.SLIM);
        }

        @Test
        @DisplayName("null tier without override → unknown default")
        void nullTierNoOverrideIsFallback() {
            assertThat(mapper.resolve("anthropic", "claude-opus-4-6", null)).isEqualTo(SchemaMode.FULL);
        }

        @Test
        @DisplayName("blank tier without override → unknown default")
        void blankTierNoOverrideIsFallback() {
            assertThat(mapper.resolve("anthropic", "claude-opus-4-6", "   ")).isEqualTo(SchemaMode.FULL);
        }
    }

    @Nested
    @DisplayName("custom unknown-model-default")
    class CustomDefault {

        @Test
        @DisplayName("unknown default can be flipped to SLIM for environments that prefer slim-by-default")
        void customDefaultHonoured() {
            props.setUnknownModelDefault(SchemaMode.SLIM);
            assertThat(mapper.resolve("openai", "mystery-model", "legendary")).isEqualTo(SchemaMode.SLIM);
        }

        @Test
        @DisplayName("null passed to setter collapses to FULL (fail-safe)")
        void nullCustomDefaultFallsBackToFull() {
            props.setUnknownModelDefault(null);
            assertThat(mapper.resolve("openai", "mystery-model", "legendary")).isEqualTo(SchemaMode.FULL);
        }
    }

    @Nested
    @DisplayName("resilience against null config maps")
    class NullConfigMaps {

        @Test
        @DisplayName("null mode-by-tier setter restores defaults, not NPE")
        void nullTierMapRestoresDefault() {
            props.setModeByTier(null);
            assertThat(mapper.resolve("anthropic", "claude-opus-4-6", "top")).isEqualTo(SchemaMode.SLIM);
        }

        @Test
        @DisplayName("null model-overrides setter yields empty map, not NPE")
        void nullOverridesMapIsEmpty() {
            props.setModelOverrides(null);
            assertThat(mapper.resolve("anthropic", "claude-opus-4-6", "top")).isEqualTo(SchemaMode.SLIM);
        }
    }
}
