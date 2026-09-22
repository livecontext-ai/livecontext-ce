package com.apimarketplace.catalog.service.generation;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Several priced models behind ONE upstream name.
 *
 * <p><b>The undercharge.</b> A tier the caller must not be able to move is pinned with `constants`
 * and sold as a model of its own, so `seedance-2.0`, `-480p`, `-1080p` and `-4k` are four public
 * ids at 152, 70, 374 and 778 credits a second - and one upstream selector,
 * `dreamina-seedance-2-0-260128`. The relay resolved the model by that selector and took the FIRST
 * match, so every relayed call on the group was priced as whichever variant happened to be declared
 * first: a 4K render billed at the 720p rate, 152 instead of 778, and a 480p one billed at more
 * than twice what it costs. Both are real models and both amounts look ordinary, so nothing
 * anywhere reported it.
 *
 * <p>The pins are in the body, written there by the builder, so they are what tells the variants
 * apart - the same idea as the itemConstants that tell two file slots apart.
 */
class RelayedVariantSelectionTest {

    /** Its own, because a sibling top-level class cannot reach the other one's private helper. */
    private static GenerationSpec spec(String json) {
        try {
            return GenerationSpec.parse(new ObjectMapper().readTree(json), "test").orElseThrow();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static GenerationSpec tiered() {
        return spec("""
                {
                  "kind": "video", "modelParam": "model", "assetPath": "content.video_url",
                  "paramMap": { "prompt": "content[0].text", "duration_seconds": "duration" },
                  "models": [
                    {
                      "id": "tier-720p", "upstream": "vendor-shared", "label": "720p",
                      "capabilities": ["prompt", "duration_seconds"],
                      "constants": { "resolution": "720p" },
                      "price": { "unit": "second", "unitCredits": 152 }
                    },
                    {
                      "id": "tier-4k", "upstream": "vendor-shared", "label": "4K",
                      "capabilities": ["prompt", "duration_seconds"],
                      "constants": { "resolution": "4k" },
                      "price": { "unit": "second", "unitCredits": 778 }
                    }
                  ]
                }
                """);
    }

    /** One model behind its upstream name: the ordinary endpoint, which must be unaffected. */
    private static GenerationSpec single() {
        return spec("""
                {
                  "kind": "video", "modelParam": "model", "assetPath": "content.video_url",
                  "paramMap": { "prompt": "content[0].text", "duration_seconds": "duration" },
                  "models": [{
                    "id": "only-one", "upstream": "vendor-one", "label": "Only",
                    "capabilities": ["prompt", "duration_seconds"],
                    "constants": { "resolution": "720p" },
                    "price": { "unit": "second", "unitCredits": 100 }
                  }]
                }
                """);
    }

    /** Two variants told apart by a NUMERIC pin, which JSON respells freely. */
    private static GenerationSpec numeric() {
        return spec("""
                {
                  "kind": "video", "modelParam": "model", "assetPath": "content.video_url",
                  "paramMap": { "prompt": "content[0].text", "duration_seconds": "duration" },
                  "models": [
                    {
                      "id": "num-720", "upstream": "vendor-num", "label": "720",
                      "capabilities": ["prompt", "duration_seconds"],
                      "constants": { "resolution": 720 },
                      "price": { "unit": "second", "unitCredits": 100 }
                    },
                    {
                      "id": "num-1080", "upstream": "vendor-num", "label": "1080",
                      "capabilities": ["prompt", "duration_seconds"],
                      "constants": { "resolution": 1080 },
                      "price": { "unit": "second", "unitCredits": 300 }
                    }
                  ]
                }
                """);
    }

    private static Map<String, Object> bodyAt(String resolution) {
        return Map.of(
                "model", "vendor-shared",
                "resolution", resolution,
                "duration", 10,
                "content", List.of(Map.of("text", "a cat")));
    }

    @Test
    @DisplayName("the DEARER variant is recognised, not the first one sharing its upstream name")
    void theDearVariantIsPricedAsItself() {
        // 10 seconds at 778 rather than at 152: the whole of the difference this test exists for.
        assertThat(RelayedGenerationMeasurement.measure(tiered(), bodyAt("4k")).modelId())
                .isEqualTo("tier-4k");
    }

    @Test
    @DisplayName("the cheaper variant is recognised too, so the fix is not a bias toward the dear one")
    void theCheapVariantIsPricedAsItself() {
        // The mirror, and it is not a formality: reading 480p as 720p OVERCHARGES, which is the
        // half a customer notices and disputes.
        assertThat(RelayedGenerationMeasurement.measure(tiered(), bodyAt("720p")).modelId())
                .isEqualTo("tier-720p");
    }

    @Test
    @DisplayName("an unrecognised pin among SEVERAL variants is refused, not billed as the cheapest")
    void anUnrecognisedPinAmongVariantsIsRefused() {
        // This asserted the opposite - that it falls back to the first upstream match - and on the
        // shipped Seedance seed that first match is the CHEAPEST of four: an unrecognised pin would
        // be billed 152 credits a second for a call that may be 778, silently, on a body this
        // platform may not have built. With several variants behind one upstream name there is no
        // defensible fallback, so the call is reported unpriceable and refused, which is the
        // direction taken everywhere else a generation cannot be priced.
        assertThat(RelayedGenerationMeasurement.measure(tiered(), bodyAt("8k")).modelId())
                .isNull();
    }

    @Test
    @DisplayName("a pin is matched however it is CASED, so a body written 4K finds the 4k variant")
    void pinsAreMatchedThroughTheSameNormalisationAsAPricedValue() {
        // Compared with String.equals once, while a priced VALUE went through normalizeKey: two
        // rules for the same kind of value. A body written "4K" matched nothing and fell through to
        // the cheap variant, which is the exact undercharge the test above now refuses.
        assertThat(RelayedGenerationMeasurement.measure(tiered(), bodyAt("4K")).modelId())
                .isEqualTo("tier-4k");
    }

    @Test
    @DisplayName("a NUMERIC pin survives the respellings a JSON round trip produces")
    void numericPinsAreComparedAsNumbers() {
        // 1080, "1080", "1080.0" and 1080.0 are one choice and were four different models to
        // String.equals. A body that came back through any serialiser could therefore match
        // nothing and, before the test above, be billed as the cheap variant.
        for (Object written : new Object[] { 1080, "1080", "1080.0", 1080.0 }) {
            assertThat(RelayedGenerationMeasurement.measure(numeric(), Map.of(
                    "model", "vendor-num", "resolution", written, "duration", 10,
                    "content", List.of(Map.of("text", "a cat")))).modelId())
                    .as("resolution written as " + written)
                    .isEqualTo("num-1080");
        }
    }

    @Test
    @DisplayName("a SINGLE model behind an upstream name still answers, pins or no pins")
    void oneVariantIsUnaffected() {
        // The half that must not change: with nothing to be ambiguous about, an endpoint answers
        // exactly as it did before variants were told apart. Refusing here would take every
        // ordinary single-model generation down with the fix.
        assertThat(RelayedGenerationMeasurement.measure(single(), Map.of(
                "model", "vendor-one", "resolution", "something-else", "duration", 10,
                "content", List.of(Map.of("text", "a cat")))).modelId())
                .isEqualTo("only-one");
    }

    @Test
    @DisplayName("a PINNED variant beats an unpinned one sharing its upstream, however they are ordered")
    void theMostSpecificVariantWins() {
        // A model with no pins matches vacuously, so "first match" let an unpinned variant shadow a
        // pinned one: Stability's sd3.5-large shadowed sd3.5-large-img2img, which is told apart
        // only by the `mode` it pins. Those two cost the same today, which is precisely why it
        // would have gone unnoticed until one of them was repriced.
        GenerationSpec withUnpinnedFirst = spec("""
                {
                  "kind": "image", "modelParam": "model", "assetPath": "data[0].url",
                  "paramMap": { "prompt": "prompt" },
                  "models": [
                    {
                      "id": "plain", "upstream": "vendor-img", "label": "Plain",
                      "capabilities": ["prompt"],
                      "price": { "unit": "call", "baseCredits": 130 }
                    },
                    {
                      "id": "img2img", "upstream": "vendor-img", "label": "Img2img",
                      "capabilities": ["prompt"],
                      "constants": { "mode": "image-to-image" },
                      "price": { "unit": "call", "baseCredits": 130 }
                    }
                  ]
                }
                """);

        assertThat(RelayedGenerationMeasurement.measure(withUnpinnedFirst,
                Map.of("model", "vendor-img", "mode", "image-to-image", "prompt", "a cat")).modelId())
                .isEqualTo("img2img");
        // And the unpinned one is still reachable when the body carries no pin.
        assertThat(RelayedGenerationMeasurement.measure(withUnpinnedFirst,
                Map.of("model", "vendor-img", "prompt", "a cat")).modelId())
                .isEqualTo("plain");
    }
}
