package com.apimarketplace.common.publication;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ShowcaseCaptureContractTest {

    @Test
    @DisplayName("The EPOCH_NOT_FOUND wire value is pinned: a deployed orchestrator and publication-service must agree on it")
    void epochNotFoundWireValueIsPinned() {
        // Both services read the constant, but the two are deployed separately: a peer still running
        // the previous image only knows the literal. Changing it is a wire-contract change, not a rename.
        assertThat(ShowcaseCaptureContract.EPOCH_NOT_FOUND).isEqualTo("EPOCH_NOT_FOUND");
    }
}
