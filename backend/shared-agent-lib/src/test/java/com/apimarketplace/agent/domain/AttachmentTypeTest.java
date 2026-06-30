package com.apimarketplace.agent.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for AttachmentType enum.
 */
@DisplayName("AttachmentType")
class AttachmentTypeTest {

    @Test
    @DisplayName("should have 4 attachment types")
    void shouldHaveFourTypes() {
        assertThat(AttachmentType.values()).hasSize(4);
    }

    @Test
    @DisplayName("should contain expected types")
    void shouldContainExpectedTypes() {
        assertThat(AttachmentType.values())
                .containsExactly(
                        AttachmentType.IMAGE,
                        AttachmentType.PDF,
                        AttachmentType.TEXT,
                        AttachmentType.OTHER
                );
    }

    @Test
    @DisplayName("should support valueOf for all types")
    void shouldSupportValueOf() {
        assertThat(AttachmentType.valueOf("IMAGE")).isEqualTo(AttachmentType.IMAGE);
        assertThat(AttachmentType.valueOf("PDF")).isEqualTo(AttachmentType.PDF);
        assertThat(AttachmentType.valueOf("TEXT")).isEqualTo(AttachmentType.TEXT);
        assertThat(AttachmentType.valueOf("OTHER")).isEqualTo(AttachmentType.OTHER);
    }
}
