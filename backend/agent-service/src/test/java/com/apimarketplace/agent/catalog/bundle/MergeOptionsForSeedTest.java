package com.apimarketplace.agent.catalog.bundle;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link MergeOptions#forSeed()} semantics: the model-catalog seed reuses an
 * allowed source (no migration), must not mass-deprecate, and keeps enabled on
 * insert. Also pins that the new {@code honorEnabledOnInsert} flag is OFF for
 * the bundle/sync paths and survives the {@code with*} copy helpers.
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
    @DisplayName("bundle and sync paths do NOT honor enabled on insert (review-gate default)")
    void bundleAndSyncForceInactiveOnInsert() {
        assertThat(MergeOptions.forBundle(7L).honorEnabledOnInsert()).isFalse();
        assertThat(MergeOptions.forSync().honorEnabledOnInsert()).isFalse();
    }

    @Test
    @DisplayName("with* copy helpers preserve honorEnabledOnInsert")
    void withHelpersPreserveHonorEnabled() {
        MergeOptions seed = MergeOptions.forSeed();
        assertThat(seed.withDeprecateMissing(true).honorEnabledOnInsert()).isTrue();
        assertThat(seed.withLabel("relabelled").honorEnabledOnInsert()).isTrue();
        // and the bundle path stays false through a copy
        assertThat(MergeOptions.forBundle(1L).withLabel("x").honorEnabledOnInsert()).isFalse();
    }
}
