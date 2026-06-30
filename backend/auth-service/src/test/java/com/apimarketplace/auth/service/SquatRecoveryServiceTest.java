package com.apimarketplace.auth.service;

import com.apimarketplace.auth.domain.AuthProvider;
import com.apimarketplace.auth.domain.User;
import com.apimarketplace.auth.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("SquatRecoveryService")
class SquatRecoveryServiceTest {

    @Mock private SquatRecoveryTokenService tokenService;
    @Mock private SquatRecoveryMailer mailer;
    @Mock private UserRepository userRepository;

    private SquatRecoveryService service;

    private static final Long VICTIM_ID = 42L;
    private static final Long ATTACKER_ID = 99L;
    private static final UUID INSTALL = UUID.fromString("11111111-2222-3333-4444-555555555555");

    @BeforeEach
    void setUp() {
        service = new SquatRecoveryService(tokenService, mailer, userRepository, 5);
    }

    private User victim() {
        return new User("victim", "victim@test.io", AuthProvider.KEYCLOAK, "kc-uuid");
    }

    @Test
    @DisplayName("happy path mints a token and ships the recovery email to the victim")
    void happy_path() {
        when(userRepository.findById(VICTIM_ID)).thenReturn(Optional.of(victim()));
        when(tokenService.mint(INSTALL, VICTIM_ID)).thenReturn("the-token");

        service.onSquatDetected(new CeLinkSquatDetectedEvent(VICTIM_ID, ATTACKER_ID, INSTALL));

        verify(mailer).sendRecoveryEmail("victim@test.io", "the-token");
    }

    @Test
    @DisplayName("skipsEmailWhenVictimMissing - orphan event must not crash listener")
    void skips_when_victim_missing() {
        when(userRepository.findById(VICTIM_ID)).thenReturn(Optional.empty());

        service.onSquatDetected(new CeLinkSquatDetectedEvent(VICTIM_ID, ATTACKER_ID, INSTALL));

        verifyNoInteractions(tokenService);
        verifyNoInteractions(mailer);
    }

    @Test
    @DisplayName("rateLimitHonoredAcrossWindow - first 5 attempts ship, the 6th is dropped silently")
    void rate_limit_drops_excess() {
        when(userRepository.findById(VICTIM_ID)).thenReturn(Optional.of(victim()));
        when(tokenService.mint(eq(INSTALL), eq(VICTIM_ID))).thenReturn("t1", "t2", "t3", "t4", "t5");

        for (int i = 0; i < 5; i++) {
            service.onSquatDetected(new CeLinkSquatDetectedEvent(VICTIM_ID, ATTACKER_ID, INSTALL));
        }
        // 6th must be dropped - rate limit hit.
        service.onSquatDetected(new CeLinkSquatDetectedEvent(VICTIM_ID, ATTACKER_ID, INSTALL));

        verify(mailer, org.mockito.Mockito.times(5)).sendRecoveryEmail(eq("victim@test.io"), any());
    }

    @Test
    @DisplayName("rate limit is per-victim - a different victim is not affected by another's spam")
    void rate_limit_is_per_victim() {
        // Exhaust victim 1.
        for (int i = 0; i < 5; i++) {
            assertThat(service.consumeRateLimit(1L)).isTrue();
        }
        assertThat(service.consumeRateLimit(1L)).isFalse();
        // Victim 2 unaffected.
        assertThat(service.consumeRateLimit(2L)).isTrue();
    }

    @Test
    @DisplayName("onSquatDetected swallows any unexpected RuntimeException - listener thread safety net")
    void on_squat_detected_swallows() {
        when(userRepository.findById(VICTIM_ID)).thenThrow(new RuntimeException("DB hiccup"));

        // Must not throw.
        service.onSquatDetected(new CeLinkSquatDetectedEvent(VICTIM_ID, ATTACKER_ID, INSTALL));
        verify(mailer, never()).sendRecoveryEmail(any(), any());
    }
}
