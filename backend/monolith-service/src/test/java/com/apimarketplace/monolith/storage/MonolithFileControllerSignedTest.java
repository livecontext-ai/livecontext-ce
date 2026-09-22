package com.apimarketplace.monolith.storage;

import com.apimarketplace.auth.client.access.OrgAccessGuard;
import com.apimarketplace.common.storage.service.StorageService;
import com.apimarketplace.common.storage.signing.ShowcaseUrlSigner;
import com.apimarketplace.common.storage.url.PublicFileUrlBuilder;
import com.apimarketplace.storage.service.file.DownloadStream;
import com.apimarketplace.storage.service.file.FileStorageService;
import com.apimarketplace.storage.util.MimeTypeRegistry;
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
import java.io.ByteArrayOutputStream;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * CE mount of the HMAC-signed public file proxy (added with the public_link node).
 * Mirrors the cloud {@code FileControllerSignedTest}: the signature IS the authorization,
 * so the security-relevant branches are valid-sig streams / forged-sig 403 / expired 403 /
 * blank-secret 403 (the CE default) / missing file 404 only AFTER a valid signature.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("MonolithFileController.proxySignedDownload - CE HMAC-signed public proxy")
class MonolithFileControllerSignedTest {

    private static final String SECRET = "test-secret-32-bytes-long-enough-for-hmac";
    private static final String KEY = "tenant-1/wf/run/node/clip.mp4";

    @Mock FileStorageService fileStorageService;
    @Mock PublicFileUrlBuilder publicFileUrlBuilder;
    @Mock StorageService storageService;
    @Mock OrgAccessGuard orgAccessGuard;
    @Mock MimeTypeRegistry mimeTypeRegistry;

    private final ShowcaseUrlSigner signer = new ShowcaseUrlSigner(SECRET);

    private MonolithFileController controller;

    @BeforeEach
    void setUp() {
        controller = new MonolithFileController(fileStorageService, publicFileUrlBuilder,
                storageService, orgAccessGuard, signer, mimeTypeRegistry,
                new com.apimarketplace.storage.service.file.StorageStreamingMetrics(
                        new io.micrometer.core.instrument.simple.SimpleMeterRegistry()));
        lenient().when(mimeTypeRegistry.resolve(anyString())).thenReturn("video/mp4");
    }

    @Test
    @DisplayName("Valid signed URL streams the bytes with Cache-Control: private and the resolved Content-Type")
    void validSignedUrlStreams() throws Exception {
        // 4h of link life left (the real default TTL of both minting sites), so the 900s
        // ceiling is what caps the freshness lifetime here, not the remaining life.
        long exp = Instant.now().getEpochSecond() + 4 * 3600;
        String sig = signer.sign(KEY, exp, "inline");
        byte[] bytes = "mp4-bytes".getBytes();
        when(fileStorageService.openStream(KEY))
                .thenReturn(Optional.of(new DownloadStream(new ByteArrayInputStream(bytes), bytes.length, "video/mp4")));

        ResponseEntity<StreamingResponseBody> response =
                controller.proxySignedDownload(KEY, exp, "inline", sig);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getFirst(HttpHeaders.CACHE_CONTROL)).isEqualTo("private, max-age=900");
        assertThat(response.getHeaders().getFirst(HttpHeaders.CONTENT_TYPE)).isEqualTo("video/mp4");
        assertThat(response.getHeaders().getContentLength()).isEqualTo(bytes.length);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        response.getBody().writeTo(out);
        assertThat(out.toByteArray()).isEqualTo(bytes);
    }

    @Test
    @DisplayName("Near-expiry signed URL caches only for what is left of the link, not the flat 900s")
    void nearExpiryCachesOnlyForTheRemainingLinkLife() {
        // Regression, mirrored from the cloud mount: the response used to advertise max-age=900
        // whatever the link had left, so a browser could re-serve a cached copy - without
        // re-presenting the signature - for up to 15 minutes AFTER exp. CE mints public links
        // too (that is why this route exists here), so the same cap must hold.
        long exp = Instant.now().getEpochSecond() + 60;
        String sig = signer.sign(KEY, exp, "inline");
        byte[] bytes = "mp4-bytes".getBytes();
        when(fileStorageService.openStream(KEY))
                .thenReturn(Optional.of(new DownloadStream(new ByteArrayInputStream(bytes), bytes.length, "video/mp4")));

        ResponseEntity<StreamingResponseBody> response =
                controller.proxySignedDownload(KEY, exp, "inline", sig);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        String header = response.getHeaders().getFirst(HttpHeaders.CACHE_CONTROL);
        assertThat(header).startsWith("private, max-age=");
        // Range, not equality: a second may tick between the test's clock read and the controller's.
        assertThat(Long.parseLong(header.substring("private, max-age=".length())))
                .isBetween(55L, 60L);
    }

    @Test
    @DisplayName("Forged signature is 403 without touching storage (does not leak whether the key exists)")
    void forgedSignatureRejected() {
        long exp = Instant.now().getEpochSecond() + 900;
        String validSig = signer.sign(KEY, exp, "inline");
        // Flip the FIRST base64url char: it always carries 6 significant bits of HMAC byte 0,
        // so the flip is guaranteed to invalidate (the LAST char has ignored padding bits).
        char first = validSig.charAt(0);
        String forged = (first == 'A' ? 'B' : 'A') + validSig.substring(1);

        ResponseEntity<StreamingResponseBody> response =
                controller.proxySignedDownload(KEY, exp, "inline", forged);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody()).isNull();
    }

    @Test
    @DisplayName("Expired URL is 403 even with an otherwise-correct signature")
    void expiredUrlRejected() {
        long exp = Instant.now().getEpochSecond() - 1;
        String sig = signer.sign(KEY, exp, "inline");

        ResponseEntity<StreamingResponseBody> response =
                controller.proxySignedDownload(KEY, exp, "inline", sig);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("Blank secret (the CE default) verifies nothing: every request is 403")
    void blankSecretRejectsEverything() {
        MonolithFileController disabled = new MonolithFileController(fileStorageService,
                publicFileUrlBuilder, storageService, orgAccessGuard,
                new ShowcaseUrlSigner(""), mimeTypeRegistry,
                new com.apimarketplace.storage.service.file.StorageStreamingMetrics(
                        new io.micrometer.core.instrument.simple.SimpleMeterRegistry()));
        long exp = Instant.now().getEpochSecond() + 900;
        String sig = signer.sign(KEY, exp, "inline");

        ResponseEntity<StreamingResponseBody> response =
                disabled.proxySignedDownload(KEY, exp, "inline", sig);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("Valid signature for a key that no longer exists is 404 (existence checked only after the signature)")
    void missingFileAfterValidSignatureIs404() {
        long exp = Instant.now().getEpochSecond() + 900;
        String sig = signer.sign(KEY, exp, "inline");
        when(fileStorageService.openStream(KEY)).thenReturn(Optional.empty());

        ResponseEntity<StreamingResponseBody> response =
                controller.proxySignedDownload(KEY, exp, "inline", sig);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }
}
