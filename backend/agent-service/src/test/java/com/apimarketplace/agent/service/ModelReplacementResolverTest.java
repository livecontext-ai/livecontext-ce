package com.apimarketplace.agent.service;

import com.apimarketplace.agent.domain.ModelConfigOverrideEntity;
import com.apimarketplace.agent.repository.ModelConfigOverrideRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link ModelReplacementResolver}: a model an admin disabled must not fail the runs that
 * already use it. They run on its replacement, or on the platform default.
 */
@DisplayName("ModelReplacementResolver")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ModelReplacementResolverTest {

    @Mock
    private ModelConfigOverrideRepository repository;

    @Mock
    private ModelCatalogService catalog;

    @Mock
    private ModelExecutionLinkService linkService;

    private ModelReplacementResolver resolver;
    private final List<ModelConfigOverrideEntity> disabledRows = new ArrayList<>();

    @BeforeEach
    void setUp() {
        resolver = new ModelReplacementResolver(repository, catalog);
        ReflectionTestUtils.setField(resolver, "executionLinkService", linkService);
        when(repository.findDisabledOrDeprecated()).thenReturn(disabledRows);
        when(catalog.getEffectiveDefaultProvider()).thenReturn("deepseek");
        when(catalog.getEffectiveDefaultModel()).thenReturn("deepseek-chat");
    }

    private void disabled(String provider, String model, String replacementProvider, String replacementModel) {
        ModelConfigOverrideEntity row = new ModelConfigOverrideEntity();
        row.setProvider(provider);
        row.setModelId(model);
        row.setEnabled(false);
        row.setReplacementProvider(replacementProvider);
        row.setReplacementModel(replacementModel);
        disabledRows.add(row);
    }

    @Nested
    @DisplayName("substituteIfDisabled (a pair a user or a stored config chose)")
    class Substitute {

        @Test
        @DisplayName("a DEPRECATED model (enabled untouched) is swapped too: on a CE it is one the cloud stopped shipping")
        void deprecatedModelIsSwapped() {
            // Regression (V533): a CE bundle only deprecates a model it stops carrying, it never
            // touches enabled, and the cloud relay refuses it. Stored agents and workflows kept
            // sending it and failed instead of moving to the platform default.
            ModelConfigOverrideEntity row = new ModelConfigOverrideEntity();
            row.setProvider("google");
            row.setModelId("gemini-3.1-flash-lite-preview");
            row.setDeprecatedAt(java.time.Instant.now());
            disabledRows.add(row);
            when(catalog.isModelAvailable("deepseek", "deepseek-chat")).thenReturn(true);

            var sub = resolver.substituteIfDisabled("google", "gemini-3.1-flash-lite-preview").orElseThrow();

            assertThat(sub.provider()).isEqualTo("deepseek");
            assertThat(sub.model()).isEqualTo("deepseek-chat");
            assertThat(sub.explicit()).isFalse();
        }

        @Test
        @DisplayName("an enabled model is never touched")
        void enabledModelUntouched() {
            assertThat(resolver.substituteIfDisabled("anthropic", "claude-opus-4-8")).isEmpty();
            verify(catalog, never()).getEffectiveDefaultModel();
        }

        @Test
        @DisplayName("a model merely absent from the catalog (not disabled) keeps running: a catalog gap must not move traffic")
        void absentButNotDisabledUntouched() {
            when(catalog.isModelAvailable(anyString(), anyString())).thenReturn(false);

            assertThat(resolver.substituteIfDisabled("claude-code", "claude-opus-4-8")).isEmpty();
        }

        @Test
        @DisplayName("a disabled model runs on its admin replacement when that one is runnable")
        void disabledRunsOnExplicitReplacement() {
            disabled("anthropic", "claude-opus-4-8", "anthropic", "claude-opus-4-9");
            when(catalog.isModelAvailable("anthropic", "claude-opus-4-9")).thenReturn(true);

            var sub = resolver.substituteIfDisabled("anthropic", "claude-opus-4-8").orElseThrow();

            assertThat(sub.provider()).isEqualTo("anthropic");
            assertThat(sub.model()).isEqualTo("claude-opus-4-9");
            assertThat(sub.explicit()).isTrue();
            assertThat(sub.replacedModel()).isEqualTo("claude-opus-4-8");
        }

        @Test
        @DisplayName("a disabled model with no replacement runs on the platform default (the bug: it used to fail)")
        void disabledWithoutReplacementRunsOnPlatformDefault() {
            disabled("anthropic", "claude-opus-4-8", null, null);

            var sub = resolver.substituteIfDisabled("anthropic", "claude-opus-4-8").orElseThrow();

            assertThat(sub.provider()).isEqualTo("deepseek");
            assertThat(sub.model()).isEqualTo("deepseek-chat");
            assertThat(sub.explicit()).isFalse();
        }

        @Test
        @DisplayName("provider matching ignores case, as stored slugs are lowercase but callers are not always")
        void providerMatchIsCaseInsensitive() {
            disabled("anthropic", "claude-opus-4-8", null, null);

            assertThat(resolver.substituteIfDisabled("Anthropic", "claude-opus-4-8")).isPresent();
        }

        @Test
        @DisplayName("a replacement that is itself disabled is followed to ITS replacement")
        void chainIsFollowed() {
            disabled("anthropic", "claude-opus-4-8", "anthropic", "claude-opus-4-9");
            disabled("anthropic", "claude-opus-4-9", "anthropic", "claude-opus-5");
            when(catalog.isModelAvailable("anthropic", "claude-opus-5")).thenReturn(true);

            var sub = resolver.substituteIfDisabled("anthropic", "claude-opus-4-8").orElseThrow();

            assertThat(sub.model()).isEqualTo("claude-opus-5");
            assertThat(sub.explicit()).isTrue();
        }

        @Test
        @DisplayName("a replacement cycle ends on the platform default instead of looping")
        void cycleFallsBackToDefault() {
            disabled("anthropic", "a", "anthropic", "b");
            disabled("anthropic", "b", "anthropic", "a");

            var sub = resolver.substituteIfDisabled("anthropic", "a").orElseThrow();

            assertThat(sub.model()).isEqualTo("deepseek-chat");
            assertThat(sub.explicit()).isFalse();
        }

        @Test
        @DisplayName("a disabled replacement with no replacement of its own falls to the platform default")
        void chainEndingOnDisabledWithoutReplacementFallsBack() {
            disabled("anthropic", "a", "anthropic", "b");
            disabled("anthropic", "b", null, null);

            assertThat(resolver.substituteIfDisabled("anthropic", "a").orElseThrow().model())
                .isEqualTo("deepseek-chat");
        }

        @Test
        @DisplayName("a replacement that is neither listed nor linked is not runnable: platform default instead")
        void unrunnableReplacementFallsBack() {
            disabled("anthropic", "claude-opus-4-8", "openai", "gpt-5");
            when(catalog.isModelAvailable("openai", "gpt-5")).thenReturn(false);
            when(linkService.isLinked("openai", "gpt-5")).thenReturn(false);

            var sub = resolver.substituteIfDisabled("anthropic", "claude-opus-4-8").orElseThrow();

            assertThat(sub.model()).isEqualTo("deepseek-chat");
            assertThat(sub.explicit()).isFalse();
        }

        @Test
        @DisplayName("a replacement runnable only through an execution link is accepted")
        void linkedReplacementIsRunnable() {
            disabled("anthropic", "claude-opus-4-8", "anthropic", "claude-opus-4-9");
            when(catalog.isModelAvailable("anthropic", "claude-opus-4-9")).thenReturn(false);
            when(linkService.isLinked("anthropic", "claude-opus-4-9")).thenReturn(true);

            var sub = resolver.substituteIfDisabled("anthropic", "claude-opus-4-8").orElseThrow();

            assertThat(sub.model()).isEqualTo("claude-opus-4-9");
            assertThat(sub.explicit()).isTrue();
        }

        @Test
        @DisplayName("in CE (no link store) a replacement must be listed by the catalog")
        void withoutLinkStoreOnlyCatalogCounts() {
            ReflectionTestUtils.setField(resolver, "executionLinkService", null);
            disabled("anthropic", "claude-opus-4-8", "anthropic", "claude-opus-4-9");
            when(catalog.isModelAvailable("anthropic", "claude-opus-4-9")).thenReturn(false);

            assertThat(resolver.substituteIfDisabled("anthropic", "claude-opus-4-8").orElseThrow().model())
                .isEqualTo("deepseek-chat");
        }

        @Test
        @DisplayName("no substitution when there is no platform default to fall back to")
        void noDefaultMeansNoSubstitution() {
            disabled("anthropic", "claude-opus-4-8", null, null);
            when(catalog.getEffectiveDefaultModel()).thenReturn(null);

            assertThat(resolver.substituteIfDisabled("anthropic", "claude-opus-4-8")).isEmpty();
        }

        @Test
        @DisplayName("a CLI model stored under the wrong provider still finds the disabled row it means")
        void mislabelledProviderIsMatchedByModelId() {
            disabled("claude-code", "claude-opus-4-7", "claude-code", "claude-opus-4-9");
            when(catalog.isModelAvailable("anthropic", "claude-opus-4-7")).thenReturn(false);
            when(catalog.isModelAvailable("claude-code", "claude-opus-4-9")).thenReturn(true);

            var sub = resolver.substituteIfDisabled("anthropic", "claude-opus-4-7").orElseThrow();

            // Pre-fix-of-the-fix the literal key missed, resolveProvider found no enabled row
            // to repair it to, and the run failed exactly as before V515.
            assertThat(sub.provider()).isEqualTo("claude-code");
            assertThat(sub.model()).isEqualTo("claude-opus-4-9");
        }

        @Test
        @DisplayName("a pair that is itself served is a deliberate direct choice: never re-mapped by model id")
        void servedPairIsNotRemapped() {
            disabled("claude-code", "claude-opus-4-7", null, null);
            when(catalog.isModelAvailable("anthropic", "claude-opus-4-7")).thenReturn(true);

            assertThat(resolver.substituteIfDisabled("anthropic", "claude-opus-4-7")).isEmpty();
        }

        @Test
        @DisplayName("a stored pair carried by an execution link is served: hiding its CLI link target must not move it")
        void linkedPairIsNotRemapped() {
            // anthropic/claude-opus-4-7 is linked to claude-code/claude-opus-4-7, and the admin
            // disabled the CLI row to hide it from users. The anthropic pair is absent from the
            // catalog (no key of its own) but runs through its link, and must keep doing so.
            disabled("claude-code", "claude-opus-4-7", null, null);
            when(catalog.isModelAvailable("anthropic", "claude-opus-4-7")).thenReturn(false);
            when(linkService.isLinked("anthropic", "claude-opus-4-7")).thenReturn(true);

            assertThat(resolver.substituteIfDisabled("anthropic", "claude-opus-4-7")).isEmpty();
        }

        @Test
        @DisplayName("the served check ignores provider case, like the disabled-set lookup")
        void servedCheckIgnoresProviderCase() {
            disabled("claude-code", "claude-opus-4-7", null, null);
            when(catalog.isModelAvailable("anthropic", "claude-opus-4-7")).thenReturn(true);

            assertThat(resolver.substituteIfDisabled("Anthropic", "claude-opus-4-7")).isEmpty();
        }

        @Test
        @DisplayName("an ambiguous model id (disabled under two providers) is not guessed")
        void ambiguousModelIdIsNotGuessed() {
            disabled("claude-code", "claude-opus-4-7", null, null);
            disabled("openrouter", "claude-opus-4-7", null, null);
            when(catalog.isModelAvailable("anthropic", "claude-opus-4-7")).thenReturn(false);

            assertThat(resolver.substituteIfDisabled("anthropic", "claude-opus-4-7")).isEmpty();
        }

        @Test
        @DisplayName("a blank pair is never looked up")
        void blankPairIgnored() {
            assertThat(resolver.substituteIfDisabled(null, "m")).isEmpty();
            assertThat(resolver.substituteIfDisabled("p", " ")).isEmpty();
            verify(repository, never()).findDisabledOrDeprecated();
        }

        @Test
        @DisplayName("fails open: a DB error reading the disabled set leaves the pair unchanged")
        void repositoryFailureFailsOpen() {
            when(repository.findDisabledOrDeprecated()).thenThrow(new IllegalStateException("db down"));

            assertThat(resolver.substituteIfDisabled("anthropic", "claude-opus-4-8")).isEmpty();
        }

        @Test
        @DisplayName("a failed read is backed off: an outage does not become one failing query per run")
        void failedReadIsBackedOff() {
            when(repository.findDisabledOrDeprecated()).thenThrow(new IllegalStateException("db down"));

            for (int i = 0; i < 5; i++) {
                resolver.substituteIfDisabled("anthropic", "claude-opus-4-8");
            }

            verify(repository, times(1)).findDisabledOrDeprecated();
        }

        @Test
        @DisplayName("the disabled set is cached: a split loop does not read the DB once per item")
        void disabledSetIsCached() {
            disabled("anthropic", "claude-opus-4-8", null, null);

            for (int i = 0; i < 5; i++) {
                resolver.substituteIfDisabled("anthropic", "claude-opus-4-8");
            }

            verify(repository, times(1)).findDisabledOrDeprecated();
        }
    }

    @Nested
    @DisplayName("explicitReplacementIfDisabled (an execution-link TARGET)")
    class LinkTarget {

        @Test
        @DisplayName("a disabled target WITH a replacement follows it, even one the user catalog does not list")
        void disabledTargetFollowsReplacement() {
            disabled("claude-code", "claude-opus-4-8", "claude-code", "claude-opus-4-9");

            var target = resolver.explicitReplacementIfDisabled("claude-code", "claude-opus-4-8").orElseThrow();

            assertThat(target.provider()).isEqualTo("claude-code");
            assertThat(target.model()).isEqualTo("claude-opus-4-9");
            verify(catalog, never()).isModelAvailable(anyString(), anyString());
        }

        @Test
        @DisplayName("a disabled target WITHOUT a replacement keeps running: hiding a CLI row must not break its links")
        void disabledTargetWithoutReplacementKeepsRunning() {
            disabled("claude-code", "claude-opus-4-8", null, null);

            assertThat(resolver.explicitReplacementIfDisabled("claude-code", "claude-opus-4-8")).isEmpty();
            verify(catalog, never()).getEffectiveDefaultModel();
        }

        @Test
        @DisplayName("an enabled target is untouched")
        void enabledTargetUntouched() {
            assertThat(resolver.explicitReplacementIfDisabled("claude-code", "claude-opus-4-8")).isEmpty();
        }
    }
}
