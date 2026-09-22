package com.apimarketplace.auth.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins auth-service's OWN configuration: every endpoint under {@code /api/internal/credentials/}
 * requires the gateway HMAC signature.
 *
 * <p>Why this test and not a filter test. {@code GatewayAuthenticationFilter} already proves that
 * prefix matching works, but it does so on a properties object the test itself constructs, so it
 * stays green no matter what this service actually configures. The thing that can regress is the
 * YAML: the list used to name six individual credential paths, which left the lookup resolvers
 * ({@code /all}, {@code /default}, {@code /{id}}, {@code /scopes}) ungated even though they return
 * DECRYPTED credentials. Narrowing back to a hand-maintained list, or dropping the entry while
 * reordering the file, would restore that hole with nothing failing.
 *
 * <p>The PREFIX form is the assertion, not the individual paths: prefix matching is what makes a
 * NEW endpoint added under it gated by default, rather than gated the day someone remembers to
 * extend a list.
 */
class InternalCredentialsHmacPinTest {

    private static final String CREDENTIALS_PREFIX = "/api/internal/credentials/";

    @Test
    @DisplayName("the whole /api/internal/credentials/ prefix is HMAC-required, as a prefix")
    void credentialsPrefixIsHmacRequired() throws Exception {
        List<String> hmacRequired = hmacRequiredPaths();

        assertThat(hmacRequired)
                .as("auth-service must gate every credential lookup, not a list of remembered ones")
                .contains(CREDENTIALS_PREFIX);
    }

    @Test
    @DisplayName("/api/internal/ is public, so the prefix above is what protects the credential API")
    void theCredentialPrefixIsTheOnlyThingStandingThere() throws Exception {
        Map<String, Object> filter = gatewayFilter();
        @SuppressWarnings("unchecked")
        List<String> publicPaths = (List<String>) filter.get("public-paths");

        // If /api/internal/ ever stops being public, this pin is no longer load-bearing and the
        // reader should know that rather than assume it still is.
        assertThat(publicPaths)
                .as("the credential prefix only matters because the internal namespace is public")
                .anyMatch(p -> p != null && p.startsWith("/api/internal"));
    }

    @Test
    @DisplayName("every credential path the widening replaced is covered by the prefix")
    void theReplacedPathsAreStillCovered() throws Exception {
        List<String> hmacRequired = hmacRequiredPaths();

        // The six that used to be listed one by one, plus the four resolvers that were NOT and
        // are the reason the widening happened. All ten must match by prefix.
        for (String path : List.of(
                "/api/internal/credentials/access-token",
                "/api/internal/credentials/data-map",
                "/api/internal/credentials/force-refresh-token",
                "/api/internal/credentials/refresh-token",
                "/api/internal/credentials/by-integration",
                "/api/internal/credentials/platform",
                "/api/internal/credentials/all",
                "/api/internal/credentials/default",
                "/api/internal/credentials/scopes",
                "/api/internal/credentials/pricing-versions/bootstrap")) {
            assertThat(hmacRequired)
                    .as("%s must be gated", path)
                    .anyMatch(prefix -> prefix != null && path.startsWith(prefix));
        }
    }

    @SuppressWarnings("unchecked")
    private static List<String> hmacRequiredPaths() throws Exception {
        Object value = gatewayFilter().get("hmac-required-paths");
        assertThat(value).as("gateway.filter.hmac-required-paths").isInstanceOf(List.class);
        return (List<String>) value;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> gatewayFilter() throws Exception {
        Map<String, Object> cfg = loadClasspathYaml("application.yml");
        Map<String, Object> gateway = (Map<String, Object>) cfg.get("gateway");
        assertThat(gateway).as("gateway block in auth-service application.yml").isNotNull();
        Map<String, Object> filter = (Map<String, Object>) gateway.get("filter");
        assertThat(filter).as("gateway.filter block").isNotNull();
        return filter;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> loadClasspathYaml(String resource) throws Exception {
        Map<String, Object> merged = new LinkedHashMap<>();
        try (InputStream in = InternalCredentialsHmacPinTest.class.getClassLoader()
                .getResourceAsStream(resource)) {
            assertThat(in).as("classpath resource %s", resource).isNotNull();
            for (Object doc : new Yaml().loadAll(in)) {
                if (doc instanceof Map) {
                    merged.putAll((Map<String, Object>) doc);
                }
            }
        }
        return merged;
    }
}
