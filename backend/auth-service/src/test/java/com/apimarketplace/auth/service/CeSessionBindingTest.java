package com.apimarketplace.auth.service;

import com.apimarketplace.auth.domain.AuthProvider;
import com.apimarketplace.auth.domain.RefreshToken;
import com.apimarketplace.auth.domain.User;
import com.apimarketplace.auth.repository.RefreshTokenRepository;
import com.apimarketplace.auth.repository.UserRepository;
import com.apimarketplace.auth.security.JwtTokenProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * CASA LC-015: a CE access token names its login session (the refresh-token row) in {@code sid},
 * and {@link CeSessionRevocationService} answers whether that session may still authenticate.
 */
@DisplayName("CE access tokens are bound to their login session (LC-015)")
class CeSessionBindingTest {

    private static User user() {
        User user = new User("u", "u@example.com", AuthProvider.LOCAL, "p-1");
        user.setId(5L);
        return user;
    }

    private static RefreshToken row(boolean revoked, LocalDateTime revokedAt, LocalDateTime expiresAt) {
        RefreshToken token = new RefreshToken("hash", user(), expiresAt);
        token.setId(7L);
        token.setRevoked(revoked);
        token.setRevokedAt(revokedAt);
        return token;
    }

    @Test
    @DisplayName("generateTokenPair signs the access token with sid = the saved refresh-token row id")
    void accessTokenCarriesSessionId() {
        UserRepository users = mock(UserRepository.class);
        RefreshTokenRepository rows = mock(RefreshTokenRepository.class);
        JwtTokenProvider jwt = mock(JwtTokenProvider.class);
        when(jwt.getRefreshTokenExpirationMs()).thenReturn(60_000L);
        when(rows.save(any(RefreshToken.class))).thenAnswer(inv -> {
            RefreshToken saved = inv.getArgument(0);
            saved.setId(77L);
            return saved;
        });
        when(jwt.generateAccessToken(any(User.class), isNull(), eq("77"))).thenReturn("access-77");

        PasswordAuthService service = new PasswordAuthService(users, rows, jwt);
        assertThat(service.generateTokenPair(user(), "ua", "127.0.0.1").accessToken()).isEqualTo("access-77");
        verify(jwt, times(1)).generateAccessToken(any(User.class), isNull(), eq("77"));
    }

    @Test
    @DisplayName("a live session is active; a revoked, expired, unknown or malformed one is not")
    void sessionStates() {
        RefreshTokenRepository rows = mock(RefreshTokenRepository.class);
        CeSessionRevocationService sessions = new CeSessionRevocationService(rows, 0, System::currentTimeMillis);
        LocalDateTime later = LocalDateTime.now().plusDays(1);

        when(rows.findById(7L)).thenReturn(Optional.of(row(false, null, later)));
        assertThat(sessions.isSessionActive("7")).isTrue();

        CeSessionRevocationService fresh = new CeSessionRevocationService(rows, 0, System::currentTimeMillis);
        when(rows.findById(7L)).thenReturn(Optional.of(row(true, LocalDateTime.now(), later)));
        assertThat(fresh.isSessionActive("7")).as("revoked by logout / password change").isFalse();

        when(rows.findById(7L)).thenReturn(Optional.of(row(false, null, LocalDateTime.now().minusMinutes(1))));
        assertThat(fresh.isSessionActive("7")).as("expired").isFalse();

        when(rows.findById(7L)).thenReturn(Optional.empty());
        assertThat(fresh.isSessionActive("7")).as("cleaned up").isFalse();
        assertThat(fresh.isSessionActive("not-a-number")).isFalse();
        assertThat(fresh.isSessionActive(null)).isFalse();
    }

    @Test
    @DisplayName("rotation grace: a just-rotated session stays valid only while the user holds a live one")
    void rotationGrace() {
        RefreshTokenRepository rows = mock(RefreshTokenRepository.class);
        LocalDateTime later = LocalDateTime.now().plusDays(1);
        when(rows.findById(7L)).thenReturn(Optional.of(row(true, LocalDateTime.now(), later)));

        when(rows.countActiveByUserId(eq(5L), any())).thenReturn(1L);
        assertThat(new CeSessionRevocationService(rows, 30, System::currentTimeMillis).isSessionActive("7"))
                .as("normal rotation: successor exists").isTrue();

        when(rows.countActiveByUserId(eq(5L), any())).thenReturn(0L);
        assertThat(new CeSessionRevocationService(rows, 30, System::currentTimeMillis).isSessionActive("7"))
                .as("logout-everywhere / password change: no live session left").isFalse();
    }

    @Test
    @DisplayName("a positive answer is cached for 5s, then re-read (a revocation lands within 5s)")
    void positiveAnswerCachedBriefly() {
        RefreshTokenRepository rows = mock(RefreshTokenRepository.class);
        AtomicLong now = new AtomicLong(1_000_000L);
        CeSessionRevocationService sessions = new CeSessionRevocationService(rows, 0, now::get);
        LocalDateTime later = LocalDateTime.now().plusDays(1);
        when(rows.findById(anyLong())).thenReturn(Optional.of(row(false, null, later)));
        assertThat(sessions.isSessionActive("7")).isTrue();

        when(rows.findById(anyLong())).thenReturn(Optional.of(row(true, LocalDateTime.now(), later)));
        now.addAndGet(CeSessionRevocationService.CACHE_TTL_MS - 1);
        assertThat(sessions.isSessionActive("7")).isTrue();
        now.addAndGet(2);
        assertThat(sessions.isSessionActive("7")).isFalse();
    }
}
