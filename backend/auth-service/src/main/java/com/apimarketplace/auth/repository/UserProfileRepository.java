package com.apimarketplace.auth.repository;

import com.apimarketplace.auth.domain.UserProfileEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Repository for {@link UserProfileEntity}. The entity shares its primary key
 * with the user ({@code user_id}), so {@code findByUserId} is just
 * {@code findById} - exposed under the explicit name to mirror
 * {@code UserOnboardingRepository.findByUserId} call sites.
 */
@Repository
public interface UserProfileRepository extends JpaRepository<UserProfileEntity, Long> {

    default Optional<UserProfileEntity> findByUserId(Long userId) {
        return findById(userId);
    }

    /** Resolve a profile by its public @handle (the /app/u/{handle} lookup). */
    Optional<UserProfileEntity> findByHandle(String handle);

    /** Handle-uniqueness check used when generating or editing a handle. */
    boolean existsByHandle(String handle);

    /**
     * The subset of {@code userIds} carrying a MANUAL verified badge, restricted to
     * accounts that are still enabled.
     *
     * <p>The join on {@code User} is what enforces the second half: a disabled or
     * deactivated account keeps its row here, and a badge is a public claim about a
     * live account. Without the join a deleted-but-not-purged publisher would keep a
     * blue check on every listing they ever published.
     *
     * <p>Badge-granting by ADMIN role is NOT visible here - it is not stored on the
     * profile. {@code VerifiedAccountService} unions the two sources; nothing else
     * should call this directly.
     */
    @Query("""
            SELECT p.userId FROM UserProfileEntity p, User u
             WHERE u.id = p.userId
               AND u.enabled = true
               AND p.verified = true
               AND p.userId IN :userIds
            """)
    List<Long> findManuallyVerifiedIdsIn(@Param("userIds") Collection<Long> userIds);

    /**
     * Resolve a batch of public @handles to their user ids. Feeds the anonymous
     * handle-keyed badge lookup: handles are user-chosen and not enumerable, which is
     * the whole reason that endpoint may be public while the id-keyed one may not.
     *
     * <p><b>Matches EXACTLY, and the caller must pass lowercase.</b> Every write path
     * stores a handle through {@code UserService.slugifyHandle} ->
     * {@code UsernameValidator.normalize}, which lowercases, so a stored handle is
     * always lowercase and an exact match loses nothing. The obvious-looking
     * {@code WHERE LOWER(p.handle) IN :handles} is what must be avoided: there is no
     * functional index on {@code lower(handle)} (V319 indexes the column itself), so it
     * turns every unauthenticated call into a sequential scan of the whole table. The
     * sibling read {@code findByHandle} lowercases its input for the same reason.
     *
     * <p>Returns {@code [handle, userId]} rows - handles that match nothing are simply
     * absent, so the caller cannot use it to probe which handles exist beyond what a
     * profile page would already answer.
     */
    @Query("""
            SELECT p.handle, p.userId FROM UserProfileEntity p
             WHERE p.handle IN :lowercaseHandles
            """)
    List<Object[]> findIdsByHandles(@Param("lowercaseHandles") Collection<String> lowercaseHandles);

    /**
     * The subset of {@code userIds} whose owner has withdrawn their profile page
     * (visibility PRIVATE).
     *
     * <p>Subtracted from the verified set by {@code VerifiedAccountService}: the badge is
     * a PUBLIC claim about an account, and a user who took their page down has said they
     * do not want one. Without this, the anonymous handle lookup would confirm "this
     * handle belongs to a verified (and therefore, today, an admin) account" for exactly
     * the accounts that opted out of being visible - strictly more than
     * {@code /by-handle} discloses, since that answers 404 for a PRIVATE profile.
     *
     * <p>Kept as its own query rather than a predicate inside the two positive lookups
     * because one of them ({@code findAdminIdsIn}) has no profile row to filter on: an
     * admin who never edited their profile has no row at all, and a join would drop them.
     */
    @Query("""
            SELECT p.userId FROM UserProfileEntity p
             WHERE p.userId IN :userIds
               AND p.profileVisibility = 'PRIVATE'
            """)
    List<Long> findPrivateProfileIdsIn(@Param("userIds") Collection<Long> userIds);
}
