package com.apimarketplace.publication.service;

import com.apimarketplace.publication.repository.CeCloudLinkRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.client.RestTemplate;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * The CE side of the paid-only cloud link flow: the onboarding {@code startUrl} returned next to
 * {@code authUrl}, the pending-flow lifetime (long enough for a cloud signup + checkout), and the
 * typed callback-state failure the controller turns into a {@code cloud_link_error=expired} redirect.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("CloudLinkService - onboarding start URL, pending-flow TTL, callback state")
class CloudLinkServiceAuthFlowTest {

    @Mock private CeCloudLinkRepository cloudLinkRepository;
    @Mock private RestTemplate restTemplate;

    private static final Long TENANT_ID = 42L;
    private static final String CLIENT_ID = "livecontext frontend";
    private static final String REDIRECT_URI = "http://localhost:8080/api/cloud-link/callback";

    private final MutableClock clock = new MutableClock(Instant.parse("2026-09-25T10:00:00Z"));

    private CloudLinkService service(String cloudApiUrl, String webUrl, Duration ttl) {
        return new CloudLinkService(cloudLinkRepository,
                "https://auth.example.com/realms/livecontext",
                CLIENT_ID, REDIRECT_URI,
                "test-encryption-key-for-unit-tests",
                cloudApiUrl, "1.0.0-test", new ObjectMapper(),
                restTemplate, clock, webUrl, ttl);
    }

    private static Map<String, String> query(String url) {
        Map<String, String> params = new LinkedHashMap<>();
        for (String pair : URI.create(url).getRawQuery().split("&")) {
            int eq = pair.indexOf('=');
            params.put(pair.substring(0, eq),
                    URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
        }
        return params;
    }

    @Nested
    @DisplayName("startUrl")
    class StartUrl {

        @Test
        @DisplayName("startUrl opens the cloud onboarding with every OAuth value URL-encoded, next to an unchanged authUrl")
        void startUrlCarriesEncodedOAuthParameters() {
            Map<String, String> result = service("https://livecontext.ai/api", null, null)
                    .generateAuthUrl(TENANT_ID, "/en/ce-setup");

            String startUrl = result.get("startUrl");
            assertThat(startUrl).startsWith("https://livecontext.ai/onboarding?ce_link=1&");
            // Raw form: values are percent-encoded, never pasted verbatim.
            assertThat(startUrl)
                    .contains("client_id=livecontext+frontend")
                    .contains("redirect_uri=http%3A%2F%2Flocalhost%3A8080%2Fapi%2Fcloud-link%2Fcallback");

            Map<String, String> params = query(startUrl);
            assertThat(params).containsEntry("ce_link", "1")
                    .containsEntry("client_id", CLIENT_ID)
                    .containsEntry("redirect_uri", REDIRECT_URI)
                    .containsEntry("state", result.get("state"))
                    .containsEntry("code_challenge_method", "S256");
            // Same PKCE challenge as the direct Keycloak URL, so either entry completes the same flow.
            assertThat(params.get("code_challenge")).matches("[A-Za-z0-9_-]{43}")
                    .isEqualTo(result.get("authUrl").replaceAll(".*[?&]code_challenge=([^&]+).*", "$1"));
            assertThat(result.get("authUrl"))
                    .startsWith("https://auth.example.com/realms/livecontext/protocol/openid-connect/auth?");
        }

        @Test
        @DisplayName("An explicit cloud-link.web-url wins over the derived one, trailing slashes trimmed")
        void explicitWebUrlWins() {
            String startUrl = service("https://livecontext.ai/api", "https://staging.livecontext.ai//", null)
                    .generateAuthUrl(TENANT_ID, null).get("startUrl");

            assertThat(startUrl).startsWith("https://staging.livecontext.ai/onboarding?ce_link=1&");
        }

        @Test
        @DisplayName("A blank web-url falls back to the cloud API URL minus /api")
        void blankWebUrlIsDerived() {
            String startUrl = service("https://cloud.example.com/api/", "  ", null)
                    .generateAuthUrl(TENANT_ID, null).get("startUrl");

            assertThat(startUrl).startsWith("https://cloud.example.com/onboarding?ce_link=1&");
        }

        @ParameterizedTest(name = "{0} -> {1}")
        @DisplayName("deriveWebUrl strips one trailing /api and trailing slashes only")
        @CsvSource({
                "https://livecontext.ai/api, https://livecontext.ai",
                "https://livecontext.ai/api/, https://livecontext.ai",
                "https://livecontext.ai, https://livecontext.ai",
                "https://livecontext.ai/, https://livecontext.ai",
                "https://example.com/apis, https://example.com/apis",
                "https://example.com/v1/api, https://example.com/v1",
        })
        void deriveWebUrl(String apiUrl, String expected) {
            assertThat(CloudLinkService.deriveWebUrl(apiUrl)).isEqualTo(expected);
        }

        @Test
        @DisplayName("deriveWebUrl falls back to the public cloud when no API URL is configured")
        void deriveWebUrlBlank() {
            assertThat(CloudLinkService.deriveWebUrl(null)).isEqualTo("https://livecontext.ai");
            assertThat(CloudLinkService.deriveWebUrl(" ")).isEqualTo("https://livecontext.ai");
        }
    }

    @Nested
    @DisplayName("pending-flow TTL")
    class PendingFlowTtl {

        @Test
        @DisplayName("Default TTL is 2 hours: a callback 31 minutes in (signup + checkout) still completes")
        void defaultTtlSurvivesSignupAndCheckout() {
            CloudLinkService service = service("https://livecontext.ai/api", null, null);
            String state = service.generateAuthUrl(TENANT_ID, "/en/ce-setup").get("state");

            clock.advance(Duration.ofMinutes(31)); // expired under the old 30-minute TTL
            assertThat(service.receiveCallback("code-1", state)).isEqualTo("/en/ce-setup");
            assertThat(service.pendingAuthFlowTtl()).isEqualTo(Duration.ofHours(2));
        }

        @Test
        @DisplayName("Default TTL still expires: a callback after 2 hours is refused as an expired state")
        void defaultTtlExpiresAfterTwoHours() {
            CloudLinkService service = service("https://livecontext.ai/api", null, null);
            String state = service.generateAuthUrl(TENANT_ID, "/en/ce-setup").get("state");

            clock.advance(Duration.ofHours(2));

            assertThatThrownBy(() -> service.receiveCallback("code-1", state))
                    .isInstanceOf(CloudLinkService.CallbackStateException.class)
                    .hasMessageContaining("Invalid or expired state")
                    .extracting(e -> ((CloudLinkService.CallbackStateException) e).getFrontendReturnPath())
                    .isNull();
        }

        @Test
        @DisplayName("A configured TTL is honoured")
        void configuredTtlIsHonoured() {
            CloudLinkService service = service("https://livecontext.ai/api", null, Duration.ofMinutes(10));
            String state = service.generateAuthUrl(TENANT_ID, null).get("state");

            clock.advance(Duration.ofMinutes(11));

            assertThatThrownBy(() -> service.receiveCallback("code-1", state))
                    .isInstanceOf(CloudLinkService.CallbackStateException.class);
        }

        @Test
        @DisplayName("A zero or negative TTL falls back to the 2-hour default instead of expiring every flow")
        void nonPositiveTtlFallsBackToDefault() {
            assertThat(service("https://livecontext.ai/api", null, Duration.ZERO).pendingAuthFlowTtl())
                    .isEqualTo(Duration.ofHours(2));
            assertThat(service("https://livecontext.ai/api", null, Duration.ofMinutes(-5)).pendingAuthFlowTtl())
                    .isEqualTo(Duration.ofHours(2));
        }
    }

    @Nested
    @DisplayName("callback state failures")
    class CallbackState {

        @Test
        @DisplayName("Unknown state -> CallbackStateException with no return path (the flow is not known)")
        void unknownState() {
            CloudLinkService service = service("https://livecontext.ai/api", null, null);

            assertThatThrownBy(() -> service.receiveCallback("code-1", "never-issued"))
                    .isInstanceOf(CloudLinkService.CallbackStateException.class)
                    .extracting(e -> ((CloudLinkService.CallbackStateException) e).getFrontendReturnPath())
                    .isNull();
            verifyNoInteractions(restTemplate);
        }

        @Test
        @DisplayName("Replayed state -> CallbackStateException carrying the flow's allowlisted return path")
        void replayedStateCarriesReturnPath() {
            CloudLinkService service = service("https://livecontext.ai/api", null, null);
            String state = service.generateAuthUrl(TENANT_ID, "/fr/app/marketplace").get("state");
            service.receiveCallback("code-1", state);

            assertThatThrownBy(() -> service.receiveCallback("code-2", state))
                    .isInstanceOf(CloudLinkService.CallbackStateException.class)
                    .hasMessageContaining("already completed")
                    .extracting(e -> ((CloudLinkService.CallbackStateException) e).getFrontendReturnPath())
                    .isEqualTo("/fr/app/marketplace");
        }

        @Test
        @DisplayName("A blank code is a malformed request, not a state failure (plain IllegalArgumentException)")
        void blankCodeIsNotAStateFailure() {
            CloudLinkService service = service("https://livecontext.ai/api", null, null);

            assertThatThrownBy(() -> service.receiveCallback(" ", "some-state"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .isNotInstanceOf(CloudLinkService.CallbackStateException.class);
        }
    }

    private static final class MutableClock extends Clock {
        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
