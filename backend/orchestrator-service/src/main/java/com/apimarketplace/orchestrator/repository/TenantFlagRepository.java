package com.apimarketplace.orchestrator.repository;

import com.apimarketplace.orchestrator.domain.TenantFlagEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface TenantFlagRepository extends JpaRepository<TenantFlagEntity, TenantFlagEntity.PK> {

    /**
     * All flag rows - used at startup to warm the in-memory cache. With
     * cardinality bounded ~1000 rows in steady state this is a cheap one-shot
     * scan; avoid invoking on the hot path.
     */
    @Override
    List<TenantFlagEntity> findAll();

    /**
     * All flags currently ON for the named flag - used by admin diagnostics
     * ("which tenants have feature X enabled?"). Bounded by tenant cardinality.
     */
    List<TenantFlagEntity> findByFlagNameAndValueTrue(String flagName);
}
