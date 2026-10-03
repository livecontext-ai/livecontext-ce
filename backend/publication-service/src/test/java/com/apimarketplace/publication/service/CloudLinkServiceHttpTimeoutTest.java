package com.apimarketplace.publication.service;

import com.apimarketplace.publication.repository.CeCloudLinkRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * The cloud client the production constructor builds has explicit connect and read timeouts.
 * The Keycloak token refresh runs while the per-tenant refresh lock is held, so a client with no
 * timeout (the previous {@code new RestTemplate()}) let one stalled Keycloak connection block every
 * other cloud call of that tenant behind the lock indefinitely.
 */
@DisplayName("CloudLinkService cloud HTTP client timeouts")
class CloudLinkServiceHttpTimeoutTest {

    private static int intField(Object target, String name) {
        return ((Number) ReflectionTestUtils.getField(target, name)).intValue();
    }

    @Test
    @DisplayName("regression: the production constructor's RestTemplate has a 5 s connect and a 15 s read timeout")
    void productionClientHasTimeouts() {
        CloudLinkService service = new CloudLinkService(mock(CeCloudLinkRepository.class),
                "https://keycloak.example.com/realms/test", "client", "http://localhost/cb",
                "test-encryption-key-for-unit-tests", "https://livecontext.ai/api", "1.0.0",
                new ObjectMapper(), (String) null, (java.time.Duration) null);

        RestTemplate client = (RestTemplate) ReflectionTestUtils.getField(service, "restTemplate");
        ClientHttpRequestFactory factory = client.getRequestFactory();

        // HttpsURLConnection-based, so a CE install's custom trust store still applies.
        assertThat(factory).isInstanceOf(SimpleClientHttpRequestFactory.class);
        assertThat(intField(factory, "connectTimeout")).isEqualTo(5_000);
        assertThat(intField(factory, "readTimeout")).isEqualTo(15_000);
    }

    @Test
    @DisplayName("the timeouts are bounded and positive (a 0 would mean wait forever)")
    void timeoutsArePositive() {
        assertThat(CloudLinkService.CLOUD_CONNECT_TIMEOUT_MS).isPositive();
        assertThat(CloudLinkService.CLOUD_READ_TIMEOUT_MS).isPositive();
    }
}
