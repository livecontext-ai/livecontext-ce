package com.apimarketplace.common.web;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Item 6 (CASA readiness): {@link AppEditionAutoConfiguration} really hands the resolved edition to
 * {@link UrlSafetyValidator}. The validator's own tests call {@code configureEditionDefaults}
 * directly, so without this class nothing proves the startup wiring exists: deleting the call
 * would leave every self-hosted install refusing its LAN with the whole suite green.
 */
@DisplayName("AppEditionAutoConfiguration feeds the edition to the egress policy")
class EgressEditionWiringTest {

    @AfterEach
    void reset() {
        UrlSafetyValidator.resetEditionDefaultsForTests();
        UrlSafetyValidator.resetEnvironmentReaderForTests();
        System.clearProperty(UrlSafetyValidator.PRIVATE_EGRESS_ALLOW_LIST_PROPERTY);
    }

    private static void boot(MockEnvironment env) {
        // The edition flags must match what AppEditionProvider expects, or its boot summary throws.
        new AppEditionAutoConfiguration().appEditionProvider(env);
    }

    private static MockEnvironment ce() {
        return new MockEnvironment()
                .withProperty("app.edition", "ce")
                .withProperty("auth.mode", "embedded")
                .withProperty("credit.unlimited", "true")
                .withProperty("plan-limits.enabled", "false");
    }

    @Test
    @DisplayName("CE: a LAN database is reachable with no egress setting")
    void ceIsPermissiveForTheLan() {
        UrlSafetyValidator.setEnvironmentReaderForTests(name -> null);
        boot(ce());
        assertDoesNotThrow(() -> UrlSafetyValidator.assertOutboundHostSafe("10.0.9.5", 5432));
        assertThrows(IllegalArgumentException.class,
            () -> UrlSafetyValidator.assertOutboundHostSafe("127.0.0.1", 5432), "loopback never");
    }

    @Test
    @DisplayName("CLOUD: the same target is refused")
    void cloudIsStrict() {
        UrlSafetyValidator.setEnvironmentReaderForTests(name -> null);
        boot(ce()); // start permissive, then prove the cloud boot switches it back
        boot(new MockEnvironment().withProperty("app.edition", "cloud"));
        assertThrows(IllegalArgumentException.class,
            () -> UrlSafetyValidator.assertOutboundHostSafe("10.0.9.5", 5432));
    }

    @Test
    @DisplayName("no edition configured at all: strict (the cloud posture)")
    void missingEditionIsStrict() {
        UrlSafetyValidator.setEnvironmentReaderForTests(name -> null);
        boot(new MockEnvironment());
        assertThrows(IllegalArgumentException.class,
            () -> UrlSafetyValidator.assertOutboundHostSafe("10.0.9.5", 5432));
    }

    @Test
    @DisplayName("before any boot (a bare JVM, a unit test): strict")
    void beforeBootIsStrict() {
        UrlSafetyValidator.setEnvironmentReaderForTests(name -> null);
        assertThrows(IllegalArgumentException.class,
            () -> UrlSafetyValidator.assertOutboundHostSafe("10.0.9.5", 5432));
    }
}
