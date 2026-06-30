package com.apimarketplace.agent.repository;

import com.apimarketplace.agent.domain.SkillBundleSyncStatusEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface SkillBundleSyncStatusRepository extends JpaRepository<SkillBundleSyncStatusEntity, Short> {
}
