package com.apimarketplace.auth.credential.service.oauth2.refresh;

/**
 * Thrown when an OAuth2 refresh attempt lands in a terminal bucket
 * ({@link RefreshErrorBucket#TERMINAL_USER} or {@link RefreshErrorBucket#TERMINAL_CONFIG}).
 *
 * <p><strong>Contract for callers:</strong> on this exception the credential's status has already
 * been flipped ({@code needs_reauth} or {@code error}) and its stored {@code access_token} /
 * {@code refresh_token} have been scrubbed. Do <em>not</em> fall back to any persisted token and
 * do <em>not</em> retry the refresh - retries will produce the same provider error and risk
 * quota or account flagging. Surface the failure to the user-facing layer, which must trigger
 * re-OAuth (for {@link RefreshErrorBucket#TERMINAL_USER}) or ops escalation
 * (for {@link RefreshErrorBucket#TERMINAL_CONFIG}).
 */
public class RefreshTerminalException extends RuntimeException {

    private final RefreshErrorBucket bucket;
    private final String providerCode;
    private final Integer httpStatus;
    private final String reason;

    public RefreshTerminalException(RefreshErrorBucket bucket,
                                    String providerCode,
                                    Integer httpStatus,
                                    String reason) {
        super(buildMessage(bucket, reason, providerCode, httpStatus));
        if (bucket != RefreshErrorBucket.TERMINAL_USER && bucket != RefreshErrorBucket.TERMINAL_CONFIG) {
            throw new IllegalArgumentException("RefreshTerminalException requires a terminal bucket, got: " + bucket);
        }
        this.bucket = bucket;
        this.providerCode = providerCode;
        this.httpStatus = httpStatus;
        this.reason = reason;
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

    public String reason() {
        return reason;
    }

    private static String buildMessage(RefreshErrorBucket bucket, String reason, String providerCode, Integer httpStatus) {
        StringBuilder sb = new StringBuilder(bucket.name().toLowerCase());
        if (reason != null && !reason.isBlank()) {
            sb.append(": ").append(reason);
        }
        if (providerCode != null && !providerCode.isBlank()) {
            sb.append(" (provider_code=").append(providerCode).append(")");
        }
        if (httpStatus != null) {
            sb.append(" [http=").append(httpStatus).append("]");
        }
        return sb.toString();
    }
}
