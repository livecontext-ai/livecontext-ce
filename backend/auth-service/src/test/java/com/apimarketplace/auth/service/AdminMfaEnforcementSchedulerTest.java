package com.apimarketplace.auth.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("AdminMfaEnforcementScheduler")
class AdminMfaEnforcementSchedulerTest {

    @Mock private MfaService mfaService;
    @InjectMocks private AdminMfaEnforcementScheduler scheduler;

    @Test
    @DisplayName("runs the admin two-factor sweep")
    void runsTheSweep() {
        when(mfaService.enforceTotpForAllAdmins()).thenReturn(2);

        scheduler.enforce();

        verify(mfaService).enforceTotpForAllAdmins();
    }

    @Test
    @DisplayName("survives a failed run, so the next interval retries instead of the scheduler dying")
    void survivesAFailedRun() {
        when(mfaService.enforceTotpForAllAdmins()).thenThrow(new IllegalStateException("db down"));

        assertThatCode(() -> scheduler.enforce()).doesNotThrowAnyException();
    }
}
