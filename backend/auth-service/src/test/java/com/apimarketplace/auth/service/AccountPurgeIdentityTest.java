package com.apimarketplace.auth.service;

import com.apimarketplace.auth.domain.Subscription;
import com.apimarketplace.auth.domain.User;
import com.apimarketplace.auth.repository.OrganizationRepository;
import com.apimarketplace.auth.repository.SubscriptionRepository;
import com.apimarketplace.auth.repository.UserRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import org.hibernate.Session;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * "Delete my account" removes the identity too, or does not run at all, and nothing
 * irreversible happens before every database statement has succeeded.
 *
 * <p>The Keycloak delete used to be best-effort: a missing admin secret, or any HTTP failure,
 * logged a warning and the purge carried on. That is the state production was in - the secret
 * was never provisioned on the auth deployment - so the nightly purge would have destroyed a
 * user's data while leaving their identity in the realm. Two bad outcomes at once: their e-mail
 * address stays on file after a deletion advertised as permanent, and signing in again
 * bootstraps them a brand-new account, so the deletion removed their work but not their access.
 *
 * <p>The opposite divergence is just as bad and is why the identity now goes LAST (before the
 * outbox): an identity deleted first, followed by a failing statement, left an account with its
 * rows but no way to sign in and restore it, retried and failing every night.
 *
 * <p>The database side runs through {@code Session.doWork} (one savepoint per statement); the
 * real SQL, its binding and its all-or-nothing rollback are proven on Postgres by
 * {@code AccountPurgePersistenceTest}. Here the doWork is a no-op, so these tests pin ORDER and
 * the abort decisions.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("Account purge - identity removal is mandatory, and last")
class AccountPurgeIdentityTest {

    @Mock private UserRepository userRepository;
    @Mock private OrganizationRepository organizationRepository;
    @Mock private SubscriptionRepository subscriptionRepository;
    @Mock private StripeBillingService stripeBillingService;
    @Mock private RestTemplate restTemplate;
    @Mock private WorkspaceDataPurger workspaceDataPurger;
    @Mock private EntityManager em;
    @Mock private Session session;

    private AccountPurgeService service;
    private User user;

    private static final Long USER_ID = 42L;
    private static final String PROVIDER_ID = "b6cd2ba7-5d6b-49f5-bc9c-ada1dc5f2074";

    @BeforeEach
    void setUp() {
        service = new AccountPurgeService(userRepository, organizationRepository,
                Optional.of(stripeBillingService), restTemplate, workspaceDataPurger,
                subscriptionRepository, TransactionOperations.withoutTransaction());
        ReflectionTestUtils.setField(service, "em", em);
        ReflectionTestUtils.setField(service, "authMode", "keycloak");
        ReflectionTestUtils.setField(service, "kcRealm", "livecontext");
        ReflectionTestUtils.setField(service, "kcClientId", "livecontext-admin-api");
        ReflectionTestUtils.setField(service, "kcServerUrl", "http://kc:8080");
        ReflectionTestUtils.setField(service, "kcClientSecret", "s3cret");

        user = new User();
        user.setId(USER_ID);
        user.setEmail("gone@test.local");
        user.setEnabled(false);
        user.setDeactivatedAt(java.time.LocalDateTime.now().minusDays(40));
        user.setProviderId(PROVIDER_ID);
        lenient().when(em.find(eq(User.class), eq(USER_ID), any(LockModeType.class))).thenReturn(user);
        lenient().when(em.unwrap(Session.class)).thenReturn(session);
        lenient().when(organizationRepository.findByOwnerId(USER_ID)).thenReturn(List.of());
    }

    private void keycloakTokenOk() {
        when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(), eq(Map.class)))
                .thenReturn(ResponseEntity.ok(Map.of("access_token", "t")));
    }

    private void assertNoOutboxRow() {
        verify(workspaceDataPurger, never()).recordUserPurge(anyString());
        verify(workspaceDataPurger, never()).recordOrgPurge(anyString(), anyString());
    }

    // ------------------------------------------------ preflight: nothing may start

    @Test
    @DisplayName("aborts before any step when the Keycloak admin secret is missing")
    void abortsWhenKeycloakSecretMissing() {
        ReflectionTestUtils.setField(service, "kcClientSecret", ""); // exactly production's state

        assertThatThrownBy(() -> service.purgeUser(USER_ID))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Keycloak admin credentials are not configured");

        // Not one statement, not one Stripe call: a half-deletion is worse than a deletion
        // deferred by one night.
        verify(em, never()).unwrap(Session.class);
        verifyNoInteractions(workspaceDataPurger, stripeBillingService, restTemplate);
    }

    @Test
    @DisplayName("aborts before any step when the user has no provider_id under keycloak auth")
    void abortsWhenProviderIdMissing() {
        // The identity exists, we have merely lost the handle to it. Purging anyway destroys the
        // data and leaves a realm identity keyed by the same e-mail, so signing in again
        // bootstraps a fresh account: the deletion removes their work but not their access.
        user.setProviderId("  ");

        assertThatThrownBy(() -> service.purgeUser(USER_ID))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no provider_id");

        verify(em, never()).unwrap(Session.class);
        verifyNoInteractions(workspaceDataPurger, stripeBillingService, restTemplate);
    }

    // ------------------------------------------------ Keycloak last

    @Test
    @DisplayName("the identity is deleted only after every database statement, and before the outbox")
    void identityIsDeletedAfterTheDatabaseWorkAndBeforeTheOutbox() {
        keycloakTokenOk();
        when(restTemplate.exchange(anyString(), eq(HttpMethod.DELETE), any(), eq(Void.class)))
                .thenReturn(ResponseEntity.noContent().build());

        assertThat(service.purgeUser(USER_ID)).isTrue();

        InOrder order = inOrder(session, restTemplate, workspaceDataPurger);
        order.verify(session, atLeastOnce()).doWork(any());
        order.verify(restTemplate).exchange(contains("/users/" + PROVIDER_ID), eq(HttpMethod.DELETE), any(), eq(Void.class));
        order.verify(workspaceDataPurger).recordUserPurge(USER_ID.toString());
    }

    @Test
    @DisplayName("a failing Keycloak token request aborts the purge and writes no outbox row")
    void abortsWhenKeycloakTokenFails() {
        when(restTemplate.exchange(anyString(), any(), any(), eq(Map.class)))
                .thenThrow(new ResourceAccessException("kc unreachable"));

        assertThatThrownBy(() -> service.purgeUser(USER_ID))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Aborting the purge");

        // The statements did run, inside the transaction this exception rolls back; what must
        // not exist is the promise to the other services.
        assertNoOutboxRow();
    }

    @Test
    @DisplayName("a failing Keycloak DELETE aborts the purge and writes no outbox row")
    void abortsWhenKeycloakDeleteFails() {
        // Distinct from the token failure above: the token call succeeds and it is the identity
        // deletion that fails (500, timeout). This is the branch that decides whether a Keycloak
        // outage can downgrade "delete my account" to "delete my data, keep my login".
        keycloakTokenOk();
        when(restTemplate.exchange(anyString(), eq(HttpMethod.DELETE), any(), eq(Void.class)))
                .thenThrow(new ResourceAccessException("kc unreachable"));

        assertThatThrownBy(() -> service.purgeUser(USER_ID))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Aborting the purge");

        assertNoOutboxRow();
    }

    @Test
    @DisplayName("a Keycloak 404 means the identity is already gone, so the purge completes")
    void proceedsWhenKeycloakUserAlreadyAbsent() {
        keycloakTokenOk();
        when(restTemplate.exchange(anyString(), eq(HttpMethod.DELETE), any(), eq(Void.class)))
                .thenThrow(HttpClientErrorException.NotFound.create(HttpStatus.NOT_FOUND, "Not Found", null, null, null));

        // Already absent is the state we want; refusing here would strand the account forever
        // (it is also how a retry looks after a commit that failed past the identity delete).
        assertThat(service.purgeUser(USER_ID)).isTrue();

        // And it must actually purge: swallowing the 404 and returning early would leave the row
        // untouched while reporting success, which reads identically in the logs.
        verify(session, atLeastOnce()).doWork(any());
        verify(workspaceDataPurger).recordUserPurge(USER_ID.toString());
    }

    // ------------------------------------------------ Stripe

    @Test
    @DisplayName("no linked Stripe subscription: Stripe is not called and the purge completes")
    void noLinkedStripeSubscriptionSkipsStripe() {
        // Every FREE account. Calling Stripe here used to throw "No Stripe subscription linked"
        // inside the purge's transaction and doom its commit.
        Subscription internal = new Subscription();
        internal.setProvider("internal");
        when(subscriptionRepository.findActiveByUserId(USER_ID)).thenReturn(Optional.of(internal));
        keycloakTokenOk();
        when(restTemplate.exchange(anyString(), eq(HttpMethod.DELETE), any(), eq(Void.class)))
                .thenReturn(ResponseEntity.noContent().build());

        assertThat(service.purgeUser(USER_ID)).isTrue();
        verifyNoInteractions(stripeBillingService);
    }

    @Test
    @DisplayName("a linked Stripe subscription is cancelled before any database statement")
    void linkedStripeSubscriptionIsCancelledFirst() throws Exception {
        linkedStripeSubscription();
        keycloakTokenOk();
        when(restTemplate.exchange(anyString(), eq(HttpMethod.DELETE), any(), eq(Void.class)))
                .thenReturn(ResponseEntity.noContent().build());

        assertThat(service.purgeUser(USER_ID)).isTrue();

        // Before the statements: they delete the local subscription row, the only link to the
        // Stripe subscription, and the cancellation reads that row.
        InOrder order = inOrder(stripeBillingService, session);
        order.verify(stripeBillingService).cancelSubscriptionAtPeriodEnd(eq(USER_ID), anyString(), anyString());
        order.verify(session, atLeastOnce()).doWork(any());
    }

    @Test
    @DisplayName("a failing Stripe cancellation aborts the purge: no statement, no identity delete")
    void failingStripeCancellationAbortsThePurge() throws Exception {
        // Deleting the account anyway would delete the only local link to a subscription that
        // Stripe keeps renewing and charging.
        linkedStripeSubscription();
        when(stripeBillingService.cancelSubscriptionAtPeriodEnd(eq(USER_ID), anyString(), anyString()))
                .thenThrow(new com.stripe.exception.ApiConnectionException("stripe unreachable"));

        assertThatThrownBy(() -> service.purgeUser(USER_ID))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Stripe cancellation failed")
                .hasMessageContaining("stripe unreachable");

        verify(em, never()).unwrap(Session.class);
        verifyNoInteractions(restTemplate);
        assertNoOutboxRow();
    }

    private void linkedStripeSubscription() {
        Subscription stripeSub = new Subscription();
        stripeSub.setProvider("stripe");
        stripeSub.setProviderSubscriptionId("sub_123");
        when(subscriptionRepository.findActiveByUserId(USER_ID)).thenReturn(Optional.of(stripeSub));
    }

    // ------------------------------------------------ editions / guards

    @Test
    @DisplayName("embedded (CE) auth has no external identity, so the purge is not blocked")
    void ceModeIsNotBlocked() {
        ReflectionTestUtils.setField(service, "authMode", "embedded");
        ReflectionTestUtils.setField(service, "kcServerUrl", "");
        ReflectionTestUtils.setField(service, "kcClientSecret", "");

        assertThatCode(() -> service.purgeUser(USER_ID)).doesNotThrowAnyException();
        verifyNoInteractions(restTemplate);
        verify(workspaceDataPurger).recordUserPurge(USER_ID.toString());
    }

    @Test
    @DisplayName("an account that was restored in the meantime is never purged")
    void restoredAccountIsSkipped() {
        user.setEnabled(true);
        user.setDeactivatedAt(null);

        assertThat(service.purgeUser(USER_ID))
                .as("a restored account must never be purged")
                .isFalse();
        verifyNoInteractions(restTemplate, workspaceDataPurger, stripeBillingService);
    }
}
