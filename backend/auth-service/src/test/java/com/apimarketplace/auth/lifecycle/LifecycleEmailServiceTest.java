package com.apimarketplace.auth.lifecycle;

import com.apimarketplace.auth.domain.User;
import com.apimarketplace.auth.domain.UserOnboarding;
import com.apimarketplace.auth.repository.UserOnboardingRepository;
import com.apimarketplace.auth.repository.UserRepository;
import com.apimarketplace.auth.service.PlanResolutionService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("LifecycleEmailService - contact properties, ordering and the no-op paths")
class LifecycleEmailServiceTest {

    private static final long USER_ID = 7L;

    @Mock private ResendClient resend;
    @Mock private UserRepository userRepository;
    @Mock private UserOnboardingRepository onboardingRepository;
    @Mock private PlanResolutionService planResolutionService;

    private LifecycleEmailService service;
    private User user;

    @BeforeEach
    void setUp() {
        service = new LifecycleEmailService(resend, userRepository, onboardingRepository, planResolutionService,
                TransactionOperations.withoutTransaction());
        when(resend.isActive()).thenReturn(true);
        // Run submitted tasks inline so the worker-thread body is observable.
        doAnswer(inv -> {
            ((Runnable) inv.getArgument(0)).run();
            return true;
        }).when(resend).submit(any());
        doAnswer(inv -> {
            ((Runnable) inv.getArgument(0)).run();
            return true;
        }).when(resend).submitBulk(any());

        user = new User();
        user.setId(USER_ID);
        user.setEmail("ada@example.com");
        user.setFirstName("Ada");
        user.setEnabled(true);
        user.setEmailVerified(true);
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
        when(planResolutionService.resolveBillingPlan(USER_ID)).thenReturn("PRO");
        when(onboardingRepository.findByUserId(USER_ID)).thenReturn(Optional.empty());
        when(resend.sendEvent(anyString(), anyString(), anyMap())).thenReturn(true);
    }

    @AfterEach
    void clearSync() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> syncedProperties() {
        ArgumentCaptor<Map<String, String>> captor = ArgumentCaptor.forClass(Map.class);
        verify(resend).upsertContact(eq("ada@example.com"), eq("Ada"), captor.capture());
        return captor.getValue();
    }

    @Test
    @DisplayName("every property falls back to its documented default; there is no credits_state any more")
    void propertiesFallBack() {
        when(planResolutionService.resolveBillingPlan(USER_ID)).thenReturn(null);

        service.syncContact(USER_ID);

        assertThat(syncedProperties()).containsExactly(
                Map.entry("locale", "en"),
                Map.entry("plan", "free"),
                Map.entry("persona", "other"),
                Map.entry("timezone", "UTC"),
                Map.entry("country", "XX"),
                Map.entry("marketing_consent", "no"),
                Map.entry("activated", "no"));
    }

    @Test
    @DisplayName("activated is yes once activated_at is stamped")
    void activatedFromActivatedAt() {
        user.setActivatedAt(Instant.parse("2026-09-20T08:00:00Z"));

        service.syncContact(USER_ID);

        assertThat(syncedProperties()).containsEntry("activated", "yes");
    }

    @Test
    @DisplayName("activated BEFORE email verification: the signup sync at verification carries activated=yes")
    void activatedBeforeVerificationIsCarriedAtVerification() {
        // The user builds a first workflow while unverified: the activation sync is dropped.
        user.setEmailVerified(false);
        user.setActivatedAt(Instant.parse("2026-09-20T08:00:00Z"));
        service.syncContactAndEmit(USER_ID, LifecycleEvents.USER_ACTIVATED, Map.of());
        verify(resend, never()).upsertContact(anyString(), any(), anyMap());

        // Then the code is confirmed: EmailVerificationService syncs and emits user.signed_up.
        user.setEmailVerified(true);
        service.syncContactAndEmit(USER_ID, LifecycleEvents.USER_SIGNED_UP, Map.of());

        assertThat(syncedProperties()).containsEntry("activated", "yes");
        InOrder order = inOrder(resend);
        order.verify(resend).upsertContact(anyString(), any(), anyMap());
        order.verify(resend).sendEvent("ada@example.com", LifecycleEvents.USER_SIGNED_UP, Map.of());
    }

    @Test
    @DisplayName("stored values are sent: plan lowercased, persona bucketed, consent as yes")
    void propertiesFromUser() {
        user.setLocale("fr");
        user.setTimeZone("Europe/Paris");
        user.setSignupCountry("FR");
        user.setMarketingConsent(true);
        UserOnboarding onboarding = new UserOnboarding();
        onboarding.setProfession("engineering");
        when(onboardingRepository.findByUserId(USER_ID)).thenReturn(Optional.of(onboarding));

        service.syncContact(USER_ID);

        Map<String, String> props = syncedProperties();
        assertThat(props).containsEntry("locale", "fr")
                .containsEntry("plan", "pro")
                .containsEntry("timezone", "Europe/Paris")
                .containsEntry("country", "FR")
                .containsEntry("marketing_consent", "yes");
        assertThat(props).containsEntry("persona", "engineering");
    }

    @Test
    @DisplayName("the signup IP is never sent to Resend, not as a property nor anywhere else")
    void signupIpNeverSent() {
        user.setSignupIp("203.0.113.7");

        service.syncContactAndEmit(USER_ID, LifecycleEvents.USER_SIGNED_UP, Map.of());

        Map<String, String> props = syncedProperties();
        assertThat(props.values()).doesNotContain("203.0.113.7");
        assertThat(props.keySet()).noneMatch(k -> k.contains("ip"));
        verify(resend).sendEvent("ada@example.com", LifecycleEvents.USER_SIGNED_UP, Map.of());
    }

    @Test
    @DisplayName("syncContactAndEmit upserts the contact BEFORE sending the event")
    void contactBeforeEvent() {
        service.syncContactAndEmit(USER_ID, LifecycleEvents.USER_SIGNED_UP, Map.of());

        InOrder order = inOrder(resend);
        order.verify(resend).upsertContact(anyString(), any(), anyMap());
        order.verify(resend).sendEvent(eq("ada@example.com"), eq(LifecycleEvents.USER_SIGNED_UP), anyMap());
    }

    @Test
    @DisplayName("emit sends only the event, the contact is not rewritten")
    void emitDoesNotSyncContact() {
        service.emit(USER_ID, LifecycleEvents.USER_RETURNED, Map.of());

        verify(resend, never()).upsertContact(anyString(), any(), anyMap());
        verify(resend).sendEvent("ada@example.com", LifecycleEvents.USER_RETURNED, Map.of());
    }

    @Test
    @DisplayName("inactive client: nothing is read and nothing is submitted")
    void inactiveIsNoOp() {
        when(resend.isActive()).thenReturn(false);

        service.syncContactAndEmit(USER_ID, LifecycleEvents.USER_SIGNED_UP, Map.of());
        service.deleteContact("ada@example.com");

        verify(resend, never()).submit(any());
        verifyNoInteractions(userRepository, planResolutionService, onboardingRepository);
    }

    @Test
    @DisplayName("a deactivated account (deletion requested) receives nothing")
    void deactivatedUserSkipped() {
        user.setDeactivatedAt(LocalDateTime.now());

        service.syncContactAndEmit(USER_ID, LifecycleEvents.USER_RETURNED, Map.of());

        verify(resend, never()).upsertContact(anyString(), any(), anyMap());
        verify(resend, never()).sendEvent(anyString(), anyString(), anyMap());
    }

    @Test
    @DisplayName("an account whose email is not verified yet gets no contact and no signup event")
    void unverifiedUserSkipped() {
        user.setEmailVerified(false);

        service.syncContactAndEmit(USER_ID, LifecycleEvents.USER_SIGNED_UP, Map.of());

        verify(resend, never()).upsertContact(anyString(), any(), anyMap());
        verify(resend, never()).sendEvent(anyString(), anyString(), anyMap());
    }

    @Test
    @DisplayName("a user without an email is skipped")
    void noEmailSkipped() {
        user.setEmail(null);

        service.syncContact(USER_ID);

        verify(resend, never()).upsertContact(any(), any(), anyMap());
    }

    @Test
    @DisplayName("inside a transaction the work waits for the commit, and a rollback sends nothing")
    void deferredToAfterCommit() {
        TransactionSynchronizationManager.initSynchronization();

        service.emit(USER_ID, LifecycleEvents.USER_RETURNED, Map.of());

        verify(resend, never()).submit(any());
        // Rollback path: completion without commit never submits.
        for (TransactionSynchronization s : TransactionSynchronizationManager.getSynchronizations()) {
            s.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);
        }
        verify(resend, never()).submit(any());
        // Commit path.
        for (TransactionSynchronization s : TransactionSynchronizationManager.getSynchronizations()) {
            s.afterCommit();
        }
        verify(resend).sendEvent("ada@example.com", LifecycleEvents.USER_RETURNED, Map.of());
    }

    @Test
    @DisplayName("deleteContact submits a DELETE of that email")
    void deleteContactSubmitted() {
        service.deleteContact("gone@example.com");

        verify(resend).deleteContact("gone@example.com");
    }

    @Test
    @DisplayName("a plan lookup failure falls back to free instead of dropping the sync")
    void planFailureFallsBack() {
        when(planResolutionService.resolveBillingPlan(USER_ID)).thenThrow(new IllegalStateException("db"));

        service.syncContact(USER_ID);

        assertThat(syncedProperties()).containsEntry("plan", "free");
    }

    // --- submitLocalized: the events other services send ---

    @Test
    @DisplayName("submitLocalized builds the payload for the CONTACT's locale, after syncing the contact")
    void localizedPayloadUsesContactLocale() {
        user.setLocale("fr");

        LifecycleEmailService.Dispatch d = service.submitLocalized(USER_ID, LifecycleEvents.BADGE_UNLOCKED,
                locale -> Map.of("tier_name", "fr".equals(locale) ? "Or" : "Gold"), false);

        assertThat(d).isEqualTo(LifecycleEmailService.Dispatch.QUEUED);
        InOrder order = inOrder(resend);
        order.verify(resend).upsertContact(anyString(), any(), anyMap());
        order.verify(resend).sendEvent("ada@example.com", LifecycleEvents.BADGE_UNLOCKED, Map.of("tier_name", "Or"));
    }

    @Test
    @DisplayName("submitLocalized with no stored locale builds the English payload")
    void localizedPayloadFallsBackToEnglish() {
        service.submitLocalized(USER_ID, LifecycleEvents.BADGE_UNLOCKED, locale -> Map.of("locale", locale), false);

        verify(resend).sendEvent("ada@example.com", LifecycleEvents.BADGE_UNLOCKED, Map.of("locale", "en"));
    }

    @Test
    @DisplayName("bulk work goes through submitBulk, single events through submit")
    void bulkUsesTheHeadroomQueue() {
        service.submitLocalized(USER_ID, LifecycleEvents.RECAP_MONTHLY, locale -> Map.of(), true);
        verify(resend).submitBulk(any());
        verify(resend, never()).submit(any());

        service.submitLocalized(USER_ID, LifecycleEvents.BADGE_UNLOCKED, locale -> Map.of(), false);
        verify(resend).submit(any());
    }

    @Test
    @DisplayName("a refused enqueue is reported BUSY (the caller retries later), never dropped silently")
    void refusedEnqueueIsBusy() {
        org.mockito.Mockito.doReturn(false).when(resend).submitBulk(any());

        assertThat(service.submitLocalized(USER_ID, LifecycleEvents.RECAP_MONTHLY, locale -> Map.of(), true))
                .isEqualTo(LifecycleEmailService.Dispatch.BUSY);
        verify(resend, never()).sendEvent(anyString(), anyString(), anyMap());
    }

    @Test
    @DisplayName("inactive client: INACTIVE, nothing read, nothing queued")
    void localizedInactive() {
        when(resend.isActive()).thenReturn(false);

        assertThat(service.submitLocalized(USER_ID, LifecycleEvents.BADGE_UNLOCKED, locale -> Map.of(), false))
                .isEqualTo(LifecycleEmailService.Dispatch.INACTIVE);
        verify(resend, never()).submit(any());
        verifyNoInteractions(userRepository);
    }

    @Test
    @DisplayName("a deactivated or unverified account gets no localized event either")
    void localizedSkipsIneligibleAccounts() {
        user.setEmailVerified(false);

        service.submitLocalized(USER_ID, LifecycleEvents.RECAP_MONTHLY, locale -> Map.of(), true);

        verify(resend, never()).sendEvent(anyString(), anyString(), anyMap());
    }

    // ---- emitIfClaimed: the window is claimed only after commit, after the queue took the task ----

    @Test
    @DisplayName("emitIfClaimed: a granted claim sends the event (contact untouched)")
    void claimedEventIsSent() {
        java.util.concurrent.atomic.AtomicInteger claims = new java.util.concurrent.atomic.AtomicInteger();

        service.emitIfClaimed(USER_ID, LifecycleEvents.CHECKOUT_STARTED, Map.of("kind", "credits"),
                () -> claims.incrementAndGet() > 0);

        assertThat(claims.get()).isEqualTo(1);
        verify(resend).sendEvent("ada@example.com", LifecycleEvents.CHECKOUT_STARTED, Map.of("kind", "credits"));
        verify(resend, never()).upsertContact(anyString(), any(), anyMap());
    }

    @Test
    @DisplayName("emitIfClaimed: a refused claim sends nothing")
    void refusedClaimSendsNothing() {
        service.emitIfClaimed(USER_ID, LifecycleEvents.CHECKOUT_STARTED, Map.of(), () -> false);

        verify(resend, never()).sendEvent(anyString(), anyString(), anyMap());
    }

    @Test
    @DisplayName("Regression (window lost): a task the full queue dropped never claims the window")
    void droppedTaskNeverClaims() {
        org.mockito.Mockito.doReturn(false).when(resend).submit(any()); // refused, the task never runs
        java.util.concurrent.atomic.AtomicInteger claims = new java.util.concurrent.atomic.AtomicInteger();

        service.emitIfClaimed(USER_ID, LifecycleEvents.CHECKOUT_STARTED, Map.of(), () -> claims.incrementAndGet() > 0);

        assertThat(claims.get()).isZero();
    }

    @Test
    @DisplayName("Regression (checkout lost): inside a transaction nothing is claimed before commit, and a rollback claims nothing")
    void claimWaitsForCommit() {
        java.util.concurrent.atomic.AtomicInteger claims = new java.util.concurrent.atomic.AtomicInteger();
        TransactionSynchronizationManager.initSynchronization();

        service.emitIfClaimed(USER_ID, LifecycleEvents.CHECKOUT_STARTED, Map.of(), () -> claims.incrementAndGet() > 0);

        assertThat(claims.get()).as("not claimed inside the caller's transaction").isZero();
        for (TransactionSynchronization s : TransactionSynchronizationManager.getSynchronizations()) {
            s.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);
        }
        assertThat(claims.get()).as("a rolled-back checkout claims nothing").isZero();
        for (TransactionSynchronization s : TransactionSynchronizationManager.getSynchronizations()) {
            s.afterCommit();
        }
        assertThat(claims.get()).as("claimed once the checkout committed").isEqualTo(1);
    }

    @Test
    @DisplayName("emitIfClaimed: an account that would receive nothing (unverified) never consumes the window")
    void ineligibleAccountNeverClaims() {
        user.setEmailVerified(false);
        java.util.concurrent.atomic.AtomicInteger claims = new java.util.concurrent.atomic.AtomicInteger();

        service.emitIfClaimed(USER_ID, LifecycleEvents.CHECKOUT_STARTED, Map.of(), () -> claims.incrementAndGet() > 0);

        assertThat(claims.get()).isZero();
        verify(resend, never()).sendEvent(anyString(), anyString(), anyMap());
    }

    @Test
    @DisplayName("emitIfClaimed: inactive (CE) claims nothing")
    void inactiveNeverClaims() {
        when(resend.isActive()).thenReturn(false);
        java.util.concurrent.atomic.AtomicInteger claims = new java.util.concurrent.atomic.AtomicInteger();

        service.emitIfClaimed(USER_ID, LifecycleEvents.CHECKOUT_STARTED, Map.of(), () -> claims.incrementAndGet() > 0);

        assertThat(claims.get()).isZero();
        verify(resend, never()).submit(any());
    }
    // ---- syncContactAndEmitIfClaimed: user.signed_up, claimed on the worker right before the send ----

    @Test
    @DisplayName("syncContactAndEmitIfClaimed: a granted claim syncs the contact THEN sends the event")
    void claimedSignupSyncsThenSends() {
        service.syncContactAndEmitIfClaimed(USER_ID, LifecycleEvents.USER_SIGNED_UP, Map.of(), () -> true);

        org.mockito.InOrder order = org.mockito.Mockito.inOrder(resend);
        order.verify(resend).upsertContact(eq("ada@example.com"), eq("Ada"), anyMap());
        order.verify(resend).sendEvent("ada@example.com", LifecycleEvents.USER_SIGNED_UP, Map.of());
    }

    @Test
    @DisplayName("Regression (welcome lost): a refused enqueue leaves the signup stamp free, so a later attempt still sends")
    void refusedEnqueueLeavesSignupClaimFree() {
        java.util.concurrent.atomic.AtomicBoolean stamp = new java.util.concurrent.atomic.AtomicBoolean();
        LifecycleEmailService.Claim claim = () -> stamp.compareAndSet(false, true);
        org.mockito.Mockito.doReturn(false).when(resend).submit(any()); // full queue: the task never runs

        service.syncContactAndEmitIfClaimed(USER_ID, LifecycleEvents.USER_SIGNED_UP, Map.of(), claim);

        assertThat(stamp.get()).as("the refused task never claimed the stamp").isFalse();
        verify(resend, never()).sendEvent(anyString(), anyString(), anyMap());

        doAnswer(inv -> {
            ((Runnable) inv.getArgument(0)).run();
            return true;
        }).when(resend).submit(any());
        service.syncContactAndEmitIfClaimed(USER_ID, LifecycleEvents.USER_SIGNED_UP, Map.of(), claim);

        assertThat(stamp.get()).isTrue();
        verify(resend, times(1)).sendEvent("ada@example.com", LifecycleEvents.USER_SIGNED_UP, Map.of());
    }

    @Test
    @DisplayName("Regression (double welcome): two racing signups against one write-once stamp send exactly once")
    void racingSignupsSendOnce() {
        java.util.concurrent.atomic.AtomicBoolean stamp = new java.util.concurrent.atomic.AtomicBoolean();
        LifecycleEmailService.Claim claim = () -> stamp.compareAndSet(false, true);

        service.syncContactAndEmitIfClaimed(USER_ID, LifecycleEvents.USER_SIGNED_UP, Map.of(), claim);
        service.syncContactAndEmitIfClaimed(USER_ID, LifecycleEvents.USER_SIGNED_UP, Map.of(), claim);

        verify(resend, times(1)).sendEvent("ada@example.com", LifecycleEvents.USER_SIGNED_UP, Map.of());
        verify(resend, times(1)).upsertContact(anyString(), any(), anyMap());
    }

    @Test
    @DisplayName("syncContactAndEmitIfClaimed: an unverified account never consumes the signup stamp")
    void unverifiedAccountNeverClaimsSignup() {
        user.setEmailVerified(false);
        java.util.concurrent.atomic.AtomicInteger claims = new java.util.concurrent.atomic.AtomicInteger();

        service.syncContactAndEmitIfClaimed(USER_ID, LifecycleEvents.USER_SIGNED_UP, Map.of(),
                () -> claims.incrementAndGet() > 0);

        assertThat(claims.get()).isZero();
        verify(resend, never()).upsertContact(anyString(), any(), anyMap());
    }

    // ---- a granted claim is only spent by an event that actually left Resend ----

    /** A write-once stamp standing for the database column, with the release contract of production. */
    private static final class StampClaim implements LifecycleEmailService.Claim {
        final java.util.concurrent.atomic.AtomicReference<String> column;
        final String mine;
        int releases;

        StampClaim(java.util.concurrent.atomic.AtomicReference<String> column, String mine) {
            this.column = column;
            this.mine = mine;
        }

        @Override
        public boolean getAsBoolean() {
            return column.compareAndSet(null, mine);
        }

        @Override
        public void release() {
            releases++;
            column.compareAndSet(mine, null);
        }
    }

    private ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> captureLogs() {
        ch.qos.logback.classic.Logger logger =
                (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(LifecycleEmailService.class);
        ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender =
                new ch.qos.logback.core.read.ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        return appender;
    }

    private static void detach(ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender) {
        ((ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(LifecycleEmailService.class))
                .detachAppender(appender);
    }

    private static java.util.List<String> warns(
            ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender) {
        return appender.list.stream()
                .filter(e -> e.getLevel() == ch.qos.logback.classic.Level.WARN)
                .map(ch.qos.logback.classic.spi.ILoggingEvent::getFormattedMessage)
                .toList();
    }

    @Test
    @DisplayName("Regression (welcome lost): a signup send that fails after the claim gives the stamp back and WARNs, so a retry still sends")
    void failedSignupSendReleasesTheStamp() {
        java.util.concurrent.atomic.AtomicReference<String> stamp = new java.util.concurrent.atomic.AtomicReference<>();
        when(resend.sendEvent(anyString(), eq(LifecycleEvents.USER_SIGNED_UP), anyMap())).thenReturn(false, true);
        var logs = captureLogs();
        try {
            service.syncContactAndEmitIfClaimed(USER_ID, LifecycleEvents.USER_SIGNED_UP, Map.of(),
                    new StampClaim(stamp, "attempt-1"));
        } finally {
            detach(logs);
        }

        assertThat(stamp.get()).as("the stamp of a welcome that never left is free again").isNull();
        assertThat(warns(logs)).singleElement().asString()
                .contains(LifecycleEvents.USER_SIGNED_UP).contains("user " + USER_ID).contains("released")
                .doesNotContain("ada@example.com");

        service.syncContactAndEmitIfClaimed(USER_ID, LifecycleEvents.USER_SIGNED_UP, Map.of(),
                new StampClaim(stamp, "attempt-2"));

        assertThat(stamp.get()).as("the retry claimed it and kept it").isEqualTo("attempt-2");
        verify(resend, times(2)).sendEvent("ada@example.com", LifecycleEvents.USER_SIGNED_UP, Map.of());
    }

    @Test
    @DisplayName("Regression (recovery email lost): a checkout.started send that fails gives the 24 h window back and WARNs")
    void failedCheckoutSendReleasesTheWindow() {
        java.util.concurrent.atomic.AtomicReference<String> window = new java.util.concurrent.atomic.AtomicReference<>();
        when(resend.sendEvent(anyString(), eq(LifecycleEvents.CHECKOUT_STARTED), anyMap())).thenReturn(false);
        StampClaim claim = new StampClaim(window, "t0");
        var logs = captureLogs();
        try {
            service.emitIfClaimed(USER_ID, LifecycleEvents.CHECKOUT_STARTED, Map.of("kind", "subscription"), claim);
        } finally {
            detach(logs);
        }

        assertThat(claim.releases).isEqualTo(1);
        assertThat(window.get()).isNull();
        assertThat(warns(logs)).singleElement().asString().contains(LifecycleEvents.CHECKOUT_STARTED).contains("released");
    }

    @Test
    @DisplayName("a send that succeeds keeps its claim: nothing is released and nothing is logged at WARN")
    void successfulSendKeepsTheClaim() {
        java.util.concurrent.atomic.AtomicReference<String> stamp = new java.util.concurrent.atomic.AtomicReference<>();
        StampClaim claim = new StampClaim(stamp, "attempt-1");
        var logs = captureLogs();
        try {
            service.syncContactAndEmitIfClaimed(USER_ID, LifecycleEvents.USER_SIGNED_UP, Map.of(), claim);
        } finally {
            detach(logs);
        }

        assertThat(claim.releases).isZero();
        assertThat(stamp.get()).isEqualTo("attempt-1");
        assertThat(warns(logs)).isEmpty();
    }

    @Test
    @DisplayName("a refused claim is never released: a failed send only gives back what THIS attempt took")
    void refusedClaimIsNeverReleased() {
        java.util.concurrent.atomic.AtomicReference<String> stamp =
                new java.util.concurrent.atomic.AtomicReference<>("winner");
        when(resend.sendEvent(anyString(), anyString(), anyMap())).thenReturn(false);
        StampClaim loser = new StampClaim(stamp, "loser");

        service.syncContactAndEmitIfClaimed(USER_ID, LifecycleEvents.USER_SIGNED_UP, Map.of(), loser);

        assertThat(loser.releases).isZero();
        assertThat(stamp.get()).isEqualTo("winner");
        verify(resend, never()).sendEvent(anyString(), anyString(), anyMap());
    }

    @Test
    @DisplayName("Regression (silent drop): a signup the full queue refused is logged at WARN")
    void refusedEnqueueWarns() {
        org.mockito.Mockito.doReturn(false).when(resend).submit(any());
        var logs = captureLogs();
        try {
            service.syncContactAndEmitIfClaimed(USER_ID, LifecycleEvents.USER_SIGNED_UP, Map.of(), () -> true);
        } finally {
            detach(logs);
        }

        assertThat(warns(logs)).singleElement().asString()
                .contains(LifecycleEvents.USER_SIGNED_UP).contains("user " + USER_ID).contains("not queued");
    }

    // ── lifecycle_event_sent analytics ─────────────────────────────────────────

    private com.apimarketplace.auth.analytics.AuthAnalyticsEmitter wireAnalytics() {
        com.apimarketplace.auth.analytics.AuthAnalyticsEmitter analytics =
                org.mockito.Mockito.mock(com.apimarketplace.auth.analytics.AuthAnalyticsEmitter.class);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "analytics", analytics);
        return analytics;
    }

    @Test
    @DisplayName("lifecycle_event_sent: a delivered event is counted with its name and delivered=true")
    void analyticsDeliveredEvent() {
        var analytics = wireAnalytics();

        service.syncContactAndEmit(USER_ID, LifecycleEvents.USER_ACTIVATED, Map.of());

        verify(analytics).lifecycleEventSent(USER_ID, LifecycleEvents.USER_ACTIVATED, true);
    }

    @Test
    @DisplayName("lifecycle_event_sent: an event Resend refused is counted as delivered=false")
    void analyticsUndeliveredEvent() {
        var analytics = wireAnalytics();
        when(resend.sendEvent(anyString(), anyString(), anyMap())).thenReturn(false);

        service.emit(USER_ID, LifecycleEvents.USER_ACTIVATED, Map.of());

        verify(analytics).lifecycleEventSent(USER_ID, LifecycleEvents.USER_ACTIVATED, false);
    }

    @Test
    @DisplayName("lifecycle_event_sent: a contact-only sync, an ineligible account or a refused claim sends no event and counts none")
    void analyticsNothingAttempted() {
        var analytics = wireAnalytics();

        service.syncContact(USER_ID);
        service.emitIfClaimed(USER_ID, LifecycleEvents.USER_ACTIVATED, Map.of(), () -> false);
        user.setEmailVerified(false);
        service.emit(USER_ID, LifecycleEvents.USER_ACTIVATED, Map.of());

        verify(analytics, never()).lifecycleEventSent(any(), any(), org.mockito.ArgumentMatchers.anyBoolean());
    }

    @Test
    @DisplayName("lifecycle_event_sent: a failing emitter never breaks the send")
    void analyticsFailureIsContained() {
        var analytics = wireAnalytics();
        org.mockito.Mockito.doThrow(new RuntimeException("posthog down"))
                .when(analytics).lifecycleEventSent(any(), any(), org.mockito.ArgumentMatchers.anyBoolean());

        service.emit(USER_ID, LifecycleEvents.USER_ACTIVATED, Map.of());

        verify(resend).sendEvent("ada@example.com", LifecycleEvents.USER_ACTIVATED, Map.of());
    }
}
