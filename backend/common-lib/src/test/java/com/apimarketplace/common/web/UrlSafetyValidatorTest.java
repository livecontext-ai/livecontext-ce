package com.apimarketplace.common.web;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.InetAddress;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for UrlSafetyValidator (SSRF protection).
 */
@DisplayName("UrlSafetyValidator")
class UrlSafetyValidatorTest {

    @AfterEach
    void resetDnsResolver() {
        UrlSafetyValidator.resetDnsResolverForTests();
    }

    @Nested
    @DisplayName("Allowed URLs")
    class AllowedUrls {

        @ParameterizedTest
        @ValueSource(strings = {
            "https://example.com",
            "https://example.com/path/to/resource",
            "http://example.com/api?key=value",
            "https://api.github.com/repos",
            "http://93.184.216.34/page"
        })
        @DisplayName("should allow valid external URLs")
        void shouldAllowValidExternalUrls(String url) {
            assertDoesNotThrow(() -> UrlSafetyValidator.validateUrl(url));
        }
    }

    @Nested
    @DisplayName("Blocked URLs - scheme violations")
    class SchemeViolations {

        @ParameterizedTest
        @ValueSource(strings = {
            "ftp://example.com/file",
            "file:///etc/passwd",
            "ssh://example.com",
            "gopher://example.com"
        })
        @DisplayName("should reject non-HTTP schemes")
        void shouldRejectNonHttpSchemes(String url) {
            IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class, () -> UrlSafetyValidator.validateUrl(url));
            assertTrue(ex.getMessage().contains("Only http and https"));
        }

        @Test
        @DisplayName("should reject URL with no scheme")
        void shouldRejectNoScheme() {
            assertThrows(IllegalArgumentException.class,
                () -> UrlSafetyValidator.validateUrl("example.com/path"));
        }
    }

    @Nested
    @DisplayName("Blocked URLs - loopback addresses")
    class LoopbackAddresses {

        @ParameterizedTest
        @ValueSource(strings = {
            "http://127.0.0.1",
            "http://127.0.0.1:8080/admin",
            "http://127.0.1.1/path",
            "http://localhost",
            "http://localhost:3000",
            "http://sub.localhost"
        })
        @DisplayName("should reject loopback and localhost addresses")
        void shouldRejectLoopback(String url) {
            assertThrows(IllegalArgumentException.class,
                () -> UrlSafetyValidator.validateUrl(url));
        }
    }

    @Nested
    @DisplayName("Blocked URLs - private IP ranges")
    class PrivateIpRanges {

        @ParameterizedTest
        @ValueSource(strings = {
            "http://10.0.0.1",
            "http://10.0.0.0:8080",
            "http://10.255.255.255/path",
            "http://172.16.0.1",
            "http://172.31.255.255",
            "http://192.168.0.1",
            "http://192.168.1.100:9090"
        })
        @DisplayName("should reject private IP addresses")
        void shouldRejectPrivateIps(String url) {
            assertThrows(IllegalArgumentException.class,
                () -> UrlSafetyValidator.validateUrl(url));
        }
    }

    @Nested
    @DisplayName("Blocked URLs - link-local (cloud metadata)")
    class LinkLocalAddresses {

        @ParameterizedTest
        @ValueSource(strings = {
            "http://169.254.169.254",
            "http://169.254.169.254/latest/meta-data/",
            "http://169.254.0.1"
        })
        @DisplayName("should reject link-local addresses (cloud metadata)")
        void shouldRejectLinkLocal(String url) {
            assertThrows(IllegalArgumentException.class,
                () -> UrlSafetyValidator.validateUrl(url));
        }
    }

    @Nested
    @DisplayName("Blocked URLs - null and blank")
    class NullAndBlank {

        @Test
        @DisplayName("should reject null URL")
        void shouldRejectNull() {
            assertThrows(IllegalArgumentException.class,
                () -> UrlSafetyValidator.validateUrl(null));
        }

        @Test
        @DisplayName("should reject blank URL")
        void shouldRejectBlank() {
            assertThrows(IllegalArgumentException.class,
                () -> UrlSafetyValidator.validateUrl("   "));
        }

        @Test
        @DisplayName("should reject empty URL")
        void shouldRejectEmpty() {
            assertThrows(IllegalArgumentException.class,
                () -> UrlSafetyValidator.validateUrl(""));
        }
    }

    @Nested
    @DisplayName("Blocked URLs - missing or invalid host")
    class InvalidHost {

        @Test
        @DisplayName("should reject URL with unresolvable hostname")
        void shouldRejectUnresolvableHost() {
            assertThrows(IllegalArgumentException.class,
                () -> UrlSafetyValidator.validateUrl("http://thishostdoesnotexist12345.invalid/path"));
        }

        @Test
        @DisplayName("dnsResolutionTimeoutRejectsSlowResolvers: URL validation fails instead of hanging execution")
        void dnsResolutionTimeoutRejectsSlowResolvers() {
            UrlSafetyValidator.setDnsResolverForTests(host -> {
                try {
                    Thread.sleep(30_000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return new InetAddress[]{InetAddress.getByName("93.184.216.34")};
            });

            IllegalArgumentException ex = assertTimeoutPreemptively(Duration.ofSeconds(10), () ->
                assertThrows(IllegalArgumentException.class,
                    () -> UrlSafetyValidator.validateUrl("https://slow-dns.example/file.xml")));

            assertTrue(ex.getMessage().contains("DNS resolution timed out"));
        }

        @Test
        @DisplayName("dnsResolutionCapacityRejectsExhaustion: slow DNS cannot create unbounded resolver threads")
        void dnsResolutionCapacityRejectsExhaustion() throws Exception {
            CountDownLatch resolverStarted = new CountDownLatch(16);
            CountDownLatch releaseResolver = new CountDownLatch(1);
            UrlSafetyValidator.setDnsResolverForTests(host -> {
                resolverStarted.countDown();
                while (true) {
                    try {
                        if (releaseResolver.await(30, TimeUnit.SECONDS)) {
                            break;
                        }
                    } catch (InterruptedException ignored) {
                        // Ignore cancellation until the test releases the resolver threads.
                    }
                }
                return new InetAddress[]{InetAddress.getByName("93.184.216.34")};
            });

            ExecutorService executor = Executors.newFixedThreadPool(16);
            List<Future<?>> blockers = new ArrayList<>();
            try {
                for (int i = 0; i < 16; i++) {
                    int index = i;
                    blockers.add(executor.submit(() -> {
                        try {
                            UrlSafetyValidator.validateUrl("https://slow-dns-" + index + ".example/file.xml");
                        } catch (IllegalArgumentException ignored) {
                            // Expected: each blocking resolver call times out at the caller.
                        }
                    }));
                }

                assertTrue(resolverStarted.await(10, TimeUnit.SECONDS), "resolver pool should reach its configured cap");

                IllegalArgumentException ex = assertTimeoutPreemptively(Duration.ofSeconds(10), () ->
                    assertThrows(IllegalArgumentException.class,
                        () -> UrlSafetyValidator.validateUrl("https://capacity-dns.example/file.xml")));

                assertTrue(ex.getMessage().contains("DNS resolution capacity exceeded"));
            } finally {
                releaseResolver.countDown();
                for (Future<?> blocker : blockers) {
                    blocker.get(5, TimeUnit.SECONDS);
                }
                executor.shutdownNow();
            }
        }
    }

    // ── validateUrlFormat (no DNS resolution) ───────────────────────────

    @Nested
    @DisplayName("validateUrlFormat - format-only checks")
    class FormatOnly {

        @ParameterizedTest
        @ValueSource(strings = {
            "https://example.com",
            "https://api.example.com/v1",
            "http://my-custom-api.test/endpoint",
            "https://thishostdoesnotexist12345.invalid/path"
        })
        @DisplayName("should accept valid URL format even with unresolvable hostname")
        void shouldAcceptValidFormat(String url) {
            assertDoesNotThrow(() -> UrlSafetyValidator.validateUrlFormat(url));
        }

        @ParameterizedTest
        @ValueSource(strings = {
            "ftp://example.com",
            "file:///etc/passwd",
            "ssh://host"
        })
        @DisplayName("should reject non-HTTP schemes")
        void shouldRejectNonHttpSchemes(String url) {
            IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class, () -> UrlSafetyValidator.validateUrlFormat(url));
            assertTrue(ex.getMessage().contains("Only http and https"));
        }

        @ParameterizedTest
        @ValueSource(strings = {
            "http://localhost",
            "http://localhost:3000",
            "http://sub.localhost"
        })
        @DisplayName("should reject localhost")
        void shouldRejectLocalhost(String url) {
            assertThrows(IllegalArgumentException.class,
                () -> UrlSafetyValidator.validateUrlFormat(url));
        }

        @Test
        @DisplayName("should reject null")
        void shouldRejectNull() {
            assertThrows(IllegalArgumentException.class,
                () -> UrlSafetyValidator.validateUrlFormat(null));
        }

        @Test
        @DisplayName("should reject blank")
        void shouldRejectBlank() {
            assertThrows(IllegalArgumentException.class,
                () -> UrlSafetyValidator.validateUrlFormat("   "));
        }

        @Test
        @DisplayName("scheme-rejection message surfaces the received URL and scheme (R-07)")
        void schemeRejectionMessageContainsUrlAndScheme() {
            IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> UrlSafetyValidator.validateUrlFormat("ftp://example.com/x"));
            assertTrue(ex.getMessage().contains("ftp"),
                "error should include the offending scheme, got: " + ex.getMessage());
            assertTrue(ex.getMessage().contains("ftp://example.com/x"),
                "error should include the offending URL, got: " + ex.getMessage());
        }

        @Test
        @DisplayName("unparseable URL error message includes the raw input (R-07)")
        void unparseableUrlMessageContainsRawInput() {
            IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> UrlSafetyValidator.validateUrlFormat("not-a-url"));
            assertTrue(ex.getMessage().contains("not-a-url"),
                "error should include the raw input 'not-a-url', got: " + ex.getMessage());
        }
    }

    @Nested
    @DisplayName("Template placeholders - {identifier} tolerance")
    class TemplatePlaceholders {

        @ParameterizedTest
        @ValueSource(strings = {
            "https://{account}.api-us1.com/api/3",
            "https://{applicationId}.algolia.net",
            "https://monitoring.{region}.amazonaws.com",
            "https://{tenant}.{region}.example.com/v1"
        })
        @DisplayName("validateUrl should accept URLs with {placeholder} tokens in host")
        void validateUrlAcceptsPlaceholders(String url) {
            assertDoesNotThrow(() -> UrlSafetyValidator.validateUrl(url));
        }

        @ParameterizedTest
        @ValueSource(strings = {
            "https://{account}.api-us1.com/api/3",
            "https://{applicationId}.algolia.net",
            "https://monitoring.{region}.amazonaws.com"
        })
        @DisplayName("validateUrlFormat should accept URLs with {placeholder} tokens")
        void validateUrlFormatAcceptsPlaceholders(String url) {
            assertDoesNotThrow(() -> UrlSafetyValidator.validateUrlFormat(url));
        }

        @Test
        @DisplayName("placeholder-tolerance still rejects non-HTTP schemes")
        void placeholderStillRejectsBadScheme() {
            assertThrows(IllegalArgumentException.class,
                () -> UrlSafetyValidator.validateUrl("ftp://{host}.example.com/x"));
        }

        @Test
        @DisplayName("placeholder-tolerance still rejects localhost hosts")
        void placeholderStillRejectsLocalhost() {
            assertThrows(IllegalArgumentException.class,
                () -> UrlSafetyValidator.validateUrl("http://localhost/{path}"));
        }
    }
}
