package com.apimarketplace.conversation.purge;

import com.apimarketplace.common.classification.RestrictedDataPolicy;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Classifies chat content written BEFORE data classification existed (CASA LC-066 backlog), so the
 * 30-day retention and the provider allow-list also cover it. Idempotent: every step only touches
 * rows still {@code NORMAL} that match, so a re-run finds nothing left to do. Batched by
 * primary-key keyset, one short statement per batch.
 *
 * <ol>
 *   <li>tool results whose metadata names a restricted integration (iconSlug) or carries the tag;</li>
 *   <li>non-user messages of a conversation from the first restricted tool result on (the same
 *       rule as {@code MessageService.classify} at write time), and any message whose tool-call
 *       JSON names a restricted integration.</li>
 * </ol>
 */
@Component
public class RestrictedConversationBackfill {

    private static final Logger log = LoggerFactory.getLogger(RestrictedConversationBackfill.class);

    private final JdbcTemplate jdbc;
    private final boolean enabled;
    private final int batchSize;

    public RestrictedConversationBackfill(
            JdbcTemplate jdbc,
            @Value("${data-classification.restricted.backfill.enabled:true}") boolean enabled,
            @Value("${data-classification.restricted.backfill.batch-size:1000}") int batchSize) {
        this.jdbc = jdbc;
        this.enabled = enabled;
        this.batchSize = Math.max(1, batchSize);
    }

    public record Report(int toolResults, int messages) {
    }

    @Scheduled(initialDelayString = "${data-classification.restricted.backfill.initial-delay-ms:300000}",
            fixedDelayString = "${data-classification.restricted.backfill.interval-ms:86400000}")
    @SchedulerLock(name = "restricted_conversation_backfill", lockAtMostFor = "PT2H", lockAtLeastFor = "PT10M")
    public void scheduledBackfill() {
        try {
            run();
        } catch (Exception e) {
            log.error("[RestrictedBackfill] conversation backfill failed: {}", e.getMessage(), e);
        }
    }

    public Report run() {
        if (!enabled) {
            return new Report(0, 0);
        }
        String integrations = sqlList();
        int toolResults = keyset("conversation.tool_results", "uuid",
                "UPDATE conversation.tool_results SET data_sensitivity = 'RESTRICTED' "
                        + "WHERE id = ANY(CAST(? AS uuid[])) AND data_sensitivity = 'NORMAL' "
                        + "AND (lower(COALESCE(metadata ->> 'iconSlug', '')) IN (" + integrations + ") "
                        + "  OR metadata ->> '__dataSensitivity__' = 'RESTRICTED')");
        int messages = keyset("conversation.messages", "varchar",
                "UPDATE conversation.messages m SET data_sensitivity = 'RESTRICTED' "
                        + "WHERE m.id = ANY(CAST(? AS varchar[])) AND m.data_sensitivity = 'NORMAL' "
                        + "AND ((upper(m.role) <> 'USER' AND EXISTS (SELECT 1 FROM conversation.tool_results t "
                        + "      WHERE t.conversation_id = m.conversation_id "
                        + "      AND t.data_sensitivity IN ('RESTRICTED', 'REDACTED') "
                        + "      AND t.created_at <= m.created_at)) "
                        + "  OR m.tool_calls ~* ('\"iconSlug\"\\s*:\\s*\"(" + String.join("|",
                                RestrictedDataPolicy.RESTRICTED_INTEGRATIONS) + ")\"'))");
        if (toolResults + messages > 0) {
            log.info("[RestrictedBackfill] conversation: tagged {} tool result(s), {} message(s)", toolResults, messages);
        }
        return new Report(toolResults, messages);
    }

    private int keyset(String table, String idType, String update) {
        int updated = 0;
        String after = null;
        while (true) {
            List<String> ids = after == null
                    ? jdbc.queryForList("SELECT id::text FROM " + table + " ORDER BY id LIMIT ?", String.class, batchSize)
                    : jdbc.queryForList("SELECT id::text FROM " + table + " WHERE id > CAST(? AS " + idType + ") "
                            + "ORDER BY id LIMIT ?", String.class, after, batchSize);
            if (ids.isEmpty()) {
                return updated;
            }
            updated += jdbc.update(update, (Object) ids.toArray(new String[0]));
            after = ids.get(ids.size() - 1);
            if (ids.size() < batchSize) {
                return updated;
            }
        }
    }

    private static String sqlList() {
        StringBuilder sb = new StringBuilder();
        for (String v : RestrictedDataPolicy.RESTRICTED_INTEGRATIONS) {
            if (!v.matches("[a-z0-9_]+")) {
                throw new IllegalStateException("Unexpected integration identifier: " + v);
            }
            sb.append(sb.length() > 0 ? "," : "").append('\'').append(v).append('\'');
        }
        return sb.toString();
    }
}
