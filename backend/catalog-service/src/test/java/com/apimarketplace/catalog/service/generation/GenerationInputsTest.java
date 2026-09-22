package com.apimarketplace.catalog.service.generation;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What the two model listings tell a caller about the files a model takes.
 *
 * <p>This block is the only machine-readable statement of what a file IS to a model and of the two
 * ways a call using it can be refused for free. Everything it omits, a caller can only learn by
 * making the call: the agent's and the app's listings both read it, and a rule enforced by the
 * builder but absent from here is a refusal nobody could have avoided.
 */
class GenerationInputsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static GenerationSpec spec(String json) {
        try {
            return GenerationSpec.parse(MAPPER.readTree(json), "test").orElseThrow();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** Seedance's shape: a pair of frames, references, and the two rules between them. */
    private static final GenerationSpec SEEDANCE = spec("""
            {
              "kind": "video", "modelParam": "model", "assetPath": "content.video_url",
              "paramMap": {
                "prompt": "content[0].text",
                "first_frame_image": {
                  "path": "content[1].image_url.url", "encoding": "data_url", "role": "first_frame",
                  "excludes": ["input_image"]
                },
                "last_frame_image": {
                  "path": "content[2].image_url.url", "encoding": "data_url", "role": "last_frame",
                  "requires": ["first_frame_image"], "excludes": ["input_image"]
                },
                "input_image": {
                  "path": "content[3].image_url.url", "encoding": "data_url", "role": "reference",
                  "maxItems": 4
                }
              },
              "models": [
                {"id": "frames-and-refs", "upstream": "a",
                 "capabilities": ["prompt", "first_frame_image", "last_frame_image", "input_image"]},
                {"id": "refs-only", "upstream": "b", "capabilities": ["prompt", "input_image"]}
              ]
            }
            """);

    private static GenerationSpec.Model model(String id) {
        return SEEDANCE.models().stream().filter(m -> m.id().equals(id)).findFirst().orElseThrow();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> slot(String modelId, String param) {
        return (Map<String, Object>) GenerationInputs.describe(SEEDANCE, model(modelId)).get(param);
    }

    @Test
    @DisplayName("each slot says what its file IS and how many it takes")
    void describesRoleAndCount() {
        assertThat(slot("frames-and-refs", "first_frame_image"))
                .containsEntry("role", "first_frame")
                .containsEntry("maxItems", 1);
        assertThat(slot("frames-and-refs", "input_image"))
                .containsEntry("role", "reference")
                .containsEntry("maxItems", 4);
    }

    @Test
    @DisplayName("a slot that only works as a pair says so, rather than leaving it to a refusal")
    void publishesThePair() {
        // The builder refuses the half-pair for free; without this line the caller has no way to
        // know that before spending the round trip, and the app has nothing to draw.
        assertThat(slot("frames-and-refs", "last_frame_image"))
                .containsEntry("requires", List.of("first_frame_image"));
        assertThat(slot("frames-and-refs", "first_frame_image")).doesNotContainKey("requires");
    }

    @Test
    @DisplayName("slots that cannot travel together say so from BOTH sides of the pair")
    void publishesTheExclusionSymmetrically() {
        // Declared once, on the frames. The reference slot has to carry it too, or a surface
        // drawing from the reference side offers a combination the provider treats as a different
        // kind of request altogether.
        assertThat(slot("frames-and-refs", "first_frame_image"))
                .containsEntry("excludes", List.of("input_image"));
        assertThat(slot("frames-and-refs", "input_image"))
                .containsEntry("excludes", List.of("first_frame_image", "last_frame_image"));
    }

    @Test
    @DisplayName("a rule naming a slot this model does not have is not reported on it")
    void narrowsToWhatThisModelTakes() {
        // refs-only takes no frame, so it can never form the forbidden pair: telling its caller
        // about one sends them to add a parameter that would be refused on its own terms.
        assertThat(slot("refs-only", "input_image"))
                .containsEntry("role", "reference")
                .doesNotContainKey("excludes")
                .doesNotContainKey("requires");
    }

    @Test
    @DisplayName("a model that takes no file at all describes nothing, rather than an empty shape")
    void saysNothingWhenThereIsNothingToSay() {
        GenerationSpec textOnly = spec("""
                {
                  "kind": "image", "assetPath": "data[0].url",
                  "paramMap": {"prompt": "prompt"},
                  "models": [{"id": "t-1", "capabilities": ["prompt"]}]
                }
                """);
        assertThat(GenerationInputs.describe(textOnly, textOnly.models().get(0))).isEmpty();
    }

    @Test
    @DisplayName("no descriptor at all is an empty block, not a failure")
    void toleratesAbsence() {
        assertThat(GenerationInputs.describe(null, model("refs-only"))).isEmpty();
        assertThat(GenerationInputs.describe(SEEDANCE, null)).isEmpty();
    }
}
