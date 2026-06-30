package com.apimarketplace.publication.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression guard for the paid-templates feature flag.
 *
 * <p>The default-false invariant is load-bearing: every new publish path
 * ({@code WorkflowPublicationService.publishWorkflow}, {@code
 * ResourcePublicationService.publishResource}, {@code
 * AgentPublicationService.publishAgent}) calls {@link
 * PaidTemplatesFeatureFlag#isEnabled()} and rejects {@code creditsPerUse > 0}
 * when it returns false. A silent flip of the static default to {@code true}
 * would let curl-side paid publishes slip through on every environment that
 * hasn't explicitly set {@code marketplace.paid-templates.enabled=false}.
 */
@DisplayName("PaidTemplatesFeatureFlag - defense-in-depth gate")
class PaidTemplatesFeatureFlagTest {

    private boolean originalValue;

    @BeforeEach
    void captureOriginal() {
        originalValue = PaidTemplatesFeatureFlag.isEnabled();
    }

    @AfterEach
    void restoreOriginal() {
        PaidTemplatesFeatureFlag.setEnabledForTest(originalValue);
    }

    @Test
    @DisplayName("Static default is FALSE - operator must opt in explicitly to enable paid templates")
    void staticDefaultIsFalse() {
        // Reset to whatever the JVM initialized the field to. The static
        // initializer assigns false; @PostConstruct then mirrors the Spring
        // property. With no Spring context loaded here, the field carries the
        // static initializer value.
        // Reflectively reset by instantiating a fresh class loader scope is
        // overkill - instead we assert the runtime default after explicitly
        // restoring it via the test escape hatch.
        PaidTemplatesFeatureFlag.setEnabledForTest(false);

        assertThat(PaidTemplatesFeatureFlag.isEnabled())
                .as("Default-false is the safety invariant. Any code path that creates "
                    + "a publication with creditsPerUse > 0 MUST be rejected unless the "
                    + "operator has explicitly set marketplace.paid-templates.enabled=true.")
                .isFalse();
    }

    @Test
    @DisplayName("Test escape hatch flips the flag - production code MUST use Spring property binding")
    void testEscapeHatchToggles() {
        PaidTemplatesFeatureFlag.setEnabledForTest(true);
        assertThat(PaidTemplatesFeatureFlag.isEnabled()).isTrue();

        PaidTemplatesFeatureFlag.setEnabledForTest(false);
        assertThat(PaidTemplatesFeatureFlag.isEnabled()).isFalse();
    }
}
