package com.apimarketplace.conversation.purge;

import com.apimarketplace.common.classification.RestrictedDataPolicy;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Removes Google restricted-scope content (Gmail, Drive) from chat history once it is older than
 * the retention window (CASA LC-011 / LC-066). Default window: 30 days,
 * {@code data-classification.restricted.retention-days}.
 *
 * <p><b>Redaction, not row deletion.</b> A tool result or an assistant message is part of a
 * conversation's structure: the next turn's history is rebuilt from it, and the UI renders the
 * tool-call card. Deleting the row would break both. The CONTENT is what Limited Use requires
 * the platform not to keep, so the content goes and a short placeholder stays:
 * <ul>
 *   <li>{@code tool_results}: {@code content_full} and {@code error_message} are nulled, the
 *       preview becomes the placeholder, and the metadata keeps only its display keys;</li>
 *   <li>{@code messages} (the assistant text of the same turn, which quotes the content): the
 *       text becomes the placeholder and each tool-call entry loses its arguments, error and
 *       visualization, which can carry the same data (a Gmail search expression, a rendered
 *       email list).</li>
 * </ul>
 * A redacted row is marked {@code REDACTED} so it is never processed twice and leaves the partial
 * index the sweep reads. Its search vector is recomputed by PostgreSQL from the placeholder.
 *
 * <p><b>The cold summary goes too.</b> {@code conversations.summary_cold} is written from the
 * conversation's history, so it restates the same content. It used to survive the purge, and once
 * the rows were REDACTED the conversation no longer read as restricted, so that summary was
 * injected into later turns sent to any provider. The summary of every conversation a pass redacts
 * is cleared by the SAME statement that redacts its rows, so nothing (a crash, a failed statement,
 * a row tagged between two steps) can leave a redacted conversation with its old summary; the next
 * compaction rebuilds it from the redacted history. A compaction already running while its rows
 * are redacted cannot write the content back either: it summarises restricted messages this close
 * to the limit as already removed ({@code ChatCompactionOrchestrator}).
 */
@Component
public class RestrictedConversationContentPurger {

    private static final Logger log = LoggerFactory.getLogger(RestrictedConversationContentPurger.class);

    /** Stored in place of the removed content. Written for the person reading the history. */
    public static final String PLACEHOLDER =
            "[Content removed: it came from Gmail or Google Drive and is kept for "
                    + "a limited time only.]";

    /** Keys of a tool-call entry that can carry restricted content. */
    static final List<String> TOOL_CALL_CONTENT_KEYS = List.of("arguments", "error", "visualization", "label");

    private static final TypeReference<List<Map<String, Object>>> TOOL_CALLS = new TypeReference<>() {
    };

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final boolean enabled;
    private final int retentionDays;
    private final int batchSize;

    public RestrictedConversationContentPurger(
            JdbcTemplate jdbc,
            ObjectMapper objectMapper,
            @Value("${data-classification.restricted.sweep.enabled:true}") boolean enabled,
            @Value("${data-classification.restricted.retention-days:" + RestrictedDataPolicy.DEFAULT_RETENTION_DAYS + "}") int retentionDays,
            @Value("${data-classification.restricted.sweep.batch-size:500}") int batchSize) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.enabled = enabled;
        this.retentionDays = Math.max(1, retentionDays);
        this.batchSize = Math.max(1, batchSize);
    }

    public record PurgeReport(int toolResultsRedacted, int messagesRedacted, int summariesCleared) {

        /** The two-count shape callers had before summaries were cleared too. */
        public PurgeReport(int toolResultsRedacted, int messagesRedacted) {
            this(toolResultsRedacted, messagesRedacted, 0);
        }
    }

    @Scheduled(fixedDelayString = "${data-classification.restricted.sweep.interval-ms:900000}",
            initialDelayString = "${data-classification.restricted.sweep.initial-delay-ms:120000}")
    @SchedulerLock(name = "restricted_conversation_content_purge", lockAtMostFor = "PT30M")
    public void scheduledPurge() {
        try {
            purge(Instant.now());
        } catch (Exception e) {
            log.error("[RestrictedRetention] conversation purge failed: {}", e.getMessage(), e);
        }
    }

    /** Redacts every restricted row created before {@code now - window}. */
    public PurgeReport purge(Instant now) {
        if (!enabled) {
            return new PurgeReport(0, 0);
        }
        Timestamp cutoff = Timestamp.from(now.minus(Duration.ofDays(retentionDays)));

        int toolResults = 0;
        int summaries = 0;
        for (int round = 0; round < 1000; round++) {
            Counts counts = redactAndClearSummaries(
                    "UPDATE conversation.tool_results SET content_full = NULL, error_message = NULL, "
                            + "content_preview = ?, "
                            + "metadata = jsonb_strip_nulls(jsonb_build_object("
                            + "'iconSlug', metadata -> 'iconSlug', 'toolName', metadata -> 'toolName', "
                            + "'displayToolName', metadata -> 'displayToolName', 'contentRedacted', true)), "
                            + "data_sensitivity = 'REDACTED' "
                            + "WHERE id IN (SELECT id FROM conversation.tool_results "
                            + "WHERE data_sensitivity = 'RESTRICTED' AND created_at < ? LIMIT ?)",
                    PLACEHOLDER, cutoff, batchSize);
            toolResults += counts.redacted();
            summaries += counts.summariesCleared();
            if (counts.redacted() < batchSize) {
                break;
            }
        }

        int messages = 0;
        for (int round = 0; round < 1000; round++) {
            List<Map<String, Object>> batch = jdbc.queryForList(
                    "SELECT id, tool_calls FROM conversation.messages "
                            + "WHERE data_sensitivity = 'RESTRICTED' AND created_at < ? LIMIT ?",
                    cutoff, batchSize);
            if (batch.isEmpty()) {
                break;
            }
            for (Map<String, Object> row : batch) {
                Counts counts = redactAndClearSummaries(
                        "UPDATE conversation.messages SET content = ?, tool_calls = ?, "
                                + "data_sensitivity = 'REDACTED' "
                                + "WHERE id = ? AND data_sensitivity = 'RESTRICTED'",
                        PLACEHOLDER, redactToolCalls((String) row.get("tool_calls")), row.get("id"));
                messages += counts.redacted();
                summaries += counts.summariesCleared();
            }
            if (batch.size() < batchSize) {
                break;
            }
        }

        if (toolResults > 0 || messages > 0 || summaries > 0) {
            log.info("[RestrictedRetention] redacted {} tool result(s) and {} message(s) older than {} days, "
                    + "cleared {} conversation summary(ies)", toolResults, messages, retentionDays, summaries);
        }
        return new PurgeReport(toolResults, messages, summaries);
    }

    record Counts(int redacted, int summariesCleared) {
    }

    /**
     * Runs {@code redaction} (an UPDATE of conversation rows) and clears the cold summary of every
     * conversation it touched, as ONE statement: the two data-modifying CTEs commit or fail together.
     */
    private Counts redactAndClearSummaries(String redaction, Object... args) {
        Map<String, Object> counts = jdbc.queryForMap(
                "WITH redacted AS (" + redaction + " RETURNING conversation_id), "
                        + "cleared AS (UPDATE conversation.conversations SET summary_cold = NULL "
                        + "WHERE summary_cold IS NOT NULL AND id IN (SELECT conversation_id FROM redacted) "
                        + "RETURNING id) "
                        + "SELECT (SELECT count(*) FROM redacted) AS redacted, "
                        + "(SELECT count(*) FROM cleared) AS cleared",
                args);
        return new Counts(((Number) counts.get("redacted")).intValue(),
                ((Number) counts.get("cleared")).intValue());
    }

    /**
     * The tool-call JSON of a message with every content-bearing key removed. Unparseable JSON
     * is dropped entirely: keeping text we cannot inspect would defeat the purge.
     */
    String redactToolCalls(String toolCallsJson) {
        if (toolCallsJson == null || toolCallsJson.isBlank()) {
            return toolCallsJson;
        }
        try {
            List<Map<String, Object>> entries = objectMapper.readValue(toolCallsJson, TOOL_CALLS);
            for (Map<String, Object> entry : entries) {
                if (entry == null) {
                    continue;
                }
                TOOL_CALL_CONTENT_KEYS.forEach(entry::remove);
                if (entry.get("thinkingMessage") != null) {
                    entry.put("thinkingMessage", PLACEHOLDER);
                }
                if (entry.get("content") != null) {
                    entry.put("content", PLACEHOLDER);
                }
            }
            return objectMapper.writeValueAsString(entries);
        } catch (Exception e) {
            return null;
        }
    }
}
