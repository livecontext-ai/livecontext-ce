package com.apimarketplace.common.web;

import java.util.regex.Pattern;

/**
 * The one definition of "a provider refusal that says it is a rate limit". Read by the catalog, to
 * tell an agent to wait instead of "refused the same way", and by the workflow engine, to decide
 * whether a node's retry is worth another attempt. Both must read one refusal the same way, so the
 * wording lives here once instead of in a copy per service.
 */
public final class RateLimitSignals {

    /**
     * Wordings providers use for a rate limit they send as a 4xx: Google's rateLimitExceeded, Meta's
     * "(#4) Application request limit reached", AWS "Rate exceeded" / ThrottlingException, Salesforce
     * REQUEST_LIMIT_EXCEEDED. Deliberately not a bare "limit_exceeded": that also names storage and
     * file-size limits, which waiting cannot lift.
     */
    public static final Pattern WORDING = Pattern.compile(
            "rate.?limit|too many requests|ratelimitexceeded|try again later|request limit reached"
                    + "|rate exceeded|throttl|request_limit_exceeded",
            Pattern.CASE_INSENSITIVE);

    private RateLimitSignals() {
    }
}
