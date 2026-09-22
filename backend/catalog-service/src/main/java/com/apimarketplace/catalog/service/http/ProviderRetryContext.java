package com.apimarketplace.catalog.service.http;

/**
 * Thread-bound holder for the caller's retry budget, and for what the retry actually did.
 *
 * <p><b>Why a caller-supplied budget exists.</b> The platform retries a provider's rate-limit
 * refusal by default, which is right for a step that has no retry logic of its own. It is wrong
 * for a workflow that already paces itself: a loop that calls, waits sixty seconds and comes back
 * turns into three requests per turn instead of one, so an author who was careful ends up
 * hammering the provider harder than one who was not. The node therefore says how long ITS call
 * may spend waiting, and {@code 0} means "do not retry, I own this".
 *
 * <p><b>Why the count travels back.</b> The wait happens inside a single tool call, so the node
 * stays RUNNING and emits nothing: without this, a step that silently took ten seconds longer is
 * indistinguishable from a slow provider. The count is stamped on the step's output so the reader
 * can see the call was re-sent, after the fact.
 *
 * <p><b>Why a thread-local.</b> Same reasoning, and same lifecycle, as {@link CredentialModeContext}
 * next to it: request-scoped data that would otherwise thread through half a dozen signatures.
 * Catalog tool execution is synchronous on one HTTP thread, and the controller clears this in a
 * {@code finally} block before the response leaves the service.
 */
public final class ProviderRetryContext {

    /** Caller's total sleep budget for this call, in ms. Null = use the platform default. */
    private static final ThreadLocal<Long> MAX_WAIT_MS = new ThreadLocal<>();

    /** How many times the provider call was re-sent. Written by the execution path. */
    private static final ThreadLocal<Integer> RETRIES = new ThreadLocal<>();

    private ProviderRetryContext() {
    }

    /**
     * Opens a call: records the caller's budget and resets what the previous call left behind.
     *
     * <p><b>Why this exists rather than a bare setter.</b> The budget self-heals, because every
     * entry point sets it. The COUNT does not: a request that left {@code RETRIES=1} on a pooled
     * Tomcat thread would have the next request on that thread report a re-send that never
     * happened, for a different tenant. Resetting on the way in makes the counter correct even
     * from an entry point that forgets to clear on the way out.
     *
     * @param seconds the caller's budget in SECONDS, as a workflow author sets it. Null leaves the
     *                platform default in place; 0 disables retrying for this call. A negative value
     *                is treated as 0 rather than rejected: the caller asked for no waiting, and
     *                failing a provider call over a malformed knob would be worse than honouring
     *                the intent. A value larger than the platform's own budget is capped at it by
     *                {@code HttpExecutionService} - see the clamp there for why.
     */
    public static void begin(Integer seconds) {
        RETRIES.remove();
        setMaxWaitSeconds(seconds);
    }

    /**
     * Records the caller's budget only, WITHOUT resetting the count.
     *
     * <p>Package-private on purpose: an entry point that used this instead of {@link #begin} would
     * leave a stale re-send count on the thread, and the next request through that thread would
     * report a re-send that never happened. Restricting the visibility makes that mistake
     * unavailable rather than merely discouraged. Tests in this package use it to set up a state
     * without disturbing the count.
     */
    static void setMaxWaitSeconds(Integer seconds) {
        if (seconds == null) {
            MAX_WAIT_MS.remove();
            return;
        }
        MAX_WAIT_MS.set(Math.max(0L, seconds.longValue()) * 1000L);
    }

    /** The caller's budget in ms, or null when it did not set one. */
    public static Long getMaxWaitMs() {
        return MAX_WAIT_MS.get();
    }

    /** Counts one re-send of the provider call. */
    public static void recordRetry() {
        Integer current = RETRIES.get();
        RETRIES.set(current == null ? 1 : current + 1);
    }

    /** How many re-sends happened on this call, 0 when none did. */
    public static int getRetries() {
        Integer current = RETRIES.get();
        return current == null ? 0 : current;
    }

    public static void clear() {
        MAX_WAIT_MS.remove();
        RETRIES.remove();
    }
}
