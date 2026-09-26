package com.apimarketplace.auth.service;

import com.apimarketplace.auth.domain.EmailVerificationCode;
import com.apimarketplace.auth.domain.User;
import com.apimarketplace.auth.repository.EmailVerificationCodeRepository;
import com.apimarketplace.auth.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.util.ReflectionTestUtils;

import jakarta.mail.internet.MimeMessage;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("EmailVerificationService Tests")
class EmailVerificationServiceTest {

    @Mock
    private EmailVerificationCodeRepository codeRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private JavaMailSender mailSender;

    @Mock
    private MimeMessage mimeMessage;

    @Mock
    private KeycloakAdminEmailVerifier kcAdminVerifier;

    @Mock
    private com.apimarketplace.auth.lifecycle.UserLifecycleContextService lifecycleContext;

    @InjectMocks
    private EmailVerificationService emailVerificationService;

    private User testUser;

    @BeforeEach
    void setUp() {
        testUser = new User();
        testUser.setId(1L);
        testUser.setEmail("test@example.com");
        testUser.setProviderId("kc-uuid-123");

        ReflectionTestUtils.setField(emailVerificationService, "mailFrom", "noreply@livecontext.io");
        ReflectionTestUtils.setField(emailVerificationService, "mailFromName", "LiveContext");
        ReflectionTestUtils.setField(emailVerificationService, "authMode", "keycloak");
        ReflectionTestUtils.setField(emailVerificationService, "mailConsoleFallbackEnabled", false);
        // Mockito @InjectMocks performs constructor injection but does not always
        // field-inject additional @Autowired(required=false) fields. Force it here
        // so the kcAdminVerifier mock is reachable in cloud-mode tests.
        ReflectionTestUtils.setField(emailVerificationService, "kcAdminVerifier", kcAdminVerifier);
        ReflectionTestUtils.setField(emailVerificationService, "lifecycleContext", lifecycleContext);
    }

    @Nested
    @DisplayName("sendCode")
    class SendCode {

        @Test
        @DisplayName("should generate 6-digit code and send email")
        void shouldGenerateCodeAndSendEmail() {
            when(codeRepository.countByEmailAndCreatedAtAfter(eq("test@example.com"), any(LocalDateTime.class)))
                    .thenReturn(0L);
            when(mailSender.createMimeMessage()).thenReturn(mimeMessage);
            when(codeRepository.save(any(EmailVerificationCode.class)))
                    .thenAnswer(invocation -> invocation.getArgument(0));

            EmailVerificationCode result = emailVerificationService.sendCode(testUser);

            assertThat(result).isNotNull();
            assertThat(result.getCode()).hasSize(6);
            assertThat(result.getCode()).matches("\\d{6}");
            assertThat(result.getUserId()).isEqualTo(1L);
            assertThat(result.getEmail()).isEqualTo("test@example.com");
            assertThat(result.isVerified()).isFalse();
            assertThat(result.getExpiresAt()).isAfter(LocalDateTime.now());

            verify(codeRepository).deleteByUserIdAndVerifiedFalse(1L);
            verify(codeRepository).save(any(EmailVerificationCode.class));
            verify(mailSender).send(any(MimeMessage.class));
        }

        @Test
        @DisplayName("should enforce 60-second cooldown between sends")
        void shouldEnforce60SecondCooldown() {
            EmailVerificationCode recentCode = new EmailVerificationCode(1L, "test@example.com", "111111",
                    LocalDateTime.now().plusMinutes(10));
            recentCode.setCreatedAt(LocalDateTime.now().minusSeconds(30));
            when(codeRepository.findTopByUserIdAndVerifiedFalseOrderByCreatedAtDesc(1L))
                    .thenReturn(Optional.of(recentCode));

            assertThatThrownBy(() -> emailVerificationService.sendCode(testUser))
                    .isInstanceOf(EmailVerificationService.RateLimitException.class)
                    .hasMessageContaining("Please wait");

            verify(codeRepository, never()).save(any());
            verify(mailSender, never()).send(any(MimeMessage.class));
        }

        @Test
        @DisplayName("should rate limit after 5 sends per hour")
        void shouldRateLimitAfter5SendsPerHour() {
            when(codeRepository.countByEmailAndCreatedAtAfter(eq("test@example.com"), any(LocalDateTime.class)))
                    .thenReturn(5L);

            assertThatThrownBy(() -> emailVerificationService.sendCode(testUser))
                    .isInstanceOf(EmailVerificationService.RateLimitException.class)
                    .hasMessageContaining("Too many verification codes");

            verify(codeRepository, never()).save(any());
            verify(mailSender, never()).send(any(MimeMessage.class));
        }

        @Test
        @DisplayName("should invalidate old codes before creating new one")
        void shouldInvalidateOldCodes() {
            when(codeRepository.countByEmailAndCreatedAtAfter(eq("test@example.com"), any(LocalDateTime.class)))
                    .thenReturn(0L);
            when(mailSender.createMimeMessage()).thenReturn(mimeMessage);
            when(codeRepository.save(any(EmailVerificationCode.class)))
                    .thenAnswer(invocation -> invocation.getArgument(0));

            emailVerificationService.sendCode(testUser);

            verify(codeRepository).deleteByUserIdAndVerifiedFalse(1L);

            ArgumentCaptor<EmailVerificationCode> captor = ArgumentCaptor.forClass(EmailVerificationCode.class);
            verify(codeRepository).save(captor.capture());
            assertThat(captor.getValue().isVerified()).isFalse();
        }

        @Test
        @DisplayName("CE embedded mode disables code generation and mail delivery")
        void ceEmbeddedModeDisablesCodeGenerationAndMailDelivery() {
            ReflectionTestUtils.setField(emailVerificationService, "authMode", "embedded");

            assertThatThrownBy(() -> emailVerificationService.sendCode(testUser))
                    .isInstanceOf(EmailVerificationService.EmailCodeFlowDisabledException.class)
                    .hasMessageContaining("disabled");

            verifyNoInteractions(codeRepository, mailSender);
        }

        @Test
        @DisplayName("should throw when user has no email")
        void shouldThrowWhenNoEmail() {
            testUser.setEmail(null);

            assertThatThrownBy(() -> emailVerificationService.sendCode(testUser))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("no email");
        }
    }

    @Nested
    @DisplayName("verifyCode")
    class VerifyCode {

        @Test
        @DisplayName("cloud mode marks email verified via KeycloakAdminEmailVerifier")
        void cloudModeMarksEmailVerifiedViaKeycloakAdmin() {
            EmailVerificationCode code = new EmailVerificationCode(1L, "test@example.com", "123456",
                    LocalDateTime.now().plusMinutes(10));
            when(codeRepository.findTopByUserIdAndVerifiedFalseOrderByCreatedAtDesc(1L))
                    .thenReturn(Optional.of(code));
            when(codeRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
            when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

            emailVerificationService.verifyCode(testUser, "123456");

            assertThat(code.isVerified()).isTrue();
            assertThat(testUser.isEmailVerified()).isTrue();
            verify(codeRepository).save(code);
            verify(userRepository).save(testUser);
            verify(kcAdminVerifier).markEmailVerified("kc-uuid-123");
        }

        private void stubValidCode() {
            EmailVerificationCode code = new EmailVerificationCode(1L, "test@example.com", "123456",
                    LocalDateTime.now().plusMinutes(10));
            when(codeRepository.findTopByUserIdAndVerifiedFalseOrderByCreatedAtDesc(1L))
                    .thenReturn(Optional.of(code));
            when(codeRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
            when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));
        }

        @Test
        @DisplayName("the unverified -> verified transition sends user.signed_up through the write-once guard")
        void verificationTransitionEmitsSignedUpOnce() {
            stubValidCode();
            testUser.setEmailVerified(false);

            emailVerificationService.verifyCode(testUser, "123456");

            verify(lifecycleContext, times(1)).recordSignup(1L);
            verifyNoMoreInteractions(lifecycleContext);
        }

        @Test
        @DisplayName("Regression (double welcome): two verification transitions on one account both go through the guard, never around it")
        void repeatedTransitionCannotDoubleEmit() {
            stubValidCode();
            testUser.setEmailVerified(false);
            User staleCopy = new User();
            staleCopy.setId(1L);
            staleCopy.setEmail("test@example.com");
            staleCopy.setProviderId("kc-uuid-123");
            staleCopy.setEmailVerified(false);
            EmailVerificationCode again = new EmailVerificationCode(1L, "test@example.com", "123456",
                    LocalDateTime.now().plusMinutes(10));
            when(codeRepository.findTopByUserIdAndVerifiedFalseOrderByCreatedAtDesc(1L))
                    .thenReturn(Optional.of(new EmailVerificationCode(1L, "test@example.com", "123456",
                            LocalDateTime.now().plusMinutes(10))), Optional.of(again));

            emailVerificationService.verifyCode(testUser, "123456");
            emailVerificationService.verifyCode(staleCopy, "123456");

            // Both saw wasVerified == false; only the guard's write-once stamp keeps one event.
            verify(lifecycleContext, times(2)).recordSignup(1L);
            verifyNoMoreInteractions(lifecycleContext);
        }

        @Test
        @DisplayName("an account that was already verified emits no second user.signed_up")
        void alreadyVerifiedEmitsNothing() {
            stubValidCode();
            testUser.setEmailVerified(true);

            emailVerificationService.verifyCode(testUser, "123456");

            verifyNoInteractions(lifecycleContext);
        }

        @Test
        @DisplayName("a wrong code verifies nothing and emits nothing")
        void wrongCodeEmitsNothing() {
            EmailVerificationCode code = new EmailVerificationCode(1L, "test@example.com", "123456",
                    LocalDateTime.now().plusMinutes(10));
            when(codeRepository.findTopByUserIdAndVerifiedFalseOrderByCreatedAtDesc(1L))
                    .thenReturn(Optional.of(code));

            assertThatThrownBy(() -> emailVerificationService.verifyCode(testUser, "000000"))
                    .isInstanceOf(EmailVerificationService.InvalidCodeException.class);

            verifyNoInteractions(lifecycleContext);
        }

        @Test
        @DisplayName("CE embedded mode disables code verification without Keycloak or repository calls")
        void embeddedModeDisablesCodeVerificationWithoutKeycloakOrRepositoryCalls() {
            ReflectionTestUtils.setField(emailVerificationService, "authMode", "embedded");

            assertThatThrownBy(() -> emailVerificationService.verifyCode(testUser, "123456"))
                    .isInstanceOf(EmailVerificationService.EmailCodeFlowDisabledException.class)
                    .hasMessageContaining("disabled");

            verifyNoInteractions(codeRepository, userRepository, kcAdminVerifier);
        }

        @Test
        @DisplayName("should fail with wrong code and increment attempts")
        void shouldFailWithWrongCodeAndIncrementAttempts() {
            EmailVerificationCode code = new EmailVerificationCode(1L, "test@example.com", "123456",
                    LocalDateTime.now().plusMinutes(10));
            when(codeRepository.findTopByUserIdAndVerifiedFalseOrderByCreatedAtDesc(1L))
                    .thenReturn(Optional.of(code));
            when(codeRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

            assertThatThrownBy(() -> emailVerificationService.verifyCode(testUser, "999999"))
                    .isInstanceOf(EmailVerificationService.InvalidCodeException.class)
                    .hasMessageContaining("Invalid verification code");

            assertThat(code.getAttempts()).isEqualTo(1);
            verify(codeRepository).save(code);
        }

        @Test
        @DisplayName("should fail after max attempts exhausted")
        void shouldFailAfterMaxAttempts() {
            EmailVerificationCode code = new EmailVerificationCode(1L, "test@example.com", "123456",
                    LocalDateTime.now().plusMinutes(10));
            code.setAttempts(3);
            code.setMaxAttempts(3);
            when(codeRepository.findTopByUserIdAndVerifiedFalseOrderByCreatedAtDesc(1L))
                    .thenReturn(Optional.of(code));

            assertThatThrownBy(() -> emailVerificationService.verifyCode(testUser, "123456"))
                    .isInstanceOf(EmailVerificationService.TooManyAttemptsException.class)
                    .hasMessageContaining("Too many failed attempts");
        }

        @Test
        @DisplayName("should fail with expired code")
        void shouldFailWithExpiredCode() {
            EmailVerificationCode code = new EmailVerificationCode(1L, "test@example.com", "123456",
                    LocalDateTime.now().minusMinutes(1));
            when(codeRepository.findTopByUserIdAndVerifiedFalseOrderByCreatedAtDesc(1L))
                    .thenReturn(Optional.of(code));

            assertThatThrownBy(() -> emailVerificationService.verifyCode(testUser, "123456"))
                    .isInstanceOf(EmailVerificationService.CodeExpiredException.class)
                    .hasMessageContaining("expired");
        }

        @Test
        @DisplayName("should fail when no code found")
        void shouldFailWhenNoCodeFound() {
            when(codeRepository.findTopByUserIdAndVerifiedFalseOrderByCreatedAtDesc(1L))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() -> emailVerificationService.verifyCode(testUser, "123456"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("No verification code found");
        }
    }

    @Nested
    @DisplayName("isEmailVerified")
    class IsEmailVerified {

        @Test
        @DisplayName("CE embedded mode reads local user state without Keycloak")
        void embeddedModeReadsLocalUserStateWithoutKeycloak() {
            ReflectionTestUtils.setField(emailVerificationService, "authMode", "embedded");
            testUser.setEmailVerified(true);
            when(userRepository.findByProviderId("kc-uuid-123")).thenReturn(Optional.of(testUser));

            boolean verified = emailVerificationService.isEmailVerified("kc-uuid-123");

            assertThat(verified).isTrue();
            verifyNoInteractions(kcAdminVerifier);
        }

        @Test
        @DisplayName("cloud mode delegates to KeycloakAdminEmailVerifier")
        void cloudModeDelegatesToKeycloakAdminVerifier() {
            when(kcAdminVerifier.isEmailVerified("kc-uuid-123")).thenReturn(true);

            boolean verified = emailVerificationService.isEmailVerified("kc-uuid-123");

            assertThat(verified).isTrue();
            verify(kcAdminVerifier).isEmailVerified("kc-uuid-123");
        }

        @Test
        @DisplayName("falls back to local user when KeycloakAdminEmailVerifier is absent")
        void fallsBackToLocalUserWhenKcVerifierAbsent() {
            ReflectionTestUtils.setField(emailVerificationService, "kcAdminVerifier", null);
            testUser.setEmailVerified(true);
            when(userRepository.findByProviderId("kc-uuid-123")).thenReturn(Optional.of(testUser));

            boolean verified = emailVerificationService.isEmailVerified("kc-uuid-123");

            assertThat(verified).isTrue();
        }
    }

    /**
     * Real transaction semantics: a recording {@link org.springframework.transaction.PlatformTransactionManager}
     * drives the verification transaction and the signup claim's own transaction, and the real
     * lifecycle services sit behind {@code recordSignup}. Only the database and Resend are mocked.
     */
    @Nested
    @DisplayName("user.signed_up at verification, with real transaction semantics")
    class SignupAfterCommit {

        private final java.util.List<String> events = new java.util.ArrayList<>();
        private final java.util.List<Runnable> workerQueue = new java.util.ArrayList<>();
        private RecordingTxManager tm;
        private com.apimarketplace.auth.lifecycle.ResendClient resend;
        private EmailVerificationService service;

        @BeforeEach
        void wire() {
            tm = new RecordingTxManager(events);
            resend = mock(com.apimarketplace.auth.lifecycle.ResendClient.class);
            when(resend.isActive()).thenReturn(true);
            // The Resend worker runs later, on its own thread: queue the task, run it after the commit.
            lenient().when(resend.submit(any())).thenAnswer(inv -> {
                events.add("submit");
                workerQueue.add(inv.getArgument(0));
                return true;
            });
            com.apimarketplace.auth.lifecycle.LifecycleEmailService emails =
                    new com.apimarketplace.auth.lifecycle.LifecycleEmailService(resend, userRepository,
                            mock(com.apimarketplace.auth.repository.UserOnboardingRepository.class), null, tm);
            com.apimarketplace.auth.lifecycle.UserLifecycleContextService realContext =
                    new com.apimarketplace.auth.lifecycle.UserLifecycleContextService(userRepository,
                            mock(com.apimarketplace.auth.repository.UserAcquisitionRepository.class), emails, tm);
            service = new EmailVerificationService(codeRepository, userRepository, mailSender);
            ReflectionTestUtils.setField(service, "authMode", "keycloak");
            ReflectionTestUtils.setField(service, "kcAdminVerifier", kcAdminVerifier);
            ReflectionTestUtils.setField(service, "lifecycleContext", realContext);

            EmailVerificationCode code = new EmailVerificationCode(1L, "test@example.com", "123456",
                    LocalDateTime.now().plusMinutes(10));
            when(codeRepository.findTopByUserIdAndVerifiedFalseOrderByCreatedAtDesc(1L)).thenReturn(Optional.of(code));
            lenient().when(codeRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
            lenient().when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
            lenient().when(userRepository.findById(1L)).thenReturn(Optional.of(testUser));
            testUser.setEmailVerified(false);
        }

        private void verifyInTransaction() {
            new org.springframework.transaction.support.TransactionTemplate(tm)
                    .executeWithoutResult(status -> service.verifyCode(testUser, "123456"));
        }

        private void runWorker() {
            events.add("worker");
            java.util.List<Runnable> tasks = new java.util.ArrayList<>(workerQueue);
            workerQueue.clear();
            tasks.forEach(Runnable::run);
        }

        @Test
        @DisplayName("Regression (verification undone): a failing signup claim never rolls back the verification")
        void failingClaimNeverRollsBackVerification() {
            when(userRepository.markSignupEmittedIfFirst(eq(1L), any())).thenAnswer(inv -> {
                events.add("claim");
                throw new org.springframework.dao.DataAccessResourceFailureException("statement timeout");
            });

            verifyInTransaction();
            runWorker();

            // The verification committed; the worker read the user (read-only), then claimed in
            // its own transaction, whose rollback touched nothing else.
            assertThat(events).containsExactly("begin", "commit", "submit",
                    "worker", "begin-read", "commit", "begin", "claim", "rollback");
            assertThat(testUser.isEmailVerified()).isTrue();
            verify(kcAdminVerifier).markEmailVerified("kc-uuid-123");
            verify(resend, never()).sendEvent(anyString(), anyString(), anyMap());
        }

        @Test
        @DisplayName("happy path: user.signed_up is claimed and sent on the worker, after the verification committed")
        void signupSentAfterCommit() {
            when(userRepository.markSignupEmittedIfFirst(eq(1L), any())).thenAnswer(inv -> {
                events.add("claim");
                return 1;
            });
            when(resend.sendEvent(anyString(), anyString(), anyMap())).thenReturn(true); // delivered: the claim is kept

            verifyInTransaction();
            assertThat(events).containsExactly("begin", "commit", "submit");
            verify(resend, never()).sendEvent(anyString(), anyString(), anyMap());

            runWorker();

            assertThat(events).containsExactly("begin", "commit", "submit",
                    "worker", "begin-read", "commit", "begin", "claim", "commit");
            org.mockito.InOrder order = inOrder(resend);
            order.verify(resend).upsertContact(eq("test@example.com"), any(), anyMap());
            order.verify(resend).sendEvent(eq("test@example.com"),
                    eq(com.apimarketplace.auth.lifecycle.LifecycleEvents.USER_SIGNED_UP), anyMap());
        }

        @Test
        @DisplayName("a verification whose transaction rolls back never claims nor sends user.signed_up")
        void rolledBackVerificationSendsNothing() {
            // recordSignup was reached, then the enclosing transaction fails: only the commit
            // boundary can hold the claim and the event back.
            assertThatThrownBy(() -> new org.springframework.transaction.support.TransactionTemplate(tm)
                    .executeWithoutResult(status -> {
                        service.verifyCode(testUser, "123456");
                        throw new IllegalStateException("enclosing work failed");
                    })).isInstanceOf(IllegalStateException.class);
            runWorker();

            assertThat(events).containsExactly("begin", "rollback", "worker");
            verify(userRepository, never()).markSignupEmittedIfFirst(anyLong(), any());
            verify(resend, never()).sendEvent(anyString(), anyString(), anyMap());
        }
    }

    /** Minimal real transaction manager: tracks one transaction per test and records begin/commit/rollback. */
    static final class RecordingTxManager
            extends org.springframework.transaction.support.AbstractPlatformTransactionManager {
        private final java.util.List<String> events;
        private Object current;

        RecordingTxManager(java.util.List<String> events) {
            this.events = events;
        }

        private static final class Tx {
            final boolean existing;

            Tx(boolean existing) {
                this.existing = existing;
            }
        }

        @Override
        protected Object doGetTransaction() {
            return new Tx(current != null);
        }

        @Override
        protected boolean isExistingTransaction(Object transaction) {
            return ((Tx) transaction).existing;
        }

        @Override
        protected void doBegin(Object transaction, org.springframework.transaction.TransactionDefinition definition) {
            current = transaction;
            events.add(definition.isReadOnly() ? "begin-read" : "begin");
        }

        @Override
        protected Object doSuspend(Object transaction) {
            Object suspended = current;
            current = null;
            return suspended;
        }

        @Override
        protected void doResume(Object transaction, Object suspendedResources) {
            current = suspendedResources;
        }

        @Override
        protected void doCommit(org.springframework.transaction.support.DefaultTransactionStatus status) {
            events.add("commit");
        }

        @Override
        protected void doRollback(org.springframework.transaction.support.DefaultTransactionStatus status) {
            events.add("rollback");
        }

        @Override
        protected void doSetRollbackOnly(org.springframework.transaction.support.DefaultTransactionStatus status) {
            events.add("rollback-only");
        }

        @Override
        protected void doCleanupAfterCompletion(Object transaction) {
            current = null;
        }
    }
}
