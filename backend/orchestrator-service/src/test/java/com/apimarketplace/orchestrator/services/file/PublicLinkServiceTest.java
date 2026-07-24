package com.apimarketplace.orchestrator.services.file;

import com.apimarketplace.common.storage.signing.ShowcaseUrlSigner;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link PublicLinkService}: URL shape, TTL clamping and the
 * disabled-installation short-circuits. Uses a REAL ShowcaseUrlSigner (its secret is
 * constructor-injected) so the minted signature is the exact HMAC storage-service verifies.
 */
@DisplayName("PublicLinkService")
class PublicLinkServiceTest {

    private static final String SECRET = "test-secret-0123456789";
    private static final String BASE = "https://livecontext.example";
    private static final String KEY = "tenant-1/wf/run/node/clip.mp4";

    private PublicLinkService enabledService() {
        return new PublicLinkService(new ShowcaseUrlSigner(SECRET), BASE + "/");
    }

    @Test
    @DisplayName("mint produces an absolute /api/files/proxy-signed URL carrying key, exp, disposition and a signature the signer verifies")
    void mintProducesVerifiableSignedUrl() {
        PublicLinkService service = enabledService();

        PublicLinkService.PublicLink link = service.mint(KEY, 60, "inline");

        assertNotNull(link);
        assertTrue(link.url().startsWith(BASE + "/api/files/proxy-signed?key="),
            "URL must be absolute (external crawlers cannot resolve a relative path), got: " + link.url());
        assertTrue(link.url().contains("&exp=" + link.expiresAt().getEpochSecond()));
        assertTrue(link.url().contains("&disposition=inline"));
        assertTrue(link.url().contains("&sig="));

        // The embedded signature must be the exact HMAC the storage-service verifier accepts.
        ShowcaseUrlSigner verifier = new ShowcaseUrlSigner(SECRET);
        String sig = link.url().replaceAll(".*&sig=", "");
        sig = java.net.URLDecoder.decode(sig, java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(verifier.verify(KEY, link.expiresAt().getEpochSecond(), "inline", sig, Instant.now().getEpochSecond()),
            "minted signature must round-trip through the shared verifier");
    }

    @Test
    @DisplayName("expiry honours the requested TTL")
    void expiryHonoursTtl() {
        PublicLinkService service = enabledService();
        long before = Instant.now().plusSeconds(30 * 60L).getEpochSecond();
        PublicLinkService.PublicLink link = service.mint(KEY, 30, "inline");
        long after = Instant.now().plusSeconds(30 * 60L).getEpochSecond();
        assertTrue(link.expiresAt().getEpochSecond() >= before && link.expiresAt().getEpochSecond() <= after + 1);
    }

    @Test
    @DisplayName("unknown disposition falls back to inline; attachment kept")
    void dispositionNormalised() {
        PublicLinkService service = enabledService();
        assertTrue(service.mint(KEY, 10, "weird").url().contains("&disposition=inline"));
        assertTrue(service.mint(KEY, 10, "ATTACHMENT").url().contains("&disposition=attachment"));
    }

    @Test
    @DisplayName("clampTtlMinutes: null/non-positive -> default 240; bounds 5-10080 enforced")
    void ttlClamped() {
        assertEquals(240, PublicLinkService.clampTtlMinutes(null));
        assertEquals(240, PublicLinkService.clampTtlMinutes(0));
        assertEquals(5, PublicLinkService.clampTtlMinutes(1));
        assertEquals(10_080, PublicLinkService.clampTtlMinutes(999_999));
        assertEquals(60, PublicLinkService.clampTtlMinutes(60));
    }

    @Test
    @DisplayName("disabled installation (blank secret or blank public URL) -> isEnabled false, mint null")
    void disabledInstallationsShortCircuit() {
        PublicLinkService noSecret = new PublicLinkService(new ShowcaseUrlSigner(""), BASE);
        assertFalse(noSecret.isEnabled());
        assertNull(noSecret.mint(KEY, 60, "inline"));

        PublicLinkService noBase = new PublicLinkService(new ShowcaseUrlSigner(SECRET), "");
        assertFalse(noBase.isEnabled());
        assertNull(noBase.mint(KEY, 60, "inline"));
    }

    @Test
    @DisplayName("blank/null key -> mint null (never signs an empty key)")
    void blankKeyRefused() {
        PublicLinkService service = enabledService();
        assertNull(service.mint(null, 60, "inline"));
        assertNull(service.mint("  ", 60, "inline"));
    }
}
