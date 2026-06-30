package com.apimarketplace.trigger.repository;

import com.apimarketplace.trigger.domain.DatasourceTriggerSubscriptionEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface DatasourceTriggerSubscriptionRepository
        extends JpaRepository<DatasourceTriggerSubscriptionEntity, Long> {

    /**
     * Fan-out query: fetch every active subscription for a given datasource.
     * Event-type match and filter evaluation happen in-memory in the service
     * layer (both are JSONB - cheap to post-filter on a handful of rows per
     * datasource, avoids the JSONB query operator overhead).
     */
    List<DatasourceTriggerSubscriptionEntity> findByDataSourceIdAndActiveTrue(Long dataSourceId);

    List<DatasourceTriggerSubscriptionEntity> findByWorkflowId(UUID workflowId);

    Optional<DatasourceTriggerSubscriptionEntity> findByWorkflowIdAndTriggerId(UUID workflowId, String triggerId);

    @Modifying
    @Query("DELETE FROM DatasourceTriggerSubscriptionEntity s WHERE s.workflowId = :workflowId")
    void deleteByWorkflowId(@Param("workflowId") UUID workflowId);

    /**
     * Prune: remove subscriptions for this workflow whose triggerId is NOT in
     * the current list. Called after sync to clean up triggers removed from
     * the plan.
     */
    @Modifying
    @Query("DELETE FROM DatasourceTriggerSubscriptionEntity s " +
           "WHERE s.workflowId = :workflowId AND s.triggerId NOT IN :currentTriggerIds")
    int deleteByWorkflowIdAndTriggerIdNotIn(@Param("workflowId") UUID workflowId,
                                            @Param("currentTriggerIds") List<String> currentTriggerIds);

    @Modifying
    @Query("UPDATE DatasourceTriggerSubscriptionEntity s SET s.active = false WHERE s.workflowId = :workflowId")
    void deactivateByWorkflowId(@Param("workflowId") UUID workflowId);

    long countByActiveTrue();
}
