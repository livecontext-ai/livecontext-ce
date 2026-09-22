package com.apimarketplace.common.storage.signing;

/**
 * {@code Cache-Control} for a response served off an HMAC-signed, expiring URL
 * ({@code /api/files/proxy-signed}, minted by {@link ShowcaseUrlSigner}).
 *
 * <p>A cached response is re-served WITHOUT coming back to the controller, so its freshness
 * lifetime is time during which the signature and expiry gate is not consulted. The lifetime is
 * therefore capped at what is left of the URL's own life: {@code min(900, exp - now)}. A flat
 * value cannot do this. The flat {@code max-age=900} this replaces was justified in-code as
 * mirroring "the 15-min URL TTL", but no minting site uses 15 minutes: the showcase rewriter
 * ({@code publication.showcase.presign-expiry-minutes}) and {@code PublicLinkService} both
 * default to 240 minutes, the latter allows up to 7 days, and its floor is 5 minutes. On that
 * 5-minute floor the old value handed out 900s of freshness for a 300s link.
 *
 * <p>{@code private} on top of the cap: a shared CDN cache holds bytes somewhere this expiry
 * cannot reach at all.
 *
 * <p>Lives beside the signer, and is used by BOTH mounts of the endpoint (cloud
 * {@code storage.web.FileController} and CE {@code monolith.storage.MonolithFileController}),
 * so the ceiling and the formula exist once rather than as two copies kept in step by a comment.
 *
 * <p><strong>The bound is not exact</strong>, and is not meant to be. A caller reads {@code now}
 * before opening the object stream, while a cache counts age from the response {@code Date}, so
 * the entry can stay fresh for the open latency past {@code exp} - normally milliseconds, and at
 * worst the object-store connection-acquisition wait. The cap exists to stop a 300s link handing
 * out 900s of freshness, not to be a precise deadline.
 */
public final class SignedResponseCacheControl {

    /**
     * Upper bound on the freshness lifetime, whatever the link has left. An anonymous viewer
     * should re-present the signature reasonably often even on a 7-day link.
     */
    public static final long MAX_AGE_CEILING_SECONDS = 900L;

    private SignedResponseCacheControl() {
    }

    /**
     * The header value for a request whose signature has ALREADY been verified.
     *
     * <p>Callers reach this only past {@link ShowcaseUrlSigner#verify}, which refuses
     * {@code exp <= now}, so the remaining life is at least one second. The floor of 1 is kept
     * anyway rather than trusting that ordering: a negative {@code delta-seconds} is not merely
     * a shorter lifetime, it is unparseable, and RFC 9111 has caches treat an unparseable value
     * as a very large one - the exact opposite of what this method exists to do. ({@code 0} is
     * valid HTTP and means "revalidate before every reuse"; only a negative value is malformed.)
     *
     * @param expiresAtEpochSeconds the {@code exp} carried by the signed URL
     * @param nowEpochSeconds       the clock reading used to verify that {@code exp}
     * @return e.g. {@code "private, max-age=900"}
     */
    public static String forExpiry(long expiresAtEpochSeconds, long nowEpochSeconds) {
        long remaining = expiresAtEpochSeconds - nowEpochSeconds;
        // Subtraction, not Math.min on the raw values: an absurd exp near Long.MIN_VALUE would
        // underflow to a huge POSITIVE remaining. Such a URL is refused by verify() long before
        // it reaches here, so this is belt-and-braces for a future caller that forgets.
        if (remaining < 1 || expiresAtEpochSeconds < nowEpochSeconds) {
            remaining = 1;
        }
        return "private, max-age=" + Math.min(MAX_AGE_CEILING_SECONDS, remaining);
    }
}
