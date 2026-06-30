package com.apimarketplace.agent.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Coverage for {@link ModelCategory} - known categories, lookup, and the
 * shape-validation contract that mirrors the V156 DB CHECK constraint.
 */
@DisplayName("ModelCategory - known categories + permissive shape contract")
class ModelCategoryTest {

    @Test
    @DisplayName("Known categories cover the three V156 seed values")
    void knownCategoriesMatchV156Seed() {
        assertThat(ModelCategory.CHAT.key()).isEqualTo("chat");
        assertThat(ModelCategory.BROWSER_AGENT.key()).isEqualTo("browser_agent");
        assertThat(ModelCategory.IMAGE_GENERATION.key()).isEqualTo("image_generation");

        assertThat(ModelCategory.defaultKeys())
                .containsExactlyInAnyOrder("chat", "browser_agent", "image_generation");
    }

    @Test
    @DisplayName("of() round-trips the canonical keys")
    void ofRoundTripsCanonicalKeys() {
        assertThat(ModelCategory.of("chat")).isEqualTo(ModelCategory.CHAT);
        assertThat(ModelCategory.of("browser_agent")).isEqualTo(ModelCategory.BROWSER_AGENT);
        assertThat(ModelCategory.of("image_generation")).isEqualTo(ModelCategory.IMAGE_GENERATION);
    }

    @Test
    @DisplayName("of() rejects unknown keys with IllegalArgumentException")
    void ofRejectsUnknownKey() {
        assertThatThrownBy(() -> ModelCategory.of("video_generation"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ModelCategory.of("CHAT"))
                .isInstanceOf(IllegalArgumentException.class); // case-sensitive
    }

    @Test
    @DisplayName("isValidShape accepts any future lowercase snake_case category (extensible)")
    void shapeValidationAcceptsForwardCompatibleKeys() {
        assertThat(ModelCategory.isValidShape("chat")).isTrue();
        assertThat(ModelCategory.isValidShape("video_generation")).isTrue();
        assertThat(ModelCategory.isValidShape("file_processing")).isTrue();
        assertThat(ModelCategory.isValidShape("embedding")).isTrue();
        assertThat(ModelCategory.isValidShape("a")).isTrue();
        assertThat(ModelCategory.isValidShape("a1_b2")).isTrue();
    }

    @Test
    @DisplayName("acceptsMode - chat & browser_agent only accept chat-eligible rows (mode null or 'chat')")
    void modeEligibilityChatAndBrowserAgent() {
        // Chat-capable rows are kept.
        assertThat(ModelCategory.acceptsMode("chat", null)).isTrue();
        assertThat(ModelCategory.acceptsMode("chat", "chat")).isTrue();
        assertThat(ModelCategory.acceptsMode("browser_agent", null)).isTrue();
        assertThat(ModelCategory.acceptsMode("browser_agent", "chat")).isTrue();
        // Image-gen rows must NOT leak into chat / browser_agent.
        assertThat(ModelCategory.acceptsMode("chat", "image")).isFalse();
        assertThat(ModelCategory.acceptsMode("browser_agent", "image")).isFalse();
        assertThat(ModelCategory.acceptsMode("chat", "embedding")).isFalse();
        assertThat(ModelCategory.acceptsMode("chat", "audio")).isFalse();
    }

    @Test
    @DisplayName("acceptsMode - image_generation accepts ONLY mode='image' rows (chat models filtered out)")
    void modeEligibilityImageGeneration() {
        assertThat(ModelCategory.acceptsMode("image_generation", "image")).isTrue();
        // The bug the user reported: chat-capable rows must NOT appear in the image tab.
        assertThat(ModelCategory.acceptsMode("image_generation", "chat")).isFalse();
        assertThat(ModelCategory.acceptsMode("image_generation", null)).isFalse();
        assertThat(ModelCategory.acceptsMode("image_generation", "embedding")).isFalse();
    }

    @Test
    @DisplayName("acceptsMode - unknown / future categories are permissive until explicitly tightened")
    void modeEligibilityUnknownCategoryPermissive() {
        // Forward-compat: a new category (e.g. video_generation) seeded into
        // the sidecar without a code change here MUST surface its rows until
        // someone adds an explicit mode predicate. Returning false would
        // silently hide every row for that category.
        assertThat(ModelCategory.acceptsMode("video_generation", "video")).isTrue();
        assertThat(ModelCategory.acceptsMode("video_generation", null)).isTrue();
        // null category = legacy global path; everything passes through.
        assertThat(ModelCategory.acceptsMode(null, "anything")).isTrue();
    }

    @Test
    @DisplayName("isValidShape rejects empty, null, mixed-case, leading-digit, special-char, or oversize keys (matches V156 CHECK)")
    void shapeValidationRejectsInvalidKeys() {
        assertThat(ModelCategory.isValidShape(null)).isFalse();
        assertThat(ModelCategory.isValidShape("")).isFalse();
        assertThat(ModelCategory.isValidShape("Chat")).isFalse();         // uppercase
        assertThat(ModelCategory.isValidShape("1category")).isFalse();    // leading digit
        assertThat(ModelCategory.isValidShape("with-dash")).isFalse();    // hyphen
        assertThat(ModelCategory.isValidShape("with space")).isFalse();   // space
        assertThat(ModelCategory.isValidShape("with.dot")).isFalse();     // dot
        // 33 chars - one over the V156 length limit
        assertThat(ModelCategory.isValidShape("a".repeat(33))).isFalse();
    }
}
