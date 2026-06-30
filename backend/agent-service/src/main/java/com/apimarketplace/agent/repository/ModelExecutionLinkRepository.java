package com.apimarketplace.agent.repository;

import com.apimarketplace.agent.domain.ModelExecutionLinkEntity;
import com.apimarketplace.agent.domain.ModelExecutionLinkScope;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ModelExecutionLinkRepository extends JpaRepository<ModelExecutionLinkEntity, Long> {

    // The natural key is (billed_provider, billed_model, scope): one route per billed
    // pair PER surface, so lookups/deletes must include the scope.
    Optional<ModelExecutionLinkEntity> findByBilledProviderAndBilledModelAndScope(
            String billedProvider, String billedModel, ModelExecutionLinkScope scope);

    List<ModelExecutionLinkEntity> findAllByOrderByBilledProviderAscBilledModelAscScopeAsc();

    void deleteByBilledProviderAndBilledModelAndScope(
            String billedProvider, String billedModel, ModelExecutionLinkScope scope);
}
