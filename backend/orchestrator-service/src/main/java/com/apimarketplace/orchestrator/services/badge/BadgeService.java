package com.apimarketplace.orchestrator.services.badge;

import com.apimarketplace.auth.client.AuthClient;
import com.apimarketplace.orchestrator.domain.badge.UserBadgeEntity;
import com.apimarketplace.orchestrator.repository.UserBadgeRepository;
import com.apimarketplace.orchestrator.services.analytics.EngagementAnalyticsEmitter;
import com.apimarketplace.orchestrator.services.lifecycle.TrophyEmailReporter;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Owns the trophy lifecycle: measure a user against the catalog, persist the
 * new unlocks, notify, and serve the grid.
 *
 * <p><b>When evaluation happens.</b> Two triggers, no producer hooks:
 * <ul>
 *   <li><b>Reading your own trophies</b> evaluates inline, unthrottled. The page
 *       has to read the same metrics anyway to draw progress bars, so unlocking
 *       from that same snapshot is free - and it removes the state where a bar
 *       reads "10 / 10" beside a badge that is still locked.</li>
 *   <li><b>The bell's home-status poll</b> evaluates in the background, heavily
 *       throttled. That poll is the cheapest available signal that a user is
 *       active right now, which is what keeps unlock notifications timely
 *       without wiring badge logic into workflow creation, run completion and
 *       publishing across four services.</li>
 * </ul>
 *
 * <p><b>Unlocks are permanent.</b> Nothing here ever deletes a row. Metrics can
 * go down (a workflow is deleted, a publication is retired) and a trophy that
 * evaporated would be worse than one that is slightly generous.
 */
@Service
public class BadgeService {

    private static final Logger log = LoggerFactory.getLogger(BadgeService.class);

    /**
     * Minimum gap between two POLL-triggered evaluations of the same user on one
     * instance. The bell polls every 60s per signed-in user; without this the
     * metric queries would run on that cadence for the whole active user base.
     * Per instance rather than cluster-wide on purpose - the unlock insert is
     * idempotent, so several replicas evaluating the same user costs redundant
     * reads, never a duplicate badge or a duplicate notification.
     */
    private static final Duration POLL_EVALUATION_THROTTLE = Duration.ofMinutes(10);

    /**
     * Above this many unlocks in a user's FIRST evaluation, the pass is treated
     * as a backfill and announces nothing.
     *
     * <p>The day this feature ships, an established user's first evaluation
     * unlocks everything they earned over months at once - twenty-odd bell rows
     * for things they did long ago, which is spam, not news. A genuinely new
     * user's first pass unlocks a handful (their cohort badge, maybe a first
     * workflow), stays under the bar, and is announced normally. Only the FIRST
     * pass can be a backfill: after it, every unlock is a real event and is
     * always announced however many land together.
     */
    private static final int BACKFILL_NOTIFICATION_THRESHOLD = 5;

    private final UserBadgeRepository badgeRepository;
    private final BadgeStatsCollector statsCollector;
    private final BadgeNotificationEmitter notificationEmitter;
    private final AuthClient authClient;
    private final TrophyEmailReporter trophyEmails;

    /** Product analytics; optional so a unit test can build the service without it. */
    @Autowired(required = false)
    private EngagementAnalyticsEmitter analytics;

    /** tenantId -> claimed poll slot. Bounded, so a large user base cannot leak. */
    private final Cache<String, Instant> pollSlots = Caffeine.newBuilder()
            .expireAfterWrite(POLL_EVALUATION_THROTTLE)
            .maximumSize(50_000)
            .build();

    /**
     * Single background worker for poll-triggered evaluations. One thread is
     * enough: the work is throttled per user, and a small bounded queue with a
     * discard policy makes a burst degrade into "evaluated a bit later" rather
     * than piling up threads behind a slow sibling service.
     */
    private final ThreadPoolExecutor evaluationExecutor = new ThreadPoolExecutor(
            1, 1, 60L, TimeUnit.SECONDS, new ArrayBlockingQueue<>(200),
            runnable -> {
                Thread thread = new Thread(runnable, "badge-evaluator");
                thread.setDaemon(true);
                return thread;
            },
            new ThreadPoolExecutor.DiscardPolicy());

    public BadgeService(UserBadgeRepository badgeRepository,
                        BadgeStatsCollector statsCollector,
                        BadgeNotificationEmitter notificationEmitter,
                        AuthClient authClient,
                        TrophyEmailReporter trophyEmails) {
        this.badgeRepository = badgeRepository;
        this.statsCollector = statsCollector;
        this.notificationEmitter = notificationEmitter;
        this.authClient = authClient;
        this.trophyEmails = trophyEmails;
    }

    /**
     * The full grid for one user: every badge in catalog order, the unlocked
     * ones carrying their date and frozen value, the locked ones carrying live
     * progress toward their threshold.
     *
     * <p>Evaluates first, from the SAME metric snapshot the progress bars use,
     * so opening the page right after crossing a threshold shows the new trophy
     * in that very response.
     */
    public List<BadgeView> getBadgesForUser(String tenantId, String organizationId) {
        Evaluation evaluation = evaluate(tenantId, organizationId);
        return buildViews(tenantId, evaluation.stats());
    }

    /**
     * The unlocked badges only, for someone else's public profile. Never
     * evaluates and never reads metrics: a visitor must not be able to trigger
     * work on the profile owner's behalf, and how close a stranger is to their
     * next trophy is nobody else's business.
     */
    public List<BadgeView> getPublicBadgesForUser(String tenantId) {
        return buildViews(tenantId, BadgeStats.empty()).stream()
                .filter(BadgeView::unlocked)
                // The frozen value is the owner's raw activity ("earned at 1,043
                // runs"), which the public page does not render and a stranger
                // has no business reading off the JSON. Reporting the threshold
                // instead says exactly what the trophy says and nothing more.
                .map(view -> new BadgeView(view.code(), view.family(), view.tier(), view.metric(),
                        view.threshold(), view.threshold(), true, view.unlockedAt()))
                .toList();
    }

    /**
     * Evaluate off the request thread, at most once per
     * {@link #POLL_EVALUATION_THROTTLE} per user per instance.
     *
     * <p>The executor drops work when saturated rather than queueing it: a
     * skipped evaluation costs nothing because unlocks are recomputed from
     * scratch every time, so the next poll simply picks it up.
     */
    public void evaluateInBackground(String tenantId, String organizationId) {
        if (tenantId == null || tenantId.isBlank()) return;
        // Claim BEFORE scheduling, so two concurrent polls cannot both queue an
        // evaluation for the same user.
        if (pollSlots.asMap().putIfAbsent(tenantId, Instant.now()) != null) return;
        try {
            evaluationExecutor.execute(() -> {
                try {
                    evaluate(tenantId, organizationId);
                } catch (RuntimeException ex) {
                    log.warn("[badges] background evaluation failed for tenant {}: {}",
                            tenantId, ex.getMessage());
                }
            });
        } catch (RuntimeException ex) {
            // DiscardPolicy does not throw, so this only fires during shutdown.
            log.debug("[badges] background evaluation not scheduled for {}: {}", tenantId, ex.getMessage());
        }
    }

    /** What one evaluation pass produced. */
    public record Evaluation(List<BadgeDefinition> unlocked, BadgeStats stats) {}

    /**
     * Compare the user's metrics against every badge they do not hold yet and
     * persist the ones they have earned.
     *
     * <p>{@code REQUIRES_NEW} applies only to callers OUTSIDE this bean: the read
     * path reaches this method by self-invocation, which bypasses the proxy. The
     * two writes therefore carry their own transaction annotations
     * ({@code UserBadgeRepository.insertIfAbsent},
     * {@code BadgeNotificationEmitter.emitUnlocked}) and do not depend on this
     * one. Never throws for a metric-source failure - the collector already
     * degrades an unreachable source to zero, which postpones an unlock instead
     * of failing the page.
     *
     * @param organizationId workspace to stamp on the unlock notifications. Null
     *                       resolves to the user's default personal workspace.
     * @return the badges unlocked by THIS call plus the metric snapshot used
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Evaluation evaluate(String tenantId, String organizationId) {
        if (tenantId == null || tenantId.isBlank()) {
            return new Evaluation(List.of(), BadgeStats.empty());
        }

        Set<String> alreadyUnlocked = unlockedCodes(tenantId);
        List<BadgeDefinition> candidates = BadgeCatalog.all().stream()
                .filter(d -> !alreadyUnlocked.contains(d.code()))
                .toList();
        if (candidates.isEmpty()) {
            // Fully decorated: no metric is worth reading, so the whole pass is
            // one indexed SELECT. This is what keeps the poll hook affordable.
            return new Evaluation(List.of(), BadgeStats.empty());
        }

        Set<BadgeMetric> needed = EnumSet.noneOf(BadgeMetric.class);
        candidates.forEach(d -> needed.add(d.metric()));
        BadgeStats stats = statsCollector.collect(tenantId, needed);

        Instant now = Instant.now();
        List<BadgeDefinition> unlocked = new ArrayList<>();
        List<Long> unlockValues = new ArrayList<>();
        for (BadgeDefinition definition : candidates) {
            long value = stats.get(definition.metric());
            if (!definition.isUnlockedBy(value)) continue;
            // insertIfAbsent returns 0 when a concurrent evaluator won the race,
            // which is exactly the case where we must NOT notify again.
            if (badgeRepository.insertIfAbsent(tenantId, definition.code(), value, now) > 0) {
                unlocked.add(definition);
                unlockValues.add(value);
            }
        }
        if (unlocked.isEmpty()) {
            return new Evaluation(List.of(), stats);
        }

        boolean backfill = alreadyUnlocked.isEmpty()
                && unlocked.size() > BACKFILL_NOTIFICATION_THRESHOLD;
        String orgId = backfill ? organizationId : resolveNotificationOrg(tenantId, organizationId);
        if (analytics != null) {
            // Every real insert, announced or not: a backfill is flagged, never hidden.
            for (BadgeDefinition definition : unlocked) {
                analytics.badgeUnlocked(tenantId, orgId, definition, backfill);
            }
        }
        if (!backfill) {
            for (int i = 0; i < unlocked.size(); i++) {
                notificationEmitter.emitUnlocked(tenantId, orgId, unlocked.get(i), unlockValues.get(i));
            }
            // Email only for the few unlocks worth one (popularity, first publication, gold or
            // platinum), and only from THIS pass's real inserts, so it can never repeat.
            trophyEmails.badgesUnlocked(tenantId, List.copyOf(unlocked), Set.copyOf(alreadyUnlocked));
        }
        log.info("[badges] tenant {} unlocked {} badge(s){}: {}", tenantId, unlocked.size(),
                backfill ? " (backfill, not announced)" : "",
                unlocked.stream().map(BadgeDefinition::code).toList());
        return new Evaluation(unlocked, stats);
    }

    /**
     * Workspace stamped on unlock notifications. The column is NOT NULL and the
     * bell reads strictly by it, so an unlock earned while browsing an org shows
     * up there; without a request context we fall back to the user's default
     * personal workspace, which is where a personal trophy belongs.
     */
    private String resolveNotificationOrg(String tenantId, String organizationId) {
        if (organizationId != null && !organizationId.isBlank()) {
            return organizationId;
        }
        try {
            return authClient.getDefaultOrganizationIdForUser(tenantId);
        } catch (RuntimeException ex) {
            log.warn("[badges] could not resolve default org for tenant {}: {}", tenantId, ex.getMessage());
            return null;
        }
    }

    private Set<String> unlockedCodes(String tenantId) {
        Set<String> codes = new java.util.HashSet<>();
        for (UserBadgeEntity row : badgeRepository.findByTenantIdOrderByUnlockedAtDesc(tenantId)) {
            codes.add(row.getBadgeCode());
        }
        return codes;
    }

    /**
     * Assemble the catalog and this user's unlock rows into view models.
     *
     * @param stats metric snapshot for the locked badges' progress. Pass
     *              {@link BadgeStats#empty()} to render without progress (the
     *              public profile, which shows unlocked badges only).
     */
    private List<BadgeView> buildViews(String tenantId, BadgeStats stats) {
        Map<String, UserBadgeEntity> unlockedByCode = new HashMap<>();
        for (UserBadgeEntity row : badgeRepository.findByTenantIdOrderByUnlockedAtDesc(tenantId)) {
            // Codes retired from the catalog keep their row but stop rendering.
            unlockedByCode.putIfAbsent(row.getBadgeCode(), row);
        }

        List<BadgeView> views = new ArrayList<>(BadgeCatalog.all().size());
        for (BadgeDefinition definition : BadgeCatalog.all()) {
            UserBadgeEntity row = unlockedByCode.get(definition.code());
            views.add(new BadgeView(
                    definition.code(),
                    definition.family(),
                    definition.tier(),
                    definition.metric(),
                    definition.threshold(),
                    // An unlocked badge shows the value frozen at unlock time, so
                    // a later drop in the metric cannot make a trophy read as if
                    // it were never earned.
                    row != null ? row.getProgressValue() : stats.get(definition.metric()),
                    row != null,
                    row != null ? row.getUnlockedAt() : null));
        }
        return views;
    }
}
