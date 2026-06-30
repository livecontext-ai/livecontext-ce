package com.apimarketplace.agent.provider;

import java.time.Duration;
import java.util.Optional;

/**
 * Exception thrown by LLM providers when an error occurs.
 *
 * <p>Carries an optional {@code retryAfter} hint parsed from upstream responses
 * (HTTP {@code Retry-After} header, Google {@code error.details[].retryDelay},
 * or Google body {@code "retry in Xs"} regex). Consumed by
 * {@link com.apimarketplace.agent.retry.RetryPolicy} to honor server-side
 * pacing instead of pure exponential backoff.</p>
 */
public class LLMProviderException extends RuntimeException {

    private final String providerName;
    private final String errorCode;
    private final boolean retryable;
    private Optional<Duration> retryAfter = Optional.empty();

    public LLMProviderException(String providerName, String message) {
        super(message);
        this.providerName = providerName;
        this.errorCode = null;
        this.retryable = false;
    }

    public LLMProviderException(String providerName, String message, Throwable cause) {
        super(message, cause);
        this.providerName = providerName;
        this.errorCode = null;
        this.retryable = false;
    }

    public LLMProviderException(String providerName, String message, String errorCode, boolean retryable) {
        super(message);
        this.providerName = providerName;
        this.errorCode = errorCode;
        this.retryable = retryable;
    }

    public LLMProviderException(String providerName, String message, String errorCode, boolean retryable, Throwable cause) {
        super(message, cause);
        this.providerName = providerName;
        this.errorCode = errorCode;
        this.retryable = retryable;
    }

    public String getProviderName() {
        return providerName;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public boolean isRetryable() {
        return retryable;
    }

    /**
     * Optional server-side retry hint. Empty when the upstream response did not
     * include a parseable {@code Retry-After} header or body delay field.
     */
    public Optional<Duration> retryAfter() {
        return retryAfter;
    }

    /**
     * Create a rate limit exception (retryable).
     */
    public static LLMProviderException rateLimited(String providerName) {
        return rateLimited(providerName, null);
    }

    /**
     * Create a rate limit exception with an optional server-side retry hint.
     */
    public static LLMProviderException rateLimited(String providerName, Duration retryAfter) {
        String suffix = retryAfter != null ? " (retry-after: " + retryAfter.toSeconds() + "s)" : "";
        LLMProviderException e = new LLMProviderException(providerName,
            "Rate limit exceeded" + suffix, "rate_limit", true);
        e.retryAfter = Optional.ofNullable(retryAfter);
        return e;
    }

    /**
     * Create a transient failure exception (retryable). Used for HTTP 408/425/5xx
     * and similar server-side blips. Named {@code transientFailure} because
     * {@code transient} is a Java reserved keyword.
     */
    public static LLMProviderException transientFailure(String providerName, String message, Duration retryAfter) {
        LLMProviderException e = new LLMProviderException(providerName, message, "transient", true);
        e.retryAfter = Optional.ofNullable(retryAfter);
        return e;
    }

    /**
     * Create an authentication exception (not retryable)
     */
    public static LLMProviderException unauthorized(String providerName) {
        return new LLMProviderException(providerName, "Invalid API key", "unauthorized", false);
    }

    /**
     * Create a model not found exception
     */
    public static LLMProviderException modelNotFound(String providerName, String model) {
        return new LLMProviderException(providerName, "Model not found: " + model, "model_not_found", false);
    }

    /**
     * Create a streaming not supported exception
     */
    public static LLMProviderException streamingNotSupported(String providerName) {
        return new LLMProviderException(providerName, "Streaming not supported", "streaming_not_supported", false);
    }
}
