package com.apimarketplace.catalog.tools;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What an agent is told after a 429 or 503 the platform did not re-send. It owns the retry now, so
 * the advice must be something it can act on: the exact wait call when the provider said how long,
 * a stop when that is longer than one wait can cover, and never an immediate re-call.
 */
@DisplayName("CatalogExecuteModule - wait advice after a rate limit or an outage")
class CatalogExecuteModuleWaitAdviceTest {

    @Test
    @DisplayName("no Retry-After: suggests a pause and forbids a tight loop")
    void noHint() {
        String advice = CatalogExecuteModule.waitAdvice(429, null);

        assertThat(advice).contains("did not re-send").contains("wait(action='sleep'").contains("tight loop");
    }

    @Test
    @DisplayName("a Retry-After within the wait tool's range becomes the exact call to make")
    void withinRange() {
        String advice = CatalogExecuteModule.waitAdvice(503, 120L);

        assertThat(advice).contains("temporarily unavailable").contains("wait(action='sleep', seconds=120)");
    }

    @Test
    @DisplayName("a Retry-After beyond one pause says not to call again now, with minutes")
    void beyondRange() {
        String advice = CatalogExecuteModule.waitAdvice(429, 3600L);

        assertThat(advice)
                .contains("3600 seconds")
                .contains("Do not call it again now")
                .contains("about 60 minutes")
                .doesNotContain("seconds=3600");
    }

    @Test
    @DisplayName("a 403 that carries a Retry-After is advised as a rate limit, not an outage")
    void forbiddenWithRetryAfterIsARateLimit() {
        String advice = CatalogExecuteModule.waitAdvice(403, 20L);

        assertThat(advice).contains("rate limiting").contains("seconds=20");
    }

    @Test
    @DisplayName("the agent reads the one shared rate-limit wording, the same object a workflow node reads")
    void wordingIsTheSharedOne() {
        assertThat(CatalogExecuteModule.RATE_LIMIT_WORDING)
                .isSameAs(com.apimarketplace.common.web.RateLimitSignals.WORDING);
    }

    @Test
    @DisplayName("a wait of a day reads in hours, not 1440 minutes")
    void longWaitsReadInHours() {
        assertThat(CatalogExecuteModule.waitAdvice(429, 86_400L)).contains("about 24 hours");
        assertThat(CatalogExecuteModule.humanDuration(300)).isEqualTo("5 minutes");
        assertThat(CatalogExecuteModule.humanDuration(3600)).isEqualTo("60 minutes");
        assertThat(CatalogExecuteModule.humanDuration(7200)).isEqualTo("2 hours");
    }
}
