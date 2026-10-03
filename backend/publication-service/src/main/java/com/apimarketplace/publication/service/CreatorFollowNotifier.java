package com.apimarketplace.publication.service;

import com.apimarketplace.auth.client.AuthClient;
import com.apimarketplace.auth.client.dto.PublisherProfileDto;
import com.apimarketplace.notification.client.NotificationClient;
import com.apimarketplace.notification.client.dto.NotificationEmitRequest;
import com.apimarketplace.publication.domain.WorkflowPublicationEntity;
import com.apimarketplace.publication.domain.WorkflowPublicationEntity.PublicationStatus;
import com.apimarketplace.publication.domain.WorkflowPublicationEntity.PublicationVisibility;
import com.apimarketplace.publication.repository.CreatorFollowRepository;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

/**
 * The two notifications of the follow feature, both in the bell and, through the
 * orchestrator's delivery topics, by email:
 * <ul>
 *   <li>{@code CREATOR_PUBLISHED} to every follower when a creator's new listing goes live
 *       ({@link #onApproved}), emailed in the daily summary (topic FOLLOWING);</li>
 *   <li>{@code CREATOR_FOLLOWED} to the creator when someone subscribes ({@link #onFollowed}),
 *       emailed in the daily summary (topic AUDIENCE).</li>
 * </ul>
 *
 * <p>{@link #onApproved} is called from {@link PublicationModerationService#approvePublication},
 * the only place a listing becomes ACTIVE on the public marketplace. It fires once per listing:
 * the first approval as PUBLIC stamps {@code followers_notified_at}, and a later republish that
 * goes back through review finds the stamp and stays quiet. PRIVATE and UNLISTED listings are
 * not on the marketplace, so they announce nothing.
 *
 * <p>Everything runs AFTER the triggering transaction commits and off the request thread: a
 * rolled-back approval or follow must not notify anyone, and the lookups (workspace, names)
 * must not stall the click. Each emit is idempotent server-side on
 * {@code (tenant, category, sourceId)} for as long as the bell row exists (30 days, or until
 * the recipient deletes it), so an unfollow then re-follow inside that window is not announced
 * twice; after it, a re-follow is announced again.
 *
 * <p>Best-effort by design, like every other bell producer: the fan-out lives in memory.
 * Shutdown drains the queue for a bounded time, but a process killed mid-fan-out loses the
 * notifications not yet sent; the listing is not announced again (a duplicate announcement is
 * worse than a missed one).
 */
@Component
public class CreatorFollowNotifier {

    private static final Logger log = LoggerFactory.getLogger(CreatorFollowNotifier.class);

    public static final String CATEGORY_CREATOR_PUBLISHED = "CREATOR_PUBLISHED";
    public static final String CATEGORY_CREATOR_FOLLOWED = "CREATOR_FOLLOWED";
    public static final String SUBJECT_TYPE_PUBLICATION = "PUBLICATION";
    public static final String SUBJECT_TYPE_USER = "USER";
    private static final long SHUTDOWN_DRAIN_SECONDS = 10;

    private final CreatorFollowRepository followRepo;
    private final NotificationClient notificationClient;
    private final AuthClient authClient;
    private final ExecutorService executor;

    @Autowired
    public CreatorFollowNotifier(CreatorFollowRepository followRepo,
                                 NotificationClient notificationClient,
                                 AuthClient authClient) {
        this(followRepo, notificationClient, authClient, Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "creator-follow-notifier");
            t.setDaemon(true);
            return t;
        }));
    }

    /** Test seam: a direct executor makes the fan-out synchronous. */
    CreatorFollowNotifier(CreatorFollowRepository followRepo,
                          NotificationClient notificationClient,
                          AuthClient authClient,
                          ExecutorService executor) {
        this.followRepo = followRepo;
        this.notificationClient = notificationClient;
        this.authClient = authClient;
        this.executor = executor;
    }

    @PreDestroy
    void shutdown() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(SHUTDOWN_DRAIN_SECONDS, TimeUnit.SECONDS)) {
                log.warn("creator-follow: notifications still running after {}s at shutdown, the rest are not sent",
                        SHUTDOWN_DRAIN_SECONDS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Called with the listing just approved, BEFORE it is saved: when this is its first
     * public go-live, stamps it and schedules the followers' notifications for after commit.
     */
    public void onApproved(WorkflowPublicationEntity publication) {
        if (publication == null
                || publication.getStatus() != PublicationStatus.ACTIVE
                || publication.getVisibility() != PublicationVisibility.PUBLIC
                || publication.getFollowersNotifiedAt() != null
                || publication.getPublisherId() == null) {
            return;
        }
        publication.setFollowersNotifiedAt(Instant.now());

        UUID publicationId = publication.getId();
        String creatorId = publication.getPublisherId();
        Map<String, Object> basePayload = payloadFor(publication);
        afterCommit(() -> notifyFollowers(publicationId, creatorId, basePayload),
                CATEGORY_CREATOR_PUBLISHED + " for publication " + publicationId);
    }

    /**
     * Called when {@code followerId} has just started following {@code creatorId} (a real new
     * follow, not a repeat): tells the creator, after the follow commits.
     */
    public void onFollowed(String followerId, String creatorId) {
        if (followerId == null || creatorId == null) return;
        afterCommit(() -> notifyCreator(followerId, creatorId),
                CATEGORY_CREATOR_FOLLOWED + " for creator " + creatorId);
    }

    private void afterCommit(Runnable work, String what) {
        Runnable submit = () -> {
            try {
                executor.execute(work);
            } catch (RejectedExecutionException e) {
                // Shutting down: the triggering change is already committed, so it must not
                // surface as a failure to the user who clicked. Best-effort, as documented above.
                log.warn("creator-follow: shutting down, {} not sent", what);
            }
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    submit.run();
                }
            });
        } else {
            submit.run();
        }
    }

    private void notifyFollowers(UUID publicationId, String creatorId, Map<String, Object> basePayload) {
        List<String> followers;
        try {
            followers = followRepo.findFollowerIds(creatorId);
        } catch (Exception e) {
            log.warn("{}: could not list followers of creator {} for publication {}: {}",
                    CATEGORY_CREATOR_PUBLISHED, creatorId, publicationId, e.getMessage());
            return;
        }
        int sent = 0;
        for (String followerId : followers) {
            try {
                if (emit(followerId, CATEGORY_CREATOR_PUBLISHED, SUBJECT_TYPE_PUBLICATION, publicationId,
                        "creator-publish:" + publicationId, basePayload)) sent++;
            } catch (Exception e) {
                log.warn("{}: emit to follower {} for publication {} failed: {}",
                        CATEGORY_CREATOR_PUBLISHED, followerId, publicationId, e.getMessage());
            }
        }
        log.info("{}: publication {} by creator {} announced to {}/{} follower(s)",
                CATEGORY_CREATOR_PUBLISHED, publicationId, creatorId, sent, followers.size());
    }

    private void notifyCreator(String followerId, String creatorId) {
        try {
            // The creator only ever learns of a follower who has a PUBLIC profile: auth withholds
            // the handle exactly when the profile is PRIVATE (or no handle exists yet), and a
            // private person must not be named, in the bell or by email. The name shown is the
            // chosen display name, never the account email or real name.
            PublisherProfileDto follower = authClient.getPublisherProfile(followerId);
            if (follower == null) {
                // getPublisherProfile answers null on a transport failure too: say so plainly.
                log.warn("{}: follower {} could not be looked up, creator {} not notified",
                        CATEGORY_CREATOR_FOLLOWED, followerId, creatorId);
                return;
            }
            if (!hasText(follower.handle())) {
                log.info("{}: follower {} has no public profile, creator {} not told who followed",
                        CATEGORY_CREATOR_FOLLOWED, followerId, creatorId);
                return;
            }
            String handle = follower.handle();
            String name = hasText(follower.displayName()) ? follower.displayName() : "@" + handle;
            Map<String, Object> payload = new HashMap<>();
            payload.put("status", "followed");
            payload.put("subjectName", name);
            payload.put("followerId", followerId);
            payload.put("profileHandle", handle);
            // One bell entry per follower: a stable id derived from them, since users have
            // numeric ids and the column is a UUID.
            UUID subjectId = UUID.nameUUIDFromBytes(("user:" + followerId).getBytes(StandardCharsets.UTF_8));
            boolean sent = emit(creatorId, CATEGORY_CREATOR_FOLLOWED, SUBJECT_TYPE_USER, subjectId,
                    "creator-follow:" + followerId + ":" + creatorId, payload);
            log.info("{}: creator {} told about follower {} (emitted={})",
                    CATEGORY_CREATOR_FOLLOWED, creatorId, followerId, sent);
        } catch (Exception e) {
            log.warn("{}: notifying creator {} of follower {} failed: {}",
                    CATEGORY_CREATOR_FOLLOWED, creatorId, followerId, e.getMessage());
        }
    }

    private boolean emit(String recipientId, String category, String subjectType, UUID subjectId,
                         String sourceId, Map<String, Object> payload) {
        // The bell is scoped by workspace: stamp the recipient's own personal workspace, or the
        // row would fall back to the caller's (the moderator's, the follower's) and never reach
        // the recipient. The delivery topics of these categories are person-scoped for the
        // same reason.
        String recipientOrg = authClient.getDefaultOrganizationIdForUser(recipientId);
        if (recipientOrg == null || recipientOrg.isBlank()) {
            log.warn("{}: recipient {} has no default workspace, skipped", category, recipientId);
            return false;
        }
        NotificationEmitRequest req = new NotificationEmitRequest();
        req.setTenantId(recipientId);
        req.setOrganizationId(recipientOrg);
        req.setCategory(category);
        req.setSeverity("info");
        req.setSubjectType(subjectType);
        req.setSubjectId(subjectId);
        req.setSourceId(sourceId);
        req.setPayload(new HashMap<>(payload));
        req.setOccurredAt(Instant.now());
        return notificationClient.emit(req);
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static Map<String, Object> payloadFor(WorkflowPublicationEntity publication) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("status", "published");
        payload.put("subjectName", publication.getTitle());
        payload.put("publicationId", String.valueOf(publication.getId()));
        payload.put("creatorId", publication.getPublisherId());
        if (publication.getPublisherName() != null) payload.put("creatorName", publication.getPublisherName());
        if (publication.getPublisherHandle() != null) payload.put("creatorHandle", publication.getPublisherHandle());
        return payload;
    }
}
