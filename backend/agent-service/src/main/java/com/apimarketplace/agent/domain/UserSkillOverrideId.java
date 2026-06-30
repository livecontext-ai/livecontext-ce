package com.apimarketplace.agent.domain;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

/**
 * Composite PK for {@link UserSkillOverrideEntity}: a per-user override of a
 * skill's default-active state. See V276 migration (2026-05-21).
 */
public class UserSkillOverrideId implements Serializable {

    private String userId;
    private UUID skillId;

    public UserSkillOverrideId() {}

    public UserSkillOverrideId(String userId, UUID skillId) {
        this.userId = userId;
        this.skillId = skillId;
    }

    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }

    public UUID getSkillId() { return skillId; }
    public void setSkillId(UUID skillId) { this.skillId = skillId; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof UserSkillOverrideId that)) return false;
        return Objects.equals(userId, that.userId)
                && Objects.equals(skillId, that.skillId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(userId, skillId);
    }
}
