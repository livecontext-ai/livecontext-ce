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
}
