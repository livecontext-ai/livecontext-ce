package com.apimarketplace.auth.lifecycle;

import com.apimarketplace.auth.repository.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("SignupIpPurgeScheduler - 12-month retention of the signup IP")
class SignupIpPurgeSchedulerTest {

    @Test
    @DisplayName("nulls every IP captured more than 12 calendar months ago")
    void purgesWithTwelveMonthCutoff() {
        UserRepository repository = mock(UserRepository.class);
        Instant now = Instant.parse("2026-09-24T03:30:00Z");
        Instant expectedCutoff = Instant.parse("2025-09-24T03:30:00Z");
        when(repository.purgeSignupIpsCapturedBefore(expectedCutoff)).thenReturn(3);
        SignupIpPurgeScheduler scheduler = new SignupIpPurgeScheduler(repository, Clock.fixed(now, ZoneOffset.UTC));

        int purged = scheduler.purge();

        assertThat(purged).isEqualTo(3);
        verify(repository).purgeSignupIpsCapturedBefore(expectedCutoff);
    }

    @Test
    @DisplayName("the scheduled entry point runs the same purge")
    void scheduledEntryPoint() {
        UserRepository repository = mock(UserRepository.class);
        Instant now = Instant.parse("2026-03-31T00:00:00Z");
        SignupIpPurgeScheduler scheduler = new SignupIpPurgeScheduler(repository, Clock.fixed(now, ZoneOffset.UTC));

        scheduler.purgeExpiredSignupIps();

        verify(repository).purgeSignupIpsCapturedBefore(Instant.parse("2025-03-31T00:00:00Z"));
    }
}
