package com.apimarketplace.publication.service;

import com.apimarketplace.publication.domain.CreatorFollowEntity.PK;
import com.apimarketplace.publication.repository.CreatorFollowRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Following a creator: a user subscribes to another user so they hear about each new
 * marketplace listing that creator publishes (see {@link CreatorFollowNotifier}).
 *
 * <p>Ids are numeric auth user ids carried as strings, the same form as
 * {@code workflow_publications.publisher_id}. The creator is not looked up in auth: a
 * person with no listing yet is still worth following, and a follow of an id that never
 * publishes costs one idle row.
 */
@Service
public class CreatorFollowService {

    public static final String INVALID_CREATOR = "INVALID_CREATOR";
    public static final String CANNOT_FOLLOW_SELF = "CANNOT_FOLLOW_SELF";

    private final CreatorFollowRepository followRepo;

    public CreatorFollowService(CreatorFollowRepository followRepo) {
        this.followRepo = followRepo;
    }

    /** Follow status as the profile page shows it. */
    public record FollowStatus(boolean following, long followerCount) {}

    /**
     * Follow a creator. Idempotent: following twice is a no-op.
     *
     * @throws IllegalArgumentException {@link #INVALID_CREATOR} for a non-numeric id,
     *         {@link #CANNOT_FOLLOW_SELF} when the caller targets themselves.
     */
    @Transactional
    public FollowStatus follow(String followerId, String creatorId) {
        String creator = requireCreator(followerId, creatorId);
        followRepo.insertIfAbsent(followerId, creator);
        return new FollowStatus(true, followRepo.countByCreatorId(creator));
    }

    /** Unfollow. Idempotent: unfollowing someone not followed deletes nothing. */
    @Transactional
    public FollowStatus unfollow(String followerId, String creatorId) {
        String creator = requireCreator(followerId, creatorId);
        followRepo.deleteFollow(followerId, creator);
        return new FollowStatus(false, followRepo.countByCreatorId(creator));
    }

    @Transactional(readOnly = true)
    public FollowStatus status(String followerId, String creatorId) {
        String creator = requireNumeric(creatorId);
        boolean following = followerId != null && !followerId.isBlank()
                && followRepo.existsById(new PK(followerId, creator));
        return new FollowStatus(following, followRepo.countByCreatorId(creator));
    }

    @Transactional(readOnly = true)
    public List<String> followerIds(String creatorId) {
        return followRepo.findFollowerIds(creatorId);
    }

    private static String requireCreator(String followerId, String creatorId) {
        String creator = requireNumeric(creatorId);
        if (creator.equals(followerId)) {
            throw new IllegalArgumentException(CANNOT_FOLLOW_SELF);
        }
        return creator;
    }

    /** Canonical numeric form ("007" and "7" are the same user), or {@link #INVALID_CREATOR}. */
    private static String requireNumeric(String creatorId) {
        try {
            long id = Long.parseLong(creatorId == null ? "" : creatorId.trim());
            if (id <= 0) throw new IllegalArgumentException(INVALID_CREATOR);
            return Long.toString(id);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(INVALID_CREATOR);
        }
    }
}
