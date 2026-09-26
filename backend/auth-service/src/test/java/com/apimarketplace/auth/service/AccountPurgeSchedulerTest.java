package com.apimarketplace.auth.service;

import com.apimarketplace.auth.domain.User;
import com.apimarketplace.auth.repository.UserOnboardingRepository;
import com.apimarketplace.auth.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The nightly account purge is the one place in this service that destroys data irreversibly, and
 * the value it passes to the selection query is what decides WHO. Nothing used to cover that: the
 * cutoff could be computed from the wrong constant, or handed to the query in the wrong place, and
 * every other test in the module stayed green while every deletion silently moved by weeks.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AccountPurgeScheduler")
class AccountPurgeSchedulerTest {

    @Mock private UserRepository userRepository;
    @Mock private UserOnboardingRepository onboardingRepository;
    @Mock private AccountPurgeService purgeService;
    @Mock private AccountDeactivationMailer mailer;

    private AccountPurgeScheduler scheduler;

    @BeforeEach
    void setUp() {
        scheduler = new AccountPurgeScheduler(userRepository, onboardingRepository, purgeService, mailer);
        lenient().when(onboardingRepository.findByUserId(anyLong())).thenReturn(Optional.empty());
    }

    private User user(long id, LocalDateTime deactivatedAt, LocalDateTime lastLoginAt) {
        User u = new User();
        u.setId(id);
        u.setEmail("user" + id + "@test.local");
        u.setFirstName("User" + id);
        u.setEnabled(false);
        u.setDeactivatedAt(deactivatedAt);
        u.setLastLoginAt(lastLoginAt);
        return u;
    }

    @Test
    @DisplayName("selects on exactly the grace period it advertises")
    void selectsOnTheAdvertisedGracePeriod() {
        when(userRepository.findAccountsPastGracePeriod(any())).thenReturn(List.of());

        LocalDateTime before = LocalDateTime.now();
        scheduler.purgeExpiredAccounts();
        LocalDateTime after = LocalDateTime.now();

        ArgumentCaptor<LocalDateTime> cutoff = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(userRepository).findAccountsPastGracePeriod(cutoff.capture());

        // The date this service reports to the user is deactivatedAt + ACCOUNT_GRACE_PERIOD_DAYS.
        // If the cutoff here is computed from anything else, everyone is deleted on a day other
        // than the one they were given, and no other test in the module notices.
        assertThat(cutoff.getValue())
                .isBetween(before.minusDays(AccountPurgeScheduler.GRACE_PERIOD_DAYS),
                           after.minusDays(AccountPurgeScheduler.GRACE_PERIOD_DAYS));
    }

    @Test
    @DisplayName("purges each expired account and confirms it by e-mail")
    void purgesAndConfirms() {
        User u = user(7L, LocalDateTime.now().minusDays(40), null);
        when(userRepository.findAccountsPastGracePeriod(any())).thenReturn(List.of(u));
        when(purgeService.purgeUser(7L)).thenReturn(true);

        scheduler.purgeExpiredAccounts();

        verify(purgeService).purgeUser(7L);
        // The address has to be captured before the purge deletes the row it lives on.
        verify(mailer).sendPurgeConfirmationEmail(eq("user7@test.local"), any());
    }

    @Test
    @DisplayName("lifecycle: a purged account's Resend contact is deleted, a declined purge keeps it")
    void purgeDeletesLifecycleContact() {
        com.apimarketplace.auth.lifecycle.LifecycleEmailService lifecycleEmails =
                org.mockito.Mockito.mock(com.apimarketplace.auth.lifecycle.LifecycleEmailService.class);
        org.springframework.test.util.ReflectionTestUtils.setField(scheduler, "lifecycleEmails", lifecycleEmails);
        User purged = user(7L, LocalDateTime.now().minusDays(40), null);
        User restored = user(8L, LocalDateTime.now().minusDays(40), null);
        when(userRepository.findAccountsPastGracePeriod(any())).thenReturn(List.of(purged, restored));
        when(purgeService.purgeUser(7L)).thenReturn(true);
        when(purgeService.purgeUser(8L)).thenReturn(false);

        scheduler.purgeExpiredAccounts();

        verify(lifecycleEmails).deleteContact("user7@test.local");
        verify(lifecycleEmails, never()).deleteContact("user8@test.local");
    }

    @Test
    @DisplayName("does not claim a deletion happened when the purge declined it")
    void noConfirmationWhenNothingWasPurged() {
        User u = user(7L, LocalDateTime.now().minusDays(40), null);
        when(userRepository.findAccountsPastGracePeriod(any())).thenReturn(List.of(u));
        when(purgeService.purgeUser(7L)).thenReturn(false);

        scheduler.purgeExpiredAccounts();

        // purgeUser returns false when the account was restored between selection and purge.
        // Telling that person their data is gone would be both false and alarming.
        verify(mailer, never()).sendPurgeConfirmationEmail(any(), any());
    }

    @Test
    @DisplayName("one failing account does not abandon the rest of the run")
    void oneFailureDoesNotStopTheRun() {
        User first = user(1L, LocalDateTime.now().minusDays(40), null);
        User second = user(2L, LocalDateTime.now().minusDays(40), null);
        when(userRepository.findAccountsPastGracePeriod(any())).thenReturn(List.of(first, second));
        when(purgeService.purgeUser(1L)).thenThrow(new IllegalStateException("keycloak unreachable"));
        when(purgeService.purgeUser(2L)).thenReturn(true);

        scheduler.purgeExpiredAccounts();

        // A Keycloak outage on one account must not silently park every later one for a day.
        verify(purgeService).purgeUser(2L);
        verify(mailer).sendPurgeConfirmationEmail(eq("user2@test.local"), any());
        verify(mailer, never()).sendPurgeConfirmationEmail(eq("user1@test.local"), any());
    }

    @Test
    @DisplayName("still purges an account whose owner signed in after asking for deletion")
    void purgesAnAccountThatSignedInAgain() {
        // The inverse used to hold, and it made the reported deletion date a lie: the interstitial's
        // own status request refreshes lastLoginAt, so simply looking at the screen deferred the
        // deletion while the screen kept showing the original date.
        LocalDateTime deactivated = LocalDateTime.now().minusDays(40);
        User u = user(9L, deactivated, LocalDateTime.now());
        when(userRepository.findAccountsPastGracePeriod(any())).thenReturn(List.of(u));
        when(purgeService.purgeUser(9L)).thenReturn(true);

        scheduler.purgeExpiredAccounts();

        verify(purgeService).purgeUser(9L);
    }

    @Test
    @DisplayName("the nightly pass is guarded by ShedLock, so only one auth replica runs it")
    void nightlyPassIsGuardedByShedLock() throws Exception {
        // Without it every replica started the same purge at 03:00 (seen twice per night in the
        // prod log), each holding a transaction on the same user rows.
        var lock = AccountPurgeScheduler.class.getMethod("purgeExpiredAccounts")
                .getAnnotation(net.javacrumbs.shedlock.spring.annotation.SchedulerLock.class);
        assertThat(lock).as("@SchedulerLock on purgeExpiredAccounts").isNotNull();
        assertThat(lock.name()).isEqualTo("account_purge");
    }
}
