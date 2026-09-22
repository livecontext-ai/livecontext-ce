package com.apimarketplace.agent.security;

import org.springframework.beans.factory.annotation.Value;
import java.time.Duration;

import com.apimarketplace.common.security.token.PlaintextTokenBackfill;
import com.apimarketplace.common.security.token.PlaintextTokenBackfill.TableSpec;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Moves agent webhook tokens and widget tokens from plaintext to encrypted + hashed storage
 * (see {@code TokenAtRest}): a delayed startup pass, plus a read-only plaintext fallback for lookups meanwhile.
 */
@Component
public class AgentTokenAtRestBackfill {

    public static final TableSpec WEBHOOK_TOKENS =
            new TableSpec("agent.agent_webhook_tokens", "id", "token", "token_hash");
    public static final TableSpec WIDGET_CONFIGS =
            new TableSpec("agent.agent_widget_configs", "id", "widget_token", "widget_token_hash");

    static final List<TableSpec> TABLES = List.of(WEBHOOK_TOKENS, WIDGET_CONFIGS);

    private final PlaintextTokenBackfill backfill;
    private final Duration startupDelay;

    /**
     * @param startupDelaySeconds how long after readiness the one-way rewrite starts. The
     *        default outlasts a rolling deploy, during which replicas on the previous image
     *        can still resolve a plaintext token but not a rewritten one; the new image reads
     *        legacy rows through {@link #findLegacy} meanwhile. 0 = inline (tests, single CE box).
     */
    public AgentTokenAtRestBackfill(JdbcTemplate jdbc,
            @Value("${token-at-rest.backfill.delay-seconds:600}") long startupDelaySeconds) {
        this.backfill = new PlaintextTokenBackfill(jdbc);
        this.startupDelay = Duration.ofSeconds(startupDelaySeconds);
    }

    @EventListener(ApplicationReadyEvent.class)
    public void migrateOnStartup() {
        backfill.scheduleMigrateAll(TABLES, startupDelay);
    }

    /** True until a pass has proven the table free of plaintext rows (gates the legacy fallback). */
    public boolean mayHaveLegacyRows(TableSpec table) {
        return backfill.mayHaveLegacyRows(table);
    }

    /** The read-only plaintext fallback of a lookup; the gate itself lives in {@link PlaintextTokenBackfill}. */
    public <T> java.util.Optional<T> findLegacy(TableSpec table, String plaintext,
                                                java.util.function.Function<String, java.util.Optional<T>> query) {
        return backfill.findLegacy(table, plaintext, query);
    }

    /** Drop a pass that has not started when the context closes (see PlaintextTokenBackfill.cancelPending). */
    @jakarta.annotation.PreDestroy
    public void cancelPendingBackfill() {
        backfill.cancelPending();
    }

    /** Write-path heal: rewrite the one row still holding {@code plaintext} so a hash-keyed UPDATE can reach it. */
    public boolean heal(TableSpec table, String plaintext) {
        return backfill.migrateByPlaintext(table, plaintext);
    }
}
