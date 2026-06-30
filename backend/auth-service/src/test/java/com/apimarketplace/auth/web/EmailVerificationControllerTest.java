package com.apimarketplace.auth.web;

import com.apimarketplace.auth.domain.User;
import com.apimarketplace.auth.repository.UserRepository;
import com.apimarketplace.auth.service.EmailVerificationService;
import com.apimarketplace.auth.service.UserResolutionService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("EmailVerificationController")
class EmailVerificationControllerTest {

    @Mock
    private EmailVerificationService emailVerificationService;

    @Mock
    private UserRepository userRepository;

    @Mock
    private UserResolutionService userResolutionService;

    @InjectMocks
    private EmailVerificationController controller;

    @Test
    @DisplayName("CE send-code is a verified no-op for already verified local users")
    void ceSendCodeIsVerifiedNoopForAlreadyVerifiedLocalUsers() {
        User user = verifiedUser();
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(emailVerificationService.isEmailCodeFlowEnabled()).thenReturn(false);

        ResponseEntity<?> response = controller.sendCode(1L);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(body(response))
                .containsEntry("verified", true)
                .containsEntry("message", "Email verification is not required in Community Edition");
        verify(emailVerificationService, never()).sendCode(user);
    }

    @Test
    @DisplayName("CE send-code rejects stale unverified local rows without sending mail")
    void ceSendCodeRejectsStaleUnverifiedLocalRowsWithoutSendingMail() {
        User user = verifiedUser();
        user.setEmailVerified(false);
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(emailVerificationService.isEmailCodeFlowEnabled()).thenReturn(false);

        ResponseEntity<?> response = controller.sendCode(1L);

        assertThat(response.getStatusCode().value()).isEqualTo(410);
        assertThat(body(response))
                .containsEntry("verified", false)
                .containsEntry("error", "email_verification_disabled");
        verify(emailVerificationService, never()).sendCode(user);
    }

    @Test
    @DisplayName("CE verify-code is a verified no-op for already verified local users")
    void ceVerifyCodeIsVerifiedNoopForAlreadyVerifiedLocalUsers() {
        User user = verifiedUser();
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(emailVerificationService.isEmailCodeFlowEnabled()).thenReturn(false);

        ResponseEntity<?> response = controller.verifyCode(1L, Map.of("code", "123456"));

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(body(response))
                .containsEntry("verified", true)
                .containsEntry("message", "Email verification is not required in Community Edition");
        verify(emailVerificationService, never()).verifyCode(user, "123456");
        verify(userResolutionService, never()).attributeCreditsIfEligible(user);
    }

    @Test
    @DisplayName("CE verify-code rejects stale unverified local rows without verifying code")
    void ceVerifyCodeRejectsStaleUnverifiedLocalRowsWithoutVerifyingCode() {
        User user = verifiedUser();
        user.setEmailVerified(false);
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(emailVerificationService.isEmailCodeFlowEnabled()).thenReturn(false);

        ResponseEntity<?> response = controller.verifyCode(1L, Map.of("code", "123456"));

        assertThat(response.getStatusCode().value()).isEqualTo(410);
        assertThat(body(response))
                .containsEntry("verified", false)
                .containsEntry("error", "email_verification_disabled");
        verify(emailVerificationService, never()).verifyCode(user, "123456");
        verify(userResolutionService, never()).attributeCreditsIfEligible(user);
    }

    private User verifiedUser() {
        User user = new User();
        user.setId(1L);
        user.setEmail("ce-user@example.test");
        user.setProviderId("local:ce-user@example.test");
        user.setEmailVerified(true);
        return user;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> body(ResponseEntity<?> response) {
        return (Map<String, Object>) response.getBody();
    }
}
