package com.apimarketplace.catalog.tools.generation;

import com.apimarketplace.agent.tools.ToolErrorCode;
import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionContext;
import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionResult;
import com.apimarketplace.catalog.service.generation.GenerationAssetResolver;
import com.apimarketplace.catalog.service.generation.GenerationProvenanceRecorder;
import com.apimarketplace.catalog.service.generation.GenerationInputResolver;
import com.apimarketplace.catalog.service.generation.GenerationLimits;
import com.apimarketplace.catalog.service.generation.DynamicOptionsResolver;
import com.apimarketplace.catalog.service.generation.GenerationRegistry;
import com.apimarketplace.catalog.service.generation.GenerationSpec;
import com.apimarketplace.catalog.tools.CatalogExecuteModule;
import com.apimarketplace.interfaces.client.InterfaceClient;
import com.apimarketplace.storage.client.StorageClient;
import com.apimarketplace.interfaces.client.dto.ImageGenerationInterfaceRequest;
import com.apimarketplace.interfaces.client.dto.InterfaceDto;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The unified generation tool.
 *
 * <p>The cases that matter most are the ones where NOTHING must happen: an
 * unknown model, a parameter the model does not accept or a value outside its
 * limits must all be refused without the catalog ever being called, because a
 * dispatched call is a call the customer pays for.
 */
class GenerationToolTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private GenerationRegistry registry;
    private CatalogExecuteModule executeModule;
    private GenerationAssetResolver assetResolver;
    private InterfaceClient interfaceClient;
    private StorageClient storage;
    private com.apimarketplace.catalog.service.generation.GenerationProvenanceRecorder provenanceRecorder;
    private GenerationToolsProvider provider;
    private GenerationModule module;

    private static GenerationSpec spec(String json) {
        try {
            return GenerationSpec.parse(MAPPER.readTree(json), "test").orElseThrow();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static final GenerationSpec VIDEO_SPEC = spec("""
            {
              "kind": "video", "modelParam": "model",              "assetPath": "content.video_url",
              "paramMap": { "prompt": "content[0].text", "duration_seconds": "duration" },
              "constants": { "content[0].type": "text" },
              "models": [{
                "id": "vid-fast", "upstream": "vendor-fast", "label": "Vid Fast",
                "capabilities": ["prompt", "duration_seconds"],
                "constraints": { "duration_seconds": { "allowed": [5, 10] } },
                "price": { "unit": "second", "unitCredits": 30 }
              }]
            }
            """);

    /**
     * A model whose PRICE moves with a choice that is not its size.
     *
     * <p>Ten seconds at 1080p and ten seconds at 720p are the same ten seconds and not the same
     * amount of money. The factor is the only thing that expresses that, and it travels beside the
     * size rather than inside it, so the run still reports the duration that was produced.
     */
    private static final GenerationSpec MODULATED_SPEC = spec("""
            {
              "kind": "video", "modelParam": "model", "assetPath": "content.video_url",
              "paramMap": {
                "prompt": "content[0].text", "duration_seconds": "duration",
                "resolution": "resolution"
              },
              "constants": { "content[0].type": "text" },
              "models": [{
                "id": "vid-tiered", "upstream": "vendor-tiered", "label": "Vid Tiered",
                "capabilities": ["prompt", "duration_seconds", "resolution"],
                "required": ["resolution"],
                "constraints": {
                  "duration_seconds": { "allowed": [5, 10] },
                  "resolution": { "allowed": ["720p", "1080p"] }
                },
                "price": {
                  "unit": "second", "unitCredits": 30,
                  "modifiers": [
                    { "param": "resolution", "multiply": { "720p": 1, "1080p": 2 } }
                  ]
                }
              }]
            }
            """);

    private static final GenerationRegistry.GenerationModel MODULATED =
            model(MODULATED_SPEC, "vid-tiered");

    private static final GenerationSpec VOICE_SPEC = spec("""
            {
              "kind": "voice", "assetPath": "$binary",
              "paramMap": { "prompt": "text" },
              "models": [{
                "id": "tts-fast", "label": "TTS Fast",
                "capabilities": ["prompt"],
                "price": { "unit": "character", "unitCredits": 0.2, "minCredits": 1 }
              }]
            }
            """);

    private static GenerationRegistry.GenerationModel model(GenerationSpec spec, String id) {
        return model(spec, id, java.util.Map.of());
    }

    /** The same model, sold by somebody else. */
    private static GenerationRegistry.GenerationModel modelFrom(GenerationSpec spec, String id,
                                                                 String slug, String name) {
        return new GenerationRegistry.GenerationModel(
                id, spec.kind(), spec.model(id).orElseThrow(), spec,
                UUID.randomUUID(), slug + "/" + id, slug, name,
                slug, slug + "_key", "async_poll", java.util.Map.of());
    }

    /** The same model, carrying values inherited from the catalogue. */
    private static GenerationRegistry.GenerationModel model(GenerationSpec spec, String id,
                                                            java.util.Map<String, java.util.List<String>> inherited) {
        return new GenerationRegistry.GenerationModel(
                id, spec.kind(), spec.model(id).orElseThrow(), spec,
                UUID.randomUUID(), "provider/" + id, "provider", "Provider Inc",
                "provider", "provider_key", "async_poll", inherited);
    }

    /** An endpoint whose own constants are sent on every call, tier or no tier. */
    private static final GenerationSpec SCAFFOLDED_SPEC = spec("""
            {
              "kind": "video", "assetPath": "content.video_url",
              "paramMap": { "prompt": "content[0].text" },
              "constants": { "content[0].type": "text" },
              "models": [{
                "id": "scaffolded", "label": "Scaffolded",
                "capabilities": ["prompt"],
                "price": { "unit": "call", "baseCredits": 10 }
              }]
            }
            """);

    /** Two ids that accept the same parameters and differ only in what they pin. */
    private static final GenerationSpec TIERED_SPEC = spec("""
            {
              "kind": "image", "modelParam": "model", "assetPath": "$binary",
              "paramMap": { "prompt": "prompt" },
              "models": [
                {
                  "id": "tier-plain", "upstream": "img", "label": "Plain",
                  "capabilities": ["prompt"],
                  "price": { "unit": "call", "baseCredits": 10 }
                },
                {
                  "id": "tier-tall", "upstream": "img", "label": "Tall",
                  "capabilities": ["prompt"],
                  "constants": { "size": "1024x1536", "quality": "high" },
                  "price": { "unit": "call", "baseCredits": 15 }
                }
              ]
            }
            """);

    /** Listed per minute, still measured in seconds like every other duration. */
    private static final GenerationSpec MUSIC_PER_MINUTE_SPEC = spec("""
            {
              "kind": "music", "modelParam": "model_id", "assetPath": "$binary",
              "paramMap": {
                "prompt": "prompt",
                "duration_seconds": { "path": "music_length_ms", "scale": 1000 }
              },
              "models": [{
                "id": "music-min", "upstream": "music_v1", "label": "Music per minute",
                "capabilities": ["prompt", "duration_seconds"],
                "price": { "unit": "minute", "unitCredits": 480 }
              }]
            }
            """);

    /**
     * A model that DEFAULTS its own count above one.
     *
     * <p>The seed gate refuses this shape, so it can only arrive by another
     * road: an API submitted over HTTP, a signed catalog bundle, a row edited
     * by hand. A caller never mentions a count and is billed for four assets
     * while one is stored, which is why the runtime guard reads the MEASURED
     * quantity rather than the caller's input.
     */
    private static final GenerationSpec BULK_DEFAULT_SPEC = spec("""
            {
              "kind": "image", "assetPath": "$binary",
              "paramMap": { "prompt": "prompt", "n": "count" },
              "models": [{
                "id": "bulk-img", "label": "Bulk",
                "capabilities": ["prompt", "n"],
                "constraints": { "n": { "min": 4 } },
                "price": { "unit": "image", "unitCredits": 100 }
              }]
            }
            """);

    /** Sold at one price per call: no parameter states how big it is. */
    private static final GenerationSpec IMAGE_FLAT_SPEC = spec("""
            {
              "kind": "image", "assetPath": "$binary",
              "paramMap": { "prompt": "prompt" },
              "models": [{
                "id": "img-flat", "label": "Image Flat",
                "capabilities": ["prompt"],
                "price": { "unit": "call", "baseCredits": 12 }
              }]
            }
            """);

    /**
     * A model that takes no prompt: an upscaler is instructed by the image it is
     * given, not by text. {@code prompt} is only required of a model that lists
     * it as a capability, so this is the shape that reaches create without one.
     */
    private static final GenerationSpec PROMPTLESS_SPEC = spec("""
            {
              "kind": "image", "assetPath": "$binary",
              "paramMap": { "n": "count" },
              "models": [{
                "id": "upscale-x2", "label": "Upscale",
                "capabilities": ["n"],
                "price": { "unit": "image", "unitCredits": 4 }
              }]
            }
            """);

    /** Image to video: the shape the input-asset path exists for. */
    private static final GenerationSpec ANIMATOR_SPEC = spec("""
            {
              "kind": "video", "modelParam": "model", "assetPath": "output[0]",
              "paramMap": {
                "prompt": "promptText",
                "input_image": { "path": "promptImage", "encoding": "data_url", "role": "source" }
              },
              "models": [{
                "id": "i2v-1", "upstream": "vendor-i2v", "label": "Animator",
                "capabilities": ["prompt", "input_image"],
                "required": ["input_image"],
                "price": { "unit": "call", "baseCredits": 50 }
              }]
            }
            """);

    private static final GenerationRegistry.GenerationModel ANIMATOR = model(ANIMATOR_SPEC, "i2v-1");

    /**
     * The multipart shape: the FileRef travels untouched because the catalog's
     * own encoder downloads the bytes further down. This is what the shipped
     * OpenAI edit models use.
     */
    private static final GenerationSpec EDITOR_SPEC = spec("""
            {
              "kind": "image", "modelParam": "model",
              "assetPath": "$base64:data[0].b64_json",
              "paramMap": {
                "prompt": "prompt",
                "input_image": { "path": "image", "encoding": "file_ref", "role": "source" }
              },
              "models": [{
                "id": "edit-1", "upstream": "vendor-edit", "label": "Editor",
                "capabilities": ["prompt", "input_image"],
                "required": ["input_image"],
                "price": { "unit": "call", "baseCredits": 6 }
              }]
            }
            """);

    private static final GenerationRegistry.GenerationModel EDITOR = model(EDITOR_SPEC, "edit-1");

    private static final GenerationRegistry.GenerationModel BULK_DEFAULT =
            model(BULK_DEFAULT_SPEC, "bulk-img");
    private static final GenerationRegistry.GenerationModel VIDEO = model(VIDEO_SPEC, "vid-fast");
    private static final GenerationRegistry.GenerationModel PROMPTLESS =
            model(PROMPTLESS_SPEC, "upscale-x2");
    private static final GenerationRegistry.GenerationModel IMAGE_FLAT = model(IMAGE_FLAT_SPEC, "img-flat");
    private static final GenerationRegistry.GenerationModel VOICE = model(VOICE_SPEC, "tts-fast");
    private static final GenerationRegistry.GenerationModel MUSIC_PER_MINUTE =
            model(MUSIC_PER_MINUTE_SPEC, "music-min");

    @BeforeEach
    void setUp() {
        registry = mock(GenerationRegistry.class);
        executeModule = mock(CatalogExecuteModule.class);
        assetResolver = mock(GenerationAssetResolver.class);
        interfaceClient = mock(InterfaceClient.class);
        storage = mock(StorageClient.class);
        provenanceRecorder = mock(
                com.apimarketplace.catalog.service.generation.GenerationProvenanceRecorder.class);
        // A stored asset by default. Left unstubbed, the module's asset
        // resolver returns null and every dispatch test ends in an NPE
        // whose ERROR line would hide a real regression later.
        when(assetResolver.resolve(any(), any(), any(), any())).thenReturn(
                new GenerationAssetResolver.Resolved(
                        Map.of("_type", "file", "path", "tenant-1/out.png"), null));
        // A REAL input resolver over a mocked storage, rather than a mocked
        // resolver: what these tests have to prove is the ORDER of resolve and
        // dispatch, and a mock of the thing under test would prove nothing.
        DynamicOptionsResolver optionsResolver = mock(DynamicOptionsResolver.class);
        when(optionsResolver.unifiedDynamicParameters(any())).thenReturn(java.util.Set.of());
        // Kept in a field so the storage pre-check suite can wire a StorageClient onto the very
        // module the provider dispatches to (it is an optional field-injected dependency, null
        // here, which is exactly the "no pre-check" behaviour every other test expects).
        module = new GenerationModule(registry, executeModule, assetResolver,
                new com.apimarketplace.catalog.service.ResponseShaper(),
                new GenerationInputResolver(storage, 20_971_520L), optionsResolver,
                mock(com.apimarketplace.catalog.service.generation.PlatformSalesResolver.class), interfaceClient,
                provenanceRecorder);
        provider = new GenerationToolsProvider(module);

        when(registry.list(null)).thenReturn(List.of(VIDEO, VOICE));
        when(registry.list("video")).thenReturn(List.of(VIDEO));
        when(registry.list("voice")).thenReturn(List.of(VOICE));
        when(registry.kinds()).thenReturn(List.of("video", "voice"));
        when(registry.resolve("vid-fast")).thenReturn(Optional.of(VIDEO));
        when(registry.resolve("tts-fast")).thenReturn(Optional.of(VOICE));
        when(registry.resolve("nope")).thenReturn(Optional.empty());
    }

    private static Map<String, Object> params(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    private ToolExecutionResult call(String toolName, Map<String, Object> p) {
        return provider.execute(toolName, p, mock(ToolExecutionContext.class));
    }

    /**
     * A caller that IS a chat turn: the conversation and message ids are the two
     * credentials the persistence path keys on, and a card belongs to a message.
     */
    private static ToolExecutionContext chatContext() {
        return new ToolExecutionContext(
                "tenant-1",
                Map.of("conversationId", "conv-1", "__messageId__", "msg-1", "__agentId__", "agent-1"),
                Map.of(), Set.of(), null, null, "org-1", null);
    }

    private ToolExecutionResult callAsChat(Map<String, Object> p) {
        return provider.execute("generation", p, chatContext());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> data(ToolExecutionResult r) {
        return (Map<String, Object>) r.data();
    }

    @Nested
    @DisplayName("tool surface")
    class Surface {

        @Test
        @DisplayName("the tool is named for what it does, not for one format")
        void toolIsFormatNeutral() {
            assertThat(provider.getTools()).singleElement()
                    .satisfies(t -> assertThat(t.name()).isEqualTo("generation"));
        }

        @Test
        @DisplayName("the tool answers to its own name only: claiming image_generation would shadow the "
                + "provider that still registers it")
        void doesNotClaimTheLegacyName() {
            ToolExecutionResult r = call("image_generation", params("action", "models"));
            assertThat(r.success()).isFalse();
            assertThat(r.errorCode()).isEqualTo(ToolErrorCode.TOOL_NOT_FOUND);
        }

        @Test
        @DisplayName("an unrelated tool name is refused")
        void unknownToolRefused() {
            ToolExecutionResult r = call("something_else", params("action", "models"));
            assertThat(r.success()).isFalse();
            assertThat(r.errorCode()).isEqualTo(ToolErrorCode.TOOL_NOT_FOUND);
        }

        @Test
        @DisplayName("a missing action is refused with the valid list")
        void missingActionRefused() {
            ToolExecutionResult r = call("generation", params());
            assertThat(r.success()).isFalse();
            assertThat(r.error()).contains("create", "models", "help");
        }

        @Test
        @DisplayName("help explains the price model rather than only naming a number")
        void helpIsHonest() {
            Map<String, Object> help = data(call("generation", params("action", "help")));
            assertThat(help.get("concepts").toString()).contains("per second");
            assertThat(help.get("actions").toString()).contains("file");
            // WHERE a file object comes from, named. Saying only "the whole file
            // object another tool returned" left the one question a cold agent
            // actually has unanswered, and the nearest wrong answer, a file_id
            // or a url, is the one it would reach for.
            assertThat(help.get("concepts").toString())
                    .contains("files(action='get')")
                    .contains("download_file");
        }

        @Test
        @DisplayName("the model listing explains what a generation costs to READ BACK, not only "
                + "what it will cost to run")
        void modelsExplainTheChargeItReports() {
            // The listing already explains the price model. What it did not say is that the answer
            // reports the amount actually taken, and that a missing one means the platform charged
            // nothing - the reading an agent needs before it totals a set of generations.
            Map<String, Object> models = data(call("generation", params("action", "models")));

            assertThat(String.valueOf(models.get("cost_note")))
                    .contains("billed_credits")
                    .contains("ABSENT MEANS NOT CHARGED BY THE PLATFORM");
        }

        @Test
        @DisplayName("what create RETURNS names every field it returns, including what it cost")
        void theReturnsLineNamesTheCharge() {
            // The one line an agent reads to know the shape it gets back. A field the tool returns
            // and this line omits is a field nobody reads: the payload is not explored, it is
            // consumed by whatever the description said would be in it.
            Map<String, Object> help = data(call("generation", params("action", "help")));
            String create = ((Map<?, ?>) help.get("actions")).get("create").toString();

            assertThat(create).contains("billed_credits");
            // And the rule that stops it being summed as a zero.
            assertThat(create).contains("ABSENT MEANS NOT CHARGED BY THE PLATFORM");
        }
    }

    @Nested
    @DisplayName("models")
    class Models {

        @Test
        @DisplayName("a model states what it PINS, which is the only thing telling two same-accepting ids apart")
        void aPinnedTierIsOnTheWire() {
            // Two ids, one accepted parameter, two prices. Without this the
            // difference lives only in the id string, and an agent looking for
            // a tall image has to parse names to find one.
            when(registry.list(null)).thenReturn(List.of(
                    model(TIERED_SPEC, "tier-plain"), model(TIERED_SPEC, "tier-tall")));

            Map<String, Object> d = data(call("generation", params("action", "models")));

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> rows = (List<Map<String, Object>>) d.get("models");
            Map<String, Object> plain = rows.stream()
                    .filter(r -> "tier-plain".equals(r.get("model"))).findFirst().orElseThrow();
            Map<String, Object> tall = rows.stream()
                    .filter(r -> "tier-tall".equals(r.get("model"))).findFirst().orElseThrow();

            assertThat(tall).containsEntry("fixed",
                    java.util.Map.of("size", "1024x1536", "quality", "high"));
            // The ordinary model pins nothing and pays no tokens for the key.
            assertThat(plain).doesNotContainKey("fixed");

            // And the endpoint's own scaffolding counts as fixed too: it is sent
            // on every call of every model, so a field that claims to list what
            // the call always sends cannot show only the tier half.
            when(registry.list(null)).thenReturn(List.of(model(SCAFFOLDED_SPEC, "scaffolded")));
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> scaffolded =
                    (List<Map<String, Object>>) data(call("generation", params("action", "models")))
                            .get("models");
            assertThat(scaffolded.get(0)).containsEntry("fixed",
                    java.util.Map.of("content[0].type", "text"));
        }

        @Test
        @DisplayName("the filters an agent may pass are DECLARED, not only handled")
        void theFiltersAreInTheInputSchema() {
            // An argument the handler reads and the schema omits is one an agent
            // can see described and cannot send: the same trap this tool already
            // hit once with action='options' and its 'parameter' argument.
            var tool = provider.getTools().get(0);

            assertThat(tool.parameters().stream().map(p -> p.name()))
                    .contains("kind", "provider", "parameter", "model");
            assertThat(tool.inputSchema().toString()).contains("provider");
        }

        @Test
        @DisplayName("an unmatched provider says the filter matched nothing, not that the platform sells nothing")
        void anUnmatchedProviderDoesNotClaimAnEmptyPlatform() {
            // The hint is the only sentence an agent gets when the list is
            // empty, and "no models are configured" ends the task.
            when(registry.list(null)).thenReturn(List.of(model(TIERED_SPEC, "tier-plain")));

            Map<String, Object> d = data(call("generation",
                    params("action", "models", "provider", "opemai")));

            assertThat((String) d.get("hint")).contains("opemai");
            assertThat((String) d.get("hint")).doesNotContain("are configured on this platform");
        }

        @Test
        @DisplayName("the listing can be narrowed to one provider, by slug or by display name")
        void providerNarrowsTheListing() {
            // The help tells an agent to compare a provider's ids; without a
            // filter that means reading every model of that format, which for
            // images is most of the catalogue.
            when(registry.list(null)).thenReturn(List.of(
                    model(TIERED_SPEC, "tier-plain"),
                    modelFrom(TIERED_SPEC, "tier-tall", "other", "Other Inc")));

            for (String asked : List.of("provider", "Provider Inc", "PROVIDER INC")) {
                Map<String, Object> d = data(call("generation",
                        params("action", "models", "provider", asked)));

                @SuppressWarnings("unchecked")
                List<Map<String, Object>> rows = (List<Map<String, Object>>) d.get("models");
                // Narrowing, not filtering-to-everything: the other provider's
                // model is the one this must drop.
                assertThat(rows).as("asked for %s", asked).hasSize(1);
                assertThat(rows.get(0)).containsEntry("model", "tier-plain");
            }

            Map<String, Object> none = data(call("generation",
                    params("action", "models", "provider", "someone-else")));
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> empty = (List<Map<String, Object>>) none.get("models");
            assertThat(empty).isEmpty();
        }

        @Test
        @DisplayName("regression: an inherited list reaches the wire, capped and marked")
        void inheritedListIsCappedForTheAgent() {
            // This payload is TOKENS and carries every model at once, so a
            // provider's hundred-voice catalogue has to arrive as a sample. The
            // cap belongs to THIS surface: the browser's copy takes the lot.
            List<String> hundred = new java.util.ArrayList<>();
            for (int i = 0; i < 100; i++) hundred.add("voice-" + i);
            when(registry.list(null)).thenReturn(List.of(
                    model(VOICE_SPEC, "tts-fast", java.util.Map.of("voice", hundred))));

            Map<String, Object> d = data(call("generation", params("action", "models")));

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> rows = (List<Map<String, Object>>) d.get("models");
            @SuppressWarnings("unchecked")
            Map<String, Object> limits = (Map<String, Object>) rows.get(0).get("limits");
            @SuppressWarnings("unchecked")
            Map<String, Object> voice = (Map<String, Object>) limits.get("voice");

            assertThat((List<?>) voice.get("allowed")).hasSize(GenerationLimits.AGENT_INLINE_CAP);
            assertThat(voice).containsEntry("allowedTruncated", true);
            assertThat(voice).containsEntry("allowedCount", 100);
            // And it says the list is not a rule: a value outside it is
            // dispatched and paid for, unlike a declared one.
            assertThat(voice).containsEntry("allowedEnforced", false);
        }

        @Test
        @DisplayName("lists every model with its accepted params, limits and price")
        void listsModels() {
            Map<String, Object> d = data(call("generation", params("action", "models")));

            assertThat(d).containsEntry("count", 2);
            assertThat(d.get("kinds")).isEqualTo(List.of("video", "voice"));

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> rows = (List<Map<String, Object>>) d.get("models");
            Map<String, Object> vid = rows.stream()
                    .filter(r -> "vid-fast".equals(r.get("model"))).findFirst().orElseThrow();
            assertThat(vid).containsEntry("kind", "video").containsEntry("async", true);
            assertThat(vid.get("accepts").toString()).contains("prompt", "duration_seconds");
            assertThat(vid.get("limits").toString()).contains("allowed");
            assertThat(vid.get("price").toString()).contains("credits_per_second");
        }

        @Test
        @DisplayName("a text cap is REPORTED, where a bare {} used to say 'restricted' and name no restriction")
        void reportsTextCaps() {
            // Every OpenAI model shipped here caps its prompt and constrains
            // nothing else, so with the cap unreported the agent was handed an
            // empty object and could only learn the limit by tripping it.
            GenerationSpec capped = spec("""
                    {
                      "kind": "image", "assetPath": "$binary",
                      "paramMap": { "prompt": "prompt" },
                      "models": [{
                        "id": "img-capped", "label": "Capped",
                        "capabilities": ["prompt"],
                        "constraints": { "prompt": { "maxLength": 4096 } },
                        "price": { "unit": "call", "baseCredits": 9 }
                      }]
                    }
                    """);
            when(registry.list(null)).thenReturn(List.of(model(capped, "img-capped")));

            Map<String, Object> d = data(call("generation", params("action", "models")));

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> rows = (List<Map<String, Object>>) d.get("models");
            @SuppressWarnings("unchecked")
            Map<String, Object> limits = (Map<String, Object>) rows.get(0).get("limits");
            assertThat(limits).extractingByKey("prompt")
                    .isEqualTo(Map.of("maxLength", 4096));
        }

        @Test
        @DisplayName("a parameter with nothing to restrict is left out, rather than listed with an empty limit")
        void omitsEmptyLimits() {
            GenerationSpec unconstrained = spec("""
                    {
                      "kind": "image", "assetPath": "$binary",
                      "paramMap": { "prompt": "prompt", "seed": "seed" },
                      "models": [{
                        "id": "img-open", "label": "Open",
                        "capabilities": ["prompt", "seed"],
                        "constraints": { "seed": {} },
                        "price": { "unit": "call", "baseCredits": 9 }
                      }]
                    }
                    """);
            when(registry.list(null)).thenReturn(List.of(model(unconstrained, "img-open")));

            Map<String, Object> d = data(call("generation", params("action", "models")));

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> rows = (List<Map<String, Object>>) d.get("models");
            assertThat(rows.get(0)).doesNotContainKey("limits");
        }

        @Test
        @DisplayName("filters by kind so an agent looking for video is not shown speech models")
        void filtersByKind() {
            Map<String, Object> d = data(call("generation", params("action", "models", "kind", "video")));
            assertThat(d).containsEntry("count", 1);
        }

        @Test
        @DisplayName("an empty result explains itself and names the kinds that do exist")
        void emptyResultIsActionable() {
            when(registry.list("hologram")).thenReturn(List.of());
            Map<String, Object> d = data(call("generation", params("action", "models", "kind", "hologram")));
            assertThat(d.get("hint").toString()).contains("video", "voice");
        }
    }

    /**
     * The recipe is what turns a generated file into something a person can recognise in their
     * workspace and run again with one word changed. It is written by the module, at exactly one
     * point in the flow: after the asset exists, and only then.
     */
    @Nested
    @DisplayName("records what made the asset")
    class RecordsTheRecipe {

        private ToolExecutionResult generate() {
            return generate(Map.of("ok", true));
        }

        /** The execution's answer, whose {@code metadata} is where the charge and the payer travel. */
        private ToolExecutionResult generate(Map<String, Object> payload) {
            when(executeModule.executeGeneration(any(), any(), any()))
                    .thenReturn(Optional.of(ToolExecutionResult.success(payload)));
            return provider.execute("generation", params(
                    "action", "create", "model", "vid-fast", "prompt", "a cat", "duration_seconds", 5),
                    chatContext());
        }

        private GenerationProvenanceRecorder.Recipe capturedRecipe() {
            ArgumentCaptor<GenerationProvenanceRecorder.Recipe> recipe =
                    ArgumentCaptor.forClass(GenerationProvenanceRecorder.Recipe.class);
            verify(provenanceRecorder).record(any(), recipe.capture(), any(), any());
            return recipe.getValue();
        }

        @Test
        @DisplayName("stamps the asset with the model, the prompt and the parameters that made it")
        void stampsTheFinishedAsset() {
            ToolExecutionResult r = generate();
            assertThat(r.success()).isTrue();

            ArgumentCaptor<com.apimarketplace.catalog.service.generation.GenerationProvenanceRecorder.Recipe>
                    recipe = ArgumentCaptor.forClass(
                        com.apimarketplace.catalog.service.generation.GenerationProvenanceRecorder.Recipe.class);
            verify(provenanceRecorder).record(any(), recipe.capture(), eq("tenant-1"), eq("org-1"));

            assertThat(recipe.getValue().model()).isEqualTo("vid-fast");
            assertThat(recipe.getValue().kind()).isEqualTo("video");
            assertThat(recipe.getValue().unified())
                    .containsEntry("prompt", "a cat")
                    // The size the run was billed on has to come back on a replay, or the variant
                    // costs a different amount than the asset it varies.
                    .containsEntry("duration_seconds", 5);
        }

        @Test
        @DisplayName("records what the platform charged, read from the execution rather than "
                + "recomputed from today's published rate")
        void recordsWhatTheExecutionCharged() {
            // The seam between the two halves of this feature: the execution reports the amount its
            // reservation committed, and the recipe is where that amount survives. Replacing this
            // reading with null leaves every other suite green and the price gone from every card.
            generate(Map.of("ok", true, "metadata",
                    Map.of("credentialSource", "platform", "billedCredits", 78)));

            assertThat(capturedRecipe().billedCredits()).isEqualByComparingTo(new java.math.BigDecimal("78"));
        }

        @Test
        @DisplayName("survives the JSON round trip that turns the amount into a double, without "
                + "inventing a fraction the ledger does not have")
        void readsTheAmountWhateverNumberTypeItArrivesAs() {
            // The value crosses an HTTP hop before it gets here, so it arrives as whatever Jackson
            // made of it. Read through the double itself, 0.1 would become 0.1000000000000000055.
            generate(Map.of("ok", true, "metadata", Map.of("billedCredits", 0.1d)));

            assertThat(capturedRecipe().billedCredits()).isEqualByComparingTo(new java.math.BigDecimal("0.1"));
        }

        @Test
        @DisplayName("hands the caller what it cost, beside the size it was charged on")
        void reportsTheChargeToTheCaller() {
            // The agent reads the RESULT, not the execution layer's metadata. Left only in
            // provider_response.metadata, the price of a call would be reachable but undocumented,
            // under a key whose help says it holds the provider's payload.
            ToolExecutionResult r = generate(Map.of("ok", true, "metadata",
                    Map.of("credentialSource", "platform", "billedCredits", 78)));

            assertThat(r.success()).isTrue();
            assertThat(((Map<?, ?>) r.data()).get("billed_credits"))
                    .isEqualTo(new java.math.BigDecimal("78"));
        }

        @Test
        @DisplayName("tells the caller nothing about a price of zero either, whatever reaches it")
        void reportsNoChargeForZero() {
            // Belt to the settlement's braces: the execution refuses to report a zero, so this can
            // only be reached if that ever changes. Untested, it was a guard a mutation walked
            // straight through - and the amount it guards is the one an agent quotes to a person.
            ToolExecutionResult r = generate(Map.of("ok", true, "metadata",
                    Map.of("credentialSource", "platform", "billedCredits", 0)));

            assertThat(((Map<?, ?>) r.data()).containsKey("billed_credits")).isFalse();
        }

        @Test
        @DisplayName("tells the caller NOTHING about a price when the platform charged nothing, so "
                + "an absent charge cannot be read as a free one")
        void reportsNoChargeWhenThePlatformDidNotCharge() {
            ToolExecutionResult r = generate(Map.of("ok", true, "metadata",
                    Map.of("credentialSource", "user")));

            assertThat(((Map<?, ?>) r.data()).containsKey("billed_credits")).isFalse();
        }

        @Test
        @DisplayName("records NO amount when the execution reported none, so nothing is invented "
                + "for a generation the platform did not charge for")
        void recordsNoAmountWhenTheExecutionReportedNone() {
            // The reader's own key paid, the install does not meter, or the endpoint has no
            // published price. Each of them arrives here as an absent key, and absent is not zero.
            generate(Map.of("ok", true, "metadata", Map.of("credentialSource", "user")));

            assertThat(capturedRecipe().billedCredits()).isNull();
        }

        @Test
        @DisplayName("records NO amount for a value that is not a number, rather than a broken one")
        void ignoresAnAmountThatIsNotANumber() {
            generate(Map.of("ok", true, "metadata", Map.of("billedCredits", "not-a-number")));

            assertThat(capturedRecipe().billedCredits()).isNull();
        }

        @Test
        @DisplayName("hands over the SAME asset the caller gets back, so the recipe lands on that row")
        void stampsTheAssetTheCallerReceives() {
            generate();

            @SuppressWarnings("unchecked")
            ArgumentCaptor<Map<String, Object>> stamped = ArgumentCaptor.forClass(Map.class);
            verify(provenanceRecorder).record(stamped.capture(), any(), anyString(), any());
            assertThat(stamped.getValue()).containsEntry("path", "tenant-1/out.png");
        }

        @Test
        @DisplayName("records nothing when the generation produced no asset")
        void recordsNothingWithoutAnAsset() {
            // The call was charged but nothing came back to annotate. A recipe stamped here would
            // have no row to land on, and the failure branch returns a recovery payload instead.
            when(executeModule.executeGeneration(any(), any(), any()))
                    .thenReturn(Optional.of(ToolExecutionResult.success(Map.of("ok", true))));
            when(assetResolver.resolve(any(), any(), any(), any()))
                    .thenReturn(new GenerationAssetResolver.Resolved(null, "the CDN timed out"));

            ToolExecutionResult r = call("generation", params("action", "create", "model", "vid-fast",
                    "prompt", "a cat", "duration_seconds", 5));

            assertThat(r.success()).isFalse();
            verifyNoInteractions(provenanceRecorder);
        }

        @Test
        @DisplayName("records nothing when no key was connected, because nothing ran")
        void recordsNothingWhenNothingRan() {
            when(executeModule.executeGeneration(any(), any(), any()))
                    .thenReturn(Optional.of(ToolExecutionResult.success(Map.of(
                            "status", "approval_needed",
                            "serviceName", "Elevenlabs",
                            "platformKeyAvailable", true,
                            "message", "Credential required for Elevenlabs"))));

            call("generation", params("action", "create", "model", "vid-fast",
                    "prompt", "a cat", "duration_seconds", 5));

            verifyNoInteractions(provenanceRecorder);
        }

        @Test
        @DisplayName("records nothing when the provider refused the call")
        void recordsNothingOnAFailedExecution() {
            when(executeModule.executeGeneration(any(), any(), any()))
                    .thenReturn(Optional.of(ToolExecutionResult.failure(
                            com.apimarketplace.agent.tools.ToolErrorCode.EXECUTION_FAILED,
                            "the provider refused")));

            call("generation", params("action", "create", "model", "vid-fast",
                    "prompt", "a cat", "duration_seconds", 5));

            verifyNoInteractions(provenanceRecorder);
        }

        @Test
        @DisplayName("records nothing when the parameters were refused before dispatch")
        void recordsNothingWhenRefusedBeforeDispatch() {
            // Nothing ran, nothing was charged, and there is no asset: the earliest refusal must
            // not leave a recipe behind either.
            call("generation", params("action", "create", "model", "vid-fast", "prompt", "a cat",
                    "duration_seconds", 999));

            verifyNoInteractions(provenanceRecorder);
        }
    }

    @Nested
    @DisplayName("create refuses before spending")
    class RefusesBeforeSpending {

        @Test
        @DisplayName("the credential pre-flight's success means NO key, and is not read as a failed generation")
        void approvalNeededIsAMissingKeyAndNotAMissingAsset() {
            // The pre-flight answers with a SUCCESS whose payload says
            // approval_needed: nothing ran and nothing was charged. Read as an
            // ordinary success it has no asset in it, so the asset resolver
            // reports "the provider produced nothing" AND states that the call
            // already cost money. Both are false, and they send the reader
            // hunting a failed generation instead of connecting a key.
            when(executeModule.executeGeneration(any(), any(), any()))
                    .thenReturn(Optional.of(ToolExecutionResult.success(Map.of(
                            "status", "approval_needed",
                            "serviceName", "Elevenlabs",
                            // The platform DOES sell this one, so switching pool
                            // is a remedy that can actually work here.
                            "platformKeyAvailable", true,
                            "message", "Credential required for Elevenlabs"))));

            ToolExecutionResult r = call("generation", params("action", "create", "model", "vid-fast",
                    "prompt", "a cat", "duration_seconds", 5));

            assertThat(r.success()).isFalse();
            // The stable code first, so a caller learns one vocabulary rather
            // than three across this path.
            assertThat(r.error()).startsWith(CatalogExecuteModule.CREDENTIALS_REQUIRED_CODE + ":");
            assertThat(r.error()).contains("Elevenlabs");
            assertThat(r.error())
                    .as("the reader must be told this cost nothing, or they will look for a refund")
                    .contains("nothing was charged");
            // THE POOL THAT WAS ACTUALLY EMPTY. This payload only ever comes
            // from the gate that reads the caller's OWN credentials, so the
            // remedy is the platform key. The first version of this assertion
            // demanded the opposite and so guaranteed the wrong sentence
            // survived two reviews: it pinned what the author meant instead of
            // what the gate had found.
            assertThat(r.error())
                    .as("the remedy must not be the pool that just came up empty")
                    .contains("credential_source='platform'")
                    .doesNotContain("credential_source='user'");
            // The asset resolver must never see it: its message is written for
            // a generation that RAN, and this one did not.
            verifyNoInteractions(assetResolver);
        }

        @Test
        @DisplayName("when NEITHER pool has a key, the remedy is not the other pool")
        void neitherPoolMeansConnectAKeyNotSwitchPools() {
            // The sibling branch of the refusal above, and the one that had no
            // test: the gate also fires when no pool was pinned and the platform
            // has nothing either. Telling that caller to buy on the platform key
            // sends them to a second empty pool, where a different guard refuses
            // them and points back at the first. Two refusals, each naming the
            // other's empty pool, and a wasted round-trip between them.
            when(executeModule.executeGeneration(any(), any(), any()))
                    .thenReturn(Optional.of(ToolExecutionResult.success(Map.of(
                            "status", "approval_needed",
                            "serviceName", "Elevenlabs",
                            "platformKeyAvailable", false,
                            "message", "Credential required for Elevenlabs"))));

            ToolExecutionResult r = call("generation", params("action", "create", "model", "vid-fast",
                    "prompt", "a cat", "duration_seconds", 5));

            assertThat(r.success()).isFalse();
            assertThat(r.error()).startsWith(CatalogExecuteModule.CREDENTIALS_REQUIRED_CODE + ":");
            assertThat(r.error())
                    .as("offering a pool that is also empty is advice that cannot work")
                    .doesNotContain("credential_source='platform'");
            assertThat(r.error()).contains("does not sell this one either");
        }

        @Test
        @DisplayName("a real success is still a success, so the approval check cannot swallow one")
        void anOrdinarySuccessIsUntouched() {
            when(executeModule.executeGeneration(any(), any(), any()))
                    .thenReturn(Optional.of(ToolExecutionResult.success(
                            Map.of("status", "succeeded"))));
            when(assetResolver.resolve(any(), any(), any(), any()))
                    .thenReturn(new GenerationAssetResolver.Resolved(
                            Map.of("_type", "file", "path", "t/x.mp4"), null));

            ToolExecutionResult r = call("generation", params("action", "create", "model", "vid-fast",
                    "prompt", "a cat", "duration_seconds", 5));

            assertThat(r.success()).isTrue();
        }

        @Test
        @DisplayName("the agent can reach EVERY parameter the platform understands")
        void theToolExposesTheWholeVocabulary() {
            // THE SEAM BETWEEN THE SYSTEM AND ITS AGENT. Four unified params
            // were understood by the platform, offered by the builder and
            // accepted by the validator, yet absent from this schema: an agent
            // could not discover them, so image-to-video was reachable from a
            // workflow and not from chat. A tool that accepts less than the
            // system it fronts makes the agent the weakest caller of its own
            // platform, and nothing failed to say so.
            //
            // Derived from GenerationSpec.UNIFIED_PARAMS rather than listed
            // again, so adding a format's own dimension there fails HERE until
            // the agent can use it too.
            java.util.Set<String> exposed = provider.getTools().get(0).parameters().stream()
                    .map(com.apimarketplace.agent.domain.ToolParameter::name)
                    .collect(java.util.stream.Collectors.toSet());

            assertThat(exposed)
                    .as("every unified parameter has to be reachable by an agent")
                    .containsAll(GenerationSpec.UNIFIED_PARAMS);
        }

        @Test
        @DisplayName("a missing model points at the discovery action instead of guessing")
        void missingModel() {
            ToolExecutionResult r = call("generation", params("action", "create", "prompt", "x"));

            assertThat(r.success()).isFalse();
            assertThat(r.errorCode()).isEqualTo(ToolErrorCode.MISSING_PARAMETER);
            assertThat(r.error()).contains("action='models'");
            verify(executeModule, never()).executeGeneration(any(), any(), any());
        }

        @Test
        @DisplayName("an unknown model lists the ones that exist, and dispatches nothing")
        void unknownModel() {
            ToolExecutionResult r = call("generation",
                    params("action", "create", "model", "nope", "prompt", "x"));

            assertThat(r.success()).isFalse();
            assertThat(r.error()).contains("vid-fast", "tts-fast");
            verify(executeModule, never()).executeGeneration(any(), any(), any());
        }

        @Test
        @DisplayName("a value outside the model's limits is refused at NO cost")
        void disallowedValueCostsNothing() {
            ToolExecutionResult r = call("generation", params(
                    "action", "create", "model", "vid-fast", "prompt", "x", "duration_seconds", 7));

            assertThat(r.success()).isFalse();
            assertThat(r.error()).contains("duration_seconds", "must be one of");
            verify(executeModule, never()).executeGeneration(any(), any(), any());
        }

        @Test
        @DisplayName("a parameter the model does not accept is refused with the list it does accept")
        void unacceptedParamCostsNothing() {
            ToolExecutionResult r = call("generation", params(
                    "action", "create", "model", "tts-fast", "prompt", "hello", "duration_seconds", 5));

            assertThat(r.success()).isFalse();
            assertThat(r.error()).contains("does not accept 'duration_seconds'");
            verify(executeModule, never()).executeGeneration(any(), any(), any());
        }

        @Test
        @DisplayName("an input file that cannot be read stops the call BEFORE anything is dispatched")
        void unreadableInputFileCostsNothing() {
            // The claim the whole input-asset feature is sold on: the FileRef is
            // turned into the provider's shape before the reservation, so a file
            // that is gone refuses the call while it is still free. Asserted at
            // the module, not on the resolver in isolation, because the ORDER of
            // the two steps is the thing that has to hold.
            when(registry.resolve("i2v-1")).thenReturn(Optional.of(ANIMATOR));
            when(storage.download(anyString(), anyString())).thenReturn(new byte[0]);

            ToolExecutionResult r = call("generation", params(
                    "action", "create", "model", "i2v-1", "prompt", "make it move",
                    "input_image", Map.of("_type", "file", "path", "tenant-1/gone.png")));

            assertThat(r.success()).isFalse();
            assertThat(r.errorCode()).isEqualTo(ToolErrorCode.INVALID_PARAMETER_VALUE);
            assertThat(r.error()).contains("input_image").contains("could not be read");
            verify(executeModule, never()).executeGeneration(any(), any(), any());
        }

        @Test
        @DisplayName("a base64 model asks for its asset path to survive the shaper, or it is charged for nothing")
        void base64ModelsExpandTheirAssetPath() {
            // The response is SHAPED before the resolver reads it, and the
            // shaper replaces any base64-looking leaf over 4 KB with the text
            // "[BASE64_CONTENT: n KB]". Above 64 KB the dehydrator has already
            // stored the file so nothing is lost; between the two the asset
            // came back as that marker, failed to decode, and the call was
            // refused AFTER the customer had been charged. Asking the shaper to
            // leave that subtree alone is what closes the window, so the request
            // is what has to be asserted, not the resolver in isolation.
            GenerationSpec base64Spec = spec("""
                    {
                      "kind": "image", "modelParam": "model",
                      "assetPath": "$base64:data[0].b64_json",
                      "paramMap": { "prompt": "prompt" },
                      "models": [{
                        "id": "b64-1", "upstream": "vendor-b64", "label": "B64",
                        "capabilities": ["prompt"],
                        "price": { "unit": "call", "baseCredits": 9 }
                      }]
                    }
                    """);
            when(registry.resolve("b64-1")).thenReturn(Optional.of(model(base64Spec, "b64-1")));
            when(executeModule.executeGeneration(any(), any(), any())).thenReturn(
                    Optional.of(ToolExecutionResult.success(Map.of("data", List.of(Map.of())))));

            call("generation", params("action", "create", "model", "b64-1", "prompt", "a boat"));

            @SuppressWarnings("unchecked")
            ArgumentCaptor<Map<String, Object>> sent = ArgumentCaptor.forClass(Map.class);
            verify(executeModule).executeGeneration(sent.capture(), any(), any());
            assertThat(sent.getValue().get("expand")).isEqualTo(List.of("data"));
        }

        @Test
        @DisplayName("the bytes are not handed BACK once they are a file, on success or on failure")
        void theAssetNeverRidesBackInTheReply() {
            // The asset path is asked to survive the response shaper, so the
            // whole blob is live in the payload by the time the module sees it.
            // Echoing it doubles a result the caller can already open, in a
            // channel measured in tokens, and it travels from a node output into
            // the run's stored state. This is the promise that justifies asking
            // for the expand in the first place.
            GenerationSpec base64Spec = spec("""
                    {
                      "kind": "image", "modelParam": "model",
                      "assetPath": "$base64:data[0].b64_json",
                      "paramMap": { "prompt": "prompt" },
                      "models": [{
                        "id": "b64-2", "upstream": "vendor-b64", "label": "B64",
                        "capabilities": ["prompt"],
                        "price": { "unit": "call", "baseCredits": 9 }
                      }]
                    }
                    """);
            String blob = "A".repeat(20_000);
            when(registry.resolve("b64-2")).thenReturn(Optional.of(model(base64Spec, "b64-2")));
            when(executeModule.executeGeneration(any(), any(), any())).thenReturn(
                    Optional.of(ToolExecutionResult.success(Map.of("created", 1, "data",
                            List.of(Map.of("b64_json", blob, "revised_prompt", "a boat"))))));

            ToolExecutionResult stored = call("generation",
                    params("action", "create", "model", "b64-2", "prompt", "a boat"));
            assertThat(String.valueOf(data(stored))).doesNotContain(blob);

            // And on the branch where nothing could be stored, which is the one
            // that used to hand the payload back untouched.
            when(assetResolver.resolve(any(), any(), any(), any())).thenReturn(
                    new GenerationAssetResolver.Resolved(Map.of(), "the asset could not be stored"));

            ToolExecutionResult failed = call("generation",
                    params("action", "create", "model", "b64-2", "prompt", "a boat"));
            assertThat(String.valueOf(failed.data())).doesNotContain(blob);
            assertThat(failed.success()).isFalse();
        }

        @Test
        @DisplayName("a model whose asset is NOT base64 asks for no such thing")
        void otherCampsDoNotExpand() {
            when(executeModule.executeGeneration(any(), any(), any())).thenReturn(
                    Optional.of(ToolExecutionResult.success(Map.of("content", Map.of()))));

            call("generation", params(
                    "action", "create", "model", "vid-fast", "prompt", "x", "duration_seconds", 5));

            @SuppressWarnings("unchecked")
            ArgumentCaptor<Map<String, Object>> sent = ArgumentCaptor.forClass(Map.class);
            verify(executeModule).executeGeneration(sent.capture(), any(), any());
            assertThat(sent.getValue()).doesNotContainKey("expand");
        }

        @Test
        @DisplayName("a multipart model keeps its file handle, and a bad one is still refused before dispatch")
        void multipartKeepsTheHandleButStillChecksIt() {
            // The one encoding a shipped descriptor uses, and the one that had
            // no coverage here: the FileRef must survive untouched, yet a value
            // that is not a file must still stop the call. The encoder further
            // down drops an unreadable part in silence, so the provider would
            // answer "image is required" on a call already dispatched.
            when(registry.resolve("edit-1")).thenReturn(Optional.of(EDITOR));
            when(executeModule.executeGeneration(any(), any(), any())).thenReturn(
                    Optional.of(ToolExecutionResult.success(Map.of("data", List.of(Map.of())))));

            ToolExecutionResult refused = call("generation", params(
                    "action", "create", "model", "edit-1", "prompt", "brighten it",
                    "input_image", "https://example.test/cat.png"));

            assertThat(refused.success()).isFalse();
            assertThat(refused.error()).contains("input_image").contains("whole file object");
            verify(executeModule, never()).executeGeneration(any(), any(), any());

            when(storage.exists(anyString(), anyString())).thenReturn(true);
            Map<String, Object> handle = Map.of("_type", "file", "path", "tenant-1/cat.png",
                    "name", "cat.png", "mimeType", "image/png");
            call("generation", params(
                    "action", "create", "model", "edit-1", "prompt", "brighten it",
                    "input_image", handle));

            @SuppressWarnings("unchecked")
            ArgumentCaptor<Map<String, Object>> sent = ArgumentCaptor.forClass(Map.class);
            verify(executeModule).executeGeneration(sent.capture(), any(), any());
            @SuppressWarnings("unchecked")
            Map<String, Object> upstream = (Map<String, Object>) sent.getValue().get("params");
            assertThat(upstream.get("image")).isEqualTo(handle);
            // Nothing was downloaded: the multipart encoder does that itself.
            verify(storage, never()).download(anyString(), anyString());
        }

        @Test
        @DisplayName("a readable input file reaches the provider as a data URL, not as a file handle")
        void aReadableInputFileIsConverted() {
            // The other half of the same order: when the file IS readable, what
            // the catalog receives is the provider's shape. A FileRef arriving
            // here would be the exact bug this feature exists to end.
            when(registry.resolve("i2v-1")).thenReturn(Optional.of(ANIMATOR));
            when(storage.download(anyString(), anyString())).thenReturn(
                    new byte[] {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A});
            when(executeModule.executeGeneration(any(), any(), any())).thenReturn(
                    Optional.of(ToolExecutionResult.success(
                            Map.of("output", List.of("https://x.test/a.mp4")))));

            call("generation", params(
                    "action", "create", "model", "i2v-1", "prompt", "make it move",
                    "input_image", Map.of("_type", "file", "path", "tenant-1/cat.png",
                            "mimeType", "image/png")));

            @SuppressWarnings("unchecked")
            ArgumentCaptor<Map<String, Object>> sent = ArgumentCaptor.forClass(Map.class);
            verify(executeModule).executeGeneration(sent.capture(), any(), any());
            @SuppressWarnings("unchecked")
            Map<String, Object> upstream = (Map<String, Object>) sent.getValue().get("params");
            assertThat((String) upstream.get("promptImage")).startsWith("data:image/png;base64,");
        }
    }

    @Nested
    @DisplayName("create dispatches")
    class Dispatches {

        private ArgumentCaptor<CatalogExecuteModule.GenerationBilling> billingCaptor;

        @BeforeEach
        void stubSuccess() {
            billingCaptor = ArgumentCaptor.forClass(CatalogExecuteModule.GenerationBilling.class);
            when(executeModule.executeGeneration(any(), any(), any()))
                    .thenReturn(Optional.of(ToolExecutionResult.success(
                            Map.of("status", "succeeded"))));
            when(assetResolver.resolve(any(), any(), any(), any()))
                    .thenReturn(new GenerationAssetResolver.Resolved(
                            Map.of("_type", "file", "path", "t/x.mp4"), null));
        }

        @Test
        @DisplayName("delegates to the single catalog path, carrying the model and the billable size")
        void delegatesWithPricingContext() {
            call("generation", params("action", "create", "model", "vid-fast",
                    "prompt", "a cat", "duration_seconds", 10));

            @SuppressWarnings("unchecked")
            ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
            verify(executeModule).executeGeneration(captor.capture(), any(), billingCaptor.capture());
            Map<String, Object> sent = captor.getValue();

            // THE ENDPOINT'S OWN ID, not its `api/tool` slug. Both are accepted
            // by the execute route, which is exactly why sending the wrong one
            // is silent. The two pre-flight gates on that same path are not so
            // forgiving: the agent restriction list holds the ids the platform
            // hands out (catalog search returns `id`), so a slug is never in it
            // and every generation by a restricted agent is refused with a
            // message pointing at a catalog search that cannot help; and the
            // credential pre-flight reads /api/catalog/tools/{id}/info, a
            // single-segment route that 404s on a slug, so the structured
            // "connect a key for X" prompt is swallowed into a warning.
            assertThat(sent).containsEntry("tool_id", VIDEO.apiToolId().toString());
            // The price-determining values travel as a typed argument, NEVER in
            // the delegated map: that map is caller-influenced, and a supplied
            // quantity of zero would be a free generation.
            assertThat(sent).doesNotContainKeys(
                    CatalogExecuteModule.GENERATION_MODEL_KEY,
                    CatalogExecuteModule.GENERATION_QUANTITY_KEY);
            assertThat(billingCaptor.getValue().modelId()).isEqualTo("vid-fast");
            assertThat(billingCaptor.getValue().quantity()).isEqualByComparingTo("10");
            // THE UNIT, which was the one field of the pricing context nothing
            // asserted here. Dropping it left 53 test classes green, including
            // this file's own 38, while downstream the dimension guard read no
            // unit, failed open, and a per-image rate could multiply a count of
            // seconds. A quantity without its unit is not a measurement.
            assertThat(billingCaptor.getValue().quantityUnit())
                    .as("a bare 10 is a count of seconds or of images, and the two are priced apart")
                    .isEqualTo("second");
        }

        @Test
        @DisplayName("the FACTOR travels beside the size, in its own field, not folded into it")
        void theFactorTravelsBesideTheSize() {
            // The seam: `GenerationBilling` is a positional record of
            // (String, BigDecimal, String, BigDecimal), and the two BigDecimals are the size and
            // the factor. Swapping them COMPILES. So the two numbers here are deliberately
            // different - ten seconds at 2x - because with equal values the test would pass on
            // the wiring it exists to check.
            //
            // What is at stake if the factor is dropped instead: a 1080p render is reserved and
            // charged at the 720p rate on every call, silently, and the only trace is an invoice
            // nobody has a reason to distrust.
            when(registry.resolve("vid-tiered")).thenReturn(Optional.of(MODULATED));

            call("generation", params("action", "create", "model", "vid-tiered",
                    "prompt", "a cat", "duration_seconds", 10, "resolution", "1080p"));

            verify(executeModule).executeGeneration(any(), any(), billingCaptor.capture());
            assertThat(billingCaptor.getValue().quantity())
                    .as("ten seconds stay ten seconds however much they cost")
                    .isEqualByComparingTo("10");
            assertThat(billingCaptor.getValue().quantityUnit()).isEqualTo("second");
            assertThat(billingCaptor.getValue().priceMultiplier())
                    .as("the 1080p tier of this model is priced at twice its published rate")
                    .isEqualByComparingTo("2");
        }

        @Test
        @DisplayName("the SAME model at its reference tier is dispatched at the published rate")
        void theReferenceTierIsNotSurcharged() {
            // Guards against the factor being hard-coded to satisfy the test above, and pins the
            // half that matters to every existing model: a tier listed at 1 is the rate as
            // published, so nothing about this call is different from before factors existed.
            when(registry.resolve("vid-tiered")).thenReturn(Optional.of(MODULATED));

            call("generation", params("action", "create", "model", "vid-tiered",
                    "prompt", "a cat", "duration_seconds", 10, "resolution", "720p"));

            verify(executeModule).executeGeneration(any(), any(), billingCaptor.capture());
            assertThat(billingCaptor.getValue().priceMultiplier()).isEqualByComparingTo("1");
        }

        @Test
        @DisplayName("a model that declares no modifiers at all is dispatched at its published rate")
        void anUnmodulatedModelIsUnchanged() {
            // The 600+ endpoints that existed before any of this. They must reach the biller with
            // a factor that changes nothing, whatever the descriptor says about anything else.
            call("generation", params("action", "create", "model", "vid-fast",
                    "prompt", "a cat", "duration_seconds", 10));

            verify(executeModule).executeGeneration(any(), any(), billingCaptor.capture());
            assertThat(billingCaptor.getValue().priceMultiplier()).isEqualByComparingTo("1");
        }

        @Test
        @DisplayName("the caller is TOLD the factor and why, so a total that is not rate x size reads")
        void theFactorIsReportedToTheCaller() {
            // Reported in the result, not only applied to the charge. Without it an agent is handed
            // a size and an amount that do not multiply out, and cannot tell a surcharge from an
            // arithmetic error except by generating again.
            when(registry.resolve("vid-tiered")).thenReturn(Optional.of(MODULATED));

            ToolExecutionResult r = call("generation", params("action", "create",
                    "model", "vid-tiered", "prompt", "a cat",
                    "duration_seconds", 10, "resolution", "1080p"));

            @SuppressWarnings("unchecked")
            Map<String, Object> out = (Map<String, Object>) r.data();
            assertThat(out.get("billed_multiplier")).isEqualTo(new java.math.BigDecimal("2"));
            assertThat((java.util.List<String>) out.get("billed_multiplier_reasons"))
                    .as("the reason names the CHOICE, since that is the only half the caller controls")
                    .containsExactly("resolution x2");
            // And the size is still the size.
            assertThat((java.math.BigDecimal) out.get("billed_quantity"))
                    .isEqualByComparingTo(new java.math.BigDecimal("10"));
        }

        @Test
        @DisplayName("a call at the published rate reports NO factor, rather than a x1 on every turn")
        void theBaseRateIsNotAnnounced() {
            when(registry.resolve("vid-tiered")).thenReturn(Optional.of(MODULATED));

            ToolExecutionResult r = call("generation", params("action", "create",
                    "model", "vid-tiered", "prompt", "a cat",
                    "duration_seconds", 10, "resolution", "720p"));

            @SuppressWarnings("unchecked")
            Map<String, Object> out = (Map<String, Object>) r.data();
            assertThat(out).doesNotContainKeys("billed_multiplier", "billed_multiplier_reasons");
        }

        @Test
        @DisplayName("a model measured another way carries ITS unit, not the last one seen")
        void theUnitFollowsTheModel() {
            // Guards against the unit being hard-coded to satisfy the test
            // above: this model is counted in images, not seconds.
            //
            // Counted in images, but only ever ONE of them: a call is now
            // refused above 1, because a bigger n is charged in full and only
            // the first asset is fetched and stored.
            when(registry.resolve("upscale-x2")).thenReturn(Optional.of(PROMPTLESS));

            call("generation", params("action", "create", "model", "upscale-x2", "n", 1));

            verify(executeModule).executeGeneration(any(), any(), billingCaptor.capture());
            assertThat(billingCaptor.getValue().quantityUnit()).isEqualTo("image");
            assertThat(billingCaptor.getValue().quantity()).isEqualByComparingTo("1");
        }

        @Test
        @DisplayName("asking for MORE than one asset is refused before dispatch, since only one comes back")
        void moreThanOneAssetIsRefused() {
            // The overcharge this closes: a per-image price multiplies n, the
            // resolver stores one file, and billed_quantity reports the number
            // asked for. The authoring gate refuses a model that offers this,
            // but a descriptor can reach the runtime by other routes, so the
            // rule also lives where the amount is computed.
            when(registry.resolve("upscale-x2")).thenReturn(Optional.of(PROMPTLESS));

            ToolExecutionResult result = call("generation",
                    params("action", "create", "model", "upscale-x2", "n", 4));

            assertThat(result.success()).isFalse();
            assertThat(result.error()).contains("one call produces and stores exactly one asset");
            // Nothing was dispatched, so nothing was charged.
            verifyNoInteractions(executeModule);
        }

        @Test
        @DisplayName("a model that DEFAULTS its own count above one is refused too, on the measured size")
        void aModelThatDefaultsMoreThanOneAssetIsRefused() {
            // Nothing in the call mentions a count: the model supplies 4 from
            // its own constraint. Reading the caller's input would have waved
            // this through and billed four assets for the one that is stored.
            when(registry.resolve("bulk-img")).thenReturn(Optional.of(BULK_DEFAULT));

            ToolExecutionResult result = call("generation",
                    params("action", "create", "model", "bulk-img", "prompt", "a cat"));

            assertThat(result.success()).isFalse();
            assertThat(result.error()).contains("one call produces and stores exactly one asset");
            verifyNoInteractions(executeModule);
        }

        @Test
        @DisplayName("the provider receives its own request shape, not the platform's vocabulary")
        void projectsRequest() {
            call("generation", params("action", "create", "model", "vid-fast",
                    "prompt", "a cat", "duration_seconds", 5));

            @SuppressWarnings("unchecked")
            ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
            verify(executeModule).executeGeneration(captor.capture(), any(), billingCaptor.capture());

            @SuppressWarnings("unchecked")
            Map<String, Object> upstream = (Map<String, Object>) captor.getValue().get("params");
            assertThat(upstream).containsEntry("model", "vendor-fast");
            assertThat(upstream).containsEntry("duration", 5);
            assertThat(upstream.get("content").toString()).contains("a cat");
        }

        @Test
        @DisplayName("an explicit credential_source reaches the catalog so a user can pay their own way")
        void forwardsCredentialSource() {
            call("generation", params("action", "create", "model", "vid-fast",
                    "prompt", "x", "duration_seconds", 5, "credential_source", "user"));

            @SuppressWarnings("unchecked")
            ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
            verify(executeModule).executeGeneration(captor.capture(), any(), billingCaptor.capture());
            assertThat(captor.getValue()).containsEntry("credential_source", "user");
        }

        @Test
        @DisplayName("an OMITTED credential_source is forwarded as omitted, so the caller's own key is still tried first")
        void doesNotInventACredentialSource() {
            // Defaulting this to "platform" would make one help sentence true
            // and take a working path away from two callers: an agent with its
            // own provider key and no credits, and an agent on an install with
            // no platform credential configured at all. Both run today, and the
            // money is correct without the default, because a reservation whose
            // call is answered by the user's own credential is released rather
            // than committed.
            call("generation", params("action", "create", "model", "vid-fast",
                    "prompt", "x", "duration_seconds", 5));

            @SuppressWarnings("unchecked")
            ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
            verify(executeModule).executeGeneration(captor.capture(), any(), billingCaptor.capture());
            assertThat(captor.getValue()).doesNotContainKey("credential_source");
        }

        @Test
        @DisplayName("an AGENT cannot pin a key: a credential_id in its arguments is not forwarded")
        void aCallerSuppliedCredentialIdIsNotForwarded() {
            // The tool's help tells the agent it gets the account's default key
            // for the provider, because no action here lists credential ids.
            // Reading one out of its arguments would make that false and let a
            // guessed number decide which of the account's keys runs. The pin
            // travels on the execution context instead, which only the app
            // dialog and the workflow node populate.
            call("generation", params("action", "create", "model", "vid-fast",
                    "prompt", "x", "duration_seconds", 5,
                    "credential_source", "user", "credential_id", 42));

            @SuppressWarnings("unchecked")
            ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
            verify(executeModule).executeGeneration(captor.capture(), any(), billingCaptor.capture());
            assertThat(captor.getValue()).doesNotContainKey("credential_id");
        }

        @Test
        @DisplayName("nothing is forwarded when nothing was pinned, so the account's default key runs")
        void doesNotInventACredentialId() {
            call("generation", params("action", "create", "model", "vid-fast",
                    "prompt", "x", "duration_seconds", 5, "credential_source", "user"));

            @SuppressWarnings("unchecked")
            ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
            verify(executeModule).executeGeneration(captor.capture(), any(), billingCaptor.capture());
            assertThat(captor.getValue()).doesNotContainKey("credential_id");
        }

        @Test
        @DisplayName("the result names the model, kind and billed size on top of the catalog's payload")
        void decoratesResult() {
            Map<String, Object> d = data(call("generation", params("action", "create",
                    "model", "vid-fast", "prompt", "x", "duration_seconds", 10)));

            assertThat(d).containsEntry("model", "vid-fast")
                    .containsEntry("kind", "video")
                    .containsEntry("provider", "Provider Inc")
                    .containsEntry("billed_unit", "second")
                    .containsKey("file")
                    .containsKey("provider_response");
            assertThat(d.get("file").toString()).contains("t/x.mp4");
            assertThat((BigDecimal) d.get("billed_quantity")).isEqualByComparingTo("10");
        }

        @Test
        @DisplayName("nested params are accepted as readily as flattened ones")
        void acceptsNestedParams() {
            call("generation", params("action", "create", "model", "vid-fast",
                    "params", Map.of("prompt", "x", "duration_seconds", 5)));
            verify(executeModule).executeGeneration(any(), any(), any());
        }

        @Test
        @DisplayName("a model listed per minute is still billed on SECONDS, and says so")
        void aPerMinuteModelReportsSeconds() {
            // The size handed to billing is the platform measurement; the
            // published rate converts it. Sending 1 (minute) here and letting a
            // per-second rate multiply it, or the reverse, is the 60x defect.
            when(registry.resolve("music-min")).thenReturn(Optional.of(MUSIC_PER_MINUTE));

            Map<String, Object> d = data(call("generation", params("action", "create",
                    "model", "music-min", "prompt", "lofi", "duration_seconds", 60)));

            verify(executeModule).executeGeneration(any(), any(), billingCaptor.capture());
            assertThat(billingCaptor.getValue().quantity()).isEqualByComparingTo("60");
            // and the agent is told which unit that 60 is counted in, so it is
            // never read against the "credits per minute" the model listing shows
            assertThat((BigDecimal) d.get("billed_quantity")).isEqualByComparingTo("60");
            assertThat(d).containsEntry("billed_unit", "second");
        }

        @Test
        @DisplayName("a model sold PER CALL still reports its size, as 1 call, rather than saying nothing")
        void aFlatModelStillReportsItsSize() {
            // The two fields were documented as absent here, in the node spec
            // and in the node documentation the agent reads. They are not: a
            // flat model measures one call as 1. An agent told they would be
            // missing writes a template that reads them anyway, or a guard for
            // an absence that never happens, and the doc it trusted is the thing
            // that was wrong.
            when(registry.resolve("img-flat")).thenReturn(Optional.of(IMAGE_FLAT));

            Map<String, Object> d = data(call("generation", params("action", "create",
                    "model", "img-flat", "prompt", "a cat")));

            assertThat(d).containsEntry("billed_unit", "call");
            assertThat((BigDecimal) d.get("billed_quantity")).isEqualByComparingTo("1");
        }

        @Test
        @DisplayName("a per-character model bills the prompt length, not one call")
        void billsCharacters() {
            Map<String, Object> d = data(call("generation", params("action", "create",
                    "model", "tts-fast", "prompt", "hello world")));
            assertThat((BigDecimal) d.get("billed_quantity")).isEqualByComparingTo("11");
            assertThat(d).containsEntry("billed_unit", "character");
        }

        @Test
        @DisplayName("the legacy 'generate' verb behaves exactly like 'create'")
        void legacyVerbWorks() {
            ToolExecutionResult r = call("generation", params("action", "generate",
                    "model", "vid-fast", "prompt", "x", "duration_seconds", 5));
            assertThat(r.success()).isTrue();
            verify(executeModule).executeGeneration(any(), any(), any());
        }
    }

    @Nested
    @DisplayName("create propagates failure")
    class Failures {

        @Test
        @DisplayName("a catalog failure is surfaced unchanged rather than being reported as success")
        void propagatesCatalogFailure() {
            when(executeModule.executeGeneration(any(), any(), any()))
                    .thenReturn(Optional.of(ToolExecutionResult.failure(
                            ToolErrorCode.QUOTA_EXCEEDED, "Insufficient credits")));

            ToolExecutionResult r = call("generation", params("action", "create",
                    "model", "vid-fast", "prompt", "x", "duration_seconds", 5));

            assertThat(r.success()).isFalse();
            assertThat(r.errorCode()).isEqualTo(ToolErrorCode.QUOTA_EXCEEDED);
            assertThat(r.error()).contains("Insufficient credits");
        }

        @Test
        @DisplayName("a run that produced no asset is a FAILURE, because the customer already paid for it")
        void noAssetIsAFailure() {
            when(executeModule.executeGeneration(any(), any(), any()))
                    .thenReturn(Optional.of(ToolExecutionResult.success(
                            Map.of("id", "job-1", "status", "running"))));
            when(assetResolver.resolve(any(), any(), any(), any()))
                    .thenReturn(new GenerationAssetResolver.Resolved(
                            Map.of(), "no asset URL at 'content.video_url'"));

            ToolExecutionResult r = call("generation", params("action", "create",
                    "model", "vid-fast", "prompt", "x", "duration_seconds", 5));

            // Reporting success here would hand the agent an empty result for a
            // paid call, which is the failure mode that looks like it worked.
            assertThat(r.success()).isFalse();
            assertThat(r.error())
                    .contains("no asset could be retrieved")
                    .contains("content.video_url");
        }

        /**
         * The charge is already committed when this branch runs, so the failure
         * has to hand back a way to still get the thing that was bought. A
         * sentence is not a way.
         */
        @Test
        @DisplayName("a paid asset that could not be stored comes back with the provider's own link")
        void aPaidAssetKeepsItsProviderLink() {
            Map<String, Object> providerAnswer = Map.of(
                    "id", "job-1", "status", "succeeded",
                    "content", Map.of("video_url", "https://cdn.example.com/v/job-1.mp4"));
            when(executeModule.executeGeneration(any(), any(), any()))
                    .thenReturn(Optional.of(ToolExecutionResult.success(providerAnswer)));
            when(assetResolver.resolve(any(), any(), any(), any()))
                    .thenReturn(new GenerationAssetResolver.Resolved(
                            Map.of(), "fetching the generated asset returned HTTP 504",
                            "https://cdn.example.com/v/job-1.mp4"));

            ToolExecutionResult r = call("generation", params("action", "create",
                    "model", "vid-fast", "prompt", "x", "duration_seconds", 5));

            assertThat(r.success()).isFalse();
            // Structured, for the HTTP surfaces and the workflow node.
            assertThat(data(r))
                    .containsEntry("asset_url", "https://cdn.example.com/v/job-1.mp4")
                    .containsEntry("provider_response", providerAnswer);
            // And in the TEXT, because an agent reads the message and nothing
            // else: a URL it cannot see is a URL it cannot use.
            assertThat(r.error())
                    .contains("https://cdn.example.com/v/job-1.mp4")
                    .contains("charged");
        }

        @Test
        @DisplayName("a refusal that never reached the provider answers with NO data - that absence is a contract")
        void aFreeRefusalCarriesNoData() {
            // The studio reads this exact distinction to decide what to tell a reader about their
            // money: a failing answer with data means the generation RAN and was billed, and a
            // failing answer with none means it never left. Nothing on this side stated it, so
            // attaching diagnostics to a free refusal - one keystroke, `data` instead of
            // `metadata` - would make the studio announce a charge that never happened AND destroy
            // the reader's prompt and uploaded files, with nothing anywhere going red.
            //
            // Pinned on a refusal raised BEFORE any provider call, which is the whole class: every
            // ToolExecutionResult.failure(...) factory leaves data null.
            ToolExecutionResult r = call("generation", params("action", "create", "model", "no-such-model"));

            assertThat(r.success()).isFalse();
            assertThat(r.data())
                    .as("a refusal that cost nothing must carry nothing: see generationWasCharged "
                            + "in the studio, which infers 'you were charged' from data being present")
                    .satisfiesAnyOf(
                            d -> assertThat(d).isNull(),
                            d -> assertThat((Map<String, Object>) d).isEmpty());
        }

        @Test
        @DisplayName("when no link was found, the raw provider answer is returned instead of nothing")
        void withoutALinkTheProviderAnswerIsStillReturned() {
            // The other way into this branch: the descriptor points at a path
            // the provider does not use. The link then exists somewhere in the
            // answer under a key this code did not expect, so the answer itself
            // is what the caller needs.
            Map<String, Object> providerAnswer = Map.of("id", "job-1", "output", "https://cdn/x.mp4");
            when(executeModule.executeGeneration(any(), any(), any()))
                    .thenReturn(Optional.of(ToolExecutionResult.success(providerAnswer)));
            when(assetResolver.resolve(any(), any(), any(), any()))
                    .thenReturn(new GenerationAssetResolver.Resolved(
                            Map.of(), "no asset URL at 'content.video_url'", null));

            ToolExecutionResult r = call("generation", params("action", "create",
                    "model", "vid-fast", "prompt", "x", "duration_seconds", 5));

            assertThat(r.success()).isFalse();
            assertThat(data(r))
                    .doesNotContainKey("asset_url")
                    .containsEntry("provider_response", providerAnswer);
            assertThat(r.error()).contains("provider_response");
        }

        @Test
        @DisplayName("the asset is resolved for the model that ran, under the caller's tenant")
        void resolvesAssetForTheCaller() {
            when(executeModule.executeGeneration(any(), any(), any()))
                    .thenReturn(Optional.of(ToolExecutionResult.success(Map.of("status", "ok"))));
            when(assetResolver.resolve(any(), any(), any(), any()))
                    .thenReturn(new GenerationAssetResolver.Resolved(
                            Map.of("_type", "file", "path", "t/x.mp4"), null));

            call("generation", params("action", "create", "model", "vid-fast",
                    "prompt", "x", "duration_seconds", 5));

            verify(assetResolver).resolve(eq(VIDEO.spec()), eq(VIDEO.model()), any(), any());
        }

        @Test
        @DisplayName("an empty dispatch is reported as a failure, never as an empty success")
        void emptyDispatchIsFailure() {
            when(executeModule.executeGeneration(any(), any(), any()))
                    .thenReturn(Optional.empty());

            ToolExecutionResult r = call("generation", params("action", "create",
                    "model", "vid-fast", "prompt", "x", "duration_seconds", 5));

            assertThat(r.success()).isFalse();
            assertThat(r.errorCode()).isEqualTo(ToolErrorCode.EXECUTION_FAILED);
        }
    }

    /**
     * The chat card.
     *
     * <p>A generated asset is shown in chat by an Interface entity that the side
     * panel re-fetches by id, so a tool that does not persist one produces no
     * card at all, however good its result is. The legacy image tool is
     * currently the only producer of one, and that is the last thing keeping it
     * alive.
     *
     * <p>The other half of the contract is that the card is expendable and the
     * asset is not: by the time a card is drawn the generation has run and been
     * charged, so nothing that happens here may turn a paid success into a
     * failure.
     */
    @Nested
    @DisplayName("chat card")
    class ChatCard {

        @BeforeEach
        void stubSuccess() {
            when(executeModule.executeGeneration(any(), any(), any()))
                    .thenReturn(Optional.of(ToolExecutionResult.success(Map.of("status", "succeeded"))));
            when(assetResolver.resolve(any(), any(), any(), any()))
                    .thenReturn(new GenerationAssetResolver.Resolved(
                            Map.of("_type", "file", "path", "t/x.mp4"), null));
        }

        private void persistReturns(String id, String name) {
            InterfaceDto dto = new InterfaceDto();
            dto.setId(UUID.fromString(id));
            dto.setName(name);
            when(interfaceClient.createOrUpdateImageGenerationInterface(any(), anyString())).thenReturn(dto);
        }

        private ImageGenerationInterfaceRequest capturePersisted() {
            ArgumentCaptor<ImageGenerationInterfaceRequest> captor =
                    ArgumentCaptor.forClass(ImageGenerationInterfaceRequest.class);
            verify(interfaceClient).createOrUpdateImageGenerationInterface(captor.capture(), eq("tenant-1"));
            return captor.getValue();
        }

        @Test
        @DisplayName("a chat generation is persisted as a card, carrying the result and the prompt that is not in it")
        void persistsTheCard() {
            persistReturns("11111111-1111-1111-1111-111111111111", "a cat");

            callAsChat(params("action", "create", "model", "vid-fast",
                    "prompt", "a cat", "duration_seconds", 10));

            ImageGenerationInterfaceRequest req = capturePersisted();
            assertThat(req.getConversationId()).isEqualTo("conv-1");
            assertThat(req.getMessageId()).isEqualTo("msg-1");
            assertThat(req.getAgentId()).isEqualTo("agent-1");
            // Without the org stamp the card reads "Failed to load" for the
            // author's org teammates, who can open the conversation.
            assertThat(req.getOrganizationId()).isEqualTo("org-1");
            // The tool result verbatim, which is the shape interface-service was
            // taught to read: one canonical file, not the legacy images[].
            assertThat(req.getData()).containsEntry("model", "vid-fast").containsKey("file");
            // The prompt is an INPUT, so it is absent from that result and has to
            // travel beside it or the card cannot say what was asked for.
            assertThat(req.getPrompt()).isEqualTo("a cat");
        }

        @Test
        @DisplayName("the persisted card is announced to the agent by marker and by visualization metadata")
        void announcesTheCard() {
            persistReturns("22222222-2222-2222-2222-222222222222", "a cat");

            ToolExecutionResult r = callAsChat(params("action", "create", "model", "vid-fast",
                    "prompt", "a cat", "duration_seconds", 10));

            assertThat(r.success()).isTrue();
            assertThat(data(r)).containsEntry("marker",
                    "[visualize:image_generation:22222222-2222-2222-2222-222222222222]");
            // The type stays the historical one: it is what the frontend renderer
            // matches on and what every card already stored was written under.
            assertThat(data(r).get("display").toString()).contains("image_generation");
            assertThat(r.metadata().get("visualization").toString())
                    .contains("22222222-2222-2222-2222-222222222222");
            // and the result the agent was going to get is still all there
            assertThat(data(r)).containsEntry("model", "vid-fast").containsKey("file");
        }

        @Test
        @DisplayName("a caller with no conversation gets no card, and interface-service is never called")
        void noChatContextNoCard() {
            // Every workflow node and every non-chat caller lands here: there is
            // no message for a card to belong to, so posting one would create a
            // row nothing can ever show.
            ToolExecutionResult r = call("generation", params("action", "create",
                    "model", "vid-fast", "prompt", "a cat", "duration_seconds", 10));

            verifyNoInteractions(interfaceClient);
            assertThat(r.success()).isTrue();
            assertThat(data(r)).containsKey("file").doesNotContainKeys("marker", "display");
        }

        @Test
        @DisplayName("a refused persist does not fail the generation the customer already paid for")
        void refusedPersistIsSwallowed() {
            when(interfaceClient.createOrUpdateImageGenerationInterface(any(), anyString())).thenReturn(null);

            ToolExecutionResult r = callAsChat(params("action", "create", "model", "vid-fast",
                    "prompt", "a cat", "duration_seconds", 10));

            assertThat(r.success()).isTrue();
            assertThat(data(r)).containsKey("file").doesNotContainKey("marker");
        }

        @Test
        @DisplayName("a persist that throws does not fail the generation either")
        void throwingPersistIsSwallowed() {
            when(interfaceClient.createOrUpdateImageGenerationInterface(any(), anyString()))
                    .thenThrow(new IllegalStateException("interface-service unreachable"));

            ToolExecutionResult r = callAsChat(params("action", "create", "model", "vid-fast",
                    "prompt", "a cat", "duration_seconds", 10));

            // The asset exists and has been charged for. Losing it because the
            // card could not be drawn would be the expensive failure mode.
            assertThat(r.success()).isTrue();
            assertThat(data(r)).containsKey("file").doesNotContainKey("marker");
        }

        @Test
        @DisplayName("a long prompt is cut to a card title rather than becoming one")
        void longPromptIsCut() {
            persistReturns("33333333-3333-3333-3333-333333333333", "x");
            String prompt = "a".repeat(120);

            callAsChat(params("action", "create", "model", "vid-fast",
                    "prompt", prompt, "duration_seconds", 10));

            assertThat(capturePersisted().getName()).hasSize(81).startsWith("a".repeat(80));
            // and the full prompt is still carried, so nothing is actually lost
            assertThat(capturePersisted().getPrompt()).isEqualTo(prompt);
        }

        @Test
        @DisplayName("a model that takes no prompt is titled by its format rather than by nothing")
        void promptlessCardIsTitledByKind() {
            persistReturns("55555555-5555-5555-5555-555555555555", "x");
            when(registry.resolve("upscale-x2")).thenReturn(Optional.of(PROMPTLESS));

            callAsChat(params("action", "create", "model", "upscale-x2", "n", 1));

            // An untitled row is indistinguishable from every other untitled row
            // in the side panel, and a prompt is only required of a model that
            // declares it, so this is reachable rather than defensive.
            assertThat(capturePersisted().getName()).isEqualTo("Generated image");
            assertThat(capturePersisted().getPrompt()).isNull();
        }

        @Test
        @DisplayName("a prompt nested under params still titles the card, because create accepts that shape")
        void nestedPromptTitlesTheCard() {
            persistReturns("44444444-4444-4444-4444-444444444444", "x");

            callAsChat(params("action", "create", "model", "vid-fast",
                    "params", Map.of("prompt", "a nested cat", "duration_seconds", 5)));

            // Reading only the top level would leave every nested caller's card
            // untitled, and this tool documents both shapes as equal.
            assertThat(capturePersisted().getName()).isEqualTo("a nested cat");
            assertThat(capturePersisted().getPrompt()).isEqualTo("a nested cat");
        }
    }

    /**
     * Refusing a generation when the account has no storage room, BEFORE the provider is called.
     *
     * <p>Billing is committed inside {@code executeGeneration}; the storage gate only runs later,
     * when the asset is made durable. An account with no room therefore used to pay for every
     * generation and keep none of them, and the failure said only that the asset "could not be
     * stored". So the assertion that matters in every test below is not the error code, it is
     * {@code verify(executeModule, never()).executeGeneration(...)}: no dispatch, no charge.
     */
    @Nested
    @DisplayName("storage pre-check - do not charge for what cannot be stored")
    class StoragePreCheck {

        private com.apimarketplace.storage.client.StorageClient storageClient;

        private void wireStorage(boolean hasRoom) {
            storageClient = mock(com.apimarketplace.storage.client.StorageClient.class);
            when(storageClient.hasRoomFor(any(), any(), anyLong())).thenReturn(hasRoom);
            org.springframework.test.util.ReflectionTestUtils.setField(module, "storageClient", storageClient);
        }

        private Map<String, Object> aGeneration() {
            return params("action", "create", "model", "vid-fast", "prompt", "a boat");
        }

        @Test
        @DisplayName("a full account is refused WITHOUT dispatching, so nothing is charged")
        void fullAccountNeverReachesTheProvider() {
            wireStorage(false);

            ToolExecutionResult r = call("generation", aGeneration());

            verify(executeModule, never()).executeGeneration(any(), any(), any());
            assertThat(r.success()).isFalse();
            assertThat(r.errorCode()).isEqualTo(ToolErrorCode.QUOTA_EXCEEDED);
        }

        @Test
        @DisplayName("the refusal tells the caller they were NOT charged, which is the whole point")
        void refusalSaysNoChargeHappened() {
            wireStorage(false);

            ToolExecutionResult r = call("generation", aGeneration());

            assertThat(r.error()).contains("NOT been charged");
            assertThat(r.error()).doesNotContain("You have been charged");
        }

        @Test
        @DisplayName("probes for 1 byte: the asset size is unknowable up front, so it asks 'any room at all?'")
        void probesOneByte() {
            wireStorage(true);
            when(executeModule.executeGeneration(any(), any(), any())).thenReturn(
                    Optional.of(ToolExecutionResult.success(Map.of("data", List.of(Map.of())))));

            call("generation", aGeneration());

            // Not the 100 MB asset cap: probing for the worst case would refuse a small image
            // whenever less than 100 MB remained, which is a false refusal of a call the account
            // could well afford.
            verify(storageClient).hasRoomFor(any(), any(), eq(1L));
        }

        @Test
        @DisplayName("probes the tenant from the execution context, which is who the asset is stored for")
        void probesTheContextTenant() {
            wireStorage(true);
            when(executeModule.executeGeneration(any(), any(), any())).thenReturn(
                    Optional.of(ToolExecutionResult.success(Map.of("data", List.of(Map.of())))));
            ToolExecutionContext ctx = mock(ToolExecutionContext.class);
            when(ctx.tenantId()).thenReturn("tenant-from-context");

            provider.execute("generation", aGeneration(), ctx);

            // The same id the asset is stored under (GenerationAssetResolver is handed
            // context.tenantId() too), so the probe and the write speak about one account.
            verify(storageClient).hasRoomFor(eq("tenant-from-context"), any(), anyLong());
        }

        @Test
        @DisplayName("no execution context -> probes the tenant the call carries, not null")
        void noContextFallsBackToTheCallTenant() {
            wireStorage(true);
            when(executeModule.executeGeneration(any(), any(), any())).thenReturn(
                    Optional.of(ToolExecutionResult.success(Map.of("data", List.of(Map.of())))));

            // Straight at the module, because GenerationToolsProvider derives the tenant FROM the
            // context and so passes null for both when there is none: routed that way the
            // fallback is reached but has nothing to fall back to, and the assertion below could
            // not tell null from a real id. Callers that hold a tenant without a context (the
            // asset resolver uses the same fallback) are the ones this branch serves.
            module.execute("create", aGeneration(), "tenant-from-call", null);

            verify(storageClient).hasRoomFor(eq("tenant-from-call"), any(), anyLong());
        }

        @Test
        @DisplayName("names no workspace, so storage-service scopes the probe from the request as the upload will")
        void namesNoWorkspace() {
            wireStorage(true);
            when(executeModule.executeGeneration(any(), any(), any())).thenReturn(
                    Optional.of(ToolExecutionResult.success(Map.of("data", List.of(Map.of())))));

            call("generation", aGeneration());

            // Passing an explicit workspace here would OVERRIDE the active one server-side and
            // could probe a different bucket than the upload lands in.
            verify(storageClient).hasRoomFor(any(), eq((String) null), anyLong());
        }

        @Test
        @DisplayName("an account with room generates exactly as before")
        void roomAvailableProceeds() {
            wireStorage(true);
            when(executeModule.executeGeneration(any(), any(), any())).thenReturn(
                    Optional.of(ToolExecutionResult.success(Map.of("data", List.of(Map.of())))));

            ToolExecutionResult r = call("generation", aGeneration());

            verify(executeModule).executeGeneration(any(), any(), any());
            assertThat(r.success()).isTrue();
        }

        @Test
        @DisplayName("no storage client wired -> generation runs, exactly as it did before this guard")
        void noStorageClientStillGenerates() {
            // The field is optional on purpose (CE wiring, slim contexts). Absent means no
            // pre-check, never a blocked generation.
            when(executeModule.executeGeneration(any(), any(), any())).thenReturn(
                    Optional.of(ToolExecutionResult.success(Map.of("data", List.of(Map.of())))));

            ToolExecutionResult r = call("generation", aGeneration());

            verify(executeModule).executeGeneration(any(), any(), any());
            assertThat(r.success()).isTrue();
        }
    }
}
