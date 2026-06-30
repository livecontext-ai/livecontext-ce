package com.apimarketplace.auth.credential.service.oauth2.refresh;

import java.time.Duration;

/**
 * Thrown when an OAuth2 refresh attempt lands in {@link RefreshErrorBucket#TRANSIENT} or
 * {@link RefreshErrorBucket#RATE_LIMIT}. The provider may recover; the pipeline schedules a
 * retry honoring {@link #retryAfter()} (if present) or a full-jitter exponential schedule.
 *
 * <p>Callers <em>may</em> fall back to the persisted {@code access_token} - it has not been
 * scrubbed. If the stored token is also stale, the caller's own 401-retry logic (or the
 * upstream API call) will surface that, and we avoid cascading a single provider blip into
 * every user request.
 */
public class RefreshTransientException extends RuntimeException {

    private final RefreshErrorBucket bucket;
    private final String providerCode;
    private final Integer httpStatus;
    private final Duration retryAfter;
    private final int attempt;

    public RefreshTransientException(RefreshErrorBucket bucket,
                                     String providerCode,
                                     Integer httpStatus,
                                     Duration retryAfter,
                                     int attempt,
                                     Throwable cause) {
        super(buildMessage(bucket, providerCode, httpStatus, attempt), cause);
        if (bucket != RefreshErrorBucket.TRANSIENT && bucket != RefreshErrorBucket.RATE_LIMIT) {
            throw new IllegalArgumentException("RefreshTransientException requires a transient bucket, got: " + bucket);
        }
        this.bucket = bucket;
        this.providerCode = providerCode;
        this.httpStatus = httpStatus;
        this.retryAfter = retryAfter;
        this.attempt = Math.max(0, attempt);
    }

    public RefreshErrorBucket bucket() {
        return bucket;
    }

    public String providerCode() {
        return providerCode;
    }

    public Integer httpStatus() {
        return httpStatus;
    }

    /** Provider-supplied {@code Retry-After} hint, or {@code null} to use the jitter schedule. */
    public Duration retryAfter() {
        return retryAfter;
    }

    /** Zero-based attempt counter; used by the jitter schedule and terminal promotion. */
    public int attempt() {
        return attempt;
    }

    private static String buildMessage(RefreshErrorBucket bucket, String providerCode, Integer httpStatus, int attempt) {
        StringBuilder sb = new StringBuilder(bucket.name().toLowerCase());
        sb.append(" (attempt=").append(attempt).append(")");
        if (providerCode != null && !providerCode.isBlank()) {
            sb.append(" provider_code=").append(providerCode);
        }
        if (httpStatus != null) {
            sb.append(" http=").append(httpStatus);
        }
        return sb.toString();
    }
}
