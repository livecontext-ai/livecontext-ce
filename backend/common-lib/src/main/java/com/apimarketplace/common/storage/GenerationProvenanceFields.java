package com.apimarketplace.common.storage;

/**
 * The names of the generation provenance, shared by the four sides that speak it.
 *
 * <p><b>What the provenance is.</b> The recipe a generated asset was made from: the model, the
 * prompt and the unified parameters the run was dispatched with. It is written onto the asset's own
 * {@code storage.storage} row, under {@link #METADATA_KEY} in the {@code metadata} jsonb, and that
 * placement is the whole design: an asset and the recipe that produced it are one object, so there
 * is nothing to keep in sync, nothing to garbage-collect when a file is deleted, and a history of
 * generations is a query over the files that carry one.</p>
 *
 * <p>It travels catalog-service → storage-service as a plain JSON object over
 * {@code POST /api/internal/storage/generation-provenance}, and comes back to the browser through
 * the storage explorer. Four modules, no shared DTO between them: written out by hand on each side
 * the names would be a convention held together by tests agreeing with each other, which is exactly
 * the failure {@link AdoptRunContextFields} was written to stop. Named once, the compiler carries
 * the contract.</p>
 *
 * <p><b>The caps are part of the contract, not a detail.</b> {@code metadata} is read back on every
 * explorer row that carries one, and a prompt has no length limit anywhere upstream. Trimming at the
 * producer keeps a jsonb column from growing without bound; a provenance still over
 * {@link #MAX_PROVENANCE_BYTES} once trimmed is dropped rather than stored, because a truncated
 * recipe that cannot reproduce the asset is worse than none: it would offer a Regenerate button that
 * quietly makes something else.</p>
 */
public final class GenerationProvenanceFields {

    /** Key under {@code storage.storage.metadata} holding the whole provenance object. */
    public static final String METADATA_KEY = "generation";

    // ── request / response wire names ───────────────────────────────────────

    /** Storage row ids to stamp (JSON array of UUID strings). */
    public static final String IDS = "ids";
    /** The provenance object itself. */
    public static final String PROVENANCE = "provenance";
    /** Response field: how many rows were actually stamped. */
    public static final String STAMPED = "stamped";

    // ── provenance object ───────────────────────────────────────────────────

    /** Public model id, the one {@code generation(action='models')} lists and a re-run needs. */
    public static final String MODEL = "model";
    /** Format produced: image, video, audio, voice, music, ... */
    public static final String KIND = "kind";
    /** Provider behind the model, for a row that wants to name it without resolving the model. */
    public static final String PROVIDER = "provider";
    /** The prompt as the caller wrote it, trimmed to {@link #MAX_PROMPT_CHARS}. */
    public static final String PROMPT = "prompt";
    /** Every other unified parameter, under its unified name. Input files stay whole FileRefs. */
    public static final String PARAMS = "params";
    /**
     * Which pool paid: {@code platform} or {@code user}.
     *
     * <p>Recorded as REPORTED by the execution, never as requested: an omitted source means the
     * catalog tries the caller's own key first and falls back, so the two can differ and only one
     * of them is what happened.
     *
     * <p>Deliberately not the credential ID. The row is visible to every org teammate who can see
     * the file, the id names an account object rather than a property of the asset, and a re-run
     * lands on the account's default key for the provider by itself.
     */
    public static final String CREDENTIAL_SOURCE = "credentialSource";
    /** The size the run was billed on, in {@link #BILLED_UNIT}. */
    public static final String BILLED_QUANTITY = "billedQuantity";
    public static final String BILLED_UNIT = "billedUnit";
    /**
     * Credits the platform actually charged for this generation, as the ledger committed them.
     *
     * <p>Written ONLY when the platform key paid and the charge was committed whole. A generation
     * run on the reader's own key is charged by their provider, not here; one on an install that
     * does not meter reaches no ledger at all; and both carry no amount. An absent amount must
     * never be read as zero: the two mean opposite things, and printing "0 credits" over a
     * generation somebody paid a provider for states the one thing this field exists to get right.
     *
     * <p>The number is the amount the reservation committed, not the seed rate and not a quote
     * recomputed at read time: a rate can be republished between the generation and the reading,
     * and the only figure worth showing next to an asset is the one that was actually taken.
     */
    public static final String BILLED_CREDITS = "billedCredits";
    /** When it was generated, ISO-8601. The row's own {@code created_at} agrees; this survives a copy. */
    public static final String AT = "at";

    /** Prompt characters kept. Past this the recipe is a story, not a parameter. */
    public static final int MAX_PROMPT_CHARS = 4000;
    /** Serialized provenance bytes accepted. Over it, nothing is stored - see the class doc. */
    public static final int MAX_PROVENANCE_BYTES = 16_384;

    private GenerationProvenanceFields() {
    }
}
