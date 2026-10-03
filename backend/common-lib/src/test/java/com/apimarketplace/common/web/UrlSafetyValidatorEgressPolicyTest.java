package com.apimarketplace.common.web;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.InetAddress;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The edition-aware private egress policy (LC-074 / LC-075 / LC-006, CASA readiness).
 *
 * <p>A self-hosted install connects to its own LAN (a database on 10.x, an SMTP relay, a service
 * on the Docker network) and must keep doing so after the SSRF guard was extended to the
 * connector nodes and to every catalog call. A cloud install must not. Loopback and the metadata
 * endpoint are refused in every configuration.
 */
@DisplayName("UrlSafetyValidator - edition-aware private egress policy")
class UrlSafetyValidatorEgressPolicyTest {

    @AfterEach
    void reset() {
        UrlSafetyValidator.resetEditionDefaultsForTests();
        UrlSafetyValidator.resetDnsResolverForTests();
        UrlSafetyValidator.resetEnvironmentReaderForTests();
        System.clearProperty(UrlSafetyValidator.PRIVATE_EGRESS_ALLOW_LIST_PROPERTY);
        System.clearProperty(UrlSafetyValidator.PRIVATE_EGRESS_HOST_SUFFIX_PROPERTY);
    }

    private static void resolveTo(String ip) {
        UrlSafetyValidator.setDnsResolverForTests(host -> new InetAddress[] {InetAddress.getByName(ip)});
    }

    /**
     * A name that does not resolve is thrown as its own type, so a caller that reached the host a
     * moment ago (the async poll of an accepted job) can retry it, while a private answer stays a
     * refusal. Regression review 2026-09-29.
     */
    @Test
    @DisplayName("an unresolvable name is an UnresolvableHostException (still an IllegalArgumentException); a private answer is not")
    void unresolvableHostHasItsOwnType() {
        UrlSafetyValidator.setDnsResolverForTests(host -> { throw new java.net.UnknownHostException(host); });
        IllegalArgumentException unresolved = assertThrows(IllegalArgumentException.class,
            () -> UrlSafetyValidator.validateEgressUrl("https://api.example.com/jobs/1"));
        assertTrue(unresolved instanceof UnresolvableHostException, unresolved.getClass().getName());
        assertEquals("Cannot resolve hostname: api.example.com", unresolved.getMessage());

        resolveTo("10.96.0.12");
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
            () -> UrlSafetyValidator.validateEgressUrl("https://api.example.com/jobs/1"));
        assertTrue(!(refused instanceof UnresolvableHostException) && !(refused instanceof UrlResolutionException),
            refused.getClass().getName());
    }

    @Test
    @DisplayName("every other 'cannot resolve' site is an UnresolvableHostException too (empty answer, socket targets, a resolver that dies)")
    void everyUnresolvableSiteHasTheType() {
        // An empty answer, for the URL checks and for a raw socket target (connectors).
        UrlSafetyValidator.setDnsResolverForTests(host -> new InetAddress[0]);
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> UrlSafetyValidator.validateEgressUrl("https://api.example.com/x")) instanceof UnresolvableHostException);
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> UrlSafetyValidator.validateUrl("https://api.example.com/x")) instanceof UnresolvableHostException);
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> UrlSafetyValidator.resolveOutboundHostSafe("db.example.com", 5432)) instanceof UnresolvableHostException);

        // A lookup that died with an Error rather than UnknownHostException.
        UrlSafetyValidator.setDnsResolverForTests(host -> { throw new StackOverflowError("resolver"); });
        IllegalArgumentException died = assertThrows(IllegalArgumentException.class,
                () -> UrlSafetyValidator.validateEgressUrl("https://api.example.com/x"));
        assertTrue(died instanceof UnresolvableHostException, died.getClass().getName());
        assertEquals("Cannot resolve hostname: api.example.com", died.getMessage());
    }

    @Nested
    @DisplayName("cloud edition (the default before configureEditionDefaults runs)")
    class Cloud {

        @Test
        @DisplayName("a private connector target is refused")
        void privateTargetRefused() {
            UrlSafetyValidator.configureEditionDefaults(false);
            assertThrows(IllegalArgumentException.class,
                () -> UrlSafetyValidator.assertOutboundHostSafe("10.0.9.5", 5432));
        }

        @Test
        @DisplayName("validateEgressUrl is as strict as validateUrl")
        void egressUrlStrict() {
            UrlSafetyValidator.configureEditionDefaults(false);
            assertThrows(IllegalArgumentException.class,
                () -> UrlSafetyValidator.validateEgressUrl("http://10.0.9.5:8083/api/internal/credentials"));
            resolveTo("10.96.0.12");
            assertThrows(IllegalArgumentException.class,
                () -> UrlSafetyValidator.validateEgressUrl("http://auth-service:8083/api/internal/x"));
        }

        @Test
        @DisplayName("validateEgressUrl lets a public target through and returns the vetted address")
        void egressUrlPublic() throws Exception {
            resolveTo("93.184.216.34");
            InetAddress[] vetted = UrlSafetyValidator.validateEgressUrl("https://api.example.com/v1");
            assertEquals(InetAddress.getByName("93.184.216.34"), vetted[0]);
        }
    }

    @Nested
    @DisplayName("self-hosted edition")
    class SelfHosted {

        @ParameterizedTest
        @ValueSource(strings = {"10.0.9.5", "172.20.0.3", "192.168.1.10", "100.64.0.1", "fd00::1"})
        @DisplayName("a LAN connector target is reachable with no configuration")
        void lanTargetReachableByDefault(String host) {
            UrlSafetyValidator.configureEditionDefaults(true);
            assertDoesNotThrow(() -> UrlSafetyValidator.assertOutboundHostSafe(host, 5432));
        }

        @Test
        @DisplayName("a custom API on the Docker host is reachable through validateEgressUrl")
        void dockerHostReachable() {
            UrlSafetyValidator.configureEditionDefaults(true);
            resolveTo("192.168.65.254");
            assertDoesNotThrow(
                () -> UrlSafetyValidator.validateEgressUrl("http://host.docker.internal:11434/api/tags"));
            assertDoesNotThrow(() -> UrlSafetyValidator.validateEgressUrl("http://10.0.0.8:8080/x"));
        }

        @Test
        @DisplayName("validateUrl (HTTP Request / Download nodes) stays strict on a self-hosted install")
        void validateUrlStaysStrict() {
            UrlSafetyValidator.configureEditionDefaults(true);
            assertThrows(IllegalArgumentException.class,
                () -> UrlSafetyValidator.validateUrl("http://10.0.9.5/admin"));
        }

        @ParameterizedTest
        @ValueSource(strings = {"127.0.0.1", "::1", "169.254.169.254", "0.0.0.0", "224.0.0.1", "ff02::1"})
        @DisplayName("loopback, metadata, unspecified and multicast stay refused")
        void neverAllowListableStayRefused(String host) {
            UrlSafetyValidator.configureEditionDefaults(true);
            assertThrows(IllegalArgumentException.class,
                () -> UrlSafetyValidator.assertOutboundHostSafe(host, 80));
        }

        @Test
        @DisplayName("localhost and the metadata names stay refused through validateEgressUrl")
        void loopbackNamesRefused() {
            UrlSafetyValidator.configureEditionDefaults(true);
            resolveTo("10.0.0.1");
            assertThrows(IllegalArgumentException.class,
                () -> UrlSafetyValidator.validateEgressUrl("http://localhost:8083/x"));
            assertThrows(IllegalArgumentException.class,
                () -> UrlSafetyValidator.validateEgressUrl("http://metadata.google.internal./x"));
            assertThrows(IllegalArgumentException.class,
                () -> UrlSafetyValidator.validateEgressUrl("http://127.0.0.1:8083/x"));
        }

        @Test
        @DisplayName("an operator can opt back into the cloud posture with 'none'")
        void noneRestoresRefusal() {
            UrlSafetyValidator.configureEditionDefaults(true);
            System.setProperty(UrlSafetyValidator.PRIVATE_EGRESS_ALLOW_LIST_PROPERTY, "none");
            assertThrows(IllegalArgumentException.class,
                () -> UrlSafetyValidator.assertOutboundHostSafe("10.0.9.5", 5432));
        }

        @Test
        @DisplayName("an explicit setting replaces the default rather than adding to it")
        void explicitSettingWins() {
            UrlSafetyValidator.configureEditionDefaults(true);
            System.setProperty(UrlSafetyValidator.PRIVATE_EGRESS_ALLOW_LIST_PROPERTY, "10.0.0.0/8");
            assertDoesNotThrow(() -> UrlSafetyValidator.assertOutboundHostSafe("10.0.9.5", 5432));
            assertThrows(IllegalArgumentException.class,
                () -> UrlSafetyValidator.assertOutboundHostSafe("192.168.1.10", 5432));
        }

        @Test
        @DisplayName("a blank environment value (Compose pass-through) keeps the edition default")
        void blankEnvKeepsDefault() {
            UrlSafetyValidator.configureEditionDefaults(true);
            UrlSafetyValidator.setEnvironmentReaderForTests(name -> "");
            assertDoesNotThrow(() -> UrlSafetyValidator.assertOutboundHostSafe("10.0.9.5", 5432));
        }
    }

    @Test
    @DisplayName("an explicit loopback allow-list entry is ignored: loopback is never allow-listable")
    void loopbackNeverAllowListable() {
        System.setProperty(UrlSafetyValidator.PRIVATE_EGRESS_ALLOW_LIST_PROPERTY, "127.0.0.0/8,::1/128,0.0.0.0/0");
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
            () -> UrlSafetyValidator.assertOutboundHostSafe("127.0.0.1", 5432));
        assertTrue(e.getMessage().contains("not allowed"), e.getMessage());
        assertThrows(IllegalArgumentException.class,
            () -> UrlSafetyValidator.assertOutboundHostSafe("::1", 5432));
    }

    @Test
    @DisplayName("multicast is refused by validateUrl")
    void multicastRefused() {
        assertThrows(IllegalArgumentException.class, () -> UrlSafetyValidator.validateUrl("http://239.1.2.3/"));
        assertThrows(IllegalArgumentException.class, () -> UrlSafetyValidator.validateUrl("http://[ff05::1]/"));
    }
}
