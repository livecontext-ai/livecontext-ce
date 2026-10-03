package com.apimarketplace.agent.service.retention;

import com.apimarketplace.common.classification.RestrictedDataPolicy;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Removes Google restricted-scope content (Gmail, Drive) from agent observability once it is
 * older than the retention window (CASA LC-011 / LC-066). Default window: 30 days,
 * {@code data-classification.restricted.retention-days}.
 *
 * <p>The unit of redaction is the EXECUTION, selected on {@code agent_executions.data_sensitivity}
 * = RESTRICTED. {@code AgentObservabilityService} sets it when a tool call read restricted data or
 * when the producer tagged the execution (a chat turn in a conversation holding such data, an
 * agent node in a run holding it, a sub-agent of a restricted parent). Such an execution holds the
 * content in its message transcript (TOOL messages repeat results, ASSISTANT messages quote them)
 * and in its tool-call rows (a later "send" call can quote the email it read). All of them lose
 * their content, arguments, error and all but their display metadata; the execution is then marked
 * {@code REDACTED}, which is what stops the next sweep from picking it up again. Rows are kept:
 * run statistics, token counts and the timeline stay intact.
 *
 * <p>The overflow text of those rows lives in storage rows that were tagged RESTRICTED with a
 * bounded expiry when they were written, and storage-service hard-deletes them. The references are
 * cleared here so nothing points at a deleted row.
 *
 * <p>Independent of {@link AgentExecutionLogRetentionSweeper}, which applies the plan-based
 * journal window and is off / dry-run by default: this one is a data-protection control and is
 * on by default.
 */
@Component
public class RestrictedObservabilityContentPurger {

    private static final Logger log = LoggerFactory.getLogger(RestrictedObservabilityContentPurger.class);

    static final String PLACEHOLDER =
            "[Content removed: it came from Gmail or Google Drive and is kept for a limited time only.]";

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactionTemplate;
    private final boolean enabled;
    private final int retentionDays;
    private final int batchSize;

    public RestrictedObservabilityContentPurger(
            JdbcTemplate jdbc,
            TransactionTemplate transactionTemplate,
            @Value("${data-classification.restricted.sweep.enabled:true}") boolean enabled,
            @Value("${data-classification.restricted.retention-days:" + RestrictedDataPolicy.DEFAULT_RETENTION_DAYS + "}") int retentionDays,
            @Value("${data-classification.restricted.sweep.batch-size:200}") int batchSize) {
        this.jdbc = jdbc;
        this.transactionTemplate = transactionTemplate;
        this.enabled = enabled;
        this.retentionDays = Math.max(1, retentionDays);
        this.batchSize = Math.max(1, batchSize);
    }

    public record PurgeReport(int executions, int messagesRedacted, int toolCallsRedacted) {
    }

    @Scheduled(fixedDelayString = "${data-classification.restricted.sweep.interval-ms:900000}",
            initialDelayString = "${data-classification.restricted.sweep.initial-delay-ms:120000}")
    @SchedulerLock(name = "restricted_observability_content_purge", lockAtMostFor = "PT30M")
    public void scheduledPurge() {
        try {
            purge(Instant.now());
        } catch (Exception e) {
            log.error("[RestrictedRetention] observability purge failed: {}", e.getMessage(), e);
        }
    }

    public PurgeReport purge(Instant now) {
        if (!enabled) {
            return new PurgeReport(0, 0, 0);
        }
        Timestamp cutoff = Timestamp.from(now.minus(Duration.ofDays(retentionDays)));
        int executions = 0;
        int messages = 0;
        int toolCalls = 0;
        for (int round = 0; round < 1000; round++) {
            List<UUID> batch = jdbc.queryForList(
                    "SELECT id FROM agent.agent_executions "
                            + "WHERE data_sensitivity = 'RESTRICTED' AND created_at < ? LIMIT ?",
                    UUID.class, cutoff, batchSize);
            if (batch.isEmpty()) {
                break;
            }
            for (UUID executionId : batch) {
                int[] counts = transactionTemplate.execute(status -> new int[] {
                        // Content first; the execution tag, which is what selects it, is flipped
                        // last, in the same transaction.
                        jdbc.update("UPDATE agent.agent_execution_messages "
                                        + "SET content = ?, content_storage_id = NULL "
                                        + "WHERE execution_id = ? AND (content IS NOT NULL OR content_storage_id IS NOT NULL)",
                                PLACEHOLDER, executionId),
                        jdbc.update("UPDATE agent.agent_execution_tool_calls "
                                        + "SET content = ?, content_storage_id = NULL, arguments = NULL, error_message = NULL, "
                                        + "metadata = jsonb_strip_nulls(jsonb_build_object("
                                        + "'iconSlug', metadata -> 'iconSlug', 'toolName', metadata -> 'toolName', "
                                        + "'contentRedacted', true)), "
                                        + "data_sensitivity = 'REDACTED' "
                                        + "WHERE execution_id = ? AND data_sensitivity <> 'REDACTED'",
                                PLACEHOLDER, executionId),
                        jdbc.update("UPDATE agent.agent_executions SET data_sensitivity = 'REDACTED' WHERE id = ?",
                                executionId)
                });
                executions++;
                if (counts != null) {
                    messages += counts[0];
                    toolCalls += counts[1];
                }
            }
            if (batch.size() < batchSize) {
                break;
            }
        }
        if (executions > 0) {
            log.info("[RestrictedRetention] redacted {} execution(s): {} message(s), {} tool call(s) older than {} days",
                    executions, messages, toolCalls, retentionDays);
        }
        return new PurgeReport(executions, messages, toolCalls);
    }
}
