package com.apimarketplace.agent.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SystemBlockTest {

    @Test
    @DisplayName("of() produces a non-breakpoint block with the given text")
    void ofFactoryProducesNonBreakpointBlock() {
        SystemBlock block = SystemBlock.of("hello");

        assertThat(block.text()).isEqualTo("hello");
        assertThat(block.cacheBreakpoint()).isFalse();
    }

    @Test
    @DisplayName("breakpoint() produces a breakpoint-closing block with the given text")
    void breakpointFactoryProducesBreakpointBlock() {
        SystemBlock block = SystemBlock.breakpoint("cache-me");

        assertThat(block.text()).isEqualTo("cache-me");
        assertThat(block.cacheBreakpoint()).isTrue();
    }

    @Test
    @DisplayName("Canonical constructor rejects null text - callers must emit \"\" for empty sections")
    void constructorRejectsNullText() {
        assertThatThrownBy(() -> new SystemBlock(null, false))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("text");
    }

    @Test
    @DisplayName("Empty text is allowed (optional sections emit empty blocks the serializer skips)")
    void emptyTextIsAllowed() {
        SystemBlock block = SystemBlock.of("");

        assertThat(block.text()).isEmpty();
        assertThat(block.isBlank()).isTrue();
    }

    @Test
    @DisplayName("isBlank() returns true for whitespace-only text")
    void isBlankDetectsWhitespace() {
        assertThat(SystemBlock.of("   \n\t ").isBlank()).isTrue();
        assertThat(SystemBlock.of("x").isBlank()).isFalse();
    }
}
