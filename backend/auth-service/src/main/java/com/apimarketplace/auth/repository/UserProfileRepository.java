package com.apimarketplace.auth.repository;

import com.apimarketplace.auth.domain.UserProfileEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

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
}
