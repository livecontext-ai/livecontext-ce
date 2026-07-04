package com.apimarketplace.agent.repository;

import com.apimarketplace.agent.domain.ModelSeedStateEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ModelSeedStateRepository
        extends JpaRepository<ModelSeedStateEntity, Short> {
}
