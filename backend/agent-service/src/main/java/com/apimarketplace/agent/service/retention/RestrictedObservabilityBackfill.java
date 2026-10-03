package com.apimarketplace.agent.service.retention;

import com.apimarketplace.common.classification.RestrictedDataPolicy;
import com.apimarketplace.common.storage.service.StorageService;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Classifies agent observability written BEFORE data classification existed (CASA LC-066
 * backlog), so {@link RestrictedObservabilityContentPurger} also redacts it. Idempotent and
 * batched by primary-key keyset.
 *
 * <ol>
 *   <li>tool calls whose metadata names a restricted integration (iconSlug);</li>
 *   <li>executions holding such a tool call (the purger's unit);</li>
 *   <li>the overflow text rows ({@code content_storage_id}) of those executions, tagged through
 *       {@link StorageService#markRestricted} so storage-service hard-deletes them.</li>
 * </ol>
 */
@Component
public class RestrictedObservabilityBackfill {

    private static final Logger log = LoggerFactory.getLogger(RestrictedObservabilityBackfill.class);

    private final JdbcTemplate jdbc;
    private final StorageService storageService;
    private final boolean enabled;
    private final int batchSize;

    public RestrictedObservabilityBackfill(
            JdbcTemplate jdbc,
            StorageService storageService,
            @Value("${data-classification.restricted.backfill.enabled:true}") boolean enabled,
            @Value("${data-classification.restricted.backfill.batch-size:1000}") int batchSize) {
        this.jdbc = jdbc;
        this.storageService = storageService;
        this.enabled = enabled;
        this.batchSize = Math.max(1, batchSize);
    }

    public record Report(int toolCalls, int executions, int overflowRows) {
    }

    @Scheduled(initialDelayString = "${data-classification.restricted.backfill.initial-delay-ms:300000}",
            fixedDelayString = "${data-classification.restricted.backfill.interval-ms:86400000}")
    @SchedulerLock(name = "restricted_observability_backfill", lockAtMostFor = "PT2H", lockAtLeastFor = "PT10M")
    public void scheduledBackfill() {
        try {
            run();
        } catch (Exception e) {
            log.error("[RestrictedBackfill] observability backfill failed: {}", e.getMessage(), e);
        }
    }

    public Report run() {
        if (!enabled) {
            return new Report(0, 0, 0);
        }
        StringBuilder integrations = new StringBuilder();
        for (String v : RestrictedDataPolicy.RESTRICTED_INTEGRATIONS) {
            if (!v.matches("[a-z0-9_]+")) {
                throw new IllegalStateException("Unexpected integration identifier: " + v);
            }
            integrations.append(integrations.length() > 0 ? "," : "").append('\'').append(v).append('\'');
        }
        int toolCalls = keyset("agent.agent_execution_tool_calls", "bigint",
                "UPDATE agent.agent_execution_tool_calls SET data_sensitivity = 'RESTRICTED' "
                        + "WHERE id = ANY(CAST(? AS bigint[])) AND data_sensitivity = 'NORMAL' "
                        + "AND lower(COALESCE(metadata ->> 'iconSlug', '')) IN (" + integrations + ")");
        int executions = keyset("agent.agent_executions", "uuid",
                "UPDATE agent.agent_executions e SET data_sensitivity = 'RESTRICTED' "
                        + "WHERE e.id = ANY(CAST(? AS uuid[])) AND e.data_sensitivity = 'NORMAL' "
                        + "AND EXISTS (SELECT 1 FROM agent.agent_execution_tool_calls c "
                        + "  WHERE c.execution_id = e.id AND c.data_sensitivity = 'RESTRICTED')");

        // Overflow text of restricted executions, grouped by the tenant that owns the storage row.
        Map<String, List<UUID>> overflowByTenant = new LinkedHashMap<>();
        jdbc.query("SELECT m.tenant_id, m.content_storage_id FROM agent.agent_execution_messages m "
                        + "JOIN agent.agent_executions e ON e.id = m.execution_id "
                        + "WHERE e.data_sensitivity = 'RESTRICTED' AND m.content_storage_id IS NOT NULL "
                        + "UNION SELECT c.tenant_id, c.content_storage_id FROM agent.agent_execution_tool_calls c "
                        + "JOIN agent.agent_executions e ON e.id = c.execution_id "
                        + "WHERE e.data_sensitivity = 'RESTRICTED' AND c.content_storage_id IS NOT NULL",
                rs -> {
                    overflowByTenant.computeIfAbsent(rs.getString(1), k -> new ArrayList<>())
                            .add((UUID) rs.getObject(2));
                });
        int overflow = 0;
        for (Map.Entry<String, List<UUID>> entry : overflowByTenant.entrySet()) {
            List<UUID> ids = entry.getValue();
            for (int i = 0; i < ids.size(); i += batchSize) {
                overflow += storageService.markRestricted(entry.getKey(),
                        ids.subList(i, Math.min(i + batchSize, ids.size())), null);
            }
        }
        if (toolCalls + executions > 0) {
            log.info("[RestrictedBackfill] observability: tagged {} tool call(s), {} execution(s), {} overflow row(s)",
                    toolCalls, executions, overflow);
        }
        return new Report(toolCalls, executions, overflow);
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
}
