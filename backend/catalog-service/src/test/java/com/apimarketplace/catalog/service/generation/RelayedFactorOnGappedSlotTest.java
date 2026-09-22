package com.apimarketplace.catalog.service.generation;

import com.apimarketplace.storage.client.StorageClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The relayed factor, on the slot shape the platform actually ships.
 *
 * <p><b>Why this exists as its own file.</b> The relay reads a call's choices back out of the
 * provider-shaped body it was sent, and the first version of that reader walked the slot by ARRAY
 * POSITION. That is unsound by construction here: the request builder pads every index below a
 * target and then PRUNES the empty ones, closing the gap, precisely because position carries no
 * meaning in these arrays. Seedance puts its reference images at {@code content[3]} with two frame
 * slots at {@code content[1]} and {@code content[2]} that the same call can never fill (the
 * descriptor declares them mutually exclusive), so the images arrive at {@code content[1]} and the
 * reader found nothing at 3.
 *
 * <p>The effect was a relayed Seedance call charged up to 20% less than the identical direct one,
 * on all twelve shipped models, silently. The existing parity test could not see it: its fixture
 * puts the slot immediately after the prompt, so no gap ever forms. This one is shaped like the
 * real descriptor on purpose.
 */
class RelayedFactorOnGappedSlotTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** The shipped Seedance shape, reduced to what decides the factor. */
    private static GenerationSpec seedanceShapedSpec() {
        String json = """
                {
                  "kind": "video",
                  "modelParam": "model",
                  "assetPath": "content.video_url",
                  "paramMap": {
                    "prompt": "content[0].text",
                    "duration_seconds": "duration",
                    "first_frame_image": {
                      "path": "content[1].image_url.url",
                      "encoding": "data_url",
                      "role": "first_frame",
                      "excludes": ["input_image"],
                      "itemConstants": { "content[1].type": "image_url",
                                         "content[1].role": "first_frame" }
                    },
                    "last_frame_image": {
                      "path": "content[2].image_url.url",
                      "encoding": "data_url",
                      "role": "last_frame",
                      "requires": ["first_frame_image"],
                      "excludes": ["input_image"],
                      "itemConstants": { "content[2].type": "image_url",
                                         "content[2].role": "last_frame" }
                    },
                    "input_image": {
                      "path": "content[3].image_url.url",
                      "encoding": "data_url",
                      "role": "reference",
                      "maxItems": 4,
                      "itemConstants": { "content[3].type": "image_url",
                                         "content[3].role": "reference_image" }
                    }
                  },
                  "constants": { "content[0].type": "text" },
                  "models": [{
                    "id": "seedance-2.0",
                    "upstream": "dreamina-seedance-2-0",
                    "capabilities": ["prompt", "duration_seconds", "first_frame_image",
                                     "last_frame_image", "input_image"],
                    "constraints": { "duration_seconds": { "allowed": [5, 10] } },
                    "price": { "unit": "second", "unitCredits": 152,
                               "modifiers": [
                                 { "param": "first_frame_image", "perAsset": 0.05 },
                                 { "param": "last_frame_image", "perAsset": 0.05 },
                                 { "param": "input_image", "perAsset": 0.05 }
                               ] }
                  }]
                }
                """;
        try {
            return GenerationSpec.parse(MAPPER.readTree(json), "seedance:create_video_task")
                    .orElseThrow();
        } catch (Exception e) {
            throw new IllegalStateException("bad fixture", e);
        }
    }

    /** A 1x1 PNG header, enough for the sniffer to name a real type. */
    private static final byte[] PNG = new byte[] {
            (byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 13
    };

    /** A stored file, in the shape the platform hands between steps. */
    private static Map<String, Object> fileRef(String name) {
        Map<String, Object> ref = new LinkedHashMap<>();
        ref.put("_type", "file");
        ref.put("path", "tenant-1/uploads/" + name + ".png");
        ref.put("name", name + ".png");
        ref.put("mimeType", "image/png");
        return ref;
    }

    /** What the direct path builds, and therefore what the relay is handed. */
    private static GenerationRequestBuilder.Built build(Map<String, Object> unified) {
        GenerationSpec spec = seedanceShapedSpec();
        return GenerationRequestBuilder.build(spec, spec.model("seedance-2.0").orElseThrow(), unified);
    }

    /**
     * The body an install actually RELAYS: built, then run through input resolution.
     *
     * <p>That second half is not ceremony. Resolution is what writes each file's own marker beside
     * it and what prunes the slots nobody filled, and both decide what the relay can read back. A
     * test that measured the pre-resolution body would be measuring a shape that never travels.
     */
    private static Map<String, Object> relayedBody(GenerationRequestBuilder.Built built) {
        StorageClient storage = mock(StorageClient.class);
        when(storage.download(anyString(), anyString())).thenReturn(PNG);
        GenerationInputResolver resolver = new GenerationInputResolver(storage, 1_048_576L);
        Map<String, Object> body = built.params();
        GenerationInputResolver.Prepared prepared =
                resolver.prepare(seedanceShapedSpec(), body, "tenant-1");
        assertThat(prepared.ok()).as("the fixture's files must resolve: %s", prepared.errors()).isTrue();
        return body;
    }

    @Test
    @DisplayName("the relay and the direct path agree when the slot's own index is EMPTY before it")
    void agreesOnAGappedSlot() {
        // Three reference images on a call that pins no frame: content[1] and content[2] are
        // written empty by the path walker and pruned away, so the images end up at content[1..3]
        // while the descriptor names content[3]. A reader that trusts the index sees none of them.
        Map<String, Object> unified = new LinkedHashMap<>();
        unified.put("prompt", "a cat");
        unified.put("duration_seconds", 10);
        unified.put("input_image", List.of(fileRef("a"), fileRef("b"), fileRef("c")));

        GenerationRequestBuilder.Built direct = build(unified);
        assertThat(direct.ok()).isTrue();
        assertThat(direct.priceMultiplier()).isEqualByComparingTo("1.15");

        RelayedGenerationMeasurement.Measured relayed =
                RelayedGenerationMeasurement.measure(seedanceShapedSpec(), relayedBody(direct));

        // One request, one price, whichever door it came through.
        assertThat(relayed.priceMultiplier()).isEqualByComparingTo(direct.priceMultiplier());
    }

    @Test
    @DisplayName("and when the frames ARE pinned, which fills the earlier slots instead")
    void agreesOnThePinnedFramePath() {
        Map<String, Object> unified = new LinkedHashMap<>();
        unified.put("prompt", "a cat");
        unified.put("duration_seconds", 10);
        unified.put("first_frame_image", fileRef("open"));
        unified.put("last_frame_image", fileRef("close"));

        GenerationRequestBuilder.Built direct = build(unified);
        assertThat(direct.ok()).isTrue();
        assertThat(direct.priceMultiplier()).isEqualByComparingTo("1.1025");

        RelayedGenerationMeasurement.Measured relayed =
                RelayedGenerationMeasurement.measure(seedanceShapedSpec(), relayedBody(direct));

        assertThat(relayed.priceMultiplier()).isEqualByComparingTo(direct.priceMultiplier());
    }

    @Test
    @DisplayName("a single reference image is one file, not the whole array")
    void oneFileIsOneFile() {
        Map<String, Object> unified = new LinkedHashMap<>();
        unified.put("prompt", "a cat");
        unified.put("duration_seconds", 10);
        unified.put("input_image", List.of(fileRef("a")));

        GenerationRequestBuilder.Built direct = build(unified);
        RelayedGenerationMeasurement.Measured relayed =
                RelayedGenerationMeasurement.measure(seedanceShapedSpec(), relayedBody(direct));

        assertThat(direct.priceMultiplier()).isEqualByComparingTo("1.05");
        assertThat(relayed.priceMultiplier()).isEqualByComparingTo("1.05");
    }

    @Test
    @DisplayName("a call carrying no file at all is at the published rate on both paths")
    void noFilesIsTheRate() {
        Map<String, Object> unified = new LinkedHashMap<>();
        unified.put("prompt", "a cat");
        unified.put("duration_seconds", 10);

        GenerationRequestBuilder.Built direct = build(unified);
        RelayedGenerationMeasurement.Measured relayed =
                RelayedGenerationMeasurement.measure(seedanceShapedSpec(), relayedBody(direct));

        assertThat(direct.priceMultiplier()).isEqualByComparingTo(BigDecimal.ONE);
        assertThat(relayed.priceMultiplier()).isEqualByComparingTo(BigDecimal.ONE);
    }
}
