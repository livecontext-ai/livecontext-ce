package com.apimarketplace.common.storage.signing;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The freshness lifetime of a signed-URL response must never outlive the link that earned it.
 *
 * <p>Regression cover for a real defect: the endpoint used to answer a flat
 * {@code private, max-age=900} whatever the link had left, so a browser could re-serve a cached
 * copy - WITHOUT re-presenting the signature - for up to 15 minutes after {@code exp}. The
 * shortest link the platform mints is 5 minutes ({@code PublicLinkService.MIN_TTL_MINUTES}), so
 * the window was reachable with a supported configuration, not only in theory.
 */
@DisplayName("SignedResponseCacheControl.forExpiry")
class SignedResponseCacheControlTest {

    private static final long NOW = 1_700_000_000L;

    @Test
    @DisplayName("A long-lived link is capped at the 900s ceiling, not given its full remaining life")
    void longLivedLinkIsCappedAtTheCeiling() {
        // 4h is the default of BOTH minting sites (showcase rewriter, PublicLinkService).
        assertThat(SignedResponseCacheControl.forExpiry(NOW + 4 * 3600, NOW))
                .isEqualTo("private, max-age=900");
    }

    @Test
    @DisplayName("A 7-day link, the platform maximum, is still capped at the ceiling")
    void platformMaximumIsStillCapped() {
        assertThat(SignedResponseCacheControl.forExpiry(NOW + 7 * 24 * 3600, NOW))
                .isEqualTo("private, max-age=900");
    }

    @Test
    @DisplayName("A link with less left than the ceiling caches only for what it has left")
    void shortLinkCachesOnlyForItsRemainingLife() {
        assertThat(SignedResponseCacheControl.forExpiry(NOW + 60, NOW))
                .isEqualTo("private, max-age=60");
    }

    @Test
    @DisplayName("The 5-minute platform floor caches for 300s - the case the flat 900 got wrong")
    void platformMinimumTtlIsNotInflatedToTheCeiling() {
        // PublicLinkService.MIN_TTL_MINUTES = 5. Pre-fix this answered 900: a cache entry stayed
        // fresh for 10 minutes after the link died.
        assertThat(SignedResponseCacheControl.forExpiry(NOW + 300, NOW))
                .isEqualTo("private, max-age=300");
    }

    @Test
    @DisplayName("Exactly at the ceiling, the two branches agree")
    void exactlyAtTheCeiling() {
        assertThat(SignedResponseCacheControl.forExpiry(NOW + 900, NOW))
                .isEqualTo("private, max-age=900");
    }

    @Test
    @DisplayName("One second of life left yields exactly one second")
    void oneSecondLeft() {
        assertThat(SignedResponseCacheControl.forExpiry(NOW + 1, NOW))
                .isEqualTo("private, max-age=1");
    }

    @Test
    @DisplayName("An already-expired link floors at 1 rather than emitting a negative delta-seconds")
    void expiredLinkFloorsAtOne() {
        // Unreachable past ShowcaseUrlSigner.verify, which refuses exp <= now. Guarded anyway:
        // a NEGATIVE delta-seconds is not a shorter lifetime, it is unparseable, and RFC 9111
        // has a cache read an unparseable value as a very large one - the exact inverse of the
        // intent. (max-age=0 is valid HTTP and means "revalidate before reuse"; only a negative
        // value is malformed. The floor of 1 keeps us clear of both readings.)
        assertThat(SignedResponseCacheControl.forExpiry(NOW - 5000, NOW))
                .isEqualTo("private, max-age=1");
        assertThat(SignedResponseCacheControl.forExpiry(NOW, NOW))
                .isEqualTo("private, max-age=1");
    }

    @Test
    @DisplayName("An absurd exp that would underflow the subtraction floors at 1, not a huge lifetime")
    void underflowDoesNotProduceAHugeLifetime() {
        // Long.MIN_VALUE - NOW overflows to a large POSITIVE number, which a naive
        // `min(ceiling, exp - now)` would happily cap at the ceiling and serve. The explicit
        // exp < now branch is what catches it.
        assertThat(SignedResponseCacheControl.forExpiry(Long.MIN_VALUE, NOW))
                .isEqualTo("private, max-age=1");
    }

    @Test
    @DisplayName("Always private: a shared cache holds bytes where the expiry cannot reach them")
    void alwaysPrivate() {
        assertThat(SignedResponseCacheControl.forExpiry(NOW + 4 * 3600, NOW)).startsWith("private, ");
        assertThat(SignedResponseCacheControl.forExpiry(NOW + 10, NOW)).startsWith("private, ");
        assertThat(SignedResponseCacheControl.forExpiry(NOW - 10, NOW)).startsWith("private, ");
    }

    @Test
    @DisplayName("The lifetime never exceeds the ceiling nor the remaining life, across a wide sweep")
    void neverExceedsEitherBound() {
        for (long remaining : new long[]{1, 2, 59, 60, 299, 300, 899, 900, 901, 3600, 14400, 604800}) {
            long value = Long.parseLong(
                    SignedResponseCacheControl.forExpiry(NOW + remaining, NOW)
                            .substring("private, max-age=".length()));
            assertThat(value)
                    .as("remaining=%d", remaining)
                    .isPositive()
                    .isLessThanOrEqualTo(SignedResponseCacheControl.MAX_AGE_CEILING_SECONDS)
                    .isLessThanOrEqualTo(remaining);
        }
    }
}
