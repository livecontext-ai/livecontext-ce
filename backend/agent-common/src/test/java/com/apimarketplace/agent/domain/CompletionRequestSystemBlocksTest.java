package com.apimarketplace.agent.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CompletionRequestSystemBlocksTest {

    @Test
    @DisplayName("hasSystemBlocks() is false when systemBlocks is null")
    void hasSystemBlocksFalseWhenNull() {
        CompletionRequest req = CompletionRequest.builder().userPrompt("hi").build();

        assertThat(req.hasSystemBlocks()).isFalse();
    }

    @Test
    @DisplayName("hasSystemBlocks() is false when systemBlocks is empty")
    void hasSystemBlocksFalseWhenEmpty() {
        CompletionRequest req = CompletionRequest.builder()
            .userPrompt("hi")
            .systemBlocks(Collections.emptyList())
            .build();

        assertThat(req.hasSystemBlocks()).isFalse();
    }

    @Test
    @DisplayName("hasSystemBlocks() is true when any block is present - even a single blank one")
    void hasSystemBlocksTrueForNonEmptyList() {
        CompletionRequest req = CompletionRequest.builder()
            .systemBlocks(List.of(SystemBlock.of("")))
            .build();

        assertThat(req.hasSystemBlocks()).isTrue();
    }

    @Test
    @DisplayName("effectiveSystemPrompt() returns the legacy systemPrompt when no blocks are set")
    void effectivePromptFallsBackToLegacyString() {
        CompletionRequest req = CompletionRequest.builder()
            .systemPrompt("legacy")
            .build();

        assertThat(req.effectiveSystemPrompt()).isEqualTo("legacy");
    }

    @Test
    @DisplayName("effectiveSystemPrompt() returns null when both inputs are unset")
    void effectivePromptNullWhenBothUnset() {
        CompletionRequest req = CompletionRequest.builder().build();

        assertThat(req.effectiveSystemPrompt()).isNull();
    }

    @Test
    @DisplayName("effectiveSystemPrompt() concatenates blocks with \\n\\n separator")
    void effectivePromptConcatenatesBlocks() {
        CompletionRequest req = CompletionRequest.builder()
            .systemBlocks(List.of(
                SystemBlock.breakpoint("base"),
                SystemBlock.of("middle"),
                SystemBlock.breakpoint("skills")
            ))
            .build();

        assertThat(req.effectiveSystemPrompt()).isEqualTo("base\n\nmiddle\n\nskills");
    }

    @Test
    @DisplayName("effectiveSystemPrompt() skips blank blocks so optional sections don't leave stray separators")
    void effectivePromptSkipsBlankBlocks() {
        CompletionRequest req = CompletionRequest.builder()
            .systemBlocks(List.of(
                SystemBlock.of("base"),
                SystemBlock.of(""),
                SystemBlock.of("   "),
                SystemBlock.of("tail")
            ))
            .build();

        assertThat(req.effectiveSystemPrompt()).isEqualTo("base\n\ntail");
    }

    @Test
    @DisplayName("effectiveSystemPrompt() prefers systemBlocks over the legacy systemPrompt when both are set")
    void effectivePromptPrefersBlocksOverLegacy() {
        CompletionRequest req = CompletionRequest.builder()
            .systemPrompt("legacy-should-be-ignored")
            .systemBlocks(List.of(SystemBlock.of("from-blocks")))
            .build();

        assertThat(req.effectiveSystemPrompt()).isEqualTo("from-blocks");
    }

    @Test
    @DisplayName("effectiveSystemPrompt() returns empty string when all blocks are blank")
    void effectivePromptEmptyWhenAllBlocksBlank() {
        CompletionRequest req = CompletionRequest.builder()
            .systemBlocks(List.of(SystemBlock.of(""), SystemBlock.of("   ")))
            .build();

        assertThat(req.effectiveSystemPrompt()).isEmpty();
    }
}
