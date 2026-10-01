package com.apimarketplace.common.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The shared rate-limit wording decides whether an agent is told to wait and whether a workflow node
 * retries. A miss means a real rate limit is treated as a permanent refusal; a false hit means a limit
 * that waiting cannot lift is retried.
 */
@DisplayName("RateLimitSignals - what counts as a rate limit in a provider's words")
class RateLimitSignalsTest {

    private static boolean says(String body) {
        return RateLimitSignals.WORDING.matcher(body).find();
    }

    @Test
    @DisplayName("recognises the rate limits real providers send as a 4xx")
    void realProviderWordings() {
        for (String body : new String[] {
                "User Rate Limit Exceeded", "rateLimitExceeded", "Too Many Requests",
                "(#4) Application request limit reached", "(#17) User request limit reached",
                "ThrottlingException: Rate exceeded", "REQUEST_LIMIT_EXCEEDED: TotalRequests Limit exceeded",
                "You have exceeded a secondary rate limit", "Please try again later"}) {
            assertThat(says(body)).as(body).isTrue();
        }
    }

    @Test
    @DisplayName("REGRESSION: storage and file-size limits are not rate limits: waiting cannot lift them")
    void limitsThatWaitingCannotLift() {
        for (String body : new String[] {
                "STORAGE_LIMIT_EXCEEDED", "FILE_SIZE_LIMIT_EXCEEDED", "payload_limit_exceeded",
                "invalid parameter: chat_id", "Not Found"}) {
            assertThat(says(body)).as(body).isFalse();
        }
    }
}
