package com.apimarketplace.agent.catalog.bundle;

/**
 * Caller intent for {@link CatalogMergeService#merge}.
 *
 * <p>Two canonical variants:
 * <ul>
 *   <li>{@link #forBundle(long)} - bundle apply path. A bundle is an
 *       authoritative snapshot of the cloud catalog at a point in time, so
 *       rows absent from the bundle are deprecated and every touched row gets
 *       {@code bundle_version} stamped.</li>
 *   <li>{@link #forSync()} - external feed sync (LiteLLM / OpenRouter). A
 *       partial feed response must NOT mass-deprecate the catalog, so
 *       {@code deprecateMissing} defaults to false. {@code bundle_version}
 *       stays untouched (the row still belongs to whatever bundle last
 *       applied it).</li>
 * </ul>
 *
 * @param source           value written to the {@code source} column on
 *                         freshly-inserted rows. Existing rows keep their own
 *                         source.
 * @param label            short tag for logs (e.g. "bundle v42",
 *                         "sync-litellm").
 * @param bundleVersion    stamped on every touched row when non-null. Null
 *                         means "leave existing bundle_version untouched".
 * @param deprecateMissing true → non-custom rows (INCLUDING bridge rows - the
 *                         cloud allowlist is authoritative, so a bridge model
 *                         retired cloud-side must retire here too) absent from
 *                         the incoming set receive {@code deprecated_at=now()}.
 *                         false → absence is ignored (sync default).
 * @param honorEnabledOnInsert true → a freshly-inserted row keeps the payload's
 *                         {@code enabled} flag (bundle and seed paths: the
 *                         payload's enabled is a signed cloud-admin decision).
 *                         false → new inserts are forced {@code enabled=false}
 *                         (SYNC-only review-gate: untrusted feeds must not
 *                         auto-expose new models).
 */
public record MergeOptions(String source, String label,
                           Long bundleVersion, boolean deprecateMissing,
                           boolean honorEnabledOnInsert) {

    /**
     * Bundle apply: authoritative snapshot, version stamped, absent rows
     * deprecated.
     *
     * <p>V381: freshly-inserted rows KEEP the payload's {@code enabled}. The
     * old review-gate (insert disabled, admin enables by hand) predates
     * {@code bundle_enabled}: the payload's effective enabled is now an
     * explicit per-model cloud-admin decision about what CE installs get, so
     * force-disabling on insert silently vetoed that decision for every model
     * the CE had not seen yet. Enabling a model grants no capability by
     * itself - a provider without keys/bridge stays unreachable in the picker.
     */
    public static MergeOptions forBundle(long bundleVersion) {
        return new MergeOptions("bundle", "bundle v" + bundleVersion,
                bundleVersion, true, true);
    }

    /**
     * Feed sync: NO version stamp, absent rows kept (a single bad feed
     * response must not wipe the catalog). Callers that want deprecate-on-miss
     * after they gain confidence in the feed can flip the flag via
     * {@link #withDeprecateMissing(boolean)}.
     */
    public static MergeOptions forSync() {
        // 'manual' is the catch-all allowed by model_config_overrides_source_check;
        // individual rows will almost always override this with their feed's
        // own source ("litellm" / "openrouter") stamped by the parser.
        return new MergeOptions("manual", "sync-feed", null, false, false);
    }

    /**
     * Model-catalog SEED apply (boot-time, CE-only). The seed is the curated
     * baseline shipped with the code, so {@code source="curated"} - already in
     * {@code model_config_overrides_source_check}, so no new migration. Like a
     * feed it must NOT mass-deprecate ({@code deprecateMissing=false}): models
     * absent from the seed may be bundle/admin/sync additions. Like the bundle
     * path (and unlike feed sync), a freshly-inserted seed model KEEPS the
     * payload's {@code enabled} (default true) via
     * {@code honorEnabledOnInsert=true}, so a fresh CE has the curated catalog
     * usable out of the box instead of review-gated off.
     */
    public static MergeOptions forSeed() {
        return new MergeOptions("curated", "model-seed", null, false, true);
    }

    public MergeOptions withDeprecateMissing(boolean deprecate) {
        return new MergeOptions(source, label, bundleVersion, deprecate, honorEnabledOnInsert);
    }

    public MergeOptions withLabel(String newLabel) {
        return new MergeOptions(source, newLabel, bundleVersion, deprecateMissing, honorEnabledOnInsert);
    }
}
