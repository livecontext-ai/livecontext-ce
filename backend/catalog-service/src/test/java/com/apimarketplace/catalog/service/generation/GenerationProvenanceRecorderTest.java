package com.apimarketplace.catalog.service.generation;

import com.apimarketplace.common.storage.GenerationProvenanceFields;
import com.apimarketplace.storage.client.StorageClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * Unit tests for {@link GenerationProvenanceRecorder}.
 *
 * <p>The recipe is what turns a generated file into something a person can recognise and run again.
 * Two properties matter more than the happy path. It has to be REPLAYABLE - every parameter as it
 * was sent, input files as whole handles - because a recipe missing one parameter reproduces a
 * different asset while claiming to reproduce this one. And it has to be SILENT on failure: by the
 * time it is called the generation has run and the customer has been charged, so nothing here may
 * cost them the asset they paid for.
 */
@DisplayName("GenerationProvenanceRecorder")
class GenerationProvenanceRecorderTest {

    private static final String TENANT = "tenant-1";
    private static final String ORG = "org-9";

    private static Map<String, Object> unified(Map<String, Object> extra) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("prompt", "a lighthouse at dusk");
        out.put("aspect_ratio", "16:9");
        out.putAll(extra);
        return out;
    }

    private static GenerationProvenanceRecorder.Recipe recipe(Map<String, Object> unified) {
        return new GenerationProvenanceRecorder.Recipe(
                "flux-1.1-pro", "image", "Flux", unified, "platform",
                new BigDecimal("1"), "image", new BigDecimal("42"));
    }

    private static Map<String, Object> fileRef(String id) {
        Map<String, Object> ref = new LinkedHashMap<>();
        ref.put("_type", "file");
        ref.put("path", "tenant/general/flux.png");
        ref.put("name", "flux.png");
        ref.put("mimeType", "image/png");
        ref.put("size", 2048);
        if (id != null) ref.put("id", id);
        return ref;
    }

    @Nested
    @DisplayName("what gets recorded")
    class Shape {

        @Test
        @DisplayName("keeps the prompt apart from the other parameters")
        void separatesThePrompt() {
            // The prompt is the one thing every format has and the one a reader recognises an entry
            // by, so it is a field of its own rather than one key among the model's parameters.
            Map<String, Object> described =
                    GenerationProvenanceRecorder.describe(recipe(unified(Map.of())));

            assertThat(described.get(GenerationProvenanceFields.PROMPT))
                    .isEqualTo("a lighthouse at dusk");
            @SuppressWarnings("unchecked")
            Map<String, Object> params =
                    (Map<String, Object>) described.get(GenerationProvenanceFields.PARAMS);
            assertThat(params).containsEntry("aspect_ratio", "16:9").doesNotContainKey("prompt");
        }

        @Test
        @DisplayName("keeps an input file as its whole handle")
        void keepsFileHandlesWhole() {
            // A path or a link would not survive the round trip: the platform reads the bytes out
            // of storage to hand them to the provider, and only the handle identifies them.
            Map<String, Object> input = fileRef("file-7");
            Map<String, Object> described = GenerationProvenanceRecorder.describe(
                    recipe(unified(Map.of("input_image", input))));

            @SuppressWarnings("unchecked")
            Map<String, Object> params =
                    (Map<String, Object>) described.get(GenerationProvenanceFields.PARAMS);
            assertThat(params.get("input_image")).isEqualTo(input);
        }

        @Test
        @DisplayName("trims a prompt longer than the cap instead of dropping the recipe")
        void trimsALongPrompt() {
            // A prompt has no length limit upstream and the metadata column is read back on every
            // history row. Trimming the one unbounded field keeps the recipe storable.
            Map<String, Object> longPrompt = unified(Map.of());
            longPrompt.put("prompt", "x".repeat(GenerationProvenanceFields.MAX_PROMPT_CHARS + 500));
            Map<String, Object> described =
                    GenerationProvenanceRecorder.describe(recipe(longPrompt));

            assertThat((String) described.get(GenerationProvenanceFields.PROMPT))
                    .hasSize(GenerationProvenanceFields.MAX_PROMPT_CHARS);
        }

        @Test
        @DisplayName("records which pool paid, by name")
        void recordsThePayingPool() {
            Map<String, Object> described =
                    GenerationProvenanceRecorder.describe(recipe(unified(Map.of())));

            assertThat(described.get(GenerationProvenanceFields.CREDENTIAL_SOURCE)).isEqualTo("platform");
        }

        @Test
        @DisplayName("records what the platform charged, so the asset can state its own price later")
        void recordsWhatItCost() {
            Map<String, Object> described =
                    GenerationProvenanceRecorder.describe(recipe(unified(Map.of())));

            assertThat(described.get(GenerationProvenanceFields.BILLED_CREDITS))
                    .isEqualTo(new BigDecimal("42"));
        }

        @Test
        @DisplayName("records NO price at all when the platform charged nothing, because absent and "
                + "zero are opposite facts on screen")
        void recordsNoPriceWhenNothingWasCharged() {
            // The reader's own key paid the provider directly. Storing 0 here would put "0 credits"
            // under an asset they were charged for elsewhere - a claim, not a missing value.
            Map<String, Object> described = GenerationProvenanceRecorder.describe(
                    new GenerationProvenanceRecorder.Recipe(
                            "flux-1.1-pro", "image", "Flux", unified(Map.of()), "user",
                            null, null, null));

            assertThat(described).doesNotContainKey(GenerationProvenanceFields.BILLED_CREDITS);
        }

        @Test
        @DisplayName("records NO price for an amount of zero either, because zero is a price and "
                + "absent is the absence of one")
        void recordsNoPriceForZero() {
            // Reachable through an endpoint published at nothing. Stored, it would draw "0 credits"
            // under the asset, which reads as "this was free" rather than as "nobody charged you".
            Map<String, Object> described = GenerationProvenanceRecorder.describe(
                    new GenerationProvenanceRecorder.Recipe(
                            "flux-1.1-pro", "image", "Flux", unified(Map.of()), "platform",
                            null, null, BigDecimal.ZERO));

            assertThat(described).doesNotContainKey(GenerationProvenanceFields.BILLED_CREDITS);
        }

        @Test
        @DisplayName("keeps a control key out of the stored parameters, wherever it came from")
        void neverStoresAControlKeyAsAParameter() {
            // This is a REACHABLE path, not a hypothetical: the module filters control keys at the
            // top level of the request but copies a caller's nested `params` map wholesale, so
            // {"params":{"credential_id":42}} arrives here as an ordinary unified parameter. Stored,
            // it would put an account object into a row every org teammate who can see the file can
            // read, and replay it as if it were a dimension of the image.
            Map<String, Object> described = GenerationProvenanceRecorder.describe(recipe(unified(Map.of(
                    "credential_id", 42,
                    "credential_source", "user",
                    "tool_id", "abc"))));

            @SuppressWarnings("unchecked")
            Map<String, Object> params =
                    (Map<String, Object>) described.get(GenerationProvenanceFields.PARAMS);
            assertThat(params)
                    .doesNotContainKey("credential_id")
                    .doesNotContainKey("credential_source")
                    .doesNotContainKey("tool_id")
                    // The real parameters are untouched by the filter.
                    .containsEntry("aspect_ratio", "16:9");
            // Deliberately NOT a search for "42" over the whole map: it carries an ISO timestamp,
            // so that assertion fails whenever the clock's minutes or seconds happen to contain it.
            assertThat(params.values().toString()).doesNotContain("42");
        }

        @Test
        @DisplayName("records nothing at all without a model")
        void refusesWithoutAModel() {
            // Nothing can be reproduced without it, and a history row that cannot say what made it
            // only takes up space.
            Map<String, Object> described = GenerationProvenanceRecorder.describe(
                    new GenerationProvenanceRecorder.Recipe(
                            null, "image", "Flux", unified(Map.of()), "platform", null, null, null));

            assertThat(described).isEmpty();
        }
    }

    @Nested
    @DisplayName("when it must stay out of the way")
    class BestEffort {

        @Test
        @DisplayName("says nothing when the asset carries no storage id")
        void skipsAFileRefWithoutAnId() {
            // No id means this platform did not store the file, so there is no row to annotate.
            StorageClient client = mock(StorageClient.class);
            GenerationProvenanceRecorder recorder = recorderWith(client);

            recorder.record(fileRef(null), recipe(unified(Map.of())), TENANT, ORG);

            verifyNoInteractions(client);
        }

        @Test
        @DisplayName("does nothing at all when no storage client is wired")
        void survivesWithoutAClient() {
            // Some profiles wire no storage client. A generation must still work there; it simply
            // records nothing.
            GenerationProvenanceRecorder recorder = new GenerationProvenanceRecorder();

            recorder.record(fileRef("file-7"), recipe(unified(Map.of())), TENANT, ORG);
            // No exception is the assertion: the caller has already been charged for the asset.
        }

        @Test
        @DisplayName("swallows a storage failure rather than failing a paid generation")
        void swallowsAFailure() {
            StorageClient client = mock(StorageClient.class);
            org.mockito.Mockito.when(client.stampGenerationProvenance(anyString(), any(), any(), any()))
                    .thenThrow(new IllegalStateException("storage is down"));
            GenerationProvenanceRecorder recorder = recorderWith(client);

            recorder.record(fileRef("file-7"), recipe(unified(Map.of())), TENANT, ORG);
            // Again: no exception. Where a file came from must never decide whether it is kept.
        }

        @Test
        @DisplayName("addresses the asset by its storage id, under the caller's own workspace")
        void addressesTheRightRow() {
            StorageClient client = mock(StorageClient.class);
            GenerationProvenanceRecorder recorder = recorderWith(client);

            recorder.record(fileRef("file-7"), recipe(unified(Map.of())), TENANT, ORG);

            verify(client).stampGenerationProvenance(
                    eq(TENANT), eq(ORG), eq(List.of("file-7")), any());
        }

        @Test
        @DisplayName("hands the storage service the whole recipe, not an empty map")
        void sendsTheRecipeItself() {
            // Asserting only that the client was CALLED would pass for a recorder that ships
            // nothing: the asset would carry a recipe key with no recipe in it, and the history
            // would list an entry that can reproduce nothing.
            StorageClient client = mock(StorageClient.class);
            GenerationProvenanceRecorder recorder = recorderWith(client);

            recorder.record(fileRef("file-7"),
                    recipe(unified(Map.of("input_image", fileRef("in-1")))), TENANT, ORG);

            @SuppressWarnings("unchecked")
            ArgumentCaptor<Map<String, Object>> sent = ArgumentCaptor.forClass(Map.class);
            verify(client).stampGenerationProvenance(anyString(), any(), any(), sent.capture());
            Map<String, Object> provenance = sent.getValue();
            assertThat(provenance).containsEntry(GenerationProvenanceFields.MODEL, "flux-1.1-pro");
            assertThat(provenance).containsEntry(GenerationProvenanceFields.PROMPT, "a lighthouse at dusk");
            assertThat(provenance).containsKey(GenerationProvenanceFields.AT);
            @SuppressWarnings("unchecked")
            Map<String, Object> params =
                    (Map<String, Object>) provenance.get(GenerationProvenanceFields.PARAMS);
            assertThat(params.get("input_image")).isEqualTo(fileRef("in-1"));
        }

        @Test
        @DisplayName("asks nothing of storage when there is no recipe worth recording")
        void skipsWhenThereIsNothingToRecord() {
            // No model means nothing can be reproduced. Calling anyway would write a key with a
            // useless value onto the asset, which the history would then list.
            StorageClient client = mock(StorageClient.class);
            GenerationProvenanceRecorder recorder = recorderWith(client);

            recorder.record(fileRef("file-7"), new GenerationProvenanceRecorder.Recipe(
                    null, "image", "Flux", unified(Map.of()), "platform", null, null, null), TENANT, ORG);

            verifyNoInteractions(client);
        }
    }

    /** Field injection is what production uses (the client is optional), so the test mirrors it. */
    private static GenerationProvenanceRecorder recorderWith(StorageClient client) {
        GenerationProvenanceRecorder recorder = new GenerationProvenanceRecorder();
        try {
            var field = GenerationProvenanceRecorder.class.getDeclaredField("storageClient");
            field.setAccessible(true);
            field.set(recorder, client);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
        return recorder;
    }
}
