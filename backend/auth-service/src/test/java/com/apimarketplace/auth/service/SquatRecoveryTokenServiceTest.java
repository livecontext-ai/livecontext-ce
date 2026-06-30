package com.apimarketplace.auth.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("SquatRecoveryTokenService")
class SquatRecoveryTokenServiceTest {

    @Mock private StringRedisTemplate redisTemplate;
    @Mock private ValueOperations<String, String> valueOps;

    private SquatRecoveryTokenService service;

    private static final UUID INSTALL = UUID.fromString("11111111-2222-3333-4444-555555555555");

    @BeforeEach
    void setUp() {
        service = new SquatRecoveryTokenService(redisTemplate, 60L);
        // lenient: the null/blank-token test short-circuits before opsForValue is touched.
        org.mockito.Mockito.lenient().when(redisTemplate.opsForValue()).thenReturn(valueOps);
    }

    @Test
    @DisplayName("mintProducesUniqueUrlSafeTokens - entropy + URL-safe alphabet, distinct across calls")
    void mint_unique_url_safe() {
        String a = service.mint(INSTALL, 42L);
        String b = service.mint(INSTALL, 42L);

        assertThat(a).isNotEqualTo(b);
        assertThat(a).matches("[A-Za-z0-9_-]+");
        // 32 random bytes → 43 base64-no-padding chars.
        assertThat(a).hasSize(43);
    }

    @Test
    @DisplayName("mint stores the token under the prefixed key with the configured TTL and the install|victim binding")
    void mint_stores_binding_with_ttl() {
        ArgumentCaptor<String> keyCap = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> valCap = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Duration> ttlCap = ArgumentCaptor.forClass(Duration.class);

        service.mint(INSTALL, 42L);

        verify(valueOps).set(keyCap.capture(), valCap.capture(), ttlCap.capture());
        assertThat(keyCap.getValue()).startsWith("ce-link:squat-recovery:");
        assertThat(valCap.getValue()).isEqualTo(INSTALL + "|42");
        assertThat(ttlCap.getValue()).isEqualTo(Duration.ofMinutes(60));
    }

    @Test
    @DisplayName("consume returns the binding atomically and the token can never be reused")
    void consume_returns_binding_atomically() {
        when(valueOps.getAndDelete(contains("the-token"))).thenReturn(INSTALL + "|42");

        Optional<SquatRecoveryTokenService.TokenBinding> binding = service.consume("the-token");

        assertThat(binding).isPresent();
        assertThat(binding.get().installId()).isEqualTo(INSTALL);
        assertThat(binding.get().victimUserId()).isEqualTo(42L);
    }

    @Test
    @DisplayName("consume returns empty on unknown / already-consumed / expired token (same shape - no oracle)")
    void consume_returns_empty_when_unknown() {
        when(valueOps.getAndDelete(any(String.class))).thenReturn(null);

        assertThat(service.consume("bogus")).isEmpty();
    }

    @Test
    @DisplayName("consumeReturnsEmptyOnRedisFailure - outage degrades to no-recovery, never throws upstream")
    void consume_returns_empty_on_redis_failure() {
        when(valueOps.getAndDelete(any(String.class))).thenThrow(new RuntimeException("redis down"));

        assertThat(service.consume("any")).isEmpty();
    }

    @Test
    @DisplayName("consume rejects null / blank token without hitting Redis")
    void consume_rejects_null_or_blank() {
        assertThat(service.consume(null)).isEmpty();
        assertThat(service.consume("")).isEmpty();
        assertThat(service.consume("  ")).isEmpty();
        verify(valueOps, org.mockito.Mockito.never()).getAndDelete(any(String.class));
    }

    @Test
    @DisplayName("consume rejects malformed Redis value (no | separator or bad uuid)")
    void consume_rejects_malformed_value() {
        when(valueOps.getAndDelete(contains("malformed"))).thenReturn("garbage-no-pipe");

        assertThat(service.consume("malformed")).isEmpty();
    }
}
