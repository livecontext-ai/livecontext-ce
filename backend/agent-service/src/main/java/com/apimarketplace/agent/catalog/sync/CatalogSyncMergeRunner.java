package com.apimarketplace.agent.catalog.sync;

import com.apimarketplace.agent.catalog.bundle.CatalogMergeService;
import com.apimarketplace.agent.catalog.bundle.MergeOptions;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/**
 * Runs {@link CatalogMergeService#merge} in a dedicated transaction ({@link
 * Propagation#REQUIRES_NEW}) so a merge failure does NOT poison the enclosing
 * {@link ModelCatalogSyncService#sync} transaction with {@code rollback-only}.
 *
 * <p>Context:
 * <ul>
 *   <li>{@code sync} is {@code @Transactional} so the write-log row insert
 *       rides on the same commit. That outer TX must be able to commit even
 *       if the merge step fails - the caller wants to persist an APPLY_ERROR
 *       log row and return a clean response.</li>
 *   <li>{@code CatalogMergeService} is intentionally not {@code @Transactional}
 *       itself; it inherits the caller's TX. Without this helper, the merge
 *       joined {@code sync}'s TX and any runtime exception marked the outer TX
 *       as rollback-only → Spring threw {@code UnexpectedRollbackException}
 *       ("Transaction silently rolled back because it has been marked as
 *       rollback-only") on method exit even though {@code sync} caught the
 *       exception.</li>
 * </ul>
 *
 * <p>With {@link Propagation#REQUIRES_NEW} the merge runs in a suspended-parent
 * child TX. If it throws, only the child rolls back; control returns to
 * {@code sync} and its TX stays live. The {@code afterCommit} pricing mirror
 * hook fires when the child TX commits (on success), which is what we want.
 */
@Component
@RequiredArgsConstructor
public class CatalogSyncMergeRunner {

    private final CatalogMergeService mergeService;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public CatalogMergeService.MergeResult merge(List<Map<String, Object>> models, MergeOptions opts) {
        return mergeService.merge(models, opts);
    }
}
