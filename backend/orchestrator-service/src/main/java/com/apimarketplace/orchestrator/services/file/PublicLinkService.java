package com.apimarketplace.orchestrator.services.file;

import com.apimarketplace.common.storage.signing.ShowcaseUrlSigner;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;

/**
 * Mints PUBLIC, time-limited, HMAC-signed download URLs for files in the platform's own
 * storage - the "Public Access for files" counterpart of webhooks/forms/chat endpoints.
 *
 * <p>Reuses the exact mechanism that already serves marketplace/share previews to anonymous
 * visitors: {@link ShowcaseUrlSigner} (HMAC-SHA256 over {@code key|exp|disposition}) verified
 * by storage-service's public {@code GET /api/files/proxy-signed} route (whitelisted in the
 * gateway's PUBLIC_ENDPOINTS - the signature IS the authorization, no JWT/tenant headers).
 * External servers (a social network's crawler fetching a video URL, a webhook consumer)
 * can therefore download the file with zero credentials until the link expires.</p>
 *
 * <p>The caller is responsible for the OWNERSHIP check (the key must belong to the calling
 * tenant) - this service only signs and shapes the URL.</p>
 */
@Service
public class PublicLinkService {

    /** Default link lifetime: matches the marketplace showcase default (4 hours). */
    public static final int DEFAULT_TTL_MINUTES = 240;
    public static final int MIN_TTL_MINUTES = 5;
    /** 7 days - long enough for slow social-network pulls, short enough to stay "temporary". */
    public static final int MAX_TTL_MINUTES = 10_080;

    private final ShowcaseUrlSigner signer;
    private final String publicBaseUrl;

    public PublicLinkService(
        ShowcaseUrlSigner signer,
        // Same resolution order as PublicFileUrlBuilder: the dedicated public origin first,
        // then the app base URL (CE sets app.public-url to the frontend origin explicitly).
        @Value("${app.public-url:${app.base-url:}}") String publicBaseUrl
    ) {
        this.signer = signer;
        this.publicBaseUrl = publicBaseUrl == null ? "" : publicBaseUrl.replaceAll("/+$", "");
    }

    /** True when the installation can mint public links (signing secret + public origin set). */
    public boolean isEnabled() {
        return signer != null && signer.isEnabled() && !publicBaseUrl.isBlank();
    }

    /** Clamp a caller-supplied TTL to [MIN, MAX]; null/non-positive falls back to the default. */
    public static int clampTtlMinutes(Integer ttlMinutes) {
        if (ttlMinutes == null || ttlMinutes <= 0) {
            return DEFAULT_TTL_MINUTES;
        }
        return Math.max(MIN_TTL_MINUTES, Math.min(ttlMinutes, MAX_TTL_MINUTES));
    }

    /** A minted public link: absolute URL + its expiry instant. */
    public record PublicLink(String url, Instant expiresAt) { }

    /**
     * Mint an absolute, publicly fetchable URL for the given storage key.
     *
     * @param storageKey  the file's storage key ({@code {tenantId}/...}) - MUST already be
     *                    ownership-checked by the caller
     * @param ttlMinutes  pre-clamped lifetime in minutes
     * @param disposition {@code inline} or {@code attachment}
     * @return the link, or null when the feature is disabled on this installation
     */
    public PublicLink mint(String storageKey, int ttlMinutes, String disposition) {
        if (!isEnabled() || storageKey == null || storageKey.isBlank()) {
            return null;
        }
        String dispo = "attachment".equalsIgnoreCase(disposition) ? "attachment" : "inline";
        Instant expiresAt = Instant.now().plusSeconds(ttlMinutes * 60L);
        String sig = signer.sign(storageKey, expiresAt.getEpochSecond(), dispo);
        if (sig == null) {
            return null;
        }
        String url = publicBaseUrl
            + "/api/files/proxy-signed?key=" + URLEncoder.encode(storageKey, StandardCharsets.UTF_8)
            + "&exp=" + expiresAt.getEpochSecond()
            + "&disposition=" + dispo
            + "&sig=" + URLEncoder.encode(sig, StandardCharsets.UTF_8);
        return new PublicLink(url, expiresAt);
    }
}
