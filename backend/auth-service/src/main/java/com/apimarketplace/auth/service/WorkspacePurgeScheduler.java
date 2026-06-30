package com.apimarketplace.auth.service;

import com.apimarketplace.auth.domain.Organization;
import com.apimarketplace.auth.repository.OrganizationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Daily cron that finds soft-deleted workspaces past the grace window and delegates the
 * hard-purge to {@link WorkspacePurgeService}. Separated so the {@code @Transactional} proxy
 * on the purge service works (no self-invocation bypass) - mirrors {@link AccountPurgeScheduler}.
 *
 * <p>Grace is configurable via {@code workspace.purge.grace-days} (default 30) so e2e can run
 * with a short window; the cron schedule via {@code workspace.purge.cron}.
 */
@Component
public class WorkspacePurgeScheduler {

    private static final Logger log = LoggerFactory.getLogger(WorkspacePurgeScheduler.class);

    private final OrganizationRepository organizationRepository;
    private final WorkspacePurgeService purgeService;

    @Value("${workspace.purge.grace-days:30}")
    private int graceDays;

    public WorkspacePurgeScheduler(OrganizationRepository organizationRepository,
                                   WorkspacePurgeService purgeService) {
        this.organizationRepository = organizationRepository;
        this.purgeService = purgeService;
    }

    @Scheduled(cron = "${workspace.purge.cron:0 30 3 * * *}", zone = "UTC")
    public void purgeExpiredWorkspaces() {
        LocalDateTime cutoff = LocalDateTime.now().minusDays(graceDays);
        List<Organization> expired = organizationRepository.findWorkspacesPastGracePeriod(cutoff);

        if (expired.isEmpty()) {
            log.debug("Workspace purge: no workspaces past the {}-day grace period", graceDays);
            return;
        }

        log.info("Workspace purge: found {} workspaces past the {}-day grace period", expired.size(), graceDays);
        for (Organization org : expired) {
            try {
                purgeService.purgeWorkspace(org.getId());
            } catch (Exception e) {
                log.error("Workspace purge: failed to purge workspace {}", org.getId(), e);
            }
        }
    }
}
