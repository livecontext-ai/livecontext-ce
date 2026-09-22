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
    @DisplayName("Known categories cover the V156 seed values plus one key per generation format")
    void knownCategoriesMatchV156Seed() {
        assertThat(ModelCategory.CHAT.key()).isEqualTo("chat");
        assertThat(ModelCategory.BROWSER_AGENT.key()).isEqualTo("browser_agent");
        assertThat(ModelCategory.IMAGE_GENERATION.key()).isEqualTo("image_generation");
        assertThat(ModelCategory.VIDEO_GENERATION.key()).isEqualTo("video_generation");

        assertThat(ModelCategory.CLASSIFICATION.key()).isEqualTo("classification");

        assertThat(ModelCategory.defaultKeys())
                .containsExactlyInAnyOrder("chat", "browser_agent", "classification",
                        "image_generation", "video_generation", "audio_generation",
                        "voice_generation", "music_generation");
    }

    @Test
    @DisplayName("a generation category derives its mode from its own name, so adding a format is free")
    void generationModeIsDerivedFromTheName() {
        assertThat(ModelCategory.modeForGenerationCategory("video_generation")).isEqualTo("video");
        assertThat(ModelCategory.modeForGenerationCategory("image_generation")).isEqualTo("image");
        // A format nobody has added yet still resolves correctly.
        assertThat(ModelCategory.modeForGenerationCategory("slides_generation")).isEqualTo("slides");
        assertThat(ModelCategory.modeForGenerationCategory("chat")).isNull();
        assertThat(ModelCategory.modeForGenerationCategory("_generation")).isNull();
        assertThat(ModelCategory.modeForGenerationCategory(null)).isNull();
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
        assertThatThrownBy(() -> ModelCategory.of("slides_generation"))
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
        // The bug the user reported: chat-capable rows must NOT be admitted by an image category.
        assertThat(ModelCategory.acceptsMode("image_generation", "chat")).isFalse();
        assertThat(ModelCategory.acceptsMode("image_generation", null)).isFalse();
        assertThat(ModelCategory.acceptsMode("image_generation", "embedding")).isFalse();
    }

    @Test
    @DisplayName("acceptsMode - every generation category admits its own format and nothing else")
    void modeEligibilityGenerationCategories() {
        assertThat(ModelCategory.acceptsMode("video_generation", "video")).isTrue();
        assertThat(ModelCategory.acceptsMode("audio_generation", "audio")).isTrue();
        assertThat(ModelCategory.acceptsMode("voice_generation", "voice")).isTrue();
        assertThat(ModelCategory.acceptsMode("music_generation", "music")).isTrue();

        // The formats must not bleed into each other: a voice model admitted
        // by video_generation would be offered for a job it cannot do.
        assertThat(ModelCategory.acceptsMode("video_generation", "image")).isFalse();
        assertThat(ModelCategory.acceptsMode("video_generation", "chat")).isFalse();
        assertThat(ModelCategory.acceptsMode("video_generation", null)).isFalse();
        assertThat(ModelCategory.acceptsMode("music_generation", "audio")).isFalse();
    }

    @Test
    @DisplayName("acceptsMode - a decision model is barred from every conversational surface")
    void decisionModelIsBarredFromConversationalSurfaces() {
        // The cloisonnement that matters: a model that cannot emit prose must never be
        // offered where a conversation is expected. chat / browser_agent admit only
        // mode null or 'chat', and ModelCatalogService resolves the category-less global
        // path (chat picker, flat model list, default-model pick) as 'chat' - so this one
        // assertion is what keeps Jev out of all of them.
        assertThat(ModelCategory.acceptsMode("chat", ModelCategory.DECISION_MODE)).isFalse();
        assertThat(ModelCategory.acceptsMode("browser_agent", ModelCategory.DECISION_MODE)).isFalse();
    }

    @Test
    @DisplayName("acceptsMode - classification admits ONLY decision models, never a chat model")
    void modeEligibilityClassification() {
        assertThat(ModelCategory.acceptsMode("classification", ModelCategory.DECISION_MODE)).isTrue();

        // Without the explicit branch, the permissive tail below would admit every one of
        // these: the classification tab would list gpt-5 beside jev-latest and rank them
        // against each other. A new category that forgets its branch fails exactly here.
        assertThat(ModelCategory.acceptsMode("classification", "chat")).isFalse();
        assertThat(ModelCategory.acceptsMode("classification", null)).isFalse();
        assertThat(ModelCategory.acceptsMode("classification", "image")).isFalse();
        assertThat(ModelCategory.acceptsMode("classification", "embedding")).isFalse();
    }

    @Test
    @DisplayName("the decision mode is not a generation format, so it never picks up that convention")
    void decisionModeIsNotAGenerationFormat() {
        assertThat(ModelCategory.isGeneration("classification")).isFalse();
        assertThat(ModelCategory.modeForGenerationCategory("classification")).isNull();
        assertThat(ModelCategory.DECISION_MODE).isEqualTo("decision");
    }

    @Test
    @DisplayName("acceptsMode - a format nobody has added yet already works, without a code change")
    void modeEligibilityFutureGenerationCategory() {
        // The convention is the point: seeding 'slides_generation' into the
        // sidecar needs no edit here for its rows to be filtered correctly.
        assertThat(ModelCategory.acceptsMode("slides_generation", "slides")).isTrue();
        assertThat(ModelCategory.acceptsMode("slides_generation", "video")).isFalse();
    }

    @Test
    @DisplayName("acceptsMode - a non-generation unknown category stays permissive rather than hiding rows")
    void modeEligibilityUnknownCategoryPermissive() {
        // Returning false for a category nobody taught this class about would
        // silently hide every one of its rows, which is worse than showing too
        // much.
        assertThat(ModelCategory.acceptsMode("file_processing", "anything")).isTrue();
        assertThat(ModelCategory.acceptsMode("embedding", null)).isTrue();
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
