package com.apimarketplace.agent.repository;

import com.apimarketplace.agent.domain.ModelProviderSettingsEntity;
import org.springframework.data.jpa.repository.JpaRepository;

/** Provider-level enable switch. Only exceptions are stored: no row means enabled. */
public interface ModelProviderSettingsRepository
        extends JpaRepository<ModelProviderSettingsEntity, String> {
}
