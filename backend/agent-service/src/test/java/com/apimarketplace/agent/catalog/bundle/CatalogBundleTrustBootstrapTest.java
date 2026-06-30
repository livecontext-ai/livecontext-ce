package com.apimarketplace.agent.catalog.bundle;

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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link CatalogBundleTrustBootstrap} - the trust-on-first-use (TOFU)
 * bootstrap that lets a cloud-linked CE with an empty trust registry auto-pin the
 * cloud's published Ed25519 signing key.
 *
 * <p>Uses a REAL {@link TrustedKeyRegistry} (so the pin path, including base64 →
 * Ed25519 parse, is exercised end to end) and a real generated Ed25519 keypair;
 * only the HTTP transport ({@link RestTemplate}) is mocked.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("CatalogBundleTrustBootstrap - TOFU signing-key fetch + pin")
class CatalogBundleTrustBootstrapTest {

    private static final String CLOUD = "https://cloud.example";
    private static final String SIGNING_KEY_URL = CLOUD + "/api/catalog-bundles/signing-key";

    @Mock private RestTemplate restTemplate;

    /** Real, empty registry so pin() is genuinely exercised. */
    private TrustedKeyRegistry emptyRegistry() {
        return new TrustedKeyRegistry("");
    }

    private static String freshEd25519PublicKeyB64() throws Exception {
        KeyPair kp = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        return Base64.getEncoder().encodeToString(kp.getPublic().getEncoded());
    }

    @Test
    @DisplayName("Empty registry + cloud serves a valid key → fetches once, pins it, returns pinned")
    void emptyRegistryFetchesAndPins() throws Exception {
        TrustedKeyRegistry registry = emptyRegistry();
        String pubB64 = freshEd25519PublicKeyB64();
        when(restTemplate.getForObject(eq(SIGNING_KEY_URL), eq(CatalogBundleTrustBootstrap.SigningKeyResponse.class)))
                .thenReturn(new CatalogBundleTrustBootstrap.SigningKeyResponse(
                        "livecontext-prod-v1", "livecontext", pubB64, "Ed25519"));
        CatalogBundleTrustBootstrap boot = new CatalogBundleTrustBootstrap(restTemplate, registry, CLOUD);

        CatalogBundleTrustBootstrap.Result result = boot.bootstrapTrust();

        assertThat(result.pinned()).isTrue();
        assertThat(result.keyId()).isEqualTo("livecontext-prod-v1");
        // The key is now usable by the verifier under exactly the keyId the cloud advertised.
        assertThat(registry.hasKeys()).isTrue();
        assertThat(registry.find("livecontext-prod-v1")).isPresent();
        verify(restTemplate).getForObject(eq(SIGNING_KEY_URL), eq(CatalogBundleTrustBootstrap.SigningKeyResponse.class));
    }

    @Test
    @DisplayName("Already-pinned registry → NO fetch, returns skipped (TOFU never re-fetches/overwrites)")
    void alreadyPinnedDoesNotFetch() throws Exception {
        // Seed a registry that already has a key (simulates operator env or an earlier TOFU pin).
        String pubB64 = freshEd25519PublicKeyB64();
        TrustedKeyRegistry registry = new TrustedKeyRegistry("preexisting-v1=" + pubB64);
        CatalogBundleTrustBootstrap boot = new CatalogBundleTrustBootstrap(restTemplate, registry, CLOUD);

        CatalogBundleTrustBootstrap.Result result = boot.bootstrapTrust();

        assertThat(result.pinned()).isFalse();
        assertThat(result.detail()).contains("already configured");
        // Crucial: the signing-key endpoint is never called when a key is already pinned.
        verifyNoInteractions(restTemplate);
        // The pre-existing key is untouched.
        assertThat(registry.find("preexisting-v1")).isPresent();
    }

    @Test
    @DisplayName("Cloud URL empty → NO fetch, returns skipped")
    void cloudUrlEmptyDoesNotFetch() {
        CatalogBundleTrustBootstrap boot = new CatalogBundleTrustBootstrap(restTemplate, emptyRegistry(), "");

        CatalogBundleTrustBootstrap.Result result = boot.bootstrapTrust();

        assertThat(result.pinned()).isFalse();
        assertThat(result.detail()).contains("cloud-url is empty");
        verifyNoInteractions(restTemplate);
    }

    @Test
    @DisplayName("Cloud returns 503 (no signing key yet) → skipped with HTTP detail, nothing pinned")
    void httpErrorIsSkipped() {
        TrustedKeyRegistry registry = emptyRegistry();
        when(restTemplate.getForObject(eq(SIGNING_KEY_URL), eq(CatalogBundleTrustBootstrap.SigningKeyResponse.class)))
                .thenThrow(new HttpServerErrorException(HttpStatus.SERVICE_UNAVAILABLE));
        CatalogBundleTrustBootstrap boot = new CatalogBundleTrustBootstrap(restTemplate, registry, CLOUD);

        CatalogBundleTrustBootstrap.Result result = boot.bootstrapTrust();

        assertThat(result.pinned()).isFalse();
        assertThat(result.detail()).contains("503");
        assertThat(registry.hasKeys()).isFalse();
    }

    @Test
    @DisplayName("Network error (connection refused) → skipped, nothing pinned")
    void networkErrorIsSkipped() {
        TrustedKeyRegistry registry = emptyRegistry();
        when(restTemplate.getForObject(eq(SIGNING_KEY_URL), eq(CatalogBundleTrustBootstrap.SigningKeyResponse.class)))
                .thenThrow(new ResourceAccessException("Connection refused"));
        CatalogBundleTrustBootstrap boot = new CatalogBundleTrustBootstrap(restTemplate, registry, CLOUD);

        CatalogBundleTrustBootstrap.Result result = boot.bootstrapTrust();

        assertThat(result.pinned()).isFalse();
        assertThat(result.detail()).contains("Connection refused");
        assertThat(registry.hasKeys()).isFalse();
    }

    @Test
    @DisplayName("Response missing publicKeyBase64 → skipped, nothing pinned")
    void malformedResponseIsSkipped() {
        TrustedKeyRegistry registry = emptyRegistry();
        when(restTemplate.getForObject(eq(SIGNING_KEY_URL), eq(CatalogBundleTrustBootstrap.SigningKeyResponse.class)))
                .thenReturn(new CatalogBundleTrustBootstrap.SigningKeyResponse(
                        "k1", "livecontext", null, "Ed25519"));
        CatalogBundleTrustBootstrap boot = new CatalogBundleTrustBootstrap(restTemplate, registry, CLOUD);

        CatalogBundleTrustBootstrap.Result result = boot.bootstrapTrust();

        assertThat(result.pinned()).isFalse();
        assertThat(result.detail()).contains("missing keyId/publicKeyBase64");
        assertThat(registry.hasKeys()).isFalse();
    }

    @Test
    @DisplayName("Unparseable public key → skipped (pin rejects), nothing pinned")
    void unparseableKeyIsSkipped() {
        TrustedKeyRegistry registry = emptyRegistry();
        when(restTemplate.getForObject(eq(SIGNING_KEY_URL), eq(CatalogBundleTrustBootstrap.SigningKeyResponse.class)))
                .thenReturn(new CatalogBundleTrustBootstrap.SigningKeyResponse(
                        "k1", "livecontext", "not-a-valid-base64-x509-key!!!", "Ed25519"));
        CatalogBundleTrustBootstrap boot = new CatalogBundleTrustBootstrap(restTemplate, registry, CLOUD);

        CatalogBundleTrustBootstrap.Result result = boot.bootstrapTrust();

        assertThat(result.pinned()).isFalse();
        assertThat(result.detail()).contains("could not be pinned");
        assertThat(registry.hasKeys()).isFalse();
    }

    @Test
    @DisplayName("Trailing slash in cloud URL is normalised (no double slash in the signing-key path)")
    void normalisesTrailingSlash() throws Exception {
        TrustedKeyRegistry registry = emptyRegistry();
        String pubB64 = freshEd25519PublicKeyB64();
        when(restTemplate.getForObject(eq(SIGNING_KEY_URL), eq(CatalogBundleTrustBootstrap.SigningKeyResponse.class)))
                .thenReturn(new CatalogBundleTrustBootstrap.SigningKeyResponse("k1", "i", pubB64, "Ed25519"));
        CatalogBundleTrustBootstrap boot = new CatalogBundleTrustBootstrap(restTemplate, registry, CLOUD + "///");

        CatalogBundleTrustBootstrap.Result result = boot.bootstrapTrust();

        assertThat(result.pinned()).isTrue();
        verify(restTemplate).getForObject(eq(SIGNING_KEY_URL), eq(CatalogBundleTrustBootstrap.SigningKeyResponse.class));
        verify(restTemplate, never()).getForObject(eq(CLOUD + "////api/catalog-bundles/signing-key"),
                eq(CatalogBundleTrustBootstrap.SigningKeyResponse.class));
    }
}
