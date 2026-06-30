package com.apimarketplace.auth.credential.service.oauth2.refresh;

/**
 * Categorisation of OAuth2 refresh-token errors, driving retry policy, status flip, and operator
 * visibility. The mapping from raw provider response to bucket lives in {@link RefreshErrorClassifier}.
 *
 * <p>Design contract: every non-success outcome of a refresh call resolves to exactly one bucket.
 * Callers (engine, scheduler, lazy-401 retry) must branch on the bucket, not on the raw HTTP
 * status or provider error code - those are carried as diagnostic payload only.
 */
public enum RefreshErrorBucket {

    /**
     * The refresh_token itself is no longer usable - revoked, expired, scope downgraded, or the
     * user disconnected the app. RFC 6749 maps this to {@code invalid_grant} and sometimes
     * {@code unauthorized_client}. Terminal: the user must re-OAuth. Tokens are scrubbed from
     * storage and the credential flips to {@link com.apimarketplace.auth.credential.domain.CredentialModels.CredentialStatus#needs_reauth}.
     * Retries are forbidden - repeating the refresh produces the same error and risks provider
     * quota/account flagging.
     */
    TERMINAL_USER,

    /**
     * The provider accepted the request format but rejected our client credentials or scope
     * configuration - RFC 6749 {@code invalid_client}, {@code invalid_scope}. Also the fallback
     * bucket for {@link #CLIENT_BUG}: a request that never should have been sent. Admin must fix
     * the template, platform credential, or scope list. Credential flips to
     * {@link com.apimarketplace.auth.credential.domain.CredentialModels.CredentialStatus#error}.
     * Retries are forbidden; the underlying config must change first.
     */
    TERMINAL_CONFIG,

    /**
     * The provider is temporarily unreachable or misbehaving - HTTP 5xx, socket timeout, TLS
     * handshake failure, DNS blip. Retry with full-jitter exponential backoff. After a capped
     * number of attempts, promote to {@link #TERMINAL_CONFIG} with reason
     * {@code max_transient_retries_exceeded}.
     */
    TRANSIENT,

    /**
     * HTTP 429 from the provider's token endpoint. Honor the {@code Retry-After} response header
     * (seconds or HTTP-date per RFC 7231 §7.1.3) rather than the jitter schedule. Not terminal on
     * its own; sustained rate-limiting eventually promotes via the TRANSIENT counter.
     */
    RATE_LIMIT,

    /**
     * Our side sent a malformed request - RFC 6749 {@code invalid_request}, or a 4xx that
     * shouldn't reach this layer (missing grant_type, etc.). Logged at ERROR severity (bug) and
     * collapsed to {@link #TERMINAL_CONFIG} for the user-visible outcome, because retrying
     * without a code change is pointless.
     */
    CLIENT_BUG
}
