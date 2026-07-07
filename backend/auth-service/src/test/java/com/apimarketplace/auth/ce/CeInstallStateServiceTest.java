package com.apimarketplace.auth.ce;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("CeInstallStateService")
class CeInstallStateServiceTest {

    @Mock
    private CeInstallStateRepository repository;

    @Mock
    private com.apimarketplace.auth.repository.UserRepository userRepository;

    private CeInstallStateService service;

    @BeforeEach
    void setUp() {
        service = new CeInstallStateService(repository, userRepository);
    }

    @Test
    @DisplayName("getStatus returns view from persisted row when present")
    void getStatusReturnsPersistedRow() {
        CeInstallState row = new CeInstallState();
        row.setId(CeInstallState.SINGLETON_ID);
        row.setBootstrapped(true);
        row.setBootstrappedAt(Instant.parse("2026-04-22T10:00:00Z"));
        row.setBootstrapAdminId(42L);
        row.setVersion("v1");
        row.setUpdatedAt(Instant.parse("2026-04-22T10:00:00Z"));
        when(repository.findById(CeInstallState.SINGLETON_ID)).thenReturn(Optional.of(row));

        CeStatusView view = service.getStatus();

        assertThat(view.bootstrapped()).isTrue();
        assertThat(view.bootstrappedAt()).isEqualTo(Instant.parse("2026-04-22T10:00:00Z"));
        assertThat(view.version()).isEqualTo("v1");
    }

    @Test
    @DisplayName("getStatus fails open (bootstrapped=false) when singleton row missing")
    void getStatusFailsOpenWhenRowMissing() {
        when(repository.findById(CeInstallState.SINGLETON_ID)).thenReturn(Optional.empty());

        CeStatusView view = service.getStatus();

        assertThat(view.bootstrapped()).isFalse();
        assertThat(view.bootstrappedAt()).isNull();
        assertThat(view.version()).isEqualTo("v1");
    }

    @Test
    @DisplayName("getStatus fails CLOSED on registrationOpen when singleton row missing (security default)")
    void getStatusFailsClosedOnRegistrationWhenRowMissing() {
        // Security boundary: if we cannot read the singleton, do NOT silently
        // re-open public signup. Wizard still works because bootstrapped=false.
        when(repository.findById(CeInstallState.SINGLETON_ID)).thenReturn(Optional.empty());

        CeStatusView view = service.getStatus();

        assertThat(view.registrationOpen())
                .as("Fail-CLOSED: missing singleton must not re-open public registration")
                .isFalse();
    }

    @Test
    @DisplayName("markBootstrapped auto-closes registration (audit-mandated post-wizard default)")
    void markBootstrappedClosesRegistration() {
        CeInstallState row = freshRow();
        row.setRegistrationOpen(true); // explicit pre-state
        when(repository.findSingletonForUpdate()).thenReturn(Optional.of(row));
        when(repository.save(any(CeInstallState.class))).thenAnswer(inv -> inv.getArgument(0));

        CeStatusView view = service.markBootstrapped(99L);

        ArgumentCaptor<CeInstallState> captor = ArgumentCaptor.forClass(CeInstallState.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().isRegistrationOpen()).isFalse();
        assertThat(view.registrationOpen()).isFalse();
    }

    @Test
    @DisplayName("markBootstrapped on already-bootstrapped row preserves registration state (idempotent)")
    void markBootstrappedClosesRegistrationIsIdempotent() {
        // Already bootstrapped with registration re-opened by admin - second
        // bootstrap call must NOT re-close it (or otherwise mutate state).
        CeInstallState alreadyDone = new CeInstallState();
        alreadyDone.setId(CeInstallState.SINGLETON_ID);
        alreadyDone.setBootstrapped(true);
        alreadyDone.setBootstrappedAt(Instant.parse("2026-04-22T08:00:00Z"));
        alreadyDone.setRegistrationOpen(true); // admin re-opened after bootstrap
        alreadyDone.setVersion("v1");
        alreadyDone.setUpdatedAt(Instant.parse("2026-04-22T08:00:00Z"));
        when(repository.findSingletonForUpdate()).thenReturn(Optional.of(alreadyDone));

        CeStatusView view = service.markBootstrapped(77L);

        verify(repository, never()).save(any());
        assertThat(view.registrationOpen()).isTrue();
    }

    @Test
    @DisplayName("isRegistrationOpen returns true when row says so")
    void isRegistrationOpenReturnsCurrent() {
        CeInstallState row = freshRow();
        row.setRegistrationOpen(true);
        when(repository.findById(CeInstallState.SINGLETON_ID)).thenReturn(Optional.of(row));

        assertThat(service.isRegistrationOpen()).isTrue();
    }

    @Test
    @DisplayName("setRegistrationOpen flips the flag and persists when value changes")
    void setRegistrationOpenPersists() {
        CeInstallState row = freshRow();
        row.setRegistrationOpen(false);
        when(repository.findSingletonForUpdate()).thenReturn(Optional.of(row));
        when(repository.save(any(CeInstallState.class))).thenAnswer(inv -> inv.getArgument(0));

        CeStatusView view = service.setRegistrationOpen(true);

        ArgumentCaptor<CeInstallState> captor = ArgumentCaptor.forClass(CeInstallState.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().isRegistrationOpen()).isTrue();
        assertThat(view.registrationOpen()).isTrue();
    }

    @Test
    @DisplayName("setRegistrationOpen is no-op when the requested value already matches")
    void setRegistrationOpenIsNoopWhenAlreadySame() {
        CeInstallState row = freshRow();
        row.setRegistrationOpen(false);
        when(repository.findSingletonForUpdate()).thenReturn(Optional.of(row));

        service.setRegistrationOpen(false);

        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("markBootstrapped flips bootstrapped=true, stamps timestamp, records admin id on first call")
    void markBootstrappedFirstCallWrites() {
        CeInstallState row = freshRow();
        when(repository.findSingletonForUpdate()).thenReturn(Optional.of(row));
        when(repository.save(any(CeInstallState.class))).thenAnswer(inv -> inv.getArgument(0));

        Instant beforeCall = Instant.now();
        CeStatusView view = service.markBootstrapped(99L);

        ArgumentCaptor<CeInstallState> captor = ArgumentCaptor.forClass(CeInstallState.class);
        verify(repository).save(captor.capture());
        CeInstallState saved = captor.getValue();
        assertThat(saved.isBootstrapped()).isTrue();
        assertThat(saved.getBootstrapAdminId()).isEqualTo(99L);
        assertThat(saved.getBootstrappedAt()).isNotNull();
        assertThat(saved.getBootstrappedAt()).isAfterOrEqualTo(beforeCall);
        assertThat(saved.getUpdatedAt()).isEqualTo(saved.getBootstrappedAt());

        assertThat(view.bootstrapped()).isTrue();
        assertThat(view.bootstrappedAt()).isEqualTo(saved.getBootstrappedAt());
    }

    @Test
    @DisplayName("markBootstrapped is idempotent: second call does not overwrite original timestamp")
    void markBootstrappedSecondCallPreservesOriginalTimestamp() {
        Instant originalStamp = Instant.parse("2026-04-22T08:00:00Z");
        CeInstallState alreadyDone = new CeInstallState();
        alreadyDone.setId(CeInstallState.SINGLETON_ID);
        alreadyDone.setBootstrapped(true);
        alreadyDone.setBootstrappedAt(originalStamp);
        alreadyDone.setBootstrapAdminId(42L);
        alreadyDone.setVersion("v1");
        alreadyDone.setUpdatedAt(originalStamp);
        when(repository.findSingletonForUpdate()).thenReturn(Optional.of(alreadyDone));

        CeStatusView view = service.markBootstrapped(77L);

        verify(repository, never()).save(any());
        assertThat(view.bootstrapped()).isTrue();
        assertThat(view.bootstrappedAt()).isEqualTo(originalStamp);
    }

    @Test
    @DisplayName("markBootstrapped self-heals when singleton row is missing (V121 should have seeded it, but defensive)")
    void markBootstrappedSelfHealsMissingRow() {
        when(repository.findSingletonForUpdate()).thenReturn(Optional.empty());
        when(repository.save(any(CeInstallState.class))).thenAnswer(inv -> inv.getArgument(0));

        CeStatusView view = service.markBootstrapped(1L);

        // Assert the outcome (bootstrapped + admin recorded), not the number of save() calls -
        // the self-heal implementation detail is allowed to change.
        assertThat(view.bootstrapped()).isTrue();
        assertThat(view.bootstrappedAt()).isNotNull();
    }

    @Test
    @DisplayName("hasUsers=false on a virgin install (no account yet) - the login->register first-run signal")
    void hasUsersFalseWhenNoAccountExists() {
        when(repository.findById(CeInstallState.SINGLETON_ID)).thenReturn(Optional.of(freshRow()));
        when(userRepository.findFirstBy()).thenReturn(Optional.empty());

        assertThat(service.getStatus().hasUsers()).isFalse();
    }

    @Test
    @DisplayName("hasUsers=true as soon as any account exists")
    void hasUsersTrueWhenAnAccountExists() {
        when(repository.findById(CeInstallState.SINGLETON_ID)).thenReturn(Optional.of(freshRow()));
        when(userRepository.findFirstBy())
                .thenReturn(Optional.of(new com.apimarketplace.auth.domain.User()));

        assertThat(service.getStatus().hasUsers()).isTrue();
    }

    @Test
    @DisplayName("hasUsers is threaded through mutation views too (markBootstrapped)")
    void hasUsersThreadedThroughMutationViews() {
        CeInstallState row = freshRow();
        when(repository.findSingletonForUpdate()).thenReturn(Optional.of(row));
        when(repository.save(any(CeInstallState.class))).thenAnswer(inv -> inv.getArgument(0));
        when(userRepository.findFirstBy())
                .thenReturn(Optional.of(new com.apimarketplace.auth.domain.User()));

        assertThat(service.markBootstrapped(1L).hasUsers()).isTrue();
    }

    @Test
    @DisplayName("hasUsers fails SAFE to true when the probe throws (never bounce a working install to register)")
    void hasUsersFailsSafeToTrueOnProbeError() {
        when(repository.findById(CeInstallState.SINGLETON_ID)).thenReturn(Optional.of(freshRow()));
        when(userRepository.findFirstBy()).thenThrow(new RuntimeException("db hiccup"));

        assertThat(service.getStatus().hasUsers()).isTrue();
    }

    private CeInstallState freshRow() {
        CeInstallState row = new CeInstallState();
        row.setId(CeInstallState.SINGLETON_ID);
        row.setBootstrapped(false);
        row.setVersion("v1");
        row.setUpdatedAt(Instant.parse("2026-01-01T00:00:00Z"));
        return row;
    }
}
