package com.apimarketplace.publication.service;

import com.apimarketplace.publication.domain.CreatorFollowEntity;
import com.apimarketplace.publication.repository.CreatorFollowRepository;
import com.apimarketplace.publication.service.CreatorFollowService.FollowStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("CreatorFollowService")
class CreatorFollowServiceTest {

    @Mock private CreatorFollowRepository repo;
    @Mock private CreatorFollowNotifier notifier;

    private CreatorFollowService service;

    @BeforeEach
    void setUp() {
        service = new CreatorFollowService(repo, notifier);
    }

    @Test
    @DisplayName("follow stores the (follower, creator) pair and reports the new follower count")
    void followStoresPair() {
        when(repo.countByCreatorId("7")).thenReturn(3L);

        FollowStatus status = service.follow("5", "7");

        verify(repo).insertIfAbsent("5", "7");
        assertThat(status).isEqualTo(new FollowStatus(true, 3L));
    }

    @Test
    @DisplayName("regression: following goes through the single-statement insert, never check-then-save (a concurrent double follow raced into a 500)")
    void followIsIdempotentAndRaceFree() {
        when(repo.insertIfAbsent("5", "7")).thenReturn(0); // already following: the insert is a no-op
        when(repo.countByCreatorId("7")).thenReturn(1L);

        assertThat(service.follow("5", "7").following()).isTrue();
        verify(repo, never()).save(any());
        verify(repo, never()).existsById(any());
    }

    @Test
    @DisplayName("a creator id is canonicalised, so '007' and '7' are the same follow")
    void creatorIdIsCanonical() {
        service.follow("5", " 007 ");

        verify(repo).insertIfAbsent("5", "7");
        verify(repo).countByCreatorId("7");
    }

    @Test
    @DisplayName("nobody can follow themselves")
    void cannotFollowSelf() {
        assertThatThrownBy(() -> service.follow("7", "7"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(CreatorFollowService.CANNOT_FOLLOW_SELF);
        verify(repo, never()).save(any());
    }

    @Test
    @DisplayName("a non-numeric or non-positive creator id is rejected")
    void invalidCreatorRejected() {
        for (String bad : new String[]{"abc", "", null, "0", "-3"}) {
            assertThatThrownBy(() -> service.follow("5", bad))
                    .as("creator id %s", bad)
                    .hasMessage(CreatorFollowService.INVALID_CREATOR);
            assertThatThrownBy(() -> service.status("5", bad))
                    .hasMessage(CreatorFollowService.INVALID_CREATOR);
        }
    }

    @Test
    @DisplayName("unfollow deletes the pair and reports not-following with the remaining count")
    void unfollowDeletes() {
        when(repo.countByCreatorId("7")).thenReturn(0L);

        FollowStatus status = service.unfollow("5", "7");

        verify(repo).deleteFollow("5", "7");
        assertThat(status).isEqualTo(new FollowStatus(false, 0L));
    }

    @Test
    @DisplayName("status reflects whether the caller follows, with the creator's follower count")
    void statusReportsFollowing() {
        when(repo.existsById(new CreatorFollowEntity.PK("5", "7"))).thenReturn(true);
        when(repo.countByCreatorId("7")).thenReturn(12L);

        assertThat(service.status("5", "7")).isEqualTo(new FollowStatus(true, 12L));
    }

    @Test
    @DisplayName("status on your own profile answers (not following) instead of rejecting the read")
    void statusOnOwnProfile() {
        when(repo.countByCreatorId("7")).thenReturn(2L);

        assertThat(service.status("7", "7")).isEqualTo(new FollowStatus(false, 2L));
    }

    @Test
    @DisplayName("a REAL new follow tells the creator")
    void newFollowNotifiesCreator() {
        when(repo.insertIfAbsent("5", "7")).thenReturn(1);

        service.follow("5", "7");

        verify(notifier).onFollowed("5", "7");
    }

    @Test
    @DisplayName("regression: a repeated follow (nothing inserted) does not notify the creator again")
    void repeatFollowIsSilent() {
        when(repo.insertIfAbsent("5", "7")).thenReturn(0);

        service.follow("5", "7");

        verifyNoInteractions(notifier);
    }

    @Test
    @DisplayName("a refused follow (self) and an unfollow never notify")
    void refusedFollowAndUnfollowAreSilent() {
        assertThatThrownBy(() -> service.follow("7", "7")).isInstanceOf(IllegalArgumentException.class);
        service.unfollow("5", "7");

        verifyNoInteractions(notifier);
    }
}
