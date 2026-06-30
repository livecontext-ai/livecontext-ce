package com.apimarketplace.agent.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for UsageInfo record.
 */
@DisplayName("UsageInfo")
class UsageInfoTest {

    @Nested
    @DisplayName("getTotal()")
    class GetTotalTests {

        @Test
        @DisplayName("should return totalTokens when provided")
        void shouldReturnTotalWhenProvided() {
            UsageInfo usage = UsageInfo.builder()
                    .promptTokens(100)
                    .completionTokens(50)
                    .totalTokens(150)
                    .build();

            assertThat(usage.getTotal()).isEqualTo(150);
        }

        @Test
        @DisplayName("should calculate total from prompt and completion when totalTokens is null")
        void shouldCalculateFromComponents() {
            UsageInfo usage = UsageInfo.builder()
                    .promptTokens(100)
                    .completionTokens(50)
                    .build();

            assertThat(usage.getTotal()).isEqualTo(150);
        }

        @Test
        @DisplayName("should handle null promptTokens")
        void shouldHandleNullPrompt() {
            UsageInfo usage = UsageInfo.builder()
                    .completionTokens(50)
                    .build();

            assertThat(usage.getTotal()).isEqualTo(50);
        }

        @Test
        @DisplayName("should handle null completionTokens")
        void shouldHandleNullCompletion() {
            UsageInfo usage = UsageInfo.builder()
                    .promptTokens(100)
                    .build();

            assertThat(usage.getTotal()).isEqualTo(100);
        }

        @Test
        @DisplayName("should return 0 when all are null")
        void shouldReturnZeroWhenAllNull() {
            UsageInfo usage = UsageInfo.builder().build();
            assertThat(usage.getTotal()).isEqualTo(0);
        }
    }

    @Nested
    @DisplayName("empty()")
    class EmptyTests {

        @Test
        @DisplayName("should create usage info with all zeros")
        void shouldCreateWithZeros() {
            UsageInfo usage = UsageInfo.empty();

            assertThat(usage.promptTokens()).isEqualTo(0);
            assertThat(usage.completionTokens()).isEqualTo(0);
            assertThat(usage.totalTokens()).isEqualTo(0);
            assertThat(usage.getTotal()).isEqualTo(0);
            assertThat(usage.cacheCreationInputTokens()).isEqualTo(0);
            assertThat(usage.cacheReadInputTokens()).isEqualTo(0);
            assertThat(usage.cachedTokens()).isEqualTo(0);
            assertThat(usage.reasoningTokens()).isEqualTo(0);
            assertThat(usage.thoughtsTokenCount()).isEqualTo(0);
            assertThat(usage.cachedContentTokenCount()).isEqualTo(0);
        }
    }

    @Nested
    @DisplayName("add()")
    class AddTests {

        @Test
        @DisplayName("should add two usage infos together")
        void shouldAddUsageInfos() {
            UsageInfo a = UsageInfo.builder().promptTokens(100).completionTokens(50).totalTokens(150).build();
            UsageInfo b = UsageInfo.builder().promptTokens(200).completionTokens(100).totalTokens(300).build();

            UsageInfo result = a.add(b);

            assertThat(result.promptTokens()).isEqualTo(300);
            assertThat(result.completionTokens()).isEqualTo(150);
            assertThat(result.totalTokens()).isEqualTo(450);
        }

        @Test
        @DisplayName("should handle null other")
        void shouldHandleNullOther() {
            UsageInfo a = UsageInfo.builder().promptTokens(100).completionTokens(50).totalTokens(150).build();

            UsageInfo result = a.add(null);

            assertThat(result).isSameAs(a);
        }

        @Test
        @DisplayName("should handle null fields in both")
        void shouldHandleNullFields() {
            UsageInfo a = UsageInfo.builder().build();
            UsageInfo b = UsageInfo.builder().promptTokens(100).build();

            UsageInfo result = a.add(b);

            assertThat(result.promptTokens()).isEqualTo(100);
            // When both sides are null, addNullable returns null
            assertThat(result.completionTokens()).isNull();
            assertThat(result.totalTokens()).isNull();
        }

        @Test
        @DisplayName("should add all 7 fields including extended tokens")
        void shouldAddAllSevenFields() {
            UsageInfo a = UsageInfo.builder()
                    .promptTokens(100).completionTokens(50).totalTokens(150)
                    .cacheCreationInputTokens(10).cacheReadInputTokens(20)
                    .cachedTokens(30).reasoningTokens(40)
                    .build();
            UsageInfo b = UsageInfo.builder()
                    .promptTokens(200).completionTokens(100).totalTokens(300)
                    .cacheCreationInputTokens(5).cacheReadInputTokens(15)
                    .cachedTokens(25).reasoningTokens(35)
                    .build();

            UsageInfo result = a.add(b);

            assertThat(result.promptTokens()).isEqualTo(300);
            assertThat(result.completionTokens()).isEqualTo(150);
            assertThat(result.totalTokens()).isEqualTo(450);
            assertThat(result.cacheCreationInputTokens()).isEqualTo(15);
            assertThat(result.cacheReadInputTokens()).isEqualTo(35);
            assertThat(result.cachedTokens()).isEqualTo(55);
            assertThat(result.reasoningTokens()).isEqualTo(75);
        }

        @Test
        @DisplayName("should handle null extended fields when one side has values")
        void shouldHandlePartialExtendedFields() {
            UsageInfo a = UsageInfo.builder()
                    .promptTokens(100).completionTokens(50).totalTokens(150)
                    .cacheCreationInputTokens(10)
                    .build();
            UsageInfo b = UsageInfo.builder()
                    .promptTokens(200).completionTokens(100).totalTokens(300)
                    .build();

            UsageInfo result = a.add(b);

            assertThat(result.promptTokens()).isEqualTo(300);
            assertThat(result.cacheCreationInputTokens()).isEqualTo(10);
            assertThat(result.cacheReadInputTokens()).isNull();
        }
    }

    @Nested
    @DisplayName("extended token fields")
    class ExtendedFieldTests {

        @Test
        @DisplayName("should store Claude cache tokens")
        void shouldStoreClaudeCacheTokens() {
            UsageInfo usage = UsageInfo.builder()
                    .promptTokens(1000)
                    .completionTokens(500)
                    .totalTokens(1500)
                    .cacheCreationInputTokens(200)
                    .cacheReadInputTokens(800)
                    .build();

            assertThat(usage.cacheCreationInputTokens()).isEqualTo(200);
            assertThat(usage.cacheReadInputTokens()).isEqualTo(800);
            assertThat(usage.cachedTokens()).isNull();
            assertThat(usage.reasoningTokens()).isNull();
        }

        @Test
        @DisplayName("should store OpenAI reasoning/cached tokens")
        void shouldStoreOpenAITokens() {
            UsageInfo usage = UsageInfo.builder()
                    .promptTokens(1000)
                    .completionTokens(500)
                    .totalTokens(1500)
                    .cachedTokens(300)
                    .reasoningTokens(400)
                    .build();

            assertThat(usage.cachedTokens()).isEqualTo(300);
            assertThat(usage.reasoningTokens()).isEqualTo(400);
            assertThat(usage.cacheCreationInputTokens()).isNull();
            assertThat(usage.cacheReadInputTokens()).isNull();
        }

        @Test
        @DisplayName("getTotal should not include extended fields (they are breakdowns)")
        void getTotalShouldNotIncludeExtended() {
            UsageInfo usage = UsageInfo.builder()
                    .promptTokens(1000)
                    .completionTokens(500)
                    .cacheCreationInputTokens(200)
                    .cacheReadInputTokens(800)
                    .reasoningTokens(400)
                    .build();

            // Extended fields are breakdowns of prompt/completion, not additive
            assertThat(usage.getTotal()).isEqualTo(1500);
        }
    }

    @Nested
    @DisplayName("Gemini-specific token fields (Stage 0.3)")
    class GeminiFieldTests {

        @Test
        @DisplayName("stores thoughtsTokenCount and cachedContentTokenCount")
        void storesGeminiFields() {
            UsageInfo usage = UsageInfo.builder()
                    .promptTokens(5000)
                    .completionTokens(500)
                    .thoughtsTokenCount(185)
                    .cachedContentTokenCount(4096)
                    .build();

            assertThat(usage.thoughtsTokenCount()).isEqualTo(185);
            assertThat(usage.cachedContentTokenCount()).isEqualTo(4096);
        }

        @Test
        @DisplayName("add() sums thoughtsTokenCount and cachedContentTokenCount across iterations")
        void addSumsGeminiFields() {
            UsageInfo a = UsageInfo.builder()
                    .thoughtsTokenCount(185).cachedContentTokenCount(4096)
                    .build();
            UsageInfo b = UsageInfo.builder()
                    .thoughtsTokenCount(220).cachedContentTokenCount(1024)
                    .build();

            UsageInfo sum = a.add(b);

            assertThat(sum.thoughtsTokenCount()).isEqualTo(405);
            assertThat(sum.cachedContentTokenCount()).isEqualTo(5120);
        }

        @Test
        @DisplayName("add() preserves null when both sides are null - no false zero")
        void addPreservesNullOnBothNull() {
            UsageInfo a = UsageInfo.builder().promptTokens(10).build();
            UsageInfo b = UsageInfo.builder().promptTokens(20).build();

            UsageInfo sum = a.add(b);

            assertThat(sum.thoughtsTokenCount()).isNull();
            assertThat(sum.cachedContentTokenCount()).isNull();
        }

        @Test
        @DisplayName("getTotal() does NOT include thoughts - Gemini bills thoughts as output cost separately")
        void getTotalExcludesThoughts() {
            UsageInfo usage = UsageInfo.builder()
                    .promptTokens(1000).completionTokens(500).totalTokens(1500)
                    .thoughtsTokenCount(185)
                    .build();

            // thoughts is a breakdown of output cost, not additive to total
            assertThat(usage.getTotal()).isEqualTo(1500);
        }

        @Test
        @DisplayName("positional constructor accepts the nine components in declared order")
        void positionalConstructorOrder() {
            UsageInfo u = new UsageInfo(
                1, 2, 3,          // prompt, completion, total
                4, 5,             // cacheCreation, cacheRead (Claude)
                6, 7,             // cached, reasoning (OpenAI)
                8, 9              // thoughts, cachedContent (Gemini) - Stage 0.3
            );
            assertThat(u.thoughtsTokenCount()).isEqualTo(8);
            assertThat(u.cachedContentTokenCount()).isEqualTo(9);
        }
    }
}
