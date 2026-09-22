package com.apimarketplace.storage.web;

import com.apimarketplace.common.storage.signing.ShowcaseUrlSigner;
import com.apimarketplace.common.web.TenantResolver;
import com.apimarketplace.storage.service.file.DownloadStream;
import com.apimarketplace.storage.service.file.FileStorageService;
import com.apimarketplace.storage.service.file.StorageStreamingMetrics;
import com.apimarketplace.storage.util.MimeTypeRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.ByteArrayInputStream;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("FileController.proxySignedDownload - HMAC-signed public proxy")
class FileControllerSignedTest {

    private static final String SECRET = "test-secret-32-bytes-long-enough-for-hmac";

    @Mock FileStorageService fileStorageService;
    @Mock MimeTypeRegistry mimeTypeRegistry;
    @Mock TenantResolver tenantResolver;

    private final StorageStreamingMetrics streamingMetrics = new StorageStreamingMetrics(null);
    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    private final ShowcaseUrlSigner signer = new ShowcaseUrlSigner(SECRET);

    private FileController controller;

    @BeforeEach
    void setUp() {
        controller = new FileController(fileStorageService, mimeTypeRegistry, tenantResolver,
                streamingMetrics, signer,
                new com.apimarketplace.common.storage.url.PublicFileUrlBuilder("https://livecontext.ai"),
                meterRegistry);
        lenient().when(mimeTypeRegistry.resolve(anyString())).thenReturn("image/png");
    }

    @Test
    @DisplayName("Valid signed URL → 200 with Cache-Control: private and Content-Type from MIME registry")
    void validSignedUrlStreams() {
        // 4h of link life left (the real default TTL of both minting sites), so the 900s
        // ceiling is what caps the freshness lifetime here, not the remaining life.
        long exp = Instant.now().getEpochSecond() + 4 * 3600;
        String key = "1/general/catalog-binary/abc.png";
        String sig = signer.sign(key, exp, "inline");

        DownloadStream ds = stubStream("png-bytes".getBytes(), 9);
        when(fileStorageService.openStream(key)).thenReturn(Optional.of(ds));

        ResponseEntity<StreamingResponseBody> response =
                controller.proxySignedDownload(key, exp, "inline", sig);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getFirst(HttpHeaders.CACHE_CONTROL))
                .isEqualTo("private, max-age=900");
        assertThat(response.getHeaders().getFirst(HttpHeaders.CONTENT_TYPE)).isEqualTo("image/png");
        assertThat(meterRegistry.counter("storage_signed_download_total", "status", "ok").count())
                .isEqualTo(1.0);
    }

    @Test
    @DisplayName("Near-expiry signed URL caches only for what is left of the link, not the flat 900s")
    void nearExpiryCachesOnlyForTheRemainingLinkLife() {
        // Regression: the response used to advertise max-age=900 whatever the link had left, so a
        // browser could re-serve a cached copy - without re-presenting the signature - for up to
        // 15 minutes AFTER exp. The freshness lifetime must never outlast the link that earned it.
        // Every boundary of the rule itself is pinned in SignedResponseCacheControlTest; what this
        // asserts is that the endpoint is WIRED to it rather than to a literal.
        long exp = Instant.now().getEpochSecond() + 60;
        String key = "1/general/catalog-binary/soon.png";
        String sig = signer.sign(key, exp, "inline");

        when(fileStorageService.openStream(key))
                .thenReturn(Optional.of(stubStream("png-bytes".getBytes(), 9)));

        ResponseEntity<StreamingResponseBody> response =
                controller.proxySignedDownload(key, exp, "inline", sig);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        // Range, not equality: a second may tick between the test's clock read and the controller's.
        String header = response.getHeaders().getFirst(HttpHeaders.CACHE_CONTROL);
        assertThat(header).startsWith("private, max-age=");
        assertThat(Long.parseLong(header.substring("private, max-age=".length())))
                .isBetween(55L, 60L);
    }

    @Test
    @DisplayName("Tampered signature → 403 with no body and invalid_sig metric - does NOT leak whether the key exists")
    void tamperedSignatureRejected() {
        long exp = Instant.now().getEpochSecond() + 900;
        String key = "1/x.png";
        String validSig = signer.sign(key, exp, "inline");
        // Flip the FIRST base64url char so the decoded HMAC bytes are guaranteed to differ.
        // (Flipping the LAST char was flaky and caused the prior @Disabled: the signature is
        // base64url-no-padding of a 32-byte HMAC, whose final char carries only 4 significant
        // bits + 2 ignored padding bits. ~1/16 of runs the final char is 'A' and the flip lands
        // in those ignored bits, so verify() - which decodes to bytes and compares with
        // MessageDigest.isEqual - saw identical bytes and returned 404 instead of 403. It was
        // time-dependent (exp changes the HMAC), not cross-test pollution. The first char always
        // carries 6 significant bits of byte 0, so flipping it always invalidates the signature.)
        char first = validSig.charAt(0);
        String tampered = (first == 'A' ? 'B' : 'A') + validSig.substring(1);

        ResponseEntity<StreamingResponseBody> response =
                controller.proxySignedDownload(key, exp, "inline", tampered);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody()).isNull();
        assertThat(meterRegistry.counter("storage_signed_download_total", "status", "invalid_sig").count())
                .isEqualTo(1.0);
    }

    @Test
    @DisplayName("Expired URL → 403 with expired metric (separate counter from invalid_sig so ops can distinguish)")
    void expiredUrlRejected() {
        long exp = Instant.now().getEpochSecond() - 1;
        String key = "1/x.png";
        String sig = signer.sign(key, exp, "inline");

        ResponseEntity<StreamingResponseBody> response =
                controller.proxySignedDownload(key, exp, "inline", sig);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(meterRegistry.counter("storage_signed_download_total", "status", "expired").count())
                .isEqualTo(1.0);
    }

    @Test
    @DisplayName("Disposition flipped from inline to attachment after signing → 403 (anti-flip guard)")
    void dispositionFlipRejected() {
        long exp = Instant.now().getEpochSecond() + 900;
        String key = "1/x.png";
        String sigForInline = signer.sign(key, exp, "inline");

        ResponseEntity<StreamingResponseBody> response =
                controller.proxySignedDownload(key, exp, "attachment", sigForInline);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("Cross-tenant key replayed with another publisher's signature → 403 (signature is bound to the key)")
    void crossTenantKeyReplayRejected() {
        long exp = Instant.now().getEpochSecond() + 900;
        String legitKey = "1/legitimate.png";
        String sig = signer.sign(legitKey, exp, "inline");

        // Replay the signature against a different key - must fail.
        ResponseEntity<StreamingResponseBody> response =
                controller.proxySignedDownload("99/foreign.png", exp, "inline", sig);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("Valid signature but key not in storage → 404 (only after signature passes - no probing leak)")
    void unknownKeyAfterValidSignature() {
        long exp = Instant.now().getEpochSecond() + 900;
        String key = "1/missing.png";
        String sig = signer.sign(key, exp, "inline");

        lenient().when(fileStorageService.openStream(key)).thenReturn(Optional.empty());

        ResponseEntity<StreamingResponseBody> response =
                controller.proxySignedDownload(key, exp, "inline", sig);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    private static DownloadStream stubStream(byte[] data, long contentLength) {
        return new DownloadStream(new ByteArrayInputStream(data), contentLength, "image/png");
    }
}
