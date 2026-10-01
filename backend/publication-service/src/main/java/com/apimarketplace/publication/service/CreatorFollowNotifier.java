package com.apimarketplace.publication.service;

import com.apimarketplace.auth.client.AuthClient;
import com.apimarketplace.notification.client.NotificationClient;
import com.apimarketplace.notification.client.dto.NotificationEmitRequest;
import com.apimarketplace.publication.domain.WorkflowPublicationEntity;
import com.apimarketplace.publication.domain.WorkflowPublicationEntity.PublicationStatus;
import com.apimarketplace.publication.domain.WorkflowPublicationEntity.PublicationVisibility;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

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
 * Tells a creator's followers, in the bell, that a new listing of theirs went live.
 *
 * <p>Called from {@link PublicationModerationService#approvePublication}, the only place a
 * listing becomes ACTIVE on the public marketplace. It fires once per listing: the first
 * approval as PUBLIC stamps {@code followers_notified_at}, and a later republish that goes
 * back through review finds the stamp and stays quiet. PRIVATE and UNLISTED listings are
 * not on the marketplace, so they announce nothing.
 *
 * <p>The fan-out (one default-workspace lookup and one emit per follower) runs AFTER the
 * approval commits and off the moderator's request thread: a rolled-back approval must not
 * notify anyone, and a creator with many followers must not stall the admin's click. Each
 * emit is idempotent server-side on {@code (tenant, category, sourceId)}.
 *
 * <p>Best-effort by design, like every other bell producer: the stamp commits with the
 * approval, and the fan-out lives in memory. Shutdown drains the queue for a bounded time,
 * but a process killed mid-fan-out loses the followers not yet reached; the listing is not
 * announced again (a duplicate announcement is worse than a missed one).
 */
@Component
public class CreatorFollowNotifier {

    private static final Logger log = LoggerFactory.getLogger(CreatorFollowNotifier.class);

    public static final String CATEGORY_CREATOR_PUBLISHED = "CREATOR_PUBLISHED";
    public static final String SUBJECT_TYPE_PUBLICATION = "PUBLICATION";
    private static final long SHUTDOWN_DRAIN_SECONDS = 10;

    private final CreatorFollowService followService;
    private final NotificationClient notificationClient;
    private final AuthClient authClient;
    private final ExecutorService executor;

    @Autowired
    public CreatorFollowNotifier(CreatorFollowService followService,
                                 NotificationClient notificationClient,
                                 AuthClient authClient) {
        this(followService, notificationClient, authClient, Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "creator-follow-notifier");
            t.setDaemon(true);
            return t;
        }));
    }

    /** Test seam: a direct executor makes the fan-out synchronous. */
    CreatorFollowNotifier(CreatorFollowService followService,
                          NotificationClient notificationClient,
                          AuthClient authClient,
                          ExecutorService executor) {
        this.followService = followService;
        this.notificationClient = notificationClient;
        this.authClient = authClient;
        this.executor = executor;
    }

    @PreDestroy
    void shutdown() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(SHUTDOWN_DRAIN_SECONDS, TimeUnit.SECONDS)) {
                log.warn("{}: fan-out still running after {}s at shutdown, remaining followers are not notified",
                        CATEGORY_CREATOR_PUBLISHED, SHUTDOWN_DRAIN_SECONDS);
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
        Runnable fanOut = () -> {
            try {
                executor.execute(() -> notifyFollowers(publicationId, creatorId, basePayload));
            } catch (RejectedExecutionException e) {
                // Shutting down: the approval is already committed, so it must not surface as a
                // failure to the moderator. Best-effort, as documented above.
                log.warn("{}: shutting down, followers of creator {} not notified for publication {}",
                        CATEGORY_CREATOR_PUBLISHED, creatorId, publicationId);
            }
        };

        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    fanOut.run();
                }
            });
        } else {
            fanOut.run();
        }
    }

    private void notifyFollowers(UUID publicationId, String creatorId, Map<String, Object> basePayload) {
        List<String> followers;
        try {
            followers = followService.followerIds(creatorId);
        } catch (Exception e) {
            log.warn("{}: could not list followers of creator {} for publication {}: {}",
                    CATEGORY_CREATOR_PUBLISHED, creatorId, publicationId, e.getMessage());
            return;
        }
        int sent = 0;
        for (String followerId : followers) {
            try {
                if (emitTo(followerId, publicationId, basePayload)) sent++;
            } catch (Exception e) {
                log.warn("{}: emit to follower {} for publication {} failed: {}",
                        CATEGORY_CREATOR_PUBLISHED, followerId, publicationId, e.getMessage());
            }
        }
        log.info("{}: publication {} by creator {} announced to {}/{} follower(s)",
                CATEGORY_CREATOR_PUBLISHED, publicationId, creatorId, sent, followers.size());
    }

    private boolean emitTo(String followerId, UUID publicationId, Map<String, Object> basePayload) {
        // The bell is scoped by workspace: stamp the follower's own personal workspace, or the
        // row would fall back to the approving moderator's and never reach the follower.
        String followerOrg = authClient.getDefaultOrganizationIdForUser(followerId);
        if (followerOrg == null || followerOrg.isBlank()) {
            log.warn("{}: follower {} has no default workspace, skipped", CATEGORY_CREATOR_PUBLISHED, followerId);
            return false;
        }
        NotificationEmitRequest req = new NotificationEmitRequest();
        req.setTenantId(followerId);
        req.setOrganizationId(followerOrg);
        req.setCategory(CATEGORY_CREATOR_PUBLISHED);
        req.setSeverity("info");
        req.setSubjectType(SUBJECT_TYPE_PUBLICATION);
        req.setSubjectId(publicationId);
        req.setSourceId("creator-publish:" + publicationId);
        req.setPayload(new HashMap<>(basePayload));
        req.setOccurredAt(Instant.now());
        return notificationClient.emit(req);
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
