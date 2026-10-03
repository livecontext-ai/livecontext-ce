package com.apimarketplace.publication.service;

import com.apimarketplace.publication.domain.CeCloudLinkEntity;
import com.apimarketplace.publication.repository.CeCloudLinkRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * The cloud realm rotates refresh tokens (revokeRefreshToken in configure-keycloak.sh): each one is
 * spent on use, and a reuse past the one Keycloak tolerates ends the link's session. The CE cloud
 * link must therefore (1) never present the same stored token from concurrent callers, and
 * (2) always present the token the previous refresh returned. The fake Keycloak below is stricter
 * than the realm (it refuses the first reuse), so any second presentation fails the test.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("CloudLinkService.getCloudAccessToken under refresh-token rotation")
class CloudLinkServiceTokenRotationTest {

    private static final Long TENANT_ID = 42L;
    private static final String TOKEN_URL = "https://keycloak.example.com/realms/test/protocol/openid-connect/token";
    private static final Instant NOW = Instant.parse("2026-10-01T10:00:00Z");

    @Mock
    private CeCloudLinkRepository cloudLinkRepository;
    @Mock
    private RestTemplate restTemplate;

    private CloudLinkService service;
    private CeCloudLinkEntity link;

    /** Every refresh token presented to the fake Keycloak, in order. */
    private final List<String> presented = new CopyOnWriteArrayList<>();
    private final Set<String> spent = ConcurrentHashMap.newKeySet();
    private final AtomicInteger refreshes = new AtomicInteger();

    @BeforeEach
    void setUp() {
        service = new CloudLinkService(
                cloudLinkRepository,
                "https://keycloak.example.com/realms/test",
                "test-client-id",
                "http://localhost:3000/callback",
                "test-encryption-key-for-unit-tests",
                "https://livecontext.ai/api",
                "1.4.0-test",
                new ObjectMapper(),
                restTemplate,
                Clock.fixed(NOW, ZoneOffset.UTC));
        CloudLinkTokenColumnsFake.backByStubbedEntity(cloudLinkRepository);

        // One row, as the database holds it: every read returns it, every write lands in it.
        // (Distinct stale instances are covered against a real database in
        // CloudLinkTokenColumnsPersistenceTest.)
        link = new CeCloudLinkEntity();
        link.setId(UUID.randomUUID());
        link.setTenantId(TENANT_ID);
        link.setCloudUserId("cloud-user-123");
        link.setLinkedAt(NOW.minusSeconds(3600));
        link.setEncryptedRefreshToken(ReflectionTestUtils.invokeMethod(service, "encrypt", "rt-1"));
        link.setCachedAccessToken("at-0");
        link.setTokenExpiresAt(NOW.minusSeconds(60)); // expired: the next caller must refresh
        when(cloudLinkRepository.findByTenantId(TENANT_ID)).thenReturn(Optional.of(link));
    }

    /**
     * A fake Keycloak with rotation: refresh token rt-N answers at-N + rt-(N+1), and a token
     * presented a second time is refused (400 invalid_grant; the realm would end the session at the
     * next one, see the class comment).
     * {@code onFirstRefresh} runs inside the first refresh, before it answers.
     */
    @SuppressWarnings("unchecked")
    private void keycloakWithRotation(Runnable onFirstRefresh, CountDownLatch secondRefreshArrived) {
        when(restTemplate.exchange(eq(TOKEN_URL), eq(HttpMethod.POST), any(HttpEntity.class), eq(Map.class)))
                .thenAnswer(invocation -> {
                    HttpEntity<MultiValueMap<String, String>> request = invocation.getArgument(2);
                    assertThat(request.getBody().getFirst("grant_type")).isEqualTo("refresh_token");
                    String token = request.getBody().getFirst("refresh_token");
                    presented.add(token);
                    int n = refreshes.incrementAndGet();
                    if (n == 1) {
                        onFirstRefresh.run();
                    } else {
                        secondRefreshArrived.countDown();
                    }
                    if (!spent.add(token)) {
                        throw new HttpClientErrorException(HttpStatus.BAD_REQUEST, "invalid_grant: Maximum allowed refresh token reuse exceeded");
                    }
                    int generation = Integer.parseInt(token.substring("rt-".length()));
                    return ResponseEntity.ok(Map.of(
                            "access_token", "at-" + generation,
                            "refresh_token", "rt-" + (generation + 1),
                            "expires_in", 900));
                });
    }

    @Test
    @DisplayName("two callers that find the token expired at once refresh ONCE: the second gets the token the first stored")
    void concurrentCallersShareOneRefresh() throws Exception {
        CountDownLatch firstRefreshStarted = new CountDownLatch(1);
        CountDownLatch secondRefreshArrived = new CountDownLatch(1);
        // The first refresh holds its answer until a racing caller reaches Keycloak too (what
        // happened before the per-tenant lock), or 1 s passes (the racing caller is waiting).
        keycloakWithRotation(() -> {
            firstRefreshStarted.countDown();
            try {
                secondRefreshArrived.await(1, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, secondRefreshArrived);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<String> first = pool.submit(() -> service.getCloudAccessToken(TENANT_ID));
            assertThat(firstRefreshStarted.await(5, TimeUnit.SECONDS)).as("the first caller reached Keycloak").isTrue();
            Future<String> second = pool.submit(() -> service.getCloudAccessToken(TENANT_ID));

            assertThat(first.get(5, TimeUnit.SECONDS)).isEqualTo("at-1");
            assertThat(second.get(5, TimeUnit.SECONDS))
                    .as("the second caller got the token the first one stored, not a refusal")
                    .isEqualTo("at-1");
        } finally {
            pool.shutdownNow();
        }
        assertThat(presented).as("rt-1 was presented once, never twice").containsExactly("rt-1");
        assertThat(decryptStoredRefreshToken()).as("the rotated token is what the row keeps").isEqualTo("rt-2");
    }

    @Test
    @DisplayName("each refresh presents the token the previous refresh returned (the spent one is never sent again)")
    void eachRefreshPresentsTheRotatedToken() {
        keycloakWithRotation(() -> { }, new CountDownLatch(1));

        assertThat(service.getCloudAccessToken(TENANT_ID)).isEqualTo("at-1");
        link.setTokenExpiresAt(NOW.minusSeconds(1)); // the cached access token runs out
        assertThat(service.getCloudAccessToken(TENANT_ID)).isEqualTo("at-2");

        assertThat(presented).containsExactly("rt-1", "rt-2");
        assertThat(decryptStoredRefreshToken()).isEqualTo("rt-3");
    }

    @Test
    @DisplayName("a valid cached access token is returned without touching Keycloak")
    void validCachedTokenSkipsTheRefresh() {
        link.setCachedAccessToken("at-cached");
        link.setTokenExpiresAt(NOW.plusSeconds(300));

        assertThat(service.getCloudAccessToken(TENANT_ID)).isEqualTo("at-cached");
        assertThat(presented).isEmpty();
    }

    private String decryptStoredRefreshToken() {
        return ReflectionTestUtils.invokeMethod(service, "decrypt", link.getEncryptedRefreshToken());
    }
}
