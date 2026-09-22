package com.apimarketplace.catalog.service.generation;

import com.apimarketplace.common.storage.GenerationProvenanceFields;
import com.apimarketplace.storage.client.StorageClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Write the recipe of a finished generation onto the asset it produced.
 *
 * <p><b>The problem it solves.</b> A generated file is indistinguishable from an uploaded one once
 * it lands in a workspace: same row, same bytes, no trace of the model or the words that made it. So
 * the two things a person always wants from an asset they generated - to browse what they have
 * generated, and to run one again with a single parameter changed - were both impossible, and the
 * only way to attempt the second was to retype the prompt from memory.
 *
 * <p>The recipe is stamped onto the asset's own storage row rather than kept in a table of its own.
 * That is deliberate: an asset and the recipe that produced it are one object, so there is nothing
 * to keep in sync, nothing to garbage-collect when the file is deleted, and the history of
 * generations is a query over the files that carry one - never a list of rows pointing at files
 * that may no longer exist.
 *
 * <p><b>Best-effort, always.</b> Every path here swallows its failures. By the time this is called
 * the generation has run, the asset is stored and the customer has been charged; failing the call
 * because a recipe could not be recorded would take away something they paid for to protect a
 * convenience. Silence is the correct outcome of a failure here, and it is logged.
 */
@Slf4j
@Service
public class GenerationProvenanceRecorder {

    /**
     * Optional for the same reason {@code BinaryResponseHandler} makes it optional: some profiles
     * do not wire the storage client at all, and a generation must still work there - it simply
     * records nothing.
     */
    @Autowired(required = false)
    private StorageClient storageClient;

    /**
     * What the asset was made from, ready to be stamped.
     *
     * @param model   public model id, the one a re-run needs
     * @param kind    format produced
     * @param provider provider behind the model
     * @param unified the unified parameters as the caller sent them, prompt included
     * @param credentialSource which pool actually paid, as REPORTED by the execution - not as
     *                         requested, because an omitted source lets the catalog fall back and
     *                         only one of the two is what happened
     * @param billedCredits what the platform charged, as the ledger committed it. Null whenever
     *                      the platform charged nothing - the reader's own key paid, or this
     *                      install has no ledger - and null is NOT zero: see
     *                      {@link GenerationProvenanceFields#BILLED_CREDITS}
     */
    public record Recipe(String model, String kind, String provider,
                         Map<String, Object> unified, String credentialSource,
                         BigDecimal billedQuantity, String billedUnit,
                         BigDecimal billedCredits) {}

    /**
     * Stamp the recipe on the asset, if there is an asset to stamp it on.
     *
     * <p>Addressed by the storage row id the upload handed back. A FileRef with no id is one this
     * platform did not store (a legacy shape, a provider link that was passed through), and there
     * is no row to annotate.
     */
    public void record(Map<String, Object> fileRef, Recipe recipe,
                       String tenantId, String organizationId) {
        if (storageClient == null || fileRef == null || recipe == null) {
            return;
        }
        Object id = fileRef.get("id");
        if (id == null || String.valueOf(id).isBlank()) {
            return;
        }
        try {
            Map<String, Object> provenance = describe(recipe);
            if (provenance.isEmpty()) {
                return;
            }
            storageClient.stampGenerationProvenance(
                    tenantId, organizationId, List.of(String.valueOf(id)), provenance);
        } catch (Exception e) {
            // Where a file came from must never decide whether the caller keeps it.
            log.warn("Could not record generation provenance for model {}: {}",
                    recipe.model(), e.getMessage());
        }
    }

    /**
     * Control keys that are never a dimension of the asset, and must not be stored as one.
     *
     * <p>{@code GenerationModule.collectUnifiedParams} filters these at the TOP level but copies a
     * caller's nested {@code params} map wholesale, so a caller that sends
     * {@code {"params":{"credential_id":42}}} gets that key into the unified map. Stored, it would
     * put an account object into a row every org teammate who can see the file can read, and replay
     * it as if it were a parameter of the image. The recipe describes the ASSET; who paid is
     * recorded once, by name, under {@code credentialSource}.
     */
    private static final java.util.Set<String> NOT_A_PARAMETER = java.util.Set.of(
            "credential_id", "credential_source", "credentialId", "credentialSource",
            "model", "kind", "action", "tool_id", "params");

    /**
     * The recipe as it is stored.
     *
     * <p>The prompt is separated from the other parameters because it is the one every format has
     * and the one a reader recognises an entry by; the rest travel under their unified names, which
     * is exactly the shape a re-run sends back, so nothing has to be translated to replay it.
     *
     * <p><b>The prompt, and only the prompt, is TRIMMED rather than dropped</b>
     * ({@link GenerationProvenanceFields#MAX_PROMPT_CHARS}). It is the one field with no length
     * limit anywhere upstream, and the column is read back on every history row, so something has
     * to bound it. Trimming is chosen over dropping the whole recipe because a prompt that long is
     * being edited anyway, and it is the one field the reader SEES before pressing the button - a
     * shortened prompt is visible on screen in a way a missing parameter never is. Every other
     * oversize recipe is refused whole, further down.
     *
     * <p>Input files keep their whole FileRef. A path or a link would not survive the round trip:
     * the platform reads the bytes out of storage to hand them to the provider in whatever shape
     * that provider wants, and the handle is what identifies them.
     */
    static Map<String, Object> describe(Recipe recipe) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (recipe.model() == null || recipe.model().isBlank()) {
            // Nothing can be reproduced without it, and a history row that cannot say what made it
            // is a row that only takes up space.
            return out;
        }
        out.put(GenerationProvenanceFields.MODEL, recipe.model());
        if (recipe.kind() != null) out.put(GenerationProvenanceFields.KIND, recipe.kind());
        if (recipe.provider() != null) out.put(GenerationProvenanceFields.PROVIDER, recipe.provider());

        Map<String, Object> unified = recipe.unified() == null ? Map.of() : recipe.unified();
        Object prompt = unified.get("prompt");
        if (prompt != null) {
            String text = String.valueOf(prompt);
            out.put(GenerationProvenanceFields.PROMPT,
                    text.length() > GenerationProvenanceFields.MAX_PROMPT_CHARS
                            ? text.substring(0, GenerationProvenanceFields.MAX_PROMPT_CHARS)
                            : text);
        }
        Map<String, Object> params = new LinkedHashMap<>();
        unified.forEach((key, value) -> {
            if (key == null || "prompt".equals(key) || value == null) return;
            if (NOT_A_PARAMETER.contains(key)) return;
            params.put(key, value);
        });
        if (!params.isEmpty()) out.put(GenerationProvenanceFields.PARAMS, params);

        if (recipe.credentialSource() != null) {
            out.put(GenerationProvenanceFields.CREDENTIAL_SOURCE, recipe.credentialSource());
        }
        if (recipe.billedQuantity() != null && recipe.billedQuantity().signum() > 0) {
            out.put(GenerationProvenanceFields.BILLED_QUANTITY, recipe.billedQuantity());
            if (recipe.billedUnit() != null) {
                out.put(GenerationProvenanceFields.BILLED_UNIT, recipe.billedUnit());
            }
        }
        // Only a positive charge is recorded. A zero would be indistinguishable on screen from a
        // real price of nothing, and there is no such price: an unbilled generation was paid for
        // somewhere else, which is a different fact from having cost nothing.
        if (recipe.billedCredits() != null && recipe.billedCredits().signum() > 0) {
            out.put(GenerationProvenanceFields.BILLED_CREDITS, recipe.billedCredits());
        }
        out.put(GenerationProvenanceFields.AT, Instant.now().toString());
        return out;
    }
}
