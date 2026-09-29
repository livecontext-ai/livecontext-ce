package com.apimarketplace.auth.lifecycle;

import com.apimarketplace.auth.domain.User;
import com.apimarketplace.auth.dto.MarketingConsentResponse;
import com.apimarketplace.auth.dto.ProfileContextRequest;
import com.apimarketplace.auth.repository.UserAcquisitionRepository;
import com.apimarketplace.auth.repository.UserRepository;
import com.apimarketplace.auth.service.KeycloakAdminEmailVerifier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("UserLifecycleContextService - what the app reports, and what it triggers")
class UserLifecycleContextServiceTest {

    private static final long USER_ID = 7L;
    private static final Instant NOW = Instant.parse("2026-09-24T10:00:00Z");

    @Mock private UserRepository userRepository;
    @Mock private UserAcquisitionRepository acquisitionRepository;
    @Mock private LifecycleEmailService lifecycleEmails;

    private UserLifecycleContextService service;

    @BeforeEach
    void setUp() {
        service = new UserLifecycleContextService(userRepository, acquisitionRepository, lifecycleEmails,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    /** A user row created {@code age} before NOW, in the zone created_at is written in. */
    private void createdAgo(java.time.Duration age) {
        User user = new User();
        user.setId(USER_ID);
        user.setCreatedAt(LocalDateTime.ofInstant(NOW.minus(age), ZoneId.systemDefault()));
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
    }

    private static ProfileContextRequest locale(String locale, Boolean explicit) {
        return new ProfileContextRequest(locale, explicit, null, null, null, null);
    }

    @Test
    @DisplayName("an explicit locale uses the explicit write, which always wins")
    void explicitLocaleWins() {
        when(userRepository.updateLocaleExplicit(USER_ID, "de")).thenReturn(1);

        boolean changed = service.updateContext(USER_ID, locale("de", true), null, null);

        assertThat(changed).isTrue();
        verify(userRepository).updateLocaleExplicit(USER_ID, "de");
        verify(userRepository, never()).updateLocaleImplicit(anyLong(), anyString());
        verify(lifecycleEmails).syncContact(USER_ID);
    }

    @Test
    @DisplayName("an implicit locale uses the implicit write, which never overrides a pick")
    void implicitLocaleUsesGuardedWrite() {
        when(userRepository.updateLocaleImplicit(USER_ID, "fr")).thenReturn(0);

        boolean changed = service.updateContext(USER_ID, locale("fr", false), null, null);

        assertThat(changed).isFalse();
        verify(userRepository).updateLocaleImplicit(USER_ID, "fr");
        verify(userRepository, never()).updateLocaleExplicit(anyLong(), anyString());
        verify(lifecycleEmails, never()).syncContact(anyLong());
    }

    @Test
    @DisplayName("a missing localeExplicit flag is treated as implicit")
    void nullExplicitIsImplicit() {
        service.updateContext(USER_ID, locale("es", null), null, null);

        verify(userRepository).updateLocaleImplicit(USER_ID, "es");
    }

    @Test
    @DisplayName("an unsupported locale and an invalid time zone are ignored without any write")
    void invalidLocaleAndZoneIgnored() {
        service.updateContext(USER_ID, new ProfileContextRequest("it", true, "Mars/Base", null, null, null), null, null);

        verify(userRepository, never()).updateLocaleExplicit(anyLong(), anyString());
        verify(userRepository, never()).updateLocaleImplicit(anyLong(), anyString());
        verify(userRepository, never()).updateTimeZoneImplicit(anyLong(), anyString());
        verify(userRepository, never()).updateTimeZoneExplicit(anyLong(), anyString());
        verifyNoInteractions(lifecycleEmails);
    }

    @Test
    @DisplayName("an explicit language pick is carried to Keycloak, so its own pages follow it")
    void explicitLocaleReachesKeycloak() {
        KeycloakAdminEmailVerifier keycloak = org.mockito.Mockito.mock(KeycloakAdminEmailVerifier.class);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "keycloakAdmin", keycloak);
        when(userRepository.updateLocaleExplicit(USER_ID, "fr")).thenReturn(1);
        User user = new User();
        user.setId(USER_ID);
        user.setProviderId("kc-uuid-7");
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));

        service.updateContext(USER_ID, locale("fr", true), null, null);

        verify(keycloak).setUserLocale("kc-uuid-7", "fr");
    }

    @Test
    @DisplayName("the Keycloak call waits for the COMMIT, never runs inside the transaction")
    void keycloakSyncRunsAfterCommit() {
        KeycloakAdminEmailVerifier keycloak = org.mockito.Mockito.mock(KeycloakAdminEmailVerifier.class);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "keycloakAdmin", keycloak);
        when(userRepository.updateLocaleExplicit(USER_ID, "fr")).thenReturn(1);
        User user = new User();
        user.setId(USER_ID);
        user.setProviderId("kc-uuid-7");
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));

        // Two blocking HTTP calls against 5 s + 15 s timeouts would otherwise hold a database
        // connection for up to ~40 s, on an endpoint anybody signed in can drive in a loop by
        // alternating their language. It also has to be after commit to be truthful: a rollback
        // would otherwise leave Keycloak holding a language the database never stored.
        // Run the handoff inline so the assertion does not chase another thread; what is asserted
        // is that the work is HANDED OFF after commit, not which pool runs it.
        java.util.List<Runnable> scheduled = new java.util.ArrayList<>();
        org.springframework.test.util.ReflectionTestUtils.setField(
                service, "keycloakSyncExecutor", (java.util.concurrent.Executor) scheduled::add);

        org.springframework.transaction.support.TransactionSynchronizationManager.initSynchronization();
        try {
            service.updateContext(USER_ID, locale("fr", true), null, null);

            verify(keycloak, never()).setUserLocale(anyString(), anyString());
            assertThat(org.springframework.transaction.support.TransactionSynchronizationManager
                    .getSynchronizations()).hasSize(1);

            org.springframework.transaction.support.TransactionSynchronizationManager
                    .getSynchronizations().forEach(
                            org.springframework.transaction.support.TransactionSynchronization::afterCommit);

            // afterCommit runs on the CALLER's thread, so doing the HTTP there would free the
            // database connection and keep holding the request. It must only schedule.
            verify(keycloak, never()).setUserLocale(anyString(), anyString());
            assertThat(scheduled).hasSize(1);

            scheduled.forEach(Runnable::run);
            verify(keycloak).setUserLocale("kc-uuid-7", "fr");
        } finally {
            org.springframework.transaction.support.TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    @DisplayName("an account with no Keycloak identity is skipped, not attempted")
    void noProviderIdMeansNoSync() {
        // A CE-era row, or one whose provider id was never written: there is no Keycloak user to
        // address, so the sync is a no-op. It has to stay a SILENT one, because this runs behind a
        // profile report nobody is waiting on - but silent and untested is how it would rot.
        KeycloakAdminEmailVerifier keycloak = org.mockito.Mockito.mock(KeycloakAdminEmailVerifier.class);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "keycloakAdmin", keycloak);
        when(userRepository.updateLocaleExplicit(USER_ID, "fr")).thenReturn(1);
        when(userRepository.findById(USER_ID)).thenReturn(Optional.empty());

        assertThatCode(() -> service.updateContext(USER_ID, locale("fr", true), null, null))
                .doesNotThrowAnyException();

        org.mockito.Mockito.verifyNoInteractions(keycloak);
    }

    @Test
    @DisplayName("a database failure while resolving the identity does not fail the profile report")
    void lookupFailureIsSwallowed() {
        // The language IS stored by then; this lookup only finds who to mirror it to. Letting it
        // escape would turn a mirroring problem into a failed request for the person who just
        // changed their language, and the change they made would look rejected.
        KeycloakAdminEmailVerifier keycloak = org.mockito.Mockito.mock(KeycloakAdminEmailVerifier.class);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "keycloakAdmin", keycloak);
        when(userRepository.updateLocaleExplicit(USER_ID, "fr")).thenReturn(1);
        when(userRepository.findById(USER_ID))
                .thenThrow(new org.springframework.dao.DataAccessResourceFailureException("down"));

        assertThatCode(() -> service.updateContext(USER_ID, locale("fr", true), null, null))
                .doesNotThrowAnyException();

        org.mockito.Mockito.verifyNoInteractions(keycloak);
    }

    @Test
    @DisplayName("a saturated sync queue drops the language update rather than failing the request")
    void keycloakSyncRejectionIsSwallowed() {
        KeycloakAdminEmailVerifier keycloak = org.mockito.Mockito.mock(KeycloakAdminEmailVerifier.class);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "keycloakAdmin", keycloak);
        org.springframework.test.util.ReflectionTestUtils.setField(
                service, "keycloakSyncExecutor", (java.util.concurrent.Executor) task -> {
                    throw new java.util.concurrent.RejectedExecutionException("queue full");
                });
        when(userRepository.updateLocaleExplicit(USER_ID, "fr")).thenReturn(1);
        User user = new User();
        user.setId(USER_ID);
        user.setProviderId("kc-uuid-7");
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));

        org.springframework.transaction.support.TransactionSynchronizationManager.initSynchronization();
        try {
            boolean changed = service.updateContext(USER_ID, locale("fr", true), null, null);

            // The request itself succeeded: the language IS stored, and that is the part the
            // person can see. Only the mirror into Keycloak is lost.
            assertThat(changed).isTrue();

            assertThatCode(() ->
                    org.springframework.transaction.support.TransactionSynchronizationManager
                            .getSynchronizations().forEach(
                                    org.springframework.transaction.support.TransactionSynchronization::afterCommit))
                    .doesNotThrowAnyException();

            // DROPPED, not run inline as a consolation: doing the two HTTP calls on this thread
            // to salvage the sync is exactly the stall that handing them to a pool avoids, and
            // it would arrive on the caller's thread under load, which is when it hurts most.
            verify(keycloak, never()).setUserLocale(anyString(), anyString());
        } finally {
            org.springframework.transaction.support.TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    @DisplayName("an IMPLICIT locale report never touches Keycloak: only a deliberate pick is worth the call")
    void implicitLocaleDoesNotReachKeycloak() {
        KeycloakAdminEmailVerifier keycloak = org.mockito.Mockito.mock(KeycloakAdminEmailVerifier.class);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "keycloakAdmin", keycloak);
        when(userRepository.updateLocaleImplicit(USER_ID, "fr")).thenReturn(1);

        service.updateContext(USER_ID, locale("fr", false), null, null);

        verifyNoInteractions(keycloak);
    }

    @Test
    @DisplayName("an account ALREADY pinned to that language is still synced, or it never would be")
    void unchangedExplicitLocaleStillSyncsKeycloak() {
        // `updateLocaleExplicit` answers 0 when the value and the flag are already what we are
        // writing - which is every account that picked a language before this shipped, since
        // locale_explicit has existed since V527. Gating the sync on "the row changed" excluded
        // exactly that population from the feature, with no backfill to rescue them. The write
        // itself is idempotent: setUserLocale reads the user first and returns without a PUT when
        // the attribute already matches.
        KeycloakAdminEmailVerifier keycloak = org.mockito.Mockito.mock(KeycloakAdminEmailVerifier.class);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "keycloakAdmin", keycloak);
        when(userRepository.updateLocaleExplicit(USER_ID, "fr")).thenReturn(0);
        User user = new User();
        user.setId(USER_ID);
        user.setProviderId("kc-uuid-7");
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));

        service.updateContext(USER_ID, locale("fr", true), null, null);

        verify(keycloak).setUserLocale("kc-uuid-7", "fr");
    }

    @Test
    @DisplayName("no Keycloak (CE) means no sync and no failure")
    void noKeycloakIsNotAFailure() {
        when(userRepository.updateLocaleExplicit(USER_ID, "fr")).thenReturn(1);

        // keycloakAdmin is null here, as it is on a self-hosted install.
        assertThat(service.updateContext(USER_ID, locale("fr", true), null, null)).isTrue();
        verify(userRepository, never()).findById(anyLong());
    }

    @Test
    @DisplayName("an explicit time zone pick uses the explicit write, which always wins")
    void explicitTimeZoneWins() {
        when(userRepository.updateTimeZoneExplicit(USER_ID, "Asia/Tokyo")).thenReturn(1);

        boolean changed = service.updateContext(
                USER_ID, new ProfileContextRequest(null, null, "Asia/Tokyo", true, null, null), null, null);

        assertThat(changed).isTrue();
        verify(userRepository).updateTimeZoneExplicit(USER_ID, "Asia/Tokyo");
        verify(userRepository, never()).updateTimeZoneImplicit(anyLong(), anyString());
        verify(lifecycleEmails).syncContact(USER_ID);
    }

    @Test
    @DisplayName("a missing timeZoneExplicit flag is treated as implicit, so a browser report stays guarded")
    void nullTimeZoneExplicitIsImplicit() {
        when(userRepository.updateTimeZoneImplicit(USER_ID, "Europe/Paris")).thenReturn(0);

        boolean changed = service.updateContext(
                USER_ID, new ProfileContextRequest(null, null, "Europe/Paris", null, null, null), null, null);

        assertThat(changed).isFalse();
        verify(userRepository).updateTimeZoneImplicit(USER_ID, "Europe/Paris");
        verify(userRepository, never()).updateTimeZoneExplicit(anyLong(), anyString());
        verify(lifecycleEmails, never()).syncContact(anyLong());
    }

    @Test
    @DisplayName("an explicit FALSE is a device report, not a pick - the same as sending nothing")
    void explicitlyFalseTimeZoneExplicitIsImplicit() {
        // The gap between the two halves that were covered. The controller test pins that the wire
        // value `false` BINDS as `false` rather than null; this pins what the service then does with
        // it. Nothing joined them, and the join is where a mistake would live: rewriting the guard
        // from `Boolean.TRUE.equals(flag)` to `flag != null` keeps every other test in this class
        // green and starts PINNING a zone the browser merely reported, which is the one thing the
        // explicit/implicit split exists to prevent. A pinned zone survives moving continents.
        when(userRepository.updateTimeZoneImplicit(USER_ID, "Europe/Paris")).thenReturn(1);

        boolean changed = service.updateContext(
                USER_ID, new ProfileContextRequest(null, null, "Europe/Paris", false, null, null),
                null, null);

        assertThat(changed).isTrue();
        verify(userRepository).updateTimeZoneImplicit(USER_ID, "Europe/Paris");
        verify(userRepository, never()).updateTimeZoneExplicit(anyLong(), anyString());
        verify(userRepository, never()).releaseTimeZone(anyLong(), anyString());
    }

    @Test
    @DisplayName("following the device again releases the pick, and outranks timeZoneExplicit")
    void followsDeviceReleasesThePick() {
        when(userRepository.releaseTimeZone(USER_ID, "Europe/Paris")).thenReturn(1);

        // Both flags set: the release wins, so a client that sends the pair cannot end up
        // pinning the very zone it asked to stop pinning.
        boolean changed = service.updateContext(
                USER_ID, new ProfileContextRequest(null, null, "Europe/Paris", true, true, null), null, null);

        assertThat(changed).isTrue();
        verify(userRepository).releaseTimeZone(USER_ID, "Europe/Paris");
        verify(userRepository, never()).updateTimeZoneExplicit(anyLong(), anyString());
        verify(userRepository, never()).updateTimeZoneImplicit(anyLong(), anyString());
        verify(lifecycleEmails).syncContact(USER_ID);
    }

    @Test
    @DisplayName("followsDevice without a usable zone releases nothing")
    void followsDeviceNeedsAZone() {
        service.updateContext(
                USER_ID, new ProfileContextRequest(null, null, "Mars/Base", null, true, null), null, null);

        verify(userRepository, never()).releaseTimeZone(anyLong(), anyString());
        verifyNoInteractions(lifecycleEmails);
    }

    @Test
    @DisplayName("a changed time zone syncs the contact")
    void timeZoneChangeSyncs() {
        when(userRepository.updateTimeZoneImplicit(USER_ID, "Asia/Tokyo")).thenReturn(1);

        service.updateContext(USER_ID, new ProfileContextRequest(null, null, "Asia/Tokyo", null, null, null), null, null);

        verify(lifecycleEmails).syncContact(USER_ID);
    }

    @Test
    @DisplayName("the Cloudflare country is captured write-once and a first capture syncs the contact")
    void countryCaptured() {
        createdAgo(java.time.Duration.ofMinutes(5));
        when(userRepository.captureSignupCountry(USER_ID, "FR")).thenReturn(1);

        service.updateContext(USER_ID, null, "fr", null);

        verify(userRepository).captureSignupCountry(USER_ID, "FR");
        verify(lifecycleEmails).syncContact(USER_ID);
    }

    @Test
    @DisplayName("XX, T1 and malformed countries are never written")
    void invalidCountryIgnored() {
        service.updateContext(USER_ID, null, "XX", null);
        service.updateContext(USER_ID, null, "T1", null);

        verify(userRepository, never()).captureSignupCountry(anyLong(), anyString());
    }

    @Test
    @DisplayName("the IP is captured write-once with its capture instant, and never triggers a Resend sync")
    void ipCapturedWithoutSync() {
        createdAgo(java.time.Duration.ofMinutes(5));
        when(userRepository.captureSignupIp(USER_ID, "203.0.113.7", NOW)).thenReturn(1);

        boolean changed = service.updateContext(USER_ID, null, null, "203.0.113.7");

        assertThat(changed).isFalse();
        verify(userRepository).captureSignupIp(USER_ID, "203.0.113.7", NOW);
        verifyNoInteractions(lifecycleEmails);
    }

    @Test
    @DisplayName("an account created 7 days ago minus a minute is still at its signup: country and IP captured")
    void signupWindowInsideBoundary() {
        createdAgo(java.time.Duration.ofDays(7).minusMinutes(1));
        when(userRepository.captureSignupCountry(USER_ID, "FR")).thenReturn(1);

        service.updateContext(USER_ID, null, "FR", "203.0.113.7");

        verify(userRepository).captureSignupCountry(USER_ID, "FR");
        verify(userRepository).captureSignupIp(USER_ID, "203.0.113.7", NOW);
    }

    @Test
    @DisplayName("an account created more than 7 days ago is not labelled with today's country or IP")
    void signupWindowOutsideBoundary() {
        createdAgo(java.time.Duration.ofDays(7).plusMinutes(1));

        boolean changed = service.updateContext(USER_ID, null, "FR", "203.0.113.7");

        assertThat(changed).isFalse();
        verify(userRepository, never()).captureSignupCountry(anyLong(), anyString());
        verify(userRepository, never()).captureSignupIp(anyLong(), anyString(), any());
        verifyNoInteractions(lifecycleEmails);
    }

    @Test
    @DisplayName("an invalid IP header is ignored")
    void invalidIpIgnored() {
        service.updateContext(USER_ID, null, null, "evil.example.com");

        verify(userRepository, never()).captureSignupIp(anyLong(), anyString(), any());
    }

    @Test
    @DisplayName("acquisition is inserted write-once, trimmed and capped")
    void acquisitionTrimmedAndCapped() {
        createdAgo(java.time.Duration.ofMinutes(5));
        ProfileContextRequest.Acquisition a = new ProfileContextRequest.Acquisition(
                "  google ", "cpc", "x".repeat(300), null, " ", "https://ref.example/" + "p".repeat(2000),
                "/fr/pricing", "2026-09-20T08:00:00Z");

        service.updateContext(USER_ID, new ProfileContextRequest(null, null, null, null, null, a), null, null);

        verify(acquisitionRepository).insertIfAbsent(eq(USER_ID), eq("google"), eq("cpc"),
                eq("x".repeat(255)), isNull(), isNull(),
                eq(("https://ref.example/" + "p".repeat(2000)).substring(0, 1024)),
                eq("/fr/pricing"), eq(Instant.parse("2026-09-20T08:00:00Z")), eq(NOW));
    }

    @Test
    @DisplayName("an acquisition with nothing usable is not inserted")
    void emptyAcquisitionSkipped() {
        createdAgo(java.time.Duration.ofMinutes(5));
        ProfileContextRequest.Acquisition a = new ProfileContextRequest.Acquisition(
                " ", null, null, null, null, null, "", "not a date");

        service.updateContext(USER_ID, new ProfileContextRequest(null, null, null, null, null, a), null, null);

        verifyNoInteractions(acquisitionRepository);
    }

    private static ProfileContextRequest landing(String path) {
        return new ProfileContextRequest(null, null, null, null, null, new ProfileContextRequest.Acquisition(
                null, null, null, null, null, null, path, null));
    }

    @Test
    @DisplayName("an account created 7 days ago minus a minute is still at its signup: its first touch is captured")
    void acquisitionInsideSignupWindow() {
        createdAgo(java.time.Duration.ofDays(7).minusMinutes(1));

        service.updateContext(USER_ID, landing("/fr/pricing"), null, null);

        verify(acquisitionRepository).insertIfAbsent(eq(USER_ID), isNull(), isNull(), isNull(), isNull(), isNull(),
                isNull(), eq("/fr/pricing"), isNull(), eq(NOW));
    }

    @Test
    @DisplayName("Regression (bogus first touch): an account created more than 7 days ago never gets today's page as its first touch")
    void acquisitionOutsideSignupWindow() {
        createdAgo(java.time.Duration.ofDays(7).plusMinutes(1));

        service.updateContext(USER_ID, landing("/app/workflows"), null, null);

        verifyNoInteractions(acquisitionRepository);
    }

    @Test
    @DisplayName("an unknown user (no created_at) captures no first touch")
    void acquisitionUnknownUser() {
        when(userRepository.findById(USER_ID)).thenReturn(Optional.empty());

        service.updateContext(USER_ID, landing("/fr/pricing"), null, null);

        verifyNoInteractions(acquisitionRepository);
    }

    @Test
    @DisplayName("a future firstSeenAt is clamped to now, an unparseable one dropped")
    void firstSeenAtParsing() {
        assertThat(UserLifecycleContextService.parseInstant("2030-01-01T00:00:00Z", NOW)).isEqualTo(NOW);
        assertThat(UserLifecycleContextService.parseInstant("2026-09-20T10:00:00+02:00", NOW))
                .isEqualTo(Instant.parse("2026-09-20T08:00:00Z"));
        assertThat(UserLifecycleContextService.parseInstant("yesterday", NOW)).isNull();
    }

    @Test
    @DisplayName("activation stamps once and syncs the contact then emits user.activated, only on the first call")
    void activationEmitsOnce() {
        when(userRepository.markActivatedIfFirst(USER_ID, NOW)).thenReturn(1, 0);

        boolean first = service.recordActivation(USER_ID);
        boolean second = service.recordActivation(USER_ID);

        assertThat(first).isTrue();
        assertThat(second).isFalse();
        // Contact first: the welcome sequence re-checks contact.properties.activated.
        verify(lifecycleEmails).syncContactAndEmit(USER_ID, LifecycleEvents.USER_ACTIVATED, Map.of());
        verify(lifecycleEmails, never()).emit(anyLong(), anyString(), anyMap());
    }

    /** Captures the claim recordSignup hands to the lifecycle worker. */
    private LifecycleEmailService.Claim signupClaim() {
        org.mockito.ArgumentCaptor<LifecycleEmailService.Claim> claim =
                org.mockito.ArgumentCaptor.forClass(LifecycleEmailService.Claim.class);
        verify(lifecycleEmails).syncContactAndEmitIfClaimed(eq(USER_ID), eq(LifecycleEvents.USER_SIGNED_UP),
                eq(Map.of()), claim.capture());
        return claim.getValue();
    }

    @Test
    @DisplayName("Regression (verification undone): signup writes nothing on the caller's thread, the claim goes to the worker")
    void signupClaimsOnTheWorkerOnly() {
        service.recordSignup(USER_ID);

        // Nothing touched the database in the caller's transaction.
        verifyNoInteractions(userRepository);
        verify(lifecycleEmails, never()).syncContactAndEmit(anyLong(), anyString(), anyMap());

        when(userRepository.markSignupEmittedIfFirst(USER_ID, NOW)).thenReturn(1);
        assertThat(signupClaim().getAsBoolean()).isTrue();
        verify(userRepository).markSignupEmittedIfFirst(USER_ID, NOW);
    }

    @Test
    @DisplayName("Regression (double welcome): of two racing signups only the FIRST claim is granted")
    void signupClaimGrantedOnce() {
        when(userRepository.markSignupEmittedIfFirst(USER_ID, NOW)).thenReturn(1, 0);

        service.recordSignup(USER_ID);
        service.recordSignup(USER_ID);

        org.mockito.ArgumentCaptor<LifecycleEmailService.Claim> claims =
                org.mockito.ArgumentCaptor.forClass(LifecycleEmailService.Claim.class);
        verify(lifecycleEmails, times(2)).syncContactAndEmitIfClaimed(eq(USER_ID),
                eq(LifecycleEvents.USER_SIGNED_UP), eq(Map.of()), claims.capture());
        assertThat(claims.getAllValues().get(0).getAsBoolean()).isTrue();
        assertThat(claims.getAllValues().get(1).getAsBoolean()).isFalse();
    }

    @Test
    @DisplayName("a failing signup claim answers not-claimed and never throws (no welcome this time, stamp stays free)")
    void failingSignupClaimAnswersFalse() {
        when(userRepository.markSignupEmittedIfFirst(USER_ID, NOW))
                .thenThrow(new org.springframework.dao.DataAccessResourceFailureException("statement timeout"));

        service.recordSignup(USER_ID);

        assertThat(signupClaim().getAsBoolean()).isFalse();
    }

    @Test
    @DisplayName("the signup claim runs in its OWN transaction (REQUIRES_NEW)")
    void signupClaimRunsInItsOwnTransaction() {
        org.springframework.transaction.PlatformTransactionManager tm =
                org.mockito.Mockito.mock(org.springframework.transaction.PlatformTransactionManager.class);
        when(tm.getTransaction(any())).thenReturn(
                new org.springframework.transaction.support.SimpleTransactionStatus());
        UserLifecycleContextService withTx = new UserLifecycleContextService(userRepository, acquisitionRepository,
                lifecycleEmails, Clock.fixed(NOW, ZoneOffset.UTC), CheckoutStartedThrottle.requiresNew(tm));
        when(userRepository.markSignupEmittedIfFirst(USER_ID, NOW)).thenReturn(1);

        withTx.recordSignup(USER_ID);
        assertThat(signupClaim().getAsBoolean()).isTrue();

        org.mockito.ArgumentCaptor<org.springframework.transaction.TransactionDefinition> def =
                org.mockito.ArgumentCaptor.forClass(org.springframework.transaction.TransactionDefinition.class);
        verify(tm).getTransaction(def.capture());
        assertThat(def.getValue().getPropagationBehavior())
                .isEqualTo(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        verify(tm).commit(any());
    }

    @Test
    @DisplayName("Regression (welcome lost): releasing a granted signup claim clears only the stamp THIS claim wrote, in its own transaction")
    void releasedSignupClaimClearsItsOwnStamp() {
        org.springframework.transaction.PlatformTransactionManager tm =
                org.mockito.Mockito.mock(org.springframework.transaction.PlatformTransactionManager.class);
        when(tm.getTransaction(any())).thenReturn(
                new org.springframework.transaction.support.SimpleTransactionStatus());
        // A clock with nanoseconds: the stamp must be what a TIMESTAMPTZ keeps, or the release never matches.
        Instant nanos = NOW.plusNanos(123_456_789);
        Instant micros = NOW.plusNanos(123_456_000);
        UserLifecycleContextService withTx = new UserLifecycleContextService(userRepository, acquisitionRepository,
                lifecycleEmails, Clock.fixed(nanos, ZoneOffset.UTC), CheckoutStartedThrottle.requiresNew(tm));
        when(userRepository.markSignupEmittedIfFirst(USER_ID, micros)).thenReturn(1);

        withTx.recordSignup(USER_ID);
        LifecycleEmailService.Claim claim = signupClaim();
        assertThat(claim.getAsBoolean()).isTrue();
        claim.release();

        verify(userRepository).releaseSignupEmitted(USER_ID, micros);
        verify(tm, times(2)).commit(any());
    }

    @Test
    @DisplayName("a refused signup claim releases nothing (it never undoes the winner's stamp)")
    void refusedSignupClaimReleasesNothing() {
        when(userRepository.markSignupEmittedIfFirst(USER_ID, NOW)).thenReturn(0);

        service.recordSignup(USER_ID);
        LifecycleEmailService.Claim claim = signupClaim();
        assertThat(claim.getAsBoolean()).isFalse();
        claim.release();

        verify(userRepository, never()).releaseSignupEmitted(anyLong(), any());
    }

    @Test
    @DisplayName("a failing release never throws on the lifecycle worker")
    void failingReleaseNeverThrows() {
        when(userRepository.markSignupEmittedIfFirst(USER_ID, NOW)).thenReturn(1);
        when(userRepository.releaseSignupEmitted(USER_ID, NOW))
                .thenThrow(new org.springframework.dao.DataAccessResourceFailureException("down"));

        service.recordSignup(USER_ID);
        LifecycleEmailService.Claim claim = signupClaim();
        assertThat(claim.getAsBoolean()).isTrue();
        org.assertj.core.api.Assertions.assertThatCode(claim::release).doesNotThrowAnyException();
    }

    /** A user created {@code age} before NOW, verified or not, with or without the signup stamp. */
    private User signupCandidate(java.time.Duration age, boolean verified, Instant stamp) {
        User user = new User();
        user.setId(USER_ID);
        user.setEmailVerified(verified);
        user.setCreatedAt(LocalDateTime.ofInstant(NOW.minus(age), ZoneId.systemDefault()));
        org.springframework.test.util.ReflectionTestUtils.setField(user, "lifecycleSignupEmittedAt", stamp);
        return user;
    }

    @Test
    @DisplayName("Regression (welcome lost): a login of a verified account 7 days old minus a minute, with no stamp, re-attempts the welcome")
    void retrySignupInsideWindow() {
        service.retrySignupIfUnsent(signupCandidate(java.time.Duration.ofDays(7).minusMinutes(1), true, null));

        verify(lifecycleEmails).syncContactAndEmitIfClaimed(eq(USER_ID), eq(LifecycleEvents.USER_SIGNED_UP),
                eq(Map.of()), any());
    }

    @Test
    @DisplayName("no welcome retry for an account older than 7 days, an already stamped one, or an unverified one")
    void retrySignupSkipped() {
        service.retrySignupIfUnsent(signupCandidate(java.time.Duration.ofDays(7).plusMinutes(1), true, null));
        service.retrySignupIfUnsent(signupCandidate(java.time.Duration.ofMinutes(5), true, NOW));
        service.retrySignupIfUnsent(signupCandidate(java.time.Duration.ofMinutes(5), false, null));
        service.retrySignupIfUnsent(null);

        verifyNoInteractions(lifecycleEmails, userRepository);
    }

    @Test
    @DisplayName("signup of a null user claims nothing")
    void signupNullUser() {
        service.recordSignup(null);
        verifyNoInteractions(userRepository, lifecycleEmails);
    }

    @Test
    @DisplayName("consent is stored and synced; an unknown user answers false and syncs nothing")
    void consent() {
        when(userRepository.updateMarketingConsent(USER_ID, true, NOW)).thenReturn(1);
        when(userRepository.updateMarketingConsent(99L, true, NOW)).thenReturn(0);

        assertThat(service.setMarketingConsent(USER_ID, true)).isTrue();
        assertThat(service.setMarketingConsent(99L, true)).isFalse();

        verify(lifecycleEmails).syncContact(USER_ID);
        verify(lifecycleEmails, never()).syncContact(99L);
    }

    @Test
    @DisplayName("consent is read back with its timestamp")
    void consentRead() {
        User user = new User();
        user.setMarketingConsent(true);
        user.setMarketingConsentAt(NOW);
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));

        Optional<MarketingConsentResponse> read = service.getMarketingConsent(USER_ID);

        assertThat(read).contains(new MarketingConsentResponse(true, NOW));
    }

    @Test
    @DisplayName("an internal X-User-ID resolves as a numeric id first, then as a provider id")
    void resolveUserId() {
        when(userRepository.existsById(7L)).thenReturn(true);
        User byProvider = new User();
        byProvider.setId(8L);
        when(userRepository.findByProviderId("kc-sub")).thenReturn(Optional.of(byProvider));

        assertThat(service.resolveUserId("7")).contains(7L);
        assertThat(service.resolveUserId("kc-sub")).contains(8L);
        assertThat(service.resolveUserId(" ")).isEmpty();
    }

    // ── marketing_consent_changed analytics ────────────────────────────────────

    private com.apimarketplace.auth.analytics.AuthAnalyticsEmitter wireAnalytics() {
        com.apimarketplace.auth.analytics.AuthAnalyticsEmitter analytics =
                org.mockito.Mockito.mock(com.apimarketplace.auth.analytics.AuthAnalyticsEmitter.class);
        org.mockito.Mockito.lenient().when(analytics.isActive()).thenReturn(true);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "analytics", analytics);
        return analytics;
    }

    private void storedConsent(boolean consent) {
        User user = new User();
        user.setId(USER_ID);
        user.setMarketingConsent(consent);
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
    }

    @Test
    @DisplayName("marketing_consent_changed: a real change is counted with the new value")
    void consentChangeIsCounted() {
        var analytics = wireAnalytics();
        storedConsent(false);
        when(userRepository.updateMarketingConsent(USER_ID, true, NOW)).thenReturn(1);

        assertThat(service.setMarketingConsent(USER_ID, true)).isTrue();

        verify(analytics).marketingConsentChanged(USER_ID, true);
    }

    @Test
    @DisplayName("marketing_consent_changed: re-saving the same value is not a change")
    void sameConsentIsNotCounted() {
        var analytics = wireAnalytics();
        storedConsent(true);
        when(userRepository.updateMarketingConsent(USER_ID, true, NOW)).thenReturn(1);

        assertThat(service.setMarketingConsent(USER_ID, true)).isTrue();

        verify(lifecycleEmails).syncContact(USER_ID);
        verify(analytics, never()).marketingConsentChanged(anyLong(), org.mockito.ArgumentMatchers.anyBoolean());
    }

    @Test
    @DisplayName("marketing_consent_changed: an unknown user or an unreadable previous value counts nothing")
    void unknownPreviousIsNotCounted() {
        var analytics = wireAnalytics();
        when(userRepository.findById(99L)).thenReturn(Optional.empty());
        when(userRepository.updateMarketingConsent(99L, true, NOW)).thenReturn(0);

        assertThat(service.setMarketingConsent(99L, true)).isFalse();

        verify(analytics, never()).marketingConsentChanged(anyLong(), org.mockito.ArgumentMatchers.anyBoolean());
    }

    @Test
    @DisplayName("marketing_consent_changed: inactive or absent analytics adds no read of the user row")
    void noAnalyticsReadWhenInactive() {
        // Absent emitter (self-hosted without PostHog).
        when(userRepository.updateMarketingConsent(USER_ID, true, NOW)).thenReturn(1);
        assertThat(service.setMarketingConsent(USER_ID, true)).isTrue();

        // Wired but inactive emitter.
        var analytics = wireAnalytics();
        when(analytics.isActive()).thenReturn(false);
        assertThat(service.setMarketingConsent(USER_ID, true)).isTrue();

        verify(userRepository, never()).findById(anyLong());
        verify(analytics, never()).marketingConsentChanged(anyLong(), org.mockito.ArgumentMatchers.anyBoolean());
    }
}
