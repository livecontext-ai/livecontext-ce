package com.apimarketplace.publication.service;

import com.apimarketplace.auth.client.AuthClient;
import com.apimarketplace.notification.client.NotificationClient;
import com.apimarketplace.notification.client.dto.NotificationEmitRequest;
import com.apimarketplace.publication.domain.WorkflowPublicationEntity;
import com.apimarketplace.publication.domain.WorkflowPublicationEntity.PublicationStatus;
import com.apimarketplace.publication.domain.WorkflowPublicationEntity.PublicationVisibility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("CreatorFollowNotifier")
class CreatorFollowNotifierTest {

    private static final UUID PUB_ID = UUID.fromString("11111111-2222-3333-4444-555555555555");

    @Mock private CreatorFollowService followService;
    @Mock private NotificationClient notificationClient;
    @Mock private AuthClient authClient;

    private CreatorFollowNotifier notifier;

    /** Runs the fan-out inline so the assertions see its effects. */
    private static final class DirectExecutor extends AbstractExecutorService {
        @Override public void execute(Runnable command) { command.run(); }
        @Override public void shutdown() {}
        @Override public List<Runnable> shutdownNow() { return List.of(); }
        @Override public boolean isShutdown() { return false; }
        @Override public boolean isTerminated() { return false; }
        @Override public boolean awaitTermination(long timeout, TimeUnit unit) { return true; }
    }

    @BeforeEach
    void setUp() {
        notifier = new CreatorFollowNotifier(followService, notificationClient, authClient, new DirectExecutor());
    }

    @AfterEach
    void clearSync() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    private static WorkflowPublicationEntity approvedPublic() {
        WorkflowPublicationEntity p = new WorkflowPublicationEntity();
        p.setId(PUB_ID);
        p.setTitle("Invoice Bot");
        p.setPublisherId("7");
        p.setPublisherName("Alice");
        p.setPublisherHandle("alice");
        p.setStatus(PublicationStatus.ACTIVE);
        p.setVisibility(PublicationVisibility.PUBLIC);
        return p;
    }

    @Test
    @DisplayName("a first public go-live notifies every follower in their own personal workspace")
    void notifiesEachFollower() {
        when(followService.followerIds("7")).thenReturn(List.of("5", "9"));
        when(authClient.getDefaultOrganizationIdForUser("5")).thenReturn("org-5");
        when(authClient.getDefaultOrganizationIdForUser("9")).thenReturn("org-9");
        when(notificationClient.emit(any())).thenReturn(true);
        WorkflowPublicationEntity pub = approvedPublic();

        notifier.onApproved(pub);

        ArgumentCaptor<NotificationEmitRequest> sent = ArgumentCaptor.forClass(NotificationEmitRequest.class);
        verify(notificationClient, times(2)).emit(sent.capture());
        NotificationEmitRequest first = sent.getAllValues().get(0);
        assertThat(first.getTenantId()).isEqualTo("5");
        assertThat(first.getOrganizationId()).isEqualTo("org-5");
        assertThat(first.getCategory()).isEqualTo("CREATOR_PUBLISHED");
        assertThat(first.getSubjectType()).isEqualTo("PUBLICATION");
        assertThat(first.getSubjectId()).isEqualTo(PUB_ID);
        assertThat(first.getSourceId()).isEqualTo("creator-publish:" + PUB_ID);
        assertThat(first.getSeverity()).isEqualTo("info");
        assertThat(first.getPayload())
                .containsEntry("status", "published")
                .containsEntry("subjectName", "Invoice Bot")
                .containsEntry("creatorName", "Alice")
                .containsEntry("creatorHandle", "alice");
        assertThat(sent.getAllValues().get(1).getOrganizationId()).isEqualTo("org-9");
        assertThat(pub.getFollowersNotifiedAt()).isNotNull();
    }

    @Test
    @DisplayName("regression: a republish approved again is NOT announced as new a second time")
    void secondApprovalIsSilent() {
        WorkflowPublicationEntity pub = approvedPublic();
        Instant firstAnnounce = Instant.parse("2026-09-01T10:00:00Z");
        pub.setFollowersNotifiedAt(firstAnnounce);

        notifier.onApproved(pub);

        verifyNoInteractions(followService, notificationClient, authClient);
        assertThat(pub.getFollowersNotifiedAt()).isEqualTo(firstAnnounce);
    }

    @Test
    @DisplayName("an UNLISTED listing is not on the marketplace and announces nothing, leaving it announceable later")
    void unlistedIsSilent() {
        WorkflowPublicationEntity pub = approvedPublic();
        pub.setVisibility(PublicationVisibility.UNLISTED);

        notifier.onApproved(pub);

        verifyNoInteractions(followService, notificationClient);
        assertThat(pub.getFollowersNotifiedAt()).isNull();
    }

    @Test
    @DisplayName("nothing is sent before the approval commits, and nothing at all if it rolls back")
    void waitsForCommit() {
        TransactionSynchronizationManager.initSynchronization();
        WorkflowPublicationEntity pub = approvedPublic();

        notifier.onApproved(pub);

        verifyNoInteractions(followService, notificationClient);
        List<TransactionSynchronization> syncs = TransactionSynchronizationManager.getSynchronizations();
        assertThat(syncs).hasSize(1);

        // Rollback: afterCompletion without afterCommit → still nothing.
        syncs.get(0).afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);
        verifyNoInteractions(followService, notificationClient);

        when(followService.followerIds("7")).thenReturn(List.of("5"));
        when(authClient.getDefaultOrganizationIdForUser("5")).thenReturn("org-5");
        syncs.get(0).afterCommit();
        verify(notificationClient).emit(any());
    }

    @Test
    @DisplayName("one follower without a workspace, or one failing emit, does not stop the others")
    void oneFailureDoesNotStopTheFanOut() {
        when(followService.followerIds("7")).thenReturn(List.of("1", "2", "3"));
        when(authClient.getDefaultOrganizationIdForUser("1")).thenReturn(null);
        when(authClient.getDefaultOrganizationIdForUser("2")).thenThrow(new RuntimeException("auth down"));
        when(authClient.getDefaultOrganizationIdForUser("3")).thenReturn("org-3");

        notifier.onApproved(approvedPublic());

        ArgumentCaptor<NotificationEmitRequest> sent = ArgumentCaptor.forClass(NotificationEmitRequest.class);
        verify(notificationClient).emit(sent.capture());
        assertThat(sent.getValue().getTenantId()).isEqualTo("3");
    }

    @Test
    @DisplayName("regression: Spring can build the bean (two constructors without @Autowired failed publication-service startup)")
    void springCanInstantiateTheBean() {
        try (AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext()) {
            ctx.registerBean(CreatorFollowService.class, () -> mock(CreatorFollowService.class));
            ctx.registerBean(NotificationClient.class, () -> mock(NotificationClient.class));
            ctx.registerBean(AuthClient.class, () -> mock(AuthClient.class));
            ctx.register(CreatorFollowNotifier.class);
            ctx.refresh();

            assertThat(ctx.getBean(CreatorFollowNotifier.class)).isNotNull();
        }
    }

    @Test
    @DisplayName("regression: an approval committing while the service shuts down is not turned into a moderator error")
    void rejectedFanOutAtShutdownIsSwallowed() {
        java.util.concurrent.ExecutorService stopped = java.util.concurrent.Executors.newSingleThreadExecutor();
        stopped.shutdown();
        CreatorFollowNotifier closing = new CreatorFollowNotifier(followService, notificationClient, authClient, stopped);
        WorkflowPublicationEntity pub = approvedPublic();

        org.assertj.core.api.Assertions.assertThatCode(() -> closing.onApproved(pub)).doesNotThrowAnyException();

        verifyNoInteractions(followService, notificationClient);
        assertThat(pub.getFollowersNotifiedAt()).isNotNull();
    }

    @Test
    @DisplayName("a PRIVATE listing is not on the marketplace and announces nothing")
    void privateIsSilent() {
        WorkflowPublicationEntity pub = approvedPublic();
        pub.setVisibility(PublicationVisibility.PRIVATE);

        notifier.onApproved(pub);

        verifyNoInteractions(followService, notificationClient);
        assertThat(pub.getFollowersNotifiedAt()).isNull();
    }

    @Test
    @DisplayName("a listing with no publisher has nobody to announce for, and is left unstamped")
    void noPublisherIsSilent() {
        WorkflowPublicationEntity pub = approvedPublic();
        pub.setPublisherId(null);

        notifier.onApproved(pub);

        verifyNoInteractions(followService, notificationClient);
        assertThat(pub.getFollowersNotifiedAt()).isNull();
    }

    @Test
    @DisplayName("a failing follower lookup is contained: no emit, no exception escapes into the approval")
    void followerLookupFailureIsContained() {
        when(followService.followerIds("7")).thenThrow(new RuntimeException("db down"));

        notifier.onApproved(approvedPublic());

        verifyNoInteractions(notificationClient, authClient);
    }

    @Test
    @DisplayName("a creator with no followers costs one lookup and no emit")
    void noFollowers() {
        when(followService.followerIds("7")).thenReturn(List.of());

        notifier.onApproved(approvedPublic());

        verify(notificationClient, never()).emit(any());
    }
}
