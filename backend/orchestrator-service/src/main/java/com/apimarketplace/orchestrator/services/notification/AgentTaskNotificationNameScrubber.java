package com.apimarketplace.orchestrator.services.notification;

import com.apimarketplace.agent.client.AgentClient;
import com.apimarketplace.orchestrator.repository.NotificationRepository;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * CASA LC-066: withholds the task's name on AGENT_TASK notifications whose task is RESTRICTED.
 *
 * <p>agent-service no longer copies a RESTRICTED task's title (which may quote an email) into a
 * notification: it writes {@code restricted: true} instead of {@code subjectName}. Notifications
 * stored before that release, and those of a task that became RESTRICTED after they were emitted,
 * still carry the title, and a notification row is untagged and delivered as it is (the bell, the
 * email, the chat digest). This job rewrites them to the withheld form.
 *
 * <p>Whether a task is RESTRICTED is asked of agent-service (ids in, ids out), never read across
 * schemas. Keyset batches over the notifications that still carry a name; a task that cannot be
 * asked about is left as it is and asked again on the next run. Idempotent: a scrubbed row no
 * longer carries a name, so it is never selected again. One replica at a time (ShedLock on
 * orchestrator.shedlock); 5 minutes after boot, then daily. Notifications are purged after 30 days
 * ({@link NotificationRetentionPurgeService}), which bounds every run.
 */
@Service
public class AgentTaskNotificationNameScrubber {

    private static final Logger log = LoggerFactory.getLogger(AgentTaskNotificationNameScrubber.class);

    /** Notifications read per batch; also the most task ids sent to agent-service in one call. */
    static final int BATCH_SIZE = 200;
    /** Safety bound on one run (the 30-day retention keeps the table far below it). */
    private static final int MAX_BATCHES = 5_000;

    private final NotificationRepository notificationRepository;
    private final AgentClient agentClient;
    private final boolean enabled;

    public AgentTaskNotificationNameScrubber(NotificationRepository notificationRepository,
                                             AgentClient agentClient,
                                             @Value("${notifications.agent-task-name-scrub.enabled:true}") boolean enabled) {
        this.notificationRepository = notificationRepository;
        this.agentClient = agentClient;
        this.enabled = enabled;
    }

    /** What one run did. */
    public record Report(int notificationsChecked, int notificationsScrubbed, int batchesFailed) {
    }

    @Scheduled(initialDelayString = "${notifications.agent-task-name-scrub.initial-delay-ms:300000}",
            fixedDelayString = "${notifications.agent-task-name-scrub.interval-ms:86400000}")
    @SchedulerLock(name = "agent-task-notification-name-scrub", lockAtMostFor = "PT30M", lockAtLeastFor = "PT1M")
    public void scheduledScrub() {
        try {
            scrub();
        } catch (Exception e) {
            log.error("[AgentTaskNotificationScrub] run failed: {}", e.getMessage(), e);
        }
    }

    public Report scrub() {
        if (!enabled) {
            return new Report(0, 0, 0);
        }
        int checked = 0;
        int scrubbed = 0;
        int failed = 0;
        long afterId = 0L;
        for (int batch = 0; batch < MAX_BATCHES; batch++) {
            List<Object[]> rows = notificationRepository.findNamedAgentTaskNotifications(afterId, BATCH_SIZE);
            if (rows == null || rows.isEmpty()) {
                break;
            }
            afterId = ((Number) rows.get(rows.size() - 1)[0]).longValue();
            checked += rows.size();
            Set<UUID> taskIds = new LinkedHashSet<>();
            for (Object[] row : rows) {
                UUID taskId = asUuid(row[1]);
                if (taskId != null) {
                    taskIds.add(taskId);
                }
            }
            try {
                Set<UUID> restricted = agentClient.findRestrictedTaskIds(taskIds);
                if (!restricted.isEmpty()) {
                    scrubbed += notificationRepository.withholdAgentTaskNames(new ArrayList<>(restricted));
                }
            } catch (Exception e) {
                // Left as it is: the next run asks again. Never "not restricted" by default.
                failed++;
                log.warn("[AgentTaskNotificationScrub] could not check {} task(s), left for the next run: {}",
                        taskIds.size(), e.getMessage());
            }
            if (rows.size() < BATCH_SIZE) {
                break;
            }
        }
        if (scrubbed > 0 || failed > 0) {
            log.info("[AgentTaskNotificationScrub] checked {} named task notification(s): withheld the RESTRICTED "
                    + "task's name on {}, {} batch(es) left for the next run", checked, scrubbed, failed);
        }
        return new Report(checked, scrubbed, failed);
    }

    private static UUID asUuid(Object value) {
        if (value instanceof UUID uuid) {
            return uuid;
        }
        if (value == null) {
            return null;
        }
        try {
            return UUID.fromString(value.toString());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
