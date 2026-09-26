package com.apimarketplace.orchestrator.services.badge;

import com.apimarketplace.auth.client.AuthClient;
import com.apimarketplace.orchestrator.domain.badge.UserBadgeEntity;
import com.apimarketplace.orchestrator.repository.UserBadgeRepository;
import com.apimarketplace.orchestrator.services.lifecycle.TrophyEmailReporter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("BadgeService")
class BadgeServiceTest {

    private static final String TENANT = "42";
    private static final String ORG = "org-7";

    @Mock private UserBadgeRepository badgeRepository;
    @Mock private BadgeStatsCollector statsCollector;
    @Mock private BadgeNotificationEmitter notificationEmitter;
    @Mock private AuthClient authClient;
    @Mock private TrophyEmailReporter trophyEmails;

    private BadgeService service;

    @BeforeEach
    void setUp() {
        service = new BadgeService(badgeRepository, statsCollector, notificationEmitter, authClient, trophyEmails);
    }

    /** No unlock rows: every badge is a candidate. */
    private void noBadgesUnlockedYet() {
        when(badgeRepository.findByTenantIdOrderByUnlockedAtDesc(TENANT)).thenReturn(List.of());
    }

    private void statsReturn(BadgeStats stats) {
        when(statsCollector.collect(eq(TENANT), any())).thenReturn(stats);
    }

    /** Every insert reports "row created" - the normal, uncontended case. */
    private void insertsSucceed() {
        when(badgeRepository.insertIfAbsent(anyString(), anyString(), anyLong(), any())).thenReturn(1);
    }

    @Test
    @DisplayName("unlocks exactly the badges whose threshold the metric has reached")
    void unlocksOnlyBadgesAtOrAboveThreshold() {
        noBadgesUnlockedYet();
        // 5 workflows: builder_1 (1) and builder_5 (5) qualify, builder_10 does not.
        statsReturn(BadgeStats.builder().put(BadgeMetric.WORKFLOWS_CREATED, 5).build());
        insertsSucceed();

        List<String> unlocked = service.evaluate(TENANT, ORG).unlocked().stream()
                .map(BadgeDefinition::code)
                .toList();

        assertThat(unlocked).containsExactly("builder_1", "builder_5");
        verify(badgeRepository).insertIfAbsent(eq(TENANT), eq("builder_1"), eq(5L), any());
        verify(badgeRepository).insertIfAbsent(eq(TENANT), eq("builder_5"), eq(5L), any());
        verify(badgeRepository, never()).insertIfAbsent(eq(TENANT), eq("builder_10"), anyLong(), any());
    }

    @Test
    @DisplayName("a badge the user already holds is not re-evaluated or re-notified")
    void alreadyUnlockedBadgeIsSkipped() {
        when(badgeRepository.findByTenantIdOrderByUnlockedAtDesc(TENANT)).thenReturn(List.of(
                new UserBadgeEntity(TENANT, "builder_1", 1, Instant.now())));
        statsReturn(BadgeStats.builder().put(BadgeMetric.WORKFLOWS_CREATED, 5).build());
        insertsSucceed();

        List<String> unlocked = service.evaluate(TENANT, ORG).unlocked().stream()
                .map(BadgeDefinition::code)
                .toList();

        assertThat(unlocked).containsExactly("builder_5");
        verify(badgeRepository, never()).insertIfAbsent(eq(TENANT), eq("builder_1"), anyLong(), any());
        verify(notificationEmitter, never()).emitUnlocked(any(), any(), badgeWithCode("builder_1"), anyLong());
    }

    @Test
    @DisplayName("a lost insert race notifies nobody - the winning evaluator already did")
    void insertRaceLoserDoesNotNotify() {
        noBadgesUnlockedYet();
        statsReturn(BadgeStats.builder().put(BadgeMetric.WORKFLOWS_CREATED, 1).build());
        // 0 rows inserted = ON CONFLICT DO NOTHING fired: another replica (or the
        // background pass racing the page load) got there first.
        when(badgeRepository.insertIfAbsent(anyString(), anyString(), anyLong(), any())).thenReturn(0);

        BadgeService.Evaluation evaluation = service.evaluate(TENANT, ORG);

        assertThat(evaluation.unlocked()).isEmpty();
        verifyNoInteractions(notificationEmitter);
    }

    @Test
    @DisplayName("a fully decorated user costs one query - no metric is collected")
    void fullyDecoratedUserSkipsMetricCollection() {
        when(badgeRepository.findByTenantIdOrderByUnlockedAtDesc(TENANT)).thenReturn(
                BadgeCatalog.all().stream()
                        .map(d -> new UserBadgeEntity(TENANT, d.code(), d.threshold(), Instant.now()))
                        .toList());

        BadgeService.Evaluation evaluation = service.evaluate(TENANT, ORG);

        assertThat(evaluation.unlocked()).isEmpty();
        verifyNoInteractions(statsCollector);
        verifyNoInteractions(notificationEmitter);
    }

    @Test
    @DisplayName("only the metrics behind a still-locked badge are collected")
    void collectsOnlyTheMetricsStillNeeded() {
        // Everything unlocked except the two easiest workflow badges, so only
        // WORKFLOWS_CREATED should be read.
        when(badgeRepository.findByTenantIdOrderByUnlockedAtDesc(TENANT)).thenReturn(
                BadgeCatalog.all().stream()
                        .filter(d -> !d.code().equals("builder_1") && !d.code().equals("builder_5"))
                        .map(d -> new UserBadgeEntity(TENANT, d.code(), d.threshold(), Instant.now()))
                        .toList());
        statsReturn(BadgeStats.builder().put(BadgeMetric.WORKFLOWS_CREATED, 0).build());

        service.evaluate(TENANT, ORG);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Set<BadgeMetric>> captor = ArgumentCaptor.forClass(Set.class);
        verify(statsCollector).collect(eq(TENANT), captor.capture());
        assertThat(captor.getValue()).containsExactly(BadgeMetric.WORKFLOWS_CREATED);
    }

    @Test
    @DisplayName("each unlock notifies once, carrying the value that earned it")
    void notifiesOncePerUnlockWithTheEarningValue() {
        noBadgesUnlockedYet();
        statsReturn(BadgeStats.builder().put(BadgeMetric.WORKFLOWS_CREATED, 7).build());
        insertsSucceed();

        service.evaluate(TENANT, ORG);

        verify(notificationEmitter).emitUnlocked(eq(TENANT), eq(ORG), badgeWithCode("builder_1"), eq(7L));
        verify(notificationEmitter).emitUnlocked(eq(TENANT), eq(ORG), badgeWithCode("builder_5"), eq(7L));
    }

    @Test
    @DisplayName("with no active workspace the unlock notification lands in the user's personal one")
    void fallsBackToDefaultPersonalOrgForNotifications() {
        noBadgesUnlockedYet();
        statsReturn(BadgeStats.builder().put(BadgeMetric.WORKFLOWS_CREATED, 1).build());
        insertsSucceed();
        when(authClient.getDefaultOrganizationIdForUser(TENANT)).thenReturn("personal-42");

        service.evaluate(TENANT, null);

        verify(notificationEmitter).emitUnlocked(eq(TENANT), eq("personal-42"), any(), anyLong());
    }

    @Test
    @DisplayName("an unlocked badge reports the value frozen at unlock, not the metric as it stands now")
    void unlockedBadgeKeepsItsFrozenValue() {
        // Earned at 12 workflows, but the user has since deleted down to 2.
        when(badgeRepository.findByTenantIdOrderByUnlockedAtDesc(TENANT)).thenReturn(List.of(
                new UserBadgeEntity(TENANT, "builder_10", 12, Instant.parse("2026-01-01T00:00:00Z"))));
        statsReturn(BadgeStats.builder().put(BadgeMetric.WORKFLOWS_CREATED, 2).build());

        BadgeView view = service.getBadgesForUser(TENANT, ORG).stream()
                .filter(b -> b.code().equals("builder_10"))
                .findFirst()
                .orElseThrow();

        assertThat(view.unlocked()).isTrue();
        assertThat(view.value()).isEqualTo(12);
        assertThat(view.unlockedAt()).isEqualTo(Instant.parse("2026-01-01T00:00:00Z"));
    }

    @Test
    @DisplayName("a metric that has dropped below the threshold never revokes the badge")
    void droppedMetricDoesNotRevoke() {
        when(badgeRepository.findByTenantIdOrderByUnlockedAtDesc(TENANT)).thenReturn(List.of(
                new UserBadgeEntity(TENANT, "builder_10", 12, Instant.now())));
        statsReturn(BadgeStats.builder().put(BadgeMetric.WORKFLOWS_CREATED, 0).build());

        service.evaluate(TENANT, ORG);

        verify(badgeRepository, never()).delete(any());
        verify(badgeRepository, never()).deleteById(any());
    }

    @Test
    @DisplayName("the grid returns every catalog badge, locked ones carrying live progress")
    void gridCoversTheWholeCatalogWithProgress() {
        noBadgesUnlockedYet();
        statsReturn(BadgeStats.builder().put(BadgeMetric.WORKFLOWS_CREATED, 3).build());
        when(badgeRepository.insertIfAbsent(anyString(), anyString(), anyLong(), any())).thenReturn(1);

        List<BadgeView> views = service.getBadgesForUser(TENANT, ORG);

        assertThat(views).hasSameSizeAs(BadgeCatalog.all());
        BadgeView builder10 = views.stream().filter(v -> v.code().equals("builder_10")).findFirst().orElseThrow();
        assertThat(builder10.unlocked()).isFalse();
        assertThat(builder10.value()).isEqualTo(3);
        assertThat(builder10.threshold()).isEqualTo(10);
    }

    @Test
    @DisplayName("the public view exposes unlocked badges only, and reads no metrics")
    void publicViewIsUnlockedOnlyAndReadsNoMetrics() {
        when(badgeRepository.findByTenantIdOrderByUnlockedAtDesc(TENANT)).thenReturn(List.of(
                new UserBadgeEntity(TENANT, "builder_1", 1, Instant.now())));

        List<BadgeView> views = service.getPublicBadgesForUser(TENANT);

        assertThat(views).extracting(BadgeView::code).containsExactly("builder_1");
        // A visitor must not be able to make the profile owner's metrics run.
        verifyNoInteractions(statsCollector);
        verifyNoInteractions(notificationEmitter);
    }

    @Test
    @DisplayName("an unlock row whose code left the catalog is ignored instead of breaking the grid")
    void retiredBadgeCodeIsIgnored() {
        when(badgeRepository.findByTenantIdOrderByUnlockedAtDesc(TENANT)).thenReturn(List.of(
                new UserBadgeEntity(TENANT, "badge_from_a_previous_release", 99, Instant.now())));
        // Every metric reads 0, so nothing new unlocks and the only row in play
        // is the retired one.
        statsReturn(BadgeStats.builder().build());

        List<BadgeView> views = service.getBadgesForUser(TENANT, ORG);

        assertThat(views).hasSameSizeAs(BadgeCatalog.all());
        assertThat(views).noneMatch(v -> v.code().equals("badge_from_a_previous_release"));
    }

    @Test
    @DisplayName("a first pass that unlocks a whole history is a backfill and announces nothing")
    void firstPassBackfillDoesNotSpamTheBell() {
        noBadgesUnlockedYet();
        // An established user on the day the feature ships: years of activity,
        // so this single pass clears well over a dozen badges at once.
        statsReturn(BadgeStats.builder()
                .put(BadgeMetric.WORKFLOWS_CREATED, 60)
                .put(BadgeMetric.RUNS_LAUNCHED, 5_000)
                .put(BadgeMetric.MEMBER_DAYS, 400)
                .build());
        insertsSucceed();

        BadgeService.Evaluation evaluation = service.evaluate(TENANT, ORG);

        // The badges ARE awarded - only the announcement is suppressed.
        assertThat(evaluation.unlocked().size()).isGreaterThan(5);
        verifyNoInteractions(notificationEmitter);
        // Nor emailed: a trophy email for something done months ago is spam, not news.
        verifyNoInteractions(trophyEmails);
    }

    @Test
    @DisplayName("a new user's small first pass is announced normally")
    void smallFirstPassIsStillAnnounced() {
        noBadgesUnlockedYet();
        statsReturn(BadgeStats.builder().put(BadgeMetric.WORKFLOWS_CREATED, 5).build());
        insertsSucceed();

        BadgeService.Evaluation evaluation = service.evaluate(TENANT, ORG);

        assertThat(evaluation.unlocked()).hasSize(2);
        verify(notificationEmitter).emitUnlocked(eq(TENANT), eq(ORG), badgeWithCode("builder_1"), anyLong());
        verify(notificationEmitter).emitUnlocked(eq(TENANT), eq(ORG), badgeWithCode("builder_5"), anyLong());
    }

    @Test
    @DisplayName("a large LATER pass is announced - only the very first one can be a backfill")
    void laterBulkUnlockIsStillAnnounced() {
        // The user already holds one badge, so this is not a first pass.
        when(badgeRepository.findByTenantIdOrderByUnlockedAtDesc(TENANT)).thenReturn(List.of(
                new UserBadgeEntity(TENANT, "builder_1", 1, Instant.now())));
        statsReturn(BadgeStats.builder()
                .put(BadgeMetric.WORKFLOWS_CREATED, 60)
                .put(BadgeMetric.RUNS_LAUNCHED, 5_000)
                .build());
        insertsSucceed();

        BadgeService.Evaluation evaluation = service.evaluate(TENANT, ORG);

        assertThat(evaluation.unlocked().size()).isGreaterThan(5);
        verify(notificationEmitter, org.mockito.Mockito.atLeast(6))
                .emitUnlocked(eq(TENANT), eq(ORG), any(), anyLong());
    }

    @Test
    @DisplayName("the public view reports the threshold, never the owner's raw activity count")
    void publicViewHidesTheOwnersRawMetric() {
        when(badgeRepository.findByTenantIdOrderByUnlockedAtDesc(TENANT)).thenReturn(List.of(
                new UserBadgeEntity(TENANT, "operator_100", 8_432, Instant.now())));

        BadgeView view = service.getPublicBadgesForUser(TENANT).get(0);

        assertThat(view.code()).isEqualTo("operator_100");
        assertThat(view.value())
                .as("a stranger must not learn the owner ran 8,432 executions")
                .isEqualTo(view.threshold());
    }

    @Test
    @DisplayName("a blank tenant is a no-op rather than a query")
    void blankTenantIsANoOp() {
        assertThat(service.evaluate("  ", ORG).unlocked()).isEmpty();
        verifyNoInteractions(badgeRepository);
        verifyNoInteractions(statsCollector);
    }

    @Test
    @DisplayName("the trophy email hook gets exactly this pass's inserted unlocks and what was held before")
    void trophyEmailHookGetsThisPassOnly() {
        when(badgeRepository.findByTenantIdOrderByUnlockedAtDesc(TENANT)).thenReturn(List.of(
                new UserBadgeEntity(TENANT, "builder_1", 1, Instant.now())));
        statsReturn(BadgeStats.builder().put(BadgeMetric.PUBLICATIONS_PUBLISHED, 1).build());
        insertsSucceed();

        service.evaluate(TENANT, ORG);

        verify(trophyEmails).badgesUnlocked(eq(TENANT),
                org.mockito.ArgumentMatchers.argThat(list -> list.stream().map(BadgeDefinition::code).toList()
                        .equals(List.of("publisher_1"))),
                eq(Set.of("builder_1")));
    }

    @Test
    @DisplayName("Regression (spam): a re-evaluation whose insert finds the row already there emails nothing")
    void lostInsertRaceNeverEmails() {
        noBadgesUnlockedYet();
        statsReturn(BadgeStats.builder().put(BadgeMetric.PUBLICATION_USES, 1).build());
        // The unlock row exists already (another pod, or the previous pass): ON CONFLICT DO NOTHING.
        when(badgeRepository.insertIfAbsent(anyString(), anyString(), anyLong(), any())).thenReturn(0);

        service.evaluate(TENANT, ORG);

        verifyNoInteractions(trophyEmails);
    }

    @Test
    @DisplayName("a badge already held is never handed to the trophy email hook again")
    void heldBadgeNeverReEmailed() {
        when(badgeRepository.findByTenantIdOrderByUnlockedAtDesc(TENANT)).thenReturn(List.of(
                new UserBadgeEntity(TENANT, "popularity_1", 1, Instant.now())));
        // Still at 1 install: nothing new to unlock in the popularity family.
        statsReturn(BadgeStats.builder().put(BadgeMetric.PUBLICATION_USES, 1).build());

        service.evaluate(TENANT, ORG);

        verifyNoInteractions(trophyEmails);
    }

    private static BadgeDefinition badgeWithCode(String code) {
        return org.mockito.ArgumentMatchers.argThat(d -> d != null && code.equals(d.code()));
    }

    @Test
    @DisplayName("analytics: every real insert reports badge_unlocked, and a backfill is flagged rather than hidden")
    void reportsEachUnlock() {
        com.apimarketplace.orchestrator.services.analytics.EngagementAnalyticsEmitter analytics = org.mockito.Mockito.mock(com.apimarketplace.orchestrator.services.analytics.EngagementAnalyticsEmitter.class);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "analytics", analytics);
        noBadgesUnlockedYet();
        statsReturn(BadgeStats.builder().put(BadgeMetric.WORKFLOWS_CREATED, 5).build());
        insertsSucceed();

        service.evaluate(TENANT, ORG);

        verify(analytics).badgeUnlocked(eq(TENANT), eq(ORG), badgeWithCode("builder_1"), eq(false));
        verify(analytics).badgeUnlocked(eq(TENANT), eq(ORG), badgeWithCode("builder_5"), eq(false));
        org.mockito.Mockito.verifyNoMoreInteractions(analytics);
    }

    @Test
    @DisplayName("analytics: a backfill pass reports each unlock with backfill=true and a lost insert reports nothing")
    void reportsBackfill() {
        com.apimarketplace.orchestrator.services.analytics.EngagementAnalyticsEmitter analytics = org.mockito.Mockito.mock(com.apimarketplace.orchestrator.services.analytics.EngagementAnalyticsEmitter.class);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "analytics", analytics);
        noBadgesUnlockedYet();
        statsReturn(BadgeStats.builder()
                .put(BadgeMetric.WORKFLOWS_CREATED, 60)
                .put(BadgeMetric.RUNS_LAUNCHED, 5_000)
                .put(BadgeMetric.MEMBER_DAYS, 400)
                .build());
        insertsSucceed();

        int unlocked = service.evaluate(TENANT, ORG).unlocked().size();

        verify(analytics, org.mockito.Mockito.times(unlocked)).badgeUnlocked(eq(TENANT), eq(ORG), any(), eq(true));
        verify(analytics, never()).badgeUnlocked(any(), any(), any(), eq(false));
    }
}
