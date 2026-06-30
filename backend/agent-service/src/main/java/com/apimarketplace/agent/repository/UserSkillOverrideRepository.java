package com.apimarketplace.agent.repository;

import com.apimarketplace.agent.domain.UserSkillOverrideEntity;
import com.apimarketplace.agent.domain.UserSkillOverrideId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * V276 (2026-05-21) - repo for per-user skill default-active overrides.
 * See {@link UserSkillOverrideEntity} for the resolution rule.
 */
@Repository
public interface UserSkillOverrideRepository
        extends JpaRepository<UserSkillOverrideEntity, UserSkillOverrideId> {

    /** Single-row lookup - used by the toggle endpoint to update-vs-insert. */
    Optional<UserSkillOverrideEntity> findByUserIdAndSkillId(String userId, UUID skillId);

    /** All overrides for a user - chat-seeding hot path. Index idx_user_skill_overrides_user_id (V276) backs this. */
    List<UserSkillOverrideEntity> findByUserId(String userId);

    /** Reset (forget the user's choice - falls back to skill.is_default_active). */
    @Modifying
    @Query("DELETE FROM UserSkillOverrideEntity o WHERE o.userId = :userId AND o.skillId = :skillId")
    int deleteByUserIdAndSkillId(@Param("userId") String userId, @Param("skillId") UUID skillId);
}
