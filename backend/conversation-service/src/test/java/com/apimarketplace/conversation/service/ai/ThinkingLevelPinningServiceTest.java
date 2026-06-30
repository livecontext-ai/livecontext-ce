package com.apimarketplace.conversation.service.ai;

import com.apimarketplace.agent.domain.ThinkingLevel;
import com.apimarketplace.agent.loop.CallPurpose;
import com.apimarketplace.conversation.repository.ConversationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Stage 1b.1 - pin the {@link ThinkingLevelPinningService} contract. The
 * service is load-bearing for Anthropic prompt-cache stability: a single
 * wrong path (e.g., writing on CLASSIFY, or flipping the stored value
 * mid-conversation) destroys the cached prefix we just paid 1.25× input
 * price to create.
 *
 * <p>Each {@code @Nested} block targets one decision surface: provider
 * family, call purpose, first-turn persistence, race resolution, and the
 * null-conversation fallback.
 */
@DisplayName("ThinkingLevelPinningService - Claude conversation-scoped pinning (Stage 1b.1)")
class ThinkingLevelPinningServiceTest {

    private static final String CONV_ID = "conv-abc";

    private ConversationRepository repo;
    private ThinkingLevelPinningService service;

    @BeforeEach
    void setUp() {
        repo = mock(ConversationRepository.class);
        service = new ThinkingLevelPinningService(repo);
    }

    @Nested
    @DisplayName("Non-Claude providers → null (loop auto-resolves)")
    class NonClaudeProviders {

        @Test
        @DisplayName("Gemini MAIN returns null - generationConfig.thinkingConfig does not invalidate cachedContent")
        void geminiReturnsNull() {
            ThinkingLevel result = service.resolveAndPin(
                    CONV_ID, "google", "gemini-3-flash", CallPurpose.MAIN, 5, 200);

            assertThat(result).isNull();
            verify(repo, never()).findThinkingLevelPinned(any());
            verify(repo, never()).pinThinkingLevelIfAbsent(any(), any());
        }

        @Test
        @DisplayName("OpenAI MAIN returns null - OpenAI ignores thinkingLevel entirely")
        void openaiReturnsNull() {
            ThinkingLevel result = service.resolveAndPin(
                    CONV_ID, "openai", "gpt-4o", CallPurpose.MAIN, 0, 10);

            assertThat(result).isNull();
            verify(repo, never()).pinThinkingLevelIfAbsent(any(), any());
        }

        @Test
        @DisplayName("Unknown provider + non-claude model → null (fail-safe: no pin, no bias)")
        void unknownProviderReturnsNull() {
            ThinkingLevel result = service.resolveAndPin(
                    CONV_ID, "mystery-llm", "mystery-model-1", CallPurpose.MAIN, 3, 100);

            assertThat(result).isNull();
        }
    }

    @Nested
    @DisplayName("Claude detection - provider name OR model prefix triggers pinning")
    class ClaudeDetection {

        @Test
        @DisplayName("provider=\"anthropic\" triggers Claude path even when model is a bare name")
        void providerAnthropicEnoughToPin() {
            when(repo.findThinkingLevelPinned(CONV_ID)).thenReturn(Optional.empty());
            when(repo.pinThinkingLevelIfAbsent(eq(CONV_ID), any())).thenReturn(1);

            ThinkingLevel result = service.resolveAndPin(
                    CONV_ID, "anthropic", "sonnet-latest", CallPurpose.MAIN, 0, 5);

            assertThat(result).isEqualTo(ThinkingLevel.LOW);
            verify(repo).pinThinkingLevelIfAbsent(CONV_ID, "LOW");
        }

        @Test
        @DisplayName("model prefix \"claude-\" triggers Claude path even when provider is blank/null")
        void claudeModelPrefixEnoughToPin() {
            when(repo.findThinkingLevelPinned(CONV_ID)).thenReturn(Optional.empty());
            when(repo.pinThinkingLevelIfAbsent(eq(CONV_ID), any())).thenReturn(1);

            ThinkingLevel result = service.resolveAndPin(
                    CONV_ID, null, "claude-sonnet-4-5", CallPurpose.MAIN, 5, 300);

            assertThat(result).isEqualTo(ThinkingLevel.HIGH);
            verify(repo).pinThinkingLevelIfAbsent(CONV_ID, "HIGH");
        }

        @Test
        @DisplayName("provider casing is ignored - \"Anthropic\" also triggers the Claude path")
        void providerCaseInsensitive() {
            when(repo.findThinkingLevelPinned(CONV_ID)).thenReturn(Optional.empty());
            when(repo.pinThinkingLevelIfAbsent(eq(CONV_ID), any())).thenReturn(1);

            service.resolveAndPin(CONV_ID, "ANTHROPIC", "claude-opus-4", CallPurpose.MAIN, 1, 10);

            verify(repo).pinThinkingLevelIfAbsent(eq(CONV_ID), any());
        }
    }

    @Nested
    @DisplayName("Claude + CLASSIFY/GUARDRAIL → MEDIUM without pinning (cache state is independent)")
    class ClassifyGuardrailNotPinned {

        @Test
        @DisplayName("CLASSIFY returns MEDIUM and NEVER reads/writes pin")
        void classifyNeverPins() {
            ThinkingLevel result = service.resolveAndPin(
                    CONV_ID, "anthropic", "claude-sonnet-4-5", CallPurpose.CLASSIFY, 10, 500);

            assertThat(result).isEqualTo(ThinkingLevel.MEDIUM);
            verify(repo, never()).findThinkingLevelPinned(any());
            verify(repo, never()).pinThinkingLevelIfAbsent(any(), any());
        }

        @Test
        @DisplayName("GUARDRAIL returns MEDIUM and NEVER reads/writes pin")
        void guardrailNeverPins() {
            ThinkingLevel result = service.resolveAndPin(
                    CONV_ID, "anthropic", "claude-sonnet-4-5", CallPurpose.GUARDRAIL, 0, 5);

            assertThat(result).isEqualTo(ThinkingLevel.MEDIUM);
            verify(repo, never()).pinThinkingLevelIfAbsent(any(), any());
        }
    }

    @Nested
    @DisplayName("Claude + MAIN - first turn writes, subsequent turns read")
    class ClaudeMainPersistence {

        @Test
        @DisplayName("first MAIN turn with tiny prompt → compute LOW, persist LOW, return LOW")
        void firstTurnShortPromptPinsLow() {
            when(repo.findThinkingLevelPinned(CONV_ID)).thenReturn(Optional.empty());
            when(repo.pinThinkingLevelIfAbsent(CONV_ID, "LOW")).thenReturn(1);

            ThinkingLevel result = service.resolveAndPin(
                    CONV_ID, "anthropic", "claude-sonnet-4-5", CallPurpose.MAIN, 2, 10);

            assertThat(result).isEqualTo(ThinkingLevel.LOW);
            verify(repo).pinThinkingLevelIfAbsent(CONV_ID, "LOW");
        }

        @Test
        @DisplayName("first MAIN turn with >2 tools → compute HIGH, persist HIGH")
        void firstTurnManyToolsPinsHigh() {
            when(repo.findThinkingLevelPinned(CONV_ID)).thenReturn(Optional.empty());
            when(repo.pinThinkingLevelIfAbsent(CONV_ID, "HIGH")).thenReturn(1);

            ThinkingLevel result = service.resolveAndPin(
                    CONV_ID, "anthropic", "claude-sonnet-4-5", CallPurpose.MAIN, 10, 10);

            assertThat(result).isEqualTo(ThinkingLevel.HIGH);
            verify(repo).pinThinkingLevelIfAbsent(CONV_ID, "HIGH");
        }

        @Test
        @DisplayName("second MAIN turn with DIFFERENT shape reuses stored value - no re-resolve, no re-write")
        void subsequentTurnReusesPinned() {
            when(repo.findThinkingLevelPinned(CONV_ID)).thenReturn(Optional.of("LOW"));

            // Second turn would otherwise resolve to HIGH (long message), but
            // the pinned value wins - this is the whole point: cache stability.
            ThinkingLevel result = service.resolveAndPin(
                    CONV_ID, "anthropic", "claude-sonnet-4-5", CallPurpose.MAIN, 20, 5000);

            assertThat(result).isEqualTo(ThinkingLevel.LOW);
            verify(repo).findThinkingLevelPinned(CONV_ID);
            verify(repo, never()).pinThinkingLevelIfAbsent(any(), any());
        }

        @Test
        @DisplayName("unparseable stored value → re-resolve and re-pin (guards against corrupted rows)")
        void unparseableStoredValueRecovers() {
            when(repo.findThinkingLevelPinned(CONV_ID)).thenReturn(Optional.of("GARBAGE"));
            when(repo.pinThinkingLevelIfAbsent(CONV_ID, "HIGH")).thenReturn(1);

            ThinkingLevel result = service.resolveAndPin(
                    CONV_ID, "anthropic", "claude-sonnet-4-5", CallPurpose.MAIN, 5, 300);

            assertThat(result).isEqualTo(ThinkingLevel.HIGH);
            verify(repo).pinThinkingLevelIfAbsent(CONV_ID, "HIGH");
        }
    }

    @Nested
    @DisplayName("Race tolerance - concurrent first-turn writers converge on whichever landed first")
    class PinRaceResolution {

        @Test
        @DisplayName("UPDATE returned 0 rows (another writer landed) → re-read and honor the winner")
        void honorsWinnerAfterLostRace() {
            // Setup: empty on read, UPDATE returns 0 (another writer got there
            // first), re-read returns the winner's value.
            when(repo.findThinkingLevelPinned(CONV_ID))
                    .thenReturn(Optional.empty())   // first read: empty
                    .thenReturn(Optional.of("LOW")); // re-read after lost race
            when(repo.pinThinkingLevelIfAbsent(CONV_ID, "HIGH")).thenReturn(0);

            ThinkingLevel result = service.resolveAndPin(
                    CONV_ID, "anthropic", "claude-sonnet-4-5", CallPurpose.MAIN, 10, 500);

            // This turn computed HIGH but lost the race - it must honor LOW.
            assertThat(result).isEqualTo(ThinkingLevel.LOW);
            verify(repo, times(2)).findThinkingLevelPinned(CONV_ID);
        }

        @Test
        @DisplayName("UPDATE returned 0 but re-read also empty → fall back to computed value (best effort)")
        void fallsBackToComputedWhenWinnerVanished() {
            // Edge case: row deleted between our read and update. Very rare
            // (row mid-delete), but the service must not NPE.
            when(repo.findThinkingLevelPinned(CONV_ID))
                    .thenReturn(Optional.empty())
                    .thenReturn(Optional.empty());
            when(repo.pinThinkingLevelIfAbsent(CONV_ID, "HIGH")).thenReturn(0);

            ThinkingLevel result = service.resolveAndPin(
                    CONV_ID, "anthropic", "claude-sonnet-4-5", CallPurpose.MAIN, 5, 300);

            assertThat(result).isEqualTo(ThinkingLevel.HIGH);
        }
    }

    @Nested
    @DisplayName("Null conversation id - fresh resolve, no DB access")
    class NoConversationIdFallback {

        @Test
        @DisplayName("null conversationId → compute fresh, skip repo entirely")
        void nullConversationIdSkipsRepo() {
            ThinkingLevel result = service.resolveAndPin(
                    null, "anthropic", "claude-sonnet-4-5", CallPurpose.MAIN, 5, 300);

            assertThat(result).isEqualTo(ThinkingLevel.HIGH);
            verify(repo, never()).findThinkingLevelPinned(any());
            verify(repo, never()).pinThinkingLevelIfAbsent(any(), any());
        }

        @Test
        @DisplayName("blank conversationId → compute fresh, skip repo entirely")
        void blankConversationIdSkipsRepo() {
            ThinkingLevel result = service.resolveAndPin(
                    "   ", "anthropic", "claude-sonnet-4-5", CallPurpose.MAIN, 0, 5);

            assertThat(result).isEqualTo(ThinkingLevel.LOW);
            verify(repo, never()).pinThinkingLevelIfAbsent(any(), any());
        }
    }

    @Nested
    @DisplayName("null purpose → treated as MAIN (matches CallPurpose.orDefault contract)")
    class NullPurposeFallsThroughToMain {

        @Test
        @DisplayName("Claude + null purpose pins like MAIN would")
        void nullPurposeTreatedAsMain() {
            when(repo.findThinkingLevelPinned(CONV_ID)).thenReturn(Optional.empty());
            when(repo.pinThinkingLevelIfAbsent(eq(CONV_ID), any())).thenReturn(1);

            ThinkingLevel result = service.resolveAndPin(
                    CONV_ID, "anthropic", "claude-sonnet-4-5", null, 0, 5);

            assertThat(result).isEqualTo(ThinkingLevel.LOW);
            verify(repo).pinThinkingLevelIfAbsent(CONV_ID, "LOW");
        }
    }
}
