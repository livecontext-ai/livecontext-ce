package com.apimarketplace.catalog.service.http;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The two things the caller says and hears back about a provider retry.
 *
 * <p>The distinction these tests are here to protect is <b>absent versus zero</b>. Absent means
 * "the platform decides", which is what every caller that never thought about a 429 wants. Zero
 * means "do not wait at all, I own the pacing". Collapsing the two would make one of the two
 * intentions unexpressible, and the one that would be lost is the one that stops us from
 * multiplying a careful author's requests.
 */
@DisplayName("ProviderRetryContext - the caller's retry budget, and what it hears back")
class ProviderRetryContextTest {

    @AfterEach
    void clear() {
        ProviderRetryContext.clear();
    }

    @Nested
    @DisplayName("the budget")
    class Budget {

        @Test
        @DisplayName("nothing set means the platform decides")
        void absentByDefault() {
            assertThat(ProviderRetryContext.getMaxWaitMs()).isNull();
        }

        @Test
        @DisplayName("zero is a real answer, not the absence of one")
        void zeroIsDistinctFromAbsent() {
            ProviderRetryContext.setMaxWaitSeconds(0);

            assertThat(ProviderRetryContext.getMaxWaitMs())
                    .as("a caller that says 'never wait' must not read as a caller that said nothing")
                    .isEqualTo(0L);
        }

        @Test
        @DisplayName("seconds become milliseconds, because that is what the wait is measured in")
        void secondsAreConvertedToMillis() {
            ProviderRetryContext.setMaxWaitSeconds(30);

            assertThat(ProviderRetryContext.getMaxWaitMs()).isEqualTo(30_000L);
        }

        @Test
        @DisplayName("null clears it back to 'the platform decides'")
        void nullRestoresTheDefault() {
            ProviderRetryContext.setMaxWaitSeconds(30);

            ProviderRetryContext.setMaxWaitSeconds(null);

            assertThat(ProviderRetryContext.getMaxWaitMs()).isNull();
        }

        @Test
        @DisplayName("a negative budget is read as zero rather than refused")
        void negativeIsTreatedAsZero() {
            // Failing a provider call over a malformed knob would be worse than honouring the
            // intent: the caller asked for no waiting, and that is exactly what -5 gets.
            ProviderRetryContext.setMaxWaitSeconds(-5);

            assertThat(ProviderRetryContext.getMaxWaitMs()).isEqualTo(0L);
        }

        @Test
        @DisplayName("a budget large enough to overflow in milliseconds still converts")
        void largeBudgetsConvertWithoutOverflow() {
            ProviderRetryContext.setMaxWaitSeconds(Integer.MAX_VALUE);

            // The multiplication happens in long arithmetic. In int arithmetic this would come
            // back NEGATIVE, and a negative budget refuses every wait, so an author asking for a
            // very long one would silently get none.
            assertThat(ProviderRetryContext.getMaxWaitMs())
                    .isEqualTo(Integer.MAX_VALUE * 1000L)
                    .isPositive();
        }
    }

    @Nested
    @DisplayName("the count that travels back")
    class Retries {

        @Test
        @DisplayName("no re-send means zero, and reading it does not create one")
        void zeroWhenNothingHappened() {
            assertThat(ProviderRetryContext.getRetries()).isZero();
            assertThat(ProviderRetryContext.getRetries()).isZero();
        }

        @Test
        @DisplayName("each re-send is counted")
        void countsEachResend() {
            ProviderRetryContext.recordRetry();
            ProviderRetryContext.recordRetry();

            assertThat(ProviderRetryContext.getRetries()).isEqualTo(2);
        }

        @Test
        @DisplayName("clear resets both, so one call cannot report the previous call's retries")
        void clearResetsBoth() {
            ProviderRetryContext.setMaxWaitSeconds(10);
            ProviderRetryContext.recordRetry();

            ProviderRetryContext.clear();

            assertThat(ProviderRetryContext.getMaxWaitMs()).isNull();
            assertThat(ProviderRetryContext.getRetries()).isZero();
        }
    }

    @Nested
    @DisplayName("opening a call")
    class Begin {

        @Test
        @DisplayName("begin RESETS the count, so one call cannot report the previous call's re-sends")
        void beginResetsTheCount() {
            // The leak this exists to close: a request that 429s once leaves RETRIES=1 on a pooled
            // Tomcat thread. Unlike the budget, which every entry point sets and therefore
            // self-heals, the count has no equivalent - so the NEXT request on that thread would
            // report a re-send that never happened, for another tenant, on a step whose only
            // evidence of a wait is this number.
            ProviderRetryContext.recordRetry();
            ProviderRetryContext.recordRetry();

            ProviderRetryContext.begin(null);

            assertThat(ProviderRetryContext.getRetries()).isZero();
        }

        @Test
        @DisplayName("begin records the budget as well")
        void beginRecordsTheBudget() {
            ProviderRetryContext.begin(0);

            assertThat(ProviderRetryContext.getMaxWaitMs()).isEqualTo(0L);
        }

        @Test
        @DisplayName("begin(null) leaves the platform to decide, and clears a previous budget")
        void beginNullClearsTheBudget() {
            ProviderRetryContext.begin(30);

            ProviderRetryContext.begin(null);

            assertThat(ProviderRetryContext.getMaxWaitMs()).isNull();
        }
    }

    @Test
    @DisplayName("one thread's budget is invisible to another, so concurrent calls cannot borrow "
            + "each other's")
    void isThreadBound() throws Exception {
        ProviderRetryContext.setMaxWaitSeconds(0);
        ProviderRetryContext.recordRetry();

        Long[] otherThreadBudget = new Long[1];
        int[] otherThreadRetries = new int[1];
        Thread other = new Thread(() -> {
            otherThreadBudget[0] = ProviderRetryContext.getMaxWaitMs();
            otherThreadRetries[0] = ProviderRetryContext.getRetries();
        });
        other.start();
        other.join();

        assertThat(otherThreadBudget[0])
                .as("a second in-flight tool call must not inherit this one's 'never wait'")
                .isNull();
        assertThat(otherThreadRetries[0]).isZero();
    }
}
