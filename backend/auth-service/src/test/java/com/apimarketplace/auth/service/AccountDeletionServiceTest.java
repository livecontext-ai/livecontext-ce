package com.apimarketplace.auth.service;

import com.apimarketplace.auth.domain.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("AccountDeletionService")
class AccountDeletionServiceTest {

    @Mock private UserService userService;
    @Mock private AccountPurgeScheduler purgeScheduler;

    private AccountDeletionService service;

    @BeforeEach
    void setUp() {
        TestAccountPolicy policy = new TestAccountPolicy("signup\\.canary(\\+[a-z0-9-]+)?@example\\.test");
        service = new AccountDeletionService(userService, purgeScheduler, policy);
        when(userService.deactivateUser(any())).thenAnswer(inv -> {
            User u = inv.getArgument(0);
            u.setEnabled(false);
            u.setDeactivatedAt(LocalDateTime.now());
            return u;
        });
    }

    private static User user(String email) {
        User u = new User();
        u.setId(42L);
        u.setEmail(email);
        u.setEnabled(true);
        return u;
    }

    @Test
    @DisplayName("a real account is only deactivated: the purge waits for the grace period")
    void realAccountIsOnlyScheduled() {
        User real = user("jane.doe@example.test");

        assertThat(service.requestDeletion(real)).isEqualTo(AccountDeletionService.Outcome.SCHEDULED);

        verify(userService).deactivateUser(real);
        verify(purgeScheduler, never()).purgeAccount(any());
    }

    @Test
    @DisplayName("a test account is deactivated, then purged right away by the same purge")
    void testAccountIsPurgedNow() {
        User canary = user("signup.canary+signup@example.test");
        when(purgeScheduler.purgeAccount(canary)).thenReturn(true);

        assertThat(service.requestDeletion(canary)).isEqualTo(AccountDeletionService.Outcome.PURGED);

        verify(userService).deactivateUser(canary);
        verify(purgeScheduler).purgeAccount(canary);
    }

    @Test
    @DisplayName("a failed immediate purge surfaces to the caller instead of answering success")
    void failedPurgeSurfaces() {
        User canary = user("signup.canary@example.test");
        when(purgeScheduler.purgeAccount(canary)).thenThrow(new IllegalStateException("Keycloak down"));

        assertThatThrownBy(() -> service.requestDeletion(canary)).hasMessageContaining("Keycloak down");
    }

    @Test
    @DisplayName("a declined immediate purge surfaces too: a test account must never stay behind silently")
    void declinedPurgeSurfaces() {
        User canary = user("signup.canary@example.test");
        when(purgeScheduler.purgeAccount(canary)).thenReturn(false);

        assertThatThrownBy(() -> service.requestDeletion(canary))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("declined");
    }
}
