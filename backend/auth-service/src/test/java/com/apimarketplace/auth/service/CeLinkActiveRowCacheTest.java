package com.apimarketplace.auth.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("CeLinkActiveRowCache")
class CeLinkActiveRowCacheTest {

    /** Long TTL - no expiry interferes during test. */
    private CeLinkActiveRowCache freshCache() {
        return new CeLinkActiveRowCache(300, 1_000);
    }

    @Test
    @DisplayName("get invokes the loader once and returns the cached value on subsequent calls")
    void get_caches_after_first_load() {
        CeLinkActiveRowCache cache = freshCache();
        AtomicInteger calls = new AtomicInteger();
        Function<Long, Boolean> loader = id -> { calls.incrementAndGet(); return true; };

        boolean a = cache.get(42L, loader);
        boolean b = cache.get(42L, loader);

        assertThat(a).isTrue();
        assertThat(b).isTrue();
        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("get caches NEGATIVE results too - a user with no link does not hit the DB on every request")
    void get_caches_negative_result() {
        CeLinkActiveRowCache cache = freshCache();
        AtomicInteger calls = new AtomicInteger();
        Function<Long, Boolean> loader = id -> { calls.incrementAndGet(); return false; };

        boolean a = cache.get(42L, loader);
        boolean b = cache.get(42L, loader);

        assertThat(a).isFalse();
        assertThat(b).isFalse();
        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("invalidate forces the next get to re-invoke the loader (register/revoke wake-up path)")
    void invalidate_forces_reload() {
        CeLinkActiveRowCache cache = freshCache();
        AtomicInteger calls = new AtomicInteger();
        Function<Long, Boolean> loader = id -> { calls.incrementAndGet(); return true; };

        cache.get(42L, loader);
        cache.invalidate(42L);
        cache.get(42L, loader);

        assertThat(calls.get()).isEqualTo(2);
    }

    @Test
    @DisplayName("invalidate of one user does not drop another user's entry")
    void invalidate_is_per_user() {
        CeLinkActiveRowCache cache = freshCache();
        AtomicInteger calls = new AtomicInteger();
        Function<Long, Boolean> loader = id -> { calls.incrementAndGet(); return true; };

        cache.get(42L, loader);
        cache.get(99L, loader);
        cache.invalidate(42L);
        cache.get(99L, loader);

        assertThat(calls.get()).isEqualTo(2);   // 42 + 99 only - 99 re-read was a hit
    }

    @Test
    @DisplayName("invalidateAll wipes every entry - ops/test helper, not the hot register/revoke path")
    void invalidate_all_wipes_everything() {
        CeLinkActiveRowCache cache = freshCache();
        AtomicInteger calls = new AtomicInteger();
        Function<Long, Boolean> loader = id -> { calls.incrementAndGet(); return true; };

        cache.get(42L, loader);
        cache.get(99L, loader);
        cache.invalidateAll();
        cache.get(42L, loader);
        cache.get(99L, loader);

        assertThat(calls.get()).isEqualTo(4);
    }
}
