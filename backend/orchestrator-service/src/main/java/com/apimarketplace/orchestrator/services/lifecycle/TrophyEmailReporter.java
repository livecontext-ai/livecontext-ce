package com.apimarketplace.orchestrator.services.lifecycle;

import com.apimarketplace.auth.client.AuthClient;
import com.apimarketplace.common.web.AppEditionProvider;
import com.apimarketplace.orchestrator.services.badge.BadgeCatalog;
import com.apimarketplace.orchestrator.services.badge.BadgeDefinition;
import com.apimarketplace.orchestrator.services.badge.BadgeFamily;
import com.apimarketplace.orchestrator.services.badge.BadgeTier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Turns a trophy unlock into a lifecycle email ({@code badge.unlocked}), for three cases only:
 * <ol>
 *   <li><b>popularity</b>: any POPULARITY badge (someone else installed the user's app);</li>
 *   <li><b>first_publication</b>: the FIRST publisher badge ever, i.e. the first published app;</li>
 *   <li><b>top_tier</b>: any badge reaching GOLD or PLATINUM. DIAMOND is left out on purpose:
 *       the founder badge is DIAMOND and every 2026 account earns it, so it would be a
 *       mass-mailing, and the other DIAMOND badges are years away.</li>
 * </ol>
 * At most ONE email per evaluation pass (the most notable case, then the highest tier): a pass
 * that unlocks several qualifying badges at once must not send a burst.
 *
 * <p><b>Never twice for one unlock</b>: {@code BadgeService} calls this only with the badges
 * whose unlock row it really inserted in this pass ({@code insertIfAbsent > 0}) and never for a
 * first-pass backfill, so a re-evaluation, a lost insert race or a restart re-emits nothing.
 *
 * <p>Best-effort: after the unlock commits, on a small bounded background thread (dropped on
 * overflow), and it never throws, so auth-service or Resend being down never affects an
 * unlock. A no-op in a self-hosted edition.
 */
@Component
public class TrophyEmailReporter {

    private static final Logger log = LoggerFactory.getLogger(TrophyEmailReporter.class);

    static final String EVENT = "badge.unlocked";
    static final String KIND_POPULARITY = "popularity";
    static final String KIND_FIRST_PUBLICATION = "first_publication";
    static final String KIND_TOP_TIER = "top_tier";

    /** Which email one unlock is worth. Lower {@code rank} wins when a pass has several. */
    record TrophyEmail(String kind, int rank, BadgeDefinition badge) {
        Map<String, Object> payload() {
            return Map.of("kind", kind, "badge_code", badge.code(), "tier", badge.tier().name(),
                    "family", badge.family().name());
        }
    }

    private final AuthClient authClient;
    private final Executor executor;
    private final boolean selfHosted;

    @Autowired
    public TrophyEmailReporter(AuthClient authClient, AppEditionProvider editionProvider) {
        this(authClient, defaultExecutor(), editionProvider);
    }

    TrophyEmailReporter(AuthClient authClient, Executor executor, AppEditionProvider editionProvider) {
        this.authClient = authClient;
        this.executor = executor;
        this.selfHosted = editionProvider != null && editionProvider.isSelfHosted();
    }

    /**
     * The email one evaluation pass is worth, if any.
     *
     * @param unlockedNow badges whose unlock row THIS pass inserted
     * @param heldBefore  codes the user held before this pass
     */
    static Optional<TrophyEmail> select(List<BadgeDefinition> unlockedNow, Set<String> heldBefore) {
        boolean publishedBefore = heldBefore.stream()
                .map(BadgeCatalog::byCode)
                .anyMatch(d -> d != null && d.family() == BadgeFamily.PUBLISHER);
        BadgeDefinition firstPublisher = publishedBefore ? null : unlockedNow.stream()
                .filter(d -> d.family() == BadgeFamily.PUBLISHER)
                .min(Comparator.comparingLong(BadgeDefinition::threshold))
                .orElse(null);
        return unlockedNow.stream()
                .map(d -> classify(d, d == firstPublisher))
                .flatMap(Optional::stream)
                .min(Comparator.comparingInt(TrophyEmail::rank)
                        .thenComparing(e -> e.badge().tier(), Comparator.reverseOrder())
                        .thenComparing(e -> e.badge().threshold(), Comparator.reverseOrder()));
    }

    private static Optional<TrophyEmail> classify(BadgeDefinition d, boolean firstPublication) {
        if (d.family() == BadgeFamily.POPULARITY) return Optional.of(new TrophyEmail(KIND_POPULARITY, 0, d));
        if (firstPublication) return Optional.of(new TrophyEmail(KIND_FIRST_PUBLICATION, 1, d));
        if (d.tier() == BadgeTier.GOLD || d.tier() == BadgeTier.PLATINUM) {
            return Optional.of(new TrophyEmail(KIND_TOP_TIER, 2, d));
        }
        return Optional.empty();
    }

    /**
     * Called by {@code BadgeService} after a non-backfill pass inserted {@code unlockedNow}.
     * Never throws.
     */
    public void badgesUnlocked(String tenantId, List<BadgeDefinition> unlockedNow, Set<String> heldBefore) {
        if (selfHosted || authClient == null || tenantId == null || tenantId.isBlank()) return;
        try {
            Optional<TrophyEmail> email = select(unlockedNow, heldBefore);
            if (email.isEmpty()) return;
            Map<String, Object> payload = email.get().payload();
            afterCommit(() -> submit(tenantId, payload));
        } catch (Exception e) {
            log.debug("[lifecycle] trophy email not scheduled for {}: {}", tenantId, e.toString());
        }
    }

    private void submit(String tenantId, Map<String, Object> payload) {
        try {
            executor.execute(() -> {
                AuthClient.LifecycleEventResult result = authClient.emitLifecycleEvent(tenantId, EVENT, payload);
                if (result != AuthClient.LifecycleEventResult.ACCEPTED) {
                    log.debug("[lifecycle] trophy email for {} not taken: {}", tenantId, result);
                }
            });
        } catch (RejectedExecutionException dropped) {
            // Queue full: dropped on purpose, a trophy email is a nicety.
        } catch (Exception e) {
            log.debug("[lifecycle] trophy email dropped for {}: {}", tenantId, e.toString());
        }
    }

    private static void afterCommit(Runnable r) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    r.run();
                }
            });
        } else {
            r.run();
        }
    }

    private static Executor defaultExecutor() {
        return new ThreadPoolExecutor(
                1, 1, 30L, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(200),
                r -> {
                    Thread t = new Thread(r, "lifecycle-trophy");
                    t.setDaemon(true);
                    return t;
                },
                new ThreadPoolExecutor.DiscardPolicy());
    }
}
