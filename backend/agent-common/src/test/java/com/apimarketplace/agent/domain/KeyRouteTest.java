package com.apimarketplace.agent.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The parent-to-child pin channel: a pin is per (tenant, provider), so what a child inherits
 * depends on whether it runs on the same provider as its parent.
 */
@DisplayName("KeyRoute - credentials channel and inheritance")
class KeyRouteTest {

    private static Map<String, Object> stamped(KeyRoute route, String provider) {
        Map<String, Object> creds = new HashMap<>();
        KeyRoute.stamp(creds, route, provider);
        return creds;
    }

    @Test
    @DisplayName("fromCredentials reads the enum or its name, and answers null for absent, blank or garbage")
    void fromCredentialsShapes() {
        assertThat(KeyRoute.fromCredentials(Map.of(KeyRoute.CREDENTIAL_KEY, KeyRoute.OWN_KEY))).isEqualTo(KeyRoute.OWN_KEY);
        assertThat(KeyRoute.fromCredentials(Map.of(KeyRoute.CREDENTIAL_KEY, "PLATFORM"))).isEqualTo(KeyRoute.PLATFORM);
        assertThat(KeyRoute.fromCredentials(Map.of(KeyRoute.CREDENTIAL_KEY, " own_key "))).isNull();
        assertThat(KeyRoute.fromCredentials(Map.of(KeyRoute.CREDENTIAL_KEY, ""))).isNull();
        assertThat(KeyRoute.fromCredentials(Map.of(KeyRoute.CREDENTIAL_KEY, 42))).isNull();
        assertThat(KeyRoute.fromCredentials(Map.of())).isNull();
        assertThat(KeyRoute.fromCredentials(null)).isNull();
    }

    @Test
    @DisplayName("stamp writes both keys, and a null route clears both")
    void stampWritesAndClears() {
        Map<String, Object> creds = stamped(KeyRoute.OWN_KEY, "openai");
        assertThat(creds).containsEntry(KeyRoute.CREDENTIAL_KEY, "OWN_KEY")
                .containsEntry(KeyRoute.PROVIDER_CREDENTIAL_KEY, "openai");

        KeyRoute.stamp(creds, null, "openai");
        assertThat(creds).doesNotContainKeys(KeyRoute.CREDENTIAL_KEY, KeyRoute.PROVIDER_CREDENTIAL_KEY);

        KeyRoute.stamp(creds, KeyRoute.PLATFORM, " ");
        assertThat(creds).containsEntry(KeyRoute.CREDENTIAL_KEY, "PLATFORM")
                .doesNotContainKey(KeyRoute.PROVIDER_CREDENTIAL_KEY);
        KeyRoute.stamp(null, KeyRoute.PLATFORM, "openai");   // never throws
    }

    @Test
    @DisplayName("same provider: the child inherits the parent's pin verbatim")
    void sameProviderInheritsVerbatim() {
        assertThat(KeyRoute.inheritFor(stamped(KeyRoute.OWN_KEY, "openai"), "openai")).isEqualTo(KeyRoute.OWN_KEY);
        assertThat(KeyRoute.inheritFor(stamped(KeyRoute.OWN_KEY, "OpenAI"), "openai ")).isEqualTo(KeyRoute.OWN_KEY);
        assertThat(KeyRoute.inheritFor(stamped(KeyRoute.PLATFORM, "openai"), "openai")).isEqualTo(KeyRoute.PLATFORM);
    }

    @Test
    @DisplayName("different provider: the child is unpinned whatever the parent's pin, because a pin is per (tenant, provider)")
    void differentProviderNeverCarries() {
        // OWN_KEY: a parent on its own OpenAI key spawning an Anthropic child used to hand it
        // OWN_KEY, which then failed closed for a key the user never had.
        assertThat(KeyRoute.inheritFor(stamped(KeyRoute.OWN_KEY, "openai"), "anthropic")).isNull();
        // PLATFORM: "no usable OpenAI key" says nothing about the tenant's Anthropic key; carrying
        // it would run the child on the platform key (billed credits) past a key the user has.
        assertThat(KeyRoute.inheritFor(stamped(KeyRoute.PLATFORM, "openai"), "anthropic")).isNull();
    }

    @Test
    @DisplayName("no provider stamped (older parent) or no child provider: the pin is inherited verbatim")
    void missingProviderInheritsVerbatim() {
        Map<String, Object> routeOnly = new HashMap<>(Map.of(KeyRoute.CREDENTIAL_KEY, "OWN_KEY"));
        assertThat(KeyRoute.inheritFor(routeOnly, "anthropic")).isEqualTo(KeyRoute.OWN_KEY);
        assertThat(KeyRoute.inheritFor(stamped(KeyRoute.OWN_KEY, "openai"), null)).isEqualTo(KeyRoute.OWN_KEY);
        assertThat(KeyRoute.inheritFor(Map.of(), "anthropic")).isNull();
        assertThat(KeyRoute.inheritFor(null, "anthropic")).isNull();
    }
}
