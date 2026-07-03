package com.apimarketplace.agent.catalog.bundle;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link MergeOptions#forSeed()} semantics: the model-catalog seed reuses an
 * allowed source (no migration), must not mass-deprecate, and keeps enabled on
 * insert. Also pins that {@code honorEnabledOnInsert} is OFF for the feed-sync
 * path only (V381: the BUNDLE path honors it - the payload's effective enabled
 * is an explicit cloud-admin decision) and survives the {@code with*} helpers.
 */
@DisplayName("MergeOptions.forSeed")
class MergeOptionsForSeedTest {

    @Test
    @DisplayName("forSeed uses source=curated (allowed by the CHECK → no migration), no deprecate, honor enabled")
    void forSeedSemantics() {
        MergeOptions o = MergeOptions.forSeed();
        assertThat(o.source()).isEqualTo("curated");
        assertThat(o.bundleVersion()).isNull();
        assertThat(o.deprecateMissing()).isFalse();
        assertThat(o.honorEnabledOnInsert()).isTrue();
    }

    @Test
    @DisplayName("V381: sync keeps the review-gate; the bundle path honors the cloud's per-model decision")
    void syncGatedBundleHonors() {
        assertThat(MergeOptions.forSync().honorEnabledOnInsert())
                .as("untrusted feeds stay review-gated")
                .isFalse();
        assertThat(MergeOptions.forBundle(7L).honorEnabledOnInsert())
                .as("signed bundles carry an explicit cloud-admin enabled per model")
                .isTrue();
    }

    @Test
    @DisplayName("with* copy helpers preserve honorEnabledOnInsert")
    void withHelpersPreserveHonorEnabled() {
        MergeOptions seed = MergeOptions.forSeed();
        assertThat(seed.withDeprecateMissing(true).honorEnabledOnInsert()).isTrue();
        assertThat(seed.withLabel("relabelled").honorEnabledOnInsert()).isTrue();
        // and the sync path stays false through a copy
        assertThat(MergeOptions.forSync().withLabel("x").honorEnabledOnInsert()).isFalse();
    }
}
