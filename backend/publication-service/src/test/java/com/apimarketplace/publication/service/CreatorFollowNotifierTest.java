package com.apimarketplace.publication.service;

import com.apimarketplace.auth.client.AuthClient;
import com.apimarketplace.auth.client.dto.PublisherProfileDto;
import com.apimarketplace.notification.client.NotificationClient;
import com.apimarketplace.notification.client.dto.NotificationEmitRequest;
import com.apimarketplace.publication.domain.WorkflowPublicationEntity;
import com.apimarketplace.publication.domain.WorkflowPublicationEntity.PublicationStatus;
import com.apimarketplace.publication.domain.WorkflowPublicationEntity.PublicationVisibility;
import com.apimarketplace.publication.repository.CreatorFollowRepository;
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

    @Mock private CreatorFollowRepository followRepo;
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
        notifier = new CreatorFollowNotifier(followRepo, notificationClient, authClient, new DirectExecutor());
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
        when(followRepo.findFollowerIds("7")).thenReturn(List.of("5", "9"));
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

        verifyNoInteractions(followRepo, notificationClient, authClient);
        assertThat(pub.getFollowersNotifiedAt()).isEqualTo(firstAnnounce);
    }

    @Test
    @DisplayName("an UNLISTED listing is not on the marketplace and announces nothing, leaving it announceable later")
    void unlistedIsSilent() {
        WorkflowPublicationEntity pub = approvedPublic();
        pub.setVisibility(PublicationVisibility.UNLISTED);

        notifier.onApproved(pub);

        verifyNoInteractions(followRepo, notificationClient);
        assertThat(pub.getFollowersNotifiedAt()).isNull();
    }

    @Test
    @DisplayName("nothing is sent before the approval commits, and nothing at all if it rolls back")
    void waitsForCommit() {
        TransactionSynchronizationManager.initSynchronization();
        WorkflowPublicationEntity pub = approvedPublic();

        notifier.onApproved(pub);

        verifyNoInteractions(followRepo, notificationClient);
        List<TransactionSynchronization> syncs = TransactionSynchronizationManager.getSynchronizations();
        assertThat(syncs).hasSize(1);

        // Rollback: afterCompletion without afterCommit → still nothing.
        syncs.get(0).afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);
        verifyNoInteractions(followRepo, notificationClient);

        when(followRepo.findFollowerIds("7")).thenReturn(List.of("5"));
        when(authClient.getDefaultOrganizationIdForUser("5")).thenReturn("org-5");
        syncs.get(0).afterCommit();
        verify(notificationClient).emit(any());
    }

    @Test
    @DisplayName("one follower without a workspace, or one failing emit, does not stop the others")
    void oneFailureDoesNotStopTheFanOut() {
        when(followRepo.findFollowerIds("7")).thenReturn(List.of("1", "2", "3"));
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
            ctx.registerBean(CreatorFollowRepository.class, () -> mock(CreatorFollowRepository.class));
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
        CreatorFollowNotifier closing = new CreatorFollowNotifier(followRepo, notificationClient, authClient, stopped);
        WorkflowPublicationEntity pub = approvedPublic();

        org.assertj.core.api.Assertions.assertThatCode(() -> closing.onApproved(pub)).doesNotThrowAnyException();

        verifyNoInteractions(followRepo, notificationClient);
        assertThat(pub.getFollowersNotifiedAt()).isNotNull();
    }

    @Test
    @DisplayName("a PRIVATE listing is not on the marketplace and announces nothing")
    void privateIsSilent() {
        WorkflowPublicationEntity pub = approvedPublic();
        pub.setVisibility(PublicationVisibility.PRIVATE);

        notifier.onApproved(pub);

        verifyNoInteractions(followRepo, notificationClient);
        assertThat(pub.getFollowersNotifiedAt()).isNull();
    }

    @Test
    @DisplayName("a listing with no publisher has nobody to announce for, and is left unstamped")
    void noPublisherIsSilent() {
        WorkflowPublicationEntity pub = approvedPublic();
        pub.setPublisherId(null);

        notifier.onApproved(pub);

        verifyNoInteractions(followRepo, notificationClient);
        assertThat(pub.getFollowersNotifiedAt()).isNull();
    }

    @Test
    @DisplayName("a failing follower lookup is contained: no emit, no exception escapes into the approval")
    void followerLookupFailureIsContained() {
        when(followRepo.findFollowerIds("7")).thenThrow(new RuntimeException("db down"));

        notifier.onApproved(approvedPublic());

        verifyNoInteractions(notificationClient, authClient);
    }

    @Test
    @DisplayName("a creator with no followers costs one lookup and no emit")
    void noFollowers() {
        when(followRepo.findFollowerIds("7")).thenReturn(List.of());

        notifier.onApproved(approvedPublic());

        verify(notificationClient, never()).emit(any());
    }

    @Test
    @DisplayName("a new follow tells the CREATOR, in their personal workspace, with the follower's public name and handle")
    void onFollowedNotifiesTheCreator() {
        when(authClient.getPublisherProfile("5")).thenReturn(new PublisherProfileDto("5", "Bob B.", "bob@x.io", null, "bob"));
        when(authClient.getDefaultOrganizationIdForUser("7")).thenReturn("org-7");
        when(notificationClient.emit(any())).thenReturn(true);

        notifier.onFollowed("5", "7");

        ArgumentCaptor<NotificationEmitRequest> sent = ArgumentCaptor.forClass(NotificationEmitRequest.class);
        verify(notificationClient).emit(sent.capture());
        NotificationEmitRequest req = sent.getValue();
        assertThat(req.getTenantId()).isEqualTo("7");
        assertThat(req.getOrganizationId()).isEqualTo("org-7");
        assertThat(req.getCategory()).isEqualTo("CREATOR_FOLLOWED");
        assertThat(req.getSubjectType()).isEqualTo("USER");
        assertThat(req.getSubjectId()).isEqualTo(UUID.nameUUIDFromBytes("user:5".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        assertThat(req.getSourceId()).isEqualTo("creator-follow:5:7");
        assertThat(req.getPayload())
                .containsEntry("status", "followed")
                .containsEntry("subjectName", "Bob B.")
                .containsEntry("profileHandle", "bob")
                // Never the account email: the creator only ever sees the public identity.
                .doesNotContainValue("bob@x.io");
    }

    @Test
    @DisplayName("a follower with a handle but no display name is named by @handle")
    void onFollowedFallsBackToHandle() {
        when(authClient.getPublisherProfile("5")).thenReturn(new PublisherProfileDto("5", " ", null, null, "bob"));
        when(authClient.getDefaultOrganizationIdForUser("7")).thenReturn("org-7");

        notifier.onFollowed("5", "7");

        ArgumentCaptor<NotificationEmitRequest> sent = ArgumentCaptor.forClass(NotificationEmitRequest.class);
        verify(notificationClient).emit(sent.capture());
        assertThat(sent.getValue().getPayload()).containsEntry("subjectName", "@bob");
    }

    @Test
    @DisplayName("regression (privacy): a follower with a PRIVATE profile is never named to the creator")
    void privateFollowerIsNotNamed() {
        // Auth withholds the handle exactly when the profile is PRIVATE, but still returns the
        // display name: naming the follower from it would expose someone who chose to be hidden.
        when(authClient.getPublisherProfile("5")).thenReturn(new PublisherProfileDto("5", "Bob B.", "bob@x.io", null, null));

        notifier.onFollowed("5", "7");

        verifyNoInteractions(notificationClient);
        verify(authClient, never()).getDefaultOrganizationIdForUser(any());
    }

    @Test
    @DisplayName("a follower lookup that fails (the client answers null) sends nothing")
    void followerLookupFailureIsSilent() {
        when(authClient.getPublisherProfile("5")).thenReturn(null);

        notifier.onFollowed("5", "7");

        verifyNoInteractions(notificationClient);
    }

    @Test
    @DisplayName("a creator with no personal workspace is skipped, nothing is emitted")
    void creatorWithoutWorkspaceIsSkipped() {
        when(authClient.getPublisherProfile("5")).thenReturn(new PublisherProfileDto("5", "Bob B.", null, null, "bob"));
        when(authClient.getDefaultOrganizationIdForUser("7")).thenReturn(null);

        notifier.onFollowed("5", "7");

        verifyNoInteractions(notificationClient);
    }

    @Test
    @DisplayName("the creator is told only after the follow commits, never on a rollback")
    void onFollowedWaitsForCommit() {
        TransactionSynchronizationManager.initSynchronization();

        notifier.onFollowed("5", "7");

        verifyNoInteractions(authClient, notificationClient);
        List<TransactionSynchronization> syncs = TransactionSynchronizationManager.getSynchronizations();
        assertThat(syncs).hasSize(1);
        syncs.get(0).afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);
        verifyNoInteractions(authClient, notificationClient);

        // And once it commits, the creator is told.
        when(authClient.getPublisherProfile("5")).thenReturn(new PublisherProfileDto("5", "Bob B.", null, null, "bob"));
        when(authClient.getDefaultOrganizationIdForUser("7")).thenReturn("org-7");
        syncs.get(0).afterCommit();
        verify(notificationClient).emit(any());
    }

    @Test
    @DisplayName("an exception while notifying the creator is contained")
    void onFollowedFailureIsContained() {
        when(authClient.getPublisherProfile("5")).thenThrow(new RuntimeException("auth down"));

        org.assertj.core.api.Assertions.assertThatCode(() -> notifier.onFollowed("5", "7")).doesNotThrowAnyException();

        verifyNoInteractions(notificationClient);
    }
}
