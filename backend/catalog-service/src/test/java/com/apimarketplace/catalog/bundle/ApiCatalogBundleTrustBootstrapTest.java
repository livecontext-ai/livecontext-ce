package com.apimarketplace.catalog.bundle;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ApiCatalogBundleTrustBootstrap} - the trust-on-first-use
 * (TOFU) bootstrap that lets a CE pointed at a cloud, with an empty trust registry,
 * auto-pin the cloud's published Ed25519 signing key.
 *
 * <p>Uses a REAL {@link ApiCatalogTrustedKeyRegistry} (so the pin path, including
 * base64 → Ed25519 parse, is exercised end to end) and a real generated Ed25519
 * keypair; only the HTTP transport ({@link RestTemplate}) is mocked.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ApiCatalogBundleTrustBootstrap - TOFU signing-key fetch + pin")
class ApiCatalogBundleTrustBootstrapTest {

    private static final String CLOUD = "https://cloud.example";
    private static final String SIGNING_KEY_URL = CLOUD + "/api/catalog/public/bundles/signing-key";

    @Mock private RestTemplate restTemplate;

    private ApiCatalogTrustedKeyRegistry emptyRegistry() {
        return new ApiCatalogTrustedKeyRegistry("");
    }

    private static String freshEd25519PublicKeyB64() throws Exception {
        KeyPair kp = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        return Base64.getEncoder().encodeToString(kp.getPublic().getEncoded());
    }

    @Test
    @DisplayName("Empty registry + cloud serves a valid key → fetches once, pins it, returns pinned")
    void emptyRegistryFetchesAndPins() throws Exception {
        ApiCatalogTrustedKeyRegistry registry = emptyRegistry();
        String pubB64 = freshEd25519PublicKeyB64();
        when(restTemplate.getForObject(eq(SIGNING_KEY_URL), eq(ApiCatalogBundleTrustBootstrap.SigningKeyResponse.class)))
                .thenReturn(new ApiCatalogBundleTrustBootstrap.SigningKeyResponse(
                        "livecontext-prod-v1", "livecontext", pubB64, "Ed25519"));
        ApiCatalogBundleTrustBootstrap boot = new ApiCatalogBundleTrustBootstrap(restTemplate, registry, CLOUD);

        ApiCatalogBundleTrustBootstrap.Result result = boot.bootstrapTrust();

        assertThat(result.pinned()).isTrue();
        assertThat(result.keyId()).isEqualTo("livecontext-prod-v1");
        assertThat(registry.hasKeys()).isTrue();
        assertThat(registry.find("livecontext-prod-v1")).isPresent();
        verify(restTemplate).getForObject(eq(SIGNING_KEY_URL), eq(ApiCatalogBundleTrustBootstrap.SigningKeyResponse.class));
    }

    @Test
    @DisplayName("Already-pinned registry → NO fetch, returns skipped (TOFU never re-fetches/overwrites)")
    void alreadyPinnedDoesNotFetch() throws Exception {
        String pubB64 = freshEd25519PublicKeyB64();
        ApiCatalogTrustedKeyRegistry registry = new ApiCatalogTrustedKeyRegistry("preexisting-v1=" + pubB64);
        ApiCatalogBundleTrustBootstrap boot = new ApiCatalogBundleTrustBootstrap(restTemplate, registry, CLOUD);

        ApiCatalogBundleTrustBootstrap.Result result = boot.bootstrapTrust();

        assertThat(result.pinned()).isFalse();
        assertThat(result.detail()).contains("already configured");
        verifyNoInteractions(restTemplate);
        assertThat(registry.find("preexisting-v1")).isPresent();
    }

    @Test
    @DisplayName("Cloud URL empty → NO fetch, returns skipped")
    void cloudUrlEmptyDoesNotFetch() {
        ApiCatalogBundleTrustBootstrap boot = new ApiCatalogBundleTrustBootstrap(restTemplate, emptyRegistry(), "");

        ApiCatalogBundleTrustBootstrap.Result result = boot.bootstrapTrust();

        assertThat(result.pinned()).isFalse();
        assertThat(result.detail()).contains("cloud-url is empty");
        verifyNoInteractions(restTemplate);
    }

    @Test
    @DisplayName("Cloud returns 503 (no signing key yet) → skipped with HTTP detail, nothing pinned")
    void httpErrorIsSkipped() {
        ApiCatalogTrustedKeyRegistry registry = emptyRegistry();
        when(restTemplate.getForObject(eq(SIGNING_KEY_URL), eq(ApiCatalogBundleTrustBootstrap.SigningKeyResponse.class)))
                .thenThrow(new HttpServerErrorException(HttpStatus.SERVICE_UNAVAILABLE));
        ApiCatalogBundleTrustBootstrap boot = new ApiCatalogBundleTrustBootstrap(restTemplate, registry, CLOUD);

        ApiCatalogBundleTrustBootstrap.Result result = boot.bootstrapTrust();

        assertThat(result.pinned()).isFalse();
        assertThat(result.detail()).contains("503");
        assertThat(registry.hasKeys()).isFalse();
    }

    @Test
    @DisplayName("Network error (connection refused) → skipped, nothing pinned")
    void networkErrorIsSkipped() {
        ApiCatalogTrustedKeyRegistry registry = emptyRegistry();
        when(restTemplate.getForObject(eq(SIGNING_KEY_URL), eq(ApiCatalogBundleTrustBootstrap.SigningKeyResponse.class)))
                .thenThrow(new ResourceAccessException("Connection refused"));
        ApiCatalogBundleTrustBootstrap boot = new ApiCatalogBundleTrustBootstrap(restTemplate, registry, CLOUD);

        ApiCatalogBundleTrustBootstrap.Result result = boot.bootstrapTrust();

        assertThat(result.pinned()).isFalse();
        assertThat(result.detail()).contains("Connection refused");
        assertThat(registry.hasKeys()).isFalse();
    }

    @Test
    @DisplayName("Response missing publicKeyBase64 → skipped, nothing pinned")
    void malformedResponseIsSkipped() {
        ApiCatalogTrustedKeyRegistry registry = emptyRegistry();
        when(restTemplate.getForObject(eq(SIGNING_KEY_URL), eq(ApiCatalogBundleTrustBootstrap.SigningKeyResponse.class)))
                .thenReturn(new ApiCatalogBundleTrustBootstrap.SigningKeyResponse(
                        "k1", "livecontext", null, "Ed25519"));
        ApiCatalogBundleTrustBootstrap boot = new ApiCatalogBundleTrustBootstrap(restTemplate, registry, CLOUD);

        ApiCatalogBundleTrustBootstrap.Result result = boot.bootstrapTrust();

        assertThat(result.pinned()).isFalse();
        assertThat(result.detail()).contains("missing keyId/publicKeyBase64");
        assertThat(registry.hasKeys()).isFalse();
    }

    @Test
    @DisplayName("Unparseable public key → skipped (pin rejects), nothing pinned")
    void unparseableKeyIsSkipped() {
        ApiCatalogTrustedKeyRegistry registry = emptyRegistry();
        when(restTemplate.getForObject(eq(SIGNING_KEY_URL), eq(ApiCatalogBundleTrustBootstrap.SigningKeyResponse.class)))
                .thenReturn(new ApiCatalogBundleTrustBootstrap.SigningKeyResponse(
                        "k1", "livecontext", "not-a-valid-base64-x509-key!!!", "Ed25519"));
        ApiCatalogBundleTrustBootstrap boot = new ApiCatalogBundleTrustBootstrap(restTemplate, registry, CLOUD);

        ApiCatalogBundleTrustBootstrap.Result result = boot.bootstrapTrust();

        assertThat(result.pinned()).isFalse();
        assertThat(result.detail()).contains("could not be pinned");
        assertThat(registry.hasKeys()).isFalse();
    }
}
