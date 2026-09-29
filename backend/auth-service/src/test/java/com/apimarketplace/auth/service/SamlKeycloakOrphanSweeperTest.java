package com.apimarketplace.auth.service;

import com.apimarketplace.auth.domain.OrganizationSamlConnection;
import com.apimarketplace.auth.domain.User;
import com.apimarketplace.auth.repository.OrganizationSamlConnectionRepository;
import com.apimarketplace.auth.repository.UserRepository;
import com.apimarketplace.auth.service.KeycloakSamlIdentityProviderClient.BrokeredUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("SamlKeycloakOrphanSweeper")
class SamlKeycloakOrphanSweeperTest {

    private static final String ALIAS = "org-aaaaaaaabbbbccccddddeeeeeeeeeeee-saml";
    private static final String ORPHAN_ALIAS = "org-11111111222233334444555555555555-saml";
    private static final Instant NOW = Instant.parse("2026-09-27T12:00:00Z");
    private static final long OLD = NOW.minus(Duration.ofHours(2)).toEpochMilli();
    private static final long FRESH = NOW.minus(Duration.ofMinutes(5)).toEpochMilli();

    @Mock private KeycloakSamlIdentityProviderClient keycloakClient;
    @Mock private UserRepository userRepository;
    @Mock private OrganizationSamlConnectionRepository samlRepository;

    private SamlKeycloakOrphanSweeper sweeper;

    @BeforeEach
    void setUp() {
        sweeper = new SamlKeycloakOrphanSweeper(keycloakClient, userRepository, samlRepository, Duration.ofMinutes(30), true);
        sweeper.setClock(Clock.fixed(NOW, ZoneOffset.UTC));
        lenient().when(samlRepository.findByIdpAlias(ALIAS)).thenReturn(Optional.of(new OrganizationSamlConnection()));
        lenient().when(userRepository.findByProviderId(anyString())).thenReturn(Optional.empty());
    }

    @Test
    @DisplayName("an old SAML-only Keycloak user with no app account is deleted; a fresh one is left for the login in progress")
    void deletesOldOrphanKeepsFreshOne() {
        when(keycloakClient.listOrganizationSamlAliases()).thenReturn(List.of(ALIAS));
        when(keycloakClient.listUsersLinkedTo(ALIAS, 0, SamlKeycloakOrphanSweeper.PAGE_SIZE))
                .thenReturn(List.of(new BrokeredUser("old", OLD), new BrokeredUser("fresh", FRESH),
                        new BrokeredUser("undated", null)));
        when(keycloakClient.deleteIfOnlyBrokeredBy("old", ALIAS)).thenReturn(true);

        SamlKeycloakOrphanSweeper.Result r = sweeper.sweep();

        assertThat(r.usersDeleted()).isEqualTo(1);
        assertThat(r.usersKept()).isEqualTo(2);
        verify(keycloakClient, never()).deleteIfOnlyBrokeredBy(eq("fresh"), anyString());
        verify(keycloakClient, never()).deleteIfOnlyBrokeredBy(eq("undated"), anyString());
    }

    @Test
    @DisplayName("a Keycloak user an app account points at is never touched, however old")
    void keepsUsersWithAnAppAccount() {
        when(keycloakClient.listOrganizationSamlAliases()).thenReturn(List.of(ALIAS));
        when(keycloakClient.listUsersLinkedTo(ALIAS, 0, SamlKeycloakOrphanSweeper.PAGE_SIZE))
                .thenReturn(List.of(new BrokeredUser("member", OLD)));
        when(userRepository.findByProviderId("member")).thenReturn(Optional.of(new User()));

        assertThat(sweeper.sweep().usersDeleted()).isZero();
        verify(keycloakClient, never()).deleteIfOnlyBrokeredBy(anyString(), anyString());
    }

    @Test
    @DisplayName("a user the client refuses to delete (credential or other link) is counted as kept")
    void userWithCredentialOrOtherLinkIsKept() {
        when(keycloakClient.listOrganizationSamlAliases()).thenReturn(List.of(ALIAS));
        when(keycloakClient.listUsersLinkedTo(ALIAS, 0, SamlKeycloakOrphanSweeper.PAGE_SIZE))
                .thenReturn(List.of(new BrokeredUser("linked", OLD)));
        when(keycloakClient.deleteIfOnlyBrokeredBy("linked", ALIAS)).thenReturn(false);

        SamlKeycloakOrphanSweeper.Result r = sweeper.sweep();

        assertThat(r.usersDeleted()).isZero();
        assertThat(r.usersKept()).isEqualTo(1);
    }

    @Test
    @DisplayName("pages through a full page, collects first and deletes after, so no user is skipped")
    void pagesThroughAllUsers() {
        when(keycloakClient.listOrganizationSamlAliases()).thenReturn(List.of(ALIAS));
        List<BrokeredUser> full = new ArrayList<>();
        for (int i = 0; i < SamlKeycloakOrphanSweeper.PAGE_SIZE; i++) full.add(new BrokeredUser("u" + i, OLD));
        when(keycloakClient.listUsersLinkedTo(ALIAS, 0, SamlKeycloakOrphanSweeper.PAGE_SIZE)).thenReturn(full);
        when(keycloakClient.listUsersLinkedTo(ALIAS, SamlKeycloakOrphanSweeper.PAGE_SIZE, SamlKeycloakOrphanSweeper.PAGE_SIZE))
                .thenReturn(List.of(new BrokeredUser("last", OLD)));
        when(keycloakClient.deleteIfOnlyBrokeredBy(anyString(), eq(ALIAS))).thenReturn(true);

        assertThat(sweeper.sweep().usersDeleted()).isEqualTo(SamlKeycloakOrphanSweeper.PAGE_SIZE + 1);
        verify(keycloakClient, times(2)).listUsersLinkedTo(eq(ALIAS), anyInt(), anyInt());
    }

    @Test
    @DisplayName("one failing delete is counted and the sweep carries on")
    void oneFailureDoesNotStopTheSweep() {
        when(keycloakClient.listOrganizationSamlAliases()).thenReturn(List.of(ALIAS));
        when(keycloakClient.listUsersLinkedTo(ALIAS, 0, SamlKeycloakOrphanSweeper.PAGE_SIZE))
                .thenReturn(List.of(new BrokeredUser("a", OLD), new BrokeredUser("b", OLD)));
        when(keycloakClient.deleteIfOnlyBrokeredBy("a", ALIAS)).thenThrow(new IllegalStateException("KC 503"));
        when(keycloakClient.deleteIfOnlyBrokeredBy("b", ALIAS)).thenReturn(true);

        SamlKeycloakOrphanSweeper.Result r = sweeper.sweep();

        assertThat(r.errors()).isEqualTo(1);
        assertThat(r.usersDeleted()).isEqualTo(1);
    }

    @Test
    @DisplayName("an IdP with no connection row is deleted only when seen orphaned on two consecutive runs")
    void orphanIdpDeletedOnSecondSighting() {
        when(keycloakClient.listOrganizationSamlAliases()).thenReturn(List.of(ALIAS, ORPHAN_ALIAS));
        when(keycloakClient.listUsersLinkedTo(anyString(), anyInt(), anyInt())).thenReturn(List.of());
        when(samlRepository.findByIdpAlias(ORPHAN_ALIAS)).thenReturn(Optional.empty());

        assertThat(sweeper.sweep().idpsDeleted()).isZero();
        verify(keycloakClient, never()).delete(anyString());

        assertThat(sweeper.sweep().idpsDeleted()).isEqualTo(1);
        verify(keycloakClient).delete(ORPHAN_ALIAS);
        verify(keycloakClient, never()).delete(ALIAS);
    }

    @Test
    @DisplayName("an IdP whose row appears between two runs (a save in progress) is not deleted")
    void idpThatGotItsRowIsKept() {
        when(keycloakClient.listOrganizationSamlAliases()).thenReturn(List.of(ORPHAN_ALIAS));
        when(keycloakClient.listUsersLinkedTo(anyString(), anyInt(), anyInt())).thenReturn(List.of());
        when(samlRepository.findByIdpAlias(ORPHAN_ALIAS))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(new OrganizationSamlConnection()));

        sweeper.sweep();
        sweeper.sweep();

        verify(keycloakClient, never()).delete(anyString());
    }

    @Test
    @DisplayName("disabled by configuration: the scheduled run does nothing; a Keycloak outage never escapes the run")
    void disabledAndFailureSafe() {
        new SamlKeycloakOrphanSweeper(keycloakClient, userRepository, samlRepository, Duration.ofMinutes(30), false)
                .scheduledSweep();
        verify(keycloakClient, never()).listOrganizationSamlAliases();

        when(keycloakClient.listOrganizationSamlAliases()).thenThrow(new IllegalStateException("KC down"));
        sweeper.scheduledSweep();
        verify(keycloakClient, never()).deleteIfOnlyBrokeredBy(any(), any());
    }
}
