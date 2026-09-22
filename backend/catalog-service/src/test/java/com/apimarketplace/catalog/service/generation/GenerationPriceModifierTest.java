package com.apimarketplace.catalog.service.generation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Price modifiers: what a call's own CHOICES do to the rate its model is
 * published at.
 *
 * <p>Every refusal here has one symptom when it is missing, and it is the same
 * one: a price quoted and charged at the reference tier for a call that asked
 * for the expensive option. Nothing in a response shows it, so the parser is
 * the only place it can be caught, which is why the malformed cases carry as
 * many tests as the arithmetic.
 */
class GenerationPriceModifierTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static JsonNode json(String s) {
        try {
            return MAPPER.readTree(s);
        } catch (Exception e) {
            throw new IllegalStateException("bad test fixture", e);
        }
    }

    /**
     * A video endpoint shaped like the shipped ones: a per-second price, a
     * caller-chosen resolution, and an optional file slot that takes up to
     * three images.
     */
    private static String descriptor(String priceBlock) {
        return """
                {
                  "kind": "video",
                  "modelParam": "model",
                  "assetPath": "content.video_url",
                  "paramMap": {
                    "prompt": "content[0].text",
                    "duration_seconds": "duration",
                    "resolution": "resolution",
                    "reference_image": {
                      "path": "content[1].image_url.url",
                      "encoding": "data_url",
                      "role": "reference",
                      "maxItems": 3
                    },
                    "input_image": { "path": "image.url", "encoding": "data_url", "role": "source" }
                  },
                  "models": [{
                    "id": "demo-1.0",
                    "upstream": "vendor-demo-1",
                    "capabilities": ["prompt", "duration_seconds", "resolution", "reference_image",
                                     "input_image"],
                    "required": ["resolution"],
                    "constraints": {
                      "duration_seconds": { "allowed": [5, 10] },
                      "resolution": { "allowed": ["480p", "720p", "1080p"] }
                    },
                    "price": %s
                  }]
                }
                """.formatted(priceBlock);
    }

    private static GenerationSpec.Model parseModel(String priceBlock) {
        return GenerationSpec.parse(json(descriptor(priceBlock)), "test").orElseThrow()
                .model("demo-1.0").orElseThrow();
    }

    private static void expectRefusal(String priceBlock, String fragment) {
        assertThatThrownBy(() -> parseModel(priceBlock))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(fragment);
    }

    private static final String RESOLUTION_AND_FILES = """
            {
              "unit": "second",
              "unitCredits": 100,
              "modifiers": [
                { "param": "resolution", "multiply": { "480p": 1, "720p": 2, "1080p": 4 } },
                { "param": "reference_image", "perAsset": 0.1 }
              ]
            }
            """;

    // ── the arithmetic ──────────────────────────────────────────────────────

    @Nested
    @DisplayName("the factor a call is charged at")
    class Arithmetic {

        private BigDecimal factorOf(Map<String, Object> supplied) {
            return parseModel(RESOLUTION_AND_FILES).price().factorFor(supplied);
        }

        @Test
        @DisplayName("a model that declares nothing is always at its published rate")
        void noModifiersIsOne() {
            GenerationSpec.Model model = parseModel("{ \"unit\": \"second\", \"unitCredits\": 100 }");
            assertThat(model.price().modifiers()).isEmpty();
            assertThat(model.price().factorFor(Map.of("resolution", "1080p")))
                    .isEqualByComparingTo(BigDecimal.ONE);
        }

        @Test
        @DisplayName("a value reads its declared factor")
        void valueFactor() {
            assertThat(factorOf(Map.of("resolution", "1080p"))).isEqualByComparingTo("4");
        }

        @Test
        @DisplayName("an OMITTED value bills at the reference tier, never at a guess")
        void absentValueIsTheReferenceTier() {
            assertThat(factorOf(Map.of("prompt", "a cat"))).isEqualByComparingTo(BigDecimal.ONE);
        }

        @Test
        @DisplayName("a value is matched however it was written: 1080P is 1080p")
        void valueMatchingIsCaseInsensitive() {
            assertThat(factorOf(Map.of("resolution", " 1080P "))).isEqualByComparingTo("4");
        }

        @Test
        @DisplayName("a numeric value is matched as a NUMBER, so 5.0 finds the entry keyed 5")
        void numericValuesCompareNumerically() {
            // A miss here would bill the reference tier for a call that asked
            // for the expensive option, which is the whole failure this feature
            // exists to remove.
            String spec = """
                    {
                      "kind": "video",
                      "modelParam": "model",
                      "assetPath": "content.video_url",
                      "paramMap": { "prompt": "content[0].text", "quality": "quality" },
                      "models": [{
                        "id": "demo-q",
                        "upstream": "vendor-q",
                        "capabilities": ["prompt", "quality"],
                        "required": ["quality"],
                        "constraints": { "quality": { "allowed": [5, 10] } },
                        "price": { "unit": "call", "baseCredits": 100,
                                   "modifiers": [{ "param": "quality",
                                                   "multiply": { "5": 1, "10": 3 } }] }
                      }]
                    }
                    """;
            GenerationSpec.Model model = GenerationSpec.parse(json(spec), "test").orElseThrow()
                    .model("demo-q").orElseThrow();
            assertThat(model.price().factorFor(Map.of("quality", new BigDecimal("10.0"))))
                    .isEqualByComparingTo("3");
        }

        @Test
        @DisplayName("each file attached adds its declared rate")
        void filesAreCounted() {
            Map<String, Object> supplied = new LinkedHashMap<>();
            supplied.put("reference_image", List.of("file-a", "file-b", "file-c"));
            assertThat(factorOf(supplied)).isEqualByComparingTo("1.3");
        }

        @Test
        @DisplayName("ONE file in a multi-file slot is one file, not a list of none")
        void oneFileCountsAsOne() {
            assertThat(factorOf(Map.of("reference_image", "file-a"))).isEqualByComparingTo("1.1");
        }

        @Test
        @DisplayName("an empty slot costs nothing extra")
        void noFileIsNoSurcharge() {
            assertThat(factorOf(Map.of("reference_image", List.of()))).isEqualByComparingTo(BigDecimal.ONE);
        }

        @Test
        @DisplayName("blank entries in a slot are not files")
        void blankEntriesAreNotFiles() {
            Map<String, Object> supplied = new LinkedHashMap<>();
            supplied.put("reference_image", java.util.Arrays.asList("file-a", "", null));
            assertThat(factorOf(supplied)).isEqualByComparingTo("1.1");
        }

        @Test
        @DisplayName("factors MULTIPLY, so a 1080p call with two images is 4 x 1.2")
        void factorsMultiply() {
            Map<String, Object> supplied = new LinkedHashMap<>();
            supplied.put("resolution", "1080p");
            supplied.put("reference_image", List.of("a", "b"));
            assertThat(factorOf(supplied)).isEqualByComparingTo("4.8");
        }

        @Test
        @DisplayName("nothing supplied at all is the published rate")
        void emptyCallIsTheRate() {
            assertThat(factorOf(Map.of())).isEqualByComparingTo(BigDecimal.ONE);
            assertThat(parseModel(RESOLUTION_AND_FILES).price().factorFor(null))
                    .isEqualByComparingTo(BigDecimal.ONE);
        }

        @Test
        @DisplayName("the explanation names only what actually moved the price")
        void explanationSkipsTheReferenceTier() {
            Map<String, Object> supplied = new LinkedHashMap<>();
            supplied.put("resolution", "480p");            // the reference tier, factor 1
            supplied.put("reference_image", List.of("a"));
            assertThat(parseModel(RESOLUTION_AND_FILES).price().explainFactor(supplied))
                    .containsExactly("reference_image x1.1");
        }
    }

    // ── what the request builder does with it ───────────────────────────────

    @Nested
    @DisplayName("the built request")
    class Built {

        private GenerationRequestBuilder.Built build(Map<String, Object> unified) {
            GenerationSpec spec = GenerationSpec.parse(json(descriptor(RESOLUTION_AND_FILES)), "test")
                    .orElseThrow();
            return GenerationRequestBuilder.build(spec, spec.model("demo-1.0").orElseThrow(), unified);
        }

        @Test
        @DisplayName("carries the factor the call's choices reached")
        void carriesTheFactor() {
            Map<String, Object> unified = new LinkedHashMap<>();
            unified.put("prompt", "a cat");
            unified.put("duration_seconds", 10);
            unified.put("resolution", "1080p");
            GenerationRequestBuilder.Built built = build(unified);

            assertThat(built.ok()).isTrue();
            assertThat(built.priceMultiplier()).isEqualByComparingTo("4");
            assertThat(built.pricedAtBaseRate()).isFalse();
            assertThat(built.priceFactors()).containsExactly("resolution x4");
        }

        @Test
        @DisplayName("leaves the SIZE alone: ten seconds stay ten seconds at any resolution")
        void theQuantityIsNotScaled() {
            Map<String, Object> unified = new LinkedHashMap<>();
            unified.put("prompt", "a cat");
            unified.put("duration_seconds", 10);
            unified.put("resolution", "1080p");
            // Folding the factor into the measurement would have the run report
            // forty seconds of video that no player would show.
            assertThat(build(unified).quantity()).isEqualByComparingTo("10");
            assertThat(build(unified).quantityUnit()).isEqualTo("second");
        }

        @Test
        @DisplayName("a call at the published rate reports 1 and says so")
        void baseRateCall() {
            Map<String, Object> unified = new LinkedHashMap<>();
            unified.put("prompt", "a cat");
            unified.put("duration_seconds", 5);
            unified.put("resolution", "480p");
            GenerationRequestBuilder.Built built = build(unified);

            assertThat(built.priceMultiplier()).isEqualByComparingTo(BigDecimal.ONE);
            assertThat(built.pricedAtBaseRate()).isTrue();
            assertThat(built.priceFactors()).isEmpty();
        }
    }

    @Nested
    @DisplayName("what the two listings say about a factor")
    class Described {

        @Test
        @DisplayName("ships the RULE, not one result of it")
        void shipsTheTable() {
            // The surfaces apply it to what is currently in the form and ask the quote for the
            // total, so they need the table. Shipping a computed number instead would be a price
            // for a call nobody has configured yet.
            Map<String, Object> described = com.apimarketplace.catalog.tools.generation.GenerationModule
                    .describeModifiers(parseModel(RESOLUTION_AND_FILES).price());

            assertThat(described).containsOnlyKeys("resolution", "reference_image");
            assertThat(described.get("resolution")).isInstanceOf(Map.class);
            assertThat(((Map<?, ?>) described.get("resolution")).get("by_value"))
                    .isEqualTo(Map.of("480p", new BigDecimal("1"),
                            "720p", new BigDecimal("2"),
                            "1080p", new BigDecimal("4")));
            assertThat(((Map<?, ?>) described.get("reference_image")).get("per_file"))
                    .isEqualTo(new BigDecimal("0.1"));
        }

        @Test
        @DisplayName("says nothing at all for a model whose price depends only on its size")
        void saysNothingWithoutModifiers() {
            assertThat(com.apimarketplace.catalog.tools.generation.GenerationModule.describeModifiers(
                    parseModel("{ \"unit\": \"second\", \"unitCredits\": 100 }").price())).isEmpty();
            assertThat(com.apimarketplace.catalog.tools.generation.GenerationModule
                    .describeModifiers(null)).isEmpty();
        }
    }

    // ── the refusals, all of which are silent mis-charges when missing ──────

    @Nested
    @DisplayName("a descriptor the parser refuses")
    class Refusals {

        @Test
        @DisplayName("a factor on a parameter the model does not accept could never apply")
        void unknownCapability() {
            expectRefusal("""
                    { "unit": "second", "unitCredits": 100,
                      "modifiers": [{ "param": "voice", "multiply": { "a": 1 } }] }
                    """, "not one of this model's capabilities");
        }

        @Test
        @DisplayName("a factor on the parameter the price already multiplies charges it twice")
        void measuringParam() {
            expectRefusal("""
                    { "unit": "second", "unitCredits": 100,
                      "modifiers": [{ "param": "duration_seconds", "multiply": { "5": 1, "10": 2 } }] }
                    """, "already sold BY");
        }

        @Test
        @DisplayName("two entries for one parameter would multiply together")
        void duplicateParam() {
            expectRefusal("""
                    { "unit": "second", "unitCredits": 100,
                      "modifiers": [
                        { "param": "resolution", "multiply": { "480p": 1, "720p": 2, "1080p": 4 } },
                        { "param": "resolution", "multiply": { "480p": 1, "720p": 3, "1080p": 5 } }
                      ] }
                    """, "twice");
        }

        @Test
        @DisplayName("a modifier that is neither a value factor nor a file rate")
        void neitherShape() {
            expectRefusal("""
                    { "unit": "second", "unitCredits": 100,
                      "modifiers": [{ "param": "resolution" }] }
                    """, "exactly one of");
        }

        @Test
        @DisplayName("a modifier that is BOTH")
        void bothShapes() {
            expectRefusal("""
                    { "unit": "second", "unitCredits": 100,
                      "modifiers": [{ "param": "reference_image", "perAsset": 0.1,
                                      "multiply": { "a": 1 } }] }
                    """, "exactly one of");
        }

        @Test
        @DisplayName("a misspelled key is a rule the seed believes it wrote and nothing enforces")
        void unknownModifierKey() {
            expectRefusal("""
                    { "unit": "second", "unitCredits": 100,
                      "modifiers": [{ "param": "reference_image", "perasset": 0.1 }] }
                    """, "unknown key 'perasset'");
        }

        @Test
        @DisplayName("a misspelled PRICE key is refused for the same reason")
        void unknownPriceKey() {
            expectRefusal("""
                    { "unit": "second", "unitCredits": 100, "modifier": [] }
                    """, "unknown key 'modifier'");
        }

        @Test
        @DisplayName("counting files on a parameter that carries a value")
        void perAssetOnAValue() {
            expectRefusal("""
                    { "unit": "second", "unitCredits": 100,
                      "modifiers": [{ "param": "resolution", "perAsset": 0.1 }] }
                    """, "does not carry a file");
        }

        @Test
        @DisplayName("a per-file rate on a REQUIRED file is part of the rate, not an extra")
        void perAssetOnARequiredFile() {
            // Every call carries the file, so the surcharge is a flat increase
            // written as a per-file rule, and it hides in a place the
            // administrator's price screen does not show.
            String spec = """
                    {
                      "kind": "video",
                      "modelParam": "model",
                      "assetPath": "content.video_url",
                      "paramMap": {
                        "prompt": "content[0].text",
                        "duration_seconds": "duration",
                        "input_image": { "path": "image.url", "encoding": "data_url", "role": "source" }
                      },
                      "models": [{
                        "id": "demo-i2v",
                        "upstream": "vendor-i2v",
                        "capabilities": ["prompt", "duration_seconds", "input_image"],
                        "required": ["input_image"],
                        "constraints": { "duration_seconds": { "allowed": [5, 10] } },
                        "price": { "unit": "second", "unitCredits": 100,
                                   "modifiers": [{ "param": "input_image", "perAsset": 0.1 }] }
                      }]
                    }
                    """;
            assertThatThrownBy(() -> GenerationSpec.parse(json(spec), "test"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("REQUIRED file");
        }

        @Test
        @DisplayName("a per-file rate of zero reads as a surcharge nobody is charged")
        void zeroPerAsset() {
            expectRefusal("""
                    { "unit": "second", "unitCredits": 100,
                      "modifiers": [{ "param": "reference_image", "perAsset": 0 }] }
                    """, "must be > 0");
        }

        @Test
        @DisplayName("a factor above the ceiling is a misplaced decimal point")
        void factorCeiling() {
            expectRefusal("""
                    { "unit": "second", "unitCredits": 100,
                      "modifiers": [{ "param": "reference_image", "perAsset": 250 }] }
                    """, "ceiling");
        }

        @Test
        @DisplayName("a value the model ALLOWS and the map does not price bills at the reference tier")
        void allowedValueWithNoFactor() {
            expectRefusal("""
                    { "unit": "second", "unitCredits": 100,
                      "modifiers": [{ "param": "resolution", "multiply": { "480p": 1, "720p": 2 } }] }
                    """, "no factor for '1080p'");
        }

        @Test
        @DisplayName("pricing a value the model does not REQUIRE bills a guess at the base rate")
        void aPricedValueMustBeStated() {
            // Omitted, the factor is 1 - the rate the model is published at - while the provider
            // renders whatever it defaults to. Nothing can close that from the inside, so the
            // parameter has to be stated on every call.
            String spec = """
                    {
                      "kind": "video",
                      "modelParam": "model",
                      "assetPath": "content.video_url",
                      "paramMap": { "prompt": "content[0].text", "resolution": "resolution" },
                      "models": [{
                        "id": "demo-optional",
                        "upstream": "vendor-optional",
                        "capabilities": ["prompt", "resolution"],
                        "constraints": { "resolution": { "allowed": ["480p", "1080p"] } },
                        "price": { "unit": "call", "baseCredits": 100,
                                   "modifiers": [{ "param": "resolution",
                                                   "multiply": { "480p": 1, "1080p": 2 } }] }
                      }]
                    }
                    """;
            assertThatThrownBy(() -> GenerationSpec.parse(json(spec), "test"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("does not require");
        }

        @Test
        @DisplayName("a FILE slot is exempt: attaching nothing genuinely costs nothing")
        void anOptionalFileSlotIsFine() {
            // The mirror of the rule above, and the reason it is not written as "every priced
            // parameter must be required": an absent file at 1x is the true price of that call.
            assertThat(parseModel(RESOLUTION_AND_FILES).price().modifiers())
                    .extracting(GenerationSpec.PriceModifier::param)
                    .contains("reference_image");
        }

        @Test
        @DisplayName("a map with no factor of 1 prices nothing at the model's own rate")
        void noReferenceTier() {
            expectRefusal("""
                    { "unit": "second", "unitCredits": 100,
                      "modifiers": [{ "param": "resolution",
                                      "multiply": { "480p": 2, "720p": 3, "1080p": 4 } }] }
                    """, "factor of 1");
        }

        @Test
        @DisplayName("two spellings of one value would depend on field order to resolve")
        void duplicateNormalisedValue() {
            String spec = """
                    {
                      "kind": "video",
                      "modelParam": "model",
                      "assetPath": "content.video_url",
                      "paramMap": { "prompt": "content[0].text", "quality": "quality" },
                      "models": [{
                        "id": "demo-q",
                        "upstream": "vendor-q",
                        "capabilities": ["prompt", "quality"],
                        "required": ["quality"],
                        "constraints": { "quality": { "allowed": [5] } },
                        "price": { "unit": "call", "baseCredits": 40,
                                   "modifiers": [{ "param": "quality",
                                                   "multiply": { "5": 1, "5.0": 2 } }] }
                      }]
                    }
                    """;
            assertThatThrownBy(() -> GenerationSpec.parse(json(spec), "test"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("twice");
        }

        @Test
        @DisplayName("a factor that is not a number")
        void nonNumericFactor() {
            expectRefusal("""
                    { "unit": "second", "unitCredits": 100,
                      "modifiers": [{ "param": "resolution",
                                      "multiply": { "480p": 1, "720p": "two", "1080p": 4 } }] }
                    """, "must be a number");
        }

        @Test
        @DisplayName("a value that the model constrains only by RANGE has no list to price")
        void aRangeIsNotAList() {
            // The map has to be exhaustive, and only a closed list says what it must cover. Between
            // two numeric bounds the values are unbounded, so every one the author did not name
            // falls through to the reference tier: the silent undercharge this whole block removes,
            // reintroduced by an omission nothing on screen can show.
            String spec = """
                    {
                      "kind": "video",
                      "modelParam": "model",
                      "assetPath": "content.video_url",
                      "paramMap": { "prompt": "content[0].text", "quality": "quality" },
                      "models": [{
                        "id": "demo-range",
                        "upstream": "vendor-range",
                        "capabilities": ["prompt", "quality"],
                        "required": ["quality"],
                        "constraints": { "quality": { "min": 1, "max": 10 } },
                        "price": { "unit": "call", "baseCredits": 100,
                                   "modifiers": [{ "param": "quality",
                                                   "multiply": { "1": 1, "10": 2 } }] }
                      }]
                    }
                    """;
            assertThatThrownBy(() -> GenerationSpec.parse(json(spec), "test"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("'allowed' list");
        }

        @Test
        @DisplayName("a factor on a binding that SCALES would apply on one path and miss on the other")
        void aScaledBindingCannotBePriced() {
            // The direct path reads the value the caller wrote; the relay reads the value the
            // writer sent upstream, which the scale has already multiplied. A map keyed on one of
            // them misses the other entirely - the same request, two prices, depending only on
            // whether the install is linked. Refused rather than converted, because no shipped
            // descriptor wants it and a conversion nobody exercises rots.
            String spec = """
                    {
                      "kind": "video",
                      "modelParam": "model",
                      "assetPath": "content.video_url",
                      "paramMap": {
                        "prompt": "content[0].text",
                        "quality": { "path": "quality_pct", "scale": 100 }
                      },
                      "models": [{
                        "id": "demo-scaled",
                        "upstream": "vendor-scaled",
                        "capabilities": ["prompt", "quality"],
                        "required": ["quality"],
                        "constraints": { "quality": { "allowed": [1, 2] } },
                        "price": { "unit": "call", "baseCredits": 100,
                                   "modifiers": [{ "param": "quality",
                                                   "multiply": { "1": 1, "2": 2 } }] }
                      }]
                    }
                    """;
            assertThatThrownBy(() -> GenerationSpec.parse(json(spec), "test"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("declares a scale");
        }

        @Test
        @DisplayName("modifiers under the ceiling each, and over it TOGETHER, are refused together")
        void theProductIsBoundedToo() {
            // Each of these passes the per-factor check on its own: 4x is well under the ceiling,
            // and so is a per-file rate of 10. A FULL call reaches 4 x (1 + 3 x 10) = 124x, because
            // this slot takes three images. The quote drops anything above the ceiling rather than
            // showing it: the reader would be quoted the base rate and charged the product. That
            // disagreement is the one thing this feature must never produce, so the bound is on the
            // PRODUCT, not on the parts.
            expectRefusal("""
                    { "unit": "second", "unitCredits": 100,
                      "modifiers": [
                        { "param": "resolution", "multiply": { "480p": 1, "720p": 3, "1080p": 4 } },
                        { "param": "reference_image", "perAsset": 10 }
                      ] }
                    """, "above the 100 ceiling");
        }

        /**
         * Two file slots in ONE array, with the markers varied.
         *
         * <p>The relay cannot read a slot's index: the dispatcher prunes empty elements and closes
         * the gap, so position means nothing by the time the body is built. The only thing left
         * that tells one slot's files from another's is the constant each writes beside its own
         * file, which is why a per-file price on a shared array needs one.
         */
        private String sharedArray(String firstConstants, String secondConstants) {
            return """
                    {
                      "kind": "video",
                      "modelParam": "model",
                      "assetPath": "content.video_url",
                      "paramMap": {
                        "prompt": "content[0].text",
                        "first_frame_image": {
                          "path": "content[1].image_url.url",
                          "encoding": "data_url",
                          "role": "first_frame"%s
                        },
                        "reference_image": {
                          "path": "content[2].image_url.url",
                          "encoding": "data_url",
                          "role": "reference",
                          "maxItems": 3%s
                        }
                      },
                      "models": [{
                        "id": "demo-shared",
                        "upstream": "vendor-shared",
                        "capabilities": ["prompt", "first_frame_image", "reference_image"],
                        "price": { "unit": "call", "baseCredits": 100,
                                   "modifiers": [{ "param": "reference_image", "perAsset": 0.1 }] }
                      }]
                    }
                    """.formatted(firstConstants, secondConstants);
        }

        @Test
        @DisplayName("a value bound INSIDE an array cannot be priced: the relay reads a moved index")
        void anIndexedValueBindingCannotBePriced() {
            // The same disease as the refused `scale`, and it was refused for neither. The relay
            // resolves a value binding literally, index included, while the dispatcher prunes empty
            // elements and closes the gap: a quality bound at content[2] behind an optional file at
            // content[1] arrives at content[1] on a call with no file. The relay reads nothing, the
            // factor collapses to 1, and the relayed 4K render bills at the reference tier while
            // the identical direct call bills 2x - the same call, two prices, decided by nothing
            // but whether the install is linked.
            //
            // File slots survive this because they are counted by MARKER rather than by index,
            // which is precisely the machinery a value binding does not have.
            String spec = """
                    {
                      "kind": "video",
                      "modelParam": "model",
                      "assetPath": "content.video_url",
                      "paramMap": {
                        "prompt": "content[0].text",
                        "quality": "content[2].quality"
                      },
                      "models": [{
                        "id": "demo-indexed",
                        "upstream": "vendor-indexed",
                        "capabilities": ["prompt", "quality"],
                        "required": ["quality"],
                        "constraints": { "quality": { "allowed": [1, 2] } },
                        "price": { "unit": "call", "baseCredits": 100,
                                   "modifiers": [{ "param": "quality",
                                                   "multiply": { "1": 1, "2": 2 } }] }
                      }]
                    }
                    """;
            assertThatThrownBy(() -> GenerationSpec.parse(json(spec), "test"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("walks an array element");
        }

        @Test
        @DisplayName("a per-file price on a slot sharing an array with NO marker of its own")
        void sharedArrayWithoutAMarker() {
            // Unmarked, the relay attributes every image in the array to this slot: a call with one
            // opening frame and one reference is measured as two references and billed 1.2x where
            // the direct path bills 1.1x. The same call, two prices, decided by whether the install
            // is linked.
            assertThatThrownBy(() -> GenerationSpec.parse(json(sharedArray("", "")), "test"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("shares an array")
                    // The REMEDY, not just the diagnosis. One message covered both failures and
                    // told an author whose slots are already marked to add a marker.
                    .hasMessageContaining("declares no itemConstants of its own");
        }

        @Test
        @DisplayName("markers that EXIST on both slots and say the same thing discriminate nothing")
        void sharedArrayWithIdenticalMarkers() {
            // The gate used to ask only whether itemConstants was non-empty. Two slots that both
            // write {"type":"image_url"} pass that and are indistinguishable in the built body, so
            // the relay counts every image twice: once for each slot, compounding the surcharge.
            // The shipped Seedance markers happen to differ, which is why nothing showed it.
            String same = """
                    ,
                          "itemConstants": { "content[%d].type": "image_url" }""";
            assertThatThrownBy(() -> GenerationSpec.parse(
                    json(sharedArray(same.formatted(1), same.formatted(2))), "test"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("shares an array")
                    // Asserted precisely, because "shares an array" alone is satisfied by the
                    // OTHER message - the one that says this slot has no marker, which is false
                    // here and sends the author looking for something they already wrote.
                    .hasMessageContaining("writes the same itemConstants as")
                    .hasMessageContaining("give this one a value the other does not write");
        }

        @Test
        @DisplayName("a marker keyed under another index is refused by the BINDING gate, before this one")
        void aMarkerUnderTheWrongIndexIsAlreadyRefused() {
            // Written expecting the modifier gate to catch it, because the measurer strips the
            // binding's own `array[index].` prefix and a constant under a different index would
            // vanish at measuring time, leaving a marker that matches every element.
            //
            // It never reaches that gate: `parseBinding` already refuses an itemConstant that does
            // not sit in the same element as the file it marks, and it refuses it for every
            // binding, priced or not. So the "unreachable marker" case cannot exist in a parsed
            // descriptor at all, and the emptiness check in sharedArrayIsDiscriminated is a
            // belt-and-braces guard rather than the thing that closes the hole.
            //
            // Pinned here anyway, and deliberately asserting the BINDING message: the modifier gate
            // is written against the shape this one guarantees, so if this rule is ever relaxed the
            // failure lands on a test that says which gate actually mattered.
            assertThatThrownBy(() -> GenerationSpec.parse(json(sharedArray(
                    """
                    ,
                          "itemConstants": { "content[1].type": "first_frame" }""",
                    """
                    ,
                          "itemConstants": { "content[9].type": "image_url" }""")), "test"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("must sit in the same element as");
        }

        @Test
        @DisplayName("markers that actually differ are accepted, which is the shipped Seedance shape")
        void sharedArrayWithDiscriminatingMarkers() {
            // The positive half. Without it the three refusals above are satisfied by a gate that
            // refuses everything, which would take every shipped per-file price down with it.
            GenerationSpec spec = GenerationSpec.parse(json(sharedArray(
                    """
                    ,
                          "itemConstants": { "content[1].type": "first_frame" }""",
                    """
                    ,
                          "itemConstants": { "content[2].type": "image_url" }""")), "test")
                    .orElseThrow();

            assertThat(spec.model("demo-shared").orElseThrow().price().modifiers())
                    .extracting(GenerationSpec.PriceModifier::param)
                    .containsExactly("reference_image");
        }

        @Test
        @DisplayName("a slot with an array to ITSELF needs no marker: everything in it is its")
        void ownArrayNeedsNoMarker() {
            assertThat(parseModel(RESOLUTION_AND_FILES).price().modifiers())
                    .extracting(GenerationSpec.PriceModifier::param)
                    .contains("reference_image");
        }

        @Test
        @DisplayName("modifiers that are not an array")
        void notAnArray() {
            expectRefusal("""
                    { "unit": "second", "unitCredits": 100, "modifiers": { "resolution": 2 } }
                    """, "must be an array");
        }
    }

    // ── a flat price is modulated too, which is why the factor is not a quantity ──

    @Test
    @DisplayName("a per-CALL price carries its factor, where scaling a quantity could not")
    void flatPricesAreModulatedToo() {
        // A flat model's billable quantity is 1 by definition and its unit rate
        // is zero, so a factor folded into the quantity would do nothing here -
        // on exactly the models that sell a resolution at one price.
        String spec = """
                {
                  "kind": "video",
                  "modelParam": "model",
                  "assetPath": "content.video_url",
                  "paramMap": { "prompt": "content[0].text", "resolution": "resolution" },
                  "models": [{
                    "id": "demo-flat",
                    "upstream": "vendor-flat",
                    "capabilities": ["prompt", "resolution"],
                    "required": ["resolution"],
                    "constraints": { "resolution": { "allowed": ["720", "1080"] } },
                    "price": { "unit": "call", "baseCredits": 4800,
                               "modifiers": [{ "param": "resolution",
                                               "multiply": { "720": 1, "1080": 2 } }] }
                  }]
                }
                """;
        GenerationSpec parsed = GenerationSpec.parse(json(spec), "test").orElseThrow();
        GenerationSpec.Model model = parsed.model("demo-flat").orElseThrow();

        Map<String, Object> unified = new LinkedHashMap<>();
        unified.put("prompt", "a cat");
        unified.put("resolution", "1080");
        GenerationRequestBuilder.Built built = GenerationRequestBuilder.build(parsed, model, unified);

        assertThat(built.ok()).isTrue();
        assertThat(built.quantity()).isEqualByComparingTo(BigDecimal.ONE);
        assertThat(built.priceMultiplier()).isEqualByComparingTo("2");
    }
}
