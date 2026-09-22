package com.apimarketplace.auth.service;

import com.apimarketplace.auth.domain.PasswordResetToken;
import com.apimarketplace.auth.domain.User;
import com.apimarketplace.auth.repository.PasswordResetTokenRepository;
import com.apimarketplace.auth.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Password reset for embedded auth. This is the flow that hands an account back
 * to whoever holds a link, so the tests here are about the guarantees, not the
 * happy path: the form must not disclose which addresses are registered, a token
 * must work exactly once even under a race, and the raw token must never be
 * persisted.
 */
class PasswordResetServiceTest {

    private UserRepository userRepository;
    private PasswordResetTokenRepository tokenRepository;
    private PasswordAuthService passwordAuthService;
    private PasswordResetMailer mailer;
    private PasswordResetService service;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        tokenRepository = mock(PasswordResetTokenRepository.class);
        passwordAuthService = mock(PasswordAuthService.class);
        mailer = mock(PasswordResetMailer.class);
        service = new PasswordResetService(userRepository, tokenRepository,
                passwordAuthService, mailer);
        // The default is "this caller claimed the token"; the race test overrides it.
        when(tokenRepository.markUsed(anyLong(), any(LocalDateTime.class))).thenReturn(1);
    }

    private User userWithPassword() {
        User u = new User();
        u.setId(7L);
        u.setEmail("owner@example.com");
        u.setFirstName("Ada");
        u.setPasswordHash("$2a$12$alreadyhashed");
        u.setEnabled(true);
        return u;
    }

    /** An independent SHA-256, so the production hash is checked and not echoed. */
    private static String sha256Hex(String value) throws Exception {
        return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    }

    /** The raw token as it went into the e-mail. */
    private String capturedMailedToken() {
        ArgumentCaptor<String> mailed = ArgumentCaptor.forClass(String.class);
        verify(mailer).dispatchResetEmail(anyString(), anyString(), mailed.capture(),
                eq(PasswordResetService.TOKEN_TTL_MINUTES), eq(7L));
        return mailed.getValue();
    }

    private PasswordResetToken savedRow() {
        ArgumentCaptor<PasswordResetToken> row = ArgumentCaptor.forClass(PasswordResetToken.class);
        verify(tokenRepository).save(row.capture());
        return row.getValue();
    }

    @Nested
    @DisplayName("requestReset must not reveal whether an account exists")
    class NoEnumeration {

        @Test
        @DisplayName("an unknown address issues no token and sends no mail")
        void unknownAddressIsSilent() {
            when(userRepository.findByEmail("nobody@example.com")).thenReturn(Optional.empty());

            service.requestReset("nobody@example.com", "10.0.0.1");

            verify(tokenRepository, never()).save(any());
            verifyNoInteractions(mailer);
        }

        @Test
        @DisplayName("an account with no local password issues no token either (it would disclose the sign-in method)")
        void oauthOnlyAccountIsSilent() {
            User oauthOnly = new User();
            oauthOnly.setId(9L);
            oauthOnly.setEmail("oauth@example.com");
            oauthOnly.setPasswordHash(null);
            oauthOnly.setEnabled(true);
            when(userRepository.findByEmail("oauth@example.com")).thenReturn(Optional.of(oauthOnly));

            service.requestReset("oauth@example.com", "10.0.0.1");

            verify(tokenRepository, never()).save(any());
            verifyNoInteractions(mailer);
        }

        @Test
        @DisplayName("a DISABLED account gets no reset mail: a suspended account must not be handed "
                + "back to whoever guessed its address")
        void disabledAccountIsSilent() {
            User disabled = userWithPassword();
            disabled.setEnabled(false);
            when(userRepository.findByEmail(anyString())).thenReturn(Optional.of(disabled));

            service.requestReset("owner@example.com", "10.0.0.1");

            verify(tokenRepository, never()).save(any());
            verifyNoInteractions(mailer);
        }

        @Test
        @DisplayName("the address is lower-cased and trimmed, so casing cannot dodge the lookup")
        void addressIsNormalised() {
            when(userRepository.findByEmail("owner@example.com")).thenReturn(Optional.of(userWithPassword()));

            service.requestReset("  Owner@Example.COM  ", "10.0.0.1");

            verify(userRepository).findByEmail("owner@example.com");
            verify(tokenRepository).save(any());
        }

        @Test
        @DisplayName("the send is DETACHED, so a known address does not pay an SMTP round trip that "
                + "an unknown one avoids (the timing would answer what the body will not)")
        void sendIsDispatchedNotInline() {
            when(userRepository.findByEmail(anyString())).thenReturn(Optional.of(userWithPassword()));

            service.requestReset("owner@example.com", "10.0.0.1");

            verify(mailer).dispatchResetEmail(eq("owner@example.com"), anyString(), anyString(),
                    eq(PasswordResetService.TOKEN_TTL_MINUTES), eq(7L));
            // Inline sending is what the mitigation removes; it must not come back.
            verify(mailer, never()).sendResetEmail(anyString(), anyString(), anyString(), anyInt());
        }
    }

    @Nested
    @DisplayName("the mail waits for the transaction to commit")
    class DispatchWaitsForCommit {

        @org.junit.jupiter.api.BeforeEach
        void openASynchronizationScope() {
            // What Spring has active inside @Transactional. Without it the service
            // has nothing to register on and sends immediately, which is the
            // fallback path every other test in this file exercises.
            org.springframework.transaction.support.TransactionSynchronizationManager
                    .initSynchronization();
        }

        @org.junit.jupiter.api.AfterEach
        void closeIt() {
            org.springframework.transaction.support.TransactionSynchronizationManager
                    .clearSynchronization();
        }

        private void fireAfterCommit() {
            for (org.springframework.transaction.support.TransactionSynchronization sync
                    : org.springframework.transaction.support.TransactionSynchronizationManager
                            .getSynchronizations()) {
                sync.afterCommit();
            }
        }

        /**
         * What Spring calls on a ROLLBACK: afterCompletion with a non-committed
         * status, and no afterCommit. Firing it is the whole point, because a
         * synchronization that dispatched from here would send a link whose row
         * never existed, and a test that fires nothing cannot tell the two apart.
         */
        private void fireRollback() {
            for (org.springframework.transaction.support.TransactionSynchronization sync
                    : org.springframework.transaction.support.TransactionSynchronizationManager
                            .getSynchronizations()) {
                sync.afterCompletion(
                        org.springframework.transaction.support.TransactionSynchronization.STATUS_ROLLED_BACK);
            }
        }

        @Test
        @DisplayName("nothing is sent while the transaction is still open")
        void notSentBeforeCommit() {
            when(userRepository.findByEmail(anyString())).thenReturn(Optional.of(userWithPassword()));

            service.requestReset("owner@example.com", "10.0.0.1");

            verifyNoInteractions(mailer);
        }

        @Test
        @DisplayName("it goes out once the transaction commits")
        void sentAfterCommit() {
            when(userRepository.findByEmail(anyString())).thenReturn(Optional.of(userWithPassword()));

            service.requestReset("owner@example.com", "10.0.0.1");
            fireAfterCommit();

            verify(mailer).dispatchResetEmail(eq("owner@example.com"), anyString(), anyString(),
                    eq(PasswordResetService.TOKEN_TTL_MINUTES), eq(7L));
        }

        @Test
        @DisplayName("a transaction that ROLLS BACK sends nothing, so nobody gets a link whose token "
                + "was never written")
        void rollbackSendsNothing() {
            when(userRepository.findByEmail(anyString())).thenReturn(Optional.of(userWithPassword()));

            service.requestReset("owner@example.com", "10.0.0.1");
            // The transaction ROLLS BACK: afterCompletion fires with a
            // non-committed status and afterCommit never does, which is what a
            // deadlock, a lost connection or a rollback-only outer transaction
            // looks like. Firing it matters: a version of this test that ended the
            // transaction by doing nothing was indistinguishable from
            // notSentBeforeCommit above, and a synchronization that dispatched
            // from afterCompletion passed it.
            fireRollback();

            verifyNoInteractions(mailer);
        }

        @Test
        @DisplayName("and a rollback AFTER a commit-dispatch cannot un-send it, so the two callbacks "
                + "are not interchangeable")
        void commitThenCompletionSendsExactlyOnce() {
            when(userRepository.findByEmail(anyString())).thenReturn(Optional.of(userWithPassword()));

            service.requestReset("owner@example.com", "10.0.0.1");
            fireAfterCommit();
            fireRollback();

            verify(mailer, times(1)).dispatchResetEmail(anyString(), anyString(), anyString(),
                    eq(PasswordResetService.TOKEN_TTL_MINUTES), eq(7L));
        }
    }

    @Nested
    @DisplayName("the stored token")
    class TokenStorage {

        @Test
        @DisplayName("is persisted as the SHA-256 OF THE MAILED VALUE, so a link can actually be redeemed")
        void storedHashIsTheHashOfTheMailedToken() throws Exception {
            when(userRepository.findByEmail(anyString())).thenReturn(Optional.of(userWithPassword()));

            service.requestReset("owner@example.com", "10.0.0.1");

            String mailedToken = capturedMailedToken();
            String stored = savedRow().getTokenHash();

            assertThat(stored).hasSize(64).matches("[0-9a-f]{64}");
            // The decisive assertion, and the one that was missing: the two sides
            // agree. Storing the hash of some OTHER random value would satisfy
            // "64 hex chars" and "differs from the mailed value" and still leave
            // every real link unredeemable.
            assertThat(stored).isEqualTo(sha256Hex(mailedToken));
            assertThat(stored).isNotEqualTo(mailedToken);
            assertThat(mailedToken).isNotBlank();
        }

        @Test
        @DisplayName("is UNGUESSABLE: two issuances never produce the same token, and it carries the "
                + "full 32 bytes of entropy")
        void tokenIsRandomAndFullLength() {
            when(userRepository.findByEmail(anyString())).thenReturn(Optional.of(userWithPassword()));

            java.util.Set<String> mailed = new java.util.HashSet<>();
            java.util.Set<String> stored = new java.util.HashSet<>();
            for (int i = 0; i < 25; i++) {
                org.mockito.Mockito.reset(mailer, tokenRepository);
                when(tokenRepository.markUsed(anyLong(), any(LocalDateTime.class))).thenReturn(1);
                service.requestReset("owner@example.com", "10.0.0.1");
                mailed.add(capturedMailedToken());
                stored.add(savedRow().getTokenHash());
            }

            // Delete secureRandom.nextBytes(...) and every one of these is the
            // same all-zero value: one link would open every account on the
            // install. Measured: without this test, 44 reset tests stayed green.
            assertThat(mailed).as("the mailed token repeated").hasSize(25);
            assertThat(stored).as("the stored hash repeated").hasSize(25);

            // Base64url without padding: 32 bytes is 43 characters. A shorter
            // token is a weaker one, so the size is pinned as a NUMBER and not
            // derived from the constant under test.
            assertThat(PasswordResetService.TOKEN_BYTES).isEqualTo(32);
            for (String token : mailed) {
                assertThat(token).hasSize(43).matches("[A-Za-z0-9_-]{43}");
            }
        }

        @Test
        @DisplayName("expires an hour out, not immediately: a token born expired refuses every link")
        void expiryIsInTheFuture() {
            when(userRepository.findByEmail(anyString())).thenReturn(Optional.of(userWithPassword()));

            service.requestReset("owner@example.com", "10.0.0.1");

            PasswordResetToken row = savedRow();
            assertThat(row.getExpiresAt()).isAfter(LocalDateTime.now().plusMinutes(50));
            assertThat(row.getExpiresAt()).isBefore(LocalDateTime.now().plusMinutes(70));
        }

        @Test
        @DisplayName("records the caller-supplied IP truncated to the column width, so an over-long "
                + "X-Forwarded-For cannot roll the whole request back")
        void ipIsTruncated() {
            when(userRepository.findByEmail(anyString())).thenReturn(Optional.of(userWithPassword()));

            service.requestReset("owner@example.com", "9".repeat(300));

            // created_ip is VARCHAR(45). Unbounded, this INSERT fails and the user
            // is told a mail is on its way with no token behind it.
            assertThat(savedRow().getCreatedIp()).hasSize(45);
        }

        @Test
        @DisplayName("burns any previous live token, so an older e-mail stops working")
        void issuingInvalidatesPrevious() {
            when(userRepository.findByEmail(anyString())).thenReturn(Optional.of(userWithPassword()));

            service.requestReset("owner@example.com", "10.0.0.1");

            verify(tokenRepository).invalidateLiveTokens(eq(7L), any(LocalDateTime.class));
        }

        @Test
        @DisplayName("the cap is FIVE per hour, as numbers and not as references to themselves")
        void theCapIsFivePerHour() {
            // Deliberately literals. Measured: setting MAX_REQUESTS_PER_WINDOW to
            // 5000 and RATE_WINDOW_MINUTES to 1 removed the rate limit entirely and
            // left every test green, because the case below derives its boundary
            // from the constant under test and the window was matched with any().
            // This is the same self-referential shape the password minimum was
            // already fixed for.
            assertThat(PasswordResetService.MAX_REQUESTS_PER_WINDOW).isEqualTo(5);
            assertThat(PasswordResetService.RATE_WINDOW_MINUTES).isEqualTo(60);
            assertThat(PasswordResetService.TOKEN_TTL_MINUTES).isEqualTo(60);
        }

        @Test
        @DisplayName("counts over a window that really is an hour back, not an arbitrary instant")
        void theWindowIsAnHourWide() {
            when(userRepository.findByEmail(anyString())).thenReturn(Optional.of(userWithPassword()));

            service.requestReset("owner@example.com", "10.0.0.1");

            ArgumentCaptor<LocalDateTime> since = ArgumentCaptor.forClass(LocalDateTime.class);
            verify(tokenRepository).countByUserSince(eq(7L), since.capture());
            assertThat(since.getValue()).isBefore(LocalDateTime.now().minusMinutes(59));
            assertThat(since.getValue()).isAfter(LocalDateTime.now().minusMinutes(61));
        }

        @Test
        @DisplayName("is refused once the per-user rate limit is reached, silently")
        void rateLimited() {
            when(userRepository.findByEmail(anyString())).thenReturn(Optional.of(userWithPassword()));
            when(tokenRepository.countByUserSince(eq(7L), any(LocalDateTime.class)))
                    .thenReturn((long) PasswordResetService.MAX_REQUESTS_PER_WINDOW);

            service.requestReset("owner@example.com", "10.0.0.1");

            verify(tokenRepository, never()).save(any());
            verifyNoInteractions(mailer);
        }
    }

    @Nested
    @DisplayName("resetPassword")
    class Redemption {

        private PasswordResetToken liveRow() {
            PasswordResetToken row = new PasswordResetToken();
            row.setId(1L);
            row.setUserId(7L);
            row.setExpiresAt(LocalDateTime.now().plusMinutes(30));
            return row;
        }

        @Test
        @DisplayName("a token issued by requestReset is redeemable with the value that was mailed")
        void issuedTokenRoundTrips() {
            when(userRepository.findByEmail(anyString())).thenReturn(Optional.of(userWithPassword()));
            service.requestReset("owner@example.com", "10.0.0.1");
            String mailedToken = capturedMailedToken();
            String storedHash = savedRow().getTokenHash();

            // The repository answers ONLY for the hash issuance actually stored,
            // so this fails unless the two ends of the flow agree.
            when(tokenRepository.findByTokenHash(storedHash)).thenReturn(Optional.of(liveRow()));

            Long userId = service.resetPassword(mailedToken, "a-good-password");

            assertThat(userId).isEqualTo(7L);
            verify(passwordAuthService).resetPasswordTo(7L, "a-good-password");
        }

        @Test
        @DisplayName("sets the password through PasswordAuthService, the one class that owns the rules")
        void delegatesTheWrite() {
            when(tokenRepository.findByTokenHash(anyString())).thenReturn(Optional.of(liveRow()));

            Long userId = service.resetPassword("raw-token", "a-good-password");

            assertThat(userId).isEqualTo(7L);
            verify(passwordAuthService).resetPasswordTo(7L, "a-good-password");
        }

        @Test
        @DisplayName("claims the token with a CONDITIONAL update and burns anything else pending")
        void singleUse() {
            when(tokenRepository.findByTokenHash(anyString())).thenReturn(Optional.of(liveRow()));

            service.resetPassword("raw-token", "a-good-password");

            // The conditional UPDATE is the single-use guarantee. Stamping the
            // entity and saving it would not be: two concurrent callers both read
            // usedAt as null before either wrote.
            verify(tokenRepository).markUsed(eq(1L), any(LocalDateTime.class));
            verify(tokenRepository).invalidateLiveTokens(eq(7L), any(LocalDateTime.class));
        }

        @Test
        @DisplayName("RACE: the caller that does not win the conditional update is refused, and does "
                + "NOT get to write a password")
        void concurrentRedemptionLoserIsRefused() {
            when(tokenRepository.findByTokenHash(anyString())).thenReturn(Optional.of(liveRow()));
            // What Postgres returns to the transaction that arrived second: it
            // blocked on the row lock, re-checked "used_at IS NULL", matched none.
            when(tokenRepository.markUsed(anyLong(), any(LocalDateTime.class))).thenReturn(0);

            assertThatThrownBy(() -> service.resetPassword("raw-token", "a-good-password"))
                    .isInstanceOf(PasswordResetService.InvalidTokenException.class)
                    .hasMessage(PasswordResetService.INVALID_TOKEN_MESSAGE);

            verify(passwordAuthService, never()).resetPasswordTo(anyLong(), anyString());
        }

        @Test
        @DisplayName("refuses an already-used token")
        void refusesUsed() {
            PasswordResetToken row = liveRow();
            row.setUsedAt(LocalDateTime.now().minusMinutes(1));
            when(tokenRepository.findByTokenHash(anyString())).thenReturn(Optional.of(row));

            assertThatThrownBy(() -> service.resetPassword("raw-token", "a-good-password"))
                    .isInstanceOf(PasswordResetService.InvalidTokenException.class);
            verify(passwordAuthService, never()).resetPasswordTo(anyLong(), anyString());
        }

        @Test
        @DisplayName("refuses an expired token")
        void refusesExpired() {
            PasswordResetToken row = liveRow();
            row.setExpiresAt(LocalDateTime.now().minusSeconds(1));
            when(tokenRepository.findByTokenHash(anyString())).thenReturn(Optional.of(row));

            assertThatThrownBy(() -> service.resetPassword("raw-token", "a-good-password"))
                    .isInstanceOf(PasswordResetService.InvalidTokenException.class);
            verify(passwordAuthService, never()).resetPasswordTo(anyLong(), anyString());
        }

        @Test
        @DisplayName("says exactly the same thing for unknown, spent, expired and blank: telling them "
                + "apart would confirm a token was really issued, and so that the address exists")
        void everyRefusalCarriesTheSameMessage() {
            PasswordResetToken spent = liveRow();
            spent.setUsedAt(LocalDateTime.now().minusMinutes(1));
            PasswordResetToken expired = liveRow();
            expired.setExpiresAt(LocalDateTime.now().minusSeconds(1));

            when(tokenRepository.findByTokenHash(anyString())).thenReturn(Optional.empty());
            String unknown = messageFrom("unknown-token");

            when(tokenRepository.findByTokenHash(anyString())).thenReturn(Optional.of(spent));
            String used = messageFrom("spent-token");

            when(tokenRepository.findByTokenHash(anyString())).thenReturn(Optional.of(expired));
            String stale = messageFrom("expired-token");

            String blank = messageFrom("   ");

            assertThat(unknown)
                    .isEqualTo(used)
                    .isEqualTo(stale)
                    .isEqualTo(blank)
                    .isEqualTo(PasswordResetService.INVALID_TOKEN_MESSAGE);
        }

        private String messageFrom(String token) {
            try {
                service.resetPassword(token, "a-good-password");
                throw new AssertionError("expected a refusal for token " + token);
            } catch (PasswordResetService.InvalidTokenException e) {
                return e.getMessage();
            }
        }

        @Test
        @DisplayName("a rejected password leaves the token usable, so a typo does not cost the link")
        void weakPasswordDoesNotBurnTheToken() {
            doThrow(new IllegalArgumentException("New password must be at least 8 characters"))
                    .when(passwordAuthService).validateNewPassword("short");

            assertThatThrownBy(() -> service.resetPassword("raw-token", "short"))
                    .isInstanceOf(IllegalArgumentException.class);

            // The check runs before the token is even LOOKED UP, so the token is
            // untouched and the answer cannot depend on whether it was genuine.
            // Deliberately not left to a transaction rollback, which a caller
            // outside a transaction would not get.
            verifyNoInteractions(tokenRepository);
            verify(passwordAuthService, never()).resetPasswordTo(anyLong(), anyString());
        }

        @Test
        @DisplayName("a bad password answers the same way for a VALID token as for a bogus one, so "
                + "the endpoint is not a token-validity oracle")
        void aBadPasswordTellsNothingAboutTheToken() {
            doThrow(new IllegalArgumentException("New password must be at least 8 characters"))
                    .when(passwordAuthService).validateNewPassword("short");
            when(tokenRepository.findByTokenHash(anyString())).thenReturn(Optional.of(liveRow()));

            String withRealToken = messageOfIllegalArgument("a-genuine-token");
            when(tokenRepository.findByTokenHash(anyString())).thenReturn(Optional.empty());
            String withBogusToken = messageOfIllegalArgument("not-a-token-at-all");

            assertThat(withRealToken).isEqualTo(withBogusToken);
        }

        private String messageOfIllegalArgument(String token) {
            try {
                service.resetPassword(token, "short");
                throw new AssertionError("expected a refusal");
            } catch (IllegalArgumentException e) {
                return e.getMessage();
            }
        }

        @Test
        @DisplayName("a token whose account is gone OR suspended says the SAME thing as any other "
                + "bad link, instead of confirming which")
        void deletedOrSuspendedAccountDoesNotLeak() {
            when(tokenRepository.findByTokenHash(anyString())).thenReturn(Optional.of(liveRow()));
            // resetPasswordTo throws exactly this for BOTH a missing row and a
            // disabled one, which is why one test covers both: the two are
            // indistinguishable by design, and the suspended-account behaviour
            // itself is pinned in PasswordAuthServiceTest.refusesADisabledAccount.
            doThrow(new IllegalArgumentException("User not found"))
                    .when(passwordAuthService).resetPasswordTo(anyLong(), anyString());

            assertThatThrownBy(() -> service.resetPassword("raw-token", "a-good-password"))
                    .isInstanceOf(PasswordResetService.InvalidTokenException.class)
                    // The controller returns an IllegalArgumentException's message
                    // verbatim, so letting this one through would tell a stranger
                    // holding an old link that the account was deleted.
                    .hasMessage(PasswordResetService.INVALID_TOKEN_MESSAGE);
        }

        @Test
        @DisplayName("a blank token is refused without a repository lookup")
        void blankTokenShortCircuits() {
            assertThatThrownBy(() -> service.resetPassword("  ", "a-good-password"))
                    .isInstanceOf(PasswordResetService.InvalidTokenException.class);
            verifyNoInteractions(tokenRepository);
        }
    }

    /**
     * The transaction boundaries, asserted structurally because these tests
     * construct the service directly and therefore run with no proxy at all.
     *
     * <p>Measured: deleting all three {@code @Transactional} annotations left 58
     * tests green. Every transaction argument in this class rests on them: the
     * atomicity {@code dispatchOnCommit} is built for, the row lock that makes
     * {@code markUsed} single-use safe (the claim and the password write have to
     * be ONE transaction, or a crash between them spends a token without
     * changing a password), and the bulk DELETE in the cleanup.
     */
    @Nested
    @DisplayName("transaction boundaries")
    class Transactions {

        @Test
        @DisplayName("requestReset, resetPassword and cleanupExpiredTokens are each @Transactional")
        void everyEntryPointIsTransactional() throws Exception {
            for (java.lang.reflect.Method method : new java.lang.reflect.Method[] {
                    PasswordResetService.class.getDeclaredMethod("requestReset", String.class, String.class),
                    PasswordResetService.class.getDeclaredMethod("resetPassword", String.class, String.class),
                    PasswordResetService.class.getDeclaredMethod("cleanupExpiredTokens"),
            }) {
                assertThat(method.isAnnotationPresent(
                        org.springframework.transaction.annotation.Transactional.class))
                        .as("%s must run in a transaction", method.getName())
                        .isTrue();
            }
        }

        @Test
        @DisplayName("and PasswordAuthService's two password writes are too, since the reset flow "
                + "calls them inside its own transaction")
        void thePasswordWritesAreTransactional() throws Exception {
            for (java.lang.reflect.Method method : new java.lang.reflect.Method[] {
                    PasswordAuthService.class.getDeclaredMethod("resetPasswordTo", Long.class, String.class),
                    PasswordAuthService.class.getDeclaredMethod("changePassword", Long.class, String.class, String.class),
            }) {
                assertThat(method.isAnnotationPresent(
                        org.springframework.transaction.annotation.Transactional.class))
                        .as("%s must run in a transaction", method.getName())
                        .isTrue();
            }
        }
    }

    /*
     * There is no isMailConfigured case here any more, and it is worth saying why
     * so it does not come back: the check keyed on spring.mail.host, which
     * defaults to "localhost" in auth-service's application.yml AND in the CE
     * monolith's application-ce.yml. It was therefore never blank in any
     * deployment, the accessor answered "configured" always, and the 503 branch
     * it fed was unreachable code that read as a working safeguard. Keying on
     * "is the host a local one" instead would refuse the whole flow to a
     * self-hoster running a real relay on localhost, breaking a working install
     * to be helpful to a broken one.
     */

    /**
     * Grouped for readability, not for discoverability.
     *
     * An earlier version of this comment claimed a plain test method beside
     * &#64;Nested classes is NOT discovered by {@code -Dtest=PasswordResetServiceTest}.
     * That is wrong, and the way it looks true is worth knowing: surefire prints
     * {@code Tests run: 0 ... in PasswordResetServiceTest} for the parent class
     * and files the top-level method's result under the LAST nested class's XML,
     * so reading the per-class console lines makes the method look skipped.
     * Measured on a clean surefire-reports directory: every method in the file
     * ran. Count the total line or the report, never the per-class lines.
     */
    @Nested
    @DisplayName("cleanupExpiredTokens")
    class Cleanup {

        @Test
        @DisplayName("deletes rows whose expiry is a week past, not live ones")
        void cleanupUsesAWeekOldCutoff() {
            when(tokenRepository.deleteExpiredBefore(any(LocalDateTime.class))).thenReturn(3);

            service.cleanupExpiredTokens();

            ArgumentCaptor<LocalDateTime> cutoff = ArgumentCaptor.forClass(LocalDateTime.class);
            verify(tokenRepository, times(1)).deleteExpiredBefore(cutoff.capture());
            assertThat(cutoff.getValue()).isBefore(LocalDateTime.now().minusDays(6));
            assertThat(cutoff.getValue()).isAfter(LocalDateTime.now().minusDays(8));
        }
    }
}
