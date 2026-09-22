package com.apimarketplace.catalog.service.generation;

import com.apimarketplace.catalog.service.execution.OutputProjector;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Contract tests for the V428 generation descriptor.
 *
 * <p>The parser is the single gate between a hand-written seed and the runtime
 * that bills real money against it, so the malformed-input cases matter as much
 * as the happy path: every one of them is a mistake that would otherwise
 * surface at call time as an unexplained failure.
 */
class GenerationSpecTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static JsonNode json(String s) {
        try {
            return MAPPER.readTree(s);
        } catch (Exception e) {
            throw new IllegalStateException("bad test fixture", e);
        }
    }

    /** A minimal, valid descriptor. Individual tests mutate one thing at a time. */
    private static final String VALID = """
            {
              "kind": "video",
              "modelParam": "model",
              "assetPath": "content.video_url",
              "paramMap": { "prompt": "content[0].text", "duration_seconds": "duration" },
              "constants": { "content[0].type": "text" },
              "models": [{
                "id": "demo-1.0",
                "upstream": "vendor-demo-1",
                "label": "Demo 1.0",
                "capabilities": ["prompt", "duration_seconds"],
                "constraints": { "duration_seconds": { "allowed": [5, 10] } },
                "price": { "unit": "second", "unitCredits": 60 }
              }]
            }
            """;

    @Nested
    @DisplayName("absence")
    class Absence {

        @Test
        @DisplayName("a null node yields an empty spec, not an error - most endpoints have no descriptor")
        void nullNodeIsEmpty() {
            assertThat(GenerationSpec.parse(null, "ctx")).isEmpty();
        }

        @Test
        @DisplayName("a missing node yields an empty spec")
        void missingNodeIsEmpty() {
            assertThat(GenerationSpec.parse(json("{}").path("generation"), "ctx")).isEmpty();
        }

        @Test
        @DisplayName("an explicit JSON null yields an empty spec")
        void explicitNullIsEmpty() {
            assertThat(GenerationSpec.parse(json("{\"generation\":null}").path("generation"), "ctx")).isEmpty();
        }
    }

    @Nested
    @DisplayName("happy path")
    class HappyPath {

        @Test
        @DisplayName("parses every declared field of a complete descriptor")
        void parsesCompleteDescriptor() {
            GenerationSpec spec = GenerationSpec.parse(json(VALID), "ctx").orElseThrow();

            assertThat(spec.kind()).isEqualTo("video");
            assertThat(spec.modelParam()).isEqualTo("model");
            assertThat(spec.assetPath()).isEqualTo("content.video_url");
            assertThat(spec.isBinaryAsset()).isFalse();
            assertThat(spec.sendsModelParam()).isTrue();
            assertThat(spec.constants()).containsEntry("content[0].type", "text");

            GenerationSpec.Model model = spec.model("demo-1.0").orElseThrow();
            assertThat(model.upstream()).isEqualTo("vendor-demo-1");
            assertThat(model.label()).isEqualTo("Demo 1.0");
            assertThat(model.accepts("prompt")).isTrue();
            assertThat(model.accepts("voice")).isFalse();
            assertThat(model.price().unit()).isEqualTo("second");
            assertThat(model.price().perUnit()).isEqualByComparingTo("60");
        }

        @Test
        @DisplayName("a binary-asset descriptor is recognised and needs no poll endpoint")
        void binaryAssetDescriptor() {
            GenerationSpec spec = GenerationSpec.parse(json("""
                    {
                      "kind": "voice",
                      "assetPath": "$binary",
                      "paramMap": { "prompt": "text" },
                      "models": [{ "id": "tts-1", "capabilities": ["prompt"],
                                   "price": { "unit": "character", "unitCredits": 0.2 } }]
                    }
                    """), "ctx").orElseThrow();

            assertThat(spec.isBinaryAsset()).isTrue();
            assertThat(spec.sendsModelParam()).isFalse();
            // upstream is meaningless without a modelParam, so it falls back to the id
            assertThat(spec.model("tts-1").orElseThrow().upstream()).isEqualTo("tts-1");
        }

        @Test
        @DisplayName("model ids are lowercased so an agent's casing never causes a miss")
        void modelIdIsLowercased() {
            GenerationSpec spec = GenerationSpec.parse(json(VALID.replace("\"demo-1.0\"", "\"Demo-1.0\"")), "ctx")
                    .orElseThrow();
            assertThat(spec.model("demo-1.0")).isPresent();
        }

        @Test
        @DisplayName("modelsById preserves declaration order so admin screens list them predictably")
        void modelsByIdPreservesOrder() {
            GenerationSpec spec = GenerationSpec.parse(json("""
                    {
                      "kind": "video", "modelParam": "model", "assetPath": "url",
                      "paramMap": { "prompt": "p" },
                      "models": [
                        { "id": "b", "upstream": "B", "capabilities": ["prompt"] },
                        { "id": "a", "upstream": "A", "capabilities": ["prompt"] }
                      ]
                    }
                    """), "ctx").orElseThrow();
            assertThat(spec.modelsById().keySet()).containsExactly("b", "a");
        }
    }

    @Nested
    @DisplayName("param bindings")
    class Bindings {

        @Test
        @DisplayName("the short string form binds a unified param straight to an upstream path")
        void shortForm() {
            GenerationSpec spec = GenerationSpec.parse(json(VALID), "ctx").orElseThrow();
            GenerationSpec.ParamBinding b = spec.paramMap().get("prompt");
            assertThat(b.path()).isEqualTo("content[0].text");
            assertThat(b.scale()).isNull();
        }

        @Test
        @DisplayName("the object form carries a scale, which converts seconds to the provider's unit")
        void scaledForm() {
            GenerationSpec spec = GenerationSpec.parse(json("""
                    {
                      "kind": "music", "assetPath": "$binary",
                      "paramMap": { "prompt": "prompt",
                                    "duration_seconds": { "path": "music_length_ms", "scale": 1000 } },
                      "models": [{ "id": "m1", "capabilities": ["prompt", "duration_seconds"],
                                   "price": { "unit": "second", "unitCredits": 8 } }]
                    }
                    """), "ctx").orElseThrow();

            GenerationSpec.ParamBinding b = spec.paramMap().get("duration_seconds");
            assertThat(b.path()).isEqualTo("music_length_ms");
            // 30 s must reach ElevenLabs as 30000 ms, and as a whole number
            assertThat(b.adapt(30)).isEqualTo(30_000L);
            assertThat(b.adapt("12.5")).isEqualTo(12_500L);
        }

        @Test
        @DisplayName("a scale leaves a non-numeric value untouched rather than corrupting it")
        void scaleIgnoresNonNumeric() {
            GenerationSpec.ParamBinding b =
                    new GenerationSpec.ParamBinding("x", BigDecimal.valueOf(1000));
            assertThat(b.adapt("not-a-number")).isEqualTo("not-a-number");
            assertThat(b.adapt(null)).isNull();
        }

        @Test
        @DisplayName("a zero or negative scale is rejected: it would silently erase the value")
        void rejectsNonPositiveScale() {
            assertThatThrownBy(() -> GenerationSpec.parse(json("""
                    {
                      "kind": "music", "assetPath": "$binary",
                      "paramMap": { "duration_seconds": { "path": "ms", "scale": 0 } },
                      "models": [{ "id": "m1", "capabilities": ["duration_seconds"] }]
                    }
                    """), "ctx"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("scale must be > 0");
        }
    }

    @Nested
    @DisplayName("rejects malformed descriptors")
    class Rejections {

        @Test
        @DisplayName("a missing kind is rejected: nothing could classify the model")
        void requiresKind() {
            assertThatThrownBy(() -> GenerationSpec.parse(json(VALID.replace("\"kind\": \"video\",", "")), "ep-x"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("generation.kind is required")
                    .hasMessageContaining("ep-x");
        }

        @Test
        @DisplayName("a non-snake_case kind is rejected so it can never break the category constraint")
        void rejectsBadKindShape() {
            assertThatThrownBy(() -> GenerationSpec.parse(json(VALID.replace("\"video\"", "\"Video Gen\"")), "ctx"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("lowercase snake_case");
        }

        @Test
        @DisplayName("a missing assetPath is rejected: the produced asset could never be found")
        void requiresAssetPath() {
            assertThatThrownBy(() -> GenerationSpec.parse(
                    json(VALID.replace("\"assetPath\": \"content.video_url\",", "")), "ctx"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("generation.assetPath is required");
        }

        @Test
        @DisplayName("an array index above the cap is refused: writing it would allocate every slot below")
        void rejectsHugeArrayIndex() {
            assertThatThrownBy(() -> GenerationSpec.parse(json("""
                    {
                      "kind": "video", "assetPath": "url",
                      "paramMap": { "prompt": "items[900000000].text" },
                      "models": [{ "id": "m1", "capabilities": ["prompt"] }]
                    }
                    """), "ctx"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("above the maximum of " + GenerationSpec.MAX_PATH_INDEX);
        }

        @Test
        @DisplayName("a malformed upstream path is refused at parse, not silently dropped at call time")
        void rejectsMalformedPathAtParse() {
            assertThatThrownBy(() -> GenerationSpec.parse(json("""
                    {
                      "kind": "video", "assetPath": "url",
                      "paramMap": { "prompt": "bad[[0]" },
                      "models": [{ "id": "m1", "capabilities": ["prompt"] }]
                    }
                    """), "ctx"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("malformed upstream path");
        }

        @Test
        @DisplayName("a parameter mapped onto the model selector is refused: a caller could run a "
                + "different model than the one being priced")
        void rejectsParamOverwritingModelSelector() {
            assertThatThrownBy(() -> GenerationSpec.parse(json("""
                    {
                      "kind": "video", "modelParam": "model", "assetPath": "url",
                      "paramMap": { "prompt": "p", "style": "model" },
                      "models": [{ "id": "m1", "upstream": "M1", "capabilities": ["prompt", "style"] }]
                    }
                    """), "ctx"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("which is the modelParam");
        }

        @Test
        @DisplayName("a parameter mapped onto a constant is refused: a caller could undo the scaffolding")
        void rejectsParamOverwritingConstant() {
            assertThatThrownBy(() -> GenerationSpec.parse(json("""
                    {
                      "kind": "video", "assetPath": "url",
                      "paramMap": { "prompt": "content[0].type" },
                      "constants": { "content[0].type": "text" },
                      "models": [{ "id": "m1", "capabilities": ["prompt"] }]
                    }
                    """), "ctx"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("generation.constants also sets");
        }

        @Test
        @DisplayName("a required parameter outside the capabilities is refused as an authoring mistake")
        void rejectsRequiredOutsideCapabilities() {
            assertThatThrownBy(() -> GenerationSpec.parse(json("""
                    {
                      "kind": "voice", "assetPath": "$binary",
                      "paramMap": { "prompt": "text" },
                      "models": [{ "id": "m1", "capabilities": ["prompt"], "required": ["voice"] }]
                    }
                    """), "ctx"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("is not one of its capabilities");
        }

        @Test
        @DisplayName("pollTool is refused and points at the waiting mechanism that actually runs")
        void rejectsPollTool() {
            // Two ways to say "this job finishes later" is how the two drift
            // apart: the catalog already executes execution.mode=async_poll,
            // and a descriptor-level poll would have been silently ignored.
            assertThatThrownBy(() -> GenerationSpec.parse(json("""
                    {
                      "kind": "video", "modelParam": "model", "assetPath": "content.video_url",
                      "pollTool": "get_video_task",
                      "paramMap": { "prompt": "p" },
                      "models": [{ "id": "m1", "upstream": "M1", "capabilities": ["prompt"] }]
                    }
                    """), "ctx"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("generation.pollTool is not supported")
                    .hasMessageContaining("async_poll");
        }

        @Test
        @DisplayName("an empty models array is rejected: the endpoint would expose nothing")
        void requiresAtLeastOneModel() {
            assertThatThrownBy(() -> GenerationSpec.parse(
                    json(VALID.replaceAll("\"models\": \\[\\{[\\s\\S]*\\}\\]", "\"models\": []")), "ctx"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("generation.models must be a non-empty array");
        }

        @Test
        @DisplayName("an unknown unified parameter in paramMap is rejected with the accepted list")
        void rejectsUnknownUnifiedParam() {
            assertThatThrownBy(() -> GenerationSpec.parse(
                    json(VALID.replace("\"prompt\": \"content[0].text\"", "\"promt\": \"content[0].text\"")), "ctx"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("'promt' is not a unified parameter")
                    .hasMessageContaining("duration_seconds");
        }

        @Test
        @DisplayName("a capability with no paramMap entry is rejected: the value could never be sent")
        void rejectsUnmappedCapability() {
            assertThatThrownBy(() -> GenerationSpec.parse(json("""
                    {
                      "kind": "video", "assetPath": "url",
                      "paramMap": { "prompt": "p" },
                      "models": [{ "id": "m1", "capabilities": ["prompt", "seed"] }]
                    }
                    """), "ctx"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("declares capability 'seed'")
                    .hasMessageContaining("no mapping");
        }

        @Test
        @DisplayName("a model with no capabilities is rejected: it could not be called at all")
        void rejectsCapabilitylessModel() {
            assertThatThrownBy(() -> GenerationSpec.parse(json("""
                    {
                      "kind": "video", "assetPath": "url",
                      "paramMap": { "prompt": "p" },
                      "models": [{ "id": "m1", "capabilities": [] }]
                    }
                    """), "ctx"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("must list at least one unified parameter");
        }

        @Test
        @DisplayName("duplicate model ids are rejected: resolution would be ambiguous")
        void rejectsDuplicateModelIds() {
            assertThatThrownBy(() -> GenerationSpec.parse(json("""
                    {
                      "kind": "video", "modelParam": "model", "assetPath": "url",
                      "paramMap": { "prompt": "p" },
                      "models": [
                        { "id": "dup", "upstream": "A", "capabilities": ["prompt"] },
                        { "id": "dup", "upstream": "B", "capabilities": ["prompt"] }
                      ]
                    }
                    """), "ctx"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("duplicate generation model id 'dup'");
        }

        @Test
        @DisplayName("several models with no modelParam are rejected: nothing could select between them")
        void rejectsMultipleModelsWithoutSelector() {
            assertThatThrownBy(() -> GenerationSpec.parse(json("""
                    {
                      "kind": "video", "assetPath": "url",
                      "paramMap": { "prompt": "p" },
                      "models": [
                        { "id": "a", "capabilities": ["prompt"] },
                        { "id": "b", "capabilities": ["prompt"] }
                      ]
                    }
                    """), "ctx"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("no modelParam");
        }

        @Test
        @DisplayName("a missing upstream is rejected when the endpoint DOES select a model")
        void requiresUpstreamWhenModelParamSet() {
            assertThatThrownBy(() -> GenerationSpec.parse(
                    json(VALID.replace("\"upstream\": \"vendor-demo-1\",", "")), "ctx"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("upstream is required because generation.modelParam is set");
        }

        @Test
        @DisplayName("a constraint on a non-capability is rejected as an authoring mistake")
        void rejectsConstraintOnNonCapability() {
            assertThatThrownBy(() -> GenerationSpec.parse(
                    json(VALID.replace("\"duration_seconds\": { \"allowed\": [5, 10] }",
                                       "\"aspect_ratio\": { \"allowed\": [\"16:9\"] }")), "ctx"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("is not one of its capabilities");
        }

        @Test
        @DisplayName("a parameter whose path is a PREFIX of a constant is refused: it would replace "
                + "the array the constant builds")
        void rejectsParamPrefixOfConstant() {
            // Constants are written first, so the paramMap write lands last and
            // puts a string where the provider requires an array. Nothing
            // reports it: the import passes and the call just fails upstream.
            assertThatThrownBy(() -> GenerationSpec.parse(json("""
                    {
                      "kind": "video", "assetPath": "url",
                      "paramMap": { "prompt": "content" },
                      "constants": { "content[0].type": "text" },
                      "models": [{ "id": "m1", "capabilities": ["prompt"] }]
                    }
                    """), "ctx"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("collides with 'content[0].type'");
        }

        @Test
        @DisplayName("a parameter whose path EXTENDS a scalar constant is refused: the write would "
                + "walk into the scalar instead of throwing at call time")
        void rejectsParamExtendingScalarConstant() {
            // The mirror image, and the one that used to blow up as a
            // ClassCastException while the customer's call was already in flight.
            assertThatThrownBy(() -> GenerationSpec.parse(json("""
                    {
                      "kind": "video", "assetPath": "url",
                      "paramMap": { "prompt": "content[0].text" },
                      "constants": { "content": "text" },
                      "models": [{ "id": "m1", "capabilities": ["prompt"] }]
                    }
                    """), "ctx"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("collides with 'content'");
        }

        @Test
        @DisplayName("a parameter nested under a constant's object path is refused too")
        void rejectsParamPrefixOfNestedConstant() {
            assertThatThrownBy(() -> GenerationSpec.parse(json("""
                    {
                      "kind": "video", "assetPath": "url",
                      "paramMap": { "prompt": "config" },
                      "constants": { "config.mode": "fast" },
                      "models": [{ "id": "m1", "capabilities": ["prompt"] }]
                    }
                    """), "ctx"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("collides with 'config.mode'");
        }

        @Test
        @DisplayName("a parameter whose path merely CONTAINS the model selector is refused as well")
        void rejectsParamPrefixOfModelSelector() {
            assertThatThrownBy(() -> GenerationSpec.parse(json("""
                    {
                      "kind": "video", "modelParam": "config.model", "assetPath": "url",
                      "paramMap": { "prompt": "p", "style": "config" },
                      "models": [{ "id": "m1", "upstream": "M1", "capabilities": ["prompt", "style"] }]
                    }
                    """), "ctx"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("collides with the modelParam 'config.model'");
        }

        @Test
        @DisplayName("two constants that collide are refused naming BOTH paths, since neither is "
                + "wrong on its own")
        void rejectsCollidingConstants() {
            assertThatThrownBy(() -> GenerationSpec.parse(json("""
                    {
                      "kind": "video", "assetPath": "url",
                      "paramMap": { "prompt": "p" },
                      "constants": { "content": "text", "content[0].type": "text" },
                      "models": [{ "id": "m1", "capabilities": ["prompt"] }]
                    }
                    """), "ctx"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("generation.constants['content']")
                    .hasMessageContaining("generation.constants['content[0].type']");
        }

        @Test
        @DisplayName("two mapped parameters that collide are refused: one would overwrite the other")
        void rejectsCollidingParams() {
            assertThatThrownBy(() -> GenerationSpec.parse(json("""
                    {
                      "kind": "video", "assetPath": "url",
                      "paramMap": { "prompt": "content[0].text", "style": "content" },
                      "models": [{ "id": "m1", "capabilities": ["prompt", "style"] }]
                    }
                    """), "ctx"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("generation.paramMap['prompt']")
                    .hasMessageContaining("generation.paramMap['style']");
        }

        @Test
        @DisplayName("an out-of-range index in a CONSTANT is refused too: it allocates the same slots")
        void rejectsOutOfRangeIndexInConstant() {
            assertThatThrownBy(() -> GenerationSpec.parse(json("""
                    {
                      "kind": "video", "assetPath": "url",
                      "paramMap": { "prompt": "p" },
                      "constants": { "items[900000000].type": "text" },
                      "models": [{ "id": "m1", "capabilities": ["prompt"] }]
                    }
                    """), "ctx"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("above the maximum of " + GenerationSpec.MAX_PATH_INDEX);
        }

        @Test
        @DisplayName("paths that only look alike are still accepted, so the check does not break "
                + "the shape every multimodal provider uses")
        void acceptsNeighbouringPaths() {
            // content[0].text next to content[0].type is the standard shape, and
            // a prefix check written with startsWith would have refused it.
            assertThat(GenerationSpec.parse(json("""
                    {
                      "kind": "video", "assetPath": "url",
                      "paramMap": { "prompt": "content[0].text", "style": "content[1].style",
                                    "seed": "contents" },
                      "constants": { "content[0].type": "text" },
                      "models": [{ "id": "m1", "capabilities": ["prompt", "style", "seed"] }]
                    }
                    """), "ctx")).isPresent();
        }

        @Test
        @DisplayName("a non-scalar constant is rejected: nested shapes belong in indexed paths")
        void rejectsContainerConstant() {
            assertThatThrownBy(() -> GenerationSpec.parse(
                    json(VALID.replace("\"content[0].type\": \"text\"", "\"content[0]\": {\"type\":\"text\"}")), "ctx"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("must be a scalar");
        }
    }

    @Nested
    @DisplayName("starting price")
    class Pricing {

        @Test
        @DisplayName("an omitted price block yields a free price rather than failing the import")
        void omittedPriceIsFree() {
            GenerationSpec spec = GenerationSpec.parse(json("""
                    {
                      "kind": "image", "assetPath": "$binary",
                      "paramMap": { "prompt": "p" },
                      "models": [{ "id": "m1", "capabilities": ["prompt"] }]
                    }
                    """), "ctx").orElseThrow();
            GenerationSpec.Price price = spec.model("m1").orElseThrow().price();
            assertThat(price.unit()).isEqualTo("call");
            assertThat(price.base()).isEqualByComparingTo("0");
            assertThat(price.perUnit()).isEqualByComparingTo("0");
        }

        @Test
        @DisplayName("floor and ceiling are carried through so a tiny call still bills something")
        void carriesMinAndMax() {
            GenerationSpec spec = GenerationSpec.parse(json("""
                    {
                      "kind": "voice", "assetPath": "$binary",
                      "paramMap": { "prompt": "text" },
                      "models": [{ "id": "m1", "capabilities": ["prompt"],
                                   "price": { "unit": "character", "unitCredits": 0.2,
                                              "minCredits": 1, "maxCredits": 5000 } }]
                    }
                    """), "ctx").orElseThrow();
            GenerationSpec.Price p = spec.model("m1").orElseThrow().price();
            assertThat(p.min()).isEqualByComparingTo("1");
            assertThat(p.max()).isEqualByComparingTo("5000");
        }

        @Test
        @DisplayName("a unit price with a zero rate is rejected: the unit would have no effect")
        void rejectsUnitWithZeroRate() {
            assertThatThrownBy(() -> GenerationSpec.parse(json("""
                    {
                      "kind": "video", "assetPath": "url",
                      "paramMap": { "prompt": "p" },
                      "models": [{ "id": "m1", "capabilities": ["prompt"],
                                   "price": { "unit": "second", "unitCredits": 0 } }]
                    }
                    """), "ctx"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("unitCredits is 0");
        }

        @Test
        @DisplayName("an unsupported price unit is rejected with the accepted list")
        void rejectsUnknownUnit() {
            assertThatThrownBy(() -> GenerationSpec.parse(
                    json(VALID.replace("\"unit\": \"second\"", "\"unit\": \"megapixel\"")), "ctx"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("is not supported")
                    .hasMessageContaining("character");
        }

        @Test
        @DisplayName("a negative price is rejected")
        void rejectsNegativePrice() {
            assertThatThrownBy(() -> GenerationSpec.parse(
                    json(VALID.replace("\"unitCredits\": 60", "\"unitCredits\": -1")), "ctx"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("credits must be >= 0");
        }

        @Test
        @DisplayName("a floor above the ceiling is rejected")
        void rejectsInvertedBounds() {
            assertThatThrownBy(() -> GenerationSpec.parse(
                    json(VALID.replace("\"unitCredits\": 60",
                                       "\"unitCredits\": 60, \"minCredits\": 100, \"maxCredits\": 10")), "ctx"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("minCredits > maxCredits");
        }
    }

    @Nested
    @DisplayName("constraints")
    class Constraints {

        @Test
        @DisplayName("an allowed list reports a violation naming the accepted values")
        void allowedListViolation() {
            GenerationSpec spec = GenerationSpec.parse(json(VALID), "ctx").orElseThrow();
            GenerationSpec.Constraint c = spec.model("demo-1.0").orElseThrow()
                    .constraints().get("duration_seconds");

            assertThat(c.violation(5)).isNull();
            assertThat(c.violation(10)).isNull();
            assertThat(c.violation(7)).contains("must be one of");
        }

        @Test
        @DisplayName("a min/max range reports which bound was crossed")
        void rangeViolation() {
            GenerationSpec spec = GenerationSpec.parse(json("""
                    {
                      "kind": "audio", "assetPath": "$binary",
                      "paramMap": { "duration_seconds": "duration_seconds" },
                      "models": [{ "id": "m1", "capabilities": ["duration_seconds"],
                                   "constraints": { "duration_seconds": { "min": 0.5, "max": 30 } },
                                   "price": { "unit": "second", "unitCredits": 4 } }]
                    }
                    """), "ctx").orElseThrow();
            GenerationSpec.Constraint c = spec.model("m1").orElseThrow().constraints().get("duration_seconds");

            assertThat(c.violation(10)).isNull();
            assertThat(c.violation(0.1)).contains(">= 0.5");
            assertThat(c.violation(45)).contains("<= 30");
            assertThat(c.violation("abc")).contains("must be a number");
        }

        @Test
        @DisplayName("a null value never violates: absence is handled by capability checks, not constraints")
        void nullNeverViolates() {
            GenerationSpec spec = GenerationSpec.parse(json(VALID), "ctx").orElseThrow();
            assertThat(spec.model("demo-1.0").orElseThrow()
                    .constraints().get("duration_seconds").violation(null)).isNull();
        }
    }

    @Nested
    @DisplayName("upstream path collisions")
    class PathCollisions {

        @Test
        @DisplayName("a path collides with itself and with anything that extends it, in either order")
        void prefixesCollideBothWays() {
            assertThat(GenerationSpec.pathsCollide("content", "content")).isTrue();
            assertThat(GenerationSpec.pathsCollide("content", "content[0].type")).isTrue();
            assertThat(GenerationSpec.pathsCollide("content[0].type", "content")).isTrue();
            assertThat(GenerationSpec.pathsCollide("config", "config.mode")).isTrue();
            assertThat(GenerationSpec.pathsCollide("config.mode", "config")).isTrue();
            assertThat(GenerationSpec.pathsCollide("content[0]", "content[0].text")).isTrue();
        }

        @Test
        @DisplayName("a different key or a different array slot is not a collision")
        void distinctPathsDoNotCollide() {
            assertThat(GenerationSpec.pathsCollide("content", "contents")).isFalse();
            // The check must not degrade into startsWith: 'content' is not a
            // prefix of 'contents[0]' in path terms, only in text terms.
            assertThat(GenerationSpec.pathsCollide("content", "contents[0]")).isFalse();
            assertThat(GenerationSpec.pathsCollide("content[0].text", "content[0].type")).isFalse();
            assertThat(GenerationSpec.pathsCollide("content[0].text", "content[1].text")).isFalse();
            assertThat(GenerationSpec.pathsCollide("a.b.c", "a.b.d")).isFalse();
        }
    }

    @Nested
    @DisplayName("shipped seeds")
    class ShippedSeeds {

        /**
         * Seedance 2.5 takes references and no pinned frame, and that is a DECISION.
         *
         * <p>ModelArk: "Dreamina Seedance 2.5 has special constraints for ...
         * first-frame/first-and-last-frame image-to-video tasks ... ratio only supports
         * adaptive; you cannot specify another aspect ratio." The 2.0 series carries no
         * such sentence, which is why it keeps both frames.
         *
         * <p>A descriptor cannot say "this value only when that slot is filled": the
         * pairing rules sit on a BINDING, which all eleven models of this endpoint
         * share, so declaring the restriction would over-refuse on the 2.0 family.
         * Until that changes, leaving the slots off 2.5 is the only truthful shape, and
         * adding them back is a one-word edit in a capabilities array that every other
         * check in this repository would wave through.
         */
        @Test
        @DisplayName("Seedance 2.5 offers no pinned frame, because its aspect ratio rule cannot be declared")
        void seedanceTwoFiveTakesNoFrame() throws Exception {
            Path seed = Path.of("..", "..", "scripts", "api-migrations", "seedance.json");
            assumeTrue(Files.isRegularFile(seed), "seedance.json not reachable from this module");

            JsonNode api = MAPPER.readTree(Files.readString(seed));
            GenerationSpec spec = null;
            for (JsonNode ep : api.path("endpoints")) {
                if (!ep.path("generation").isMissingNode() && !ep.path("generation").isNull()) {
                    spec = GenerationSpec.parse(ep.path("generation"), "seedance").orElseThrow();
                }
            }
            assertThat(spec).as("seedance.json must still declare a generation endpoint").isNotNull();

            List<String> frames = List.of("first_frame_image", "last_frame_image");
            for (GenerationSpec.Model model : spec.models()) {
                boolean isTwoFive = model.id().startsWith("seedance-2.5");
                for (String frame : frames) {
                    assertThat(model.accepts(frame))
                            .as("%s accepting '%s': on 2.5 a pinned frame forces ratio=adaptive, "
                                    + "which no descriptor can say for three models out of eleven; "
                                    + "on the 2.0 series the provider documents no such restriction",
                                    model.id(), frame)
                            .isEqualTo(!isTwoFive);
                }
                // Both families keep the reference slot, and every model that takes a closing
                // frame takes an opening one: the pair is never half-offered.
                assertThat(model.accepts("input_image")).as("%s keeps its references", model.id())
                        .isTrue();
            }
        }

        /**
         * Drift guard. Every descriptor committed to scripts/api-migrations MUST
         * parse, because the import that reads them runs against production and
         * a rejected seed there is a failed release, not a failed test.
         */
        @Test
        @DisplayName("every generation block in the shipped seeds parses")
        void shippedSeedsParse() throws Exception {
            Path dir = Path.of("..", "..", "scripts", "api-migrations");
            assumeTrue(Files.isDirectory(dir), "api-migrations not reachable from this module");

            int endpointsWithSpec = 0;
            try (var files = Files.list(dir)) {
                for (Path f : files.filter(p -> p.toString().endsWith(".json")).toList()) {
                    JsonNode api = MAPPER.readTree(Files.readString(f));
                    for (JsonNode ep : api.path("endpoints")) {
                        JsonNode gen = ep.path("generation");
                        if (gen.isMissingNode() || gen.isNull()) continue;
                        String ctx = f.getFileName() + "/" + ep.path("name").asText("?");
                        Optional<GenerationSpec> parsed = GenerationSpec.parse(gen, ctx);
                        assertThat(parsed).as("descriptor in %s must parse", ctx).isPresent();
                        endpointsWithSpec++;
                    }
                }
            }
            // Guards against the file-walk silently matching nothing and the test
            // passing while proving absolutely nothing.
            assertThat(endpointsWithSpec)
                    .as("expected the shipped seeds to declare generation endpoints")
                    .isGreaterThanOrEqualTo(4);
        }

        @Test
        @DisplayName("the asset survives the PROJECTION, or every call is charged and returns nothing")
        void theAssetSurvivesTheProjection() throws Exception {
            // The provider's response is projected against the endpoint's own
            // outputSchema BEFORE anything looks for the asset, and the charge
            // is committed in between. The projector keeps declared keys and
            // drops the rest, so an assetPath rooted at an undeclared key reads
            // a field that has already been deleted: the call succeeds, the
            // money moves, the customer gets nothing, every time.
            //
            // It shipped that way on the flagship video model, whose create
            // endpoint described the SUBMIT acknowledgement while the call
            // actually returns the finished body. Every asset-resolver test
            // hands in a map that already contains the field, so the projection
            // was never in the loop and none of them could see it.
            Path dir = Path.of("..", "..", "scripts", "api-migrations");
            assumeTrue(Files.isDirectory(dir), "api-migrations not reachable from this module");

            OutputProjector projector = new OutputProjector(MAPPER);
            int checked = 0;
            try (var files = Files.list(dir)) {
                for (Path f : files.filter(p -> p.toString().endsWith(".json")).toList()) {
                    JsonNode api = MAPPER.readTree(Files.readString(f));
                    for (JsonNode ep : api.path("endpoints")) {
                        JsonNode gen = ep.path("generation");
                        if (gen.isMissingNode() || gen.isNull()) continue;
                        String assetPath = gen.path("assetPath").asText("");
                        // The binary form has no field to project: the bytes ARE
                        // the response.
                        if (assetPath.isBlank() || "$binary".equals(assetPath)) continue;
                        JsonNode schema = ep.path("outputSchema");
                        if (!schema.isArray() || schema.isEmpty()) continue;

                        String ctx = f.getFileName() + "/" + ep.path("name").asText("?");
                        // A base64 asset is read out of the projected body just
                        // like a URL is, so it faces the same projection: only
                        // the sentinel has to come off first.
                        String readPath = assetPath.startsWith(GenerationSpec.ASSET_BASE64_PREFIX)
                                ? assetPath.substring(GenerationSpec.ASSET_BASE64_PREFIX.length())
                                : assetPath;
                        String root = readPath.split("\\.")[0].split("\\[")[0];

                        // A response shaped like the one this endpoint really
                        // returns, carrying the asset where the descriptor says.
                        Map<String, Object> upstream = new LinkedHashMap<>();
                        upstream.put("id", "task-1");
                        upstream.put("status", "succeeded");
                        upstream.put(root, Map.of(
                                "video_url", "https://example.test/a.mp4",
                                "url", "https://example.test/a.mp4"));

                        Object projected = projector.project(upstream, schema.toString());

                        assertThat(projected)
                                .as("%s: the projected response must still be a map", ctx)
                                .isInstanceOf(Map.class);
                        assertThat(((Map<?, ?>) projected).containsKey(root))
                                .as("%s: assetPath '%s' reads '%s', which the projection dropped. "
                                        + "The charge is committed before this is read, so every call "
                                        + "would be billed and return no asset.", ctx, assetPath, root)
                                .isTrue();
                        checked++;
                    }
                }
            }
            assertThat(checked)
                    .as("expected at least one seeded generation to read its asset from a field")
                    .isGreaterThanOrEqualTo(1);
        }

        @Test
        @DisplayName("the shipped seeds cover several kinds, proving the descriptor is not image-shaped")
        void shippedSeedsCoverSeveralKinds() throws Exception {
            Path dir = Path.of("..", "..", "scripts", "api-migrations");
            assumeTrue(Files.isDirectory(dir), "api-migrations not reachable from this module");

            List<String> kinds = new java.util.ArrayList<>();
            try (var files = Files.list(dir)) {
                for (Path f : files.filter(p -> p.toString().endsWith(".json")).toList()) {
                    JsonNode api = MAPPER.readTree(Files.readString(f));
                    for (JsonNode ep : api.path("endpoints")) {
                        GenerationSpec.parse(ep.path("generation"), f.getFileName().toString())
                                .ifPresent(s -> kinds.add(s.kind()));
                    }
                }
            }
            assertThat(kinds).contains("video", "voice", "audio", "music");
        }

        /**
         * A model sold by the size of a call has to be able to state that size on
         * EVERY call, or the price multiplies nothing and the call is refused. The
         * refusal used to name the platform administrator, for a price that was
         * published and correct.
         */
        @Test
        @DisplayName("every shipped generation model can state the size of any call it is asked for")
        void everyShippedModelCanStateItsSize() throws Exception {
            Path dir = Path.of("..", "..", "scripts", "api-migrations");
            assumeTrue(Files.isDirectory(dir), "api-migrations not reachable from this module");

            int checked = 0;
            try (var files = Files.list(dir)) {
                for (Path f : files.filter(p -> p.toString().endsWith(".json")).toList()) {
                    JsonNode api = MAPPER.readTree(Files.readString(f));
                    for (JsonNode ep : api.path("endpoints")) {
                        Optional<GenerationSpec> parsed =
                                GenerationSpec.parse(ep.path("generation"), f.getFileName().toString());
                        if (parsed.isEmpty()) continue;
                        for (GenerationSpec.Model m : parsed.get().models()) {
                            assertThat(m.canAlwaysStateItsSize())
                                    .as("model '%s' in %s is priced per %s but neither defaults nor "
                                                    + "requires '%s'",
                                            m.id(), f.getFileName(), m.price().unit(), m.measuringParam())
                                    .isTrue();
                            checked++;
                        }
                    }
                }
            }
            assertThat(checked).isGreaterThanOrEqualTo(4);
        }
    }

    @Nested
    @DisplayName("the size a model is sold by")
    class CallSize {

        @Test
        @DisplayName("the measuring parameter follows the price's dimension, not its scale")
        void measuringParamFollowsTheDimension() {
            assertThat(model("{\"unit\":\"second\",\"unitCredits\":60}", "duration_seconds")
                    .measuringParam()).isEqualTo("duration_seconds");
            assertThat(model("{\"unit\":\"minute\",\"unitCredits\":480}", "duration_seconds")
                    .measuringParam()).isEqualTo("duration_seconds");
            assertThat(model("{\"unit\":\"image\",\"unitCredits\":40}", "n")
                    .measuringParam()).isEqualTo("n");
            assertThat(model("{\"unit\":\"character\",\"unitCredits\":0.3}", null)
                    .measuringParam()).isEqualTo("prompt");
            assertThat(model("{\"unit\":\"call\",\"baseCredits\":25}", null)
                    .measuringParam()).isNull();
        }

        @Test
        @DisplayName("an enumerated list of sizes defaults to its smallest, which the model really produces")
        void allowedListDefaultsToItsSmallest() {
            GenerationSpec.Model m = model("{\"unit\":\"second\",\"unitCredits\":60}",
                    "duration_seconds", "{\"duration_seconds\":{\"allowed\":[10,5]}}");
            assertThat(m.defaultMeasurement()).isEqualByComparingTo("5");
            assertThat(m.canAlwaysStateItsSize()).isTrue();
        }

        @Test
        @DisplayName("a bare min/max range is a boundary, not a default: half a second of sound is nobody's ask")
        void rangeIsNotADefault() {
            GenerationSpec.Model m = model("{\"unit\":\"second\",\"unitCredits\":4}",
                    "duration_seconds", "{\"duration_seconds\":{\"min\":0.5,\"max\":30}}");
            assertThat(m.defaultMeasurement()).isNull();
            assertThat(m.canAlwaysStateItsSize()).isFalse();
        }

        @Test
        @DisplayName("declaring the parameter required is the other way to always have a size")
        void requiredCountsAsStatable() {
            GenerationSpec spec = json("""
                    {
                      "kind": "audio", "assetPath": "$binary",
                      "paramMap": { "prompt": "text", "duration_seconds": "duration_seconds" },
                      "models": [{ "id": "m1", "capabilities": ["prompt", "duration_seconds"],
                                   "required": ["duration_seconds"],
                                   "constraints": { "duration_seconds": { "min": 0.5, "max": 30 } },
                                   "price": { "unit": "second", "unitCredits": 4 } }]
                    }
                    """);
            assertThat(spec.model("m1").orElseThrow().canAlwaysStateItsSize()).isTrue();
        }

        @Test
        @DisplayName("a count of assets defaults to one, because one asset is what a provider produces")
        void assetCountDefaultsToOne() {
            assertThat(model("{\"unit\":\"image\",\"unitCredits\":40}", "n").defaultMeasurement())
                    .isEqualByComparingTo("1");
        }

        @Test
        @DisplayName("a count whose smallest legal value is above one defaults to that value, never below it")
        void assetCountRespectsItsFloor() {
            GenerationSpec.Model m = model("{\"unit\":\"image\",\"unitCredits\":40}",
                    "n", "{\"n\":{\"min\":2,\"max\":8}}");
            assertThat(m.defaultMeasurement()).isEqualByComparingTo("2");
        }

        @Test
        @DisplayName("a list of words measures nothing, so it yields no default")
        void wordsAreNotSizes() {
            GenerationSpec.Constraint c = json("""
                    {
                      "kind": "video", "assetPath": "url",
                      "paramMap": { "prompt": "p", "resolution": "r" },
                      "models": [{ "id": "m1", "capabilities": ["prompt", "resolution"],
                                   "constraints": { "resolution": { "allowed": ["480p", "1080p"] } } }]
                    }
                    """).model("m1").orElseThrow().constraints().get("resolution");
            assertThat(c.smallestAllowedNumber()).isNull();
        }

        @Test
        @DisplayName("a model priced by a dimension it does not even accept is refused at parse: nothing could fix it")
        void refusesAPriceNothingCanMeasure() {
            assertThatThrownBy(() -> json("""
                    {
                      "kind": "video", "assetPath": "url",
                      "paramMap": { "prompt": "p" },
                      "models": [{ "id": "m1", "capabilities": ["prompt"],
                                   "price": { "unit": "second", "unitCredits": 60 } }]
                    }
                    """))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("priced per second")
                    .hasMessageContaining("does not accept 'duration_seconds'");
        }

        @Test
        @DisplayName("the same refusal covers a per-image price with no way to say how many")
        void refusesAPerImagePriceWithoutACount() {
            assertThatThrownBy(() -> json("""
                    {
                      "kind": "image", "assetPath": "url",
                      "paramMap": { "prompt": "p" },
                      "models": [{ "id": "m1", "capabilities": ["prompt"],
                                   "price": { "unit": "image", "unitCredits": 40 } }]
                    }
                    """))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("does not accept 'n'");
        }

        @Test
        @DisplayName("a descriptor that neither defaults nor requires still parses, so an upgrade never deletes a model")
        void aSoftBreachStillParses() {
            // This parser also reads descriptors already stored in the catalog.
            // Throwing here would remove a live model from a running platform
            // instead of fixing anything; the seed gate refuses it where it can
            // still be edited, and the request builder refuses the call.
            GenerationSpec spec = json("""
                    {
                      "kind": "audio", "assetPath": "$binary",
                      "paramMap": { "prompt": "text", "duration_seconds": "duration_seconds" },
                      "models": [{ "id": "m1", "capabilities": ["prompt", "duration_seconds"],
                                   "price": { "unit": "second", "unitCredits": 4 } }]
                    }
                    """);
            assertThat(spec.model("m1")).isPresent();
        }

        private GenerationSpec.Model model(String price, String measuringParam) {
            return model(price, measuringParam, null);
        }

        /** One model priced as given, accepting a prompt plus the measuring parameter. */
        private GenerationSpec.Model model(String price, String measuringParam, String constraints) {
            String caps = measuringParam == null ? "\"prompt\"" : "\"prompt\", \"" + measuringParam + "\"";
            String map = measuringParam == null ? "" : ", \"" + measuringParam + "\": \"m\"";
            return json("""
                    {
                      "kind": "video", "assetPath": "url",
                      "paramMap": { "prompt": "p"%s },
                      "models": [{ "id": "m1", "capabilities": [%s]%s, "price": %s }]
                    }
                    """.formatted(map, caps,
                        constraints == null ? "" : ", \"constraints\": " + constraints, price))
                    .model("m1").orElseThrow();
        }

        private GenerationSpec json(String body) {
            try {
                return GenerationSpec.parse(MAPPER.readTree(body), "ctx").orElseThrow();
            } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                throw new IllegalStateException(e);
            }
        }
    }

    @Nested
    @DisplayName("base64 asset paths")
    class Base64AssetPaths {

        @Test
        @DisplayName("a base64 path is read as a path, not as the sentinel plus a name")
        void parsesTheSentinel() {
            GenerationSpec spec = GenerationSpec.parse(json(VALID.replace(
                    "\"assetPath\": \"content.video_url\"",
                    "\"assetPath\": \"$base64:data[0].b64_json\"")), "ctx").orElseThrow();

            assertThat(spec.isBase64Asset()).isTrue();
            assertThat(spec.isBinaryAsset()).isFalse();
            assertThat(spec.base64Path()).isEqualTo("data[0].b64_json");
        }

        @Test
        @DisplayName("a wildcard is accepted, because the part carrying the image moves between calls")
        void acceptsWildcard() {
            GenerationSpec spec = GenerationSpec.parse(json(VALID.replace(
                    "\"assetPath\": \"content.video_url\"",
                    "\"assetPath\": \"$base64:candidates[0].content.parts[*].inlineData.data\"")),
                    "ctx").orElseThrow();

            assertThat(spec.base64Path()).endsWith("parts[*].inlineData.data");
        }

        @Test
        @DisplayName("the sentinel with no path is refused: it names nothing to read")
        void refusesEmptyPath() {
            assertThatThrownBy(() -> GenerationSpec.parse(json(VALID.replace(
                    "\"assetPath\": \"content.video_url\"", "\"assetPath\": \"$base64:\"")), "ctx"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("needs the path the base64 payload sits at");
        }

        @Test
        @DisplayName("a malformed base64 path is refused at parse, not discovered after a paid call")
        void refusesMalformedPath() {
            assertThatThrownBy(() -> GenerationSpec.parse(json(VALID.replace(
                    "\"assetPath\": \"content.video_url\"",
                    "\"assetPath\": \"$base64:data[[0]].b64\"")), "ctx"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("malformed response path");
        }

        @Test
        @DisplayName("a malformed URL asset path is refused too, not only a base64 one")
        void refusesAMalformedPlainAssetPath() {
            // The URL camp is read by the same walker, so a typo there fails the
            // same way and just as late: after the call has been charged.
            assertThatThrownBy(() -> GenerationSpec.parse(json(VALID.replace(
                    "\"assetPath\": \"content.video_url\"",
                    "\"assetPath\": \"content[[0]].video_url\"")), "ctx"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("malformed response path");
        }

        @Test
        @DisplayName("a wildcard stays out of WRITE paths, where it could not mean anything")
        void refusesWildcardInAWritePath() {
            assertThatThrownBy(() -> GenerationSpec.parse(json(VALID.replace(
                    "\"prompt\": \"content[0].text\"", "\"prompt\": \"content[*].text\"")), "ctx"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("malformed upstream path");
        }
    }

    @Nested
    @DisplayName("values a model pins for itself")
    class PinnedModelValues {

        /** Two tiers of one upstream model, priced apart, exactly like OpenAI's quality tiers. */
        private static final String TIERED = """
                {
                  "kind": "image",
                  "modelParam": "model",
                  "assetPath": "$base64:data[0].b64_json",
                  "paramMap": { "prompt": "prompt" },
                  "models": [
                    {
                      "id": "img-low", "upstream": "img", "capabilities": ["prompt"],
                      "constants": { "quality": "low" },
                      "price": { "unit": "call", "baseCredits": 6 }
                    },
                    {
                      "id": "img-high", "upstream": "img", "capabilities": ["prompt"],
                      "constants": { "quality": "high" },
                      "price": { "unit": "call", "baseCredits": 211 }
                    }
                  ]
                }
                """;

        @Test
        @DisplayName("each tier keeps its own pinned value, so two prices can share one upstream model")
        void eachModelKeepsItsOwnPin() {
            GenerationSpec spec = GenerationSpec.parse(json(TIERED), "ctx").orElseThrow();

            assertThat(spec.model("img-low").orElseThrow().constants())
                    .containsEntry("quality", "low");
            assertThat(spec.model("img-high").orElseThrow().constants())
                    .containsEntry("quality", "high");
        }

        @Test
        @DisplayName("a model that pins nothing carries an empty map, never null")
        void defaultsToEmpty() {
            GenerationSpec spec = GenerationSpec.parse(json(VALID), "ctx").orElseThrow();

            assertThat(spec.model("demo-1.0").orElseThrow().constants()).isEmpty();
        }

        @Test
        @DisplayName("pinning a value the same model also accepts is refused: the caller's value is written last")
        void refusesAPinTheCallerCouldOverwrite() {
            // The hazard this rule exists for: the price is computed for the
            // pinned tier, and the request runs on the caller's.
            String clashing = """
                    {
                      "kind": "image",
                      "modelParam": "model",
                      "assetPath": "$base64:data[0].b64_json",
                      "paramMap": { "prompt": "prompt", "quality": "quality" },
                      "models": [{
                        "id": "img-low", "upstream": "img",
                        "capabilities": ["prompt", "quality"],
                        "constants": { "quality": "low" },
                        "price": { "unit": "call", "baseCredits": 6 }
                      }]
                    }
                    """;

            assertThatThrownBy(() -> GenerationSpec.parse(json(clashing), "ctx"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("would overwrite the pinned one");
        }

        @Test
        @DisplayName("pinning the model selector is refused: it would run a model other than the one priced")
        void refusesPinningTheModelParam() {
            String clashing = TIERED.replace("\"constants\": { \"quality\": \"low\" }",
                    "\"constants\": { \"model\": \"something-else\" }");

            assertThatThrownBy(() -> GenerationSpec.parse(json(clashing), "ctx"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("writes to the modelParam");
        }

        @Test
        @DisplayName("pinning a path the endpoint's own constants build is refused")
        void refusesCollidingWithEndpointConstants() {
            String clashing = TIERED.replace("\"paramMap\": { \"prompt\": \"prompt\" },",
                    "\"paramMap\": { \"prompt\": \"prompt\" }, \"constants\": { \"quality\": \"auto\" },");

            assertThatThrownBy(() -> GenerationSpec.parse(json(clashing), "ctx"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("generation.constants also sets");
        }

        @Test
        @DisplayName("a pin is refused on the path an input asset's MEDIA TYPE lands, not only its value")
        void refusesAPinUnderAMimePath() {
            // The second write of the same binding. The input resolver writes it
            // at dispatch, AFTER the builder wrote the pin, so a pin sitting
            // there is overwritten just as surely as one on the value path.
            String clashing = """
                    {
                      "kind": "image", "modelParam": "model",
                      "assetPath": "$base64:data[0].b64_json",
                      "paramMap": {
                        "prompt": "prompt",
                        "input_image": {
                          "path": "img", "encoding": "base64", "role": "source", "mimePath": "quality"
                        }
                      },
                      "models": [{
                        "id": "img-low", "upstream": "img",
                        "capabilities": ["prompt", "input_image"],
                        "constants": { "quality": "low" },
                        "price": { "unit": "call", "baseCredits": 6 }
                      }]
                    }
                    """;

            assertThatThrownBy(() -> GenerationSpec.parse(json(clashing), "ctx"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("would overwrite the pinned one");
        }

        @Test
        @DisplayName("a pin on a path only ANOTHER model exposes is allowed, since one model runs per call")
        void allowsAPinTheRunningModelCannotReach() {
            // Deliberate, and pinned here so a later tightening cannot remove it
            // in silence: refusing this would forbid the ordinary shape where
            // most models expose a parameter and one pins it instead.
            String mixed = """
                    {
                      "kind": "image", "modelParam": "model",
                      "assetPath": "$base64:data[0].b64_json",
                      "paramMap": { "prompt": "prompt", "quality": "quality" },
                      "models": [
                        { "id": "img-tuned", "upstream": "img",
                          "capabilities": ["prompt", "quality"],
                          "price": { "unit": "call", "baseCredits": 20 } },
                        { "id": "img-fixed", "upstream": "img",
                          "capabilities": ["prompt"],
                          "constants": { "quality": "low" },
                          "price": { "unit": "call", "baseCredits": 6 } }
                      ]
                    }
                    """;

            GenerationSpec spec = GenerationSpec.parse(json(mixed), "ctx").orElseThrow();

            assertThat(spec.model("img-fixed").orElseThrow().constants())
                    .containsEntry("quality", "low");
            assertThat(spec.model("img-tuned").orElseThrow().accepts("quality")).isTrue();
        }

        @Test
        @DisplayName("two pins of the same model may not land on the same place")
        void refusesTwoPinsThatCollide() {
            String clashing = TIERED.replace("\"constants\": { \"quality\": \"low\" }",
                    "\"constants\": { \"quality\": \"low\", \"quality[0]\": \"high\" }");

            assertThatThrownBy(() -> GenerationSpec.parse(json(clashing), "ctx"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("which collide");
        }

        @Test
        @DisplayName("a pinned value must be a scalar, since a nested shape is built with indexed paths")
        void refusesContainerPin() {
            String clashing = TIERED.replace("\"constants\": { \"quality\": \"low\" }",
                    "\"constants\": { \"quality\": { \"tier\": \"low\" } }");

            assertThatThrownBy(() -> GenerationSpec.parse(json(clashing), "ctx"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("must be a scalar");
        }
    }

    @Nested
    @DisplayName("input assets and the form the provider wants them in")
    class AssetEncodings {

        private static final String WITH_ASSET = """
                {
                  "kind": "video", "assetPath": "output[0]",
                  "paramMap": {
                    "prompt": "promptText",
                    "input_image": { "path": "promptImage", "encoding": "data_url", "role": "source" }
                  },
                  "models": [{ "id": "v-1", "capabilities": ["prompt", "input_image"] }]
                }
                """;

        @Test
        @DisplayName("an encoding is parsed onto the binding that carries the file")
        void parsesEncoding() {
            GenerationSpec spec = GenerationSpec.parse(json(WITH_ASSET), "ctx").orElseThrow();

            assertThat(spec.paramMap().get("input_image").encoding())
                    .isEqualTo(GenerationSpec.AssetEncoding.DATA_URL);
            assertThat(spec.paramMap().get("prompt").encoding()).isNull();
        }

        @Test
        @DisplayName("an input asset with NO encoding is refused, which is exactly the state that used to ship")
        void refusesAnAssetWithoutEncoding() {
            // Before this rule the FileRef was written into the provider's body
            // as a Map. Nothing failed at import, nothing failed at build, and
            // the provider refused a call that had already been paid for.
            assertThatThrownBy(() -> GenerationSpec.parse(json(WITH_ASSET.replace(
                    "{ \"path\": \"promptImage\", \"encoding\": \"data_url\", \"role\": \"source\" }",
                    "\"promptImage\"")), "ctx"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("carries a file, so it needs an 'encoding'");
        }

        @Test
        @DisplayName("an unknown encoding is refused with the list of the ones that exist")
        void refusesUnknownEncoding() {
            assertThatThrownBy(() -> GenerationSpec.parse(json(WITH_ASSET.replace(
                    "\"data_url\"", "\"magic\"")), "ctx"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("is not a supported form")
                    .hasMessageContaining("data_url");
        }

        @Test
        @DisplayName("an encoding on a parameter that carries a value is refused: there is nothing to encode")
        void refusesEncodingOnAPlainParameter() {
            assertThatThrownBy(() -> GenerationSpec.parse(json(WITH_ASSET.replace(
                    "\"prompt\": \"promptText\"",
                    "\"prompt\": { \"path\": \"promptText\", \"encoding\": \"base64\" }")), "ctx"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("only means something for a parameter that carries a file");
        }

        @Test
        @DisplayName("a media-type path belongs to the encoding that needs one, and no other")
        void refusesMimePathOnDataUrl() {
            assertThatThrownBy(() -> GenerationSpec.parse(json(WITH_ASSET.replace(
                    "\"encoding\": \"data_url\", \"role\": \"source\" }",
                    "\"encoding\": \"data_url\", \"role\": \"source\", \"mimePath\": \"mime\" }")), "ctx"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("only for the 'base64' encoding");
        }

        @Test
        @DisplayName("a media-type path collides like any other write, since it is one")
        void mimePathTakesPartInCollisionChecks() {
            assertThatThrownBy(() -> GenerationSpec.parse(json(WITH_ASSET.replace(
                    "{ \"path\": \"promptImage\", \"encoding\": \"data_url\", \"role\": \"source\" }",
                    "{ \"path\": \"img\", \"encoding\": \"base64\", \"role\": \"source\", \"mimePath\": \"promptText\" }")), "ctx"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("which collide");
        }
    }

    @Nested
    @DisplayName("text length limits")
    class TextLimits {

        private static final String CAPPED = """
                {
                  "kind": "image", "assetPath": "$binary",
                  "paramMap": { "prompt": "prompt" },
                  "models": [{
                    "id": "img-1", "capabilities": ["prompt"],
                    "constraints": { "prompt": { "maxLength": 10 } }
                  }]
                }
                """;

        @Test
        @DisplayName("a prompt over the provider's cap is refused before the call is paid for")
        void refusesOverlongPrompt() {
            GenerationSpec spec = GenerationSpec.parse(json(CAPPED), "ctx").orElseThrow();
            GenerationSpec.Constraint c =
                    spec.model("img-1").orElseThrow().constraints().get("prompt");

            assertThat(c.violation("a".repeat(11)))
                    .contains("at most 10 characters")
                    .contains("got 11");
        }

        @Test
        @DisplayName("a prompt at the cap is accepted, so the limit is not off by one")
        void acceptsPromptAtTheCap() {
            GenerationSpec spec = GenerationSpec.parse(json(CAPPED), "ctx").orElseThrow();

            assertThat(spec.model("img-1").orElseThrow().constraints().get("prompt")
                    .violation("a".repeat(10))).isNull();
        }

        @Test
        @DisplayName("a misspelled constraint key is refused, where it used to be dropped in silence")
        void refusesUnknownConstraintKey() {
            // Silently ignoring it made the seed READ as if the value were
            // restricted while nothing checked it, and the provider refused the
            // call after the charge.
            assertThatThrownBy(() -> GenerationSpec.parse(
                    json(CAPPED.replace("\"maxLength\": 10", "\"maxLenght\": 10")), "ctx"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("unknown key 'maxLenght'");
        }

        @Test
        @DisplayName("a non-positive maxLength is refused: it would reject every possible value")
        void refusesNonPositiveMaxLength() {
            assertThatThrownBy(() -> GenerationSpec.parse(
                    json(CAPPED.replace("\"maxLength\": 10", "\"maxLength\": 0")), "ctx"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("maxLength must be > 0");
        }
    }

    @Nested
    @DisplayName("several files at once, each with its own meaning")
    class RoleNamedSlots {

        /** xAI 1.5: a first frame, a last frame and up to three references in ONE call. */
        private static final String THREE_SLOTS = """
                {
                  "kind": "video", "assetPath": "url",
                  "paramMap": {
                    "prompt": "prompt",
                    "input_image": { "path": "image.url", "encoding": "data_url", "role": "first_frame" },
                    "last_frame_image": { "path": "last_frame.url", "encoding": "data_url", "role": "last_frame" },
                    "reference_image": {
                      "path": "reference_images[0].url", "encoding": "data_url",
                      "role": "reference", "maxItems": 3
                    }
                  },
                  "models": [{
                    "id": "v-1",
                    "capabilities": ["prompt", "input_image", "last_frame_image", "reference_image"]
                  }]
                }
                """;

        @Test
        @DisplayName("three image slots coexist on one endpoint, each keeping its own role")
        void parsesThreeSlots() {
            GenerationSpec spec = GenerationSpec.parse(json(THREE_SLOTS), "ctx").orElseThrow();

            assertThat(spec.paramMap().get("input_image").role())
                    .isEqualTo(GenerationSpec.AssetRole.FIRST_FRAME);
            assertThat(spec.paramMap().get("last_frame_image").role())
                    .isEqualTo(GenerationSpec.AssetRole.LAST_FRAME);
            assertThat(spec.paramMap().get("reference_image").role())
                    .isEqualTo(GenerationSpec.AssetRole.REFERENCE);
            assertThat(spec.paramMap().get("reference_image").maxItems()).isEqualTo(3);
        }

        @Test
        @DisplayName("'last_frame' is a role the surfaces can name, so a closing frame is declarable at all")
        void lastFrameIsAKnownRole() {
            // Before it existed the only way to declare the second frame of a
            // clip was to call it something it is not.
            assertThat(GenerationSpec.AssetRole.LAST_FRAME.wire()).isEqualTo("last_frame");
        }

        @Test
        @DisplayName("two slots with the same role are refused: nothing downstream could tell them apart")
        void refusesTwoSlotsSharingARole() {
            // Both would be labelled "Reference image" in the composer menu, and
            // the picker behind each entry would be a coin toss.
            assertThatThrownBy(() -> GenerationSpec.parse(json(THREE_SLOTS.replace(
                    "\"path\": \"image.url\", \"encoding\": \"data_url\", \"role\": \"first_frame\"",
                    "\"path\": \"image.url\", \"encoding\": \"data_url\", \"role\": \"reference\"")), "ctx"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("two file slots with the same role 'reference'");
        }

        @Test
        @DisplayName("a role-named slot cannot declare a different role: the label and the wire would disagree")
        void refusesANameThatContradictsItsRole() {
            // The seed author reads the NAME and every surface labels the field
            // from the ROLE, so this ships a field called "Reference image" whose
            // file is sent as the closing frame.
            assertThatThrownBy(() -> GenerationSpec.parse(json(THREE_SLOTS.replace(
                    "\"path\": \"last_frame.url\", \"encoding\": \"data_url\", \"role\": \"last_frame\"",
                    "\"path\": \"last_frame.url\", \"encoding\": \"data_url\", \"role\": \"source\"")), "ctx"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("is the 'last_frame' slot, so its role can only be 'last_frame'");
        }

        @Test
        @DisplayName("a role-named slot still has to say its role, like every other file slot")
        void refusesARoleNamedSlotWithNoRole() {
            assertThatThrownBy(() -> GenerationSpec.parse(json(THREE_SLOTS.replace(
                    ", \"role\": \"last_frame\"", "")), "ctx"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("carries a file, so it needs");
        }
    }

    @Nested
    @DisplayName("slots that only work as a pair")
    class PairedSlots {

        /** Seedance: two images or none, never the closing one alone. */
        private static final String PAIRED = """
                {
                  "kind": "video", "assetPath": "url",
                  "paramMap": {
                    "prompt": "content[0].text",
                    "first_frame_image": {
                      "path": "content[1].image_url.url", "encoding": "data_url", "role": "first_frame"
                    },
                    "last_frame_image": {
                      "path": "content[2].image_url.url", "encoding": "data_url", "role": "last_frame",
                      "requires": ["first_frame_image"]
                    }
                  },
                  "models": [{
                    "id": "s-1",
                    "capabilities": ["prompt", "first_frame_image", "last_frame_image"]
                  }]
                }
                """;

        @Test
        @DisplayName("the companion is parsed onto the slot that cannot run without it")
        void parsesTheCompanion() {
            GenerationSpec spec = GenerationSpec.parse(json(PAIRED), "ctx").orElseThrow();

            assertThat(spec.paramMap().get("last_frame_image").requires())
                    .containsExactly("first_frame_image");
            assertThat(spec.paramMap().get("first_frame_image").requires()).isEmpty();
        }

        @Test
        @DisplayName("a companion this endpoint cannot send is refused: the slot could never be used")
        void refusesAnUnmappedCompanion() {
            assertThatThrownBy(() -> GenerationSpec.parse(json(PAIRED.replace(
                    "\"requires\": [\"first_frame_image\"]", "\"requires\": [\"input_image\"]")), "ctx"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("which this endpoint does not map");
        }

        @Test
        @DisplayName("a model offering one half of a pair is refused: every call using it would be turned down")
        void refusesAModelMissingTheOtherHalf() {
            assertThatThrownBy(() -> GenerationSpec.parse(json(PAIRED.replace(
                    "\"capabilities\": [\"prompt\", \"first_frame_image\", \"last_frame_image\"]",
                    "\"capabilities\": [\"prompt\", \"last_frame_image\"]")), "ctx"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("only works together with 'first_frame_image'");
        }

        @Test
        @DisplayName("a slot cannot require itself, which would be a call nobody can make")
        void refusesSelfReference() {
            assertThatThrownBy(() -> GenerationSpec.parse(json(PAIRED.replace(
                    "\"requires\": [\"first_frame_image\"]", "\"requires\": [\"last_frame_image\"]")), "ctx"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("cannot name 'last_frame_image' itself");
        }

        @Test
        @DisplayName("an empty companion list is refused: it reads as a rule and enforces nothing")
        void refusesAnEmptyList() {
            assertThatThrownBy(() -> GenerationSpec.parse(json(PAIRED.replace(
                    "[\"first_frame_image\"]", "[]")), "ctx"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("must be a non-empty array");
        }

        @Test
        @DisplayName("a blank companion name is refused rather than quietly ignored")
        void refusesABlankCompanion() {
            assertThatThrownBy(() -> GenerationSpec.parse(json(PAIRED.replace(
                    "[\"first_frame_image\"]", "[\"   \"]")), "ctx"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("must be non-blank parameter names");
        }

        @Test
        @DisplayName("a MISSPELLED rule is refused, where it used to disarm the guard in silence")
        void refusesAnUnknownBindingKey() {
            // The worst outcome available: `require` parses, the pair is unenforced, the import is
            // green, and the provider refusal the rule exists to prevent comes back with nothing
            // anywhere pointing at the cause.
            assertThatThrownBy(() -> GenerationSpec.parse(json(PAIRED.replace(
                    "\"requires\":", "\"require\":")), "ctx"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("has unknown key 'require'");
        }

        @Test
        @DisplayName("a companion outside the platform vocabulary is refused with the list that exists")
        void refusesAnUnknownCompanion() {
            assertThatThrownBy(() -> GenerationSpec.parse(json(PAIRED.replace(
                    "\"requires\": [\"first_frame_image\"]", "\"requires\": [\"opening_shot\"]")), "ctx"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("is not a unified parameter");
        }
    }

    @Nested
    @DisplayName("slots that cannot travel together")
    class ExcludedSlots {

        /** Seedance again: pinning a frame and lending a reference are different kinds of request. */
        private static final String EXCLUSIVE = """
                {
                  "kind": "video", "assetPath": "url",
                  "paramMap": {
                    "prompt": "content[0].text",
                    "first_frame_image": {
                      "path": "content[1].image_url.url", "encoding": "data_url",
                      "role": "first_frame", "excludes": ["input_image"]
                    },
                    "input_image": {
                      "path": "content[3].image_url.url", "encoding": "data_url",
                      "role": "reference", "maxItems": 4
                    }
                  },
                  "models": [{
                    "id": "s-2",
                    "capabilities": ["prompt", "first_frame_image", "input_image"]
                  }]
                }
                """;

        @Test
        @DisplayName("the forbidden pair is parsed onto the slot that declared it")
        void parsesTheExclusion() {
            GenerationSpec spec = GenerationSpec.parse(json(EXCLUSIVE), "ctx").orElseThrow();

            assertThat(spec.paramMap().get("first_frame_image").excludes())
                    .containsExactly("input_image");
            // Declared once. The other side carries nothing, and is still refused at run time,
            // because a rule that has to be written twice is a rule that gets written once.
            assertThat(spec.paramMap().get("input_image").excludes()).isEmpty();
        }

        @Test
        @DisplayName("both models may take both halves: only sending them together is refused")
        void doesNotForbidTheCapability() {
            // The exclusion is about one CALL, not about what the model can do. Refusing the
            // capability would make the endpoint offer one of its two modes and hide the other.
            GenerationSpec spec = GenerationSpec.parse(json(EXCLUSIVE), "ctx").orElseThrow();
            assertThat(spec.models().get(0).capabilities())
                    .contains("first_frame_image", "input_image");
        }

        @Test
        @DisplayName("a file slot NAMING a value parameter is refused too, not only a rule written on one")
        void refusesARuleNamingAValueParameter() {
            // The other spelling. Published in the listing, printed under the field, and able to
            // block nothing: the composer closes a slot by looking at the files attached, and a
            // value has none. Which half was refused used to depend on which binding the author
            // happened to be writing.
            assertThatThrownBy(() -> GenerationSpec.parse(json(EXCLUSIVE.replace(
                    "\"role\": \"first_frame\", \"excludes\": [\"input_image\"]",
                    "\"role\": \"first_frame\", \"excludes\": [\"aspect_ratio\"]")), "ctx"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("is not a file slot");
        }

        @Test
        @DisplayName("a pairing rule on a parameter carrying a VALUE is refused, not half-supported")
        void refusesARuleOnAValueParameter() {
            // Enforced at build time and drawn by no surface is the worst of both: it reads
            // like a guarantee. The restriction an author actually wants there (an aspect
            // ratio forbidden beside a pinned frame) is per MODEL, and a binding shared by
            // every model of the endpoint cannot express it.
            assertThatThrownBy(() -> GenerationSpec.parse(json(EXCLUSIVE.replace(
                    "\"prompt\": \"content[0].text\",",
                    "\"prompt\": \"content[0].text\", \"aspect_ratio\": "
                            + "{\"path\": \"ratio\", \"excludes\": [\"first_frame_image\"]},")), "ctx"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("is a rule about FILE slots");
        }

        @Test
        @DisplayName("a forbidden partner this endpoint cannot send is refused as a rule about nothing")
        void refusesAnUnmappedPartner() {
            assertThatThrownBy(() -> GenerationSpec.parse(json(EXCLUSIVE.replace(
                    "\"excludes\": [\"input_image\"]", "\"excludes\": [\"input_video\"]")), "ctx"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("which this endpoint does not map");
        }

        @Test
        @DisplayName("requiring and excluding the same slot is refused: no caller could satisfy it")
        void refusesARuleThatContradictsItself() {
            assertThatThrownBy(() -> GenerationSpec.parse(json(EXCLUSIVE.replace(
                    "\"role\": \"first_frame\", \"excludes\": [\"input_image\"]",
                    "\"role\": \"first_frame\", \"requires\": [\"input_image\"], "
                            + "\"excludes\": [\"input_image\"]")), "ctx"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("both requires and excludes");
        }

        @Test
        @DisplayName("one slot requiring what another forbids is refused, however it is spelled")
        void refusesTheSameContradictionWrittenFromBothSides() {
            // The contradiction survives being split across two bindings, and the reader of either
            // one alone sees a rule that looks fine.
            assertThatThrownBy(() -> GenerationSpec.parse(json(EXCLUSIVE.replace(
                    "\"role\": \"reference\", \"maxItems\": 4",
                    "\"role\": \"reference\", \"maxItems\": 4, "
                            + "\"requires\": [\"first_frame_image\"]")), "ctx"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("No caller can satisfy both");
        }
    }

    @Nested
    @DisplayName("one meaning, one slot")
    class RoleUniqueness {

        /** A video and an audio, both genuinely sources, which is the case the rule costs. */
        private static final String TWO_SOURCES = """
                {
                  "kind": "video", "assetPath": "url",
                  "paramMap": {
                    "prompt": "prompt",
                    "input_video": {"path": "video", "encoding": "data_url", "role": "source"},
                    "input_audio": {"path": "audio", "encoding": "data_url", "role": "source"}
                  },
                  "models": [{"id": "v-2", "capabilities": ["prompt", "input_video", "input_audio"]}]
                }
                """;

        @Test
        @DisplayName("two slots of DIFFERENT kinds still cannot share a role, and that is deliberate")
        void refusesEvenAcrossKinds() {
            // Recorded rather than discovered: every surface labels a slot from its role alone, so
            // two "Source" fields are two identical labels over two different pickers. The day a
            // provider needs this shape, the fix is to key the label on role AND kind - not to
            // drop the rule, and not to mis-declare one of the two roles.
            assertThatThrownBy(() -> GenerationSpec.parse(json(TWO_SOURCES), "ctx"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("two file slots with the same role 'source'");
        }
    }
}
