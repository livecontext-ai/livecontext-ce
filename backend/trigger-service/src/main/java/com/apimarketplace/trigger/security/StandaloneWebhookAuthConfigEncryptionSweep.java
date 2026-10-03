package com.apimarketplace.trigger.security;

import com.apimarketplace.common.security.CredentialEncryptionService;
import com.apimarketplace.common.security.SensitiveJsonbBackfill;
import com.apimarketplace.common.security.SensitiveJsonbBackfill.ColumnSpec;
import com.apimarketplace.common.security.SensitiveJsonbBackfill.TableSpec;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;

/**
 * Re-encryption sweep for {@code trigger.standalone_webhooks.auth_config} (basicPassword, authHeaderValue, jwtSecretKey written by StandaloneWebhookService).
 *
 * <p>Once per start, after the rollout delay, re-encrypts in place (CASA LC-024): a secret still
 * in clear, and at {@code credential.encryption.write-version=2} a value still in the legacy v1
 * envelope or sealed under a previous key id. Rows already in the current format are not touched.
 * This service only touches its own schema; see {@link SensitiveJsonbBackfill} for the rules.
 */
@Component
public class StandaloneWebhookAuthConfigEncryptionSweep {

    /** Exactly the keys the service encrypts on write and decrypts on read (nothing else is touched). */
    static final java.util.Set<String> FIELDS = java.util.Set.of("basicPassword", "authHeaderValue", "jwtSecretKey");

    static final List<TableSpec> TABLES = List.of(new TableSpec("trigger.standalone_webhooks", "id", "auth_config", FIELDS));
    static final List<ColumnSpec> COLUMNS = List.of();

    private final SensitiveJsonbBackfill sweep;
    private final Duration startupDelay;

    public StandaloneWebhookAuthConfigEncryptionSweep(JdbcTemplate jdbc, ObjectMapper objectMapper, CredentialEncryptionService encryptionService,
            @Value("${token-at-rest.backfill.delay-seconds:600}") long startupDelaySeconds) {
        this.sweep = new SensitiveJsonbBackfill(jdbc, objectMapper, encryptionService);
        this.startupDelay = Duration.ofSeconds(startupDelaySeconds);
    }

    @EventListener(ApplicationReadyEvent.class)
    public void migrateOnStartup() {
        sweep.scheduleMigrateAll(TABLES, COLUMNS, startupDelay);
    }

    /** Drop a sweep that has not started when the context closes. */
    @jakarta.annotation.PreDestroy
    public void cancelPendingSweep() {
        sweep.cancelPending();
    }
}
